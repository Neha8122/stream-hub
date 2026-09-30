package com.streamhub.playback;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Playback sessions in Redis, one hash per session with a TTL that every
 * heartbeat renews. A player that goes quiet simply expires.
 */
@Component
public class SessionStore {

    public record Session(String id, long userId, long titleId, int positionSeconds, int durationSeconds) { }

    /** Result of moving a session's position. */
    public sealed interface Move {
        record Moved(Session session, int previousPosition) implements Move { }
        record NotFound() implements Move { }
        record NotYours() implements Move { }
    }

    /**
     * Check the owner and swap the position in one atomic step, so two
     * heartbeats racing each other can't both compute their delta from the
     * same old position.
     */
    private static final DefaultRedisScript<List> MOVE = new DefaultRedisScript<>("""
            local s = redis.call('HMGET', KEYS[1], 'userId', 'titleId', 'position', 'duration')
            if not s[1] then return {'missing'} end
            if s[1] ~= ARGV[1] then return {'not-yours'} end
            redis.call('HSET', KEYS[1], 'position', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return {'ok', s[2], s[3], s[4]}
            """, List.class);

    private final StringRedisTemplate redis;

    public SessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Session open(long userId, long titleId, int position, int duration, Duration ttl) {
        String id = UUID.randomUUID().toString();
        String key = key(id);
        redis.opsForHash().putAll(key, Map.of(
                "userId", Long.toString(userId), "titleId", Long.toString(titleId),
                "position", Integer.toString(position), "duration", Integer.toString(duration)));
        redis.expire(key, ttl);
        return new Session(id, userId, titleId, position, duration);
    }

    public Move move(String sessionId, long userId, int newPosition, Duration ttl) {
        List<?> r = redis.execute(MOVE, List.of(key(sessionId)),
                Long.toString(userId), Integer.toString(newPosition), Long.toString(ttl.toMillis()));
        String status = String.valueOf(r.get(0));
        return switch (status) {
            case "missing" -> new Move.NotFound();
            case "not-yours" -> new Move.NotYours();
            default -> new Move.Moved(new Session(sessionId, userId, Long.parseLong(String.valueOf(r.get(1))),
                    newPosition, Integer.parseInt(String.valueOf(r.get(3)))),
                    Integer.parseInt(String.valueOf(r.get(2))));
        };
    }

    public void close(String sessionId) {
        redis.delete(key(sessionId));
    }

    public Optional<Long> ttlSeconds(String sessionId) {
        Long t = redis.getExpire(key(sessionId));
        return t == null || t < 0 ? Optional.empty() : Optional.of(t);
    }

    private static String key(String id) {
        return "session:" + id;
    }
}
