package com.streamhub.recs;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The online feature store, in Redis:
 *
 *   recs:taste:{user}   hash  v = taste vector, at = when it was last updated
 *   recs:seen:{user}    set   titles the user has watched (never recommended back)
 *   recs:trend:{hour}   zset  seconds watched per title in that hour (for cold start)
 *   recs:done:{event}   flag  this event was applied (idempotency)
 *   recs:foryou:{user}  json  the last computed "for you" row, for 30 s
 *
 * All four change together in one Lua script, so an event is applied exactly
 * once in effect: Kafka may deliver it twice, but the second time the flag is
 * already there and nothing changes.
 */
@Component
public class TasteStore {

    public enum Applied { APPLIED, DUPLICATE, CONFLICT }

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC);
    static final Duration DONE_FOR = Duration.ofDays(7);   // far longer than any redelivery
    static final Duration SEEN_FOR = Duration.ofDays(90);
    static final Duration TREND_BUCKET_FOR = Duration.ofHours(26);

    /**
     * Applies an event's changes unless it was applied already. The taste was
     * computed from a copy read earlier; if someone changed it since (its
     * "at" moved), nothing is written and the caller recomputes. Events for
     * one user share a Kafka partition, so that's rare, but it's checked.
     */
    private static final RedisScript<Long> APPLY = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            local at = redis.call('HGET', KEYS[2], 'at')
            if (at or '') ~= ARGV[1] then return -1 end
            redis.call('HSET', KEYS[2], 'v', ARGV[2], 'at', ARGV[3])
            redis.call('SADD', KEYS[3], ARGV[4])
            redis.call('EXPIRE', KEYS[3], ARGV[7])
            redis.call('ZINCRBY', KEYS[4], ARGV[5], ARGV[4])
            redis.call('EXPIRE', KEYS[4], ARGV[8])
            redis.call('SET', KEYS[1], '1', 'EX', ARGV[6])
            redis.call('DEL', KEYS[5])       -- taste changed: the cached row is out of date
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public TasteStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Taste> taste(long userId) {
        Map<Object, Object> h = redis.opsForHash().entries(tasteKey(userId));
        if (h.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Taste(Taste.decode((String) h.get("v")),
                Instant.ofEpochMilli(Long.parseLong((String) h.get("at")))));
    }

    public boolean alreadyApplied(String eventId) {
        return Boolean.TRUE.equals(redis.hasKey(doneKey(eventId)));
    }

    /** {@code previous} is the taste the new one was computed from (null if none). */
    public Applied apply(String eventId, long userId, long titleId, int watchedSeconds, Instant when,
                         Taste previous, Taste next) {
        Long r = redis.execute(APPLY,
                List.of(doneKey(eventId), tasteKey(userId), seenKey(userId), trendKey(when), forYouKey(userId)),
                previous == null ? "" : Long.toString(previous.at().toEpochMilli()),
                next.encoded(), Long.toString(next.at().toEpochMilli()),
                Long.toString(titleId), Integer.toString(watchedSeconds),
                Long.toString(DONE_FOR.toSeconds()), Long.toString(SEEN_FOR.toSeconds()),
                Long.toString(TREND_BUCKET_FOR.toSeconds()));
        return r == null || r == -1 ? Applied.CONFLICT : r == 1 ? Applied.APPLIED : Applied.DUPLICATE;
    }

    public Set<Long> seen(long userId) {
        Set<String> s = redis.opsForSet().members(seenKey(userId));
        return s == null ? Set.of() : s.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /** Most-watched titles over the last 24 hours, best first. */
    public List<Long> trending(Instant now, int limit) {
        List<String> buckets = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            buckets.add(trendKey(now.minus(Duration.ofHours(h))));
        }
        Set<ZSetOperations.TypedTuple<String>> top = redis.opsForZSet()
                .unionWithScores(buckets.get(0), buckets.subList(1, buckets.size()));
        if (top == null) {
            return List.of();
        }
        return top.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(limit).map(t -> Long.valueOf(t.getValue())).toList();
    }

    /**
     * The computed row, reused for a short while: each page view would
     * otherwise be a vector search in the catalog. Cleared whenever the
     * user's taste changes (see APPLY), so it's never stale about what they
     * just watched; the TTL only bounds how long trending can lag.
     */
    public Optional<String> cachedForYou(long userId) {
        return Optional.ofNullable(redis.opsForValue().get(forYouKey(userId)));
    }

    public void cacheForYou(long userId, String json, Duration ttl) {
        redis.opsForValue().set(forYouKey(userId), json, ttl);
    }

    private static String forYouKey(long u) { return "recs:foryou:" + u; }
    private static String tasteKey(long u) { return "recs:taste:" + u; }
    private static String seenKey(long u) { return "recs:seen:" + u; }
    private static String doneKey(String e) { return "recs:done:" + e; }
    private static String trendKey(Instant t) { return "recs:trend:" + HOUR.format(t); }
}
