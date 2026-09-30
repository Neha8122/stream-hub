package com.streamhub.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.streamhub.events.PlaybackEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Real Redis and Kafka: what each call publishes, and how watched time is counted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PlaybackTest {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired TestRestTemplate http;
    @Autowired SessionStore sessions;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void watchedTimeCountsOnlyRealProgress() {
        String s = start(1, 10, 0);
        assertEquals(HttpStatus.OK, move("heartbeat", 1, s, 30).getStatusCode());
        move("heartbeat", 1, s, 30);        // the player retried the same heartbeat
        move("heartbeat", 1, s, 400);       // a jump far ahead
        move("heartbeat", 1, s, 100);       // seeking backwards
        assertEquals(HttpStatus.OK, move("stop", 1, s, 130).getStatusCode());

        List<PlaybackEvent> events = eventsOf(s, 6);
        assertEquals(List.of("START", "HEARTBEAT", "HEARTBEAT", "HEARTBEAT", "HEARTBEAT", "STOP"),
                events.stream().map(e -> e.type().name()).toList());
        assertEquals(List.of(0, 30, 0, 60, 0, 30), events.stream().map(PlaybackEvent::watchedSeconds).toList(),
                "retry adds 0, a jump adds at most 60, seeking back adds 0");
        assertTrue(events.stream().allMatch(e -> e.userId() == 1 && e.titleId() == 10));
        assertEquals(6, events.stream().map(PlaybackEvent::eventId).distinct().count(), "every event has its own id");
    }

    @Test
    void sessionsAreClosedAndPrivate() {
        String s = start(2, 11, 0);
        assertEquals(HttpStatus.FORBIDDEN, move("heartbeat", 3, s, 10).getStatusCode(), "someone else's session");
        assertTrue(sessions.ttlSeconds(s).orElseThrow() <= 120);
        move("stop", 2, s, 10);
        assertEquals(HttpStatus.NOT_FOUND, move("heartbeat", 2, s, 20).getStatusCode(), "closed by stop");
        assertEquals(HttpStatus.NOT_FOUND, move("heartbeat", 2, "no-such-session", 20).getStatusCode());
    }

    @Test
    void racingHeartbeatsCantDoubleCount() throws Exception {
        String s = start(4, 12, 0);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return move("heartbeat", 4, s, 45);    // 20 copies of the same heartbeat at once
            }));
        }
        go.countDown();
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        int total = eventsOf(s, 21).stream().mapToInt(PlaybackEvent::watchedSeconds).sum();
        assertEquals(45, total, "only one of the racing copies may count the 45 seconds");
    }

    // --- helpers ---

    private String start(long user, long title, int position) {
        ResponseEntity<Map> r = http.postForEntity("/playback/start",
                request(user, Map.of("titleId", title, "positionSeconds", position, "durationSeconds", 3600)), Map.class);
        assertEquals(HttpStatus.CREATED, r.getStatusCode());
        return (String) r.getBody().get("sessionId");
    }

    private ResponseEntity<Map> move(String what, long user, String session, int position) {
        return http.postForEntity("/playback/" + what,
                request(user, Map.of("sessionId", session, "positionSeconds", position)), Map.class);
    }

    private static HttpEntity<Map<String, Object>> request(long user, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", Long.toString(user));
        h.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, h);
    }

    /** Reads the topic from the start until {@code n} events of this session have been seen. */
    private List<PlaybackEvent> eventsOf(String session, int n) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<PlaybackEvent> out = new ArrayList<>();
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p, new StringDeserializer(), new StringDeserializer())) {
            c.subscribe(List.of(PlaybackEvent.TOPIC));
            long deadline = System.currentTimeMillis() + 15_000;
            while (out.size() < n && System.currentTimeMillis() < deadline) {
                for (var rec : c.poll(Duration.ofMillis(200))) {
                    PlaybackEvent e = json.readValue(rec.value(), PlaybackEvent.class);
                    if (e.sessionId().equals(session)) {
                        assertEquals(Long.toString(e.userId()), rec.key(), "keyed by user");
                        out.add(e);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertEquals(n, out.size(), "events for session " + session);
        return out;
    }
}
