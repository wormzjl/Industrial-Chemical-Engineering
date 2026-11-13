package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.BAND_TIE_LINE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.DEPOSITION_TOLERANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FAILED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.MERGED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.R;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SINGLE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SPLIT;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.CrystalSet.Crystal;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Feed;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;

/**
 * The carried crystals of {@link FluidTpEquilibrium} (S01), under {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS}: the
 * stability tests' trial filter ({@link #carriedTrial}, the workspace's {@code carriedFilter}), the crystal-rich test of
 * a split's product ({@link #crystalRich}, also the two-phase flash's), the restated split ({@link #carriedSplit}) and the
 * stand-in held out while the rest is split on the dry core ({@link #beyondStandIn}, which calls
 * {@link DryCore#hydrocarbon} back). It reads the engine's kernel, evaluator, crystal set and critical band, given once
 * at construction. Immutable; one instance per engine, built by the dry core.
 */
final class CarriedCrystals {
    /**
     * Under {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS}: a phase holding more than this share of a crystal's species
     * is that crystal's end of a tie line. A label for a stationary phase, never a validity bound on a fluid state.
     */
    private static final double CARRIED_SHARE = 0.5;

    private final DryCore dryCore;
    private final PengRobinsonKernel kernel;
    private final CubicPhaseEvaluator evaluator;
    private final int count;
    private final int basis;
    private final CrystalSet crystalSet;
    private final CriticalBand criticalBand;

    CarriedCrystals(DryCore dryCore, PengRobinsonKernel kernel, CubicPhaseEvaluator evaluator, int count, int basis,
                    CrystalSet crystalSet, CriticalBand criticalBand) {
        this.dryCore = dryCore;
        this.kernel = kernel;
        this.evaluator = evaluator;
        this.count = count;
        this.basis = basis;
        this.crystalSet = crystalSet;
        this.criticalBand = criticalBand;
    }

    /**
     * Whether a stationary trial is a crystal's stand-in and no fluid phase of the phase under test, under
     * {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS}. The trial is dense, and one of two things holds, the cheap checks
     * first. A crystal species is over {@link #CARRIED_SHARE} of the trial or of the tested phase, the tested phase is dense
     * or a split's product (a feed on a vapour root is never filtered: from its side no methane-rich stationary point exists,
     * every liquid-like trial runs to the near-pure species), and the tested phase is supersaturated by the deposit test's own
     * sign. Or the trial is the other side of the same gap: a dense supersaturated phase with the crystal species taken out
     * ({@link #crystalLean}), where that phase is the dense feed under test or a split's liquid, whichever product is tested
     * (its plane is the vapour's too). Above the triple point, where the liquid is the stable
     * phase, nothing is excluded.
     */
    boolean carriedTrial(double[] tested, Root testedRoot, double[] trial, Root trialRoot, Workspace w) {
        double t = w.carriedTemperature;
        boolean denseTrial = false;
        for (Crystal c : crystalSet.crystals) {
            int i = c.component();
            boolean rich = trial[i] > CARRIED_SHARE || tested[i] > CARRIED_SHARE;
            double[] phase = rich || !w.carriedProduct ? tested : w.liquidAmounts;
            if (!rich && !crystalLean(phase, trial, i)) continue;
            if (!denseTrial) {
                kernel.evaluate(t, w.pressure, trial, trialRoot, w.mixture, w.first);
                w.kernelEvaluations++;
                if (!PhaseIdentification.denseLiquidLike(w.first, t, w.pressure)) return false;
                denseTrial = true;
            }
            Root root = rich || !w.carriedProduct ? testedRoot : Root.LIQUID;
            double pressure = w.pressure;
            if (!w.carriedProduct || !rich) {
                kernel.evaluate(t, pressure, phase, root, w.mixture, w.second);
                w.kernelEvaluations++;
                if (!PhaseIdentification.denseLiquidLike(w.second, t, pressure)) continue;
            }
            evaluator.evaluate(t, pressure, phase, root, w.phases, w.carriedState);
            w.kernelEvaluations++;
            if (supersaturated(c, w.carriedState, t, w.pressure)) return true;
        }
        return false;
    }

    /**
     * Whether {@code trial} (shares) is {@code phase} (amounts) with crystal species {@code i} taken out: leaner in it by more
     * than the other species together lose, so two liquids that part by another species stay two liquids; {@code phase} no
     * stand-in itself.
     */
    private boolean crystalLean(double[] phase, double[] trial, int i) {
        double total = 0.0;
        for (int k = 0; k < count; k++) total += phase[k];
        double lost = phase[i] - trial[i] * total;
        if (!(lost > 0.0) || phase[i] > CARRIED_SHARE * total) return false;
        for (int k = 0; k < count; k++) if (!crystalSet.crystalSpecies[k]) lost -= Math.max(phase[k] - trial[k] * total, 0.0);
        return lost > 0.0;
    }

    /** The deposit test's sign ({@link CrystalEquilibrium#solveWithCrystals}): whether {@code c} would form from {@code state}. */
    private static boolean supersaturated(Crystal c, PhaseState state, double t, double p) {
        return (state.chemicalPotential(c.component()) - c.anchored().chemicalPotential(t, p)) / (R * t) > DEPOSITION_TOLERANCE;
    }

    /** Whether the product {@code amounts} in {@code state} is over {@link #CARRIED_SHARE} of a crystal's species, supersaturated. */
    boolean crystalRich(double[] amounts, PhaseState state, double t, Workspace w) {
        double total = 0.0;
        for (int i = 0; i < count; i++) total += amounts[i];
        for (Crystal c : crystalSet.crystals) {
            if (amounts[c.component()] > CARRIED_SHARE * total && supersaturated(c, state, t, w.pressure)) return true;
        }
        return false;
    }

    /**
     * A split under {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS} restated on the two-slot path. A split with no
     * crystal-rich product is the answer. Both products dense outside the critical band (the test of {@link DryCore#twoPhaseResult})
     * are two liquids, the crystal-rich one the crystal's stand-in in a liquid slot of its own (W3b, option A: the network
     * carries two liquids; the feed as one liquid was the one-liquid-slot workaround, a merged liquid at a composition the
     * fluid equilibrium splits). Otherwise a crystal-rich denser product is the stand-in beside a vapour: it keeps its
     * crystal species and the lighter product takes every other species, the shape a crystal is carried in. In the vapour the crystal's mass would take a gas's volume and
     * energy (a CO2 let-down below the triple point, stated all vapour, ran to CO2's 90 K floor).
     */
    int carriedSplit(double t, Feed material, Workspace w) {
        boolean swapped = w.vaporState.molarVolume() < w.liquidState.molarVolume();
        double[] denser = swapped ? w.vaporAmounts : w.liquidAmounts;
        double[] lighter = swapped ? w.liquidAmounts : w.vaporAmounts;
        boolean denserRich = crystalRich(denser, swapped ? w.vaporState : w.liquidState, t, w);
        boolean lighterRich = crystalRich(lighter, swapped ? w.liquidState : w.vaporState, t, w);
        if (!denserRich && !lighterRich) return SPLIT;
        boolean band = criticalBand.inBand(t, w.pressure, material) || w.tieLine < BAND_TIE_LINE;
        if (!band && w.liquidProductDenseLiquidLike && w.vapourProductDenseLiquidLike) return SPLIT;
        if (!denserRich) return SPLIT;
        for (int i = 0; i < count; i++) {
            double held = crystalSet.crystalSpecies[i] ? denser[i] : 0.0;
            w.liquidAmounts[i] = held;
            w.vaporAmounts[i] = material.amounts[i] - held;
        }
        evaluator.evaluate(t, w.pressure, w.liquidAmounts, Root.LIQUID, w.phases, w.liquidState);
        evaluator.evaluate(t, w.pressure, w.vaporAmounts, Root.VAPOR, w.phases, w.vaporState);
        w.kernelEvaluations += 2;
        w.liquidProductDenseLiquidLike = false;
        w.vapourProductDenseLiquidLike = false;
        return SPLIT;
    }

    /**
     * A split under {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS} whose crystal-rich liquid is sound and whose vapour
     * condenses a liquid of its own: three fluid phases, the crystal's stand-in and the shape a crystal is carried in
     * beside a vapour and a liquid. The stand-in is held out, the rest split alone, and the stand-in goes back into the
     * liquid, which then holds it supersaturated. The rest is split as {@code w.standInFeed} (its basis amounts those of
     * {@code material}: the core alone reads it, never a result builder); {@code material} stays as it is.
     */
    int beyondStandIn(double t, TangentPlaneStability.Result feed, Feed material, Workspace w) {
        String failure = w.failure;
        Feed rest = w.standInFeed;
        double[] held = w.standIn;
        for (int i = 0; i < count; i++) rest.amounts[i] = material.amounts[i] - held[i];
        System.arraycopy(material.basisAmounts, 0, rest.basisAmounts, 0, basis);
        w.beyondStandIn = true;
        int outcome;
        try {
            outcome = dryCore.hydrocarbon(t, rest, w);
        } finally {
            w.beyondStandIn = false;
        }
        w.feedResult = feed;
        if (outcome == FAILED || outcome == MERGED) {
            // The failure is the whole feed's split again; the states may hold the rest's.
            w.failure = failure;
            w.ownSplit = false;
            return FAILED;
        }
        if (outcome == SINGLE) {
            if (w.singleRoot == Root.LIQUID) return SINGLE;
            for (int i = 0; i < count; i++) {
                w.vaporAmounts[i] = material.amounts[i] - held[i];
                w.liquidAmounts[i] = 0.0;
            }
        }
        for (int i = 0; i < count; i++) w.liquidAmounts[i] += held[i];
        evaluator.evaluate(t, w.pressure, w.liquidAmounts, Root.LIQUID, w.phases, w.liquidState);
        evaluator.evaluate(t, w.pressure, w.vaporAmounts, Root.VAPOR, w.phases, w.vaporState);
        w.ownSplit = true;
        w.kernelEvaluations += 2;
        w.liquidProductDenseLiquidLike = false;
        w.vapourProductDenseLiquidLike = false;
        return SPLIT;
    }
}
