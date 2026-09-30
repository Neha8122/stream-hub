package com.streamhub.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtTest {

    private static final String SECRET = "a-test-secret-that-is-long-enough-for-hs256";
    private final Instant now = Instant.parse("2026-09-30T10:00:00Z");
    private final Jwt jwt = new Jwt(SECRET, Duration.ofHours(1), Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void roundTrip() {
        Jwt.Claims c = jwt.verify(jwt.issue(42, "a@b.com")).orElseThrow();
        assertEquals(42, c.userId());
        assertEquals("a@b.com", c.email());
        assertEquals(now.plus(Duration.ofHours(1)), c.expiresAt());
    }

    @Test
    void expiredTokenIsRejected() {
        String token = jwt.issue(1, "a@b.com");
        Jwt later = new Jwt(SECRET, Duration.ofHours(1), Clock.fixed(now.plus(Duration.ofHours(2)), ZoneOffset.UTC));
        assertTrue(later.verify(token).isEmpty());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        Jwt other = new Jwt("some-other-secret-also-long-enough-for-hs256", Duration.ofHours(1),
                Clock.fixed(now, ZoneOffset.UTC));
        assertTrue(jwt.verify(other.issue(1, "a@b.com")).isEmpty());
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = jwt.issue(1, "a@b.com").split("\\.");
        String evil = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"stream-hub\",\"sub\":\"999\",\"exp\":" + (now.getEpochSecond() + 3600) + "}").getBytes());
        assertTrue(jwt.verify(parts[0] + "." + evil + "." + parts[2]).isEmpty(), "changed user id, same signature");
    }

    @Test
    void unsignedTokenIsRejected() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        String body = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"iss\":\"stream-hub\",\"sub\":\"1\",\"exp\":" + (now.getEpochSecond() + 3600) + "}").getBytes());
        assertTrue(jwt.verify(header + "." + body + ".").isEmpty());
    }

    @Test
    void garbageIsRejectedWithoutThrowing() {
        assertTrue(jwt.verify("not-a-token").isEmpty());
        assertTrue(jwt.verify("").isEmpty());
    }

    @Test
    void shortSecretIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Jwt("short", Duration.ofHours(1), Clock.systemUTC()));
    }
}
