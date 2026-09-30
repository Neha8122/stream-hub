package com.streamhub.home;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
import java.util.function.Supplier;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * One downstream service, called only through its guards
 * (docs/lld-resilience.html §3):
 *
 * <pre>bulkhead → circuit breaker → retry → HTTP call with a timeout</pre>
 *
 * Every failure mode ends in an exception the caller turns into a fallback:
 * {@code BulkheadFullException} (too many calls already in flight),
 * {@code CallNotPermittedException} (breaker open), or the HTTP error.
 */
public final class Dependency {

    public record Settings(String name, String url, Duration timeout, int maxConcurrent, Duration openFor) { }

    private final String name;
    private final RestClient http;
    private final Bulkhead bulkhead;
    private final CircuitBreaker breaker;
    private final Retry retry;

    /**
     * @param http Spring's RestClient.Builder: it's instrumented, so every call
     *             gets a span and carries the trace id in a traceparent header.
     *             A builder made with RestClient.builder() would send neither.
     */
    public Dependency(Settings s, RestClient.Builder http, CircuitBreakerRegistry breakers,
                      BulkheadRegistry bulkheads, RetryRegistry retries) {
        this.name = s.name();
        // HTTP/1.1: the JDK client otherwise tries to upgrade plain-HTTP
        // connections to HTTP/2 (h2c) on first use, an extra round trip that
        // servers without h2c support can answer by dropping the connection.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(200))
                .build());
        factory.setReadTimeout(s.timeout());
        this.http = http.clone().baseUrl(s.url()).requestFactory(factory).build();

        // At most maxConcurrent calls in flight; beyond that, fail at once.
        this.bulkhead = bulkheads.bulkhead(name, BulkheadConfig.custom()
                .maxConcurrentCalls(s.maxConcurrent())
                .maxWaitDuration(Duration.ZERO)
                .build());

        // Open when half of the last 20 calls (at least 10) failed or were slow.
        this.breaker = breakers.circuitBreaker(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(s.timeout().multipliedBy(9).dividedBy(10))
                .slowCallRateThreshold(50)
                .waitDurationInOpenState(s.openFor())
                .permittedNumberOfCallsInHalfOpenState(3)
                // A 404 or 400 is our request's fault, not a sign the service is unwell.
                .ignoreExceptions(HttpClientErrorException.class)
                .build());

        // One retry for a dropped connection or a 5xx, after 30-60 ms. Never
        // for a timeout: that time is already spent and the deadline won't wait.
        this.retry = retries.retry(name, RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.ofRandomized(Duration.ofMillis(45), 0.33))
                .retryOnException(Dependency::worthRetrying)
                .build());
    }

    /** Runs {@code call} with every guard in place. */
    public <T> T call(java.util.function.Function<RestClient, T> call) {
        Supplier<T> guarded = Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(breaker,
                        Retry.decorateSupplier(retry, () -> call.apply(http))));
        return guarded.get();
    }

    private static boolean worthRetrying(Throwable t) {
        if (t instanceof CallNotPermittedException || t instanceof HttpClientErrorException) {
            return false;
        }
        if (t instanceof HttpServerErrorException) {
            return true;
        }
        if (t instanceof ResourceAccessException) {
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (c instanceof SocketTimeoutException || c instanceof HttpTimeoutException) {
                    return false;               // timed out: don't spend the budget twice
                }
            }
            return true;                        // refused / reset: a quick second try can work
        }
        return false;
    }

    public String name() { return name; }
    public CircuitBreaker breaker() { return breaker; }
    public Bulkhead bulkhead() { return bulkhead; }
}
