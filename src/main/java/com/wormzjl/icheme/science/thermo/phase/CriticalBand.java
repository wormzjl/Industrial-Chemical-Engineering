package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.BAND_TIE_LINE;

import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Feed;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;

/**
 * The declared critical band of {@link FluidTpEquilibrium} (S01): whether a feed is a pure fluid inside one of the
 * declared boxes ({@link FluidTpEquilibrium#pureBandBox}, which stays the engine's public definition), and the reason
 * a banded answer or failure names. It reads the evaluator's critical constants, given once at construction.
 * Immutable; one instance per engine.
 */
final class CriticalBand {
    private final CubicPhaseEvaluator evaluator;

    CriticalBand(CubicPhaseEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * The pure-fluid band: one component at least 0.95 of the amount, at reduced temperature and pressure (its own
     * critical constants) inside one of the declared boxes, closed intervals ({@link FluidTpEquilibrium#pureBandBox}).
     */
    boolean inBand(double t, double p, Feed material) {
        int dominant = -1;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            if (material.z[i] >= 0.95) dominant = i;
        }
        if (dominant < 0) return false;
        var constants = evaluator.component(dominant);
        return FluidTpEquilibrium.pureBandBox(t / constants.criticalTemperatureKelvin(), p / constants.criticalPressurePascal()) != 0;
    }

    String bandReason(double t, double p, Feed material, Workspace w, double stationary) {
        if (inBand(t, p, material)) return "critical band: pure fluid near its critical point";
        if (w.tieLine < BAND_TIE_LINE) return "critical band: short tie line (sum z ln K^2 = " + w.tieLine + ")";
        return "critical band: incipient phase next to the feed (sum (ln w - ln z)^2 = " + stationary + ")";
    }
}
