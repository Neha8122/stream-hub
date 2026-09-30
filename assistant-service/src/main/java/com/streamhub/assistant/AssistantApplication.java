package com.streamhub.assistant;

import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@RestController
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }

    @Bean CircuitBreakerRegistry breakers(MeterRegistry m) {
        CircuitBreakerRegistry r = CircuitBreakerRegistry.ofDefaults();
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(r).bindTo(m);
        return r;
    }

    @Bean BulkheadRegistry bulkheads(MeterRegistry m) {
        BulkheadRegistry r = BulkheadRegistry.ofDefaults();
        TaggedBulkheadMetrics.ofBulkheadRegistry(r).bindTo(m);
        return r;
    }

    @Bean RetryRegistry retries(MeterRegistry m) {
        RetryRegistry r = RetryRegistry.ofDefaults();
        TaggedRetryMetrics.ofRetryRegistry(r).bindTo(m);
        return r;
    }

    /**
     * The key comes only from assistant.llm.api-key (STREAM_HUB_ANTHROPIC_KEY).
     * ANTHROPIC_API_KEY is deliberately not read: on a developer machine it
     * is often another account's key (a work one, or the CLI's own).
     */
    @Bean Claude claude(@Value("${assistant.llm.url}") String url, @Value("${assistant.llm.api-key:}") String key,
                        @Value("${assistant.llm.model}") String model, @Value("${assistant.llm.timeout}") Duration timeout,
                        @Value("${assistant.llm.max-concurrent}") int max, @Value("${assistant.llm.open-for}") Duration openFor,
                        @Value("${assistant.llm.max-tokens}") int maxTokens, RestClient.Builder http,
                        CircuitBreakerRegistry b, BulkheadRegistry h, RetryRegistry r) {
        return new Claude(new Claude.Settings(url, key, model, timeout, max, openFor, maxTokens), http, b, h, r);
    }

    @Bean CatalogSearch catalogSearch(RestClient.Builder http, @Value("${assistant.catalog.url}") String url,
                                      @Value("${assistant.catalog.timeout}") Duration timeout) {
        var settings = ClientHttpRequestFactorySettings.defaults().withConnectTimeout(timeout).withReadTimeout(timeout);
        return new CatalogSearch(http.clone().baseUrl(url)
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings)).build());
    }

    @Bean Clock clock() {
        return Clock.systemUTC();
    }

    @Bean SemanticCache semanticCache(@Value("${assistant.cache.similarity}") double similarity,
                                      @Value("${assistant.cache.ttl}") Duration ttl,
                                      @Value("${assistant.cache.max-entries}") int max, Clock clock,
                                      MeterRegistry metrics) {
        SemanticCache c = new SemanticCache(similarity, ttl, max, clock);
        metrics.gauge("assistant.cache.entries", c, SemanticCache::size);
        return c;
    }

    @Bean Budget budget(StringRedisTemplate redis, @Value("${assistant.budget.per-user-per-day}") int perUser,
                        @Value("${assistant.budget.global-per-day}") int global, Clock clock) {
        return new Budget(redis, perUser, global, clock);
    }

    /**
     * Question embeddings for the cache: same model as the catalog, loaded
     * once, thread-safe. Case and punctuation are dropped first: "A space
     * adventure!" and "a space adventure" are one question, but the "!"
     * alone moves the raw embeddings apart by more than the cache allows.
     */
    @Bean Function<String, float[]> questionEmbedder() {
        AllMiniLmL6V2EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
        return q -> model.embed(normalise(q)).content().vector();
    }

    static String normalise(String q) {
        return q.toLowerCase().replaceAll("\\p{Punct}+", " ").replaceAll("\\s+", " ").strip();
    }

    @Bean AssistantService assistant(Claude claude, CatalogSearch catalog, SemanticCache cache, Budget budget,
                                     Function<String, float[]> questionEmbedder, MeterRegistry metrics) {
        return new AssistantService(claude, catalog, cache, budget, questionEmbedder, metrics);
    }

    private final ObjectProvider<AssistantService> assistant;

    public AssistantApplication(ObjectProvider<AssistantService> assistant) {
        this.assistant = assistant;
    }

    public record Ask(String question) { }

    /** X-User-Id is set by the gateway after checking the token. */
    @PostMapping("/assistant/ask")
    public AssistantService.Answer ask(@RequestHeader("X-User-Id") long userId, @RequestBody Ask body) {
        return assistant.getObject().ask(userId, body.question());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badQuestion(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
