package com.library.recommendation.application.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.infrastructure.ai.ChatbotKnowledgeClient;
import com.library.recommendation.infrastructure.ai.GeminiChatClient;
import com.library.recommendation.infrastructure.persistence.ChatbotReadQueries;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Authenticated tool orchestration and transport; the final Markdown belongs to Gemini. */
@Service
public class ChatbotService {
    private static final int MAX_ROUNDS=6, MAX_CALLS=12;
    private final FaqCatalog catalog;
    private final GeminiChatClient model;
    private final ChatbotKnowledgeClient knowledge;
    private final ChatbotReadQueries reads;
    private final ChatConversationStore conversations;
    private final ObjectMapper mapper;
    private final String instructions;
    public ChatbotService(FaqCatalog catalog,GeminiChatClient model,ChatbotKnowledgeClient knowledge,
        ChatbotReadQueries reads,ChatConversationStore conversations,ObjectMapper mapper) throws IOException {
        this.catalog=catalog;this.model=model;this.knowledge=knowledge;this.reads=reads;this.conversations=conversations;this.mapper=mapper;
        instructions=new ClassPathResource("chatbot/system-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
    }
    public ChatbotReply answer(String question,String language,Long readerId) { return answer(question,language,readerId,null); }
    public ChatbotReply answer(String question,String language,Long readerId,UUID id) {
        String lang="en".equals(language)?"en":"vi";
        var session=conversations.begin(id,readerId,lang);
        if(session.busy()) return failure("CONVERSATION_BUSY",lang).withContext(session.id(),false);
        boolean finished=false;
        try {
            model.startBudget(Duration.ofSeconds(60));
            var contents=new ArrayList<JsonNode>();
            for(var turn:session.context().turns()) {
                contents.add(message("user",turn.question())); contents.add(message("model",turn.answer()));
            }
            contents.add(message("user",question));
            var allowed=ChatbotTool.available(readerId!=null);
            String prompt=prompt(lang,readerId!=null);
            int calls=0;
            for(int round=0;round<=MAX_ROUNDS;round++) {
                boolean finalRound=round==MAX_ROUNDS || calls>=MAX_CALLS;
                var step=model.next(prompt+(finalRound?"\nNo further tool calls are available for this turn. Answer from the results already received or explain what remains unknown.":""),
                    List.copyOf(contents),finalRound?List.of():allowed);
                if(step.failure()!=null) return failure(step.failure(),lang).withContext(session.id(),session.reset());
                if(step.calls().isEmpty()) {
                    // Forward model text verbatim, including refusal, formatting and length choices.
                    boolean saved=conversations.finish(session,question,step.text()); finished=true;
                    return new ChatbotReply("ANSWERED",step.text(),"markdown",step.truncated()?"OUTPUT_TRUNCATED":null,
                        session.id(),session.reset() || !saved);
                }
                if(finalRound) return failure("TOOL_LIMIT_REACHED",lang).withContext(session.id(),session.reset());
                contents.add(step.content().deepCopy()); // Includes native call IDs and thought signatures.
                var responses=mapper.createArrayNode();
                for(var call:step.calls()) {
                    Object data=++calls>MAX_CALLS?error("TOOL_LIMIT_REACHED",false):execute(call,allowed,readerId,lang);
                    var response=responses.addObject().putObject("functionResponse");
                    response.put("name",call.name());
                    if(call.id()!=null) response.put("id",call.id());
                    response.set("response",mapper.valueToTree(data));
                }
                var result=mapper.createObjectNode().put("role","user"); result.set("parts",responses);contents.add(result);
            }
            return failure("TOOL_LIMIT_REACHED",lang).withContext(session.id(),session.reset());
        } catch(Exception unavailable) {
            return failure("CHATBOT_UNAVAILABLE",lang).withContext(session.id(),session.reset());
        } finally {
            model.endBudget();
            if(!finished) conversations.release(session);
        }
    }
    private Object execute(GeminiChatClient.ToolCall call,List<ChatbotTool> allowed,Long readerId,String lang) {
        try {
            ChatbotTool tool;
            try { tool=ChatbotTool.valueOf(call.name()); }
            catch(IllegalArgumentException unknown) { return error("UNKNOWN_TOOL",false); }
            if(!allowed.contains(tool)) return error("AUTH_REQUIRED",false);
            var args=ChatbotReadQueries.validate(tool,call.arguments());
            if(tool==ChatbotTool.search_library_help) {
                var retrieved=knowledge.retrieveResult(args.getOrDefault("query",""),lang);
                var entries=retrieved.failure()==null && !retrieved.entries().isEmpty()?retrieved.entries():catalog.entries();
                return Map.of("retrievalStatus",retrieved.failure()==null?"complete":retrieved.failure(),
                    "documents",entries.stream().map(e->Map.of("id",e.id(),"title",e.copy(lang).question(),"content",e.copy(lang).answer(),"url",e.referencePath())).toList());
            }
            return reads.execute(tool,call.arguments(),tool.personal?readerId:null,lang);
        } catch(IllegalArgumentException invalid) { return error("INVALID_ARGUMENTS",false); }
        catch(SecurityException forbidden) { return error("AUTH_REQUIRED",false); }
        catch(Exception unavailable) { return error("LOOKUP_UNAVAILABLE",true); }
    }
    private String prompt(String lang,boolean reader) throws IOException {
        return instructions+"\nResponse language: "+lang+". Authenticated reader: "+reader+". Vietnam date: "+LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
            +"\nAPPROVED LIBRARY74 GUIDANCE:\n"+mapper.writeValueAsString(catalog.entries().stream().map(e->Map.of(
                "id",e.id(),"question",e.copy(lang).question(),"answer",e.copy(lang).answer(),"url",e.referencePath())).toList());
    }
    private JsonNode message(String role,String text) { return mapper.valueToTree(Map.of("role",role,"parts",List.of(Map.of("text",text)))); }
    private static Object error(String code,boolean retryable) { return Map.of("error",Map.of("code",code,"retryable",retryable)); }
    public void clear(UUID id,Long readerId) { conversations.clear(id,readerId); }
    private static ChatbotReply failure(String code,String lang) {
        boolean en="en".equals(lang);
        String text=switch(code) {
            case "AI_QUOTA_EXCEEDED" -> en?"The AI provider's quota is exhausted. Please try again later.":"Nhà cung cấp AI đang hết hạn mức. Bạn hãy thử lại sau.";
            case "CONVERSATION_BUSY","AI_BUSY" -> en?"A response is already being processed. Please wait and retry.":"Trợ lý đang xử lý câu trả lời. Bạn chờ một chút rồi thử lại nhé.";
            case "AI_NOT_CONFIGURED","AI_CONFIGURATION_ERROR" -> en?"The AI service is not configured correctly. Please contact the library.":"Dịch vụ AI chưa được cấu hình phù hợp. Bạn vui lòng liên hệ thư viện.";
            case "REQUEST_TIMEOUT","AI_TIMEOUT" -> en?"The AI service took too long to respond. Please retry.":"Dịch vụ AI phản hồi quá lâu. Bạn hãy thử lại nhé.";
            default -> en?"The assistant service is temporarily unavailable. Please retry later.":"Dịch vụ trợ lý đang gặp lỗi tạm thời. Bạn hãy thử lại sau.";
        };
        return new ChatbotReply("UNAVAILABLE",text,"markdown",code,null,false);
    }
}
