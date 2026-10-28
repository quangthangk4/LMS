package com.library.recommendation.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.ChatbotTool;
import java.time.Duration;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GeminiChatClientTest {
    final ObjectMapper mapper=new ObjectMapper();
    MockRestServiceServer server; RestClient.Builder builder; GeminiChatClient client;
    FaqModelCooldownTest.MutableClock clock;
    @BeforeEach void setup() {
        builder=RestClient.builder();server=MockRestServiceServer.bindTo(builder).build();
        clock=new FaqModelCooldownTest.MutableClock("2026-10-28T08:00:00Z");
        client=new GeminiChatClient(mapper,"test-key","primary","backup",builder,clock);
    }
    String url(String model) {return "https://generativelanguage.googleapis.com/v1beta/models/"+model+":generateContent";}
    List<com.fasterxml.jackson.databind.JsonNode> contents() {return List.of(mapper.valueToTree(Map.of("role","user","parts",List.of(Map.of("text","Help me")))));}
    String text(String value,String finish) throws Exception {
        var content = Map.of("role", "model", "parts", List.of(Map.of("text", value)));
        return mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", finish, "content", content))));
    }
    @Test void autoModeAllowsDirectMarkdownWithoutEnvelopeOrAnswerValidation() throws Exception {
        String answer="## Hướng dẫn\n\n"+"Giải thích tự nhiên. ".repeat(40)+"\n1. **100.000đ**\n2. [Sách](/publicpage/book/1)";
        server.expect(requestTo(url("primary"))).andExpect(method(HttpMethod.POST)).andExpect(header("x-goog-api-key","test-key"))
            .andExpect(jsonPath("$.toolConfig.functionCallingConfig.mode").value("AUTO"))
            .andExpect(jsonPath("$.generationConfig.responseMimeType").doesNotExist())
            .andExpect(jsonPath("$.generationConfig.responseJsonSchema").doesNotExist())
            .andRespond(withSuccess(text(answer,"STOP"),MediaType.APPLICATION_JSON));
        var result=client.next("Library74 instructions",contents(),ChatbotTool.available(true));
        assertThat(result.text()).isEqualTo(answer);assertThat(result.calls()).isEmpty();assertThat(result.failure()).isNull();server.verify();
    }
    @Test void preservesMultipleNativeCallsIdsAndThoughtSignatures() throws Exception {
        String payload="{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[{\"thoughtSignature\":\"opaque\",\"functionCall\":{\"name\":\"get_my_loans\",\"id\":\"call-1\",\"args\":{}}},{\"functionCall\":{\"name\":\"get_my_fines\",\"args\":{}}}]}}]}";
        server.expect(anything()).andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
        var result=client.next("Scope",contents(),ChatbotTool.available(true));
        assertThat(result.calls()).hasSize(2);assertThat(result.calls().getFirst().id()).isEqualTo("call-1");
        assertThat(result.content().path("parts").path(0).path("thoughtSignature").asText()).isEqualTo("opaque");server.verify();
    }
    @Test void finalRoundOmitsToolsAndDoesNotForceAFunctionCall() throws Exception {
        server.expect(anything()).andExpect(jsonPath("$.tools").doesNotExist()).andExpect(jsonPath("$.toolConfig").doesNotExist())
            .andRespond(withSuccess(text("I need more information.","STOP"),MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("I need more information.");server.verify();
    }
    @Test void doesNotReplaceTheModelsRefusalWithACannedAnswer() throws Exception {
        server.expect(anything()).andRespond(withSuccess(text("Tôi không tìm thấy thông tin này trong nguồn được cung cấp. Bạn muốn xem hướng dẫn mượn sách không?","STOP"),MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),ChatbotTool.available(false)).text()).contains("Bạn muốn xem");server.verify();
    }
    @Test void partialTextIsForwardedWithATruncationFlag() throws Exception {
        server.expect(anything()).andRespond(withSuccess(text("Các bước tiếp theo…","MAX_TOKENS"),MediaType.APPLICATION_JSON));
        var result=client.next("Scope",contents(),List.of());
        assertThat(result.text()).isEqualTo("Các bước tiếp theo…");assertThat(result.truncated()).isTrue();server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}","{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}]}"})
    void malformedOrEmptyProviderOutputIsATechnicalFailureWithoutAnswerShopping(String payload) {
        server.expect(requestTo(url("primary"))).andRespond(withSuccess(payload,MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),List.of()).failure()).isNotNull();server.verify();
    }
    @Test void quotaFallsBackAndPrimaryIsSkippedDuringProviderCooldown() throws Exception {
        server.expect(requestTo(url("primary"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("First","STOP"),MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("Second","STOP"),MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("First");
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("Second");server.verify();
    }
    @Test void exhaustedQuotaIsReportedWithoutInventingContent() {
        server.expect(requestTo(url("primary"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url("backup"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(client.next("Scope",contents(),List.of()).failure()).isEqualTo("AI_QUOTA_EXCEEDED");server.verify();
    }
    @Test void dailyQuotaWaitsUntilProviderResetWhileBackupContinuesToWork() throws Exception {
        String quota="{\"error\":{\"details\":[{\"violations\":[{\"quotaId\":\"GenerateRequestsPerDayPerModel\"}]},{\"retryDelay\":\"120s\"}]}}";
        server.expect(requestTo(url("primary"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After","180").contentType(MediaType.APPLICATION_JSON).body(quota));
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("First","STOP"),MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("Later","STOP"),MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("First");
        clock.advance(Duration.ofHours(2));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("Later");server.verify();
    }
    @Test void minuteQuotaHonorsRetryAfterBeforeTryingPrimaryAgain() throws Exception {
        server.expect(requestTo(url("primary"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","180"));
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("First","STOP"),MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("Still backup","STOP"),MediaType.APPLICATION_JSON));
        server.expect(requestTo(url("primary"))).andRespond(withSuccess(text("Primary again","STOP"),MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("First");
        clock.advance(Duration.ofSeconds(120));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("Still backup");
        clock.advance(Duration.ofSeconds(61));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("Primary again");server.verify();
    }
    @ParameterizedTest @ValueSource(ints={400,401,403})
    void configurationErrorsDoNotTryTheSameKeyOnAnotherModel(int status) {
        server.expect(requestTo(url("primary"))).andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThat(client.next("Scope",contents(),List.of()).failure()).isEqualTo("AI_CONFIGURATION_ERROR");
        assertThat(client.next("Scope",contents(),List.of()).failure()).isEqualTo("AI_CONFIGURATION_ERROR");server.verify();
    }
    @ParameterizedTest @ValueSource(ints={404,408,500,503})
    void transientOrMissingModelFailuresUseBackup(int status) throws Exception {
        server.expect(requestTo(url("primary"))).andRespond(withStatus(HttpStatus.valueOf(status)));
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("Recovered","STOP"),MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("Recovered");server.verify();
    }
    @Test void timeoutUsesBackupAndMissingKeyNeverCallsProvider() throws Exception {
        server.expect(requestTo(url("primary"))).andRespond(request->{throw new SocketTimeoutException("timeout");});
        server.expect(requestTo(url("backup"))).andRespond(withSuccess(text("Recovered","STOP"),MediaType.APPLICATION_JSON));
        assertThat(client.next("Scope",contents(),List.of()).text()).isEqualTo("Recovered");
        var missing=new GeminiChatClient(mapper,"","primary","backup",RestClient.builder(),clock);
        assertThat(missing.next("Scope",contents(),List.of()).failure()).isEqualTo("AI_NOT_CONFIGURED");server.verify();
    }
    @Test void expiredTurnBudgetStopsAdditionalHttpCalls() {
        client.startBudget(Duration.ofSeconds(1));clock.advance(Duration.ofSeconds(2));
        assertThat(client.next("Scope",contents(),List.of()).failure()).isEqualTo("REQUEST_TIMEOUT");client.endBudget();server.verify();
    }
}
