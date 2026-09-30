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

/**
 * Builds the home page from its rows, all fetched at once, under one
 * deadline. A row that fails or is late is replaced by its fallback, and
 * the page lists which dependencies were degraded. The page itself never
 * fails.
 */
public final class HomeService {

    /** Where a row's content came from. */
    public enum Source { LIVE, STALE, UNAVAILABLE }

    public record Item(long titleId, String name, Integer positionSeconds) { }
    public record Row(String id, String heading, List<Item> items, Source source) { }
    public record Home(List<Row> rows, Set<String> degraded, long tookMillis) { }

    /** A row's result: its content, or which dependency let it down. */
    private record Outcome(Row row, String failedDependency) { }

    private final Dependency history;
    private final Dependency catalog;
    private final List<String> genres;
    private final Duration deadline;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** Last good copy of each shared (non-personal) row, served when catalog is down. */
    private final Map<String, Row> lastGood = new ConcurrentHashMap<>();

    public HomeService(Dependency history, Dependency catalog, List<String> genres, Duration deadline) {
        this.history = history;
        this.catalog = catalog;
        this.genres = genres;
        this.deadline = deadline;
    }

    public Home home(long userId) {
        long start = System.nanoTime();
        List<CompletableFuture<Outcome>> rows = new ArrayList<>();
        rows.add(fetch(() -> continueWatching(userId)));
        for (String genre : genres) {
            rows.add(fetch(() -> genreRow(genre)));
        }

        List<Row> page = new ArrayList<>();
        Set<String> degraded = new TreeSet<>();
        for (CompletableFuture<Outcome> f : rows) {
            long left = deadline.toNanos() - (System.nanoTime() - start);
            Outcome o = f.completeOnTimeout(null, Math.max(0, left), TimeUnit.NANOSECONDS).join();
            if (o == null) {
                // Missed the deadline: whatever it was waiting for is too slow today.
                o = lateFallback(rowIdOf(rows.indexOf(f)));
            }
            if (o.failedDependency() != null) {
                degraded.add(o.failedDependency());
            }
            if (o.row() != null) {
                page.add(o.row());
            }
        }
        return new Home(page, degraded, (System.nanoTime() - start) / 1_000_000);
    }

    private CompletableFuture<Outcome> fetch(java.util.function.Supplier<Outcome> work) {
        return CompletableFuture.supplyAsync(work, workers);
    }

    private String rowIdOf(int index) {
        return index == 0 ? "continue-watching" : "genre:" + genres.get(index - 1);
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
