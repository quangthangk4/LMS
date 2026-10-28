package com.library.integration;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.ChatbotTool;
import com.library.recommendation.infrastructure.persistence.ChatbotReadQueries;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.library.recommendation.infrastructure.cache.RedisStateStore;
import com.library.recommendation.application.chatbot.ChatConversationStore;
import com.library.recommendation.application.chatbot.ChatbotReply;
import com.library.recommendation.application.impl.GetRecommendationsUseCaseImpl;
import com.library.recommendation.infrastructure.ai.AiGatewayService;
import java.time.Duration;
import java.util.UUID;
import static org.mockito.Mockito.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Runs migrations and actual SELECTs in a disposable pgvector database, without the application/external services. */
@Testcontainers
class ChatbotReadOnlyIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory redisConnection;
    static StringRedisTemplate redis;
    static RedisStateStore state;
    static JdbcTemplate jdbc;
    static ChatbotReadQueries queries;
    static TransactionTemplate transaction;
    static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll static void setup() {
        var source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl()); source.setUser(postgres.getUsername()); source.setPassword(postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        queries = new ChatbotReadQueries(new NamedParameterJdbcTemplate(source));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        redisConnection=new LettuceConnectionFactory(redisContainer.getHost(),redisContainer.getMappedPort(6379));
        redisConnection.afterPropertiesSet(); redisConnection.start();
        redis=new StringRedisTemplate(redisConnection); state=new RedisStateStore(redis,true);
    }
    @org.junit.jupiter.api.AfterAll static void closeRedis() { if(redisConnection!=null) redisConnection.destroy(); }

    @Test void redisLeaseAndCompareAndSetCannotOverwriteNewerValues() {
        String key="test:"+UUID.randomUUID();
        assertThat(state.create(key,"one",Duration.ofSeconds(60))).isTrue();
        assertThat(state.replace(key,"wrong","two",Duration.ofSeconds(60))).isFalse();
        assertThat(state.replace(key,"one","two",Duration.ofSeconds(60))).isTrue();
        assertThat(state.deleteIfValue(key,"one")).isFalse();
        var first=state.acquire(key+":lock",Duration.ofSeconds(30));
        var second=state.acquire(key+":lock",Duration.ofSeconds(30));
        assertThat(first.acquired()).isTrue(); assertThat(second.acquired()).isFalse();
        state.release(second); assertThat(state.read(key+":lock").value()).isEqualTo(first.token());
        state.release(first); assertThat(state.read(key+":lock").value()).isNull();
    }

    @Test void conversationIsBoundedScopedExpiresAndCannotBeResurrectedAfterClearOrLogout() {
        var conversations=new ChatConversationStore(state,mapper);
        UUID id=UUID.randomUUID();
        for(int n=0;n<8;n++) {
            var session=conversations.begin(id,7L,"vi");
            conversations.finish(session,"Question "+n,ChatbotReply.message("ANSWERED","Public guidance"),"book=42 campus=CAMPUS_2");
        }
        var session=conversations.begin(id,7L,"vi");
        assertThat(session.context().turns()).hasSize(6);
        assertThat(session.context().turns().getFirst().question()).isEqualTo("Question 2");
        assertThat(redis.getExpire(session.key())).isBetween(1790L,1800L);
        var stranger=conversations.begin(id,8L,"vi");
        assertThat(stranger.context().turns()).isEmpty();
        conversations.finish(stranger,"Hello",ChatbotReply.message("ANSWERED","Hi"),null);
        conversations.clear(id,7L);
        conversations.finish(session,"Late request",ChatbotReply.message("ANSWERED","Must not persist"),null);
        assertThat(state.read(session.key()).value()).isEqualTo("CLEARED");
        var old=conversations.begin(UUID.randomUUID(),7L,"vi");
        conversations.finish(old,"Old question",ChatbotReply.message("ANSWERED","Old context"),null);
        conversations.revoke(7L);
        var afterLogout=conversations.begin(UUID.fromString(old.id()),7L,"vi");
        assertThat(afterLogout.key()).isNotEqualTo(old.key());
        assertThat(afterLogout.context().turns()).isEmpty();
        conversations.finish(afterLogout,"New session",ChatbotReply.message("ANSWERED","Hi"),null);
        UUID expiring=UUID.randomUUID();
        var prior=conversations.begin(expiring,null,"en");
        conversations.finish(prior,"Hello",ChatbotReply.message("ANSWERED","Hi"),null);
        redis.expire(prior.key(),Duration.ZERO);
        var expired=conversations.begin(expiring,null,"en");
        assertThat(expired.context().turns()).isEmpty();
        assertThat(expired.reset()).isTrue();
        conversations.finish(expired,"New question",ChatbotReply.message("ANSWERED","Hi"),null);
    }

    @Test void recommendationsUseEightHourRedisCacheButHydrateFreshSqlAndRefreshExplicitly() {
        var ai=mock(AiGatewayService.class);
        Long user=jdbc.queryForObject("SELECT MIN(id) FROM users",Long.class);
        var ids=jdbc.queryForList("SELECT DISTINCT i.publication_id FROM items i WHERE i.status='AVAILABLE' AND NOT EXISTS (SELECT 1 FROM reshelving_tasks q WHERE q.item_id=i.id AND q.status='WAITING') ORDER BY i.publication_id LIMIT 2",Long.class);
        jdbc.update("DELETE FROM ai_recommendations WHERE user_id=?",user);
        when(ai.getRecommendations(eq(user),any(),eq(20))).thenReturn(new AiGatewayService.AiRecommendationResult(ids,"TEST"));
        var recommendations=new GetRecommendationsUseCaseImpl(new NamedParameterJdbcTemplate(jdbc),mapper,ai,state);
        var first=recommendations.execute(user,2);
        assertThat(first).isNotEmpty();
        var keys=redis.keys("lms:reco:v1:"+user+":*");
        assertThat(keys).hasSize(1);
        assertThat(redis.getExpire(keys.iterator().next())).isBetween(28790L,28800L);
        assertThat(recommendations.execute(user,2)).extracting("publicationId").containsExactlyElementsOf(first.stream().map(r->r.getPublicationId()).toList());
        verify(ai,times(1)).getRecommendations(eq(user),any(),eq(20));
        Long book=first.getFirst().getPublicationId();
        Long available=jdbc.queryForObject("SELECT COUNT(*) FROM items i WHERE i.publication_id=? AND i.status='AVAILABLE' AND NOT EXISTS (SELECT 1 FROM reshelving_tasks q WHERE q.item_id=i.id AND q.status='WAITING')",Long.class,book);
        assertThat(recommendations.execute(user,2).getFirst().getAvailableItems()).isEqualTo(available.intValue());
        recommendations.refresh(user,2);
        verify(ai,times(2)).getRecommendations(eq(user),any(),eq(20));
    }

    @Test void availabilityDistinguishesExistingBookWithoutCampusCopiesFromMissingPublication() {
        Long book=jdbc.queryForObject("SELECT MIN(id) FROM publications",Long.class);
        var write=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        var answer=write.execute(status->{
            status.setRollbackOnly();
            jdbc.update("UPDATE items SET branch='Cơ sở 1 - Lý Thường Kiệt' WHERE publication_id=?",book);
            return queries.execute(ChatbotTool.get_publication_availability,
                mapper.createObjectNode().put("publicationId",book.toString()).put("branch","CAMPUS_2"),null,"vi");
        });
        assertThat(answer.answer()).startsWith("Không.").contains("có trong thư viện","Cơ sở 2");
        assertThat(answer.total()).isZero();
        var missing=queries.execute(ChatbotTool.get_publication_availability,
            mapper.createObjectNode().put("publicationId",Long.toString(Long.MAX_VALUE)).put("branch","CAMPUS_2"),null,"vi");
        assertThat(missing.answer()).contains("Chưa tìm thấy sách");
    }

    @Test void catalogPolicyAndPersonalToolsUseRealSqlWithoutChangingBusinessTables() throws Exception {
        Long userId = jdbc.queryForObject("SELECT user_id FROM borrowing_transactions ORDER BY id LIMIT 1", Long.class);
        long viewsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM user_interactions", Long.class);
        long notificationsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE is_read", Long.class);
        for (var tool : ChatbotTool.values()) {
            if (tool == ChatbotTool.search_library_help) continue;
            var arguments = mapper.createObjectNode();
            if (List.of(ChatbotTool.search_catalog, ChatbotTool.get_publication_details, ChatbotTool.get_publication_availability, ChatbotTool.get_publication_rating_summary).contains(tool)) {
                arguments.put("publicationId", jdbc.queryForObject("SELECT MIN(id)::text FROM publications", String.class));
                if (!tool.arguments.contains("publicationId")) { arguments.remove("publicationId"); arguments.put("query", "Code"); }
            }
            var reply = transaction.execute(status -> queries.execute(tool, arguments, tool.personal ? userId : null, "en"));
            assertThat(reply).isNotNull();
            assertThat(reply.status()).isEqualTo("ANSWERED");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_interactions", Long.class)).isEqualTo(viewsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE is_read", Long.class)).isEqualTo(notificationsBefore);
    }

    @Test void fineAggregateAndLoansAreScopedToTheSelectedReaderAcrossPages() throws Exception {
        Long userId = jdbc.queryForObject("SELECT user_id FROM borrowing_transactions ORDER BY id LIMIT 1", Long.class);
        var reply = transaction.execute(status -> queries.execute(ChatbotTool.get_my_fines, mapper.createObjectNode(), userId, "vi"));
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM fines f JOIN borrowing_transactions t ON t.id=f.transaction_id WHERE t.user_id=? AND f.payment_status='UNPAID'", Long.class, userId);
        assertThat(reply.total()).isEqualTo(count);
        var other = transaction.execute(status -> queries.execute(ChatbotTool.get_my_loans, mapper.createObjectNode(), Long.MAX_VALUE, "vi"));
        assertThat(other.total()).isZero();
        assertThat(other.cards()).isEmpty();
    }
    @Test void catalogSearchMatchesWholeTermsAndStoredDescriptionsRatherThanJavaScriptSubstrings() {
        var books=jdbc.queryForList("SELECT id FROM publications ORDER BY id LIMIT 2",Long.class);
        var write=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        write.executeWithoutResult(status->{
            status.setRollbackOnly();
            jdbc.update("UPDATE publications SET title='Java Subjectsentinel',description='Basics',ai_summary=NULL WHERE id=?",books.get(0));
            jdbc.update("UPDATE publications SET title='JavaScript Subjectsentinel',description='Web scripting',ai_summary=NULL WHERE id=?",books.get(1));
            var java=queries.execute(ChatbotTool.search_catalog,mapper.createObjectNode().put("query","Java Subjectsentinel"),null,"en");
            assertThat(java.cards()).extracting(c->c.path()).containsExactly("/publicpage/book/"+books.get(0));
            jdbc.update("UPDATE publications SET title='Nhập môn',description='Lập trình Subjectsentinel dành cho người mới bắt đầu' WHERE id=?",books.get(0));
            var beginner=queries.execute(ChatbotTool.search_catalog,mapper.createObjectNode().put("query","lap trinh Subjectsentinel"),null,"vi");
            assertThat(beginner.cards()).extracting(c->c.path()).containsExactly("/publicpage/book/"+books.get(0));
            assertThat(beginner.cards().getFirst().facts()).anyMatch(f->f.value().contains("người mới bắt đầu"));
        });
    }
    @Test void urgentLoansUseDueDatesEvenBeforeTheOverdueSchedulerUpdatesStatus() {
        var active=jdbc.queryForMap("SELECT id,user_id FROM borrowing_transactions WHERE status IN ('BORROWING','OVERDUE') ORDER BY id LIMIT 1");
        Long user=((Number)active.get("user_id")).longValue();
        var write=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        write.executeWithoutResult(status->{
            status.setRollbackOnly();
            jdbc.update("UPDATE borrowing_transactions SET due_date=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date+10 WHERE user_id=? AND status IN ('BORROWING','OVERDUE')",user);
            jdbc.update("UPDATE borrowing_transactions SET due_date=(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date-1,status='BORROWING' WHERE id=?",active.get("id"));
            var urgent=queries.execute(ChatbotTool.get_my_loans,mapper.createObjectNode().put("status","DUE_SOON"),user,"vi");
            assertThat(urgent.total()).isEqualTo(1L);
            assertThat(urgent.answer()).startsWith("Có, bạn có 1 cuốn");
            assertThat(urgent.cards()).hasSize(1);
            assertThat(urgent.cards().getFirst().facts()).anyMatch(f->"Trạng thái".equals(f.label()) && "Quá hạn".equals(f.value()));
            var overdue=queries.execute(ChatbotTool.get_my_loans,mapper.createObjectNode().put("status","OVERDUE"),user,"vi");
            assertThat(overdue.total()).isEqualTo(1L);
        });
    }
}
