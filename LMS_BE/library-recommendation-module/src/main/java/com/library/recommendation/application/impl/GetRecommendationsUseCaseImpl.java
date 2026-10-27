package com.library.recommendation.application.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.GetRecommendationsUseCase;
import com.library.recommendation.dto.response.RecommendationResponse;
import com.library.recommendation.infrastructure.ai.AiGatewayService;
import com.library.recommendation.infrastructure.cache.RedisStateStore;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetRecommendationsUseCaseImpl implements GetRecommendationsUseCase {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AiGatewayService aiGatewayService;
    private final RedisStateStore state;
    private static final Duration CACHE_TTL = Duration.ofHours(8);
    private static final int POOL_SIZE = 20;
    private final ReentrantLock[] refreshLocks = java.util.stream.IntStream.range(0, 256)
        .mapToObj(i -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    private static final String FETCH_AI_RECS_SQL = """
        SELECT pub_ids::text, strategy, computed_at
        FROM ai_recommendations
        WHERE user_id = :userId
        """;

    private static final String FETCH_PUBS_BY_IDS_SQL = """
        SELECT
            p.id                                                              AS publication_id,
            p.title,
            p.cover_image_url,
            p.publication_year,
            (SELECT COUNT(*) FROM items i
             WHERE i.publication_id = p.id AND i.status = 'AVAILABLE'
               AND NOT EXISTS (SELECT 1 FROM reshelving_tasks q WHERE q.item_id=i.id AND q.status='WAITING')) AS available_items,
            COALESCE(ROUND(CAST(AVG(r.star) AS numeric), 1), 0.0)            AS rating_average,
            COUNT(DISTINCT r.id)                                              AS rating_count,
            COUNT(DISTINCT bt.id)                                             AS borrow_count,
            STRING_AGG(DISTINCT a.name, ', ')                                AS author_names
        FROM publications p
        LEFT JOIN ratings r ON r.publication_id = p.id
        LEFT JOIN publication_authors pa ON pa.publication_id = p.id
        LEFT JOIN authors a ON a.id = pa.author_id
        LEFT JOIN items bi ON bi.publication_id = p.id
        LEFT JOIN borrowing_transactions bt ON bt.item_id = bi.id
        WHERE p.id IN (:pubIds)
          AND EXISTS (
              SELECT 1 FROM items ai
              WHERE ai.publication_id = p.id AND ai.status = 'AVAILABLE'
                AND NOT EXISTS (SELECT 1 FROM reshelving_tasks q WHERE q.item_id=ai.id AND q.status='WAITING')
          )
        GROUP BY p.id, p.title, p.cover_image_url, p.publication_year
        """;

    private static final String FALLBACK_SQL = """
        SELECT
            p.id                                                              AS publication_id,
            p.title,
            p.cover_image_url,
            p.publication_year,
            (SELECT COUNT(*) FROM items i
             WHERE i.publication_id = p.id AND i.status = 'AVAILABLE'
               AND NOT EXISTS (SELECT 1 FROM reshelving_tasks q WHERE q.item_id=i.id AND q.status='WAITING')) AS available_items,
            COALESCE(ROUND(CAST(AVG(r.star) AS numeric), 1), 0.0)            AS rating_average,
            COUNT(DISTINCT r.id)                                              AS rating_count,
            COALESCE(bc.borrow_count, 0)                                      AS borrow_count,
            STRING_AGG(DISTINCT a.name, ', ')                                AS author_names
        FROM publications p
        LEFT JOIN ratings r ON r.publication_id = p.id
        LEFT JOIN publication_authors pa ON pa.publication_id = p.id
        LEFT JOIN authors a ON a.id = pa.author_id
        LEFT JOIN (
            SELECT i.publication_id, COUNT(*) AS borrow_count
            FROM borrowing_transactions bt
            JOIN items i ON i.id = bt.item_id
            GROUP BY i.publication_id
        ) bc ON bc.publication_id = p.id
        WHERE EXISTS (
            SELECT 1 FROM items ai
            WHERE ai.publication_id = p.id AND ai.status = 'AVAILABLE'
              AND NOT EXISTS (SELECT 1 FROM reshelving_tasks q WHERE q.item_id=ai.id AND q.status='WAITING')
        )
        GROUP BY p.id, p.title, p.cover_image_url, p.publication_year, bc.borrow_count
        ORDER BY COALESCE(bc.borrow_count, 0) DESC
        LIMIT :limit
        """;

    @Override
    public List<RecommendationResponse> execute(Long userId, int limit) {
        return execute(userId, null, limit);
    }

    @Override
    public List<RecommendationResponse> execute(Long userId, String faculty, int limit) {
        return load(userId, faculty, limit, false);
    }

    @Override
    public List<RecommendationResponse> refresh(Long userId, int limit) {
        return load(userId, null, limit, true);
    }

    private List<RecommendationResponse> load(Long userId, String faculty, int limit, boolean force) {
        if (userId == null || userId <= 0 || limit < 1 || limit > POOL_SIZE) throw new IllegalArgumentException("Invalid recommendation request");
        var profileRows = jdbcTemplate.queryForList("SELECT faculty,updated_at FROM users WHERE id=:userId", Map.of("userId", userId));
        String effectiveFaculty = faculty != null && !faculty.isBlank() ? faculty
            : profileRows.isEmpty() ? "" : java.util.Objects.toString(profileRows.get(0).get("faculty"), "");
        Instant profileChanged = profileRows.isEmpty() ? Instant.EPOCH : instant(profileRows.get(0).get("updated_at"));
        String key = "lms:reco:v1:" + userId + ":" + java.util.HexFormat.of().formatHex(effectiveFaculty.getBytes(StandardCharsets.UTF_8));
        if (!force) {
            var cached = cachedIds(key, effectiveFaculty);
            if (!cached.isEmpty()) return hydrate(cached, limit);
        }
        var stored = jdbcTemplate.queryForList(FETCH_AI_RECS_SQL, Map.of("userId", userId));
        if (!force && !stored.isEmpty()) {
            Instant computed = instant(stored.get(0).get("computed_at"));
            if (!computed.isAfter(Instant.now().plusSeconds(5)) && computed.isAfter(profileChanged) && computed.isAfter(Instant.now().minus(CACHE_TTL))) {
                var ids = parsePubIds(java.util.Objects.toString(stored.get(0).get("pub_ids"), "[]"), POOL_SIZE);
                if (!ids.isEmpty()) { cache(key, ids, effectiveFaculty, computed); return hydrate(ids, limit); }
            }
        }
        var local = refreshLocks[Math.floorMod(userId.hashCode(), refreshLocks.length)];
        if (!local.tryLock()) {
            if (force) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Recommendation refresh already running");
            return fallbackStored(stored, limit);
        }
        RedisStateStore.Lease lease = null;
        try {
            lease = state.acquire("lms:reco:lock:" + userId, Duration.ofSeconds(30));
            if (lease.available() && !lease.acquired()) {
                if (force) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "Recommendation refresh already running");
                return fallbackStored(stored, limit);
            }
            // A completed request may have populated Redis while this request read PostgreSQL.
            if (!force) { var current = cachedIds(key, effectiveFaculty); if (!current.isEmpty()) return hydrate(current, limit); }
            var prediction = fetchFromAiGateway(userId, effectiveFaculty.isBlank() ? null : effectiveFaculty, POOL_SIZE);
            var ids = prediction.ids();
            if (ids.isEmpty()) {
                if (force) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Personalization could not be refreshed");
                return fallbackStored(stored, limit);
            }
            Instant generated = Instant.now();
            try {
                jdbcTemplate.update("""
                    INSERT INTO ai_recommendations(user_id,pub_ids,strategy,computed_at)
                    VALUES (:userId,CAST(:ids AS JSONB),:strategy,:computed)
                    ON CONFLICT(user_id) DO UPDATE SET pub_ids=EXCLUDED.pub_ids,strategy=EXCLUDED.strategy,computed_at=EXCLUDED.computed_at
                    """, Map.of("userId", userId, "ids", objectMapper.writeValueAsString(ids), "strategy",prediction.strategy(),"computed", Timestamp.from(generated)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
            cache(key, ids, effectiveFaculty, generated);
            return hydrate(ids, limit);
        } finally { state.release(lease); local.unlock(); }
    }

    private List<Long> cachedIds(String key, String faculty) {
        var read = state.read(key);
        if (!read.available() || read.value() == null || read.value().length() > 16000) return List.of();
        try {
            var json = objectMapper.readTree(read.value());
            Instant generated = Instant.ofEpochMilli(json.path("generatedAt").asLong(0));
            if (!faculty.equals(json.path("faculty").asText()) || generated.isAfter(Instant.now().plusSeconds(5))
                || !generated.isAfter(Instant.now().minus(CACHE_TTL))) return List.of();
            return parsePubIds(json.path("ids").toString(), POOL_SIZE);
        } catch (Exception invalid) { return List.of(); }
    }

    private void cache(String key, List<Long> ids, String faculty, Instant generated) {
        try {
            Duration remaining = Duration.between(Instant.now(), generated.plus(CACHE_TTL));
            if (remaining.compareTo(CACHE_TTL)>0) remaining=CACHE_TTL;
            if (!remaining.isNegative() && !remaining.isZero()) state.put(key,
                objectMapper.writeValueAsString(Map.of("ids", ids, "faculty", faculty, "generatedAt", generated.toEpochMilli())), remaining);
        } catch (Exception ignored) { /* PostgreSQL remains the fallback; never expose cache internals. */ }
    }

    private List<RecommendationResponse> hydrate(List<Long> ids, int limit) {
        var ranked = preserveAiRanking(ids, fetchByIds(ids), limit);
        if (ranked.size() >= limit) return ranked;
        var combined = new java.util.ArrayList<>(ranked);
        var present = ranked.stream().map(RecommendationResponse::getPublicationId).collect(Collectors.toSet());
        fetchFallback(limit).stream().filter(book -> present.add(book.getPublicationId())).limit(limit - ranked.size()).forEach(combined::add);
        return List.copyOf(combined);
    }

    private List<RecommendationResponse> fallbackStored(List<Map<String,Object>> rows, int limit) {
        if (!rows.isEmpty()) {
            var ids = parsePubIds(java.util.Objects.toString(rows.get(0).get("pub_ids"), "[]"), POOL_SIZE);
            if (!ids.isEmpty()) return hydrate(ids, limit);
        }
        return fetchFallback(limit);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime date) return date.toInstant();
        if (value instanceof Instant date) return date;
        return Instant.EPOCH;
    }

    private record Prediction(List<Long> ids,String strategy) {}
    private Prediction fetchFromAiGateway(Long userId, String faculty, int limit) {
        try {
            AiGatewayService.AiRecommendationResult result =
                aiGatewayService.getRecommendations(userId, faculty, limit);
            if (result == null || result.publicationIds() == null) {
                return new Prediction(List.of(),"AI_GATEWAY_UNAVAILABLE");
            }
            String strategy=result.strategy()==null || result.strategy().isBlank() || result.strategy().length()>30 ? "PERSONALIZED" : result.strategy();
            return new Prediction(result.publicationIds().stream().filter(id -> id != null && id > 0).distinct().limit(limit).toList(),strategy);
        } catch (Exception e) {
            log.warn("AI recommendation gateway failed for userId={}, faculty={}, falling back to cache/local", userId, faculty, e);
            return new Prediction(List.of(),"AI_GATEWAY_UNAVAILABLE");
        }
    }

    private List<RecommendationResponse> preserveAiRanking(
        List<Long> pubIds,
        List<RecommendationResponse> fetched,
        int limit
    ) {
        Map<Long, RecommendationResponse> byId = fetched.stream()
            .collect(Collectors.toMap(RecommendationResponse::getPublicationId, r -> r));

        return pubIds.stream()
            .filter(byId::containsKey)
            .map(byId::get)
            .limit(limit)
            .toList();
    }

    private List<Long> parsePubIds(String json, int limit) {
        try {
            List<Long> ids = objectMapper.readValue(json, new TypeReference<>() {});
            return ids.stream().filter(id -> id != null && id > 0).distinct().limit(limit).toList();
        } catch (Exception e) {
            log.warn("Failed to parse pub_ids JSON: {}", json, e);
            return Collections.emptyList();
        }
    }

    private List<RecommendationResponse> fetchByIds(List<Long> pubIds) {
        return jdbcTemplate.queryForList(FETCH_PUBS_BY_IDS_SQL, Map.of("pubIds", pubIds))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    private List<RecommendationResponse> fetchFallback(int limit) {
        return jdbcTemplate.queryForList(FALLBACK_SQL, Map.of("limit", limit))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    private RecommendationResponse toResponse(Map<String, Object> row) {
        String authorNamesRaw = (String) row.get("author_names");
        List<String> authorNames = authorNamesRaw != null
            ? Arrays.asList(authorNamesRaw.split(", "))
            : Collections.emptyList();

        return RecommendationResponse.builder()
            .publicationId(((Number) row.get("publication_id")).longValue())
            .title((String) row.get("title"))
            .coverImageUrl((String) row.get("cover_image_url"))
            .publicationYear(row.get("publication_year") != null
                ? ((Number) row.get("publication_year")).intValue() : null)
            .availableItems(((Number) row.get("available_items")).intValue())
            .ratingAverage(((Number) row.get("rating_average")).doubleValue())
            .ratingCount(((Number) row.get("rating_count")).intValue())
            .borrowCount(((Number) row.getOrDefault("borrow_count", 0)).longValue())
            .authorNames(authorNames)
            .build();
    }
}
