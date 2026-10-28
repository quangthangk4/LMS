package com.library.recommendation.application.chatbot;

import com.library.recommendation.infrastructure.ai.FaqIntentSelector;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FaqChatbotService {
    public record Suggestion(String id, String question) {}
    public record Answer(String status, String faqId, String answer, String sourceTitle, String sourcePath, FaqContent content) {}
    private final FaqCatalog catalog;
    private final FaqIntentSelector selector;
    public FaqChatbotService(FaqCatalog catalog, FaqIntentSelector selector) {
        this.catalog = catalog; this.selector = selector;
    }

    public List<Suggestion> suggestions(String language) {
        return catalog.entries().stream().map(entry -> new Suggestion(entry.id(), entry.copy(language).question())).toList();
    }

    public Answer answer(String question, String language) {
        var exact = catalog.exactMatch(question);
        if (exact.isPresent()) return approved(exact.get(), language);
        var draft = selector.generate(question, catalog.entries(), language);
        String id = draft == null ? null : draft.faqId();
        if (id != null && !"NONE".equals(id)) {
            var entry = catalog.find(id);
            if (entry.isPresent()) {
                if (draft.content() == null) return approved(entry.get(), language);
                return new Answer("ANSWERED", id, draft.content().text(), entry.get().copy(language).question(),
                    entry.get().referencePath(), draft.content());
            }
        }
        boolean en = "en".equals(language);
        if (id == null || (!"NONE".equals(id) && catalog.find(id).isEmpty())) {
            return new Answer("UNAVAILABLE", null, en
                ? "I cannot answer right now. Please choose a suggested question or open the user guide."
                : "Tôi chưa thể trả lời lúc này. Bạn hãy chọn câu hỏi gợi ý hoặc mở hướng dẫn sử dụng.", null, null, null);
        }
        return new Answer("UNSUPPORTED", null, en
            ? "I could not find this information in the Library74 guide. Please ask about using the website or contact the library."
            : "Tôi chưa tìm thấy thông tin này trong hướng dẫn Library74. Bạn có thể hỏi về cách sử dụng website hoặc liên hệ thư viện.", null, null, null);
    }

    private Answer approved(FaqCatalog.Entry entry, String language) {
        var copy = entry.copy(language);
        return new Answer("ANSWERED", entry.id(), copy.answer(), copy.question(), entry.referencePath(), FaqContent.fromSource(copy.answer()));
    }

}
