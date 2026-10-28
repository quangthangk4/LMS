package com.library.recommendation.application.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.infrastructure.cache.RedisStateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Completed user/model text only. Native tool exchanges exist only during the active request. */
@Component
public class ChatConversationStore implements com.library.shared.port.ChatConversationRevocationPort {
    public static final Duration TTL = Duration.ofMinutes(30);
    public static final int MAX_BYTES=65536;
    public record Turn(String question, String answer) {}
    public record Context(String language, List<Turn> turns) {}
    public record Session(String id, String key, String expected, Context context,
                          RedisStateStore.Lease lease, boolean reset, boolean busy) {}
    private final RedisStateStore redis;
    private final ObjectMapper mapper;
    public ChatConversationStore(RedisStateStore redis, ObjectMapper mapper) { this.redis=redis; this.mapper=mapper; }

    private String key(UUID id, Long owner) {
        if(owner==null) return "lms:chat:v2:public:"+id;
        String ownerKey="lms:chat:owner:"+owner;
        var generation=redis.read(ownerKey);
        if(!generation.available()) return null;
        if(generation.value()==null) {
            redis.create(ownerKey,UUID.randomUUID().toString(),Duration.ofMinutes(60));
            generation=redis.read(ownerKey);
        }
        if(generation.value()==null) return null;
        redis.replace(ownerKey,generation.value(),generation.value(),Duration.ofMinutes(60));
        return "lms:chat:v2:reader:"+owner+":"+generation.value()+":"+id;
    }
    @Override public void revoke(Long owner) {
        redis.put("lms:chat:owner:"+owner,UUID.randomUUID().toString(),Duration.ofMinutes(60));
    }
    public Session begin(UUID supplied, Long owner, String language) {
        String id = (supplied == null ? UUID.randomUUID() : supplied).toString();
        String key = key(UUID.fromString(id), owner);
        var empty = new Context(language,List.of());
        if(key==null) return new Session(id,"",null,empty,null,true,false);
        var lease = redis.acquire(key + ":lock", Duration.ofSeconds(100));
        if (lease.available() && !lease.acquired()) return new Session(id,key,null,empty,lease,false,true);
        var read = redis.read(key);
        if (!read.available()) return new Session(id,key,null,empty,lease,true,false);
        String expected = read.value();
        if("CLEARED".equals(expected)) { redis.release(lease); return new Session(id,key,null,empty,lease,true,true); }
        Context context = empty;
        try {
            if (expected != null && expected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_BYTES) {
                var decoded = mapper.readValue(expected, Context.class);
                if (language.equals(decoded.language()) && decoded.turns() != null && decoded.turns().size() <= 6
                    && decoded.turns().stream().allMatch(t->t.question()!=null && t.answer()!=null)) context=decoded;
            }
            if (expected == null) {
                expected = mapper.writeValueAsString(empty);
                if (!redis.create(key,expected,TTL)) expected=null;
            }
        } catch (Exception invalid) { expected=null; }
        return new Session(id,key,expected,context,lease,supplied != null && context.turns().isEmpty(),false);
    }
    public boolean finish(Session session, String question, String answer) {
        try {
            if (session.expected() == null || session.busy()) return false;
            var turns = new ArrayList<>(session.context().turns());
            turns.add(new Turn(question,answer));
            if (turns.size()>6) turns.remove(0);
            String encoded=mapper.writeValueAsString(new Context(session.context().language(),List.copyOf(turns)));
            while(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES && !turns.isEmpty()) {
                turns.remove(0); // Evict whole completed turns, never cut Markdown or JSON fragments.
                encoded=mapper.writeValueAsString(new Context(session.context().language(),List.copyOf(turns)));
            }
            boolean saved=redis.replace(session.key(),session.expected(),encoded,TTL);
            return saved && !turns.isEmpty();
        } catch (Exception ignored) { return false; }
        finally { redis.release(session.lease()); }
    }
    public void release(Session session) { redis.release(session.lease()); }
    public void clear(UUID id, Long owner) {
        String key=key(id,owner);
        if(key==null) return;
        // A tombstone also prevents an aborted request still starting from resurrecting context.
        redis.put(key,"CLEARED",TTL);
    }
}
