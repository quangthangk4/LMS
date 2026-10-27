package com.library.recommendation.application.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.recommendation.infrastructure.cache.RedisStateStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Six bounded turns, scoped by authenticated owner; no private tool results in model history. */
@Component
public class ChatConversationStore implements com.library.shared.port.ChatConversationRevocationPort {
    public static final Duration TTL = Duration.ofMinutes(30);
    public record Turn(String question, String publicAnswer) {}
    public record Context(String language, List<Turn> turns, String bookReference) {}
    public record Session(String id, String key, String expected, Context context,
                          RedisStateStore.Lease lease, boolean reset, boolean busy) {}
    private final RedisStateStore redis;
    private final ObjectMapper mapper;
    public ChatConversationStore(RedisStateStore redis, ObjectMapper mapper) { this.redis=redis; this.mapper=mapper; }

    private String key(UUID id, Long owner) {
        if(owner==null) return "lms:chat:v1:public:"+id;
        String ownerKey="lms:chat:owner:"+owner;
        var generation=redis.read(ownerKey);
        if(!generation.available()) return "lms:chat:offline:"+owner+":"+id;
        if(generation.value()==null) {
            redis.create(ownerKey,UUID.randomUUID().toString(),Duration.ofMinutes(60));
            generation=redis.read(ownerKey);
        }
        if(generation.value()!=null) redis.replace(ownerKey,generation.value(),generation.value(),Duration.ofMinutes(60));
        return "lms:chat:v1:reader:"+owner+":"+generation.value()+":"+id;
    }
    @Override public void revoke(Long owner) {
        redis.put("lms:chat:owner:"+owner,UUID.randomUUID().toString(),Duration.ofMinutes(60));
    }
    public Session begin(UUID supplied, Long owner, String language) {
        String id = (supplied == null ? UUID.randomUUID() : supplied).toString();
        String key = key(UUID.fromString(id), owner);
        var lease = redis.acquire(key + ":lock", Duration.ofSeconds(32));
        var empty = new Context(language,List.of(),"");
        if (lease.available() && !lease.acquired()) return new Session(id,key,null,empty,lease,false,true);
        var read = redis.read(key);
        if (!read.available()) return new Session(id,key,null,empty,lease,true,false);
        String expected = read.value();
        if("CLEARED".equals(expected)) { redis.release(lease); return new Session(id,key,null,empty,lease,true,true); }
        Context context = empty;
        try {
            if (expected != null && expected.length() <= 24000) {
                var decoded = mapper.readValue(expected, Context.class);
                if (language.equals(decoded.language()) && decoded.turns() != null && decoded.turns().size() <= 6) context=decoded;
            }
            if (expected == null) {
                expected = mapper.writeValueAsString(empty);
                if (!redis.create(key,expected,TTL)) expected=null;
            }
        } catch (Exception invalid) { expected=null; }
        return new Session(id,key,expected,context,lease,supplied != null && context.turns().isEmpty(),false);
    }
    public void finish(Session session, String question, ChatbotReply reply, String reference) {
        try {
            if (session.expected() == null || session.busy()) return;
            var turns = new ArrayList<>(session.context().turns());
            // Never store private results in history passed to Gemini. Only public prose and references.
            String publicText = reply.sections().isEmpty() ? reply.answer() : reply.sections().stream()
                .map(ChatbotReply::answer).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.joining("\n"));
            turns.add(new Turn(question, publicText == null ? "" : publicText.substring(0,Math.min(publicText.length(),1200))));
            if (turns.size()>6) turns.remove(0);
            var next = new Context(session.context().language(),List.copyOf(turns), reference == null ? session.context().bookReference() : reference);
            redis.replace(session.key(),session.expected(),mapper.writeValueAsString(next),TTL);
        } catch (Exception ignored) { /* Stateless answering remains available. */ }
        finally { redis.release(session.lease()); }
    }
    public void clear(UUID id, Long owner) {
        String key=key(id,owner);
        // A tombstone also prevents an aborted request still starting from resurrecting context.
        redis.put(key,"CLEARED",TTL);
    }
}
