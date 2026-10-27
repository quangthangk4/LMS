package com.library.recommendation.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.FaqCatalog;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class FaqIntentSelectorTest {
    static final String PRIMARY = "gemini-3.5-flash-lite";
    static final String BACKUP = "gemini-3.1-flash-lite";
    final ObjectMapper mapper = new ObjectMapper();
    MockRestServiceServer server;
    FaqIntentSelector selector;
    FaqCatalog catalog;
    FaqModelCooldownTest.MutableClock clock;
    RestClient.Builder builder;

    @BeforeEach void setUp() throws Exception {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new FaqModelCooldownTest.MutableClock("2026-10-26T08:00:00Z");
        selector = new FaqIntentSelector(mapper, "test-key", PRIMARY, BACKUP, builder, clock);
        catalog = new FaqCatalog(mapper);
    }

    String selected(FaqIntentSelector instance, String question, List<FaqCatalog.Entry> entries) {
        var draft = instance.generate(question, entries, "vi");
        return draft == null ? null : draft.faqId();
    }

    @Test void plannerAcceptsMultipleNativeCallsAndPreservesThoughtSignatures() throws Exception {
        var content=Map.of("role","model","parts",List.of(
            Map.of("thoughtSignature","opaque-signature","functionCall",Map.of("name","search_library_help","args",Map.of("query","forgot password"))),
            Map.of("functionCall",Map.of("name","search_library_help","args",Map.of("query","login")))));
        server.expect(requestTo(url(PRIMARY)))
            .andExpect(jsonPath("$.toolConfig.functionCallingConfig.mode").value("ANY"))
            .andExpect(jsonPath("$.tools[0].functionDeclarations.length()").value(8))
            .andRespond(withSuccess(mapper.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",content)))),MediaType.APPLICATION_JSON));
        var plan=selector.plan("Reset my password and sign in",com.library.recommendation.application.chatbot.ChatbotTool.available(false),"en",List.of(),"");
        assertThat(plan.failure()).isNull(); assertThat(plan.calls()).hasSize(2);
        assertThat(plan.modelContent().path("parts").path(0).path("thoughtSignature").asText()).isEqualTo("opaque-signature");
        server.verify();
    }
    @Test void providerQuotaAndMalformedOutputAreExplicitFailures() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(url(BACKUP))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        var plan=selector.plan("Hello",com.library.recommendation.application.chatbot.ChatbotTool.available(false),"en",List.of(),"");
        assertThat(plan.failure()).isEqualTo("AI_QUOTA_EXCEEDED");
        server.verify();
    }
    @Test void compositionSupportsSeveralGroundedTopicsWithServerValidatedSources() throws Exception {
        var borrow=catalog.find("borrow").orElseThrow(); var login=catalog.find("login").orElseThrow();
        var answers=List.of(Map.of("faqId","borrow","summary","You can request a book on Library74.","steps",List.of(),"bullets",List.of(),"note","","evidence",List.of(borrow.en().answer().substring(0,25))),
            Map.of("faqId","login","summary","Sign in using your registered account.","steps",List.of(),"bullets",List.of(),"note","","evidence",List.of(login.en().answer().substring(0,25))));
        server.expect(anything()).andRespond(withSuccess(envelope(mapper.writeValueAsString(Map.of("answers",answers)),"STOP"),MediaType.APPLICATION_JSON));
        var composed=selector.compose("How to sign in and borrow?",List.of(borrow,login),"en");
        assertThat(composed.failure()).isNull();
        assertThat(composed.drafts()).extracting(FaqIntentSelector.Draft::faqId).containsExactly("borrow","login");
        assertThat(composed.drafts()).allMatch(d->d.content()!=null);
        server.verify();
    }
    @Test void expiredRequestBudgetStopsBeforeCallingProvider() {
        selector.startBudget(Duration.ofSeconds(1));
        assertThat(selector.plan("Hello",com.library.recommendation.application.chatbot.ChatbotTool.available(false),"en",List.of(),"").failure()).isEqualTo("REQUEST_TIMEOUT");
        selector.endBudget(); server.verify();
    }

    String composition(String summary, List<String> evidence) throws Exception {
        return mapper.writeValueAsString(Map.of("faqId", "borrow", "summary", summary,
            "steps", List.of("Đăng nhập vào Library74."), "bullets", List.of(), "note", "", "evidence", evidence));
    }

    @Test void nativeToolCallingAdvertisesOnlyAllowedReadToolsAndParsesStructuredCall() throws Exception {
        var tools = com.library.recommendation.application.chatbot.ChatbotTool.available(false);
        String payload = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content", Map.of("parts", List.of(
            Map.of("functionCall", Map.of("name", "search_catalog", "args", Map.of("query", "Clean Code")))))))));
        server.expect(requestTo(url(PRIMARY)))
            .andExpect(jsonPath("$.tools[0].functionDeclarations.length()").value(7))
            .andExpect(jsonPath("$.generationConfig.responseMimeType").doesNotExist())
            .andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        var result = selector.chooseTool("Find Clean Code", tools, "en");
        assertThat(result.name()).isEqualTo("search_catalog");
        assertThat(result.arguments().path("query").asText()).isEqualTo("Clean Code");
        assertThat(tools).noneMatch(tool -> tool.personal);
        server.verify();
    }

    @Test void fabricatedToolOrPersonalToolOnAnonymousRequestIsRejectedWithoutBackup() throws Exception {
        String payload = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", "STOP", "content", Map.of("parts", List.of(
            Map.of("functionCall", Map.of("name", "get_my_loans", "args", Map.of()))))))));
        server.expect(anything()).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        assertThat(selector.chooseTool("My loans", com.library.recommendation.application.chatbot.ChatbotTool.available(false), "en")).isNull();
        server.verify();
    }

    @Test void composesFromLocalizedReferencesInOneRequestWithoutTools() throws Exception {
        String reference = catalog.find("borrow").orElseThrow().vi().answer();
        server.expect(requestTo(url(PRIMARY)))
            .andExpect(jsonPath("$.systemInstruction.parts[0].text", org.hamcrest.Matchers.containsString(reference.substring(0, 25))))
            .andExpect(jsonPath("$.systemInstruction.parts[0].text", org.hamcrest.Matchers.containsString("Vietnamese")))
            .andExpect(jsonPath("$.tools").doesNotExist())
            .andRespond(withSuccess(envelope(composition("Bạn có thể gửi yêu cầu mượn trên Library74.",
                List.of(reference.substring(0, 25))), "STOP"), MediaType.APPLICATION_JSON));
        var draft = selector.generate("Tôi muốn biết trình tự mượn một cuốn sách", catalog.entries(), "vi");
        assertThat(draft.content().summary()).isEqualTo("Bạn có thể gửi yêu cầu mượn trên Library74.");
        assertThat(draft.content().steps()).containsExactly("Đăng nhập vào Library74.");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bạn được mượn 999 cuốn trong 777 ngày.", "Mở https://evil.example", "<b>Đăng nhập</b>"})
    void unsafeCompositionsFallBackToSelectedSourceWithoutTryingBackup(String summary) throws Exception {
        String reference = catalog.find("borrow").orElseThrow().vi().answer();
        server.expect(anything()).andRespond(withSuccess(envelope(composition(summary,
            List.of(reference.substring(0, 25))), "STOP"), MediaType.APPLICATION_JSON));
        var draft = selector.generate("Tôi cần hướng dẫn mượn", catalog.entries(), "vi");
        assertThat(draft.faqId()).isEqualTo("borrow");
        assertThat(draft.content()).isNull();
        server.verify();
    }

    @Test void inventedEvidenceFallsBackToSource() throws Exception {
        server.expect(anything()).andRespond(withSuccess(envelope(composition("Hướng dẫn mượn sách.",
            List.of("A fabricated reference")), "STOP"), MediaType.APPLICATION_JSON));
        assertThat(selector.generate("Tôi cần hướng dẫn mượn", catalog.entries(), "vi").content()).isNull();
        server.verify();
    }

    String envelope(String selected, String finish) throws Exception {
        return mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("finishReason", finish,
            "content", Map.of("parts", List.of(Map.of("text", selected)))))));
    }

    @Test void sendsOnlyQuestionAndApprovedTopicsWithNoToolsOrApiKeyInBody() throws Exception {
        server.expect(requestTo(url(PRIMARY)))
            .andExpect(method(HttpMethod.POST)).andExpect(header("x-goog-api-key", "test-key"))
            .andExpect(jsonPath("$.tools").doesNotExist())
            .andExpect(jsonPath("$.generationConfig.temperature").doesNotExist())
            .andExpect(jsonPath("$.generationConfig.topP").doesNotExist())
            .andExpect(jsonPath("$.generationConfig.topK").doesNotExist())
            .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.faqId.enum").isArray())
            .andExpect(jsonPath("$.contents[0].parts[0].text").value("How can I take books home?"))
            .andExpect(jsonPath("$.contents.length()").value(1))
            .andRespond(withSuccess(envelope("{\"faqId\":\"borrow\"}", "STOP"), MediaType.APPLICATION_JSON));
        assertThat(selected(selector, "How can I take books home?", catalog.entries())).isEqualTo("borrow");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"faqId\":\"unknown\"}", "This is a fabricated answer", "null",
        "{\"faqId\":\"borrow\",\"answer\":\"invented text\"}", "{\"faqId\":123}",
        "{\"faqId\":\"borrow\"} {\"extra\":true}", "{\"faqId\":\"NONE\",\"faqId\":\"borrow\"}"})
    void rejectsInvalidOrFreeFormProviderOutput(String payload) throws Exception {
        server.expect(anything()).andRespond(withSuccess(envelope(payload, "STOP"), MediaType.APPLICATION_JSON));
        assertThat(selected(selector, "Question", catalog.entries())).isNull();
        server.verify();
    }

    @Test void explicitNoMatchIsPreserved() throws Exception {
        server.expect(anything()).andRespond(withSuccess(envelope("{\"faqId\":\"NONE\"}", "STOP"), MediaType.APPLICATION_JSON));
        assertThat(selected(selector, "Weather?", catalog.entries())).isEqualTo("NONE");
        server.verify(); // No backup request even though another model might choose an answer.
    }

    @Test void truncatedOutputIsNotAcceptedEvenIfItLooksLikeValidJson() throws Exception {
        server.expect(anything()).andRespond(withSuccess(envelope("{\"faqId\":\"borrow\"}", "MAX_TOKENS"), MediaType.APPLICATION_JSON));
        assertThat(selected(selector, "Question", catalog.entries())).isNull();
    }

    @Test void providerErrorsFailClosedWithoutLeakingBody() {
        server.expect(requestTo(url(PRIMARY))).andRespond(withServerError());
        server.expect(requestTo(url(BACKUP))).andRespond(withServerError());
        assertThat(selected(selector, "Question", catalog.entries())).isNull();
        server.verify();
    }

    @Test void missingKeyDoesNotCallProvider() {
        var unavailable = new FaqIntentSelector(mapper, "", PRIMARY, BACKUP, builder, clock);
        assertThat(selected(unavailable, "Question", catalog.entries())).isNull();
        server.verify();
    }

    static String url(String model) { return "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"; }

    void expectAnswer(String model, String id) throws Exception {
        server.expect(requestTo(url(model))).andRespond(withSuccess(envelope("{\"faqId\":\"" + id + "\"}", "STOP"), MediaType.APPLICATION_JSON));
    }

    @Test void primaryFailureUsesBackupOnceAndThenSkipsPrimaryDuringCooldown() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withServiceUnavailable());
        expectAnswer(BACKUP, "borrow"); expectAnswer(BACKUP, "creator");
        assertThat(selected(selector, "Question one", catalog.entries())).isEqualTo("borrow");
        assertThat(selected(selector, "Question two", catalog.entries())).isEqualTo("creator");
        server.verify();
    }

    @Test void timeoutUsesBackupAndReleasesTheConcurrencySlot() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withException(new SocketTimeoutException("test timeout")));
        expectAnswer(BACKUP, "borrow"); expectAnswer(BACKUP, "creator");
        assertThat(selected(selector, "Question one", catalog.entries())).isEqualTo("borrow");
        assertThat(selected(selector, "Question two", catalog.entries())).isEqualTo("creator");
        server.verify();
    }

    @Test void primaryIsRetriedOnlyAfterRetryAfterCooldown() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "120"));
        expectAnswer(BACKUP, "borrow"); expectAnswer(BACKUP, "creator"); expectAnswer(PRIMARY, "renewal");
        assertThat(selected(selector, "Question one", catalog.entries())).isEqualTo("borrow");
        clock.advance(Duration.ofSeconds(119));
        assertThat(selected(selector, "Question two", catalog.entries())).isEqualTo("creator");
        clock.advance(Duration.ofSeconds(1));
        assertThat(selected(selector, "Question three", catalog.entries())).isEqualTo("renewal");
        server.verify();
    }

    @Test void retryAfterHttpDateIsHonored() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "Mon, 26 Oct 2026 08:02:00 GMT"));
        expectAnswer(BACKUP, "borrow"); expectAnswer(BACKUP, "creator"); expectAnswer(PRIMARY, "renewal");
        assertThat(selected(selector, "One", catalog.entries())).isEqualTo("borrow");
        clock.advance(Duration.ofSeconds(119)); assertThat(selected(selector, "Two", catalog.entries())).isEqualTo("creator");
        clock.advance(Duration.ofSeconds(1)); assertThat(selected(selector, "Three", catalog.entries())).isEqualTo("renewal");
        server.verify();
    }

    @Test void malformedQuotaMetadataStillUsesConservativeDefaultCooldown() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", "not a date").body("not valid JSON"));
        expectAnswer(BACKUP, "borrow"); expectAnswer(BACKUP, "creator"); expectAnswer(PRIMARY, "renewal");
        assertThat(selected(selector, "One", catalog.entries())).isEqualTo("borrow");
        clock.advance(Duration.ofSeconds(59)); assertThat(selected(selector, "Two", catalog.entries())).isEqualTo("creator");
        clock.advance(Duration.ofSeconds(1)); assertThat(selected(selector, "Three", catalog.entries())).isEqualTo("renewal");
        server.verify();
    }

    @Test void dailyQuotaErrorParksPrimaryUntilPacificReset() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
            .contentType(MediaType.APPLICATION_JSON).body("""
                {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
                  "violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}
                """));
        expectAnswer(BACKUP, "borrow"); expectAnswer(BACKUP, "creator"); expectAnswer(PRIMARY, "renewal");
        assertThat(selected(selector, "Question one", catalog.entries())).isEqualTo("borrow");
        clock.advance(Duration.ofHours(1));
        assertThat(selected(selector, "Question two", catalog.entries())).isEqualTo("creator");
        clock.advance(Duration.ofHours(22)); // 07:00 UTC, Pacific midnight on the next day.
        assertThat(selected(selector, "Question three", catalog.entries())).isEqualTo("renewal");
        server.verify();
    }

    @Test void structuredRetryInfoAlsoDelaysTheNextPrimaryAttempt() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"details\":[{\"retryDelay\":\"90.5s\"}]}}"));
        expectAnswer(BACKUP, "borrow"); expectAnswer(BACKUP, "creator"); expectAnswer(PRIMARY, "renewal");
        assertThat(selected(selector, "Question one", catalog.entries())).isEqualTo("borrow");
        clock.advance(Duration.ofSeconds(90));
        assertThat(selected(selector, "Question two", catalog.entries())).isEqualTo("creator");
        clock.advance(Duration.ofMillis(500));
        assertThat(selected(selector, "Question three", catalog.entries())).isEqualTo("renewal");
        server.verify();
    }

    @Test void providerSuccessIsNotBlockedByOldMinuteOrDailyCaps() throws Exception {
        // All 501 sequential calls happen at the same fixed instant. Google is mocked, not bypassed.
        server.expect(org.springframework.test.web.client.ExpectedCount.times(501), requestTo(url(PRIMARY)))
            .andRespond(withSuccess(envelope("{\"faqId\":\"borrow\"}", "STOP"), MediaType.APPLICATION_JSON));
        for (int index = 0; index < 501; index++) {
            assertThat(selected(selector, "Question", catalog.entries())).isEqualTo("borrow");
        }
        server.verify();
    }

    @Test void concurrencyProtectionStillLimitsFourInflightCallsAndReleasesSlots() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        String result = envelope("{\"faqId\":\"borrow\"}", "STOP");
        server.expect(org.springframework.test.web.client.ExpectedCount.times(4), requestTo(url(PRIMARY)))
            .andRespond(request -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Test timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new java.io.IOException("Test interrupted");
                }
                return withSuccess(result, MediaType.APPLICATION_JSON).createResponse(request);
            });
        expectAnswer(PRIMARY, "creator");
        var workers = Executors.newFixedThreadPool(4);
        try {
            var pending = new ArrayList<Future<String>>();
            for (int index = 0; index < 4; index++) pending.add(workers.submit(() -> selected(selector, "Question", catalog.entries())));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(selected(selector, "Fifth concurrent request", catalog.entries())).isNull();
            release.countDown();
            for (var response : pending) assertThat(response.get(10, TimeUnit.SECONDS)).isEqualTo("borrow");
            assertThat(selected(selector, "Request after completion", catalog.entries())).isEqualTo("creator");
            server.verify();
        } finally {
            release.countDown(); workers.shutdownNow();
        }
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 402, 403})
    void clientErrorsDoNotInvokeBackupOrImmediatelyRepeatBadConfiguration(int status) {
        server.expect(requestTo(url(PRIMARY))).andRespond(withStatus(org.springframework.http.HttpStatusCode.valueOf(status)));
        assertThat(selected(selector, "One", catalog.entries())).isNull();
        assertThat(selected(selector, "Two", catalog.entries())).isNull();
        server.verify();
    }

    @Test void missingPrimaryModelUsesBackup() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withResourceNotFound());
        expectAnswer(BACKUP, "borrow");
        assertThat(selected(selector, "Question", catalog.entries())).isEqualTo("borrow");
        server.verify();
    }

    @Test void noneFromBackupIsNotTreatedAsAFailureToRetry() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withServerError());
        expectAnswer(BACKUP, "NONE");
        assertThat(selected(selector, "Unrelated question", catalog.entries())).isEqualTo("NONE");
        server.verify();
    }

    @Test void safetyBlockDoesNotInvokeBackup() throws Exception {
        server.expect(requestTo(url(PRIMARY))).andRespond(withSuccess(envelope("", "SAFETY"), MediaType.APPLICATION_JSON));
        assertThat(selected(selector, "Question", catalog.entries())).isNull();
        server.verify();
    }

    @Test void blankFallbackDisablesItAndDuplicateModelIsNeverCalledTwice() {
        for (String backup : new String[]{"", PRIMARY}) {
            var localBuilder = RestClient.builder();
            var localServer = MockRestServiceServer.bindTo(localBuilder).build();
            var single = new FaqIntentSelector(mapper, "test-key", PRIMARY, backup, localBuilder, clock);
            localServer.expect(requestTo(url(PRIMARY))).andRespond(withServerError());
            assertThat(selected(single, "Question", catalog.entries())).isNull();
            localServer.verify();
        }
    }
}
