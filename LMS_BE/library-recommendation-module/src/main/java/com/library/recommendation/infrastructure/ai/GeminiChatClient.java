package com.library.recommendation.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.ChatbotTool;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Native AUTO tool calling. Model text is forwarded without semantic filtering or rewriting. */
@Component
@Slf4j
public class GeminiChatClient {
    public record ToolCall(String name, JsonNode arguments, String id) {}
    public record Step(JsonNode content, List<ToolCall> calls, String text, String failure, boolean truncated) {}
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final Map<String,FaqModelCooldown> models=new LinkedHashMap<>();
    private final Clock clock;
    private volatile Instant configurationBlockedUntil=Instant.MIN;
    private final ThreadLocal<Instant> deadline=new ThreadLocal<>();
    public void startBudget(Duration duration) { deadline.set(clock.instant().plus(duration)); }
    public void endBudget() { deadline.remove(); }
    public boolean hasBudget(Duration duration) { return deadline.get()==null || clock.instant().plus(duration).isBefore(deadline.get()); }

    @Autowired
    public GeminiChatClient(ObjectMapper mapper,
        @Value("${chatbot.api-key:}") String apiKey,
        @Value("${chatbot.model:gemini-3.5-flash-lite}") String model,
        @Value("${chatbot.fallback-model:gemini-3.1-flash-lite}") String fallback) {
        this(mapper,apiKey,model,fallback,defaultBuilder(),Clock.systemUTC());
    }
    GeminiChatClient(ObjectMapper mapper,String key,String model,String fallback,RestClient.Builder builder,Clock clock) {
        this.mapper=mapper; this.apiKey=key.trim(); this.clock=clock;
        addModel(model);
        if(fallback!=null && !fallback.isBlank()) addModel(fallback);
        client=builder.baseUrl("https://generativelanguage.googleapis.com").build();
    }
    private void addModel(String name) {
        if(name==null || !name.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid chatbot model");
        models.putIfAbsent(name,new FaqModelCooldown(clock));
    }
    private static RestClient.Builder defaultBuilder() {
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return RestClient.builder().requestFactory(factory);
    }

    public Step next(String instruction,List<JsonNode> contents,List<ChatbotTool> tools) {
        var body=new LinkedHashMap<String,Object>();
        body.put("systemInstruction",Map.of("parts",List.of(Map.of("text",instruction))));
        body.put("contents",contents);
        body.put("generationConfig",Map.of("maxOutputTokens",3072));
        if(!tools.isEmpty()) {
            body.put("tools",List.of(Map.of("functionDeclarations",tools.stream().map(ChatbotTool::declaration).toList())));
            body.put("toolConfig",Map.of("functionCallingConfig",Map.of("mode","AUTO")));
        }
        var response=exchangeResult(body);
        if(response.failure()!=null) return new Step(null,List.of(),null,response.failure(),false);
        var candidate=response.response().path("candidates").path(0);
        var content=candidate.path("content");
        var parts=content.path("parts");
        String finish=candidate.path("finishReason").asText();
        if(!parts.isArray()) return new Step(null,List.of(),null,"AI_EMPTY_RESPONSE",false);
        var text=new StringBuilder();
        var calls=new ArrayList<ToolCall>();
        for(var part:parts) {
            if(part.path("thought").asBoolean()) continue;
            if(part.path("functionCall").isObject()) {
                var call=part.path("functionCall");
                if(!call.path("name").isTextual() || !call.path("args").isObject())
                    return new Step(null,List.of(),null,"AI_INVALID_TOOL_CALL",false);
                calls.add(new ToolCall(call.path("name").asText(),call.path("args"),call.hasNonNull("id")?call.path("id").asText():null));
            } else if(part.path("text").isTextual()) text.append(part.path("text").asText());
        }
        if(!calls.isEmpty() && "STOP".equals(finish)) return new Step(content,List.copyOf(calls),null,null,false);
        if(!text.toString().isBlank() && ("STOP".equals(finish) || "MAX_TOKENS".equals(finish)))
            return new Step(content,List.of(),text.toString(),null,"MAX_TOKENS".equals(finish));
        return new Step(null,List.of(),null,"STOP".equals(finish)?"AI_EMPTY_RESPONSE":"AI_GENERATION_STOPPED",false);
    }

    private record Exchange(JsonNode response,String failure) {}
    private volatile String cooldownFailure="AI_UNAVAILABLE";
    private Exchange exchangeResult(Map<String, Object> body) {
        if (apiKey.isBlank()) return new Exchange(null,"AI_NOT_CONFIGURED");
        if (clock.instant().isBefore(configurationBlockedUntil)) return new Exchange(null,"AI_CONFIGURATION_ERROR");
        try {
            for (var model : models.entrySet()) {
                if (!hasBudget(Duration.ofSeconds(1))) return new Exchange(null,"REQUEST_TIMEOUT");
                if (!model.getValue().isAvailable()) continue;
                try {
                    JsonNode response = client.post().uri("/v1beta/models/" + model.getKey() + ":generateContent")
                        .header("x-goog-api-key", apiKey).contentType(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().body(JsonNode.class);
                    // NONE, safety blocks and malformed output STOP the chain. Never answer-shop.
                    return response==null ? new Exchange(null,"AI_INVALID_RESPONSE") : new Exchange(response,null);
                } catch (RestClientResponseException failure) {
                    int status = failure.getStatusCode().value();
                    log.warn("Chatbot provider model={} HTTP={}", model.getKey(), status);
                    if (status == 429) {
                        cooldownFailure="AI_QUOTA_EXCEEDED";
                        model.getValue().pauseUntil(quotaRetryAt(failure, model.getValue()));
                    } else if (status == 404) {
                        cooldownFailure="AI_MODEL_UNAVAILABLE";
                        model.getValue().pauseUntil(clock.instant().plusSeconds(600));
                    } else if (status == 408 || status >= 500) {
                        cooldownFailure="AI_UNAVAILABLE";
                        model.getValue().pauseUntil(retryAfter(failure, clock.instant().plusSeconds(15)));
                    } else {
                        // Bad credentials/payload are not transient. Do not try the same key twice.
                        configurationBlockedUntil = clock.instant().plusSeconds(60);
                        return new Exchange(null,"AI_CONFIGURATION_ERROR");
                    }
                } catch (ResourceAccessException failure) {
                    cooldownFailure="AI_TIMEOUT";
                    log.warn("Chatbot provider model={} network unavailable", model.getKey());
                    model.getValue().pauseUntil(clock.instant().plusSeconds(15));
                }
            }
            return new Exchange(null,cooldownFailure);
        } catch (Exception ignored) {
            // Never log questions, provider bodies or exceptions containing credentials.
            return new Exchange(null,"AI_UNAVAILABLE");
        }
    }

    private Instant quotaRetryAt(RestClientResponseException failure, FaqModelCooldown cooldown) {
        Instant until = retryAfter(failure, clock.instant().plusSeconds(60));
        try {
            // Inspect structured quota metadata only; never log the body or use it as an answer.
            for (JsonNode detail : mapper.readTree(failure.getResponseBodyAsString()).path("error").path("details")) {
                for (JsonNode violation : detail.path("violations")) {
                    String metric = (violation.path("quotaMetric").asText() + violation.path("quotaId").asText())
                        .toLowerCase(java.util.Locale.ROOT);
                    if (metric.contains("perday") || metric.contains("per_day")) until = later(until, cooldown.nextDailyReset());
                }
                String delay = detail.path("retryDelay").asText();
                if (delay.matches("[0-9]+(\\.[0-9]+)?s")) {
                    long millis = new java.math.BigDecimal(delay.substring(0, delay.length() - 1))
                        .movePointRight(3).setScale(0, java.math.RoundingMode.CEILING).longValueExact();
                    until = later(until, clock.instant().plusMillis(millis));
                }
            }
        } catch (Exception ignored) { /* Unknown quota details retain conservative cooldown. */ }
        return until;
    }

    private Instant retryAfter(RestClientResponseException failure, Instant minimum) {
        String value = failure.getResponseHeaders() == null ? null : failure.getResponseHeaders().getFirst("Retry-After");
        if (value == null) return minimum;
        try {
            Instant requested = value.matches("[0-9]+") ? clock.instant().plusSeconds(Long.parseLong(value))
                : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return later(minimum, requested);
        } catch (RuntimeException ignored) { return minimum; }
    }

    private static Instant later(Instant first, Instant second) { return first.isAfter(second) ? first : second; }
}
