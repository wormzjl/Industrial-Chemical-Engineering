package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.BAND_STATIONARY_DISTANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.BAND_TIE_LINE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FAILED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.MERGED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SINGLE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SPLIT;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.THREE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.UNRESOLVED_STABILITY_BUDGET_FACTOR;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.diagnostics;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Feed;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Settings;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;
import com.wormzjl.icheme.science.thermo.phase.ThreeSlotFlash.Slots;
import java.util.ArrayList;
import java.util.List;

/**
 * The dry core of {@link FluidTpEquilibrium} (S01): the TP answer of one feed without crystals, at the workspace's
 * pressure. The feed's tangent-plane stability test (counted; filtered on the carried path) and its repetition on the
 * longer budget, the dispatch of an unstable feed to the three-slot system ({@link ThreeSlotFlash}) or the two-phase
 * flash ({@link TwoPhaseFlash}), the carried path's restatements ({@link CarriedCrystals}), and the answer of the outcome:
 * one phase, a merged split, two or three phases, or the typed failure. It reads the feed it is handed ({@link Feed}) and
 * no other. It holds the engine's contract, kernel, evaluator, settings, stability test and critical band, given once at
 * construction; it builds the longer-budget test, the polar list, the carried crystals and both flashes once, and calls
 * back into the engine only for the answer builders ({@code converged}, {@code expand}, {@code diagnostics}). Immutable;
 * one instance per engine.
 */
final class DryCore {
    private final FluidTpEquilibrium engine;
    private final PhaseContract contract;
    private final PengRobinsonKernel kernel;
    private final CubicPhaseEvaluator evaluator;
    private final Settings settings;
    private final TangentPlaneStability stability;
    /** The same test with {@link FluidTpEquilibrium#UNRESOLVED_STABILITY_BUDGET_FACTOR} times the per-trial budget (P3 WP7d). */
    private final TangentPlaneStability patientStability;
    private final int count;
    /** W3a D1: water is a component of the cubic ({@link WaterParticipation#IN_EQUATION_OF_STATE}). */
    private final boolean inEquationOfState;
    /** W3a D3: per component, polar by its group counts (a derived list); a feed holding one runs the three-slot system. */
    private final boolean[] polar;
    /** The declared critical band (S01), the engine's. */
    private final CriticalBand criticalBand;
    /** The carried crystals (S01), built once here: they split the rest of a stand-in on this core. */
    final CarriedCrystals carried;
    /** The two-phase flash (S01), built once at construction. */
    final TwoPhaseFlash twoPhaseFlash;
    /** The three-slot system (W3a D3, S01), built once at construction. */
    final ThreeSlotFlash threeSlotFlash;

    /**
     * Built once, at the end of the engine's constructor. Builds the carried crystals and the two flashes, each handed
     * this core, which they only store: none of them runs before the engine's constructor has returned.
     */
    DryCore(FluidTpEquilibrium engine, PhaseContract contract, PengRobinsonKernel kernel, CubicPhaseEvaluator evaluator,
            Settings settings, TangentPlaneStability stability, int count, int basis, boolean inEquationOfState,
            int cubicWater, CrystalSet crystalSet, CriticalBand criticalBand) {
        this.engine = engine;
        this.contract = contract;
        this.kernel = kernel;
        this.evaluator = evaluator;
        this.settings = settings;
        this.stability = stability;
        this.count = count;
        this.inEquationOfState = inEquationOfState;
        this.criticalBand = criticalBand;
        var s = settings.stability();
        patientStability = new TangentPlaneStability(kernel, new TangentPlaneStability.Settings(s.instabilityTolerance(),
                s.convergenceTolerance(), s.newtonSwitch(), s.maximumSuccessiveSubstitutions(), s.accelerationCycle(),
                Math.multiplyExact(s.maximumIterations(), UNRESOLVED_STABILITY_BUDGET_FACTOR), s.trivialDistance(),
                s.pureComponentTrialLimit(), s.exhaustive()), cubicWater);
        polar = new boolean[count];
        if (evaluator.derivation() != null) for (int i = 0; i < count; i++) polar[i] = evaluator.derivation().polar(i);
        carried = new CarriedCrystals(this, kernel, evaluator, count, basis, crystalSet, criticalBand);
        twoPhaseFlash = new TwoPhaseFlash(this, carried, kernel, evaluator, settings, stability, count, crystalSet.crystalSpecies);
        threeSlotFlash = new ThreeSlotFlash(this, twoPhaseFlash, kernel, evaluator, settings, stability, count, polar, cubicWater);
    }

    // The dry calculation: hydrocarbon phases at one pressure.

    /**
     * The dry TP answer of {@code material} (the request's feed, or a crystal solve's reduced feed): the core, then the
     * result of its outcome. The core and the builders read the feed from {@code material} only.
     */
    EquilibriumResult solveDry(double t, double p, PhaseCompetition competition, Feed material, Workspace w) {
        w.pressure = p;
        int outcome = hydrocarbon(t, material, w);
        return switch (outcome) {
            case SINGLE -> singlePhase(t, p, competition, material, w);
            case MERGED -> merged(t, p, competition, material, w);
            case SPLIT -> twoPhaseResult(t, p, competition, material, w);
            case THREE -> threePhaseResult(t, p, competition, material, w);
            default -> failed(t, p, competition, material, w);
        };
    }

    private EquilibriumResult singlePhase(double t, double p, PhaseCompetition competition, Feed material, Workspace w) {
        TangentPlaneStability.Result feed = w.feedResult;
        boolean band = criticalBand.inBand(t, p, material) || feed.nearestStationaryDistance() < BAND_STATIONARY_DISTANCE && material.presentCount > 1;
        String why = band ? criticalBand.bandReason(t, p, material, w, feed.nearestStationaryDistance()) : null;
        if (material.presentCount == 1) {
            int only = material.present[0];
            kernel.evaluate(t, p, material.amounts, Root.VAPOR, w.mixture, w.first);
            w.kernelEvaluations++;
            if (w.first.physicalRootCount() > 1) {
                kernel.evaluate(t, p, material.amounts, Root.LIQUID, w.mixture, w.second);
                w.kernelEvaluations++;
                // d(ln phi_L - ln phi_V)/d ln P = Z_L - Z_V: the gap over the Z gap is the relative distance in
                // pressure to the equal-fugacity pressure, to first order.
                double gap = w.second.logFugacityCoefficient(only) - w.first.logFugacityCoefficient(only);
                double compressibilityGap = w.first.compressibility() - w.second.compressibility();
                if (Math.abs(gap) <= settings.coexistenceTolerance() * compressibilityGap) {
                    PhaseState vapour = evaluator.evaluate(t, p, material.amounts, Root.VAPOR, w.phases, w.vaporScratch).copy();
                    PhaseState liquid = evaluator.evaluate(t, p, material.amounts, Root.LIQUID, w.phases, w.liquidScratch).copy();
                    w.kernelEvaluations += 2;
                    return engine.converged(competition, Classification.PURE_COEXISTENCE_UNDERDETERMINED, List.of(),
                            List.of(vapour, liquid), diagnostics(w, feed, band), why);
                }
            }
        }
        Root root = w.singleRoot == Root.VAPOR ? Root.VAPOR : Root.LIQUID;
        PhaseState state = evaluator.evaluate(t, p, material.amounts, root, w.phases, w.vaporScratch);
        w.kernelEvaluations++;
        Classification classification = singleClassification(state, t, p, material, w);
        PhaseKind label = singleLabel(state, classification, t, p, material, w);
        var phase = new PhaseAmounts(label, null, t, p, material.basisAmounts, state.copy());
        return engine.converged(competition, classification, List.of(phase), List.of(), diagnostics(w, feed, band), why);
    }

    /** The classification of one hydrocarbon phase from its root situation (P2 rule). */
    private Classification singleClassification(PhaseState state, double t, double p, Feed material, Workspace w) {
        return switch (state.kind()) {
            case VAPOR -> Classification.SINGLE_VAPOR;
            case LIQUID -> Classification.SINGLE_LIQUID;
            default -> material.presentCount == 1 && supercritical(material.present[0], t, p)
                    ? Classification.SUPERCRITICAL_FLUID : Classification.SINGLE_FLUID;
        };
    }

    /**
     * The label of a single phase: the root for three roots; {@link PhaseKind#FLUID} for a pure supercritical fluid;
     * otherwise the phase identification parameter of its one root: liquid-like above one and above the root's
     * compressibility factor ({@link PhaseIdentification#liquidLike(double, double)}, P3 WP7c: a hot dilute gas has a
     * parameter just above one and its {@code Z} above that).
     */
    private PhaseKind singleLabel(PhaseState state, Classification classification, double t, double p, Feed material, Workspace w) {
        return switch (classification) {
            case SINGLE_VAPOR -> PhaseKind.VAPOR;
            case SINGLE_LIQUID -> PhaseKind.LIQUID;
            case SUPERCRITICAL_FLUID -> PhaseKind.FLUID;
            default -> {
                Root root = state.root() == Root.VAPOR ? Root.VAPOR : Root.LIQUID;
                kernel.evaluate(t, p, material.amounts, root, w.mixture, w.first);
                w.kernelEvaluations++;
                double parameter = PhaseIdentification.parameter(w.first, t, p);
                yield Double.isNaN(parameter) ? PhaseKind.FLUID
                        : PhaseIdentification.liquidLike(parameter, w.first.compressibility()) ? PhaseKind.LIQUID : PhaseKind.VAPOR;
            }
        };
    }

    private boolean supercritical(int component, double t, double p) {
        var constants = evaluator.component(component);
        return t > constants.criticalTemperatureKelvin() && p > constants.criticalPressurePascal();
    }

    /** A near-critical split below the model's resolution: one fluid, the feed on its stability root, in the band. */
    private EquilibriumResult merged(double t, double p, PhaseCompetition competition, Feed material, Workspace w) {
        Root root = w.feedResult.feedRoot() == Root.VAPOR ? Root.VAPOR : Root.LIQUID;
        PhaseState state = evaluator.evaluate(t, p, material.amounts, root, w.phases, w.vaporScratch);
        w.kernelEvaluations++;
        var phase = new PhaseAmounts(PhaseKind.FLUID, null, t, p, material.basisAmounts, state.copy());
        return engine.converged(competition, Classification.SINGLE_FLUID, List.of(phase), List.of(), diagnostics(w, w.feedResult, true),
                mergedDetail(w));
    }

    private String mergedDetail(Workspace w) {
        return "merged near-critical split (sum z ln K^2 = " + w.tieLine + " below " + settings.mergeTieLine()
                + "): the stability test found the feed unstable, but its two phases coincide within the model's resolution";
    }

    /**
     * A dry split. The denser phase is the liquid and the lighter the vapour, unless both are liquid-like
     * ({@code PIP > max(1, Z)} on each product's own root) and denser than the cubic's critical volume
     * ({@link PhaseIdentification#denseLiquidLike}: a dense gas beside a liquid at 8 to 10 MPa has a parameter just above
     * one while less dense than {@code 3.9514 b}) and the split lies outside the declared critical band: then it is
     * {@link Classification#LIQUID_LIQUID} with both phases {@link PhaseKind#LIQUID}, the lighter first. Inside the band
     * the parameter is no label to trust (next to a critical point it runs to plus or minus infinity), so a split there
     * stays vapour-liquid.
     */
    private EquilibriumResult twoPhaseResult(double t, double p, PhaseCompetition competition, Feed material, Workspace w) {
        PhaseState liquid = w.liquidState;
        PhaseState vapour = w.vaporState;
        boolean swapped = vapour.molarVolume() < liquid.molarVolume();
        boolean band = criticalBand.inBand(t, p, material) || w.tieLine < BAND_TIE_LINE;
        boolean liquidLiquid = !band && w.liquidProductDenseLiquidLike && w.vapourProductDenseLiquidLike;
        PhaseAmounts lighter = new PhaseAmounts(liquidLiquid ? PhaseKind.LIQUID : PhaseKind.VAPOR, null, t, p,
                engine.expand(swapped ? w.liquidAmounts : w.vaporAmounts), (swapped ? liquid : vapour).copy());
        PhaseAmounts denser = new PhaseAmounts(PhaseKind.LIQUID, null, t, p,
                engine.expand(swapped ? w.vaporAmounts : w.liquidAmounts), (swapped ? vapour : liquid).copy());
        return engine.converged(competition, liquidLiquid ? Classification.LIQUID_LIQUID : Classification.VAPOR_LIQUID,
                List.of(lighter, denser), List.of(), diagnostics(w, w.feedResult, band), band ? criticalBand.bandReason(t, p, material, w, Double.NaN) : null);
    }

    private EquilibriumResult failed(double t, double p, PhaseCompetition competition, Feed material, Workspace w) {
        double seed = w.feedResult == null ? Double.NaN : w.seedDistance;
        boolean band = criticalBand.inBand(t, p, material) || w.tieLine < BAND_TIE_LINE || seed < BAND_STATIONARY_DISTANCE;
        String detail = band ? EquilibriumResult.CRITICAL_BAND + " (" + criticalBand.bandReason(t, p, material, w, seed) + "): " + w.failure : w.failure;
        return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition,
                detail, diagnostics(w, w.feedResult, band));
    }

    // The hydrocarbon core, at the workspace's pressure.

    /**
     * Stability test and, for an unstable feed, the split, of {@code material}, whose composition and present list it sets
     * first. Returns SINGLE, SPLIT, MERGED, THREE or FAILED.
     */
    int hydrocarbon(double t, Feed material, Workspace w) {
        double total = 0.0;
        for (int i = 0; i < count; i++) total += material.amounts[i];
        material.presentCount = 0;
        for (int i = 0; i < count; i++) {
            material.z[i] = material.amounts[i] / total;
            if (material.amounts[i] > 0.0) material.present[material.presentCount++] = i;
        }
        w.tieLine = Double.NaN;
        w.seedDistance = Double.NaN;
        w.residual = Double.NaN;
        w.productDistance = Double.NaN;
        w.ownSplit = false;
        w.carriedTemperature = t;
        TangentPlaneStability.Result feed = feedStability(stability, t, material, w);
        if (feed.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) {
            // A trial ran out of its budget without proving anything (P3 WP7d: next to a mixture critical point a trial can
            // crawl towards the trivial solution on a root whose own Hessian is indefinite): once more on the longer budget.
            feed = feedStability(patientStability, t, material, w);
        }
        w.feedResult = feed;
        switch (feed.verdict()) {
            case UNRESOLVED -> {
                w.failure = "the feed's stability test is unresolved (also with " + patientStability.settings().maximumIterations()
                        + " iterations per trial)";
                return FAILED;
            }
            case STABLE -> {
                w.singleRoot = feed.feedRoot();
                return SINGLE;
            }
            default -> {
                if (threeSlotFeed(material, w)) return threeSlotFlash.threeSlot(t, feed, material, w);
                w.standInHeld = false;
                int outcome = twoPhaseFlash.twoPhase(t, feed, material, w);
                if (!w.carrying) return outcome;
                if (outcome == SPLIT) return carried.carriedSplit(t, material, w);
                return outcome == FAILED && w.standInHeld && !w.beyondStandIn ? carried.beyondStandIn(t, feed, material, w) : outcome;
            }
        }
    }

    /** The feed's stability test at the workspace's pressure on {@code test}, counted. */
    private TangentPlaneStability.Result feedStability(TangentPlaneStability test, double t, Feed material, Workspace w) {
        w.carriedProduct = false;
        return stabilityOf(test, t, material.amounts, material, w);
    }

    /**
     * The stability test of {@code amounts} at the workspace's pressure, counted; filtered when carrying on the two-slot
     * path. A feed of the three-slot system is not filtered: a crystal's stand-in is a liquid of its own there.
     */
    TangentPlaneStability.Result stabilityOf(TangentPlaneStability test, double t, double[] amounts, Feed material, Workspace w) {
        TangentPlaneStability.Result result = w.carrying && !threeSlotFeed(material, w)
                ? test.test(t, w.pressure, amounts, w.carriedFilter, w.stability)
                : test.test(t, w.pressure, amounts, w.stability);
        w.kernelEvaluations += result.kernelEvaluations();
        w.derivativeEvaluations += result.derivativeEvaluations();
        return result;
    }

    // W3a D3: three fluid phases as one system, for a feed of a derived list holding a polar compound.

    /**
     * Whether an unstable feed takes the three-slot system: water in the equation of state and a polar compound present.
     * Every other feed keeps the two-slot path bit for bit. Under {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS} such a feed answers its fluid-only equilibrium, unfiltered: a dense
     * product rich in a crystal's species is the crystal's stand-in in a liquid slot of its own, for every crystal alike
     * (W3b, option A; the merge of two dense products into one liquid was the one-liquid-slot workaround). Water under oil at
     * 263 K is gas, oil and a supercooled aqueous liquid; a merged liquid at an unstable composition is no equilibrium.
     */
    private boolean threeSlotFeed(Feed material, Workspace w) {
        if (!inEquationOfState || w.slots == null) return false;
        for (int a = 0; a < material.presentCount; a++) if (polar[material.present[a]]) return true;
        return false;
    }

    /**
     * Three fluid phases (W3a D3): {@link Classification#VAPOR_LIQUID_LIQUID}, the lightest (largest molar volume) the
     * vapour, then the two liquids lighter first, as {@link Classification#LIQUID_LIQUID} lists them. No liquid is named
     * aqueous: a threshold on the water fraction would flip.
     */
    private EquilibriumResult threePhaseResult(double t, double p, PhaseCompetition competition, Feed material, Workspace w) {
        Slots s = w.slots;
        int[] order = {0, 1, 2};
        for (int a = 0; a < 3; a++) {
            for (int b = a + 1; b < 3; b++) {
                if (s.states[order[b]].molarVolume() > s.states[order[a]].molarVolume()) {
                    int swap = order[a];
                    order[a] = order[b];
                    order[b] = swap;
                }
            }
        }
        List<PhaseAmounts> phases = new ArrayList<>(3);
        for (int a = 0; a < 3; a++) {
            int k = order[a];
            phases.add(new PhaseAmounts(a == 0 ? PhaseKind.VAPOR : PhaseKind.LIQUID, null, t, p, engine.expand(s.amounts[k]), s.states[k].copy()));
        }
        boolean band = criticalBand.inBand(t, p, material) || w.tieLine < BAND_TIE_LINE;
        return engine.converged(competition, Classification.VAPOR_LIQUID_LIQUID, phases, List.of(), diagnostics(w, w.feedResult, band),
                band ? criticalBand.bandReason(t, p, material, w, Double.NaN) : null);
    }
}
