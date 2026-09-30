package com.streamhub.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongFunction;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Cache-aside in Redis with stampede protection (docs/lld-catalog-cache.html).
 *
 * <p>Each entry carries its own "fresh until" time and lives in Redis much
 * longer than that. A stale entry is still served; one request, the one
 * that wins a short Redis lock, reloads it from Postgres while everyone
 * else gets the stale copy at once. So when a popular title expires, the
 * database sees one query, not thousands.
 *
 * <p>Titles that don't exist are cached too (briefly), and if Redis itself
 * is down every read simply goes to Postgres.
 */
@Component
public class TitleCache {

    /** What's stored under each key: the title (or null if missing) and when it goes stale. */
    record Entry(Title value, long freshUntilMillis) {
        boolean isFresh() {
            return System.currentTimeMillis() < freshUntilMillis;
        }
    }

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CacheSettings settings;
    private final Counter hits, staleHits, misses, refreshes, redisErrors;

    public TitleCache(StringRedisTemplate redis, ObjectMapper json, CacheSettings settings, MeterRegistry metrics) {
        this.redis = redis;
        this.json = json;
        this.settings = settings;
        this.hits = metrics.counter("catalog.cache", "result", "hit");
        this.staleHits = metrics.counter("catalog.cache", "result", "stale");
        this.misses = metrics.counter("catalog.cache", "result", "miss");
        this.refreshes = metrics.counter("catalog.cache.refresh");
        this.redisErrors = metrics.counter("catalog.cache.redis_errors");
    }

    /** The title with this id, read through the cache; {@code loader} reads Postgres. */
    public Optional<Title> get(long id, LongFunction<Optional<Title>> loader) {
        String key = key(id);
        Entry entry;
        try {
            entry = read(key);
        } catch (DataAccessException redisDown) {
            redisErrors.increment();
            return loader.apply(id);            // slower, still correct
        }

        if (entry != null && entry.isFresh()) {
            hits.increment();
            return Optional.ofNullable(entry.value());
        }
        if (entry != null) {
            // Stale: one request refreshes, everyone else is served the old copy right away.
            staleHits.increment();
            if (tryLock(key)) {
                return refresh(id, key, loader);
            }
            return Optional.ofNullable(entry.value());
        }

        // Nothing cached at all: one request loads, the others wait for it.
        // They keep waiting as long as someone holds the lock (a slow loader
        // is not a reason to stampede); if the lock is free and there's still
        // no entry, the loader failed, and the next request takes over.
        misses.increment();
        long deadline = System.currentTimeMillis() + LOCK_TTL.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (tryLock(key)) {
                return refresh(id, key, loader);
            }
            sleep(5);
            Entry loaded = readQuietly(key);
            if (loaded != null) {
                return Optional.ofNullable(loaded.value());
            }
        }
        return loader.apply(id);                // Redis is misbehaving; don't wait forever
    }

    /** After a write: drop the entry, so the next read loads the new value. */
    public void evict(long id) {
        try {
            redis.delete(key(id));
        } catch (DataAccessException redisDown) {
            redisErrors.increment();            // entry expires by itself; fresh-for bounds the staleness
        }
    }

    /** Test hook: pretend the cached entry for {@code id} went stale. */
    void makeStale(long id) {
        Entry e = readQuietly(key(id));
        if (e != null) {
            write(key(id), new Entry(e.value(), 0), settings.keepFor());
        }
    }

    private Optional<Title> refresh(long id, String key, LongFunction<Optional<Title>> loader) {
        try {
            // Double-check: between our read and winning the lock, another
            // request may have refreshed the entry and released the lock.
            Entry now = readQuietly(key);
            if (now != null && now.isFresh()) {
                return Optional.ofNullable(now.value());
            }
            refreshes.increment();
            Optional<Title> title = loader.apply(id);
            if (title.isPresent()) {
                write(key, new Entry(title.get(), freshUntil(settings.freshFor())), settings.keepFor());
            } else {
                // Remember "missing" too, briefly: a stream of bad ids can't hammer Postgres.
                write(key, new Entry(null, freshUntil(settings.missingFor())), settings.missingFor());
            }
            return title;
        } finally {
            unlock(key);
        }
    }

    /** Fresh-for plus or minus 10%, so entries cached together don't all go stale together. */
    private static long freshUntil(Duration freshFor) {
        long ms = freshFor.toMillis();
        long jitter = ms / 10;
        return System.currentTimeMillis() + ms + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
    }

    // --- Redis plumbing ---

    private boolean tryLock(String key) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey(key), "1", LOCK_TTL));
        } catch (DataAccessException e) {
            redisErrors.increment();
            return true;                        // can't coordinate; let this request load
        }
    }

    private void unlock(String key) {
        try {
            redis.delete(lockKey(key));
        } catch (DataAccessException e) {
            redisErrors.increment();            // the lock expires by itself after LOCK_TTL
        }
    }

    private Entry read(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return null;
        }
        try {
            return json.readValue(raw, Entry.class);
        } catch (JsonProcessingException e) {
            return null;                        // unreadable (e.g. old format): treat as a miss
        }
    }

    private Entry readQuietly(String key) {
        try {
            return read(key);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private void write(String key, Entry entry, Duration ttl) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(entry), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        } catch (DataAccessException e) {
            redisErrors.increment();            // couldn't cache it; the next read loads again
        }
    }

    private static String key(long id) {
        return "title:" + id;
    }

    private static String lockKey(String key) {
        return "lock:" + key;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
