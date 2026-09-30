package com.streamhub.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * No key configured, even though ANTHROPIC_API_KEY may be set in the
 * environment (it is on the author's machine, for another account): the
 * assistant must not pick that up, and answers from search instead.
 */
@SpringBootTest(properties = "assistant.llm.api-key=")
@Testcontainers
class NoKeyTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        r.add("assistant.llm.url", FakeUpstreams::url);
        r.add("assistant.catalog.url", FakeUpstreams::url);
    }

    @Autowired AssistantService assistant;
    @Autowired Claude claude;

    @Test
    void withoutAKeyItAnswersFromSearchAndNeverCallsTheApi() {
        FakeUpstreams.reset();
        assertFalse(claude.configured(), "ANTHROPIC_API_KEY must not be picked up");
        AssistantService.Answer a = assistant.ask(1, "space");
        assertEquals(AssistantService.Mode.FALLBACK, a.mode());
        assertEquals("no_key", a.reason());
        assertFalse(a.titles().isEmpty());
        assertTrue(FakeUpstreams.claudeRequests.isEmpty());
    }
}
