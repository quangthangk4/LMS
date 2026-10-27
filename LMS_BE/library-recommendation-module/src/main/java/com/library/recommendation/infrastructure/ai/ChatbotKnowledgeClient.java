package com.library.recommendation.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.recommendation.application.chatbot.FaqCatalog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class ChatbotKnowledgeClient {
    private final FaqCatalog catalog;
    private final RestClient client;
    private final RestClient syncClient;
    private final boolean enabled;
    private final String key;
    private final AtomicBoolean syncing = new AtomicBoolean();

    public ChatbotKnowledgeClient(FaqCatalog catalog,
        @Value("${ai.gateway.base-url:http://localhost:8001}") String baseUrl,
        @Value("${chatbot.internal-api-key:}") String key,
        @Value("${chatbot.rag-enabled:true}") boolean enabled) {
        this.catalog = catalog; this.key = key.trim(); this.enabled = enabled;
        client = client(baseUrl, 5); syncClient = client(baseUrl, 180);
    }
    private RestClient client(String baseUrl, int seconds) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1)); factory.setReadTimeout(Duration.ofSeconds(seconds));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { synchronize(); }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    @Async
    public void synchronize() {
        if (!enabled || key.isBlank() || !syncing.compareAndSet(false, true)) return;
        try {
            var status = client.get().uri("/internal/chatbot/knowledge/status").header("X-Chatbot-Service-Key", key)
                .retrieve().body(JsonNode.class);
            if (status != null && status.path("ready").asBoolean() && catalog.version().equals(status.path("version").asText())) return;
            var documents = new ArrayList<Map<String, Object>>();
            for (var entry : catalog.entries()) for (String language : List.of("vi", "en")) {
                var copy = entry.copy(language);
                documents.add(Map.of("id", entry.id(), "language", language, "title", copy.question(),
                    "content", copy.answer(), "source_path", entry.sourcePath()));
            }
            syncClient.post().uri("/internal/chatbot/knowledge/sync").header("X-Chatbot-Service-Key", key)
                .body(Map.of("revision", catalog.revision(), "version", catalog.version(), "documents", documents))
                .retrieve().toBodilessEntity();
        } catch (Exception ignored) {
            // Only technical state; never log keys, questions, content or provider response bodies.
            log.warn("Chatbot knowledge sync unavailable; current FAQ and read tools remain available");
        } finally { syncing.set(false); }
    }

    public record Retrieval(List<FaqCatalog.Entry> entries,String failure) {}
    public List<FaqCatalog.Entry> retrieve(String question, String language) { return retrieveResult(question,language).entries(); }
    public Retrieval retrieveResult(String question, String language) {
        if (!enabled || key.isBlank()) return new Retrieval(List.of(),"KNOWLEDGE_NOT_CONFIGURED");
        try {
            JsonNode result = client.post().uri("/internal/chatbot/knowledge/search").header("X-Chatbot-Service-Key", key)
                .body(Map.of("query", question, "language", language, "version", catalog.version()))
                .retrieve().body(JsonNode.class);
            if (result == null || !catalog.version().equals(result.path("version").asText())) return new Retrieval(List.of(),"KNOWLEDGE_NOT_READY");
            var textByTopic = new LinkedHashMap<String, StringBuilder>();
            for (JsonNode hit : result.path("hits")) {
                String id = hit.path("topic_id").asText();
                var entry = catalog.find(id);
                String text = hit.path("content").asText();
                double semantic = hit.path("score").asDouble(Double.NaN);
                double lexical = hit.path("lexical_score").asDouble(0);
                if (entry.isEmpty() || !entry.get().sourcePath().equals(hit.path("source_path").asText())
                    || !hit.path("score").isNumber() || !Double.isFinite(semantic) || semantic < -1.001 || semantic > 1.001
                    || !Double.isFinite(lexical) || lexical < 0 || lexical > 1.001
                    || (semantic < 0.35 && lexical < 0.15) || text.isBlank() || text.length() > 5000) continue;
                // The catalog is authoritative even if the AI gateway returns malformed/stale content.
                String normalizedSource = entry.get().copy(language).answer().replaceAll("\\s+", " ").trim();
                if (!normalizedSource.contains(text.replaceAll("\\s+", " ").trim())) continue;
                var collected = textByTopic.computeIfAbsent(id, ignored -> new StringBuilder());
                if (collected.length() + text.length() <= 6000) collected.append(text).append('\n');
            }
            var entries = new ArrayList<FaqCatalog.Entry>();
            textByTopic.forEach((id, text) -> {
                var original = catalog.find(id).orElseThrow();
                var copy = new FaqCatalog.Copy(original.copy(language).question(), text.toString().trim(), List.of());
                entries.add(new FaqCatalog.Entry(id, original.sourcePath(), copy, copy));
            });
            return new Retrieval(List.copyOf(entries),null);
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict notReady) {
            return new Retrieval(List.of(),"KNOWLEDGE_NOT_READY");
        } catch (org.springframework.web.client.ResourceAccessException timeout) {
            return new Retrieval(List.of(),"KNOWLEDGE_TIMEOUT");
        } catch (Exception ignored) { return new Retrieval(List.of(),"KNOWLEDGE_UNAVAILABLE"); }
    }
}
