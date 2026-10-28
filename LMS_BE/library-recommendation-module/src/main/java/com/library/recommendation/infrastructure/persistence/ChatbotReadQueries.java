package com.library.recommendation.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.recommendation.application.chatbot.ChatbotTool;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SELECT-only JSON adapters. No response prose, UI cards, tracking or payment mutations. */
@Repository
public class ChatbotReadQueries {
    private static final String READY="i.status='AVAILABLE' AND NOT EXISTS (SELECT 1 FROM reshelving_tasks rq WHERE rq.item_id=i.id AND rq.status='WAITING')";
    private static final String TODAY="(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh')::date";
    private final NamedParameterJdbcTemplate jdbc;
    public ChatbotReadQueries(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Transactional(readOnly=true)
    public Map<String,Object> execute(ChatbotTool tool,JsonNode arguments,Long readerId,String language) {
        if(tool.personal && readerId==null) throw new SecurityException("Reader authentication required");
        var args=validate(tool,arguments);
        int page=arguments.path("page").asInt(0), size=arguments.path("pageSize").asInt(10);
        var p=new HashMap<String,Object>();
        p.put("userId",readerId);p.put("limit",size);p.put("offset",page*size);
        p.put("query",args.getOrDefault("query",""));
        p.put("publicationId",args.containsKey("publicationId")?Long.valueOf(args.get("publicationId")):null);
        p.put("branch",switch(args.getOrDefault("branch","ANY")) {
            case "CAMPUS_1" -> "Cơ sở 1 - Lý Thường Kiệt";
            case "CAMPUS_2" -> "Cơ sở 2 - Dĩ An";
            default -> null;
        });
        if(tool==ChatbotTool.get_current_circulation_policy) {
            var rows=jdbc.queryForList("""
                SELECT pickup_deadline_hours,default_loan_days,max_active_borrows,max_active_reservations,
                    max_renewals,renewal_window_days,overdue_fine_per_day,default_deposit_amount,
                    block_borrow_when_unpaid_fines,updated_at FROM circulation_policies WHERE id=1
                """,Map.of());
            if(rows.isEmpty()) throw new IllegalStateException("Policy unavailable");
            return page(rows,1L,0,size,"/publicpage/faq#current-policy");
        }
        if(tool==ChatbotTool.search_library_help) throw new IllegalArgumentException("Help is provided by the knowledge adapter");
        return tool.personal?personal(tool,p,args,page,size):catalog(tool,p,args,page,size);
    }

    public static Map<String, String> validate(ChatbotTool tool, JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Invalid tool arguments");
        var args = new HashMap<String, String>();
        node.fields().forEachRemaining(field -> {
            String key = field.getKey(); JsonNode value = field.getValue();
            if (!tool.arguments.contains(key)) throw new IllegalArgumentException("Unknown argument");
            if ("page".equals(key) || "pageSize".equals(key)) {
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < ("pageSize".equals(key)?1:0) || value.intValue() > ("pageSize".equals(key)?50:1000))
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


    private Map<String,Object> catalog(ChatbotTool tool,Map<String,Object> p,Map<String,String> args,int page,int size) {
        if(tool!=ChatbotTool.list_publications && ((String)p.get("query")).isBlank() && p.get("publicationId")==null)
            throw new IllegalArgumentException("A title, query, ISBN or publication ID is required");
        boolean one=Set.of(ChatbotTool.get_publication_details,ChatbotTool.get_publication_availability,ChatbotTool.get_publication_rating_summary).contains(tool);
        String from="""
            FROM publications p WHERE (CAST(:publicationId AS BIGINT) IS NULL OR p.id=:publicationId)
            AND (:query='' OR LOWER(COALESCE(p.isbn,''))=LOWER(:query) OR
                to_tsvector('simple',unaccent(CONCAT_WS(' ',p.title,
                    (SELECT STRING_AGG(a.name,' ') FROM publication_authors pa JOIN authors a ON a.id=pa.author_id WHERE pa.publication_id=p.id)
            """+(one?"":",p.description,p.ai_summary")+"""
                ))) @@ plainto_tsquery('simple',unaccent(:query)))
            """+(one?"":" AND (CAST(:branch AS VARCHAR) IS NULL OR EXISTS (SELECT 1 FROM items bi WHERE bi.publication_id=p.id AND bi.branch=:branch)) ");
        Long total=jdbc.queryForObject("SELECT COUNT(*) "+from,p,Long.class);
        var bp=new HashMap<>(p);
        if(one) { bp.put("offset",0);bp.put("limit",size); }
        String order="most_borrowed".equals(args.get("sort"))
            ?"(SELECT COUNT(*) FROM borrowing_transactions bt JOIN items bi ON bi.id=bt.item_id WHERE bi.publication_id=p.id AND bt.borrowed_date IS NOT NULL AND (CAST(:branch AS VARCHAR) IS NULL OR bi.branch=:branch)) DESC,p.id DESC"
            :"p.created_at DESC NULLS LAST,p.id DESC";
        var books=jdbc.queryForList("""
            SELECT p.id,p.title,p.isbn,p.description,p.ai_summary,p.publication_year,p.edition,p.cover_image_url,
                (SELECT pub.name FROM publishers pub WHERE pub.id=p.publisher_id) AS publisher,
                (SELECT STRING_AGG(DISTINCT a.name,', ' ORDER BY a.name) FROM publication_authors pa JOIN authors a ON a.id=pa.author_id WHERE pa.publication_id=p.id) AS authors,
                (SELECT COUNT(*) FROM items i WHERE i.publication_id=p.id AND
            """+READY+" AND (CAST(:branch AS VARCHAR) IS NULL OR i.branch=:branch)) AS available_items "+from+" ORDER BY "+order+" LIMIT :limit OFFSET :offset",bp);
        var records=books.stream().map(book->{var row=normalized(book);row.put("url","/publicpage/book/"+book.get("id"));return row;}).toList();
        var result=page(records,total,one?0:page,size,"/publicpage/search");
        result.put("branch",p.get("branch"));
        if(one && total!=null && total==1 && !books.isEmpty()) {
            var book=books.getFirst();p.put("publicationId",((Number)book.get("id")).longValue());
            String path="/publicpage/book/"+book.get("id");
            if(tool==ChatbotTool.get_publication_availability) {
                String copies="FROM items i WHERE i.publication_id=:publicationId AND "+READY+" AND (CAST(:branch AS VARCHAR) IS NULL OR i.branch=:branch)";
                Long count=jdbc.queryForObject("SELECT COUNT(*) "+copies,p,Long.class);
                var items=jdbc.queryForList("SELECT i.id,i.barcode,i.branch,i.location "+copies+" ORDER BY i.branch,i.location,i.id LIMIT :limit OFFSET :offset",p);
                result=page(items,count,page,size,path);result.put("publication",records.getFirst());result.put("branch",p.get("branch"));
                result.put("availabilityByBranch",jdbc.queryForList("SELECT i.branch,COUNT(*) AS available_items "+copies+" GROUP BY i.branch ORDER BY i.branch",p).stream().map(ChatbotReadQueries::normalized).toList());
                result.put("reservationCreated",false);
            } else if(tool==ChatbotTool.get_publication_rating_summary) {
                var ratings=jdbc.queryForList("SELECT star,COUNT(*) AS votes FROM ratings WHERE publication_id=:publicationId GROUP BY star ORDER BY star DESC",p);
                var summary=jdbc.queryForList("SELECT COUNT(*) AS rating_count,COALESCE(ROUND(AVG(star)::numeric,2),0) AS rating_average FROM ratings WHERE publication_id=:publicationId",p);
                result=page(ratings,(long)ratings.size(),0,size,path);
                result.put("publication",records.getFirst());result.put("summary",summary.isEmpty()?Map.of():normalized(summary.getFirst()));
            }
        } else if(one) result.put("needsPublicationSelection",total!=null && total>1);
        return result;
    }

    private Map<String,Object> personal(ChatbotTool tool,Map<String,Object> p,Map<String,String> args,int page,int size) {
        String path,from,select,order;
        if(tool==ChatbotTool.get_my_loans || tool==ChatbotTool.get_my_loan_history) {
            path="/userpage/my-books";
            from="FROM borrowing_transactions t JOIN items i ON i.id=t.item_id JOIN publications p ON p.id=i.publication_id WHERE t.user_id=:userId AND "
                +(tool==ChatbotTool.get_my_loans?"t.status IN ('BORROWING','OVERDUE')":"t.status IN ('RETURNED','LOST_RECOVERED')");
            String status=args.getOrDefault("status","ACTIVE");
            if(tool==ChatbotTool.get_my_loans && !"ACTIVE".equals(status)) {
                if("DUE_SOON".equals(status)) from+=" AND t.due_date <= "+TODAY+" + 3";
                else if("OVERDUE".equals(status)) from+=" AND t.due_date < "+TODAY;
                else { from=from.replace("t.status IN ('BORROWING','OVERDUE')","t.status=:loanStatus");p.put("loanStatus",status); }
            }
            String dateColumn="borrowed".equals(args.get("dateType"))?"t.borrowed_date":"t.returned_date";
            if(args.containsKey("dateFrom")) {from+=" AND ("+dateColumn+" AT TIME ZONE 'Asia/Ho_Chi_Minh')::date>=CAST(:dateFrom AS DATE)";p.put("dateFrom",args.get("dateFrom"));}
            if(args.containsKey("dateTo")) {from+=" AND ("+dateColumn+" AT TIME ZONE 'Asia/Ho_Chi_Minh')::date<=CAST(:dateTo AS DATE)";p.put("dateTo",args.get("dateTo"));}
            select="SELECT t.id,p.id AS publication_id,p.title,p.cover_image_url,i.barcode,i.branch,i.location,t.status,CASE WHEN t.status IN ('BORROWING','OVERDUE') AND t.due_date < "+TODAY+" THEN 'OVERDUE' ELSE t.status END AS effective_status,t.borrowed_date,t.due_date,t.returned_date,t.renewal_count ";
            order=tool==ChatbotTool.get_my_loans?"t.due_date ASC,t.id ASC":"t.returned_date DESC NULLS LAST,t.id DESC";
        } else if(tool==ChatbotTool.get_my_fines) {
            path="/userpage/fines";
            from="FROM fines f JOIN borrowing_transactions t ON t.id=f.transaction_id JOIN items i ON i.id=t.item_id JOIN publications p ON p.id=i.publication_id WHERE t.user_id=:userId";
            String status=args.getOrDefault("status","UNPAID");
            if(!"ALL".equals(status)) {from+=" AND f.payment_status=:status";p.put("status",status);}
            select="SELECT f.id,t.id AS transaction_id,p.id AS publication_id,p.title,f.fine_amount,f.payment_status,f.type,f.created_at ";order="f.created_at DESC,f.id DESC";
        } else if(tool==ChatbotTool.get_my_reservations) {
            path="/userpage/reservations";
            from="FROM reservations r JOIN publications p ON p.id=r.publication_id WHERE r.user_id=:userId";
            select="SELECT r.id,p.id AS publication_id,p.title,p.cover_image_url,r.status,r.queue_position,r.hold_expiration_time,r.preferred_branch ";order="r.reservation_date DESC,r.id DESC";
        } else if(tool==ChatbotTool.get_my_wishlist) {
            path="/userpage/wishlist";
            from="FROM wish_lists_item wi JOIN wish_lists w ON w.id=wi.wish_list_id JOIN publications p ON p.id=wi.publication_id WHERE w.user_id=:userId";
            select="SELECT p.id,p.id AS publication_id,p.title,p.cover_image_url,wi.added_at ";order="wi.added_at DESC NULLS LAST,wi.id DESC";
        } else if(tool==ChatbotTool.get_my_notifications) {
            path="/userpage/notifications";
            from="FROM user_notifications un JOIN notifications n ON n.id=un.notification_id WHERE un.user_id=:userId";
            select="SELECT un.id,n.title,n.message,un.is_read,un.created_at ";order="un.created_at DESC,un.id DESC";
        } else throw new IllegalArgumentException("Not a personal read tool");
        Long total=jdbc.queryForObject("SELECT COUNT(*) "+from,p,Long.class);
        var result=page(jdbc.queryForList(select+from+" ORDER BY "+order+" LIMIT :limit OFFSET :offset",p),total,page,size,path);
        if(tool==ChatbotTool.get_my_fines) result.put("totalFineAmount",jdbc.queryForObject("SELECT COALESCE(SUM(f.fine_amount),0) "+from,p,BigDecimal.class));
        if(tool==ChatbotTool.get_my_loans) {
            result.put("urgentWindowDays",3);
            var summary=jdbc.queryForList("SELECT COUNT(*) FILTER (WHERE t.due_date < "+TODAY+") AS overdue_count,COUNT(*) FILTER (WHERE t.due_date BETWEEN "+TODAY+" AND "+TODAY+"+3) AS due_within_three_days,MIN(t.due_date) AS earliest_due_date "+from,p);
            result.put("summary",summary.isEmpty()?Map.of():normalized(summary.getFirst()));
        }
        return result;
    }
    private static Map<String,Object> page(List<? extends Map<String,?>> rows,Long total,int page,int size,String path) {
        var result=new LinkedHashMap<String,Object>();long count=total==null?0:total;
        result.put("records",rows.stream().map(ChatbotReadQueries::normalized).toList());
        result.put("total",count);result.put("page",page);result.put("pageSize",size);
        result.put("hasNext",((long)page+1)*size<count);result.put("url",path);result.put("retrievedAt",Instant.now().toString());
        return result;
    }
    private static LinkedHashMap<String,Object> normalized(Map<String,?> row) {
        var result=new LinkedHashMap<String,Object>();
        row.forEach((key,value)->{
            Object normalized=value;
            if(value instanceof Timestamp date) normalized=date.toInstant().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime().toString();
            else if(value instanceof java.sql.Date date) normalized=date.toLocalDate().toString();
            else if(value instanceof java.time.temporal.TemporalAccessor) normalized=value.toString();
            else if((key.equals("id") || key.endsWith("_id")) && value instanceof Number) normalized=value.toString();
            result.put(key,normalized);
        });
        if(result.get("publication_id")!=null) result.put("publication_url","/publicpage/book/"+result.get("publication_id"));
        return result;
    }
}
