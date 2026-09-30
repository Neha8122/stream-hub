package com.streamhub.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

/**
 * Answers keyed by the meaning of the question, not its exact text: "a
 * funny space movie" can reuse the answer to "something funny set in
 * space". A hit costs a few milliseconds and no tokens; a miss costs a
 * second or two and real money.
 *
 * Only shared answers go in: the assistant's answers depend on the question
 * and the catalog, never on who asked. Entries expire (the catalog changes)
 * and the oldest are dropped past a size limit.
 *
 * In memory, one per instance, searched by brute force: 1,000 entries x 384
 * dimensions is well under a millisecond. Past a few instances or ~100k
 * entries it would move to a shared vector index (Redis or pgvector).
 */
public final class SemanticCache {

    public record Hit(AssistantService.Answer answer, double similarity) { }

    private record Entry(float[] vector, String question, AssistantService.Answer answer, Instant expires) { }

    private final double threshold;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public SemanticCache(double threshold, Duration ttl, int maxEntries, Clock clock) {
        this.threshold = threshold;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /** The closest cached question, if it's close enough. Vectors are unit length. */
    public synchronized Optional<Hit> get(float[] question) {
        Instant now = clock.instant();
        Entry best = null;
        double bestSim = -1;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (!e.expires().isAfter(now)) {
                it.remove();
                continue;
            }
            double sim = dot(question, e.vector());
            if (sim > bestSim) {
                bestSim = sim;
                best = e;
            }
        }
        return best != null && bestSim >= threshold ? Optional.of(new Hit(best.answer(), bestSim)) : Optional.empty();
    }

    public synchronized void put(float[] question, String text, AssistantService.Answer answer) {
        entries.addLast(new Entry(question, text, answer, clock.instant().plus(ttl)));
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        entries.clear();
    }

    static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
