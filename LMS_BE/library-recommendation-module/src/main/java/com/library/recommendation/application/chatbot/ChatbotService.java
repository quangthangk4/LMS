package com.library.recommendation.application.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.library.recommendation.infrastructure.ai.ChatbotKnowledgeClient;
import com.library.recommendation.infrastructure.ai.FaqIntentSelector;
import com.library.recommendation.infrastructure.persistence.ChatbotReadQueries;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Bounded read-only orchestration. Tool proposals never authorize access. */
@Service
public class ChatbotService {
    private final FaqCatalog catalog;
    private final FaqChatbotService faq;
    private final FaqIntentSelector model;
    private final ChatbotKnowledgeClient knowledge;
    private final ChatbotReadQueries reads;
    private final ChatConversationStore conversations;
    @Autowired
    public ChatbotService(FaqCatalog catalog,FaqChatbotService faq,FaqIntentSelector model,
        ChatbotKnowledgeClient knowledge,ChatbotReadQueries reads,ChatConversationStore conversations) {
        this.catalog=catalog; this.faq=faq; this.model=model; this.knowledge=knowledge; this.reads=reads; this.conversations=conversations;
    }
    public ChatbotService(FaqCatalog c,FaqChatbotService f,FaqIntentSelector m,ChatbotKnowledgeClient k,ChatbotReadQueries r) { this(c,f,m,k,r,null); }
    public ChatbotReply answer(String question,String language,Long readerId) { return answer(question,language,readerId,null); }
    public ChatbotReply answer(String question,String language,Long readerId,UUID conversationId) {
        String lang="en".equals(language)?"en":"vi";
        var session=conversations==null?null:conversations.begin(conversationId,readerId,lang);
        if(session!=null && session.busy()) return failure("CONVERSATION_BUSY",lang).withContext(session.id(),false);
        ChatbotReply answer;
        var reference=new String[]{null};
        var privateLookup=new boolean[]{false};
        try {
            model.startBudget(java.time.Duration.ofSeconds(25));
            answer=respond(question,lang,readerId,session==null?"":session.context().toString(),reference,privateLookup);
        } catch(Exception unexpected) { answer=failure("LOOKUP_UNAVAILABLE",lang); }
        finally { model.endBudget(); }
        if(session!=null) {
            // Never copy personal result prose, amounts or cards into model history.
            var history=privateLookup[0]?ChatbotReply.message("ANSWERED","Response delivered directly to the reader."):answer;
            conversations.finish(session,question,history,reference[0]);
            answer=answer.withContext(session.id(),session.reset());
        }
        return answer;
    }
    public void clear(UUID id,Long readerId) { if(conversations!=null) conversations.clear(id,readerId); }

    private ChatbotReply respond(String question,String lang,Long readerId,String context,String[] reference,boolean[] privateLookup) {
        var exact=catalog.exactMatch(question);
        if(exact.isPresent()) {
            var reply=ChatbotReply.legacy(faq.answer(question,lang));
            if(Set.of("policy","renewal","deposit","fines","pickup").contains(exact.get().id())) {
                try { return combine(List.of(reply,reads.execute(ChatbotTool.get_current_circulation_policy,JsonNodeFactory.instance.objectNode(),null,lang))); }
                catch(Exception unavailable) { return combine(List.of(reply,failure("LOOKUP_UNAVAILABLE",lang))); }
            }
            return reply;
        }
        var allowed=ChatbotTool.available(readerId!=null);
        var transcript=new ArrayList<Map<String,Object>>();
        var results=new ArrayList<ChatbotReply>();
        var help=new LinkedHashMap<String,FaqCatalog.Entry>();
        var seen=new HashSet<String>();
        int executions=0;
        boolean done=false;
        for(int round=0;round<3 && executions<4 && !done;round++) {
            var plan=model.plan(question,allowed,lang,transcript,context);
            if(plan==null || plan.failure()!=null) {
                results.add(failure(plan==null?"AI_INVALID_RESPONSE":plan.failure(),lang)); break;
            }
            transcript.add(Map.of("role","model","parts",plan.modelContent().path("parts")));
            var responses=new ArrayList<Map<String,Object>>();
            for(var call:plan.calls()) {
                if("respond_to_reader".equals(call.name())) {
                    String kind=call.arguments().path("kind").asText();
                    if(!"DONE".equals(kind)) results.add(social(kind,call.arguments().path("message").asText(),lang));
                    else if(results.isEmpty() && help.isEmpty()) results.add(failure("NO_MATCH",lang));
                    done=true;
                    responses.add(functionResponse(call,Map.of("delivered",true))); continue;
                }
                if(executions>=4 || !seen.add(call.name()+call.arguments())) {
                    responses.add(functionResponse(call,Map.of("limitReached",true))); done=true; continue;
                }
                executions++;
                try {
                    var tool=ChatbotTool.valueOf(call.name());
                    if(!allowed.contains(tool)) {
                        results.add(social("AUTH_REQUIRED","",lang)); done=true;
                        responses.add(functionResponse(call,Map.of("authenticationRequired",true))); continue;
                    }
                    var args=ChatbotReadQueries.validate(tool,call.arguments());
                    if(tool==ChatbotTool.search_library_help) {
                        if(!model.hasBudget(java.time.Duration.ofSeconds(6))) { results.add(failure("REQUEST_TIMEOUT",lang)); done=true; continue; }
                        var retrieved=knowledge.retrieveResult(args.getOrDefault("query",question),lang);
                        if(retrieved.failure()!=null) results.add(failure(retrieved.failure(),lang));
                        else if(retrieved.entries().isEmpty()) results.add(failure("NO_MATCH",lang));
                        else retrieved.entries().stream().limit(4).forEach(entry->help.putIfAbsent(entry.id(),entry));
                        responses.add(functionResponse(call,Map.of("topics",retrieved.entries().stream().map(FaqCatalog.Entry::id).toList(),
                            "state",retrieved.failure()==null?"complete":retrieved.failure())));
                    } else {
                        if(tool.personal) privateLookup[0]=true;
                        var result=reads.execute(tool,call.arguments(),tool.personal?readerId:null,lang);
                        results.add(result);
                        if(!tool.personal) {
                            rememberBook(result,call.arguments(),reference);
                            responses.add(functionResponse(call,Map.of("answer",result.answer(),"cards",result.cards(),"total",result.total()==null?0:result.total())));
                        } else responses.add(functionResponse(call,Map.of("deliveredDirectly",true)));
                    }
                } catch(IllegalArgumentException invalid) {
                    results.add(social("CLARIFY","",lang)); done=true;
                    responses.add(functionResponse(call,Map.of("invalidArguments",true)));
                } catch(Exception unavailable) {
                    results.add(failure("LOOKUP_UNAVAILABLE",lang)); done=true;
                    responses.add(functionResponse(call,Map.of("unavailable",true)));
                }
            }
            if(!responses.isEmpty()) transcript.add(Map.of("role","user","parts",responses));
            if(!help.isEmpty() && plan.calls().stream().allMatch(c->c.name().equals("search_library_help"))) done=true;
            // A successful single independent lookup does not require a paid DONE round.
            if(plan.calls().size()==1 && !"search_catalog".equals(plan.calls().getFirst().name())
                && !results.isEmpty() && !"CLARIFY".equals(results.get(results.size()-1).status())) done=true;
        }
        if(!help.isEmpty()) {
            var entries=help.values().stream().limit(8).toList();
            var composed=model.compose(question,entries,lang);
            if(composed==null || composed.failure()!=null) {
                entries.stream().limit(4).forEach(e->results.add(helpReply(e,null,lang)));
                results.add(failure(composed==null?"AI_INVALID_RESPONSE":composed.failure(),lang));
            } else if(composed.drafts().isEmpty()) results.add(failure("NO_MATCH",lang));
            else for(var draft:composed.drafts()) entries.stream().filter(e->e.id().equals(draft.faqId())).findFirst()
                .ifPresent(entry->results.add(helpReply(entry,draft.content(),lang)));
        }
        return results.isEmpty()?failure("NO_MATCH",lang):combine(results);
    }
    private static Map<String,Object> functionResponse(FaqIntentSelector.ToolCall call,Map<String,Object> response) {
        var result=new LinkedHashMap<String,Object>();
        result.put("name",call.name()); result.put("response",response);
        if(call.id()!=null) result.put("id",call.id());
        return Map.of("functionResponse",result);
    }
    private static void rememberBook(ChatbotReply reply,JsonNode args,String[] reference) {
        var books=reply.cards().stream().filter(c->c.path()!=null && c.path().matches("/publicpage/book/[1-9][0-9]*"))
            .map(c->c.path()+" title="+c.title()).distinct().toList();
        if(books.size()==1) reference[0]=books.get(0)+" campus="+args.path("branch").asText("ANY");
    }
    private static ChatbotReply helpReply(FaqCatalog.Entry entry,FaqContent content,String lang) {
        var copy=entry.copy(lang);
        var body=content==null?FaqContent.fromSource(copy.answer()):content;
        return new ChatbotReply("ANSWERED",entry.id(),body.text(),copy.question(),entry.sourcePath(),body,
            List.of(new ChatbotReply.Source(copy.question(),entry.sourcePath())),List.of(),null,null,null);
    }
    private static ChatbotReply combine(List<ChatbotReply> results) {
        if(results.size()==1) return results.get(0);
        boolean answered=results.stream().anyMatch(r->"ANSWERED".equals(r.status()));
        return new ChatbotReply(answered?"ANSWERED":results.get(0).status(),null,"",null,null,null,List.of(),List.of(),null,null,null,
            results.stream().map(ChatbotReply::reasonCode).filter(Objects::nonNull).findFirst().orElse(null),null,false,List.copyOf(results));
    }
    private static ChatbotReply social(String kind,String text,String lang) {
        boolean en="en".equals(lang);
        return switch(kind) {
            case "AUTH_REQUIRED" -> new ChatbotReply("AUTH_REQUIRED",null,en?"Please sign in with a reader account to view your own records.":"Bạn hãy đăng nhập bằng tài khoản bạn đọc để xem dữ liệu của mình.",
                en?"Sign in":"Đăng nhập","/publicpage/login",null,List.of(),List.of(),null,null,null);
            case "CLARIFY" -> ChatbotReply.message("CLARIFY",en?"Which book, campus or information would you like to look up?":"Bạn muốn tra cứu sách nào, tại cơ sở nào hoặc cần hướng dẫn vấn đề gì?");
            case "THANKS" -> ChatbotReply.message("ANSWERED",en?"You're welcome! If you need help finding a book or using Library74, just ask.":"Không có gì! Nếu cần tìm sách hoặc hướng dẫn sử dụng Library74, bạn cứ hỏi nhé.");
            case "GREETING","INTRO","SOCIAL" -> ChatbotReply.message("ANSWERED",
                en?"Hello! I'm Library74's assistant. I can guide website use, find books and help you look up your own library records. What would you like help with?"
                    :"Xin chào! Tôi là trợ lý Library74, có thể hướng dẫn sử dụng, tìm sách và giúp bạn tra cứu thông tin của mình. Bạn cần hỗ trợ gì?");
            default -> ChatbotReply.message("UNSUPPORTED",en?"I help with Library74 guidance and read-only lookups. I cannot perform actions, answer chapter questions or use external information."
                :"Tôi hỗ trợ hướng dẫn và tra cứu thông tin Library74. Tôi không thao tác thay bạn, trả lời nội dung chương sách hoặc dùng thông tin bên ngoài.");
        };
    }
    private static ChatbotReply failure(String code,String lang) {
        boolean en="en".equals(lang);
        String text=switch(code) {
            case "NO_MATCH" -> en?"I could not find suitable information in Library74's guidance. Please clarify your question or contact the library.":"Tôi chưa tìm thấy thông tin phù hợp trong hướng dẫn Library74. Bạn có thể nói rõ hơn hoặc liên hệ thư viện.";
            case "AI_QUOTA_EXCEEDED" -> en?"The AI provider's quota is temporarily exhausted. Please retry later or choose a suggested question.":"Nhà cung cấp AI đang hết hạn mức. Bạn hãy thử lại sau hoặc chọn câu hỏi gợi ý.";
            case "CONVERSATION_BUSY","AI_BUSY" -> en?"A response is already being processed. Please wait a moment and retry.":"Trợ lý đang xử lý câu trả lời. Bạn chờ một chút rồi thử lại nhé.";
            case "KNOWLEDGE_NOT_READY" -> en?"The guidance index is updating. Please retry shortly or choose a suggested question.":"Kho hướng dẫn đang được cập nhật. Bạn hãy thử lại sau hoặc chọn câu hỏi gợi ý.";
            default -> code.startsWith("KNOWLEDGE")
                ? (en?"The guidance lookup service is temporarily unavailable. This is a service error, not missing guidance. Please retry later.":"Dịch vụ tìm hướng dẫn đang gặp lỗi tạm thời, chưa thể kết luận là không có thông tin. Bạn hãy thử lại sau.")
                : (en?"The assistant's lookup or AI service is temporarily unavailable. Please retry later or choose a suggested question.":"Dịch vụ tra cứu hoặc AI đang gặp lỗi tạm thời. Bạn hãy thử lại sau hoặc chọn câu hỏi gợi ý.");
        };
        return ChatbotReply.failure("NO_MATCH".equals(code)?"NO_MATCH":"UNAVAILABLE",code,text);
    }
}
