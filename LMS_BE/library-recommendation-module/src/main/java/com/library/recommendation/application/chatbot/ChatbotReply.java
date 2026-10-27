package com.library.recommendation.application.chatbot;

import java.time.Instant;
import java.util.List;

public record ChatbotReply(String status, String faqId, String answer, String sourceTitle, String sourcePath,
                           FaqContent content, List<Source> sources, List<Card> cards, Long total,
                           Integer page, Instant retrievedAt, String reasonCode, String conversationId,
                           boolean contextReset, List<ChatbotReply> sections) {
    public ChatbotReply(String status, String faqId, String answer, String sourceTitle, String sourcePath,
        FaqContent content, List<Source> sources, List<Card> cards, Long total, Integer page, Instant retrievedAt) {
        this(status,faqId,answer,sourceTitle,sourcePath,content,sources,cards,total,page,retrievedAt,null,null,false,List.of());
    }
    public ChatbotReply withContext(String id, boolean reset) {
        return new ChatbotReply(status,faqId,answer,sourceTitle,sourcePath,content,sources,cards,total,page,retrievedAt,reasonCode,id,reset,sections);
    }
    public static ChatbotReply failure(String status, String reason, String text) {
        return new ChatbotReply(status,null,text,null,null,null,List.of(),List.of(),null,null,null,reason,null,false,List.of());
    }
    public record Source(String title, String path) {}
    public record Fact(String label, String value) {}
    public record Card(String id, String title, String subtitle, String imageUrl, String path, List<Fact> facts) {}

    public static ChatbotReply message(String status, String message) {
        return new ChatbotReply(status, null, message, null, null, null, List.of(), List.of(), null, null, null);
    }
    public static ChatbotReply legacy(FaqChatbotService.Answer answer) {
        var sources = answer.sourcePath() == null ? List.<Source>of() : List.of(new Source(answer.sourceTitle(), answer.sourcePath()));
        return new ChatbotReply(answer.status(), answer.faqId(), answer.answer(), answer.sourceTitle(), answer.sourcePath(),
            answer.content(), sources, List.of(), null, null, null);
    }
}
