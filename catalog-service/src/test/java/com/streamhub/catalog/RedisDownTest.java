package com.streamhub.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** With Redis gone, reads still work: straight from Postgres, quickly. */
@SpringBootTest
@Testcontainers
class RedisDownTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired CatalogService catalog;

    @Test
    void readsFallBackToPostgres() {
        assertTrue(catalog.title(1).isPresent());       // warm, via Redis
        redis.stop();                                   // Redis dies

        long start = System.nanoTime();
        assertTrue(catalog.title(4).isPresent(), "served from Postgres");
        assertTrue(catalog.title(1).isPresent());
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 2_000, "fell back in " + ms + " ms; Redis timeouts must stay short");
    }
}
