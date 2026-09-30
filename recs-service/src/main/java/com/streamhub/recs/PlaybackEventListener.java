package com.streamhub.recs;

import com.streamhub.events.PlaybackEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Its own consumer group on the playback topic: history and recs each get
 * every event, and neither slows the other down. Taste is updated within
 * moments of a heartbeat, so the "for you" row reacts to tonight's viewing.
 */
@Component
public class PlaybackEventListener {

    private final TasteStore store;
    private final Catalog catalog;
    private final Duration halfLife;
    private final MeterRegistry metrics;

    public PlaybackEventListener(TasteStore store, Catalog catalog, MeterRegistry metrics,
                                 @Value("${recs.half-life}") Duration halfLife) {
        this.store = store;
        this.catalog = catalog;
        this.metrics = metrics;
        this.halfLife = halfLife;
        for (String r : new String[] {"applied", "duplicate", "ignored", "no_embedding"}) {
            metrics.counter("recs.events", "result", r);
        }
    }

    @KafkaListener(topics = PlaybackEvent.TOPIC, groupId = "recs", concurrency = "3")
    public void on(PlaybackEvent e) {
        count(handle(e));
    }

    String handle(PlaybackEvent e) {
        if (e.watchedSeconds() <= 0) {
            return "ignored";                  // a start, or a seek: no viewing to learn from
        }
        if (store.alreadyApplied(e.eventId())) {
            return "duplicate";                // cheap early exit; the script checks again atomically
        }
        Optional<float[]> title = catalog.embedding(e.titleId());
        if (title.isEmpty()) {
            return "no_embedding";             // not indexed yet: this nudge is lost, later ones won't be
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            Taste before = store.taste(e.userId()).orElse(null);
            Taste after = Taste.update(before, title.get(), e.watchedSeconds(), e.occurredAt(), halfLife);
            switch (store.apply(e.eventId(), e.userId(), e.titleId(), e.watchedSeconds(), e.occurredAt(),
                    before, after)) {
                case APPLIED: return "applied";
                case DUPLICATE: return "duplicate";
                case CONFLICT: continue;       // changed under us: re-read and recompute
            }
        }
        throw new IllegalStateException("taste for user " + e.userId() + " kept changing; will be retried");
    }

    private void count(String result) {
        metrics.counter("recs.events", "result", result).increment();
    }
}
