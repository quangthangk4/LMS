package com.library.recommendation.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.impl.GetRecommendationsUseCaseImpl;
import com.library.recommendation.infrastructure.ai.AiGatewayService;
import com.library.recommendation.infrastructure.cache.RedisStateStore;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class GetRecommendationsUseCaseTest {
    final ObjectMapper mapper=new ObjectMapper();
    NamedParameterJdbcTemplate jdbc;
    AiGatewayService ai;
    RedisStateStore redis;
    GetRecommendationsUseCaseImpl service;
    List<Map<String,Object>> stored;
    List<Map<String,Object>> books;
    Map<String,String> cache;
    @BeforeEach void setup() {
        jdbc=mock(NamedParameterJdbcTemplate.class); ai=mock(AiGatewayService.class); redis=mock(RedisStateStore.class);
        service=new GetRecommendationsUseCaseImpl(jdbc,mapper,ai,redis);
        stored=List.of(); books=List.of(row(1L),row(2L),row(3L)); cache=new HashMap<>();
        when(redis.read(anyString())).thenAnswer(a->new RedisStateStore.Read(true,cache.get(a.getArgument(0))));
        when(redis.acquire(anyString(),any())).thenAnswer(a->new RedisStateStore.Lease(a.getArgument(0),"token",true,true));
        when(redis.put(anyString(),anyString(),any())).thenAnswer(a->{cache.put(a.getArgument(0),a.getArgument(1));return true;});
        when(jdbc.queryForList(anyString(),anyMap())).thenAnswer(a->{
            String sql=a.getArgument(0);
            if(sql.startsWith("SELECT faculty")) return List.of(Map.of("faculty","CS","updated_at",Timestamp.from(Instant.now().minus(Duration.ofDays(1)))));
            if(sql.contains("FROM ai_recommendations")) return stored;
            Map<String,Object> params=a.getArgument(1);
            return params.containsKey("limit")?books.stream().limit(((Number)params.get("limit")).longValue()).toList():books;
        });
        when(ai.getRecommendations(7L,"CS",20)).thenReturn(new AiGatewayService.AiRecommendationResult(List.of(3L,1L,2L),"ALS"));
    }
    @Test void gatewayRankingIsPreservedAndCachedForEightHoursWithoutCachingStock() throws Exception {
        assertThat(service.execute(7L,3)).extracting("publicationId").containsExactly(3L,1L,2L);
        verify(redis).put(startsWith("lms:reco:v1:7:"),anyString(),argThat(ttl->ttl.compareTo(Duration.ofHours(8))<=0 && ttl.compareTo(Duration.ofHours(8).minusSeconds(3))>0));
        assertThat(cache.values().iterator().next()).doesNotContain("available_items","title");
        books=List.of(row(2L));
        assertThat(service.execute(7L,1)).extracting("publicationId").containsExactly(2L);
        verify(ai,times(1)).getRecommendations(anyLong(),any(),anyInt());
    }
    @Test void freshPostgresSnapshotIsUsedBeforeCallingAi() {
        stored=List.of(Map.of("pub_ids","[2,1]","computed_at",Timestamp.from(Instant.now().minusSeconds(60))));
        assertThat(service.execute(7L,2)).extracting("publicationId").containsExactly(2L,1L);
        verifyNoInteractions(ai);
        verify(redis).put(anyString(),anyString(),argThat(ttl->ttl.compareTo(Duration.ofHours(8).minusSeconds(59))<0));
    }
    @Test void stalePostgresSnapshotIsFallbackWhenAiFails() {
        stored=List.of(Map.of("pub_ids","[2,1]","computed_at",Timestamp.from(Instant.now().minus(Duration.ofHours(9)))));
        when(ai.getRecommendations(anyLong(),any(),anyInt())).thenReturn(null);
        assertThat(service.execute(7L,2)).extracting("publicationId").containsExactly(2L,1L);
        verify(redis,never()).put(anyString(),anyString(),any());
    }
    @Test void missingAiAndSnapshotUseTrending() {
        when(ai.getRecommendations(anyLong(),any(),anyInt())).thenReturn(null);
        assertThat(service.execute(7L,2)).extracting("publicationId").containsExactly(1L,2L);
    }
    @Test void manualRefreshBypassesFreshCacheAndReplacesRanking() {
        service.execute(7L,2);
        when(ai.getRecommendations(anyLong(),any(),anyInt())).thenReturn(new AiGatewayService.AiRecommendationResult(List.of(2L,3L),"ALS"));
        assertThat(service.refresh(7L,2)).extracting("publicationId").containsExactly(2L,3L);
        assertThat(service.execute(7L,2)).extracting("publicationId").containsExactly(2L,3L);
        verify(ai,times(2)).getRecommendations(anyLong(),any(),anyInt());
    }
    @Test void failedManualRefreshKeepsOldCacheAndReportsFailure() {
        service.execute(7L,2);
        var previous=new HashMap<>(cache);
        when(ai.getRecommendations(anyLong(),any(),anyInt())).thenReturn(null);
        assertThatThrownBy(()->service.refresh(7L,2)).isInstanceOf(ResponseStatusException.class);
        assertThat(cache).isEqualTo(previous);
    }
    @Test void distributedLeaseCoalescesConcurrentRefreshes() {
        when(redis.acquire(anyString(),any())).thenReturn(new RedisStateStore.Lease("lock","",true,false));
        assertThatThrownBy(()->service.refresh(7L,2)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(ai);
    }
    @Test void redisOutageStillUsesPersistedSnapshot() {
        when(redis.read(anyString())).thenReturn(new RedisStateStore.Read(false,null));
        stored=List.of(Map.of("pub_ids","[1]","computed_at",Timestamp.from(Instant.now())));
        assertThat(service.execute(7L,1)).extracting("publicationId").containsExactly(1L);
        verifyNoInteractions(ai);
    }
    @Test void invalidRequestsDoNotReadAnyData() {
        assertThatThrownBy(()->service.execute(7L,21)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.refresh(null,1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc,ai,redis);
    }
    private Map<String,Object> row(long id) {
        return Map.of("publication_id",id,"title","Book "+id,"cover_image_url","/cover.jpg","publication_year",2026,
            "available_items",2,"rating_average",4.5,"rating_count",12,"author_names","A, B");
    }
}
