package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.CONVERGED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.DEPOSITION_TOLERANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.DEPOSITION_TRACE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FLUID_ONLY_NOTE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.R;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.diagnostics;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.fluidChemicalPotential;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.holdsLiquid;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CrystalSet.Crystal;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Feed;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Settings;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;
import java.util.ArrayList;
import java.util.List;

/**
 * Crystals at TP of {@link FluidTpEquilibrium} (P4 stage 2a, generalised in P5; S01): the equilibrium of the competing
 * crystals with the fluid they leave. The fluid answer of the whole feed on the dry core ({@link DryCore#solveDry}),
 * each crystal tested against it by fugacity, the vapour-solid solve ({@link #depositFromVapour}) and the general solve
 * whose every iterate re-flashes the reduced feed ({@link #depositFromFluid}), two crystals alternating their single
 * solves ({@link #twoCrystals}) on the request less the partner's solid ({@link #reduceFeed}), the coverage of a crystal
 * competition's answer ({@link #solidCoverage}), the solids-assessed answer and the typed
 * {@link EquilibriumResult#CRYSTAL_PHASES} hold. The reduced feeds are the workspace's scratch feeds; the request's feed
 * stays as it is. It reads the engine's contract, evaluator, settings, converged coverage and crystal set, given once
 * at construction, and calls back into the engine only for {@code single} and the static answer inspectors and
 * diagnostics. Immutable; one instance per engine.
 */
final class CrystalEquilibrium {
    /** Two crystals at once (W3a D5): the sweeps of their single solves, and the move of a solid that ends them. */
    private static final int TWO_CRYSTAL_SWEEPS = 60;
    private static final double TWO_CRYSTAL_CHANGE = 1.0e-12;
    /** Newton steps of the gas-solid solve before NOT_CONVERGED. */
    private static final int DEPOSITION_ITERATIONS = 60;
    /** Re-flashes of the general crystal solve (P5) before NOT_CONVERGED. */
    private static final int CRYSTAL_ITERATIONS = 100;

    private final FluidTpEquilibrium engine;
    private final DryCore dryCore;
    private final PhaseContract contract;
    private final CubicPhaseEvaluator evaluator;
    private final Settings settings;
    private final int count;
    private final int basis;
    /** The coverage of a converged fluid-only answer (the engine's). */
    private final EquilibriumResult.Coverage convergedCoverage;
    private final CrystalSet crystalSet;

    CrystalEquilibrium(FluidTpEquilibrium engine, DryCore dryCore, PhaseContract contract, CubicPhaseEvaluator evaluator,
                       Settings settings, int count, int basis, EquilibriumResult.Coverage convergedCoverage,
                       CrystalSet crystalSet) {
        this.engine = engine;
        this.dryCore = dryCore;
        this.contract = contract;
        this.evaluator = evaluator;
        this.settings = settings;
        this.count = count;
        this.basis = basis;
        this.convergedCoverage = convergedCoverage;
        this.crystalSet = crystalSet;
    }

    /**
     * TP with competing crystals. The fluid answer of the whole feed first; each competing crystal whose
     * species is carried is tested against it, {@code D = (mu_i - mu_s)/(R T)}, the species' chemical potential in the
     * fluid answer (fugacity, equal in every phase) against the crystal's; a fluid answer whose split has a product
     * proved unstable gives its split's chemical potential as the test (below the crystal's triple point that third
     * phase is typically a subcooled liquid of the species, which the crystal replaces). No {@code D} above
     * {@link FluidTpEquilibrium#DEPOSITION_TOLERANCE}: the fluid answer, the solids assessed. Otherwise a pure feed of the species is its
     * crystal alone (except within the coexistence tolerance of its sublimation or melting point, undetermined as a
     * pure fluid's coexistence); a mixture's solid amount is solved so that the fluid the crystal leaves, re-flashed
     * with the stability test, has the crystal's chemical potential: {@link #depositFromVapour} first, and where that
     * does not leave one stable fluid phase on its root, {@link #depositFromFluid} (which answers beside one or, with water in the
     * equation of state, two liquids and a vapour); two crystals forming alternate in {@link #twoCrystals}; three crystals forming are the typed
     * {@link EquilibriumResult#CRYSTAL_PHASES} hold.
     */
    EquilibriumResult solveWithCrystals(double t, double p, PhaseCompetition competition, Workspace w) {
        EquilibriumResult fluid = dryCore.solveDry(t, p, competition, w.request, w);
        w.fluidEquilibria = 1;
        boolean threeFluid = !fluid.converged() && splitChemicalPotentialsUsable(fluid, w.request, w);
        if (!fluid.converged() && !threeFluid) return fluid;
        double rt = R * t;
        Crystal forming = null;
        int formingCount = 0;
        double drive = Double.NEGATIVE_INFINITY;
        double solidMu = Double.NaN;
        StringBuilder forces = new StringBuilder();
        for (int a = 0; a < w.activeCount; a++) {
            Crystal c = w.active[a];
            if (!(w.request.amounts[c.component()] > 0.0)) continue;
            double mu = c.anchored().chemicalPotential(t, p);
            double fluidMu = threeFluid ? w.liquidState.chemicalPotential(c.component()) : fluidChemicalPotential(fluid, c.component());
            double d = (fluidMu - mu) / rt;
            forces.append(forces.isEmpty() ? "" : ", ").append(c.id()).append(": (mu - mu_s)/RT = ").append(d);
            if (d > DEPOSITION_TOLERANCE) formingCount++;
            if (d > drive) {
                drive = d;
                forming = c;
                solidMu = mu;
            }
        }
        if (forming == null) return threeFluid ? fluid : assessed(fluid, "solids assessed: no competing crystal's species is carried", w);
        if (w.request.presentCount == 1) {
            // A pure feed of the crystal's species (never a three-phase fluid answer).
            if (fluid.phases().size() == 1) {
                PhaseState state = fluid.phases().get(0).state();
                PhaseState solid = forming.anchored().evaluate(t, p, w.solidState);
                double gap = Math.abs(state.compressibilityFactor() - p * solid.molarVolume() / rt);
                if (Math.abs(drive) <= settings.coexistenceTolerance() * gap) {
                    String point = fluid.phases().get(0).kind() == PhaseKind.LIQUID ? "melting" : "sublimation";
                    return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, fluid.detail()
                            + "; pure " + point + " point of " + forming.id() + " (" + forces + "): the split is undetermined by T and P",
                            contract.identity(), competition, Classification.PURE_COEXISTENCE_UNDERDETERMINED, List.of(),
                            List.of(state, solid.copy()), solidCoverage(fluid.coverage(), w), fluid.diagnostics(), null);
                }
            }
            if (!(drive > DEPOSITION_TOLERANCE)) return assessed(fluid, "solids assessed: no crystal forms (" + forces + ")", w);
            boolean liquid = holdsLiquid(fluid);
            PhaseAmounts solid = new PhaseAmounts(PhaseKind.SOLID, forming.id(), t, p, w.request.basisAmounts,
                    forming.anchored().evaluate(t, p, w.solidState).copy(), null);
            var deposition = new EquilibriumResult.Deposition(forming.id(), evaluator.components().get(forming.component()), drive,
                    Double.NaN, w.request.amounts[forming.component()], true, 0, w.fluidEquilibria);
            return solidAnswer(competition, Classification.SOLID_PRESENT, List.of(solid), fluid, deposition,
                    (liquid ? "complete freezing: the pure " : "complete deposition: the pure ") + evaluator.components().get(forming.component())
                            + " is its crystal " + forming.id() + " (" + forces + "), the fluid vanishes", w);
        }
        if (!(drive > DEPOSITION_TOLERANCE)) {
            return threeFluid ? fluid : assessed(fluid, "solids assessed: no crystal forms (" + forces + ")", w);
        }
        Crystal second = null;
        if (formingCount > 1) {
            if (formingCount > 2) return crystalPhasesHold(fluid, "three crystals form (" + forces + ")", competition, w);
            for (int a = 0; a < w.activeCount; a++) {
                Crystal c = w.active[a];
                if (c != forming && w.request.amounts[c.component()] > 0.0
                        && (fluidMuOf(fluid, threeFluid, c.component(), w) - c.anchored().chemicalPotential(t, p)) / rt > DEPOSITION_TOLERANCE) {
                    second = c;
                }
            }
        }
        // The solves below flash reduced feeds in scratch feeds of their own; the request's feed stays as it is.
        w.secondForming = null;
        try {
            if (second == null) {
                EquilibriumResult answer = threeFluid ? null
                        : depositFromVapour(fluid, forming, drive, solidMu, forces, t, p, competition, w.request, w);
                if (answer == null) {
                    answer = depositFromFluid(fluid, threeFluid, forming, drive, solidMu, forces, t, p, competition, w.request, w);
                }
                if (w.secondForming == null) return answer;
                second = w.secondForming;
            }
            return twoCrystals(forming, second, forces, t, p, competition, w);
        } finally {
            w.secondForming = null;
            w.paired = null;
        }
    }

    /**
     * Whether a fluid answer that is not converged is a split whose product the stability re-check proved unstable (a
     * third fluid phase indicated, {@link EquilibriumResult#UNSTABLE_PRODUCT}; also where a short tie line types it
     * {@link EquilibriumResult#CRITICAL_BAND}), whose two phases are still in the workspace with equal chemical potentials
     * (to 1e-6 RT): the split's chemical potentials then test and steer the crystals (P5). They are never an answer: an
     * answer needs a converged, stability-tested fluid the crystal leaves. {@code fluid} is the answer of the last dry
     * solve on {@code w} (each caller calls this right after {@code solveDry}), and the two phases must be that solve's
     * own split ({@code Workspace.ownSplit}): a three-slot audit failure writes no split, and a pair an earlier solve or
     * request left there is never judged. {@code material} is the feed {@code fluid} answers: its present list is checked.
     */
    private boolean splitChemicalPotentialsUsable(EquilibriumResult fluid, Feed material, Workspace w) {
        if (fluid.status() != EquilibriumResult.Status.NOT_CONVERGED) return false;
        String detail = fluid.detail();
        boolean unstableProduct = detail.startsWith(EquilibriumResult.UNSTABLE_PRODUCT)
                || detail.startsWith(EquilibriumResult.CRITICAL_BAND) && detail.contains("): " + EquilibriumResult.UNSTABLE_PRODUCT);
        if (!unstableProduct) return false;
        if (!w.ownSplit) return false;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            if (!(w.liquidState.moleFraction(i) > 0.0) || !(w.vaporState.moleFraction(i) > 0.0)) return false;
            double difference = (w.liquidState.chemicalPotential(i) - w.vaporState.chemicalPotential(i)) / (R * w.liquidState.temperature());
            if (!(Math.abs(difference) <= 1.0e-6)) return false;
        }
        return true;
    }

    /**
     * The vapour-solid solve, the first attempt of a mixture: the solid amount is the one unknown. With {@code r} the
     * species' amount left in the fluid and {@code s = ln(r/z_i)} in {@code (-infinity, 0]}, the fluid holds
     * {@code z - (z_i - r) e_i} and {@code f(s) = (mu_i^V - mu_s)/(R T)} on the reduced feed's vapour root increases with
     * {@code s}. Newton on {@code s} with {@code f' = 1 - y_i + y_i Phi_ii}, bracketed (bisection when a step leaves the
     * bracket), to {@code |f| <= }{@link FluidTpEquilibrium#DEPOSITION_TOLERANCE}; below the trace floor ({@code y_i = }{@link FluidTpEquilibrium#DEPOSITION_TRACE})
     * with {@code f} still positive the deposition is complete. The reduced feed is then re-flashed with the stability
     * test; the answer is returned only when that fluid is one phase meeting the tolerance on its own root
     * ({@link Classification#LIQUID_SOLID} for a liquid-like one-root fluid, else {@link Classification#VAPOR_SOLID});
     * every other outcome returns {@code null} and {@link #depositFromFluid} decides. A second crystal forming is the
     * typed hold. The smaller of the solid and the fluid's share is computed directly ({@code -z_i expm1(s)} or
     * {@code z_i exp(s)}), the larger by difference, so the species is conserved to rounding.
     *
     * @param material the feed the crystal forms from (the request's, or {@link #twoCrystals}' feed less the partner's
     *                 solid); the reduced feed is built in {@code w.reducedFeed}
     */
    private EquilibriumResult depositFromVapour(EquilibriumResult fluid, Crystal c, double drive, double solidMu, CharSequence forces,
                                                double t, double p, PhaseCompetition competition, Feed material, Workspace w) {
        int i = c.component();
        int slot = c.slot();
        String species = evaluator.components().get(i);
        double z = material.amounts[i];
        double others = 0.0;
        for (int j = 0; j < count; j++) if (j != i) others += material.amounts[j];
        w.reducedFeed.takeAmounts(material);
        Root root = Root.VAPOR;
        double floor = DeterministicMath.log(others * (DEPOSITION_TRACE / (1.0 - DEPOSITION_TRACE)) / z);
        double s = 0.0;
        double f = depositionResidual(t, p, c, z, s, solidMu, root, w);
        boolean liquid = holdsLiquid(fluid) || fluid.phases().size() != 1;
        // The feed as one vapour is not supersaturated: the crystal forms out of the answer's liquid (the general solve).
        if (!(f > DEPOSITION_TOLERANCE)) return null;
        double slope = w.reducedSlope;
        double high = 0.0;
        double low = Double.NEGATIVE_INFINITY;
        int iterations = 0;
        boolean complete = false;
        while (true) {
            if (iterations > 0 && Math.abs(f) <= DEPOSITION_TOLERANCE) break;
            if (iterations >= DEPOSITION_ITERATIONS) return null;
            if (f > 0.0) high = s; else low = s;
            double next = s - f / slope;
            boolean bracketed = low > Double.NEGATIVE_INFINITY;
            if (!(slope > 0.0) || !Double.isFinite(next) || next >= high || bracketed && next <= low) {
                next = bracketed ? 0.5 * (low + high) : s - Math.max(1.0, Math.abs(f));
            }
            if (next < floor) {
                if (s == floor) {
                    complete = true;
                    break;
                }
                next = floor;
            }
            s = next;
            f = depositionResidual(t, p, c, z, s, solidMu, root, w);
            slope = w.reducedSlope;
            iterations++;
            if (s == floor && f > 0.0) {
                complete = true;
                break;
            }
        }
        double solid;
        double vapour;
        if (complete) {
            solid = z;
            vapour = 0.0;
        } else {
            double deposit = -z * DeterministicMath.expm1(s);
            double left = z * DeterministicMath.exp(s);
            if (deposit <= left) {
                solid = deposit;
                vapour = z - solid;
            } else {
                vapour = left;
                solid = z - vapour;
            }
            if (!(solid > 0.0)) {
                if (liquid) return null;
                return assessed(fluid, "solids assessed: " + c.id() + " at its onset (the deposit rounds to zero)", w);
            }
        }
        // The re-flash of the reduced feed: the fluid the crystal leaves, by the stability test.
        w.reducedFeed.amounts[i] = vapour;
        w.reducedFeed.basisAmounts[slot] = vapour;
        EquilibriumResult reduced = dryCore.solveDry(t, p, competition, w.reducedFeed, w);
        w.fluidEquilibria++;
        if (!reduced.converged() || reduced.phases().size() != 1) return null;
        double residual = Double.NaN;
        double rt = R * t;
        if (!complete) {
            residual = Math.abs((reduced.phases().get(0).state().chemicalPotential(i) - solidMu) / rt);
            // A liquid on the liquid root of three (the vapour root was solved), or a vapour that disagrees: the general solve.
            if (!(residual <= DEPOSITION_TOLERANCE)) return null;
        } else if (holdsLiquid(reduced)) {
            return null;
        }
        EquilibriumResult held = secondCrystal(reduced, c, t, p, competition, w.reducedFeed, w);
        if (held != null) return held;
        PhaseAmounts fluidPhase = reduced.phases().get(0);
        boolean liquidLike = fluidPhase.kind() == PhaseKind.LIQUID;
        double[] solidAmounts = new double[basis];
        solidAmounts[slot] = solid;
        PhaseAmounts crystal = new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, solidAmounts, c.anchored().evaluate(t, p, w.solidState).copy(), null);
        var deposition = new EquilibriumResult.Deposition(c.id(), species, drive, residual, solid, complete,
                iterations, w.fluidEquilibria);
        String note = complete
                ? "complete deposition of " + species + " as " + c.id() + ": at the trace limit y = " + DEPOSITION_TRACE
                        + " the vapour is still supersaturated; the fluid is the feed without " + species
                : c.id() + (liquidLike ? " freezes " + solid + " mol of " + species + " out of the dense one-root fluid"
                        : " deposits " + solid + " mol of " + species + " from the vapour") + " (feed (mu - mu_s)/RT = " + drive
                        + ", |mu_i - mu_s|/RT = " + residual + " after " + iterations + " Newton steps)";
        if (liquidLike) {
            note += "; the fluid left is one " + reduced.classification() + " liquid-like by its phase identification parameter"
                    + " (a dense one-root fluid carrying the crystal's species, P5): its crystal is answered beside it";
        }
        if (liquid) {
            note += "; the fluid-only answer of the feed was " + fluid.classification() + ": its liquid is metastable against "
                    + c.id() + " (the fluid left by the deposition is a stable single phase)";
        }
        return solidAnswer(competition, liquidLike ? Classification.LIQUID_SOLID : Classification.VAPOR_SOLID,
                List.of(fluidPhase, crystal), reduced, deposition, note, w);
    }

    /**
     * The general crystal solve: the solid amount of crystal {@code c} out of a mixture whose fluid the crystal leaves
     * may be a vapour, a liquid or a vapour-liquid split. The unknown is {@code s = ln(r/z_i)} in {@code (-infinity, 0]};
     * every iterate re-flashes the reduced feed {@code z - (z_i - r) e_i} with the stability test, and
     * {@code f(s) = (mu_i - mu_s)/(R T)} of that fluid answer is nondecreasing in {@code s} (a binary's vapour-liquid
     * split holds it constant, where the step goes on), with {@code f(0) = D > 0}. Steps: Newton with
     * {@code f' = 1 - y_i + y_i Phi_ii} on a one-phase fluid's root, a secant otherwise, {@code s - max(1, |f|)} before a
     * bracket exists, bisection when a step leaves the bracket or two steps fail to halve {@code |f|}; at most
     * {@link #CRYSTAL_ITERATIONS} re-flashes. Converged on a converged fluid answer each of whose phases has
     * {@code |mu_i - mu_s|/(R T) <= }{@link FluidTpEquilibrium#DEPOSITION_TOLERANCE} (a split's phases agree on {@code mu_i} only to the
     * flash's fugacity tolerance: stopped on its first phase alone, the search left the other outside and the answer was
     * refused, LNG at 140.9 K and 620 kPa freezing CO2). A reduced fluid whose split has a product proved unstable steers
     * the search but is never an answer. Below the trace floor with {@code f} still positive the deposition is complete.
     * The answer: the fluid's phases and the crystal ({@link Classification#VAPOR_SOLID},
     * {@link Classification#LIQUID_SOLID}, {@link Classification#VAPOR_LIQUID_SOLID}, {@link Classification#LIQUID_LIQUID_SOLID}
     * or {@link Classification#VAPOR_LIQUID_LIQUID_SOLID}: the crystal's species meets it in every fluid phase, two liquids
     * included, W3b step 1, with water in the equation of state; on a contract without it two liquids or three fluid
     * phases are the typed hold); a fluid with a product proved unstable whose bracket closes is the typed
     * {@link EquilibriumResult#CRYSTAL_PHASES} hold, a search that does not converge {@code NOT_CONVERGED}.
     * The solid is {@code -z_i expm1(s)} and the fluid carries {@code z_i e^s}, so the species is conserved to rounding.
     *
     * @param material the feed the crystal forms from, as in {@link #depositFromVapour}; {@code fluid} is its answer
     */
    private EquilibriumResult depositFromFluid(EquilibriumResult fluid, boolean threeFluid, Crystal c, double drive, double solidMu,
                                               CharSequence forces, double t, double p, PhaseCompetition competition, Feed material,
                                               Workspace w) {
        int i = c.component();
        int slot = c.slot();
        String species = evaluator.components().get(i);
        double z = material.amounts[i];
        double others = 0.0;
        for (int j = 0; j < count; j++) if (j != i) others += material.amounts[j];
        w.reducedFeed.takeAmounts(material);
        double rt = R * t;
        double floor = DeterministicMath.log(others * (DEPOSITION_TRACE / (1.0 - DEPOSITION_TRACE)) / z);
        double s = 0.0;
        double f = drive;
        EquilibriumResult reduced = threeFluid ? null : fluid;
        double slope = reduced != null && reduced.phases().size() == 1 ? singlePhaseSlope(t, p, reduced, i, material, w) : Double.NaN;
        double high = 0.0;
        double low = Double.NEGATIVE_INFINITY;
        double previousS = Double.NaN;
        double previousF = Double.NaN;
        double best = Math.abs(f);
        // The largest |mu_i - mu_s|/RT over the phases of the last converged re-flash: the convergence test.
        double worst = Double.NaN;
        int stalls = 0;
        int iterations = 0;
        boolean complete = false;
        while (true) {
            if (reduced != null && iterations > 0 && worst <= DEPOSITION_TOLERANCE) break;
            if (iterations >= CRYSTAL_ITERATIONS) {
                return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the crystal solve of " + c.id()
                        + " did not converge in " + iterations + " re-flashes (|mu_i - mu_s|/RT = " + Math.abs(f) + ", the fluid "
                        + (reduced == null ? "a split with a product proved unstable" : reduced.classification()) + ")",
                        diagnostics(w, w.feedResult, false));
            }
            boolean bracketed = low > Double.NEGATIVE_INFINITY;
            if (bracketed && high - low <= 1.0e-14 * Math.max(1.0, Math.abs(low))) {
                // The bracket closed on a jump of f: no fluid the engine answers takes the crystal's chemical potential here.
                if (reduced == null) {
                    return crystalPhasesHold(fluid, c.id() + " forms and the fluid it leaves has three fluid phases (" + forces + ")", competition, w);
                }
                return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the crystal solve of " + c.id()
                        + " closed its bracket on a jump of the chemical potential (|mu_i - mu_s|/RT = " + Math.abs(f) + ", the fluid "
                        + reduced.classification() + ")", diagnostics(w, w.feedResult, false));
            }
            double proposal = Double.NaN;
            if (slope > 0.0 && Double.isFinite(slope)) proposal = s - f / slope;
            if (!Double.isFinite(proposal) && Double.isFinite(previousS) && previousS != s) {
                double secant = (f - previousF) / (s - previousS);
                if (secant > 0.0 && Double.isFinite(secant)) proposal = s - f / secant;
            }
            double next;
            if (bracketed) {
                boolean bisect = stalls >= 2 || !(proposal > low && proposal < high);
                next = bisect ? 0.5 * (low + high) : proposal;
                if (bisect) stalls = 0;
            } else {
                next = proposal < s && Double.isFinite(proposal) ? proposal : s - Math.max(1.0, Math.abs(f));
            }
            if (next < floor) {
                if (s == floor) {
                    complete = true;
                    break;
                }
                next = floor;
            }
            previousS = s;
            previousF = f;
            s = next;
            double left = z * DeterministicMath.exp(s);
            w.reducedFeed.amounts[i] = left;
            w.reducedFeed.basisAmounts[slot] = left;
            EquilibriumResult answer = dryCore.solveDry(t, p, competition, w.reducedFeed, w);
            w.fluidEquilibria++;
            iterations++;
            if (answer.converged()) {
                reduced = answer;
                f = (fluidChemicalPotential(answer, i) - solidMu) / rt;
                worst = Math.abs(f);
                for (PhaseAmounts phase : answer.phases()) {
                    worst = Math.max(worst, Math.abs((phase.state().chemicalPotential(i) - solidMu) / rt));
                }
                slope = answer.phases().size() == 1 ? singlePhaseSlope(t, p, answer, i, w.reducedFeed, w) : Double.NaN;
            } else if (splitChemicalPotentialsUsable(answer, w.reducedFeed, w)) {
                reduced = null;
                f = (w.liquidState.chemicalPotential(i) - solidMu) / rt;
                worst = Double.NaN;
                slope = Double.NaN;
            } else {
                return answer;
            }
            if (Math.abs(f) <= 0.5 * best) stalls = 0; else stalls++;
            best = Math.min(best, Math.abs(f));
            if (f > 0.0) high = s; else low = s;
            if (s == floor && f > 0.0) {
                complete = true;
                break;
            }
        }
        double solid;
        if (complete) {
            solid = z;
            w.reducedFeed.amounts[i] = 0.0;
            w.reducedFeed.basisAmounts[slot] = 0.0;
            reduced = dryCore.solveDry(t, p, competition, w.reducedFeed, w);
            w.fluidEquilibria++;
            if (!reduced.converged()) return reduced;
        } else {
            solid = -z * DeterministicMath.expm1(s);
            if (!(solid > 0.0)) {
                EquilibriumResult base = fluid.converged() ? fluid : reduced;
                if (base == null) return fluid;
                return assessed(base, "solids assessed: " + c.id() + " at its onset (the deposit rounds to zero)", w);
            }
        }
        Classification classification = switch (reduced.classification()) {
            case VAPOR_LIQUID -> Classification.VAPOR_LIQUID_SOLID;
            case LIQUID_LIQUID -> Classification.LIQUID_LIQUID_SOLID;
            case VAPOR_LIQUID_LIQUID -> Classification.VAPOR_LIQUID_LIQUID_SOLID;
            case SINGLE_VAPOR, SINGLE_LIQUID, SINGLE_FLUID, SUPERCRITICAL_FLUID ->
                    reduced.phases().get(0).kind() == PhaseKind.LIQUID ? Classification.LIQUID_SOLID : Classification.VAPOR_SOLID;
            default -> null;
        };
        // A contract without water in the equation of state answers one liquid: two liquids beside a crystal stay its typed hold
        // (a package's own engine, outside the network since W3b); with water in the equation of state they are the answer.
        if (contract.water() != WaterParticipation.IN_EQUATION_OF_STATE) {
            if (reduced.classification() == Classification.LIQUID_LIQUID) {
                return crystalPhasesHold(reduced, "the fluid left by the crystal " + c.id() + " is two liquids (" + forces + ")", competition, w);
            }
            if (reduced.classification() == Classification.VAPOR_LIQUID_LIQUID) {
                return crystalPhasesHold(reduced, c.id() + " forms and the fluid it leaves has three fluid phases (" + forces + ")", competition, w);
            }
        }
        if (classification == null) {
            return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the fluid left by the crystal "
                    + c.id() + " is " + reduced.classification() + ", which a crystal answer does not carry", diagnostics(w, w.feedResult,
                    reduced.diagnostics().criticalBand()));
        }
        // Every fluid phase meets the crystal: the convergence test read them all.
        double residual = complete ? Double.NaN : worst;
        EquilibriumResult held = secondCrystal(reduced, c, t, p, competition, w.reducedFeed, w);
        if (held != null) return held;
        double[] solidAmounts = new double[basis];
        solidAmounts[slot] = solid;
        PhaseAmounts crystal = new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, solidAmounts, c.anchored().evaluate(t, p, w.solidState).copy(), null);
        List<PhaseAmounts> phases = new ArrayList<>(reduced.phases());
        phases.add(crystal);
        var deposition = new EquilibriumResult.Deposition(c.id(), species, drive, residual, solid, complete,
                iterations, w.fluidEquilibria);
        String out = switch (classification) {
            case LIQUID_SOLID -> "freezes " + solid + " mol of " + species + " out of the liquid";
            case VAPOR_LIQUID_SOLID -> "forms " + solid + " mol of " + species + " beside a vapour and a liquid";
            case LIQUID_LIQUID_SOLID -> "freezes " + solid + " mol of " + species + " beside two liquids";
            case VAPOR_LIQUID_LIQUID_SOLID -> "forms " + solid + " mol of " + species + " beside a vapour and two liquids";
            default -> "deposits " + solid + " mol of " + species + " from the vapour";
        };
        String note = complete
                ? "complete " + (classification == Classification.VAPOR_SOLID ? "deposition" : "freezing") + " of " + species + " as "
                        + c.id() + ": at the trace limit y = " + DEPOSITION_TRACE + " the fluid is still supersaturated; the fluid is the"
                        + " feed without " + species
                : c.id() + " " + out + " (feed (mu - mu_s)/RT = " + drive + ", |mu_i - mu_s|/RT = " + residual + " after "
                        + iterations + " re-flashes of the reduced feed)";
        if (!fluid.converged()) {
            note += "; the fluid-only answer of the feed had a product proved unstable (a third fluid phase): the crystal replaces it";
        } else if (fluid.classification() != reduced.classification()) {
            note += "; the fluid-only answer of the feed was " + fluid.classification();
        }
        return solidAnswer(competition, classification, phases, reduced, deposition, note, w);
    }

    /** {@code mu_i} of a fluid answer, or of the workspace's split when that answer had a product proved unstable. */
    private static double fluidMuOf(EquilibriumResult fluid, boolean threeFluid, int component, Workspace w) {
        return threeFluid ? w.liquidState.chemicalPotential(component) : fluidChemicalPotential(fluid, component);
    }

    /**
     * Two crystals at once (W3a D5, owner decision 6 A: ice and another crystal coexist): each crystal's solid amount
     * from its single solve ({@link #depositFromVapour}, then {@link #depositFromFluid}) on the feed less the other's
     * solid, alternated until neither amount moves by more than {@link #TWO_CRYSTAL_CHANGE} of its species' feed (the
     * coupling is the fluid's dilution only, so a sweep contracts by about the second species' share of the fluid), at
     * most {@link #TWO_CRYSTAL_SWEEPS} sweeps. The answer: the last solve's fluid and crystal with the first crystal
     * beside them, each crystal's species at its chemical potential within ten times {@link FluidTpEquilibrium#DEPOSITION_TOLERANCE}; a
     * fluid that both species leave entirely is the two crystals alone ({@link Classification#SOLID_PRESENT}). Each single
     * solve starts from {@code w.pairFeed} ({@link #reduceFeed}); the request's feed stays as it is.
     */
    private EquilibriumResult twoCrystals(Crystal first, Crystal second, CharSequence forces, double t, double p,
                                          PhaseCompetition competition, Workspace w) {
        Crystal[] pair = {first, second};
        double[] solid = new double[2];
        EquilibriumResult[] answers = new EquilibriumResult[2];
        double rt = R * t;
        int sweeps = 0;
        boolean converged = false;
        while (!converged && sweeps < TWO_CRYSTAL_SWEEPS) {
            sweeps++;
            double change = 0.0;
            for (int a = 0; a < 2; a++) {
                Crystal c = pair[a];
                Crystal other = pair[1 - a];
                reduceFeed(other, solid[1 - a], w);
                double z = w.pairFeed.amounts[c.component()];
                double mu = c.anchored().chemicalPotential(t, p);
                double amount;
                if (w.pairFeed.presentCount == 1) {
                    // The other species left entirely: this one alone, its crystal where the fluid is supersaturated.
                    EquilibriumResult pure = dryCore.solveDry(t, p, competition, w.pairFeed, w);
                    w.fluidEquilibria++;
                    if (!pure.converged()) return pure;
                    amount = (fluidChemicalPotential(pure, c.component()) - mu) / rt > DEPOSITION_TOLERANCE ? z : 0.0;
                    answers[a] = null;
                } else {
                    EquilibriumResult base = dryCore.solveDry(t, p, competition, w.pairFeed, w);
                    w.fluidEquilibria++;
                    boolean threeFluid = !base.converged() && splitChemicalPotentialsUsable(base, w.pairFeed, w);
                    if (!base.converged() && !threeFluid) return base;
                    double d = (fluidMuOf(base, threeFluid, c.component(), w) - mu) / rt;
                    if (!(d > DEPOSITION_TOLERANCE)) {
                        amount = 0.0;
                        answers[a] = base;
                    } else {
                        w.paired = other;
                        EquilibriumResult r = threeFluid ? null
                                : depositFromVapour(base, c, d, mu, forces, t, p, competition, w.pairFeed, w);
                        if (r == null) r = depositFromFluid(base, threeFluid, c, d, mu, forces, t, p, competition, w.pairFeed, w);
                        w.paired = null;
                        if (!r.converged()) return r;
                        amount = r.deposition() == null ? 0.0 : r.deposition().solidAmount();
                        answers[a] = r;
                    }
                }
                change = Math.max(change, Math.abs(amount - solid[a]) / w.request.amounts[c.component()]);
                solid[a] = amount;
            }
            converged = change <= TWO_CRYSTAL_CHANGE;
        }
        if (!converged) {
            return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the two crystals " + first.id()
                    + " and " + second.id() + " did not converge in " + sweeps + " sweeps (solids " + solid[0] + " and " + solid[1] + " mol)",
                    diagnostics(w, w.feedResult, false));
        }
        String note = "two crystals: " + first.id() + " " + solid[0] + " mol and " + second.id() + " " + solid[1] + " mol after " + sweeps
                + " sweeps of their single solves";
        int carried = 0;
        for (int i = 0; i < count; i++) if (w.request.amounts[i] > 0.0) carried++;
        // A feed of the two species alone: with both crystals forming no fluid keeps them at fixed T and P (the phase rule
        // leaves a fluid beside two crystals of a binary one pressure per temperature), so both deposit entirely.
        boolean alone = carried == 2 && solid[0] > 0.0 && solid[1] > 0.0;
        if (alone) {
            solid[0] = w.request.amounts[first.component()];
            solid[1] = w.request.amounts[second.component()];
        }
        if (alone || answers[1] == null && solid[0] == w.request.amounts[first.component()]
                && solid[1] == w.request.amounts[second.component()]) {
            List<PhaseAmounts> phases = new ArrayList<>(2);
            for (int a = 0; a < 2; a++) {
                Crystal c = pair[a];
                phases.add(new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, engine.single(c.slot(), solid[a]), c.anchored().evaluate(t, p, w.solidState).copy(),
                        null));
            }
            var deposition = new EquilibriumResult.Deposition(second.id(), evaluator.components().get(second.component()), Double.NaN,
                    Double.NaN, solid[1], true, sweeps, w.fluidEquilibria);
            return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note
                    + "; the fluid vanishes", contract.identity(), competition, Classification.SOLID_PRESENT, phases, List.of(),
                    solidCoverage(convergedCoverage, w), diagnostics(w, w.feedResult, false), deposition);
        }
        EquilibriumResult last = answers[1];
        if (last == null || !last.converged()) {
            return crystalPhasesHold(answers[0] != null ? answers[0] : last != null ? last : EquilibriumResult.notConverged(Specification.TP,
                    contract.identity(), competition, "two crystals", diagnostics(w, w.feedResult, false)),
                    "two crystals form and the fluid they leave is not one the engine answers (" + forces + ")", competition, w);
        }
        if (solid[0] == 0.0 || solid[1] == 0.0) {
            // One of the two does not form beside the other: the single answer that holds the one that does.
            EquilibriumResult one = solid[1] > 0.0 || answers[0] == null ? last : answers[0];
            return one;
        }
        // The first crystal's species on the last fluid: at its chemical potential within the tolerance.
        double mu = first.anchored().chemicalPotential(t, p);
        double worst = 0.0;
        for (PhaseAmounts phase : last.phases()) {
            if (phase.kind() == PhaseKind.SOLID) continue;
            worst = Math.max(worst, Math.abs((phase.state().chemicalPotential(first.component()) - mu) / rt));
        }
        boolean firstGone = solid[0] == w.request.amounts[first.component()];
        if (!firstGone && !(worst <= 10.0 * DEPOSITION_TOLERANCE)) {
            return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, "the two crystals " + first.id()
                    + " and " + second.id() + " left the first off its chemical potential (|mu_i - mu_s|/RT = " + worst + ")",
                    diagnostics(w, w.feedResult, false));
        }
        List<PhaseAmounts> phases = new ArrayList<>(last.phases());
        phases.add(new PhaseAmounts(PhaseKind.SOLID, first.id(), t, p, engine.single(first.slot(), solid[0]), first.anchored().evaluate(t, p, w.solidState).copy(),
                null));
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, last.detail() + "; " + note,
                contract.identity(), competition, last.classification(), phases, List.of(), last.coverage(), last.diagnostics(),
                last.deposition());
    }

    /**
     * The request's feed less {@code amount} of crystal {@code c}'s species into {@code w.pairFeed}, with its composition and
     * present list as the hydrocarbon core computes them.
     */
    private void reduceFeed(Crystal c, double amount, Workspace w) {
        Feed pair = w.pairFeed;
        pair.takeAmounts(w.request);
        if (amount > 0.0) {
            double left = amount >= w.request.amounts[c.component()] ? 0.0 : w.request.amounts[c.component()] - amount;
            pair.amounts[c.component()] = left;
            pair.basisAmounts[c.slot()] = left;
        }
        double total = 0.0;
        for (int i = 0; i < count; i++) total += pair.amounts[i];
        pair.presentCount = 0;
        for (int i = 0; i < count; i++) {
            pair.z[i] = pair.amounts[i] / total;
            if (pair.amounts[i] > 0.0) pair.present[pair.presentCount++] = i;
        }
    }

    /** {@code df/ds} of the crystal solve on a one-phase fluid answer of {@code material}: {@code 1 - y_i + y_i Phi_ii}. */
    private double singlePhaseSlope(double t, double p, EquilibriumResult reduced, int i, Feed material, Workspace w) {
        try {
            PhaseDerivatives d = evaluator.derivatives(t, p, material.amounts, reduced.phases().get(0).state().root(), w.phases, w.reduced);
            w.kernelEvaluations++;
            w.derivativeEvaluations++;
            double y = d.state().moleFraction(i);
            return 1.0 - y + y * d.logFugacityCompositionDerivative(i, i);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return Double.NaN;
        }
    }

    /**
     * A second competing crystal that forms against the fluid a crystal leaves ({@code reduced}, the answer of
     * {@code material}): the typed hold, or null.
     */
    private EquilibriumResult secondCrystal(EquilibriumResult reduced, Crystal c, double t, double p, PhaseCompetition competition,
                                            Feed material, Workspace w) {
        double rt = R * t;
        for (int a = 0; a < w.activeCount; a++) {
            Crystal other = w.active[a];
            if (other == c || other == w.paired || !(material.amounts[other.component()] > 0.0)) continue;
            double d = (fluidChemicalPotential(reduced, other.component()) - other.anchored().chemicalPotential(t, p)) / rt;
            if (d > DEPOSITION_TOLERANCE) {
                // Owner decision 6 A: ice and another crystal coexist; the caller solves the two at once.
                w.secondForming = other;
                return crystalPhasesHold(reduced, "a second crystal " + other.id() + " forms beside " + c.id(), competition, w);
            }
        }
        return null;
    }

    /**
     * {@code f(s)} of the vapour-solid solve at {@code r = z e^s} (the reduced feed set into {@code w.reducedFeed}), from the
     * fluid evaluator's derivatives on {@code root}; the slope {@code 1 - y_i + y_i Phi_ii} into {@code w.reducedSlope}.
     */
    private double depositionResidual(double t, double p, Crystal c, double z, double s, double solidMu, Root root,
                                      Workspace w) {
        int i = c.component();
        double r = s == 0.0 ? z : z * DeterministicMath.exp(s);
        w.reducedFeed.amounts[i] = r;
        PhaseDerivatives d = evaluator.derivatives(t, p, w.reducedFeed.amounts, root, w.phases, w.reduced);
        w.kernelEvaluations++;
        w.derivativeEvaluations++;
        PhaseState state = d.state();
        double y = state.moleFraction(i);
        w.reducedSlope = 1.0 - y + y * d.logFugacityCompositionDerivative(i, i);
        return (state.chemicalPotential(i) - solidMu) / (R * t);
    }

    /**
     * The coverage of a crystal competition's answer: the fluid answer's grade folded with each competing crystal's
     * (the worse of them), the fluid evidence without the fluid-only note, and each crystal's record line (its revision,
     * grade and evidence, which names the declared errors of D16 for the CO2-I crystal).
     */
    EquilibriumResult.Coverage solidCoverage(EquilibriumResult.Coverage fluid, Workspace w) {
        if (fluid == convergedCoverage) {
            EquilibriumResult.Coverage cached = crystalSet.crystalCoverages.get(w.competition);
            if (cached != null) return cached;
        }
        List<Crystal> set = new ArrayList<>(w.activeCount);
        for (int a = 0; a < w.activeCount; a++) set.add(w.active[a]);
        return solidCoverage(fluid, w.competition, set);
    }

    static EquilibriumResult.Coverage solidCoverage(EquilibriumResult.Coverage fluid, PhaseCompetition competition,
                                                    List<Crystal> set) {
        String base = fluid.evidence();
        if (base.endsWith(FLUID_ONLY_NOTE)) base = base.substring(0, base.length() - FLUID_ONLY_NOTE.length());
        EquilibriumResult.CoverageGrade grade = fluid.grade();
        StringBuilder evidence = new StringBuilder(base).append("; solids assessed (").append(competition).append(')');
        for (int a = 0; a < set.size(); a++) {
            Crystal c = set.get(a);
            if (c.grade().ordinal() > grade.ordinal()) grade = c.grade();
            evidence.append(a == 0 ? ": " : "; ").append(c.evidence());
        }
        return new EquilibriumResult.Coverage(grade, evidence.toString());
    }

    /** The fluid answer under a crystal competition, no crystal among its phases: the solids assessed. */
    EquilibriumResult assessed(EquilibriumResult fluid, String note, Workspace w) {
        return new EquilibriumResult(fluid.specification(), EquilibriumResult.Status.CONVERGED, null, fluid.detail() + "; " + note,
                fluid.identity(), fluid.competition(), fluid.classification(), fluid.phases(), fluid.coexisting(),
                solidCoverage(fluid.coverage(), w), fluid.diagnostics(), null);
    }

    /** An answer with a crystal among its phases: the fluid answer's grade and band folded with the crystals'. */
    private EquilibriumResult solidAnswer(PhaseCompetition competition, Classification classification, List<PhaseAmounts> phases,
                                          EquilibriumResult fluid, EquilibriumResult.Deposition deposition, String note, Workspace w) {
        boolean band = fluid.diagnostics().criticalBand();
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, fluid.detail() + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), solidCoverage(fluid.coverage(), w),
                diagnostics(w, w.feedResult, band), deposition);
    }

    /**
     * The typed hold of a crystal competition whose answer needs a phase set the engine does not implement (a fluid with a
     * product proved unstable beside a crystal, three crystals, two crystals whose fluid is not answered); see {@link EquilibriumResult#CRYSTAL_PHASES}.
     */
    private EquilibriumResult crystalPhasesHold(EquilibriumResult fluid, String why, PhaseCompetition competition, Workspace w) {
        String detail = EquilibriumResult.CRYSTAL_PHASES + ": " + why + "; that phase set beside a crystal is not implemented";
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.UNSUPPORTED, UnsupportedReason.NOT_IMPLEMENTED, detail,
                contract.identity(), competition, Classification.NONE, List.of(), List.of(),
                new EquilibriumResult.Coverage(EquilibriumResult.CoverageGrade.UNAVAILABLE, detail),
                diagnostics(w, w.feedResult, fluid.diagnostics().criticalBand()), null);
    }
}
