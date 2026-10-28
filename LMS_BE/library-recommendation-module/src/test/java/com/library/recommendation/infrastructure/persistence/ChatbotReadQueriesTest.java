package com.library.recommendation.infrastructure.persistence;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.ChatbotTool;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class ChatbotReadQueriesTest {
    final ObjectMapper mapper=new ObjectMapper();NamedParameterJdbcTemplate jdbc;ChatbotReadQueries queries;
    @BeforeEach void setup() {jdbc=mock(NamedParameterJdbcTemplate.class);queries=new ChatbotReadQueries(jdbc);}
    @Test void everyPrivateAdapterIsSelectOnlyAndScopedToServerIdentity() throws Exception {
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of());
        for(var tool:ChatbotTool.values()) if(tool.personal) queries.execute(tool,mapper.readTree("{}"),7L,"vi");
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(call->{
            assertThat((String)call.getArgument(0)).startsWith("SELECT").contains(":userId");
            assertThat((Map<String,Object>)call.getArgument(1)).containsEntry("userId",7L);
        });
    }
    @ParameterizedTest @ValueSource(strings={"{\"userId\":999}","{\"page\":-1}","{\"pageSize\":0}","{\"pageSize\":51}","{\"page\":1.5}","{\"status\":\"UNPAID;DELETE\"}"})
    void invalidArgumentsCannotReachSql(String json) throws Exception {
        assertThatThrownBy(()->queries.execute(ChatbotTool.get_my_fines,mapper.readTree(json),7L,"vi")).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(jdbc);
    }
    @Test void anonymousRequestsCannotReadAnyPrivateAdapter() throws Exception {
        for(var tool:ChatbotTool.values()) if(tool.personal) assertThatThrownBy(()->queries.execute(tool,mapper.createObjectNode(),null,"vi")).isInstanceOf(SecurityException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void fineTotalCoversTheEntireFilterAndJsonContainsRawValuesWithoutUiCards() throws Exception {
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(27L);
        when(jdbc.queryForObject(contains("SUM(f.fine_amount)"),anyMap(),eq(BigDecimal.class))).thenReturn(new BigDecimal("1234000"));
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("id",1L,"title","Book","fine_amount",new BigDecimal("50000"))));
        var result=queries.execute(ChatbotTool.get_my_fines,mapper.readTree("{\"page\":2,\"pageSize\":5}"),7L,"vi");
        assertThat(result).containsEntry("total",27L).containsEntry("hasNext",true).containsEntry("totalFineAmount",new BigDecimal("1234000"));
        assertThat(result).doesNotContainKeys("answer","cards","content");
        assertThat(((List<Map<String,Object>>)result.get("records")).getFirst()).containsEntry("id","1").containsEntry("fine_amount",new BigDecimal("50000"));
    }
    @Test void searchBindsUntrustedWordsAndNeverReadsPrivateOrUploadFields() throws Exception {
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of());
        queries.execute(ChatbotTool.search_catalog,mapper.readTree("{\"query\":\"x' OR 1=1 --\",\"branch\":\"CAMPUS_2\"}"),null,"vi");
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(call->{
            assertThat((String)call.getArgument(0)).startsWith("SELECT").doesNotContain("x' OR","file_url","full_name","ai_processing_error");
            assertThat((Map<String,Object>)call.getArgument(1)).containsEntry("branch","Cơ sở 2 - Dĩ An");
        });
    }
    @Test void urgencyFilterUsesVietnamDatesAndReturnsDataForTheModelToExplain() throws Exception {
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Long.class))).thenReturn(2L);
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of());
        var result=queries.execute(ChatbotTool.get_my_loans,mapper.readTree("{\"status\":\"DUE_SOON\"}"),7L,"vi");
        assertThat(result).containsEntry("total",2L).containsEntry("urgentWindowDays",3).doesNotContainKey("answer");
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(call->assertThat((String)call.getArgument(0)).contains(":userId","t.due_date <="));
    }
    @Test void publicPolicyDoesNotExposeAdminIdentity() throws Exception {
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("default_deposit_amount",new BigDecimal("50000"))));
        assertThat(queries.execute(ChatbotTool.get_current_circulation_policy,mapper.createObjectNode(),null,"vi")).containsEntry("url","/publicpage/faq#current-policy");
        assertThat(mockingDetails(jdbc).getInvocations()).allSatisfy(call->assertThat((String)call.getArgument(0)).doesNotContain("updated_by_admin"));
    }
}
