package com.streamhub.assistant;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A cost guardrail: how many LLM calls one user, and everyone together, may
 * make per day. Checked and counted in one script, so two concurrent asks
 * can't both take the last call. Cache hits don't count: they're free.
 */
public final class Budget {

    public enum Verdict { OK, USER_LIMIT, GLOBAL_LIMIT }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private static final RedisScript<Long> TAKE = RedisScript.of("""
            local u = tonumber(redis.call('GET', KEYS[1]) or '0')
            local g = tonumber(redis.call('GET', KEYS[2]) or '0')
            if u >= tonumber(ARGV[1]) then return 1 end
            if g >= tonumber(ARGV[2]) then return 2 end
            redis.call('INCR', KEYS[1]); redis.call('EXPIRE', KEYS[1], 172800)
            redis.call('INCR', KEYS[2]); redis.call('EXPIRE', KEYS[2], 172800)
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int perUser;
    private final int global;
    private final Clock clock;

    public Budget(StringRedisTemplate redis, int perUser, int global, Clock clock) {
        this.redis = redis;
        this.perUser = perUser;
        this.global = global;
        this.clock = clock;
    }

    /**
     * Takes one call from the budget if there's one left. If Redis is down
     * this fails closed: without a count there's no limit, and an unlimited
     * LLM bill is worse than an assistant that answers from search for a while.
     */
    public Verdict take(long userId) {
        String day = DAY.format(clock.instant());
        Long r = redis.execute(TAKE, List.of("assistant:budget:" + userId + ":" + day, "assistant:budget:all:" + day),
                Integer.toString(perUser), Integer.toString(global));
        return r == null || r == 2 ? Verdict.GLOBAL_LIMIT : r == 1 ? Verdict.USER_LIMIT : Verdict.OK;
    }
}
