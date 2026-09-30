package com.streamhub.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Claude's Messages API, called only through the same guards as any other
 * dependency: bulkhead → circuit breaker → retry → HTTP call with a timeout.
 * An LLM is the slowest, least predictable and most expensive dependency in
 * the system, so it gets them more than anything.
 *
 * The reply is forced through a tool ("answer") with a JSON schema: the
 * model has to fill in fields, not write free text we'd have to parse.
 */
public final class Claude {

    public record Settings(String url, String apiKey, String model, Duration timeout, int maxConcurrent,
                           Duration openFor, int maxTokens) { }

    public record Usage(int inputTokens, int outputTokens) { }

    /** The tool call's input, as the model filled it in. */
    public record Reply(JsonNode input, Usage usage) { }

    static final String API_VERSION = "2023-06-01";

    private final Settings settings;
    private final RestClient http;
    private final Bulkhead bulkhead;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public Claude(Settings s, RestClient.Builder http, CircuitBreakerRegistry breakers, BulkheadRegistry bulkheads,
                  RetryRegistry retries) {
        this.settings = s;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(s.timeout());
        this.http = http.clone().baseUrl(s.url()).requestFactory(factory)
                .defaultHeader("x-api-key", s.apiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .build();
        this.bulkhead = bulkheads.bulkhead("claude", BulkheadConfig.custom()
                .maxConcurrentCalls(s.maxConcurrent()).maxWaitDuration(Duration.ZERO).build());
        this.breaker = breakers.circuitBreaker("claude", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(s.timeout().multipliedBy(9).dividedBy(10))
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(s.openFor())
                .permittedNumberOfCallsInHalfOpenState(2)
                // 400 means our request was wrong: a bug, not an unwell API.
                // 401/403 (bad key), 429 (rate limited) and 529 (overloaded)
                // all count: backing off is exactly right for them.
                .ignoreExceptions(HttpClientErrorException.BadRequest.class)
                .build());
        // One retry for 5xx/529 or a dropped connection, after ~0.5-1.5 s.
        // Never for a timeout (8 s already spent) or a 429 (retrying makes it worse).
        this.retry = retries.retry("claude", RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.ofRandomized(Duration.ofSeconds(1), 0.5))
                .retryOnException(Claude::worthRetrying)
                .build());
    }

    /** False without a key: the assistant then always falls back, and says why. */
    public boolean configured() {
        return settings.apiKey() != null && !settings.apiKey().isBlank();
    }

    public Reply call(String system, String user, String toolName, Map<String, Object> inputSchema,
                      String toolDescription) {
        Map<String, Object> body = Map.of(
                "model", settings.model(),
                "max_tokens", settings.maxTokens(),
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", user)),
                "tools", List.of(Map.of("name", toolName, "description", toolDescription,
                        "input_schema", inputSchema)),
                "tool_choice", Map.of("type", "tool", "name", toolName));
        return Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(breaker,
                        Retry.decorateSupplier(retry, () -> send(body, toolName)))).get();
    }

    private Reply send(Map<String, Object> body, String toolName) {
        JsonNode r = http.post().uri("/v1/messages").body(body).retrieve().body(JsonNode.class);
        if (r == null) {
            throw new IllegalStateException("empty response");
        }
        Usage usage = new Usage(r.path("usage").path("input_tokens").asInt(),
                r.path("usage").path("output_tokens").asInt());
        for (JsonNode block : r.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && toolName.equals(block.path("name").asText())) {
                return new Reply(block.path("input"), usage);
            }
        }
        // e.g. stop_reason max_tokens before the tool call finished
        throw new IllegalStateException("no " + toolName + " tool call; stop_reason="
                + r.path("stop_reason").asText());
    }

    private static boolean worthRetrying(Throwable t) {
        if (t instanceof HttpServerErrorException) {
            return true;                                    // 500, 502, 503, 529 overloaded
        }
        if (t instanceof ResourceAccessException) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public CircuitBreaker breaker() { return breaker; }
    public String model() { return settings.model(); }
}
