package com.library.recommendation.application.chatbot;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit allowlist. No endpoint, URL or SQL passthrough. */
public enum ChatbotTool {
    search_library_help("General Library74 instructions, website creator, how to use features. Not book chapter content.", false, Set.of("query")),
    get_current_circulation_policy("Current public loan days, pickup deadline, renewal limits, deposit and overdue fine policy.", false, Set.of()),
    search_catalog("Find public books by title, author, ISBN or keyword, with optional campus.", false, Set.of("query", "branch", "page")),
    get_publication_details("Public metadata and stored description/summary of a specific book. Never chapter/PDF content.", false, Set.of("query", "publicationId")),
    get_publication_availability("Available copies of a book, campus and shelf. Does not reserve or reveal borrowers.", false, Set.of("query", "publicationId", "branch", "page")),
    list_publications("List newest or most-borrowed public books.", false, Set.of("sort", "branch", "page")),
    get_publication_rating_summary("Aggregate ratings of one book, without reviewer identities.", false, Set.of("query", "publicationId")),
    get_my_loans("Current reader's loans. DUE_SOON = needs returning urgently: overdue or due within three days; OVERDUE = past due. Read only.", true, Set.of("page", "status")),
    get_my_loan_history("Current reader's returned loan history. Date ranges default to return date; use dateType for borrow date.", true, Set.of("page", "dateFrom", "dateTo", "dateType")),
    get_my_fines("Current reader's fines, with full filtered count and amount, not just the first page.", true, Set.of("page", "status")),
    get_my_reservations("Current reader's reservations and pickup deadlines. Does not change them.", true, Set.of("page")),
    get_my_wishlist("Read current reader's wishlist. Does not add or remove anything.", true, Set.of("page")),
    get_my_notifications("Read current reader's notifications WITHOUT marking them read.", true, Set.of("page"));

    public final String description;
    public final boolean personal;
    public final Set<String> arguments;
    ChatbotTool(String description, boolean personal, Set<String> arguments) {
        this.description = description; this.personal = personal; this.arguments = arguments;
    }
    public static List<ChatbotTool> available(boolean reader) {
        return Arrays.stream(values()).filter(tool -> reader || !tool.personal).toList();
    }
    public Map<String, Object> declaration() {
        var properties = new LinkedHashMap<String, Object>();
        for (String argument : arguments) {
            properties.put(argument, switch (argument) {
                case "page" -> Map.of("type", "INTEGER", "description", "Zero-based page, 0 for first page; maximum 1000.");
                case "branch" -> Map.of("type", "STRING", "enum", List.of("CAMPUS_1", "CAMPUS_2", "ANY"),
                    "description", "CAMPUS_1 = Ly Thuong Kiet, CAMPUS_2 = Di An; ANY for both.");
                case "sort" -> Map.of("type", "STRING", "enum", List.of("newest", "most_borrowed"));
                case "status" -> Map.of("type", "STRING", "enum", this == get_my_loans
                    ? List.of("ACTIVE", "BORROWING", "OVERDUE", "DUE_SOON", "WAITING_FOR_PICKUP") : List.of("UNPAID", "PAID", "ALL"));
                case "dateType" -> Map.of("type", "STRING", "enum", List.of("borrowed", "returned"));
                case "publicationId" -> Map.of("type", "STRING", "description", "Exact numeric publication ID, never a user ID.");
                case "dateFrom", "dateTo" -> Map.of("type", "STRING", "description", "ISO local date YYYY-MM-DD, Vietnam timezone. Used with dateType.");
                default -> Map.of("type", "STRING", "description", "Search words, title, author or ISBN. At most 200 characters.");
            });
        }
        return Map.of("name", name(), "description", description,
            "parameters", Map.of("type", "OBJECT", "properties", properties));
    }
}
