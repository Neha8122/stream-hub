package com.streamhub.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * docs/lld-resilience.html §4. One fake server plays both history and
 * catalog; each test makes it slow, broken or flaky on purpose.
 */
// Catalog gets room for a 60-page burst (60 x 4 calls) so the bulkhead test
// isolates one question: does a slow history hurt catalog rows? Under a real
// burst the catalog bulkhead would shed load too, by design.
@SpringBootTest(properties = {"home.breaker.open-for=1s", "home.catalog.max-concurrent=300"})
class ChaosTest {

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("home.history.url", FakeServices::url);
        r.add("home.catalog.url", FakeServices::url);
        r.add("home.recs.url", FakeServices::url);
    }

    @Autowired HomeService home;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void healthy() {
        FakeServices.reset();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        home.forgetLastGood();
    }

    @Test
    void allHealthy() {
        HomeService.Home h = home.home(1);
        assertEquals(List.of(), List.copyOf(h.degraded()));
        assertEquals(5, h.rows().size());
        assertTrue(h.rows().stream().allMatch(r -> r.source() == HomeService.Source.LIVE));
        assertEquals("The Title 5", h.rows().get(0).items().get(0).name(), "continue watching has names");
        assertEquals("Picked for you", row(h, "for-you").heading());
    }

    @Test
    void recsDownLeavesOutJustItsRow() {
        FakeServices.recs.status = 500;
        HomeService.Home h = home.home(1);
        assertEquals(List.of("recs"), List.copyOf(h.degraded()));
        assertTrue(h.rows().stream().noneMatch(r -> r.id().equals("for-you")), "no stale copy of a personal row");
        assertEquals(4, h.rows().size(), "every other row is served");
    }

    @Test
    void slowRecsDoesntSlowThePage() {
        FakeServices.recs.delayMs = 2_000;
        HomeService.Home h = home.home(1);
        assertTrue(h.tookMillis() < 450, "page took " + h.tookMillis() + " ms");
        assertEquals(List.of("recs"), List.copyOf(h.degraded()));
    }

    @Test
    void slowHistoryDoesntSlowThePage() {
        FakeServices.history.delayMs = 2_000;
        HomeService.Home h = home.home(1);
        assertTrue(h.tookMillis() < 450, "page took " + h.tookMillis() + " ms; the deadline is 300");
        assertEquals(List.of("history"), List.copyOf(h.degraded()));
        assertEquals(HomeService.Source.UNAVAILABLE, row(h, "continue-watching").source());
        assertEquals(HomeService.Source.LIVE, row(h, "genre:drama").source(), "healthy rows unaffected");
    }

    @Test
    void breakerStopsCallingABrokenServiceAndLetsItBackWhenHealed() throws Exception {
        FakeServices.history.status = 500;
        for (int i = 0; i < 15; i++) {
            home.home(1);
        }
        CircuitBreaker b = breakers.circuitBreaker("history");
        assertEquals(CircuitBreaker.State.OPEN, b.getState());
        int whenOpen = FakeServices.history.requests.get();
        for (int i = 0; i < 20; i++) {
            HomeService.Home h = home.home(1);
            assertTrue(h.degraded().contains("history"));
        }
        assertEquals(whenOpen, FakeServices.history.requests.get(), "an open breaker sends nothing to history");

        FakeServices.history.status = 200;                           // history recovers
        Thread.sleep(1_200);                            // breaker's open-for is 1 s in this test
        for (int i = 0; i < 5; i++) {
            home.home(1);
        }
        assertEquals(CircuitBreaker.State.CLOSED, b.getState(), "trial calls succeeded");
        assertEquals(HomeService.Source.LIVE, row(home.home(1), "continue-watching").source());
    }

    @Test
    void bulkheadCapsCallsToASlowService() throws Exception {
        FakeServices.history.delayMs = 1_000;                        // every call ties up a connection until the timeout
        List<HomeService.Home> pages = concurrently(60, () -> home.home(1));
        assertTrue(FakeServices.history.maxInFlight.get() <= 20,
                "history saw " + FakeServices.history.maxInFlight.get() + " calls at once; the bulkhead allows 20");
        long slowest = pages.stream().mapToLong(HomeService.Home::tookMillis).max().orElseThrow();
        assertTrue(slowest < 700, "slowest of 60 pages took " + slowest + " ms");
        assertTrue(pages.stream().allMatch(p -> row(p, "genre:drama").source() == HomeService.Source.LIVE),
                "catalog rows kept working: its bulkhead is separate");
    }

    @Test
    void oneFailedCallIsRetriedOnce() {
        FakeServices.catalog.failNext.set(1);
        HomeService.Home h = home.home(1);
        assertEquals(List.of(), List.copyOf(h.degraded()), "the retry covered the blip");
        assertEquals(1 + 3 + 1, FakeServices.catalog.requests.get(), "3 rows + 1 batch lookup + 1 retry");
    }

    @Test
    void catalogDownServesLastGoodRows() {
        home.home(1);                                   // warm: rows remembered
        FakeServices.catalog.status = 500;
        HomeService.Home h = home.home(1);
        assertTrue(h.degraded().contains("catalog"));
        assertEquals(HomeService.Source.STALE, row(h, "genre:drama").source());
        assertEquals(10, row(h, "genre:drama").items().size(), "yesterday's row beats no row");

        home.forgetLastGood();
        HomeService.Home cold = home.home(1);
        assertTrue(cold.rows().stream().noneMatch(r -> r.id().startsWith("genre:")),
                "with no good copy the row is left out, the page still loads");
    }

    // --- helpers ---

    private static HomeService.Row row(HomeService.Home h, String id) {
        return h.rows().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no row " + id + " in " + h.rows()));
    }

    private static <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
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
