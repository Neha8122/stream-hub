package com.streamhub.recs;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    public RecsService(TasteStore store, Catalog catalog, MeterRegistry metrics, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Recs forYou(long userId, int limit) {
        limit = Math.max(1, Math.min(limit, 50));
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
        return r;
    }
}
