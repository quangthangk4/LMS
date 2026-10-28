package com.library.recommendation.application.chatbot;

/** The reply text is Gemini Markdown; other fields describe transport/session state only. */
public record ChatbotReply(String status,String answer,String format,String reasonCode,String conversationId,boolean contextReset) {
    public ChatbotReply withContext(String id,boolean reset) { return new ChatbotReply(status,answer,format,reasonCode,id,reset); }
}
