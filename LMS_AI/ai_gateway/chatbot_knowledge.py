"""Isolated help knowledge: no publication/user queries and no generative model calls."""
from __future__ import annotations

import hashlib
import hmac
import json
import math
import os
import re
import uuid
import unicodedata
from contextlib import contextmanager
from dataclasses import dataclass

import psycopg2
from fastapi import APIRouter, Header, HTTPException
from pgvector.psycopg2 import register_vector
from pydantic import BaseModel, ConfigDict, Field

PIPELINE = "help-v1-pyvi-224-32"
LOCK_ID = 742706301
SOURCE = re.compile(r"^/publicpage/(guide|faq|about|contact|privacy-policy)$")
SEMANTIC_MIN = 0.35
LEXICAL_MIN = 0.15


def rank_help_candidates(query: str, candidates: list[dict]) -> list[dict]:
    """Fuse cosine and lexical ranks; a Vietnamese encoder alone misses English paraphrases.

    These are candidate gates, not probabilities. The BE still verifies the source and
    grounded generation must decline questions not supported by those candidates.
    """
    if not candidates:
        return []
    from sklearn.feature_extraction.text import TfidfVectorizer

    def normalized(text):
        text = unicodedata.normalize("NFD", text.lower().replace("đ", "d"))
        return re.sub(r"\s+", " ", "".join(c for c in text if not unicodedata.combining(c))).strip()

    texts = [normalized(f"{c['title']} {c['title']} {c['content']}") for c in candidates]
    vectorizer = TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True)
    try:
        matrix = vectorizer.fit_transform(texts)
        lexical = (matrix @ vectorizer.transform([normalized(query)]).T).toarray().ravel()
    except ValueError:  # sources with no usable vocabulary
        lexical = [0.0] * len(candidates)
    # Character n-grams alone can mistake "tomorrow" for "borrow". Require an
    # actual content word as well; generic question words are not evidence.
    stop = set("how what where when which who can could should would the and with for this that have has from your you our are was not does about please toi ban cua cho lam nao the duoc voi khi neu cac mot nhung nay sao phai nhu xin minh library library74 book books sach".split())
    def terms(text):
        return {word for word in re.findall(r"[a-z0-9]+", text) if len(word) >= 3} - stop
    query_terms = terms(normalized(query))
    for i, text in enumerate(texts):
        if not query_terms.intersection(terms(text)):
            lexical[i] = 0.0
    semantic_order = sorted(range(len(candidates)), key=lambda i: candidates[i]["score"], reverse=True)
    lexical_order = sorted(range(len(candidates)), key=lambda i: lexical[i], reverse=True)
    semantic_rank = {i: rank for rank, i in enumerate(semantic_order)}
    lexical_rank = {i: rank for rank, i in enumerate(lexical_order)}
    eligible = [i for i, c in enumerate(candidates) if math.isfinite(c["score"])
                and (c["score"] >= SEMANTIC_MIN or lexical[i] >= LEXICAL_MIN)]
    order = sorted(eligible, key=lambda i: 1 / (60 + semantic_rank[i]) + 1 / (60 + lexical_rank[i]), reverse=True)[:5]
    return [{**candidates[i], "lexical_score": float(lexical[i])} for i in order]


class Document(BaseModel):
    model_config = ConfigDict(extra="forbid")
    id: str = Field(pattern=r"^[a-z_]+$", max_length=100)
    language: str = Field(pattern=r"^(vi|en)$")
    title: str = Field(min_length=1, max_length=300)
    content: str = Field(min_length=1, max_length=15000)
    source_path: str = Field(pattern=SOURCE.pattern)


class SyncRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    revision: int = Field(gt=0)
    version: str = Field(pattern=r"^[a-f0-9]{64}$")
    documents: list[Document] = Field(min_length=1, max_length=300)


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str = Field(min_length=1, max_length=500)
    language: str = Field(pattern=r"^(vi|en)$")
    version: str = Field(pattern=r"^[a-f0-9]{64}$")


@dataclass(frozen=True)
class Chunk:
    topic_id: str
    language: str
    index: int
    title: str
    source_path: str
    content: str
    model_input: str
    input_hash: str
    tokens: int


def prepare_text(text: str, language: str) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    if language == "vi":
        from pyvi import ViTokenizer
        return ViTokenizer.tokenize(text)
    return text


def split_document(document: Document, model) -> list[Chunk]:
    # Count the exact input (including title and special tokens) before encode.
    tokenizer = model.tokenizer
    budget = min(224, int(model.max_seq_length) - 8)
    if budget < 48:
        raise ValueError("Embedding model context is too small")

    def model_input(body):
        return prepare_text(f"{document.title}\n{body}", document.language)

    def count(body):
        return len(tokenizer.encode(model_input(body), add_special_tokens=True, truncation=False))

    # A sentence/step is preferred. Oversized sentences are split at word boundaries.
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+|\n+", document.content) if s.strip()]
    if not sentences or not document.title.strip():
        raise ValueError("Knowledge title and content must not be blank")
    units = []
    for sentence in sentences:
        current = ""
        for word in sentence.split():
            candidate = f"{current} {word}".strip()
            if count(candidate) > budget:
                if not current or count(word) > budget:
                    raise ValueError("An unbroken token or title exceeds the model budget")
                units.append(current)
                current = word
            else:
                current = candidate
        if current:
            units.append(current)

    bodies, current = [], []
    for unit in units:
        if current and count(" ".join(current + [unit])) > budget:
            bodies.append(" ".join(current))
            # Repeat complete trailing units only, with at most 32 overlap tokens.
            overlap = []
            for previous in reversed(current):
                trial = [previous] + overlap
                if len(tokenizer.encode(prepare_text(" ".join(trial), document.language), add_special_tokens=False)) > 32:
                    break
                overlap = trial
            current = overlap if count(" ".join(overlap + [unit])) <= budget else []
        current.append(unit)
    if current:
        bodies.append(" ".join(current))
    chunks = []
    for index, body in enumerate(bodies):
        encoded = model_input(body)
        tokens = len(tokenizer.encode(encoded, add_special_tokens=True, truncation=False))
        if tokens > budget:
            raise ValueError("Chunk exceeds tokenizer budget")
        chunks.append(Chunk(document.id, document.language, index, document.title, document.source_path,
                            body, encoded, hashlib.sha256(encoded.encode()).hexdigest(), tokens))
    return chunks


class KnowledgeStore:
    def __init__(self, database_url: str, model, model_id: str):
        self.database_url, self.model = database_url, model
        # Hugging Face records the loaded weight revision in its config. A weight or
        # context change invalidates vectors even when the configured model name stays the same.
        config = getattr(getattr(model, "_first_module", lambda: None)(), "auto_model", None)
        commit = getattr(getattr(config, "config", None), "_commit_hash", None)
        self.model_id = f"{model_id}@{commit or 'unversioned'}:context={model.max_seq_length}"

    @contextmanager
    def connection(self):
        # Dedicated connections: background encoding never shares SimpleConnectionPool across threads.
        connection = psycopg2.connect(self.database_url, connect_timeout=3)
        try:
            register_vector(connection)
            with connection.cursor() as cursor:
                cursor.execute("SET statement_timeout = '5000ms'")
            connection.commit()
            yield connection
        finally:
            connection.close()

    def status(self):
        with self.connection() as connection, connection.cursor() as cursor:
            cursor.execute("""SELECT source_revision,corpus_version,model_id,pipeline_version
                FROM ai_engine.chatbot_knowledge_generations WHERE active""")
            row = cursor.fetchone()
            return {"version": row[1] if row else None, "revision": row[0] if row else 0,
                    "ready": bool(row and row[2] == self.model_id and row[3] == PIPELINE)}

    def sync(self, request: SyncRequest):
        identities = [(d.id, d.language) for d in request.documents]
        if len(identities) != len(set(identities)):
            raise HTTPException(400, "Duplicate knowledge document")
        with self.connection() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT pg_try_advisory_lock(%s)", (LOCK_ID,))
                    if not cursor.fetchone()[0]:
                        raise HTTPException(409, "Knowledge sync is already running")
                connection.commit()  # session lock stays held without a long transaction during encode
                with connection.cursor() as cursor:
                    cursor.execute("""SELECT source_revision,corpus_version,model_id,pipeline_version
                        FROM ai_engine.chatbot_knowledge_generations WHERE active""")
                    active = cursor.fetchone()
                    if active and active[0] > request.revision:
                        raise HTTPException(409, "Older source revision cannot replace current knowledge")
                    if active and active[1:] == (request.version, self.model_id, PIPELINE):
                        return {"version": request.version, "changed": False}
                    # Detect forgotten revision bumps instead of letting old deployments overwrite newer content.
                    if active and active[0] == request.revision and active[1] != request.version:
                        raise HTTPException(409, "Increment knowledge source revision after content changes")
                    cursor.execute("""SELECT input_hash,embedding FROM ai_engine.chatbot_knowledge_chunks c
                        JOIN ai_engine.chatbot_knowledge_generations g ON g.id=c.generation_id
                        WHERE g.active AND g.model_id=%s AND g.pipeline_version=%s""", (self.model_id, PIPELINE))
                    cached = dict(cursor.fetchall())
                connection.commit()
                chunks = [chunk for document in request.documents for chunk in split_document(document, self.model)]
                missing = list(dict.fromkeys(c.model_input for c in chunks if c.input_hash not in cached))
                for offset in range(0, len(missing), 8):
                    batch = missing[offset:offset + 8]
                    vectors = self.model.encode(batch, normalize_embeddings=True)
                    for text, vector in zip(batch, vectors):
                        vector = vector.tolist() if hasattr(vector, "tolist") else list(vector)
                        if len(vector) != 768 or not all(math.isfinite(x) for x in vector):
                            raise ValueError("Knowledge vectors must contain 768 finite values")
                        cached[hashlib.sha256(text.encode()).hexdigest()] = vector
                generation = str(uuid.uuid4())
                with connection.cursor() as cursor:
                    cursor.execute("""INSERT INTO ai_engine.chatbot_knowledge_generations
                        (id,source_revision,corpus_version,model_id,pipeline_version)
                        VALUES (%s,%s,%s,%s,%s)""", (generation, request.revision, request.version, self.model_id, PIPELINE))
                    for chunk in chunks:
                        vector = cached[chunk.input_hash]
                        cursor.execute("""INSERT INTO ai_engine.chatbot_knowledge_chunks
                            (generation_id,topic_id,language,chunk_index,title,source_path,content,input_hash,token_count,embedding)
                            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::vector)""",
                            (generation, chunk.topic_id, chunk.language, chunk.index, chunk.title, chunk.source_path,
                             chunk.content, chunk.input_hash, chunk.tokens, json.dumps([float(x) for x in vector])))
                    cursor.execute("UPDATE ai_engine.chatbot_knowledge_generations SET active=FALSE WHERE active")
                    cursor.execute("UPDATE ai_engine.chatbot_knowledge_generations SET active=TRUE WHERE id=%s", (generation,))
                    cursor.execute("""DELETE FROM ai_engine.chatbot_knowledge_generations WHERE NOT active AND id NOT IN
                        (SELECT id FROM ai_engine.chatbot_knowledge_generations WHERE NOT active
                         ORDER BY created_at DESC,id DESC LIMIT 1)""")
                connection.commit()
                return {"version": request.version, "changed": True, "chunks": len(chunks), "embedded": len(missing)}
            finally:
                connection.rollback()
                # connection() closes this dedicated session and releases its advisory lock.

    def search(self, request: SearchRequest):
        text = prepare_text(request.query, request.language)
        if len(self.model.tokenizer.encode(text, add_special_tokens=True, truncation=False)) > int(self.model.max_seq_length):
            raise HTTPException(422, "Please shorten the question for semantic retrieval")
        vector = self.model.encode(text, normalize_embeddings=True)
        vector = vector.tolist() if hasattr(vector, "tolist") else list(vector)
        with self.connection() as connection, connection.cursor() as cursor:
            cursor.execute("""SELECT 1 FROM ai_engine.chatbot_knowledge_generations
                WHERE active AND corpus_version=%s AND model_id=%s AND pipeline_version=%s""",
                (request.version, self.model_id, PIPELINE))
            if cursor.fetchone() is None:
                raise HTTPException(409, "Knowledge index is not ready for the requested source version")
            cursor.execute("""SELECT c.topic_id,c.content,c.source_path,1-(c.embedding <=> %s::vector) AS score,c.title
                FROM ai_engine.chatbot_knowledge_chunks c
                JOIN ai_engine.chatbot_knowledge_generations g ON g.id=c.generation_id
                WHERE g.active AND g.corpus_version=%s AND g.model_id=%s AND g.pipeline_version=%s AND c.language=%s
                ORDER BY c.embedding <=> %s::vector LIMIT 2000""",
                (json.dumps([float(x) for x in vector]), request.version, self.model_id, PIPELINE, request.language, json.dumps([float(x) for x in vector])))
            candidates = [{"topic_id": r[0], "content": r[1], "source_path": r[2], "score": float(r[3]), "title": r[4]}
                          for r in cursor.fetchall()]
            return {"version": request.version, "hits": rank_help_candidates(request.query, candidates)}


def create_router(get_store):
    router = APIRouter(prefix="/internal/chatbot/knowledge")

    def authorize(value):
        expected = os.getenv("CHATBOT_INTERNAL_API_KEY", "").strip()
        if not expected or not value or not hmac.compare_digest(value, expected):
            raise HTTPException(403, "Knowledge service access denied")

    def store():
        try:
            return get_store()
        except Exception:
            raise HTTPException(503, "Knowledge service is not ready") from None

    @router.get("/status")
    def status(x_chatbot_service_key: str | None = Header(default=None)):
        authorize(x_chatbot_service_key)
        try:
            return store().status()
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, "Knowledge store is unavailable") from None

    @router.post("/sync")
    def sync(request: SyncRequest, x_chatbot_service_key: str | None = Header(default=None)):
        authorize(x_chatbot_service_key)
        try:
            return store().sync(request)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, "Knowledge synchronization failed") from None

    @router.post("/search")
    def search(request: SearchRequest, x_chatbot_service_key: str | None = Header(default=None)):
        authorize(x_chatbot_service_key)
        try:
            return store().search(request)
        except HTTPException:
            raise
        except Exception:
            raise HTTPException(503, "Knowledge retrieval is unavailable") from None

    return router
