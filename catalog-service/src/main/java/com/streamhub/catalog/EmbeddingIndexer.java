package com.streamhub.catalog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps embeddings in step with titles, off the write path. A create or
 * update leaves the title's embedding empty; this fills it in within
 * seconds. A title is keyword-searchable at once and meaning-searchable
 * shortly after, and a write never waits for (or fails because of) the model.
 */
@Component
public class EmbeddingIndexer {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);

    private final TitleRepository titles;
    private final Embedder embedder;
    private final Counter embedded;
    private final Counter failures;

    public EmbeddingIndexer(TitleRepository titles, Embedder embedder, MeterRegistry metrics) {
        this.titles = titles;
        this.embedder = embedder;
        this.embedded = metrics.counter("catalog.embeddings.indexed");
        this.failures = metrics.counter("catalog.embeddings.failures");
        metrics.gauge("catalog.embeddings.missing", titles, TitleRepository::countMissingEmbeddings);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        indexMissing();
    }

    /** Also the safety net: anything missed (crash, model error) is picked up on the next pass. */
    @Scheduled(fixedDelayString = "${catalog.search.index-every:5s}")
    public synchronized int indexMissing() {
        int done = 0;
        List<Title> batch;
        while (!(batch = titles.missingEmbeddings(32)).isEmpty()) {
            for (Title t : batch) {
                try {
                    titles.saveEmbedding(t.id(), embedder.embed(Embedder.document(t)));
                    embedded.increment();
                    done++;
                } catch (RuntimeException e) {
                    failures.increment();
                    log.warn("could not embed title {}; will retry", t.id(), e);
                    return done;                // don't spin on a broken model; next pass retries
                }
            }
        }
        if (done > 0) {
            log.info("embedded {} titles", done);
        }
        return done;
    }
}
