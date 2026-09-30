package com.streamhub.history;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.streamhub.events.PlaybackEvent;
import com.streamhub.events.PlaybackEvent.Type;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** docs/lld-playback-events.html §4, against real Kafka and Postgres. */
@SpringBootTest
@Testcontainers
class HistoryTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final AtomicLong users = new AtomicLong(1_000);

    @Autowired HistoryStore store;
    @Autowired PlaybackEventListener listener;
    @Autowired MeterRegistry metrics;

    @AfterEach
    void noHook() {
        listener.setAfterCommit(e -> { });
    }

    @Test
    void theSameEventTwiceCountsOnce() {
        long user = users.incrementAndGet();
        PlaybackEvent e = event(user, 1, 30, 30, Instant.now());
        send(e);
        send(e);                                            // redelivered / published twice
        send(event(user, 1, 60, 30, Instant.now().plusSeconds(30)));   // a later, different event
        await().atMost(Duration.ofSeconds(15)).until(() -> store.watchedSeconds(user) == 60);
        sleep(500);
        assertEquals(60, store.watchedSeconds(user), "30 + 30, the duplicate adds nothing");
    }

    @Test
    void aLateOldEventCantRewindProgress() {
        long user = users.incrementAndGet();
        Instant t = Instant.now();
        send(event(user, 7, 600, 0, t.plusSeconds(60)));    // newer
        send(event(user, 7, 100, 0, t));                    // older, arrives later
        await().atMost(Duration.ofSeconds(15)).until(() -> !store.continueWatching(user, 0.95, 10).isEmpty());
        sleep(500);
        assertEquals(600, store.continueWatching(user, 0.95, 10).get(0).positionSeconds());
    }

    @Test
    void crashAfterTheDatabaseCommitIsHarmless() {
        long user = users.incrementAndGet();
        PlaybackEvent e = event(user, 2, 30, 30, Instant.now());
        AtomicBoolean crashed = new AtomicBoolean();
        // The transaction has committed; now "crash" before Kafka's offset
        // commit. The error handler retries, so the event comes again.
        listener.setAfterCommit(ev -> {
            if (ev.eventId().equals(e.eventId()) && crashed.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated crash between DB commit and offset commit");
            }
        });
        double duplicatesBefore = duplicates();
        send(e);
        await().atMost(Duration.ofSeconds(15)).until(() -> crashed.get() && duplicates() > duplicatesBefore);
        assertEquals(30, store.watchedSeconds(user), "delivered twice, counted once");
    }

    @Test
    void unreadableMessageGoesToTheDeadLetterTopicAndTheRestFlows() {
        long user = users.incrementAndGet();
        String poison = "not json at all " + UUID.randomUUID();
        sendRaw(Long.toString(user), poison.getBytes(StandardCharsets.UTF_8));
        send(event(user, 3, 40, 40, Instant.now()));        // right behind it, same partition
        await().atMost(Duration.ofSeconds(20)).until(() -> store.watchedSeconds(user) == 40);
        assertTrue(deadLetters().contains(poison), "the poison message is parked on the DLT");
    }

    @Test
    void eventThatKeepsFailingIsParkedAfterRetries() {
        long user = users.incrementAndGet();
        PlaybackEvent bad = new PlaybackEvent(UUID.randomUUID().toString(), Type.HEARTBEAT, user, 0, "s", 1, 10, 1,
                Instant.now());                             // titleId 0: rejected every time
        send(bad);
        send(event(user, 4, 25, 25, Instant.now()));
        await().atMost(Duration.ofSeconds(20)).until(() -> store.watchedSeconds(user) == 25);
        await().atMost(Duration.ofSeconds(10)).until(() -> deadLetters().contains(bad.eventId()));
    }

    @Test
    void continueWatchingIsNewestFirstWithoutFinishedTitles() {
        long user = users.incrementAndGet();
        Instant t = Instant.now();
        send(event(user, 21, 300, 0, t));
        send(event(user, 22, 3_500, 0, t.plusSeconds(1)));  // 3500 of 3600: finished
        send(event(user, 23, 900, 0, t.plusSeconds(2)));
        await().atMost(Duration.ofSeconds(15)).until(() -> store.continueWatching(user, 0.95, 10).size() == 2);
        assertEquals(List.of(23L, 21L), store.continueWatching(user, 0.95, 10).stream()
                .map(HistoryStore.Progress::titleId).toList());
    }

    // --- helpers ---

    private static PlaybackEvent event(long user, long title, int position, int watched, Instant at) {
        return new PlaybackEvent(UUID.randomUUID().toString(), Type.HEARTBEAT, user, title, "session-" + user,
                position, 3_600, watched, at);
    }

    private static void send(PlaybackEvent e) {
        try (KafkaProducer<String, PlaybackEvent> p = new KafkaProducer<>(producerProps(),
                new StringSerializer(), new JsonSerializer<PlaybackEvent>().noTypeInfo())) {
            p.send(new ProducerRecord<>(PlaybackEvent.TOPIC, Long.toString(e.userId()), e)).get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void sendRaw(String key, byte[] value) {
        try (KafkaProducer<String, byte[]> p = new KafkaProducer<>(producerProps(),
                new StringSerializer(), new ByteArraySerializer())) {
            p.send(new ProducerRecord<>(PlaybackEvent.TOPIC, key, value)).get();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> producerProps() {
        return Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
    }

    /** Everything on the dead-letter topic, as text. */
    private static String deadLetters() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + System.nanoTime());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        StringBuilder all = new StringBuilder();
        try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(p, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of(PlaybackEvent.DEAD_LETTERS));
            for (int i = 0; i < 10; i++) {
                c.poll(Duration.ofMillis(300)).forEach(r -> all.append(new String(r.value(), StandardCharsets.UTF_8)).append('\n'));
            }
        }
        return all.toString();
    }

    private double duplicates() {
        return metrics.counter("history.events", "result", "duplicate").count();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
