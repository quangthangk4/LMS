package com.library.recommendation.application.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Only this reviewed, packaged resource is readable by the FAQ assistant. */
@Component
public class FaqCatalog {
    public record Document(int revision, List<Entry> entries) {}
    public record Copy(String question, String answer, List<String> aliases) {}
    public record Entry(String id, String sourcePath, Copy vi, Copy en) {
        public Copy copy(String language) { return "en".equals(language) ? en : vi; }
        public String referencePath() {
            // These static pages do not render an individually addressable canonical topic.
            return (java.util.Set.of("about","privacy").contains(id)?"/publicpage/faq":sourcePath) + "#" + id;
        }
    }

    private final List<Entry> entries;
    private final int revision;
    private final String version;

    public FaqCatalog(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("chatbot/faq.json").getInputStream()) {
            var document = mapper.readValue(input, Document.class);
            revision = document.revision();
            if (revision < 1) throw new IllegalStateException("Knowledge revision must be positive");
            entries = List.copyOf(document.entries());
        }
        try {
            version = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(entries)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var ids = new HashSet<String>();
        var questions = new HashSet<String>();
        if (entries.isEmpty()) throw new IllegalStateException("Empty chatbot FAQ catalog");
        for (var entry : entries) {
            if (entry.id() == null || !entry.id().matches("[a-z_]+") || !ids.add(entry.id())
                || entry.sourcePath() == null
                || !entry.sourcePath().matches("/publicpage/(guide|faq|about|contact|privacy-policy)")) {
                throw new IllegalStateException("Invalid chatbot FAQ metadata");
            }
            for (var copy : List.of(entry.vi(), entry.en())) {
                if (copy.question() == null || copy.question().isBlank()
                    || copy.answer() == null || copy.answer().isBlank() || copy.aliases() == null) {
                    throw new IllegalStateException("Incomplete chatbot FAQ copy");
                }
                for (var question : java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(copy.question()), copy.aliases().stream()).toList()) {
                    if (question == null || normalize(question).isBlank() || !questions.add(normalize(question))) {
                        throw new IllegalStateException("Duplicate or empty chatbot FAQ question");
                    }
                }
            }
        }
    }

    public List<Entry> entries() { return entries; }
    public int revision() { return revision; }
    public String version() { return version; }
    public Optional<Entry> find(String id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }
    public Optional<Entry> exactMatch(String question) {
        String normalized = normalize(question);
        return entries.stream().filter(entry -> java.util.stream.Stream.of(entry.vi(), entry.en())
            .anyMatch(copy -> normalize(copy.question()).equals(normalized)
                || copy.aliases().stream().anyMatch(alias -> normalize(alias).equals(normalized)))).findFirst();
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT).replace('đ', 'd').replaceAll("[^a-z0-9]+", " ").trim();
    }
}
