package com.library.recommendation.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.recommendation.application.chatbot.ChatbotReply;
import com.library.recommendation.application.chatbot.ChatbotReply.Card;
import com.library.recommendation.application.chatbot.ChatbotReply.Fact;
import com.library.recommendation.application.chatbot.ChatbotReply.Source;
import com.library.recommendation.application.chatbot.ChatbotTool;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Auditable SELECT-only adapters; never invokes controllers with tracking/payment side effects. */
@Repository
public class ChatbotReadQueries {
    private static final int SIZE = 5;
    private static final String READY = "i.status='AVAILABLE' AND NOT EXISTS (SELECT 1 FROM reshelving_tasks rq WHERE rq.item_id=i.id AND rq.status='WAITING')";
    private final NamedParameterJdbcTemplate jdbc;
    public ChatbotReadQueries(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public ChatbotReply execute(ChatbotTool tool, JsonNode arguments, Long readerId, String language) {
        if (tool.personal && readerId == null) throw new SecurityException("Reader authentication required");
        var args = validate(tool, arguments);
        boolean en = "en".equals(language);
        var params = new HashMap<String, Object>();
        int page = arguments.path("page").asInt(0);
        params.put("userId", readerId); params.put("limit", SIZE); params.put("offset", page * SIZE);
        params.put("query", args.getOrDefault("query", ""));
        params.put("pattern", "%" + args.getOrDefault("query", "").toLowerCase(java.util.Locale.ROOT) + "%");
        params.put("publicationId", args.containsKey("publicationId") ? Long.valueOf(args.get("publicationId")) : null);
        String branch = args.getOrDefault("branch", "ANY");
        params.put("branch", switch (branch) {
            case "CAMPUS_1" -> "Cơ sở 1 - Lý Thường Kiệt";
            case "CAMPUS_2" -> "Cơ sở 2 - Dĩ An";
            default -> null;
        });
        if (tool == ChatbotTool.get_current_circulation_policy) return policy(en);
        if (!tool.personal) return catalog(tool, params, args, page, en);
        return personal(tool, params, args, page, en);
    }

    public static Map<String, String> validate(ChatbotTool tool, JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Invalid tool arguments");
        var args = new HashMap<String, String>();
        node.fields().forEachRemaining(field -> {
            String key = field.getKey(); JsonNode value = field.getValue();
            if (!tool.arguments.contains(key)) throw new IllegalArgumentException("Unknown argument");
            if ("page".equals(key)) {
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0 || value.intValue() > 1000)
                    throw new IllegalArgumentException("Invalid page");
            } else {
                if (!value.isTextual() || value.asText().length() > 200 || value.asText().isBlank())
                    throw new IllegalArgumentException("Invalid argument");
                String text = value.asText();
                if ("publicationId".equals(key) && (!text.matches("[1-9][0-9]{0,18}") || new BigDecimal(text).compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0))
                    throw new IllegalArgumentException("Invalid publication ID");
                if ("branch".equals(key) && !Set.of("ANY", "CAMPUS_1", "CAMPUS_2").contains(text)) throw new IllegalArgumentException("Invalid campus");
                if ("sort".equals(key) && !Set.of("newest", "most_borrowed").contains(text)) throw new IllegalArgumentException("Invalid sort");
                if ("status".equals(key) && !(tool == ChatbotTool.get_my_loans
                    ? Set.of("ACTIVE", "BORROWING", "OVERDUE", "DUE_SOON", "WAITING_FOR_PICKUP") : Set.of("ALL", "UNPAID", "PAID")).contains(text))
                    throw new IllegalArgumentException("Invalid status");
                if ("dateType".equals(key) && !Set.of("borrowed", "returned").contains(text)) throw new IllegalArgumentException("Invalid date type");
                if ("dateFrom".equals(key) || "dateTo".equals(key)) {
                    try { LocalDate.parse(text); } catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid date"); }
                }
                args.put(key, text);
            }
        });
        if (args.containsKey("dateFrom") && args.containsKey("dateTo") && args.get("dateFrom").compareTo(args.get("dateTo")) > 0)
            throw new IllegalArgumentException("Invalid date range");
        return args;
    }

    private ChatbotReply policy(boolean en) {
        var rows = jdbc.queryForList("""
            SELECT pickup_deadline_hours,default_loan_days,max_active_borrows,max_active_reservations,
                   max_renewals,renewal_window_days,overdue_fine_per_day,default_deposit_amount,
                   block_borrow_when_unpaid_fines,updated_at FROM circulation_policies WHERE id=1
            """, Map.of());
        if (rows.isEmpty()) throw new IllegalStateException("Current policy is unavailable");
        var row = rows.get(0);
        var facts = List.of(fact(en ? "Loan period (days)" : "Thời hạn mượn (ngày)", row.get("default_loan_days")),
            fact(en ? "Maximum active loans" : "Số sách đang mượn tối đa", row.get("max_active_borrows")),
            fact(en ? "Maximum reservations" : "Số lượt đặt trước tối đa", row.get("max_active_reservations")),
            fact(en ? "Maximum renewals per loan" : "Số lượt gia hạn mỗi lần mượn", row.get("max_renewals")),
            fact(en ? "Renewal window (days)" : "Mở gia hạn trước hạn (ngày)", row.get("renewal_window_days")),
            fact(en ? "Pickup deadline (hours)" : "Hạn nhận sách (giờ)", row.get("pickup_deadline_hours")),
            fact(en ? "Deposit" : "Tiền cọc", money(row.get("default_deposit_amount"))),
            fact(en ? "Overdue fine per day" : "Phí trễ hạn mỗi ngày", money(row.get("overdue_fine_per_day"))),
            fact(en ? "Unpaid fines block borrowing" : "Chặn mượn khi còn nợ phạt", Boolean.TRUE.equals(row.get("block_borrow_when_unpaid_fines")) ? (en ? "Yes" : "Có") : (en ? "No" : "Không")));
        String title = en ? "Current circulation policy" : "Chính sách mượn trả hiện hành";
        return reply(title, List.of(new Card("policy", title, en ? "General policy; individual eligibility may differ." : "Quy định chung; từng giao dịch còn phải đáp ứng điều kiện riêng.",
            null, "/publicpage/faq#current-policy", facts)), 1L, 0, "/publicpage/faq#current-policy", en);
    }

    private ChatbotReply catalog(ChatbotTool tool, Map<String, Object> p, Map<String, String> args, int page, boolean en) {
        if (tool != ChatbotTool.list_publications && ((String)p.get("query")).isBlank() && p.get("publicationId") == null)
            return ChatbotReply.message("CLARIFY", en ? "Please provide a book title, author, ISBN or publication ID." : "Bạn hãy nhập tên sách, tác giả, ISBN hoặc mã ấn phẩm.");
        boolean oneBook = tool == ChatbotTool.get_publication_details || tool == ChatbotTool.get_publication_availability || tool == ChatbotTool.get_publication_rating_summary;
        String from = """
            FROM publications p WHERE
            (CAST(:publicationId AS BIGINT) IS NULL OR p.id=:publicationId)
            AND (:query='' OR LOWER(COALESCE(p.isbn,''))=:query OR
                to_tsvector('simple',unaccent(CONCAT_WS(' ',p.title,
                    (SELECT STRING_AGG(a.name,' ') FROM publication_authors pa JOIN authors a ON a.id=pa.author_id WHERE pa.publication_id=p.id)
            """ + (oneBook ? "" : ",p.description,p.ai_summary") + """
                ))) @@ plainto_tsquery('simple',unaccent(:query)))
            """ + (oneBook ? "" : " AND (CAST(:branch AS VARCHAR) IS NULL OR EXISTS (SELECT 1 FROM items bi WHERE bi.publication_id=p.id AND bi.branch=:branch)) ");
        Long count = jdbc.queryForObject("SELECT COUNT(*) " + from, p, Long.class);
        var bookParams = new HashMap<>(p);
        if (oneBook) { bookParams.put("offset", 0); bookParams.put("limit", 5); }
        String order = "most_borrowed".equals(args.get("sort"))
            ? "(SELECT COUNT(*) FROM borrowing_transactions bt JOIN items bi ON bi.id=bt.item_id WHERE bi.publication_id=p.id AND bt.borrowed_date IS NOT NULL AND (CAST(:branch AS VARCHAR) IS NULL OR bi.branch=:branch)) DESC,p.id DESC"
            : "p.created_at DESC NULLS LAST,p.id DESC";
        var rows = jdbc.queryForList("""
            SELECT p.id,p.title,p.isbn,p.description,p.ai_summary,p.publication_year,p.edition,p.cover_image_url,
                (SELECT pub.name FROM publishers pub WHERE pub.id=p.publisher_id) AS publisher,
                (SELECT STRING_AGG(DISTINCT a.name,', ' ORDER BY a.name) FROM publication_authors pa JOIN authors a ON a.id=pa.author_id WHERE pa.publication_id=p.id) AS authors,
                (SELECT COUNT(*) FROM items i WHERE i.publication_id=p.id AND
            """ + READY + " AND (CAST(:branch AS VARCHAR) IS NULL OR i.branch=:branch)) AS available_items " + from + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", bookParams);
        if (oneBook && count != null && count == 1 && !rows.isEmpty()) {
            var book = rows.get(0); p.put("publicationId", ((Number)book.get("id")).longValue());
            String path = bookPath(book);
            if (tool == ChatbotTool.get_publication_availability) {
                String copies = "FROM items i WHERE i.publication_id=:publicationId AND " + READY + " AND (CAST(:branch AS VARCHAR) IS NULL OR i.branch=:branch)";
                var total = jdbc.queryForObject("SELECT COUNT(*) " + copies, p, Long.class);
                var items = jdbc.queryForList("SELECT i.id,i.barcode,i.branch,i.location " + copies + " ORDER BY i.branch,i.location,i.id LIMIT :limit OFFSET :offset", p);
                String campus = p.get("branch") == null ? (en ? "across both campuses" : "tại cả hai cơ sở") : (en ? "at " : "tại ") + p.get("branch");
                String availability = total != null && total > 0
                    ? (en ? "Yes. " : "Có. ") + book.get("title") + (en ? " has " : " còn ") + total + (en ? " available copies " : " bản sao có sẵn ") + campus + "."
                    : (en ? "No. " : "Không. ") + book.get("title") + (en ? " is in the catalog, but has no available copies " : " có trong thư viện, nhưng chưa có bản sao sẵn sàng cho mượn ") + campus + ".";
                return reply(availability,
                    items.stream().map(item -> new Card(id(item), text(book.get("title")), null, image(book.get("cover_image_url")), path,
                        List.of(fact("Barcode", item.get("barcode")), fact(en ? "Campus" : "Cơ sở", item.get("branch")), fact(en ? "Shelf" : "Vị trí kệ", item.get("location"))))).toList(), total, page, path, en);
            }
            if (tool == ChatbotTool.get_publication_rating_summary) {
                var ratings = jdbc.queryForList("SELECT star,COUNT(*) AS votes FROM ratings WHERE publication_id=:publicationId GROUP BY star ORDER BY star DESC", p);
                var facts = new ArrayList<>(ratings.stream().map(row -> fact(row.get("star") + (en ? " stars" : " sao"), row.get("votes"))).toList());
                long votes = ratings.stream().mapToLong(row -> ((Number)row.get("votes")).longValue()).sum();
                double weighted = ratings.stream().mapToDouble(row -> ((Number)row.get("star")).doubleValue() * ((Number)row.get("votes")).longValue()).sum();
                facts.add(0, fact(en ? "Average rating" : "Điểm trung bình", votes == 0 ? "—" : String.format(en ? java.util.Locale.US : java.util.Locale.forLanguageTag("vi-VN"), "%.2f/5", weighted / votes)));
                facts.add(1, fact(en ? "Total ratings" : "Số đánh giá", votes));
                return reply(en ? "Recorded rating distribution" : "Thống kê đánh giá đã ghi nhận", List.of(new Card(id(book), text(book.get("title")), null, image(book.get("cover_image_url")), path, facts)), 1L, 0, path, en);
            }
        }
        String message = count == null || count == 0 ? (en ? "No matching book was found." : "Chưa tìm thấy sách phù hợp.")
            : oneBook && count > 1 ? (en ? "Several books match. Please specify the exact title, ISBN or publication ID." : "Có nhiều ấn phẩm phù hợp. Bạn hãy chỉ rõ tên, ISBN hoặc mã ấn phẩm.")
            : (en ? "I found " : "Tôi tìm thấy ") + count + (en ? " matching books in the library." : " ấn phẩm phù hợp trong thư viện.");
        return reply(message, rows.stream().map(book -> new Card(id(book), text(book.get("title")), text(book.get("authors")),
            image(book.get("cover_image_url")), bookPath(book), bookFacts(book, tool == ChatbotTool.get_publication_details || tool == ChatbotTool.search_catalog, en))).toList(), count, page, "/publicpage/search", en);
    }

    private List<Fact> bookFacts(Map<String, Object> row, boolean detail, boolean en) {
        var facts = new ArrayList<>(List.of(fact("ISBN", row.get("isbn")), fact(en ? "Year" : "Năm xuất bản", row.get("publication_year")),
            fact(en ? "Edition" : "Phiên bản", row.get("edition")), fact(en ? "Publisher" : "Nhà xuất bản", row.get("publisher")), fact(en ? "Available copies" : "Bản sao khả dụng", row.get("available_items"))));
        if (detail) {
            facts.add(fact(en ? "Description" : "Mô tả", clip(row.get("description"), 1200)));
            facts.add(fact(en ? "Stored summary" : "Tóm tắt đã lưu", clip(row.get("ai_summary"), 1200)));
        }
        return facts;
    }

    private ChatbotReply personal(ChatbotTool tool, Map<String, Object> p, Map<String, String> args, int page, boolean en) {
        String path, from, select, order;
        Function<Map<String, Object>, Card> card;
        if (tool == ChatbotTool.get_my_loans || tool == ChatbotTool.get_my_loan_history) {
            path = "/userpage/my-books";
            from = "FROM borrowing_transactions t JOIN items i ON i.id=t.item_id JOIN publications p ON p.id=i.publication_id WHERE t.user_id=:userId AND "
                + (tool == ChatbotTool.get_my_loans ? "t.status IN ('BORROWING','OVERDUE')" : "t.status IN ('RETURNED','LOST_RECOVERED')");
            if (tool == ChatbotTool.get_my_loans && args.containsKey("status") && !"ACTIVE".equals(args.get("status"))) {
                if ("DUE_SOON".equals(args.get("status"))) from += " AND t.due_date <= (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + 3";
                else if ("OVERDUE".equals(args.get("status"))) from += " AND t.due_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date";
                else { from = from.replace("t.status IN ('BORROWING','OVERDUE')", "t.status=:loanStatus"); p.put("loanStatus", args.get("status")); }
            }
            String dateColumn = "borrowed".equals(args.get("dateType")) ? "t.borrowed_date" : "t.returned_date";
            if (args.containsKey("dateFrom")) { from += " AND (" + dateColumn + " AT TIME ZONE 'Asia/Ho_Chi_Minh')::date>=CAST(:dateFrom AS DATE)"; p.put("dateFrom", args.get("dateFrom")); }
            if (args.containsKey("dateTo")) { from += " AND (" + dateColumn + " AT TIME ZONE 'Asia/Ho_Chi_Minh')::date<=CAST(:dateTo AS DATE)"; p.put("dateTo", args.get("dateTo")); }
            select = "SELECT t.id,p.title,p.cover_image_url,i.barcode,CASE WHEN t.status IN ('BORROWING','OVERDUE') AND t.due_date < (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date THEN 'OVERDUE' ELSE t.status END AS status,t.borrowed_date,t.due_date,t.returned_date,t.renewal_count ";
            order = tool == ChatbotTool.get_my_loans ? "t.due_date ASC,t.id ASC" : "t.returned_date DESC NULLS LAST,t.id DESC";
            card = row -> new Card(id(row), text(row.get("title")), null, image(row.get("cover_image_url")), path,
                List.of(fact("Barcode", row.get("barcode")), fact(en ? "Status" : "Trạng thái", label(row.get("status"), en)),
                    fact(en ? "Borrowed" : "Ngày mượn", row.get("borrowed_date")), fact(en ? "Due" : "Hạn trả", row.get("due_date")),
                    fact(en ? "Returned" : "Ngày trả", row.get("returned_date")), fact(en ? "Renewals used" : "Số lượt đã gia hạn", row.get("renewal_count"))));
        } else if (tool == ChatbotTool.get_my_fines) {
            path = "/userpage/fines";
            from = "FROM fines f JOIN borrowing_transactions t ON t.id=f.transaction_id JOIN items i ON i.id=t.item_id JOIN publications p ON p.id=i.publication_id WHERE t.user_id=:userId";
            String status = args.getOrDefault("status", "UNPAID");
            if (!"ALL".equals(status)) { from += " AND f.payment_status=:status"; p.put("status", status); }
            select = "SELECT f.id,p.title,f.fine_amount,f.payment_status,f.type,f.created_at "; order = "f.created_at DESC,f.id DESC";
            card = row -> new Card(id(row), text(row.get("title")), null, null, path,
                List.of(fact(en ? "Amount" : "Số tiền", money(row.get("fine_amount"))), fact(en ? "Status" : "Trạng thái", label(row.get("payment_status"), en)),
                    fact(en ? "Violation" : "Vi phạm", label(row.get("type"), en)), fact(en ? "Created" : "Ngày phát sinh", row.get("created_at"))));
        } else if (tool == ChatbotTool.get_my_reservations) {
            path = "/userpage/reservations";
            from = "FROM reservations r JOIN publications p ON p.id=r.publication_id WHERE r.user_id=:userId";
            select = "SELECT r.id,p.title,p.cover_image_url,r.status,r.queue_position,r.hold_expiration_time,r.preferred_branch "; order = "r.reservation_date DESC,r.id DESC";
            card = row -> new Card(id(row), text(row.get("title")), null, image(row.get("cover_image_url")), path,
                List.of(fact(en ? "Status" : "Trạng thái", label(row.get("status"), en)), fact(en ? "Queue position" : "Vị trí hàng đợi", row.get("queue_position")),
                    fact(en ? "Pickup deadline" : "Hạn nhận", row.get("hold_expiration_time")), fact(en ? "Campus" : "Cơ sở", row.get("preferred_branch"))));
        } else if (tool == ChatbotTool.get_my_wishlist) {
            path = "/userpage/wishlist";
            from = "FROM wish_lists_item wi JOIN wish_lists w ON w.id=wi.wish_list_id JOIN publications p ON p.id=wi.publication_id WHERE w.user_id=:userId";
            select = "SELECT p.id,p.title,p.cover_image_url,wi.added_at "; order = "wi.added_at DESC NULLS LAST,wi.id DESC";
            card = row -> new Card(id(row), text(row.get("title")), null, image(row.get("cover_image_url")), bookPath(row), List.of(fact(en ? "Added" : "Ngày thêm", row.get("added_at"))));
        } else if (tool == ChatbotTool.get_my_notifications) {
            path = "/userpage/notifications";
            from = "FROM user_notifications un JOIN notifications n ON n.id=un.notification_id WHERE un.user_id=:userId";
            select = "SELECT un.id,n.title,n.message,un.is_read,un.created_at "; order = "un.created_at DESC,un.id DESC";
            card = row -> new Card(id(row), clip(row.get("title"), 250), clip(row.get("message"), 700), null, path,
                List.of(fact(en ? "Received" : "Thời điểm nhận", row.get("created_at")), fact(en ? "Read" : "Đã đọc", row.get("is_read") == null ? "—" : Boolean.TRUE.equals(row.get("is_read")) ? (en ? "Yes" : "Có") : (en ? "No" : "Không"))));
        } else throw new IllegalArgumentException("Not a personal read tool");
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + from, p, Long.class);
        String message = total == null || total == 0 ? (en ? "No records match this filter." : "Bạn chưa có dữ liệu phù hợp với bộ lọc này.")
            : en ? "Your records at the time of lookup" : "Dữ liệu của bạn tại thời điểm tra cứu";
        if (tool == ChatbotTool.get_my_loans) {
            long count=total==null?0:total;
            String status=args.getOrDefault("status","ACTIVE");
            if("DUE_SOON".equals(status) || "OVERDUE".equals(status)) {
                String condition="OVERDUE".equals(status) ? (en?"overdue":"đã quá hạn") : (en?"overdue or due within the next 3 days":"đã quá hạn hoặc đến hạn trong 3 ngày tới");
                message=count==0 ? (en?"No, you have no books ":"Không, hiện bạn không có sách ")+condition+"."
                    : (en?"Yes, you have ":"Có, bạn có ")+count+(en?" books ":" cuốn ")+condition+(en?". Please prioritize returning these.":". Bạn nên ưu tiên trả các cuốn sau.");
            } else message=count==0 ? (en?"You have no loans matching this filter.":"Bạn chưa có sách đang mượn phù hợp với yêu cầu.")
                : (en?"You currently have ":"Bạn đang có ")+count+(en?" loans matching this filter.":" cuốn theo bộ lọc này.");
        }
        if (tool == ChatbotTool.get_my_fines) {
            BigDecimal amount = jdbc.queryForObject("SELECT COALESCE(SUM(f.fine_amount),0) " + from, p, BigDecimal.class);
            String status=args.getOrDefault("status","UNPAID");
            if("UNPAID".equals(status)) message=total==null || total==0 ? (en?"You have no unpaid fines.":"Hiện bạn không có phí phạt chưa thanh toán.")
                : (en?"You have ":"Bạn có ")+total+(en?" unpaid fines, totaling ":" khoản phạt chưa thanh toán, tổng cộng ")+money(amount)+".";
            else message=(en?"I found ":"Tôi tìm thấy ")+(total==null?0:total)+(en?" fine records, totaling ":" khoản phạt theo yêu cầu, tổng cộng ")+money(amount)+".";
        } else if(tool != ChatbotTool.get_my_loans) {
            long count=total==null?0:total;
            String noun=switch(tool) {
                case get_my_reservations -> en?"reservations":"lượt đặt trước";
                case get_my_wishlist -> en?"saved books":"sách yêu thích";
                case get_my_notifications -> en?"notifications":"thông báo";
                default -> en?"returned loans":"lượt mượn đã trả";
            };
            message=count==0 ? (en?"You have no ":"Bạn chưa có ")+noun+(en?" matching this request.":" phù hợp với yêu cầu này.")
                : (en?"I found ":"Tôi tìm thấy ")+count+" "+noun+(en?" for you.":" của bạn.");
        }
        var rows = jdbc.queryForList(select + from + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", p);
        return reply(message, rows.stream().map(card).toList(), total, page, path, en);
    }

    private static ChatbotReply reply(String message, List<Card> cards, Long total, int page, String path, boolean en) {
        String title=switch(path) {
            case "/userpage/my-books" -> en?"My books":"Sách của tôi";
            case "/userpage/fines" -> en?"My fines":"Phí phạt của tôi";
            case "/userpage/reservations" -> en?"My reservations":"Đặt trước của tôi";
            case "/userpage/wishlist" -> en?"My wishlist":"Sách yêu thích";
            case "/userpage/notifications" -> en?"My notifications":"Thông báo của tôi";
            case "/publicpage/search" -> en?"Book catalog":"Danh mục sách";
            case "/publicpage/faq#current-policy" -> en?"Circulation policy":"Quy định mượn trả";
            default -> en?"Book details":"Chi tiết ấn phẩm";
        };
        return new ChatbotReply("ANSWERED", null, message, title, path, null,
            List.of(new Source(title, path)), cards, total == null ? 0L : total, page, Instant.now());
    }
    private static Fact fact(String label, Object value) { return new Fact(label, text(value)); }
    private static String label(Object value, boolean en) {
        return switch (text(value)) {
            case "BORROWING" -> en ? "Borrowing" : "Đang mượn";
            case "OVERDUE" -> en ? "Overdue" : "Quá hạn";
            case "RETURNED" -> en ? "Returned" : "Đã trả";
            case "WAITING_FOR_PICKUP" -> en ? "Waiting for pickup" : "Chờ nhận sách";
            case "READY_FOR_PICKUP" -> en ? "Ready for pickup" : "Đã có sách, chờ nhận";
            case "PENDING" -> en ? "Queued" : "Đang chờ";
            case "CANCELLED" -> en ? "Cancelled" : "Đã hủy";
            case "EXPIRED" -> en ? "Expired" : "Hết hạn";
            case "COMPLETED" -> en ? "Completed" : "Hoàn tất";
            case "UNPAID" -> en ? "Unpaid" : "Chưa thanh toán";
            case "PAID" -> en ? "Paid" : "Đã thanh toán";
            case "OVERDUE_RETURN" -> en ? "Late return" : "Trả trễ hạn";
            case "DAMAGED_BOOK" -> en ? "Damaged book" : "Sách hư hỏng";
            case "LOST_BOOK" -> en ? "Lost book" : "Mất sách";
            default -> text(value);
        };
    }
    private static String id(Map<String, Object> row) { return text(row.get("id")); }
    private static String bookPath(Map<String, Object> row) { return "/publicpage/book/" + id(row); }
    private static String text(Object value) {
        if (value == null) return "—";
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDateTime().toString().replace('T',' ');
        return value.toString();
    }
    private static String clip(Object value, int limit) { String text = text(value); return text.length() > limit ? text.substring(0, limit) + "…" : text; }
    private static String money(Object value) {
        if (value == null) return "—";
        return new java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.forLanguageTag("vi-VN")))
            .format(new BigDecimal(value.toString()).setScale(0, RoundingMode.HALF_UP)) + "đ";
    }
    private static String image(Object value) {
        if (value == null) return null;
        String url = value.toString();
        return url.matches("(?i)^https?://[^\\s]+$") || (url.startsWith("/") && !url.startsWith("//") && !url.contains("\\")) ? url : null;
    }
}
