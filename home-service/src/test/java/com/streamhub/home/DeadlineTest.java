package com.streamhub.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The page deadline on its own. Per-call timeouts don't bound a page: a
 * dependency can be slow yet inside its own timeout, and calls can add up.
 * Here history's timeout is a generous 1 s and history takes 800 ms; only
 * the page's 200 ms deadline keeps the page fast.
 */
@SpringBootTest(properties = {"home.deadline=200ms", "home.history.timeout=1s"})
class DeadlineTest {

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("home.history.url", FakeServices::url);
        r.add("home.catalog.url", FakeServices::url);
    }

    @Autowired HomeService home;
    @Autowired CircuitBreakerRegistry breakers;

    @BeforeEach
    void reset() {
        FakeServices.reset();
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    @Test
    void theDeadlineBoundsThePageEvenWhenEachCallIsWithinItsTimeout() {
        FakeServices.history.delayMs = 800;
        HomeService.Home h = home.home(1);
        assertTrue(h.tookMillis() < 400, "page took " + h.tookMillis() + " ms; the deadline is 200");
        assertEquals(java.util.Set.of("history"), h.degraded());
        assertTrue(h.rows().stream().anyMatch(r -> r.id().equals("genre:drama")
                && r.source() == HomeService.Source.LIVE), "rows that were ready are still served");
    }
}
