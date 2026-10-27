package com.library.recommendation.presentation.controller;

import com.library.recommendation.application.chatbot.FaqChatbotService;
import com.library.recommendation.application.chatbot.ChatbotService;
import com.library.recommendation.application.chatbot.ChatbotReply;
import com.library.shared.util.SecurityEvaluator;
import com.library.shared.dto.ApiResponseApp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/chatbot")
public class FaqChatbotController {
    public record Question(@NotBlank @Size(max = 500) String message,
        @Pattern(regexp = "vi|en") String language, java.util.UUID conversationId) {}

    // Default MVC validation logging includes rejected field values. Chat questions may
    // contain private text, so return a generic error without logging the request.
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalidQuestion() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }
    private final FaqChatbotService service;
    private final ChatbotService chat;
    private final SecurityEvaluator security;
    public FaqChatbotController(FaqChatbotService service, ChatbotService chat, SecurityEvaluator security) {
        this.service = service; this.chat = chat; this.security = security;
    }

    @GetMapping("/faqs")
    public ResponseEntity<ApiResponseApp<List<FaqChatbotService.Suggestion>>> faqs(
        @RequestParam(name = "language", defaultValue = "vi") String language) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponseApp.success(service.suggestions(language)));
    }

    @PostMapping("/messages")
    public ResponseEntity<ApiResponseApp<ChatbotReply>> answer(@Valid @RequestBody Question request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponseApp.success(request.conversationId()==null ? chat.answer(request.message().trim(),request.language(),null)
                : chat.answer(request.message().trim(), request.language(), null,request.conversationId())));
    }

    @PostMapping("/my/messages")
    public ResponseEntity<ApiResponseApp<ChatbotReply>> personalAnswer(@Valid @RequestBody Question request) {
        if (!security.isAuthenticated()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        Long readerId = security.hasAnyRole("STUDENT", "FACULTY") ? security.getCurrentUserId() : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(ApiResponseApp.success(request.conversationId()==null ? chat.answer(request.message().trim(),request.language(),readerId)
                : chat.answer(request.message().trim(), request.language(), readerId,request.conversationId())));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> clear(@PathVariable java.util.UUID id) {
        chat.clear(id,null); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @DeleteMapping("/my/conversations/{id}")
    public ResponseEntity<Void> clearPersonal(@PathVariable java.util.UUID id) {
        if(!security.isAuthenticated()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        Long readerId=security.hasAnyRole("STUDENT","FACULTY")?security.getCurrentUserId():null;
        chat.clear(id,readerId); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
