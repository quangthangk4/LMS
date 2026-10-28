package com.library.recommendation.application.chatbot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.infrastructure.ai.ChatbotKnowledgeClient;
import com.library.recommendation.infrastructure.ai.GeminiChatClient;
import com.library.recommendation.infrastructure.persistence.ChatbotReadQueries;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatbotServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    GeminiChatClient model; ChatbotKnowledgeClient knowledge; ChatbotReadQueries reads; ChatConversationStore store; ChatbotService service;
    ChatConversationStore.Session session;
    @BeforeEach void setup() throws Exception {
        model=mock(GeminiChatClient.class);knowledge=mock(ChatbotKnowledgeClient.class);reads=mock(ChatbotReadQueries.class);store=mock(ChatConversationStore.class);
        session=new ChatConversationStore.Session(UUID.randomUUID().toString(),"key","expected",new ChatConversationStore.Context("vi",List.of()),null,false,false);
        when(store.begin(any(),any(),anyString())).thenReturn(session);when(store.finish(any(),anyString(),anyString())).thenReturn(true);
        service=new ChatbotService(new FaqCatalog(mapper),model,knowledge,reads,store,mapper);
    }
    GeminiChatClient.Step text(String value) {return new GeminiChatClient.Step(mapper.createObjectNode(),List.of(),value,null,false);}
    GeminiChatClient.Step call(String name,String args) throws Exception {
        var content=mapper.readTree("{\"role\":\"model\",\"parts\":[{\"thoughtSignature\":\"signature\",\"functionCall\":{\"name\":\""+name+"\",\"id\":\"call-id\",\"args\":"+args+"}}]}");
        return new GeminiChatClient.Step(content,List.of(new GeminiChatClient.ToolCall(name,mapper.readTree(args),"call-id")),null,null,false);
    }
    @Test void exactAndParaphrasedFaqsBothGoToGeminiAndItsMarkdownIsReturnedVerbatim() {
        String markdown="Library74 do **Võ Quang Thắng** phát triển.\n\n[Xem thêm](/publicpage/about#creator)";
        when(model.next(anyString(),anyList(),anyList())).thenReturn(text(markdown));
        assertThat(service.answer("Ai tạo trang web này?","vi",null).answer()).isEqualTo(markdown);
        assertThat(service.answer("ai làm ra cái trang web này vậy?","vi",null).answer()).isEqualTo(markdown);
        verify(model,times(2)).next(contains("Võ Quang Thắng"),anyList(),anyList());verifyNoInteractions(reads,knowledge);
        verify(store,times(2)).finish(eq(session),anyString(),eq(markdown));
    }
    @Test void greetingsClarificationAndRefusalsAreNotReplacedWithTemplates() {
        String answer="Chào bạn! Bạn đang muốn tìm tài liệu cho môn học nào?";
        when(model.next(anyString(),anyList(),anyList())).thenReturn(text(answer));
        assertThat(service.answer("Xin chào","vi",null).answer()).isEqualTo(answer);
        verifyNoInteractions(reads,knowledge);
    }
    @Test void privateResultsAreSentToGeminiButOnlyFinalQuestionAndAnswerArePersisted() throws Exception {
        when(model.next(anyString(),anyList(),anyList())).thenReturn(call("get_my_fines","{}"),text("Bạn có **50.000đ** chưa thanh toán."));
        var data=Map.<String,Object>of("records",List.of(Map.of("title","Private book","fine_amount",50000)),"totalFineAmount",50000);
        when(reads.execute(eq(ChatbotTool.get_my_fines),any(),eq(7L),eq("vi"))).thenReturn(data);
        var answer=service.answer("Tôi còn nợ phạt không?","vi",7L);
        assertThat(answer.answer()).isEqualTo("Bạn có **50.000đ** chưa thanh toán.");
        var requests=ArgumentCaptor.forClass(List.class);verify(model,times(2)).next(anyString(),requests.capture(),anyList());
        assertThat(requests.getAllValues().getLast().toString()).contains("Private book","50000","functionResponse","signature","call-id");
        verify(store).finish(session,"Tôi còn nợ phạt không?",answer.answer());
    }
    @Test void sendsCompletedHistoryAsUserModelPairsWithoutOldToolLogs() throws Exception {
        session=new ChatConversationStore.Session(session.id(),"key","expected",new ChatConversationStore.Context("vi",List.of(
            new ChatConversationStore.Turn("Có sách Java không?","Có, [Java Basics](/publicpage/book/42)."))),null,false,false);
        when(store.begin(any(),any(),anyString())).thenReturn(session);
        when(model.next(anyString(),anyList(),anyList())).thenReturn(text("Tôi sẽ kiểm tra CS2."));
        service.answer("Cuốn đó ở CS2 còn không?","vi",7L);
        var requests=ArgumentCaptor.forClass(List.class);verify(model).next(anyString(),requests.capture(),anyList());
        List<JsonNode> contents=requests.getValue();assertThat(contents).hasSize(3);
        assertThat(contents.get(0).path("role").asText()).isEqualTo("user");
        assertThat(contents.get(1).path("role").asText()).isEqualTo("model");
        assertThat(contents.get(1).toString()).contains("/publicpage/book/42").doesNotContain("functionResponse");
    }
    @Test void returnsEveryResultOfAMultiToolBatchToGeminiBeforeItsSingleFinalReply() throws Exception {
        var first=call("get_my_loans","{}");
        var second=call("get_my_fines","{}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)second.content().path("parts").get(0).path("functionCall")).put("id","fine-call");
        var content=first.content().deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode)content.path("parts")).add(second.content().path("parts").get(0));
        var batch=new GeminiChatClient.Step(content,List.of(first.calls().getFirst(),
            new GeminiChatClient.ToolCall("get_my_fines",mapper.createObjectNode(),"fine-call")),null,null,false);
        when(model.next(anyString(),anyList(),anyList())).thenReturn(batch,text("Bạn có 1 sách cần trả và **50.000đ** phí phạt."));
        when(reads.execute(eq(ChatbotTool.get_my_loans),any(),eq(7L),eq("vi"))).thenReturn(Map.of("total",1));
        when(reads.execute(eq(ChatbotTool.get_my_fines),any(),eq(7L),eq("vi"))).thenReturn(Map.of("totalFineAmount",50000));
        var reply=service.answer("Tôi cần trả sách nào và còn nợ phạt không?","vi",7L);
        var requests=ArgumentCaptor.forClass(List.class);verify(model,times(2)).next(anyString(),requests.capture(),anyList());
        List<JsonNode> last=requests.getAllValues().getLast();
        var responses=last.getLast().path("parts");assertThat(responses.size()).isEqualTo(2);
        assertThat(responses.get(0).path("functionResponse").path("response").path("total").asInt()).isEqualTo(1);
        assertThat(responses.get(1).path("functionResponse").path("id").asText()).isEqualTo("fine-call");
        assertThat(responses.get(1).path("functionResponse").path("response").path("totalFineAmount").asInt()).isEqualTo(50000);
        verify(store).finish(session,"Tôi cần trả sách nào và còn nợ phạt không?",reply.answer());
    }
    @Test void authenticatedIdentityIsServerOwnedAndModelIdentityArgumentsAreRejected() throws Exception {
        when(model.next(anyString(),anyList(),anyList())).thenReturn(call("get_my_fines","{\"userId\":999}"),text("Vui lòng hỏi dữ liệu của chính bạn."));
        service.answer("Phí phạt","vi",7L);
        verifyNoInteractions(reads);
        var requests=ArgumentCaptor.forClass(List.class);verify(model,times(2)).next(anyString(),requests.capture(),anyList());
        assertThat(requests.getAllValues().getLast().toString()).contains("INVALID_ARGUMENTS");
    }
    @Test void anonymousPrivateCallsAndUnknownWriteCallsNeverReachSql() throws Exception {
        for(String name:List.of("get_my_loans","renew_book")) {
            when(model.next(anyString(),anyList(),anyList())).thenReturn(call(name,"{}"),text("Bạn có thể xem hướng dẫn."));
            service.answer("Giúp tôi","vi",null);
        }
        verifyNoInteractions(reads);
    }
    @Test void readFailureIsReturnedAsJsonSoGeminiCanExplainTheFailureNaturally() throws Exception {
        when(model.next(anyString(),anyList(),anyList())).thenReturn(call("search_catalog","{\"query\":\"Java\"}"),text("Mình chưa truy cập được danh mục lúc này, bạn thử lại sau nhé."));
        when(reads.execute(any(),any(),any(),anyString())).thenThrow(new IllegalStateException("private database error"));
        assertThat(service.answer("Có Java không?","vi",null).answer()).startsWith("Mình chưa truy cập");
        var requests=ArgumentCaptor.forClass(List.class);verify(model,times(2)).next(anyString(),requests.capture(),anyList());
        assertThat(requests.getAllValues().getLast().toString()).contains("LOOKUP_UNAVAILABLE").doesNotContain("private database error");
    }
    @Test void knowledgeFallbackSuppliesApprovedDocumentsNotACannedUserAnswer() throws Exception {
        when(model.next(anyString(),anyList(),anyList())).thenReturn(call("search_library_help","{\"query\":\"mượn sách\"}"),text("Bạn đăng nhập, tìm sách và gửi yêu cầu nhé."));
        when(knowledge.retrieveResult(anyString(),anyString())).thenReturn(new ChatbotKnowledgeClient.Retrieval(List.of(),"KNOWLEDGE_NOT_READY"));
        assertThat(service.answer("Hướng dẫn mượn","vi",null).answer()).startsWith("Bạn đăng nhập");
        var requests=ArgumentCaptor.forClass(List.class);verify(model,times(2)).next(anyString(),requests.capture(),anyList());
        assertThat(requests.getAllValues().getLast().toString()).contains("documents","#borrow");
    }
    @Test void providerErrorReleasesTheSessionWithoutStoringAFakeAssistantAnswer() {
        when(model.next(anyString(),anyList(),anyList())).thenReturn(new GeminiChatClient.Step(null,List.of(),null,"AI_QUOTA_EXCEEDED",false));
        assertThat(service.answer("Chào","vi",null).reasonCode()).isEqualTo("AI_QUOTA_EXCEEDED");
        verify(store,never()).finish(any(),anyString(),anyString());verify(store).release(session);
    }
    @Test void boundedLoopGivesTheModelAFinalToolFreeRound() throws Exception {
        when(model.next(anyString(),anyList(),anyList())).thenAnswer(invocation-> {
            List<ChatbotTool> tools=invocation.getArgument(2);
            return tools.isEmpty()?text("Tôi cần thêm thông tin để hoàn tất."):call("get_my_loans","{}");
        });
        when(reads.execute(any(),any(),any(),anyString())).thenReturn(Map.of("records",List.of(),"total",0));
        assertThat(service.answer("Xem dữ liệu","vi",7L).answer()).isEqualTo("Tôi cần thêm thông tin để hoàn tất.");
        verify(reads,times(6)).execute(any(),any(),eq(7L),eq("vi"));
        verify(model).next(contains("No further tool calls"),anyList(),eq(List.of()));
    }
}
