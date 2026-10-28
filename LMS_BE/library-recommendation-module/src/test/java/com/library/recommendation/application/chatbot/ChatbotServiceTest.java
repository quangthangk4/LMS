package com.library.recommendation.application.chatbot;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.infrastructure.ai.ChatbotKnowledgeClient;
import com.library.recommendation.infrastructure.ai.FaqIntentSelector;
import com.library.recommendation.infrastructure.persistence.ChatbotReadQueries;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatbotServiceTest {
    final ObjectMapper mapper=new ObjectMapper();
    FaqCatalog catalog;
    FaqIntentSelector model;
    ChatbotKnowledgeClient knowledge;
    ChatbotReadQueries reads;
    ChatbotService service;
    @BeforeEach void setup() throws Exception {
        catalog=new FaqCatalog(mapper); model=mock(FaqIntentSelector.class);
        knowledge=mock(ChatbotKnowledgeClient.class); reads=mock(ChatbotReadQueries.class);
        service=new ChatbotService(catalog,new FaqChatbotService(catalog,model),model,knowledge,reads);
        when(model.hasBudget(any())).thenReturn(true);
    }
    FaqIntentSelector.ToolCall call(String name,String json) throws Exception { return new FaqIntentSelector.ToolCall(name,mapper.readTree(json)); }
    void plan(FaqIntentSelector.ToolCall... calls) throws Exception {
        when(model.plan(anyString(),anyList(),anyString(),anyList(),anyString()))
            .thenReturn(new FaqIntentSelector.Plan(List.of(calls),mapper.readTree("{\"role\":\"model\",\"parts\":[]}"),null))
            .thenReturn(new FaqIntentSelector.Plan(List.of(call("respond_to_reader","{\"kind\":\"DONE\"}")),mapper.readTree("{\"parts\":[]}"),null));
    }
    @Test void exactFaqWorksWithoutProviderOrVectorStore() {
        assertThat(service.answer("Ai tạo trang web này?","vi",null).answer()).contains("Võ Quang Thắng");
        verify(model,never()).plan(anyString(),anyList(),anyString(),anyList(),anyString());
        verifyNoInteractions(reads,knowledge);
    }
    @Test void privateResultsAreScopedAndNeverUploadedEvenDuringAnotherToolRound() throws Exception {
        plan(call("get_my_fines","{}"),call("get_my_loans","{}"));
        when(reads.execute(any(),any(),eq(7L),eq("vi"))).thenReturn(ChatbotReply.message("ANSWERED","PRIVATE_FINE_987654"));
        var answer=service.answer("Tôi nợ phạt và đang mượn sách nào?","vi",7L);
        assertThat(answer.sections()).hasSize(2);
        verify(reads).execute(eq(ChatbotTool.get_my_fines),any(),eq(7L),eq("vi"));
        var transcript=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model,times(2)).plan(anyString(),anyList(),eq("vi"),transcript.capture(),anyString());
        assertThat(transcript.getAllValues().toString()).doesNotContain("PRIVATE_FINE_987654");
        verify(model,never()).compose(anyString(),anyList(),anyString());
    }
    @Test void anonymousCannotExecuteAProtectedTool() throws Exception {
        plan(call("get_my_loans","{}"));
        assertThat(service.answer("My loans","en",null).status()).isEqualTo("AUTH_REQUIRED");
        verifyNoInteractions(reads,knowledge);
    }
    @Test void modelSuppliedIdentityIsRejectedBeforeDatabaseLookup() throws Exception {
        plan(call("get_my_fines","{\"userId\":999}"));
        assertThat(service.answer("My fines","en",7L).status()).isEqualTo("CLARIFY");
        verifyNoInteractions(reads);
    }
    @Test void writeToolsCannotReachDatabase() throws Exception {
        plan(call("renew_book","{}"));
        assertThat(service.answer("Renew for me","en",7L).status()).isEqualTo("CLARIFY");
        verifyNoInteractions(reads);
    }
    @Test void forgotPasswordAndLoginUseMultipleGroundedTopics() throws Exception {
        plan(call("search_library_help","{\"query\":\"password reset\"}"),call("search_library_help","{\"query\":\"login\"}"));
        var password=catalog.find("password").orElseThrow();
        var login=catalog.find("login").orElseThrow();
        when(knowledge.retrieveResult("password reset","en")).thenReturn(new ChatbotKnowledgeClient.Retrieval(List.of(password),null));
        when(knowledge.retrieveResult("login","en")).thenReturn(new ChatbotKnowledgeClient.Retrieval(List.of(login),null));
        when(model.compose(anyString(),eq(List.of(password,login)),eq("en"))).thenReturn(new FaqIntentSelector.Grounded(
            List.of(new FaqIntentSelector.Draft(password.id(),null),new FaqIntentSelector.Draft(login.id(),null)),null));
        var answer=service.answer("I forgot my password; help me reset it and sign in again","en",null);
        assertThat(answer.sections()).hasSize(2).allMatch(section->section.status().equals("ANSWERED"));
        assertThat(answer.sections()).extracting(ChatbotReply::faqId).containsExactly(password.id(),login.id());
        verifyNoInteractions(reads);
    }
    @Test void noMatchIsDifferentFromIndexOrProviderFailure() throws Exception {
        plan(call("search_library_help","{}"));
        when(knowledge.retrieveResult(anyString(),anyString())).thenReturn(new ChatbotKnowledgeClient.Retrieval(List.of(),null));
        when(model.compose(anyString(),anyList(),anyString())).thenReturn(new FaqIntentSelector.Grounded(List.of(),null));
        assertThat(service.answer("Unknown instruction","en",null).status()).isEqualTo("NO_MATCH");
        plan(call("search_library_help","{}"));
        when(knowledge.retrieveResult(anyString(),anyString())).thenReturn(new ChatbotKnowledgeClient.Retrieval(List.of(),"KNOWLEDGE_NOT_READY"));
        assertThat(service.answer("Unknown instruction","en",null).reasonCode()).isEqualTo("KNOWLEDGE_NOT_READY");
        when(model.plan(anyString(),anyList(),anyString(),anyList(),anyString())).thenReturn(new FaqIntentSelector.Plan(List.of(),null,"AI_QUOTA_EXCEEDED"));
        assertThat(service.answer("Unknown instruction","en",null).reasonCode()).isEqualTo("AI_QUOTA_EXCEEDED");
        verify(model,times(2)).compose(anyString(),anyList(),anyString());
    }
    @Test void retrievedGuidanceRemainsUsableWhenWritingModelFails() throws Exception {
        plan(call("search_library_help","{}"));
        var source=catalog.find("borrow").orElseThrow();
        when(knowledge.retrieveResult(anyString(),anyString())).thenReturn(new ChatbotKnowledgeClient.Retrieval(List.of(source),null));
        when(model.compose(anyString(),anyList(),anyString())).thenReturn(new FaqIntentSelector.Grounded(List.of(),"AI_QUOTA_EXCEEDED"));
        var answer=service.answer("Explain borrowing please","en",null);
        assertThat(answer.sections().get(0).content().steps()).isNotEmpty();
        assertThat(answer.sections().get(1).reasonCode()).isEqualTo("AI_QUOTA_EXCEEDED");
    }
    @Test void conversationalReplyDoesNotReadDatabaseOrInventLookupResults() throws Exception {
        plan(call("respond_to_reader","{\"kind\":\"SOCIAL\",\"message\":\"Hello! How can I help you use Library74?\"}"));
        assertThat(service.answer("Who are you and what can you help me with?","en",null).answer()).startsWith("Hello!");
        verifyNoInteractions(reads,knowledge);
    }
    @Test void boundedExecutionRejectsDuplicateCallsAndNeverExceedsFourReads() throws Exception {
        plan(call("get_my_loans","{\"page\":0}"),call("get_my_loans","{\"page\":1}"),call("get_my_loans","{\"page\":2}"),
            call("get_my_loans","{\"page\":3}"),call("get_my_loans","{\"page\":4}"));
        when(reads.execute(any(),any(),anyLong(),anyString())).thenReturn(ChatbotReply.message("ANSWERED","Delivered"));
        service.answer("List all my loans","en",7L);
        verify(reads,times(4)).execute(any(),any(),eq(7L),eq("en"));
    }
    @Test void aSuccessfulDiscoveryStopsWithoutUnrequestedAvailabilityAndIsNarratedOnce() throws Exception {
        var content=mapper.readTree("{\"parts\":[]}");
        when(model.plan(anyString(),anyList(),anyString(),anyList(),anyString()))
            .thenReturn(new FaqIntentSelector.Plan(List.of(call("search_catalog","{\"query\":\"Clean Code\"}")),content,null))
            .thenReturn(new FaqIntentSelector.Plan(List.of(call("get_publication_availability","{\"publicationId\":\"42\",\"branch\":\"CAMPUS_2\"}")),content,null));
        when(reads.execute(eq(ChatbotTool.search_catalog),any(),isNull(),eq("en"))).thenReturn(new ChatbotReply(
            "ANSWERED",null,"Found a book",null,null,null,List.of(),List.of(new ChatbotReply.Card("42","Clean Code",null,null,"/publicpage/book/42",List.of())),1L,0,null));
        when(reads.execute(eq(ChatbotTool.get_publication_availability),any(),isNull(),eq("en"))).thenReturn(ChatbotReply.message("ANSWERED","Yes. Clean Code has 2 copies at campus 2."));
        var contentReply=new FaqContent("Yes, I found Clean Code in the library.",List.of(),List.of(),"");
        when(model.narrate(anyString(),anyList(),eq("en"))).thenReturn(new FaqIntentSelector.Grounded(List.of(new FaqIntentSelector.Draft("lookup",contentReply)),null));
        var reply=service.answer("Find Clean Code please","en",null);
        assertThat(reply.sections()).isEmpty();
        assertThat(reply.answer()).isEqualTo(contentReply.text());
        assertThat(reply.cards()).hasSize(1);
        verify(model,times(1)).plan(anyString(),anyList(),eq("en"),anyList(),anyString());
        verify(model,times(1)).narrate(anyString(),anyList(),eq("en"));
        verify(reads,never()).execute(eq(ChatbotTool.get_publication_availability),any(),any(),anyString());
    }
    @Test void aCreatorParaphraseIsCheckedAgainstReviewedSourcesBeforeRefusal() throws Exception {
        plan(call("respond_to_reader","{\"kind\":\"UNSUPPORTED\"}"));
        when(model.compose(anyString(),anyList(),eq("vi"))).thenReturn(new FaqIntentSelector.Grounded(List.of(new FaqIntentSelector.Draft("creator",null)),null));
        var answer=service.answer("ai làm ra cái trang web này vậy?","vi",null);
        assertThat(answer.answer()).contains("Võ Quang Thắng");
        assertThat(answer.sourcePath()).isEqualTo("/publicpage/about#creator");
        verifyNoInteractions(reads);
    }
    @Test void intermediateEmptySearchesAreNotShownWhenABroaderSearchFindsBooks() throws Exception {
        var content=mapper.readTree("{\"parts\":[]}");
        when(model.plan(anyString(),anyList(),anyString(),anyList(),anyString()))
            .thenReturn(new FaqIntentSelector.Plan(List.of(call("search_catalog","{\"query\":\"beginner programming books\"}")),content,null))
            .thenReturn(new FaqIntentSelector.Plan(List.of(call("search_catalog","{\"query\":\"programming\"}")),content,null));
        var empty=new ChatbotReply("ANSWERED",null,"No matching book", "Catalog","/publicpage/search",null,List.of(),List.of(),0L,0,null);
        var found=new ChatbotReply("ANSWERED",null,"Found 1 book", "Catalog","/publicpage/search",null,List.of(),List.of(new ChatbotReply.Card("42","Programming",null,null,"/publicpage/book/42",List.of())),1L,0,null);
        when(reads.execute(eq(ChatbotTool.search_catalog),any(),isNull(),eq("en"))).thenReturn(empty,found);
        var answer=service.answer("I need books for beginner programmers","en",null);
        assertThat(answer.answer()).contains("Found 1 book").doesNotContain("No matching");
        assertThat(answer.cards()).hasSize(1);
        verify(model).narrate(anyString(),eq(List.of(found)),eq("en"));
    }
    @Test void narrationOnlyReceivesPublicResultsAndCanSelectRelevantBooks() throws Exception {
        plan(call("search_catalog","{\"query\":\"Java\"}"),call("get_my_fines","{}"));
        var java=new ChatbotReply.Card("1","Java",null,null,"/publicpage/book/1",List.of());
        var javascript=new ChatbotReply.Card("2","JavaScript",null,null,"/publicpage/book/2",List.of());
        var publicResult=new ChatbotReply("ANSWERED",null,"Found 2 books","Catalog","/publicpage/search",null,List.of(),List.of(java,javascript),2L,0,null);
        when(reads.execute(eq(ChatbotTool.search_catalog),any(),isNull(),eq("en"))).thenReturn(publicResult);
        when(reads.execute(eq(ChatbotTool.get_my_fines),any(),eq(7L),eq("en"))).thenReturn(ChatbotReply.message("ANSWERED","PRIVATE_123456"));
        when(model.narrate(anyString(),eq(List.of(publicResult)),eq("en"))).thenReturn(new FaqIntentSelector.Grounded(List.of(new FaqIntentSelector.Draft("lookup",new FaqContent("I found a Java book.",List.of(),List.of(),""))),null,List.of("/publicpage/book/1")));
        var answer=service.answer("Find Java books and check my fines","en",7L);
        assertThat(answer.sections()).hasSize(2);
        assertThat(answer.sections().get(1).cards()).containsExactly(java);
        verify(model,times(1)).narrate(anyString(),eq(List.of(publicResult)),eq("en"));
    }
}
