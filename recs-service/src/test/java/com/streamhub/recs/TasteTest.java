package com.streamhub.recs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The taste maths on its own. */
class TasteTest {

    static final Duration DAY = Duration.ofDays(1);
    static final Instant T0 = Instant.parse("2026-09-30T20:00:00Z");

    @Test
    void firstWatchIsTheTitleScaledByTimeWatched() {
        Taste t = Taste.update(null, new float[] {1, 0}, 30, T0, DAY);
        assertArrayEquals(new float[] {0.5f, 0}, t.vector());
        assertEquals(T0, t.at());
    }

    @Test
    void oldViewingHalvesEveryHalfLife() {
        Taste a = Taste.update(null, new float[] {1, 0}, 60, T0, DAY);
        Taste b = Taste.update(a, new float[] {0, 1}, 60, T0.plus(DAY), DAY);
        assertArrayEquals(new float[] {0.5f, 1f}, b.vector(), 1e-6f);
        Taste c = Taste.update(b, new float[] {0, 0}, 60, T0.plus(DAY.multipliedBy(3)), DAY);
        assertArrayEquals(new float[] {0.125f, 0.25f}, c.vector(), 1e-6f);
    }

    @Test
    void aLateEventDoesNotMakeOldTasteGrowBack() {
        Taste a = Taste.update(null, new float[] {1, 0}, 60, T0, DAY);
        Taste late = Taste.update(a, new float[] {0, 1}, 60, T0.minus(DAY), DAY);
        assertArrayEquals(new float[] {1f, 1f}, late.vector(), 1e-6f);
        assertEquals(T0, late.at(), "time never goes backwards");
    }

    @Test
    void storageRoundTrips() {
        float[] v = {0.1f, -2.5f, 3e-7f};
        assertArrayEquals(v, Taste.decode(new Taste(v, T0).encoded()));
    }
}
