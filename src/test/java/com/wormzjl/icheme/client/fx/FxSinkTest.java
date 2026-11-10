package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Travelling puffs (batch 2026-11-10-custom-shader-system, W3 to W6): phase, fade, staggered seeds and the packed drift. */
class FxSinkTest {
    @Test
    void phaseRunsOncePerPeriodOffsetBySeed() {
        assertEquals(0, FxSink.phase(0, 2, 0), 1e-12);
        assertEquals(.5, FxSink.phase(1, 2, 0), 1e-12);
        assertEquals(0, FxSink.phase(4096, 2, 0), 1e-9);
        assertEquals(.25, FxSink.phase(0, 2, 1024), 1e-12);
        assertEquals(.75, FxSink.phase(1, 2, 1024), 1e-12);
    }

    @Test
    void staggeredPuffsKeepTheStreamSteady() {
        // Three puffs a third of a period apart: their summed opacity varies little over the period.
        double lo = Double.MAX_VALUE, hi = 0;
        for (int i = 0; i < 100; i++) {
            double t = i / 50.0, sum = 0;
            for (int k = 0; k < 3; k++) sum += FxSink.envelope(FxSink.phase(t, 2, FxSink.staggered(k, 3, 0)));
            lo = Math.min(lo, sum);
            hi = Math.max(hi, sum);
        }
        assertTrue(lo > 1.6 && hi < 2.1 && hi / lo < 1.2, lo + " to " + hi);
        assertEquals(0, FxSink.envelope(0), 1e-12);
        assertEquals(1, FxSink.envelope(.5), 1e-12);
        for (int noise = -100; noise < 100; noise += 7) {
            int seed = FxSink.staggered(2, 3, noise);
            assertTrue(seed >= 0 && seed <= FxSink.SEED_MASK);
        }
    }

    @Test
    void packedDriftFitsTheNormalBytes() {
        // DRIFT_SCALE 64 m: half of it packs to 0.5; a 100 m drift is cut to the scale, its direction kept.
        float[] small = FxSink.drift(0, 32, 0);
        assertEquals(.5, small[1], 1e-6);
        float[] large = FxSink.drift(60, 80, 0);
        assertEquals(1, Math.sqrt(large[0] * large[0] + large[1] * large[1]), 1e-6);
        assertEquals(.6, large[0], 1e-6);
    }
}
