package com.streamhub.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.streamhub.auth.Jwt;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Register and log in over HTTP, against real Postgres. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class UserServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired Jwt jwt;

    private ResponseEntity<Map> register(String email, String password) {
        return http.postForEntity("/users/register",
                Map.of("email", email, "password", password, "displayName", "Neha"), Map.class);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return http.postForEntity("/auth/login", Map.of("email", email, "password", password), Map.class);
    }

    @Test
    void registerThenLoginGivesAValidToken() {
        ResponseEntity<Map> reg = register("neha@example.com", "correct-horse");
        assertEquals(HttpStatus.CREATED, reg.getStatusCode());
        assertTrue(reg.getBody().get("passwordHash") == null, "the hash must never leave the service");

        ResponseEntity<Map> login = login("neha@example.com", "correct-horse");
        assertEquals(HttpStatus.OK, login.getStatusCode());
        String token = (String) login.getBody().get("token");
        Jwt.Claims claims = jwt.verify(token).orElseThrow();
        assertEquals(((Number) reg.getBody().get("id")).longValue(), claims.userId());
        assertEquals(3600, ((Number) login.getBody().get("expiresInSeconds")).intValue());
    }

    @Test
    void emailIsCaseInsensitiveAndUnique() {
        assertEquals(HttpStatus.CREATED, register("Case@Example.com", "password-1").getStatusCode());
        assertEquals(HttpStatus.CONFLICT, register("case@example.com", "password-2").getStatusCode());
        assertEquals(HttpStatus.OK, login("CASE@example.com", "password-1").getStatusCode());
    }

    @Test
    void wrongPasswordAndUnknownEmailLookTheSame() {
        register("known@example.com", "the-password");
        ResponseEntity<Map> wrong = login("known@example.com", "not-the-password");
        ResponseEntity<Map> unknown = login("nobody@example.com", "the-password");
        assertEquals(HttpStatus.UNAUTHORIZED, wrong.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.getStatusCode());
        assertEquals(wrong.getBody(), unknown.getBody());
    }

    @Test
    void unknownEmailTakesAboutAsLongAsAWrongPassword() {
        register("timing@example.com", "the-password");
        login("timing@example.com", "warm-up");
        long wrong = time(() -> login("timing@example.com", "not-the-password"));
        long unknown = time(() -> login("nobody-here@example.com", "not-the-password"));
        // Both run one BCrypt check. Without the dummy hash, "unknown" would
        // return in ~1 ms and give away which emails have accounts.
        assertTrue(unknown > wrong / 3, "unknown email took " + unknown + " ms vs wrong password " + wrong + " ms");
    }

    @Test
    void badInputIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST, register("not-an-email", "long-enough-password").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, register("ok@example.com", "short").getStatusCode());
    }

    @Test
    void meNeedsTheGatewayHeader() {
        long id = ((Number) register("me@example.com", "password-me").getBody().get("id")).longValue();
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", Long.toString(id));
        ResponseEntity<Map> me = http.exchange("/users/me", HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertEquals(HttpStatus.OK, me.getStatusCode());
        assertEquals("me@example.com", me.getBody().get("email"));
        assertNotNull(me.getBody().get("displayName"));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getForEntity("/users/me", Map.class).getStatusCode());
    }

    private static long time(Runnable r) {
        long start = System.nanoTime();
        r.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
