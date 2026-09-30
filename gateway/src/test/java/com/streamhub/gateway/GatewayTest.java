package com.streamhub.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.streamhub.auth.Jwt;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The gateway in front of two fake backends that record what reached them.
 * Real Redis for the rate limiter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"routes.timeout-ms=500", "limits.login-per-ip-per-second=2"})
@Testcontainers
class GatewayTest {

    static final String SECRET = "dev-only-secret-change-me-at-least-32-bytes";

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    /** What the backend saw: path + X-User-Id header (or "-"). */
    static final List<String> seen = new CopyOnWriteArrayList<>();
    static final HttpServer backend = startBackend();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        String url = "http://localhost:" + backend.getAddress().getPort();
        r.add("routes.catalog", () -> url);
        r.add("routes.users", () -> url);
        r.add("routes.playback", () -> url);
        r.add("routes.history", () -> url);
        r.add("routes.home", () -> url);
        r.add("auth.jwt-secret", () -> SECRET);
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @Autowired WebTestClient http;
    @Autowired Jwt jwt;
    @Autowired org.springframework.data.redis.connection.ReactiveRedisConnectionFactory redisFactory;

    @BeforeEach
    void clear() {
        seen.clear();
        // Rate-limit buckets live in Redis and every test calls from 127.0.0.1:
        // start each test with full buckets so tests can't affect each other.
        redisFactory.getReactiveConnection().serverCommands().flushAll().block();
    }

    @Test
    void noTokenMeansNoEntry() {
        http.get().uri("/titles/1").exchange().expectStatus().isUnauthorized();
        assertTrue(seen.isEmpty(), "the backend must never see an unauthenticated request");
    }

    @Test
    void garbageOrForeignTokensAreRejected() {
        http.get().uri("/titles/1").header("Authorization", "Bearer not.a.jwt").exchange()
                .expectStatus().isUnauthorized();
        Jwt other = new Jwt("a-completely-different-secret-of-32-bytes!", Duration.ofHours(1), Clock.systemUTC());
        http.get().uri("/titles/1").header("Authorization", "Bearer " + other.issue(1, "x@y.z")).exchange()
                .expectStatus().isUnauthorized();
        Jwt past = new Jwt(SECRET, Duration.ofHours(1),
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        http.get().uri("/titles/1").header("Authorization", "Bearer " + past.issue(1, "x@y.z")).exchange()
                .expectStatus().isUnauthorized();
        assertTrue(seen.isEmpty());
    }

    @Test
    void validTokenReachesTheBackendWithTheUserId() {
        http.get().uri("/titles/1").header("Authorization", "Bearer " + jwt.issue(42, "neha@example.com"))
                .exchange().expectStatus().isOk();
        assertEquals(List.of("/titles/1 user=42"), seen);
    }

    @Test
    void aClientCannotClaimToBeSomeoneElse() {
        http.get().uri("/titles/1")
                .header("Authorization", "Bearer " + jwt.issue(42, "neha@example.com"))
                .header("X-User-Id", "1")                 // pretending to be user 1
                .exchange().expectStatus().isOk();
        assertEquals(List.of("/titles/1 user=42"), seen, "the spoofed header must be replaced");

        seen.clear();
        http.post().uri("/auth/login").header("X-User-Id", "1").exchange().expectStatus().isOk();
        assertEquals(List.of("/auth/login user=-"), seen, "and stripped on public routes too");
    }

    @Test
    void loginIsPublicButRateLimitedPerIp() {
        int ok = 0, limited = 0;
        for (int i = 0; i < 20; i++) {
            HttpStatus s = (HttpStatus) http.post().uri("/auth/login").exchange().returnResult(String.class).getStatus();
            if (s == HttpStatus.OK) {
                ok++;
            } else if (s == HttpStatus.TOO_MANY_REQUESTS) {
                limited++;
            }
        }
        // 2 per second, bursts of 4: most of 20 quick attempts are refused.
        assertTrue(ok >= 1 && ok <= 6, ok + " login attempts got through");
        assertTrue(limited >= 14, limited + " were rate limited");
    }

    @Test
    void slowBackendGetsATimeoutNotAHang() {
        long start = System.nanoTime();
        http.get().uri("/titles/slow").header("Authorization", "Bearer " + jwt.issue(7, "a@b.c"))
                .exchange().expectStatus().isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 1_500, "gave up after " + ms + " ms; the route timeout is 500 ms");
    }

    private static HttpServer startBackend() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            s.createContext("/", ex -> {
                String user = ex.getRequestHeaders().getFirst("X-User-Id");
                seen.add(ex.getRequestURI().getPath() + " user=" + (user == null ? "-" : user));
                if (ex.getRequestURI().getPath().equals("/titles/slow")) {
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            s.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
