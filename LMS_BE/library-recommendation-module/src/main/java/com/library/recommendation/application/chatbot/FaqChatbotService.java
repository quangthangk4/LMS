package com.library.recommendation.application.chatbot;

import java.util.List;
import org.springframework.stereotype.Service;

/** Suggested questions only; answers are always produced by Gemini. */
@Service
public class FaqChatbotService {
    public record Suggestion(String id,String question) {}
    private final FaqCatalog catalog;
    public FaqChatbotService(FaqCatalog catalog) { this.catalog=catalog; }
    public List<Suggestion> suggestions(String language) {
        return catalog.entries().stream().map(e->new Suggestion(e.id(),e.copy(language).question())).toList();
    }
}
