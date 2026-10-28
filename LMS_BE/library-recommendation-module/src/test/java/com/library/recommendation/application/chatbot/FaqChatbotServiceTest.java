package com.library.recommendation.application.chatbot;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
class FaqChatbotServiceTest {
    @Test void suggestsCanonicalQuestionsInBothLanguagesWithoutGeneratingAnswers() throws Exception {
        var catalog=new FaqCatalog(new ObjectMapper());var service=new FaqChatbotService(catalog);
        assertThat(service.suggestions("vi")).hasSize(catalog.entries().size());
        assertThat(service.suggestions("en").getFirst().question()).isEqualTo(catalog.entries().getFirst().en().question());
        assertThat(catalog.find("password").orElseThrow().referencePath()).endsWith("#password");
    }
}
