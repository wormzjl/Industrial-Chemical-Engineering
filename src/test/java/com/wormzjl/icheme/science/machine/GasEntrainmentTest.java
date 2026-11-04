package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The gas onset and head share of plan section 3.6 (Turpin et al. 1986) against TWO_PHASE_MACHINES_RESEARCH_REVIEW.md
 * section 1 and tmp/rotating-machinery/two-phase/laws.js. */
class GasEntrainmentTest {
    private static final double BAR = 1e5;

    @Test void theOnsetRisesWithTheSuctionPressure() {
        assertEquals(.0218, GasEntrainment.ONSET_RATIO_PER_PASCAL * BAR, 1e-4, "r_T = 3 p[psia]/2000 = 0.0218 p[bar]");
        assertEquals(.021, GasEntrainment.onset(BAR), 5e-4, "2.1 % at 1 bar");
        assertEquals(.052, GasEntrainment.onset(2.5 * BAR), 5e-4, "5.2 % at 2.5 bar");
        assertEquals(.098, GasEntrainment.onset(5 * BAR), 5e-4, "9.8 % at 5 bar");
        assertEquals(.18, GasEntrainment.onset(10 * BAR), 5e-3, "18 % at 10 bar");
        assertEquals(.30, GasEntrainment.onset(20 * BAR), 5e-3, "30 % at 20 bar");
        double r = GasEntrainment.ONSET_RATIO_PER_PASCAL * 3e5;
        assertEquals(r / (1 + r), GasEntrainment.onset(3e5), 1e-15);
        assertThrows(IllegalArgumentException.class, () -> GasEntrainment.onset(0));
        assertThrows(IllegalArgumentException.class, () -> GasEntrainment.onset(Double.NaN));
    }

    @Test void theHeadShareMatchesTheResearchTable() {
        assertEquals(1, GasEntrainment.headShare(0, BAR), 0, "no gas, the liquid curve");
        assertEquals(.89, GasEntrainment.headShare(.02, BAR), 5e-3, "1 bar, 2 %: the Goulds rule's 10 % loss");
        assertEquals(.34, GasEntrainment.headShare(.04, BAR), 5e-3, "1 bar, 4 %: its 43 % loss");
        assertEquals(.01, GasEntrainment.headShare(.10, BAR), 5e-3, "1 bar, 10 %: near gas lock");
        assertEquals(.95, GasEntrainment.headShare(.04, 2.5 * BAR), 5e-3, "2.5 bar, 4 %");
        assertEquals(.66, GasEntrainment.headShare(.07, 2.5 * BAR), 5e-3, "2.5 bar, 7 %");
        assertEquals(.32, GasEntrainment.headShare(.10, 2.5 * BAR), 5e-3, "2.5 bar, 10 %");
        assertEquals(.81, GasEntrainment.headShare(.20, 10 * BAR), 5e-3, "10 bar, 20 %");
        assertEquals(.45, GasEntrainment.headShare(.30, 10 * BAR), 5e-3, "10 bar, 30 %");
        assertEquals(1 / (1 + Math.pow(1 / GasEntrainment.HALF_HEAD_ONSETS, 4)), GasEntrainment.headShare(GasEntrainment.onset(BAR), BAR), 1e-12, "0.87 at the onset");
        assertEquals(.5, GasEntrainment.headShare(GasEntrainment.HALF_HEAD_ONSETS * GasEntrainment.onset(4 * BAR), 4 * BAR), 1e-12, "halved at 1.6 onsets");
        assertEquals(.88, GasEntrainment.headShare(.02, BAR) * (1 - .02), 5e-3, "the rise share M (1 − α) at 1 bar, 2 %");
    }

    /** W10c (owner decision D35): the churn's loudness in onsets r = α/α_T, the build plan's numbers. */
    @Test void theChurnRisesFromHalfTheOnsetPeaksAndFadesTowardGasLock() {
        assertEquals(0, GasEntrainment.churn(.5), 1e-12, "silent at half the onset");
        assertEquals(0, GasEntrainment.churn(.2), 1e-12);
        assertEquals(.76, GasEntrainment.churn(1), .005, "at the onset");
        assertEquals(1.0, GasEntrainment.churn(1.42), .005, "the peak");
        assertEquals(.51, GasEntrainment.churn(2), .005);
        assertEquals(.13, GasEntrainment.churn(3), .005);
        for (double r = 0; r < 6; r += .01) assertTrue(GasEntrainment.churn(r) >= 0 && GasEntrainment.churn(r) <= 1, "r " + r);
        assertEquals(0, GasEntrainment.churn(Double.NaN));
        assertEquals(0, GasEntrainment.churn(Double.POSITIVE_INFINITY));
    }

    @Test void theHeadShareFallsWithGasAndRisesWithPressure() {
        for (double p : new double[]{1e3, BAR, 10 * BAR, 100 * BAR}) {
            double before = 1;
            for (double a = .005; a <= 1; a += .005) {
                double m = GasEntrainment.headShare(a, p);
                assertTrue(m > 0 && m < before, "falls with gas at " + p + " Pa, " + a);
                assertTrue(GasEntrainment.headShare(a, 2 * p) > m, "rises with pressure at " + p + " Pa, " + a);
                before = m;
            }
        }
    }
}
