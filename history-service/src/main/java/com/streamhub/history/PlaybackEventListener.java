package com.streamhub.history;

import com.streamhub.events.PlaybackEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Consumer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka delivers at least once; {@link HistoryStore#apply} makes a second
 * delivery harmless. The offset is committed after this method returns,
 * i.e. after the database transaction: a crash in between means a
 * redelivery, never a lost event.
 */
@Component
public class PlaybackEventListener {

    private final HistoryStore store;
    private final Counter applied, duplicates;
    /** Test hook: runs after the transaction has committed, before the offset is. */
    private volatile Consumer<PlaybackEvent> afterCommit = e -> { };

    public PlaybackEventListener(HistoryStore store, MeterRegistry metrics) {
        this.store = store;
        this.applied = metrics.counter("history.events", "result", "applied");
        this.duplicates = metrics.counter("history.events", "result", "duplicate");
    }

    @KafkaListener(topics = PlaybackEvent.TOPIC, concurrency = "3")
    public void on(PlaybackEvent event) {
        if (store.apply(event)) {
            applied.increment();
        } else {
            duplicates.increment();
        }
        afterCommit.accept(event);
    }

    void setAfterCommit(Consumer<PlaybackEvent> hook) {
        this.afterCommit = hook;
    }
}
