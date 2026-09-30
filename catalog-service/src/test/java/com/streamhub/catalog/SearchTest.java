package com.streamhub.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Hybrid search against real Postgres + pgvector and the real model, over
 * the 60 seeded titles.
 */
@SpringBootTest(properties = {"catalog.search.embed-timeout=150ms", "catalog.search.max-concurrent-embeddings=2",
        "catalog.search.index-every=1h"})           // tests run the indexer themselves
@Import({TestInfra.class, SearchTest.Model.class})
class SearchTest {

    /** The real model, with a dial to make it slow. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Model {
        static volatile int delayMs;
        static final AtomicInteger inFlight = new AtomicInteger();
        static final AtomicInteger maxInFlight = new AtomicInteger();

        @Bean
        @Primary
        Embedder slowable() {
            Embedder real = new Embedder.MiniLm();
            return text -> {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    return real.embed(text);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } finally {
                    inFlight.decrementAndGet();
                }
            };
        }
    }

    @Autowired SearchService search;
    @Autowired CatalogService catalog;
    @Autowired TitleRepository titles;
    @Autowired EmbeddingIndexer indexer;

    @BeforeEach
    void indexed() {
        Model.delayMs = 0;
        Model.maxInFlight.set(0);
        indexer.indexMissing();
    }

    @AfterEach
    void fast() {
        Model.delayMs = 0;
    }

    @Test
    void meaningFindsWhatKeywordsMiss() {
        // "An android who has lost its memory wanders a ruined planet looking
        // for the humans who built it": not one word in common with the query.
        SearchService.Result r = search.search("lonely robot searching for its makers", 5);
        assertEquals(SearchService.Mode.HYBRID, r.mode());
        SearchService.Hit hit = find(r, "The Lost Machine");
        assertNull(hit.keywordRank(), "no keyword match...");
        assertEquals(1, hit.semanticRank(), "...but the closest in meaning");
    }

    @Test
    void aTitleBothHalvesAgreeOnComesFirst() {
        SearchService.Result r = search.search("bank robbery", 5);
        SearchService.Hit top = r.hits().get(0);
        assertTrue(top.title().genres().contains("crime"));
        assertTrue(top.keywordRank() != null && top.semanticRank() != null, "found by both: " + top);
        assertTrue(r.hits().stream().allMatch(h -> h.title().genres().contains("crime")),
                "all five are crime: " + names(r));
    }

    @Test
    void aSlowModelFallsBackToKeywordResultsInTime() {
        Model.delayMs = 1_000;
        long start = System.nanoTime();
        SearchService.Result r = search.search("bank robbery", 5);
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(SearchService.Mode.KEYWORD, r.mode());
        assertFalse(r.hits().isEmpty(), "keyword results still served");
        assertTrue(r.hits().stream().allMatch(h -> h.semanticRank() == null));
        assertTrue(tookMs < 400, "took " + tookMs + " ms; the embedding limit is 150");
    }

    @Test
    void aStuckModelTiesUpAtMostTwoThreads() throws Exception {
        Model.delayMs = 1_000;
        List<SearchService.Result> results = concurrently(10, () -> search.search("ghost", 5));
        assertTrue(Model.maxInFlight.get() <= 2, Model.maxInFlight.get() + " embeddings at once; the cap is 2");
        assertTrue(results.stream().allMatch(r -> r.mode() == SearchService.Mode.KEYWORD));
        Thread.sleep(1_100);                           // let the stuck ones finish before other tests
    }

    @Test
    void aNewTitleIsKeywordSearchableAtOnceAndMeaningSearchableOnceIndexed() {
        Title t = catalog.create(new Title(0, "The Velvet Depths", List.of("comedy"), 2026, 95,
                "A disgraced chef opens a restaurant aboard a submarine."));
        SearchService.Hit byKeyword = find(search.search("submarine", 3), "The Velvet Depths");
        assertEquals(1, byKeyword.keywordRank(), "keyword: at once");
        assertNull(byKeyword.semanticRank());
        assertTrue(titles.embedding(t.id()).isEmpty(), "not embedded yet: the write didn't wait for the model");

        indexer.indexMissing();
        SearchService.Hit hit = find(search.search("cooking under the sea", 5), "The Velvet Depths");
        assertNull(hit.keywordRank());
        assertTrue(hit.semanticRank() <= 2, "found by meaning: " + hit);
    }

    @Test
    void anEditedTitleIsReEmbedded() {
        Title t = catalog.create(new Title(0, "The Amber Hours", List.of("drama"), 2026, 100,
                "A lighthouse keeper writes letters she never sends."));
        indexer.indexMissing();
        catalog.update(new Title(t.id(), t.name(), List.of("sci-fi"), 2026, 100,
                "Colonists on a frozen moon fight over the last working reactor."));
        assertTrue(titles.embedding(t.id()).isEmpty(), "the old embedding is cleared with the edit");
        indexer.indexMissing();
        assertTrue(find(search.search("settlers on an icy moon", 5), "The Amber Hours").semanticRank() <= 3);
    }

    @Test
    void reciprocalRankFusion() {
        // 7 is 1st by keyword and 3rd by meaning; 5 is 1st by meaning only;
        // 8 and 6 are both 2nd in one list: a tie, broken by id.
        List<SearchService.Fused> f = SearchService.fuse(List.of(7L, 8L), List.of(5L, 6L, 7L));
        assertEquals(List.of(7L, 5L, 6L, 8L), f.stream().map(SearchService.Fused::id).toList());
        assertEquals(1.0 / 61 + 1.0 / 63, f.get(0).score(), 1e-12);
        assertEquals(List.of(), SearchService.fuse(List.of(), List.of()));
    }

    // --- helpers ---

    private static SearchService.Hit find(SearchService.Result r, String name) {
        return r.hits().stream().filter(h -> h.title().name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in " + names(r)));
    }

    private static List<String> names(SearchService.Result r) {
        return r.hits().stream().map(h -> h.title().name()).toList();
    }

    private static <T> List<T> concurrently(int n, java.util.concurrent.Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
