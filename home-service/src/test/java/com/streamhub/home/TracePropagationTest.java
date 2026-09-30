package com.streamhub.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Every downstream call made for one page carries that page's trace id,
 * including the ones made on row threads.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.otlp.tracing.endpoint=http://localhost:1/unused")
@AutoConfigureObservability                 // tracing is off in tests unless asked for
class TracePropagationTest {

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry r) {
        r.add("home.history.url", FakeServices::url);
        r.add("home.catalog.url", FakeServices::url);
    }

    @Autowired TestRestTemplate http;

    @Test
    void everyCallForOnePageSharesItsTraceId() {
        FakeServices.reset();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", "1");
        h.set("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");     // as the gateway would send it
        assertEquals(200, http.exchange("/home", HttpMethod.GET, new HttpEntity<>(h), String.class)
                .getStatusCode().value());

        List<String> seen = new ArrayList<>(FakeServices.history.traceparents);
        seen.addAll(FakeServices.catalog.traceparents);
        assertEquals(5, seen.size(), "1 history + 3 rows + 1 batch lookup: " + seen);
        Set<String> traceIds = seen.stream().map(tp -> tp.split("-").length > 1 ? tp.split("-")[1] : tp)
                .collect(Collectors.toSet());
        assertEquals(Set.of(traceId), traceIds, "every call, on every row thread, continues the page's trace");
        assertTrue(seen.stream().map(tp -> tp.split("-")[2]).distinct().count() == 5,
                "each call is its own span inside that trace");
    }
}
