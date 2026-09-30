package com.streamhub.recs;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.streamhub.events.PlaybackEvent;
import com.streamhub.events.PlaybackEvent.Type;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Real Kafka and Redis; a fake catalog whose embeddings are known. */
@SpringBootTest
@Testcontainers
class RecsTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void catalog(DynamicPropertyRegistry r) {
        r.add("recs.catalog.url", FakeCatalog::url);
    }

    static final AtomicLong users = new AtomicLong(1_000);
    static KafkaProducer<String, Object> producer;

    @Autowired RecsService recs;
    @Autowired TasteStore store;
    @Autowired Catalog catalog;
    @Autowired MeterRegistry metrics;
    @Autowired StringRedisTemplate redisOps;

    @BeforeEach
    void setUp() {
        FakeCatalog.down = false;
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                    JsonSerializer.ADD_TYPE_INFO_HEADERS, false));
        }
    }

    @AfterAll
    static void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    void aNewUserGetsWhatIsTrending() {
        long someoneElse = users.incrementAndGet();
        double applied = count("applied");
        watch(someoneElse, 7, 40);                     // more of title 7 than any other test watches of anything
        watch(someoneElse, 3, 2);
        await().atMost(Duration.ofSeconds(20)).until(() -> count("applied") >= applied + 42);

        long newcomer = users.incrementAndGet();
        RecsService.Recs r = recs.forYou(newcomer, 3);
        assertEquals(RecsService.Source.TRENDING, r.source());
        assertEquals(7, r.titles().get(0).id(), "most watched today comes first");
    }

    @Test
    void watchingSpaceTitlesRecommendsOtherSpaceTitles() {
        long u = users.incrementAndGet();
        watch(u, 1, 5);
        watch(u, 2, 5);
        awaitTaste(u, 10);

        RecsService.Recs r = recs.forYou(u, 2);
        assertEquals(RecsService.Source.PERSONAL, r.source());
        assertEquals(List.of(3L, 4L), ids(r), "the other two space titles; 1 and 2 were already watched");
    }

    @Test
    void theRowFollowsWhatTheyWatchTonight() {
        long u = users.incrementAndGet();
        watch(u, 9, 5);                                // some crime earlier
        awaitTaste(u, 5);
        assertEquals("crime", FakeCatalog.cluster(ids(recs.forYou(u, 1)).get(0)));

        watch(u, 5, 20);                               // then a long romance evening
        awaitTaste(u, 25);
        List<Long> now = ids(recs.forYou(u, 3));
        assertTrue(now.stream().allMatch(id -> FakeCatalog.cluster(id).equals("romance")),
                "top 3 are romance now: " + now);
    }

    @Test
    void aRedeliveredEventChangesNothing() {
        long once = users.incrementAndGet();
        long twice = users.incrementAndGet();
        PlaybackEvent a = heartbeat(once, 2, 40);
        PlaybackEvent b = new PlaybackEvent(UUID.randomUUID().toString(), Type.HEARTBEAT, twice, 2,
                a.sessionId(), a.positionSeconds(), a.durationSeconds(), 40, a.occurredAt());
        double handledBefore = count("applied") + count("duplicate");
        send(a);
        send(b);
        send(b);                                       // Kafka delivered it again
        await().atMost(Duration.ofSeconds(20)).until(() -> count("applied") + count("duplicate") >= handledBefore + 3);

        assertArrayEquals(store.taste(once).orElseThrow().vector(), store.taste(twice).orElseThrow().vector(),
                "applied once, however many times it arrived");
    }

    @Test
    void anEventForATitleWithoutAnEmbeddingIsSkippedAndTheNextOneStillApplies() {
        long u = users.incrementAndGet();
        double before = count("no_embedding");
        send(heartbeat(u, 99, 60));                    // not indexed yet
        watch(u, 4, 1);
        awaitTaste(u, 1);
        assertEquals(before + 1, count("no_embedding"));
        assertFalse(store.seen(u).contains(99L));
    }

    @Test
    void catalogDownForAWhileDelaysEventsButLosesNone() throws Exception {
        long u = users.incrementAndGet();
        catalog.forgetEmbeddings();                    // must ask the catalog
        FakeCatalog.down = true;
        watch(u, 8, 3);
        Thread.sleep(700);                             // a retry or two fail
        FakeCatalog.down = false;
        awaitTaste(u, 3);
        assertTrue(store.seen(u).contains(8L));
    }

    // --- helpers ---

    private void watch(long user, long title, int heartbeats) {
        for (int i = 0; i < heartbeats; i++) {
            send(heartbeat(user, title, 60));
        }
    }

    private static PlaybackEvent heartbeat(long user, long title, int watched) {
        return new PlaybackEvent(UUID.randomUUID().toString(), Type.HEARTBEAT, user, title, "s-" + user,
                120, 3600, watched, Instant.now());
    }

    private void send(PlaybackEvent e) {
        producer.send(new ProducerRecord<>(PlaybackEvent.TOPIC, Long.toString(e.userId()), e));
        producer.flush();
    }

    /** Waits until this user's taste has absorbed at least {@code minutes} full heartbeats. */
    private void awaitTaste(long user, int minutes) {
        await().atMost(Duration.ofSeconds(20)).until(() -> store.taste(user)
                .map(t -> norm1(t.vector()) >= minutes * 0.999).orElse(false));
    }

    /** Sum of the cluster axes: each full heartbeat adds exactly 1 (decay is negligible within a test). */
    private static double norm1(float[] v) {
        return v[0] + v[1] + v[2];
    }

    private double count(String result) {
        return metrics.counter("recs.events", "result", result).count();
    }

    private static List<Long> ids(RecsService.Recs r) {
        return r.titles().stream().map(Catalog.Title::id).toList();
    }
}
