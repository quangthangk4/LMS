"""Real pgvector tests. CI supplies an isolated DB; never uses DATABASE_URL/production."""
from __future__ import annotations

import os
from pathlib import Path

import psycopg2
import pytest
from fastapi import HTTPException

from ai_gateway.chatbot_knowledge import Document, KnowledgeStore, LOCK_ID, SearchRequest, SyncRequest

class FakeTokenizer:
    def encode(self, text, add_special_tokens=True, truncation=False):
        return list(range(len(text.split()) + (2 if add_special_tokens else 0)))


class FakeModel:
    tokenizer = FakeTokenizer()
    max_seq_length = 256

    def encode(self, text, normalize_embeddings=True):
        vector = [1.0 / (768 ** 0.5)] * 768
        return vector if isinstance(text, str) else [vector[:] for _ in text]


@pytest.fixture
def store():
    url = os.getenv("CHATBOT_TEST_DATABASE_URL", "")
    if not url:
        pytest.skip("CHATBOT_TEST_DATABASE_URL must point to an isolated pgvector test database")
    if psycopg2.extensions.parse_dsn(url).get("dbname") != "chatbot_test":
        pytest.fail("Refusing to run knowledge integration tests outside database chatbot_test")
    migration = Path(__file__).resolve().parents[3] / "LMS_BE/library-bootstrap/src/main/resources/db/migration/V63__chatbot_knowledge.sql"
    with psycopg2.connect(url) as connection, connection.cursor() as cursor:
        cursor.execute("CREATE EXTENSION IF NOT EXISTS vector; CREATE SCHEMA IF NOT EXISTS ai_engine")
        cursor.execute("DROP TABLE IF EXISTS ai_engine.chatbot_knowledge_chunks; DROP TABLE IF EXISTS ai_engine.chatbot_knowledge_generations")
        cursor.execute(migration.read_text(encoding="utf-8"))
    return KnowledgeStore(url, FakeModel(), "test-embedding-768")


def request(revision, version, content="Sign in. Find a book. Request a loan."):
    return SyncRequest(revision=revision, version=version * 64,
        documents=[Document(id="borrow", language="en", title="Borrowing", content=content, source_path="/publicpage/guide")])


def test_initial_sync_noop_updates_reuse_vectors_and_retains_only_two_generations(store):
    assert store.sync(request(1, "a"))["embedded"] == 1
    assert store.sync(request(1, "a"))["changed"] is False
    assert store.sync(request(2, "b"))["embedded"] == 0
    assert store.sync(request(3, "c", "Updated borrowing instructions."))["embedded"] == 1
    with psycopg2.connect(store.database_url) as connection, connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*),COUNT(*) FILTER(WHERE active) FROM ai_engine.chatbot_knowledge_generations")
        assert cursor.fetchone() == (2, 1)
    with pytest.raises(HTTPException):
        store.sync(request(1, "a"))


def test_failed_encoding_keeps_active_generation_and_stale_versions_are_not_searched(store):
    store.sync(request(1, "a"))
    original = store.model.encode
    def failed(*args, **kwargs):
        raise RuntimeError("Simulated model failure")
    store.model.encode = failed
    with pytest.raises(RuntimeError):
        store.sync(request(2, "b", "Updated source."))
    store.model.encode = original
    assert store.status()["version"] == "a" * 64
    assert store.search(SearchRequest(query="Borrow", language="en", version="a" * 64))["hits"]
    with pytest.raises(HTTPException) as error:
        store.search(SearchRequest(query="Borrow", language="en", version="b" * 64))
    assert error.value.status_code == 409
    assert store.search(SearchRequest(query="Borrow", language="vi", version="a" * 64))["hits"] == []


def test_content_changes_require_revision_bump_and_model_changes_invalidate_vectors(store):
    store.sync(request(1, "a"))
    with pytest.raises(HTTPException) as error:
        store.sync(request(1, "b", "New content without a revision bump."))
    assert error.value.status_code == 409
    assert store.status()["version"] == "a" * 64
    replacement = KnowledgeStore(store.database_url, FakeModel(), "different-embedding-weights")
    assert replacement.status()["ready"] is False
    assert replacement.sync(request(1, "a"))["embedded"] == 1
    assert replacement.status()["ready"] is True
    assert store.status()["ready"] is False


def test_another_worker_holding_the_lock_prevents_a_second_publication(store):
    store.sync(request(1, "a"))
    with psycopg2.connect(store.database_url) as connection, connection.cursor() as cursor:
        cursor.execute("SELECT pg_advisory_lock(%s)", (LOCK_ID,))
        connection.commit()
        try:
            with pytest.raises(HTTPException) as error:
                store.sync(request(2, "b"))
            assert error.value.status_code == 409
            assert store.status()["version"] == "a" * 64
        finally:
            cursor.execute("SELECT pg_advisory_unlock(%s)", (LOCK_ID,))
