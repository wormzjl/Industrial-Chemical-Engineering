package com.wormzjl.icheme.science.thermo.phase;

import java.util.List;
import java.util.Objects;

/**
 * A bracketed search for the root of an increasing function of one variable (P3 WP6b): the latest point with a
 * negative and with a positive residual, and the TP answers there. Its next point is the proposed Newton or secant
 * step while that stays strictly inside the bracket and the residual keeps shrinking (below half the last one at
 * least every second step), otherwise the bracket's midpoint; before a bracket exists, the step capped and kept
 * inside the window.
 *
 * <p>The PH and UV searches' bracket ({@link SpecificationSearch}; S01): a workspace holds two, the outer search and
 * the nested UV search's pressure search, each created with the workspace and reset at a search's start. The answer
 * inspectors its straddle tests read are here; {@link #holdsSolid} is also the searches'.
 */
final class BracketSearch {
    double minimum, maximum;
    double lower, upper, lowerResidual, upperResidual;
    EquilibriumResult lowerResult, upperResult;
    private boolean lowerCoexistence, upperCoexistence;
    private double lastMagnitude;
    private int stalls;
    private boolean bisect;
    /** Attempts at a binary's three-phase point from this search's bracket (P5). */
    int threePhaseAttempts;

    void reset(double minimum, double maximum) {
        threePhaseAttempts = 0;
        this.minimum = minimum;
        this.maximum = maximum;
        lower = upper = lowerResidual = upperResidual = Double.NaN;
        lowerResult = upperResult = null;
        lowerCoexistence = upperCoexistence = false;
        lastMagnitude = Double.NaN;
        stalls = 0;
        bisect = false;
    }

    /** {@code coexistence}: the answer at {@code x} is a coexistence from a balance (P3 WP6b), not a TP answer. */
    void record(double x, double residual, EquilibriumResult result, boolean coexistence) {
        double best = Math.min(Math.abs(lowerResidual), Math.abs(upperResidual));
        if (Double.isNaN(lowerResidual)) best = Math.abs(upperResidual);
        if (Double.isNaN(upperResidual)) best = Math.abs(lowerResidual);
        lastMagnitude = best;
        if (residual < 0.0) {
            lower = x;
            lowerResidual = residual;
            lowerResult = result;
            lowerCoexistence = coexistence;
        } else {
            upper = x;
            upperResidual = residual;
            upperResult = result;
            upperCoexistence = coexistence;
        }
        track(Math.abs(residual));
    }

    /** The bracket end with a result and the smaller residual, or null. */
    EquilibriumResult best() {
        if (lowerResult == null) return upperResult;
        if (upperResult == null) return lowerResult;
        return Math.abs(lowerResidual) <= Math.abs(upperResidual) ? lowerResult : upperResult;
    }

    boolean bestCoexistence() {
        EquilibriumResult best = best();
        return best != null && (best == lowerResult ? lowerCoexistence : upperCoexistence);
    }

    /**
     * Whether the ends hold one fluid phase each, labelled liquid at one end and vapour at the other (by the root of
     * three, or by the phase identification parameter of one), and every other phase the same: for a pure component
     * below its critical pressure, the jump of its coexistence lies between them. A dry feed's ends are the fluid
     * alone.
     */
    boolean straddlesPureCoexistence() {
        if (lowerResult == null || upperResult == null) return false;
        PhaseKind a = oneFluid(lowerResult);
        PhaseKind b = oneFluid(upperResult);
        return (a == PhaseKind.LIQUID && b == PhaseKind.VAPOR || a == PhaseKind.VAPOR && b == PhaseKind.LIQUID)
                && sameBesideFluid(lowerResult, upperResult);
    }

    /**
     * Whether one end holds a crystal and the other is fluid alone (P4 stage 2a, D21): for the one major species of a
     * competing crystal, the jump of its crystal step lies between them. A pure feed's ends are each one phase (the
     * crystal alone against one fluid phase); a near-pure feed's crystal end also holds the fluid with the traces.
     */
    boolean straddlesCrystalStep() {
        if (lowerResult == null || upperResult == null) return false;
        return holdsSolid(lowerResult) != holdsSolid(upperResult);
    }

    /** Whether an end's answer holds a crystal (P4 stage 2a). */
    boolean endHoldsSolid() { return holdsSolid(lowerResult) || holdsSolid(upperResult); }

    /** A side known without a residual: the root lies below {@code x} ({@code above}) or above it. */
    void recordSide(double x, boolean above) {
        if (above) {
            upper = x;
            upperResidual = Double.NaN;
            upperResult = null;
            upperCoexistence = false;
        } else {
            lower = x;
            lowerResidual = Double.NaN;
            lowerResult = null;
            lowerCoexistence = false;
        }
        track(Double.NaN);
    }

    /**
     * Bisection after two bracketed steps in a row that did not halve the smaller residual of the bracket's ends as it
     * stood before the step (a side without a residual counts as no progress).
     */
    private void track(double magnitude) {
        if (bracketed()) {
            stalls = magnitude <= 0.5 * lastMagnitude ? 0 : stalls + 1;
            bisect = stalls >= 2;
        }
    }

    boolean bracketed() { return !Double.isNaN(lower) && !Double.isNaN(upper); }

    /** The secant through the bracket's ends (regula falsi), NaN without two residuals. */
    double bracketSlope() { return (upperResidual - lowerResidual) / (upper - lower); }

    double width() { return Math.abs(upper - lower); }

    double next(double x, double step, double cap) {
        if (bracketed()) {
            double a = Math.min(lower, upper);
            double b = Math.max(lower, upper);
            double candidate = x + step;
            if (bisect || !(candidate > a && candidate < b)) candidate = 0.5 * (a + b);
            return candidate;
        }
        if (!Double.isFinite(step)) return x;
        return Math.clamp(x + Math.clamp(step, -cap, cap), minimum, maximum);
    }

    // The answer inspectors of the straddle tests (P4 stage 2a, D21).

    static boolean holdsSolid(EquilibriumResult result) {
        if (result == null || !result.converged()) return false;
        for (PhaseAmounts phase : result.phases()) if (phase.kind() == PhaseKind.SOLID) return true;
        for (PhaseState state : result.coexisting()) if (state.kind() == PhaseKind.SOLID) return true;
        return false;
    }

    /** Whether a phase is a hydrocarbon fluid: labelled liquid, vapour or fluid (not a crystal). */
    private static boolean hydrocarbonFluid(PhaseKind kind) {
        return kind == PhaseKind.LIQUID || kind == PhaseKind.VAPOR || kind == PhaseKind.FLUID;
    }

    /** The label of an answer's one hydrocarbon fluid phase, or null where it has none or several. */
    private static PhaseKind oneFluid(EquilibriumResult result) {
        PhaseKind found = null;
        for (PhaseAmounts phase : result.phases()) {
            if (!hydrocarbonFluid(phase.kind())) continue;
            if (found != null) return null;
            found = phase.kind();
        }
        return found;
    }

    /** Whether two answers hold the same phases beside their hydrocarbon fluid ones: by kind and crystal, in order. */
    private static boolean sameBesideFluid(EquilibriumResult a, EquilibriumResult b) {
        List<PhaseAmounts> x = a.phases();
        List<PhaseAmounts> y = b.phases();
        int i = 0;
        int j = 0;
        while (true) {
            while (i < x.size() && hydrocarbonFluid(x.get(i).kind())) i++;
            while (j < y.size() && hydrocarbonFluid(y.get(j).kind())) j++;
            if (i == x.size() || j == y.size()) return i == x.size() && j == y.size();
            if (x.get(i).kind() != y.get(j).kind() || !Objects.equals(x.get(i).crystal(), y.get(j).crystal())) return false;
            i++;
            j++;
        }
    }
}
