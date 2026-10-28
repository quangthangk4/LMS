package com.library.recommendation.presentation.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.application.chatbot.FaqChatbotService;
import com.library.recommendation.application.chatbot.ChatbotService;
import com.library.recommendation.application.chatbot.ChatbotReply;
import com.library.shared.util.SecurityEvaluator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class FaqChatbotControllerTest {
    final ObjectMapper mapper = new ObjectMapper();
    FaqChatbotService service;
    ChatbotService chat;
    SecurityEvaluator security;
    MockMvc mvc;

    @BeforeEach void setUp() {
        service = mock(FaqChatbotService.class);
        chat = mock(ChatbotService.class); security = mock(SecurityEvaluator.class);
        var validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new FaqChatbotController(service, chat, security))
            .setValidator(validator).build();
    }

    @Test void suggestionsArePublicContentWithoutAnswersOrAccountFields() throws Exception {
        when(service.suggestions("en")).thenReturn(List.of(new FaqChatbotService.Suggestion("borrow", "How do I borrow?")));
        mvc.perform(get("/api/v1/chatbot/faqs").param("language", "en"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data[0].id").value("borrow"))
            .andExpect(jsonPath("$.data[0].answer").doesNotExist());
    }

    @Test void validRequestIsTrimmedAndReturnsReviewedAnswer() throws Exception {
        when(chat.answer("Cách mượn sách?", "vi", null)).thenReturn(new ChatbotReply(
            "ANSWERED", "**Đăng nhập**, rồi tìm sách.\n\n[Xem hướng dẫn](/publicpage/guide#borrow)", "markdown", null,null,false));
        mvc.perform(post("/api/v1/chatbot/messages").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("message", " Cách mượn sách? ", "language", "vi"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.answer").value("**Đăng nhập**, rồi tìm sách.\n\n[Xem hướng dẫn](/publicpage/guide#borrow)"))
            .andExpect(jsonPath("$.data.format").value("markdown"))
            .andExpect(jsonPath("$.data.cards").doesNotExist());
    }

    @ParameterizedTest @ValueSource(strings = {"", "   "})
    void emptyMessagesAreRejected(String value) throws Exception {
        mvc.perform(post("/api/v1/chatbot/messages").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("message", value)))).andExpect(status().isBadRequest());
        verifyNoInteractions(service, chat);
    }

    @Test void longMessageOrUnsupportedLanguageIsRejectedBeforeProviderCall() throws Exception {
        for (var payload : List.of(Map.of("message", "a".repeat(501)), Map.of("message", "hello", "language", "fr"))) {
            mvc.perform(post("/api/v1/chatbot/messages").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void providerUnavailableReturnsTheFaqFallbackWithoutALocalQuotaError() throws Exception {
        when(chat.answer(anyString(), any(), isNull())).thenReturn(
            new ChatbotReply("UNAVAILABLE", "The AI service is unavailable.","markdown","AI_UNAVAILABLE",null,false));
        mvc.perform(post("/api/v1/chatbot/messages").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"hello\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
    }

    @Test void personalChatRejectsAnonymousRequestsBeforeToolSelection() throws Exception {
        mvc.perform(post("/api/v1/chatbot/my/messages").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"My fines\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(chat);
    }

    @Test void authenticatedReaderIdentityComesOnlyFromSecurityContext() throws Exception {
        when(security.isAuthenticated()).thenReturn(true);
        when(security.hasAnyRole("STUDENT", "FACULTY")).thenReturn(true);
        when(security.getCurrentUserId()).thenReturn(7L);
        when(chat.answer("My fines", "en", 7L)).thenReturn(new ChatbotReply("ANSWERED", "Your fines","markdown",null,null,false));
        mvc.perform(post("/api/v1/chatbot/my/messages").contentType(MediaType.APPLICATION_JSON)
            .content("{\"message\":\"My fines\",\"language\":\"en\",\"userId\":999}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(chat).answer("My fines", "en", 7L);
    }
}
