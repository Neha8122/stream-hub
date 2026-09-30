package com.streamhub.recs;

import com.streamhub.events.PlaybackEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@RestController
public class RecsApplication {

    private static final Logger log = LoggerFactory.getLogger(RecsApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RecsApplication.class, args);
    }

    /** Declared here too, so any service can start first; same settings everywhere. */
    @Bean
    NewTopic playbackEvents() {
        return TopicBuilder.name(PlaybackEvent.TOPIC).partitions(6).replicas(1).build();
    }

    /**
     * Retry a failing event a few times (catalog briefly down), then skip it.
     * No dead-letter topic on purpose: taste is derived data and an
     * approximation anyway. Losing one 60-second nudge is harmless; blocking
     * the partition, and every user's recommendations with it, is not.
     */
    @Bean
    DefaultErrorHandler errorHandler(MeterRegistry metrics) {
        return new DefaultErrorHandler((record, ex) -> {
            metrics.counter("recs.events", "result", "skipped").increment();
            log.warn("skipping playback event at {}-{}@{} after retries", record.topic(), record.partition(),
                    record.offset(), ex);
        }, new FixedBackOff(500, 4));
    }

    /** Spring's builder, so calls to catalog are traced. */
    @Bean
    Catalog catalog(RestClient.Builder http, @Value("${recs.catalog.url}") String url,
                    @Value("${recs.catalog.timeout}") Duration timeout) {
        var settings = org.springframework.boot.http.client.ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(timeout).withReadTimeout(timeout);
        return new Catalog(http.clone().baseUrl(url)
                .requestFactory(org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.jdk()
                        .build(settings))
                .build());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    private final org.springframework.beans.factory.ObjectProvider<RecsService> recs;

    public RecsApplication(org.springframework.beans.factory.ObjectProvider<RecsService> recs) {
        this.recs = recs;
    }

    /** X-User-Id is set by the gateway after checking the token. */
    @GetMapping("/recs/for-you")
    public RecsService.Recs forYou(@RequestHeader("X-User-Id") long userId,
                                   @RequestParam(defaultValue = "10") int limit) {
        return recs.getObject().forYou(userId, limit);
    }
}
