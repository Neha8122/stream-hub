package com.streamhub.recs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * "For you": the titles nearest to the user's taste, minus what they've
 * already watched. A new user has no taste yet, so they get what everyone
 * is watching today instead (and the response says which it is).
 */
@Service
public class RecsService {

    public enum Source { PERSONAL, TRENDING }

    public record Recs(Source source, List<Catalog.Title> titles) { }

    private final TasteStore store;
    private final Catalog catalog;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final ObjectMapper json;
    private final Duration cacheFor;

    public RecsService(TasteStore store, Catalog catalog, MeterRegistry metrics, Clock clock, ObjectMapper json,
                       @Value("${recs.cache-for}") Duration cacheFor) {
        this.json = json;
        this.cacheFor = cacheFor;
        this.store = store;
        this.catalog = catalog;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Recs forYou(long userId, int limit) {
        limit = Math.max(1, Math.min(limit, 50));
        Optional<Recs> cached = cached(userId, limit);
        if (cached.isPresent()) {
            metrics.counter("recs.requests", "source", "cached").increment();
            return cached.get();
        }
        Set<Long> seen = store.seen(userId);
        Optional<Taste> taste = store.taste(userId);
        Recs r;
        if (taste.isPresent()) {
            r = new Recs(Source.PERSONAL, catalog.nearest(taste.get().vector(), new ArrayList<>(seen), limit));
        } else {
            List<Long> ids = store.trending(clock.instant(), limit + seen.size()).stream()
                    .filter(id -> !seen.contains(id)).limit(limit).toList();
            r = new Recs(Source.TRENDING, catalog.titles(ids));
        }
        metrics.counter("recs.requests", "source", r.source().name().toLowerCase()).increment();
        if (limit == DEFAULT_LIMIT) {
            try {
                store.cacheForYou(userId, json.writeValueAsString(r), cacheFor);
            } catch (JsonProcessingException | RuntimeException e) {
                // a cache that can't be written is just a slower next request
            }
        }
        return r;
    }

    /** Only the home page's size is cached; other sizes are rare. */
    static final int DEFAULT_LIMIT = 10;

    private Optional<Recs> cached(long userId, int limit) {
        if (limit != DEFAULT_LIMIT) {
            return Optional.empty();
        }
        try {
            Optional<String> s = store.cachedForYou(userId);
            return s.isEmpty() ? Optional.empty() : Optional.of(json.readValue(s.get(), Recs.class));
        } catch (JsonProcessingException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
