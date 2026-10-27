package com.library.recommendation.infrastructure.cache;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Best-effort, bounded-lifetime state. No business records or untrusted key passthrough. */
@Component
public class RedisStateStore {
    public record Read(boolean available, String value) {}
    public record Lease(String key, String token, boolean available, boolean acquired) {}
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> REPLACE = new DefaultRedisScript<>(
        "if redis.call('get',KEYS[1]) == ARGV[1] then redis.call('psetex',KEYS[1],ARGV[3],ARGV[2]); return 1 else return 0 end", Long.class);

    public RedisStateStore(StringRedisTemplate redis, @Value("${lms.redis.enabled:true}") boolean enabled) {
        this.redis = redis; this.enabled = enabled;
    }
    public Read read(String key) {
        if (!enabled) return new Read(false, null);
        try { return new Read(true, redis.opsForValue().get(key)); }
        catch (RuntimeException failure) { return new Read(false, null); }
    }
    public boolean put(String key, String value, Duration ttl) {
        if (!enabled || ttl.isNegative() || ttl.isZero()) return false;
        try { redis.opsForValue().set(key, value, ttl); return true; }
        catch (RuntimeException failure) { return false; }
    }
    public boolean create(String key, String value, Duration ttl) {
        if (!enabled) return false;
        try { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, value, ttl)); }
        catch (RuntimeException failure) { return false; }
    }
    public boolean replace(String key, String expected, String value, Duration ttl) {
        if (!enabled) return false;
        try { return Long.valueOf(1).equals(redis.execute(REPLACE, List.of(key), expected, value, Long.toString(ttl.toMillis()))); }
        catch (RuntimeException failure) { return false; }
    }
    public boolean deleteIfValue(String key, String expected) {
        if (!enabled) return false;
        try { return Long.valueOf(1).equals(redis.execute(RELEASE, List.of(key), expected)); }
        catch (RuntimeException failure) { return false; }
    }
    public Lease acquire(String key, Duration ttl) {
        if (!enabled) return new Lease(key, "", false, false);
        String token = UUID.randomUUID().toString();
        try { return new Lease(key, token, true, Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl))); }
        catch (RuntimeException failure) { return new Lease(key, token, false, false); }
    }
    public void release(Lease lease) {
        if (lease != null && lease.available() && lease.acquired()) deleteIfValue(lease.key(), lease.token());
    }
}
