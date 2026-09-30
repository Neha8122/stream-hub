package com.streamhub.home;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the home page from its rows, all fetched at once, under one
 * deadline. A row that fails or is late is replaced by its fallback, and
 * the page lists which dependencies were degraded. The page itself never
 * fails.
 */
public final class HomeService {

    private static final Logger log = LoggerFactory.getLogger(HomeService.class);

    /** Where a row's content came from. */
    public enum Source { LIVE, STALE, UNAVAILABLE }

    public record Item(long titleId, String name, Integer positionSeconds) { }
    public record Row(String id, String heading, List<Item> items, Source source) { }
    public record Home(List<Row> rows, Set<String> degraded, long tookMillis) { }

    /** A row's result: its content, or which dependency let it down. */
    private record Outcome(Row row, String failedDependency) { }

    private final Dependency history;
    private final Dependency catalog;
    private final Dependency recs;
    private final List<String> genres;
    private final Duration deadline;
    /**
     * Rows run on their own virtual threads. The wrapper copies the caller's
     * context (the current trace span) onto each task: without it every row's
     * calls would start a new, orphaned trace, and a slow page couldn't be
     * traced end to end.
     */
    private final ExecutorService workers = ContextExecutorService.wrap(
            Executors.newVirtualThreadPerTaskExecutor(),
            ContextSnapshotFactory.builder().build()::captureAll);
    private final MeterRegistry metrics;
    /** Last good copy of each shared (non-personal) row, served when catalog is down. */
    private final Map<String, Row> lastGood = new ConcurrentHashMap<>();

    public HomeService(Dependency history, Dependency catalog, Dependency recs, List<String> genres,
                       Duration deadline, MeterRegistry metrics) {
        this.metrics = metrics;
        // Both series exist from the start: a ratio over a missing
        // complete="false" series is "no data", not 0, while all is well.
        metrics.counter("home.pages", "complete", "true");
        metrics.counter("home.pages", "complete", "false");
        this.history = history;
        this.catalog = catalog;
        this.recs = recs;
        this.genres = genres;
        this.deadline = deadline;
    }

    public Home home(long userId) {
        long start = System.nanoTime();
        List<String> ids = new ArrayList<>();
        List<CompletableFuture<Outcome>> rows = new ArrayList<>();
        ids.add("continue-watching");
        rows.add(fetch(() -> continueWatching(userId)));
        ids.add("for-you");
        rows.add(fetch(() -> forYou(userId)));
        for (String genre : genres) {
            ids.add("genre:" + genre);
            rows.add(fetch(() -> genreRow(genre)));
        }

        List<Row> page = new ArrayList<>();
        Set<String> degraded = new TreeSet<>();
        for (int i = 0; i < rows.size(); i++) {
            CompletableFuture<Outcome> f = rows.get(i);
            long left = deadline.toNanos() - (System.nanoTime() - start);
            Outcome o = f.completeOnTimeout(null, Math.max(0, left), TimeUnit.NANOSECONDS).join();
            if (o == null) {
                // Missed the deadline: whatever it was waiting for is too slow today.
                o = lateFallback(ids.get(i));
            }
            if (o.failedDependency() != null) {
                degraded.add(o.failedDependency());
            }
            if (o.row() != null) {
                page.add(o.row());
            }
        }
        // For the "complete" SLO: a 200 that served stale rows is not a good page.
        metrics.counter("home.pages", "complete", Boolean.toString(degraded.isEmpty())).increment();
        if (!degraded.isEmpty()) {
            // Carries [traceId-spanId] (Boot's log pattern), so the line leads to the trace.
            log.warn("home page degraded: {}", degraded);
        }
        for (String d : degraded) {
            metrics.counter("home.degraded", "dependency", d).increment();
        }
        return new Home(page, degraded, (System.nanoTime() - start) / 1_000_000);
    }

    private CompletableFuture<Outcome> fetch(java.util.function.Supplier<Outcome> work) {
        return CompletableFuture.supplyAsync(work, workers);
    }

    private Outcome continueWatching(long userId) {
        List<Clients.Progress> progress;
        try {
            progress = Clients.continueWatching(history, userId);
        } catch (RuntimeException e) {
            return new Outcome(new Row("continue-watching", "Continue watching", List.of(), Source.UNAVAILABLE),
                    history.name());
        }
        // Names come from the catalog in one call; if that fails the row still
        // shows, with ids only, and catalog is marked degraded.
        Map<Long, String> names;
        String failed = null;
        try {
            names = Clients.titles(catalog, progress.stream().map(Clients.Progress::titleId).toList()).stream()
                    .collect(java.util.stream.Collectors.toMap(Clients.Title::id, Clients.Title::name));
        } catch (RuntimeException e) {
            names = Map.of();
            failed = catalog.name();
        }
        List<Item> items = new ArrayList<>();
        for (Clients.Progress p : progress) {
            items.add(new Item(p.titleId(), names.get(p.titleId()), p.positionSeconds()));
        }
        return new Outcome(new Row("continue-watching", "Continue watching", items,
                failed == null ? Source.LIVE : Source.STALE), failed);
    }

    /**
     * Personal, so there's no shared last-good copy to fall back to: if recs
     * is down the row is left out and the rest of the page is unaffected.
     */
    private Outcome forYou(long userId) {
        try {
            Clients.Recs r = Clients.forYou(recs, userId, 10);
            List<Item> items = r.titles().stream().map(t -> new Item(t.id(), t.name(), null)).toList();
            String heading = "PERSONAL".equals(r.source()) ? "Picked for you" : "Trending today";
            return new Outcome(new Row("for-you", heading, items, Source.LIVE), null);
        } catch (RuntimeException e) {
            return new Outcome(null, recs.name());
        }
    }

    private Outcome genreRow(String genre) {
        String id = "genre:" + genre;
        try {
            List<Item> items = Clients.byGenre(catalog, genre, 10).stream()
                    .map(t -> new Item(t.id(), t.name(), null)).toList();
            Row row = new Row(id, "Popular in " + genre, items, Source.LIVE);
            lastGood.put(id, row);
            return new Outcome(row, null);
        } catch (RuntimeException e) {
            return new Outcome(stale(id).orElse(null), catalog.name());
        }
    }

    private Outcome lateFallback(String id) {
        if (id.equals("continue-watching")) {
            return new Outcome(new Row(id, "Continue watching", List.of(), Source.UNAVAILABLE), history.name());
        }
        if (id.equals("for-you")) {
            return new Outcome(null, recs.name());
        }
        return new Outcome(stale(id).orElse(null), catalog.name());
    }

    private Optional<Row> stale(String id) {
        Row r = lastGood.get(id);
        return r == null ? Optional.empty() : Optional.of(new Row(r.id(), r.heading(), r.items(), Source.STALE));
    }

    /** Test hook: forget the last good copies. */
    void forgetLastGood() {
        lastGood.clear();
    }
}
