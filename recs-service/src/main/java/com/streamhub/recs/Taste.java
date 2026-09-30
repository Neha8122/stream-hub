package com.streamhub.recs;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * A user's taste: a running, time-decayed sum of the embeddings of what
 * they watched, weighted by how long they watched. It points toward the
 * kind of thing they like, so the titles nearest to it are the
 * recommendations.
 *
 * Old viewing fades with a half-life: after one half-life a minute watched
 * then counts half as much as a minute watched now. That's what makes the
 * row react: an evening of sci-fi moves it today, and a year-old binge
 * doesn't pin it forever.
 *
 * Pure maths, no I/O, so it's tested on its own.
 */
public record Taste(float[] vector, Instant at) {

    /** A heartbeat carries at most 60 s of watching (playback clamps it). */
    static final double FULL_WEIGHT_SECONDS = 60;

    /**
     * The taste after watching {@code watchedSeconds} of a title with this
     * embedding, at {@code when}. {@code previous} may be null (first watch).
     */
    static Taste update(Taste previous, float[] title, int watchedSeconds, Instant when, Duration halfLife) {
        double weight = Math.min(watchedSeconds, FULL_WEIGHT_SECONDS) / FULL_WEIGHT_SECONDS;
        float[] out = new float[title.length];
        Instant at = when;
        if (previous != null) {
            // Events can arrive a little out of order; never grow old taste back.
            double ageSeconds = Math.max(0, Duration.between(previous.at(), when).toMillis() / 1000.0);
            double decay = Math.pow(0.5, ageSeconds / halfLife.toSeconds());
            for (int i = 0; i < out.length; i++) {
                out[i] = (float) (previous.vector()[i] * decay);
            }
            if (when.isBefore(previous.at())) {
                at = previous.at();
            }
        }
        for (int i = 0; i < out.length; i++) {
            out[i] += (float) (title[i] * weight);
        }
        return new Taste(out, at);
    }

    /** Compact storage form: base64 of the raw floats. */
    String encoded() {
        ByteBuffer b = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float f : vector) {
            b.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(b.array());
    }

    static float[] decode(String s) {
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(s));
        float[] v = new float[b.remaining() / Float.BYTES];
        for (int i = 0; i < v.length; i++) {
            v[i] = b.getFloat();
        }
        return v;
    }
}
