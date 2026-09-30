package com.streamhub.catalog;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hybrid search: keyword matches (Postgres full-text) and meaning matches
 * (pgvector nearest neighbours), merged by reciprocal rank fusion.
 *
 * Keyword search is exact but literal: "spaceship" misses a title that says
 * "freighter drifting among the stars". Meaning search finds that, but can
 * rank a vaguely related title above one that names the exact thing.
 * Together they cover each other.
 *
 * The meaning half depends on the model, so it's guarded like any other
 * dependency: a time limit and a cap on concurrent embeddings. Past either,
 * search answers with keyword results alone and says so.
 */
@Service
public class SearchService {

    public enum Mode { HYBRID, KEYWORD }

    public record Hit(Title title, Integer keywordRank, Integer semanticRank, double score) { }
    public record Result(String query, Mode mode, List<Hit> hits) { }

    /** RRF's constant: dampens the gap between rank 1 and rank 2. 60 is the paper's value. */
    static final int RRF_K = 60;
    private static final int CANDIDATES = 20;

    private final TitleRepository titles;
    private final CatalogService catalog;
    private final Embedder embedder;
    private final MeterRegistry metrics;
    private final Duration embedTimeout;
    private final Semaphore embedSlots;
    private final ExecutorService embedThreads = Executors.newVirtualThreadPerTaskExecutor();

    public SearchService(TitleRepository titles, CatalogService catalog, Embedder embedder, MeterRegistry metrics,
                         @Value("${catalog.search.embed-timeout:150ms}") Duration embedTimeout,
                         @Value("${catalog.search.max-concurrent-embeddings:8}") int maxEmbeddings) {
        this.titles = titles;
        this.catalog = catalog;
        this.embedder = embedder;
        this.metrics = metrics;
        this.embedTimeout = embedTimeout;
        this.embedSlots = new Semaphore(maxEmbeddings);
        for (Mode m : Mode.values()) {
            metrics.counter("catalog.search", "mode", m.name().toLowerCase());
        }
    }

    public Result search(String query, int limit) {
        limit = Math.max(1, Math.min(limit, 50));
        // Start the embedding first; the keyword query runs meanwhile.
        CompletableFuture<float[]> vector = embed(query);
        List<Long> byKeyword = titles.keywordSearch(query, CANDIDATES);
        List<Long> byMeaning = null;
        float[] v = await(vector);
        if (v != null) {
            byMeaning = titles.nearest(v, CANDIDATES, List.of());
        }
        Mode mode = byMeaning == null ? Mode.KEYWORD : Mode.HYBRID;
        metrics.counter("catalog.search", "mode", mode.name().toLowerCase()).increment();

        List<Hit> hits = new ArrayList<>();
        List<Fused> ranked = fuse(byKeyword, byMeaning == null ? List.of() : byMeaning);
        List<Fused> top = ranked.subList(0, Math.min(limit, ranked.size()));
        Map<Long, Title> found = new HashMap<>();
        catalog.titles(top.stream().map(Fused::id).toList()).forEach(t -> found.put(t.id(), t));
        for (Fused f : top) {
            Title t = found.get(f.id());
            if (t != null) {
                hits.add(new Hit(t, f.keywordRank(), f.semanticRank(), f.score()));
            }
        }
        return new Result(query, mode, hits);
    }

    record Fused(long id, Integer keywordRank, Integer semanticRank, double score) { }

    /**
     * Reciprocal rank fusion: score = sum over lists of 1 / (k + rank).
     * Uses only ranks, never raw scores, so a ts_rank and a cosine distance
     * never have to be put on the same scale. A title near the top of both
     * lists beats one at the very top of just one.
     */
    static List<Fused> fuse(List<Long> keyword, List<Long> semantic) {
        Map<Long, Integer> kw = ranks(keyword);
        Map<Long, Integer> sem = ranks(semantic);
        Map<Long, Double> score = new HashMap<>();
        kw.forEach((id, r) -> score.merge(id, 1.0 / (RRF_K + r), Double::sum));
        sem.forEach((id, r) -> score.merge(id, 1.0 / (RRF_K + r), Double::sum));
        return score.entrySet().stream()
                .map(e -> new Fused(e.getKey(), kw.get(e.getKey()), sem.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingDouble(Fused::score).reversed().thenComparingLong(Fused::id))
                .toList();
    }

    private static Map<Long, Integer> ranks(List<Long> ids) {
        Map<Long, Integer> r = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            r.putIfAbsent(ids.get(i), i + 1);
        }
        return r;
    }

    /**
     * The model call, capped. The slot is held until the embedding really
     * finishes, not until the caller stops waiting: a stuck model can tie up
     * at most max-concurrent-embeddings threads, however many searches come.
     */
    private CompletableFuture<float[]> embed(String query) {
        if (!embedSlots.tryAcquire()) {
            metrics.counter("catalog.search.embed_skipped", "reason", "busy").increment();
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return embedder.embed(query);
            } finally {
                embedSlots.release();
            }
        }, embedThreads);
    }

    private float[] await(CompletableFuture<float[]> vector) {
        try {
            return vector.get(embedTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            metrics.counter("catalog.search.embed_skipped", "reason", "timeout").increment();
            return null;
        } catch (Exception e) {
            metrics.counter("catalog.search.embed_skipped", "reason", "error").increment();
            return null;
        }
    }
}
