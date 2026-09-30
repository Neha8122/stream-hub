package com.streamhub.home;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class HomeApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomeApplication.class, args);
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

    @Bean Dependency history(@Value("${home.history.url}") String url, @Value("${home.history.timeout}") Duration timeout,
                             @Value("${home.history.max-concurrent}") int max, @Value("${home.breaker.open-for}") Duration openFor,
                             org.springframework.web.client.RestClient.Builder http,
                             CircuitBreakerRegistry b, BulkheadRegistry h, RetryRegistry r) {
        return new Dependency(new Dependency.Settings("history", url, timeout, max, openFor), http, b, h, r);
    }

    @Bean Dependency catalog(@Value("${home.catalog.url}") String url, @Value("${home.catalog.timeout}") Duration timeout,
                             @Value("${home.catalog.max-concurrent}") int max, @Value("${home.breaker.open-for}") Duration openFor,
                             org.springframework.web.client.RestClient.Builder http,
                             CircuitBreakerRegistry b, BulkheadRegistry h, RetryRegistry r) {
        return new Dependency(new Dependency.Settings("catalog", url, timeout, max, openFor), http, b, h, r);
    }

    @Bean Dependency recs(@Value("${home.recs.url}") String url, @Value("${home.recs.timeout}") Duration timeout,
                          @Value("${home.recs.max-concurrent}") int max, @Value("${home.breaker.open-for}") Duration openFor,
                          org.springframework.web.client.RestClient.Builder http,
                          CircuitBreakerRegistry b, BulkheadRegistry h, RetryRegistry r) {
        return new Dependency(new Dependency.Settings("recs", url, timeout, max, openFor), http, b, h, r);
    }

    @Bean HomeService homeService(@Qualifier("history") Dependency history, @Qualifier("catalog") Dependency catalog,
                                  @Qualifier("recs") Dependency recs,
                                  @Value("${home.genres}") String genres, @Value("${home.deadline}") Duration deadline,
                                  MeterRegistry metrics) {
        return new HomeService(history, catalog, recs, Arrays.asList(genres.split(",")), deadline, metrics);
    }

    private final java.util.function.Supplier<HomeService> home;

    public HomeApplication(org.springframework.beans.factory.ObjectProvider<HomeService> home) {
        this.home = home::getObject;
    }

    /** X-User-Id is set by the gateway after checking the token. */
    @GetMapping("/home")
    public HomeService.Home home(@RequestHeader("X-User-Id") long userId) {
        return home.get().home(userId);
    }
}
