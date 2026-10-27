package com.library.recommendation.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.FaqCatalog;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatbotKnowledgeClientTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicReference<String> request = new AtomicReference<>();
    HttpServer server;
    FaqCatalog catalog;
    ChatbotKnowledgeClient client;

    @BeforeEach void setup() throws Exception {
        catalog = new FaqCatalog(mapper);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new ChatbotKnowledgeClient(catalog, "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", true);
    }
    @AfterEach void close() { server.stop(0); }

    void response(String path, Object body) {
        server.createContext("/internal/chatbot/knowledge/" + path, exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("X-Chatbot-Service-Key")).isEqualTo("test-key");
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = mapper.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
    }
    Map<String, Object> hit(String text, String path, double score) {
        return Map.of("topic_id", catalog.entries().get(0).id(), "content", text, "source_path", path, "score", score);
    }

    @Test void retrievalChecksCorpusSourceAndPassesOnlyServerTextToGeneration() {
        var entry = catalog.entries().get(0);
        response("search", Map.of("version", catalog.version(), "hits", List.of(
            hit("Invented instructions", entry.sourcePath(), .99),
            hit(entry.vi().answer(), "https://evil.example", .99),
            hit(entry.vi().answer(), entry.sourcePath(), .1),
            hit(entry.vi().answer(), entry.sourcePath(), .9))));
        var result = client.retrieve("hướng dẫn", "vi");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).vi().answer()).isEqualTo(entry.vi().answer());
        assertThat(request.get()).contains(catalog.version(), "\"language\":\"vi\"").doesNotContain("userId", "token");
    }

    @Test void staleCorpusCannotBeUsedEvenWhenItsTopicIsKnown() {
        var entry = catalog.entries().get(0);
        response("search", Map.of("version", "a".repeat(64), "hits", List.of(hit(entry.vi().answer(), entry.sourcePath(), .9))));
        assertThat(client.retrieve("guidance", "vi")).isEmpty();
    }
    @Test void emptySearchAndIndexFailureHaveDifferentStates() {
        response("search",Map.of("version",catalog.version(),"hits",List.of()));
        var empty=client.retrieveResult("guidance","vi");
        assertThat(empty.entries()).isEmpty(); assertThat(empty.failure()).isNull();
        server.removeContext("/internal/chatbot/knowledge/search");
        server.createContext("/internal/chatbot/knowledge/search",exchange->{exchange.sendResponseHeaders(409,-1);exchange.close();});
        assertThat(client.retrieveResult("guidance","vi").failure()).isEqualTo("KNOWLEDGE_NOT_READY");
    }

    @Test void lexicalRetrievalStillRequiresAuthoritativeContentAndValidScores() {
        var entry = catalog.entries().get(0);
        var lexicalHit = new java.util.HashMap<>(hit(entry.en().answer(), entry.sourcePath(), .2));
        lexicalHit.put("lexical_score", .6);
        var forged = new java.util.HashMap<>(lexicalHit);
        forged.put("content", "Invented text with a high lexical score");
        response("search", Map.of("version", catalog.version(), "hits", List.of(forged, lexicalHit)));
        assertThat(client.retrieve("guidance", "en")).hasSize(1);
    }

    @Test void activeMatchingIndexDoesNotUploadOrReembedAgain() {
        response("status", Map.of("version", catalog.version(), "ready", true));
        client.synchronize();
        assertThat(request.get()).isEmpty();
    }

    @Test void outdatedIndexReceivesTheSameVersionedBilingualSourceAsTheWebsite() throws Exception {
        response("status", Map.of("ready", false));
        response("sync", Map.of("changed", true));
        client.synchronize();
        var body = mapper.readTree(request.get());
        assertThat(body.path("revision").asInt()).isEqualTo(catalog.revision());
        assertThat(body.path("version").asText()).isEqualTo(catalog.version());
        assertThat(body.path("documents").size()).isEqualTo(catalog.entries().size() * 2);
        assertThat(request.get()).doesNotContain("user_id", "accessToken", "file_url");
    }

    @Test void absentInternalKeyDisablesGatewayCalls() {
        var disabled = new ChatbotKnowledgeClient(catalog, "http://127.0.0.1:" + server.getAddress().getPort(), "", true);
        disabled.synchronize();
        assertThat(disabled.retrieve("guidance", "en")).isEmpty();
        assertThat(request.get()).isNull();
    }
}
