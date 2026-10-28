package com.library.recommendation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.ChatbotTool;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ChatbotReadQueriesTest {
    final ObjectMapper mapper = new ObjectMapper();
    NamedParameterJdbcTemplate jdbc;
    ChatbotReadQueries queries;
    @BeforeEach void setup() { jdbc = mock(NamedParameterJdbcTemplate.class); queries = new ChatbotReadQueries(jdbc); }

    @ParameterizedTest @EnumSource(value=ChatbotTool.class, names={"get_my_loans","get_my_loan_history","get_my_fines","get_my_reservations","get_my_wishlist","get_my_notifications"})
    void everyPersonalToolRequiresIdentityAndScopesEveryQuery(ChatbotTool tool) throws Exception {
        assertThatThrownBy(() -> queries.execute(tool, mapper.readTree("{}"), null, "en")).isInstanceOf(SecurityException.class);
        verifyNoInteractions(jdbc);
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        if (tool == ChatbotTool.get_my_fines) when(jdbc.queryForObject(anyString(), anyMap(), eq(BigDecimal.class))).thenReturn(BigDecimal.ZERO);
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
        queries.execute(tool, mapper.readTree("{}"), 7L, "en");
        var details = mockingDetails(jdbc).getInvocations();
        assertThat(details).allSatisfy(call -> {
            assertThat(call.getMethod().getName()).startsWith("query");
            assertThat((String)call.getArgument(0)).startsWith("SELECT").contains(":userId");
            assertThat((Map<String,Object>)call.getArgument(1)).containsEntry("userId", 7L);
        });
    }
    @ParameterizedTest @ValueSource(strings={"{\"userId\":999}","{\"page\":-1}","{\"page\":2147483648}","{\"status\":\"UNPAID;DELETE\"}","{\"page\":1.5}"})
    void malformedOrPrivilegeArgumentsNeverReachSql(String arguments) throws Exception {
        assertThatThrownBy(() -> queries.execute(ChatbotTool.get_my_fines, mapper.readTree(arguments), 7L, "vi"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void fineTotalsCoverTheFullFilterRatherThanTheDisplayedPage() throws Exception {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(27L);
        when(jdbc.queryForObject(contains("SUM(f.fine_amount)"), anyMap(), eq(BigDecimal.class))).thenReturn(new BigDecimal("1234000"));
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of("id", 1L,"title","Book","fine_amount",new BigDecimal("50000"),"payment_status","UNPAID","type","OVERDUE_RETURN")));
        var reply = queries.execute(ChatbotTool.get_my_fines, mapper.readTree("{\"page\":2}"), 7L, "vi");
        assertThat(reply.total()).isEqualTo(27);
        assertThat(reply.answer()).contains("1.234.000đ");
        assertThat(reply.cards()).hasSize(1);
        verify(jdbc).queryForObject(argThat(sql -> sql.contains("SUM(f.fine_amount)") && !sql.contains("LIMIT")), anyMap(), eq(BigDecimal.class));
    }
    @Test void searchIsParameterizedAndDoesNotReadPrivateOrUploadFields() throws Exception {
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of());
        queries.execute(ChatbotTool.search_catalog, mapper.readTree("{\"query\":\"x' OR 1=1 --\",\"branch\":\"CAMPUS_2\"}"), null, "vi");
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(call -> {
            String sql = call.getArgument(0);
            assertThat(sql).startsWith("SELECT").doesNotContain("x' OR", "file_url", "full_name", "ai_processing_error");
            assertThat((Map<String,Object>)call.getArgument(1)).containsEntry("branch", "Cơ sở 2 - Dĩ An");
        });
    }
    @Test void overdueAndDepositPolicyDoNotExposeAdminIdentity() throws Exception {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of(
            "default_loan_days",21,"max_active_borrows",5,"max_active_reservations",2,"max_renewals",3,
            "renewal_window_days",2,"pickup_deadline_hours",24,"default_deposit_amount",new BigDecimal("50000"),
            "overdue_fine_per_day",new BigDecimal("2000"),"block_borrow_when_unpaid_fines",true)));
        var reply = queries.execute(ChatbotTool.get_current_circulation_policy, mapper.readTree("{}"), null, "vi");
        assertThat(reply.cards().get(0).facts()).anySatisfy(fact -> assertThat(fact.value()).isEqualTo("50.000đ"));
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(call -> assertThat((String)call.getArgument(0)).doesNotContain("updated_by_admin"));
    }
    @Test void urgentLoansAnswerYesFirstAndFilterDatesWithoutExposingOtherReaders() throws Exception {
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(2L);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of());
        var reply=queries.execute(ChatbotTool.get_my_loans,mapper.readTree("{\"status\":\"DUE_SOON\"}"),7L,"vi");
        assertThat(reply.answer()).startsWith("Có, bạn có 2 cuốn").contains("3 ngày tới");
        assertThat(reply.sourceTitle()).isEqualTo("Sách của tôi").isNotEqualTo(reply.answer());
        verify(jdbc).queryForObject(contains("t.due_date <= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 3"),org.mockito.ArgumentMatchers.<Map<String,Object>>argThat(p->Long.valueOf(7).equals(p.get("userId"))),eq(Long.class));
    }
    @Test void emptyUrgentLoansAnswerNoWithoutInventingDueDates() throws Exception {
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of());
        assertThat(queries.execute(ChatbotTool.get_my_loans,mapper.readTree("{\"status\":\"DUE_SOON\"}"),7L,"vi").answer()).startsWith("Không,");
    }
}
