package com.streamhub.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

/** docs/lld-catalog-cache.html §3, against real Postgres and Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfra.class)
class CatalogCacheTest {

    @Autowired CatalogService catalog;
    @Autowired TitleRepository repository;
    @Autowired TitleCache cache;
    @Autowired StringRedisTemplate redis;
    @Autowired TestRestTemplate http;

    @BeforeEach
    void emptyCache() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void secondReadComesFromTheCache() {
        long before = repository.reads();
        String name = catalog.title(1).orElseThrow().name();
        assertEquals(name, catalog.title(1).orElseThrow().name());
        assertEquals(1, repository.reads() - before);
    }

    @Test
    void staleKeyUnderStampedeReadsTheDatabaseOnce() throws Exception {
        catalog.title(1);
        cache.makeStale(1);
        long before = repository.reads();

        List<Optional<Title>> results = concurrently(200, () -> catalog.title(1));

        assertEquals(1, repository.reads() - before, "200 requests on a stale key: one refresh");
        results.forEach(r -> assertTrue(r.isPresent()));
    }

    @Test
    void coldKeyUnderStampedeBarelyTouchesTheDatabase() throws Exception {
        long before = repository.reads();

        List<Optional<Title>> results = concurrently(200, () -> catalog.title(2));

        long reads = repository.reads() - before;
        assertTrue(reads <= 2, "200 requests on a cold key made " + reads + " database reads");
        results.forEach(r -> assertTrue(r.isPresent()));
    }

    @Test
    void unknownIdsAreRememberedToo() throws Exception {
        long before = repository.reads();

        List<Optional<Title>> results = concurrently(1_000, () -> catalog.title(999_999));

        long reads = repository.reads() - before;
        assertTrue(reads <= 2, "1,000 requests for a missing id made " + reads + " database reads");
        results.forEach(r -> assertTrue(r.isEmpty()));
    }

    @Test
    void updateIsVisibleOnTheNextRead() {
        Title t = catalog.title(3).orElseThrow();
        catalog.update(new Title(3, "Renamed", t.genres(), t.releaseYear(), t.durationMinutes(), t.description()));
        assertEquals("Renamed", catalog.title(3).orElseThrow().name());
    }

    @Test
    void httpApi() {
        assertEquals(HttpStatus.OK, http.getForEntity("/titles/1", Title.class).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity("/titles/424242", String.class).getStatusCode());
        Title[] some = http.getForObject("/titles/batch?ids=3,1,999999", Title[].class);
        assertEquals(List.of(3L, 1L), java.util.Arrays.stream(some).map(Title::id).toList(), "in order, unknown left out");
        Title[] drama = http.getForObject("/titles?genre=drama&limit=5", Title[].class);
        assertTrue(drama.length > 0 && drama.length <= 5);
        for (Title d : drama) {
            assertTrue(d.genres().contains("drama"));
        }
    }

    /** Runs {@code task} on n threads released at the same instant. */
    private static <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }
}
