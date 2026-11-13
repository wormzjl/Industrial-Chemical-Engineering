package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.DEPOSITION_TOLERANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.PRESSURE_STEP_CAP;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.R;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.crystalCeiling;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.fluidChemicalPotential;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.holdsLiquid;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CrystalSet.Crystal;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.UnsupportedReason;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;
import java.util.List;

/**
 * The onset searches of {@link FluidTpEquilibrium} (S01): the temperature or pressure at which a competing crystal
 * starts to form from a feed, a bracketed search on {@code D = (mu_i - mu_s)/(R T)} of the fluid answer of the whole
 * feed, or of the feed as one phase on a named root (the freezing and one-phase frost points). The engine's four public
 * onset functions are its entries ({@link FluidTpEquilibrium#depositionTemperature},
 * {@link FluidTpEquilibrium#frostTemperature}, {@link FluidTpEquilibrium#freezingTemperature},
 * {@link FluidTpEquilibrium#depositionPressure}); each fluid answer is the engine's TP answer with the crystals left out.
 * It reads the engine's contract and evaluator, given once at construction, and calls back into the engine for the
 * request's checks, its TP answer, the crystal step's temperature and pressure (the partial-pressure approximation), the
 * grading and the solids-assessed answer. Immutable; one instance per engine.
 */
final class CrystalOnsets {
    /** Fluid TP equilibria of an onset search before NOT_CONVERGED. */
    private static final int ONSET_EVALUATIONS = 100;

    private final FluidTpEquilibrium engine;
    private final PhaseContract contract;
    private final CubicPhaseEvaluator evaluator;

    CrystalOnsets(FluidTpEquilibrium engine, PhaseContract contract, CubicPhaseEvaluator evaluator) {
        this.engine = engine;
        this.contract = contract;
        this.evaluator = evaluator;
    }

    /**
     * @param single the root of the feed as one phase whose chemical potential the search uses ({@link Root#LIQUID}:
     *        the freezing point, {@link Root#VAPOR}: the frost point of the gas as one phase), or {@code null} for the
     *        fluid answer of the whole feed (the onset functions of P4)
     */
    DepositionOnset onset(boolean temperatureSearch, Root single, String crystalId, double fixed, List<String> species,
                          double[] amounts, Workspace w) {
        boolean freezing = single == Root.LIQUID;
        if (!(fixed > 0.0) || !Double.isFinite(fixed)) throw new IllegalArgumentException("The fixed temperature or pressure must be positive");
        var request = EquilibriumRequest.tp(temperatureSearch ? 200.0 : fixed, temperatureSearch ? fixed : 1.0e5, species, amounts,
                PhaseCompetition.FLUID_AND_CRYSTALS);
        EquilibriumResult refused = engine.contractChecks(request, w);
        if (refused == null) refused = engine.implementationChecks(Specification.TP, w.competition, w);
        if (refused == null && w.activeCount == 0) {
            refused = engine.unsupported(Specification.TP, w.competition, UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED,
                    "package " + contract.packageId() + " admits no crystal");
        }
        if (refused != null) {
            return DepositionOnset.without(refused.status(), refused.reason(), refused.detail(), crystalId,
                    Double.NaN, Double.NaN, Double.NaN, 0);
        }
        PhaseCompetition competition = w.competition;
        Crystal c = null;
        for (int a = 0; a < w.activeCount; a++) {
            Crystal k = w.active[a];
            if (!(w.request.amounts[k.component()] > 0.0) || crystalId != null && !k.id().equals(crystalId)) continue;
            if (c != null) throw new IllegalArgumentException("Several competing crystals are made of carried species; name one");
            c = k;
        }
        if (c == null) {
            throw new IllegalArgumentException("No competing crystal" + (crystalId == null ? "" : " " + crystalId)
                    + " is made of a carried species");
        }
        int i = c.component();
        double y = w.request.amounts[i] / w.hydrocarbonTotal;
        // The window: the numerical box below the competition's ceiling, where its crystals cease to exist (owner rule of
        // 2026-10-16: the models continue past their data ranges).
        double tLow = NumericalBox.MINIMUM_TEMPERATURE;
        double tHigh = Math.min(NumericalBox.MAXIMUM_TEMPERATURE, crystalCeiling(w));
        double pLow = NumericalBox.MINIMUM_PRESSURE;
        double pHigh = NumericalBox.MAXIMUM_PRESSURE;
        var anchor = c.anchored().anchor();
        double temperature = temperatureSearch ? Double.NaN : fixed;
        double pressure = temperatureSearch ? fixed : Double.NaN;
        // The fixed variable inside the window.
        if (temperatureSearch ? pressure < pLow || pressure > pHigh : temperature < tLow || temperature > tHigh) {
            String detail = temperatureSearch ? "the pressure " + pressure + " Pa lies outside the numerical box " + pLow + " .. " + pHigh + " Pa"
                    : "the temperature " + temperature + " K lies outside the window " + tLow + " .. " + tHigh
                    + " K (the numerical box below the ceiling of " + competition + ", where its crystals cease to exist)";
            return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, detail, c.id(), temperature, pressure, Double.NaN, 0);
        }
        // The partial-pressure approximation, a diagnostic: the pure sublimation line at y_i P, or p_sub(T)/y_i.
        double approximation = Double.NaN;
        if (temperatureSearch) {
            if (y * pressure <= anchor.pressure()) {
                double estimate = engine.crystalStepTemperature(y * pressure, Double.NaN, c, Root.VAPOR, w);
                if (estimate <= anchor.temperature()) approximation = estimate;
            }
        } else if (temperature <= anchor.temperature()) {
            double sublimation = engine.crystalStepPressure(temperature, Double.NaN, c, Root.VAPOR, w);
            if (sublimation > 0.0) approximation = sublimation / y;
        }
        double xLow = temperatureSearch ? 1.0 / tHigh : DeterministicMath.log(pLow);
        double xHigh = temperatureSearch ? 1.0 / tLow : DeterministicMath.log(pHigh);
        // A pressure search without an approximation starts at the top of the package's data, inside the box.
        double xStart = temperatureSearch ? xLow : Math.min(xHigh, DeterministicMath.log(contract.domain().envelope()[3]));
        double x;
        if (freezing) {
            // The ideal-solubility estimate of the liquidus (a start only).
            approximation = Double.NaN;
            double fusion = c.fusionEnthalpy();
            double start = 1.0 / (1.0 / c.tripleTemperature() - R * DeterministicMath.log(Math.min(y, 1.0)) / fusion);
            x = Double.isFinite(start) && start > 0.0 ? Math.clamp(1.0 / start, xLow, xHigh) : xLow;
        } else if (Double.isFinite(approximation) && (temperatureSearch ? approximation >= tLow && approximation <= tHigh
                : approximation >= pLow && approximation <= pHigh)) {
            x = temperatureSearch ? 1.0 / approximation : DeterministicMath.log(approximation);
        } else {
            x = xStart;
        }
        double negative = Double.NaN;
        double positive = Double.NaN;
        double negativeValue = Double.NaN;
        double positiveValue = Double.NaN;
        double previousX = Double.NaN;
        double previousD = Double.NaN;
        int evaluations = 0;
        EquilibriumResult fluid;
        double d;
        while (true) {
            if (evaluations >= ONSET_EVALUATIONS) {
                return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the onset search of " + c.id()
                        + " did not converge in " + evaluations + " fluid equilibria", c.id(), temperature, pressure,
                        approximation, evaluations);
            }
            double t = temperatureSearch ? 1.0 / x : temperature;
            double p = temperatureSearch ? pressure : DeterministicMath.exp(x);
            if (single != null) {
                fluid = null;
                d = singlePhaseDrive(t, p, c, single, w);
                evaluations++;
                if (Double.isNaN(d)) {
                    return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the liquid at " + t + " K, " + p
                            + " Pa of the freezing search of " + c.id() + " was refused by the equation of state", c.id(),
                            temperature, pressure, approximation, evaluations);
                }
            } else {
                fluid = fluidAnswer(t, p, competition, w);
                evaluations++;
                if (!fluid.converged()) {
                    return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the fluid equilibrium at " + t + " K, "
                            + p + " Pa of the onset search of " + c.id() + ": " + fluid.detail(), c.id(), temperature, pressure,
                            approximation, evaluations);
                }
                d = (fluidChemicalPotential(fluid, i) - c.anchored().chemicalPotential(t, p)) / (R * t);
            }
            if (Math.abs(d) <= DEPOSITION_TOLERANCE) break;
            if (d > 0.0) {
                positive = x;
                positiveValue = d;
            } else {
                negative = x;
                negativeValue = d;
            }
            boolean bracketed = !Double.isNaN(positive) && !Double.isNaN(negative);
            if (bracketed && Math.abs(positive - negative) <= (temperatureSearch ? 1.0e-13 * x : 1.0e-13)) {
                if (Math.abs(negativeValue) < Math.abs(positiveValue)) x = negative; else x = positive;
                t = temperatureSearch ? 1.0 / x : temperature;
                p = temperatureSearch ? pressure : DeterministicMath.exp(x);
                if (single != null) {
                    d = singlePhaseDrive(t, p, c, single, w);
                    evaluations++;
                    break;
                }
                fluid = fluidAnswer(t, p, competition, w);
                evaluations++;
                if (!fluid.converged()) continue;
                d = (fluidChemicalPotential(fluid, i) - c.anchored().chemicalPotential(t, p)) / (R * t);
                break;
            }
            double slope = Double.NaN;
            if (!Double.isNaN(previousX) && previousX != x) slope = (d - previousD) / (x - previousX);
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                slope = temperatureSearch
                        ? (evaluator.idealGas(i).enthalpy(t) - c.anchored().properties(t, p).enthalpy()) / R : 1.0;
            }
            previousX = x;
            previousD = d;
            double next = x - d / slope;
            if (bracketed) {
                double a = Math.min(positive, negative);
                double b = Math.max(positive, negative);
                if (!(next > a && next < b)) next = 0.5 * (a + b);
            } else {
                double cap = temperatureSearch ? 0.2 * x : PRESSURE_STEP_CAP;
                next = x + Math.clamp(next - x, -cap, cap);
                if (!Double.isFinite(next)) next = x;
                if (d < 0.0 && x >= xHigh || d > 0.0 && x <= xLow) {
                    // The onset lies beyond the window: colder (or denser) than its end without a crystal, or warmer (or
                    // more dilute) than its other end with one.
                    boolean beyondHigh = d < 0.0;
                    // x = 1/T: beyond xHigh is below tLow; x = ln P: beyond xHigh is above pHigh.
                    boolean below = temperatureSearch == beyondHigh;
                    String detail = "the onset of " + c.id() + " lies " + (below ? "below" : "above") + " the "
                            + (temperatureSearch ? "temperature window " + tLow + " .. " + tHigh + " K" : "pressure window " + pLow + " .. "
                            + pHigh + " Pa") + " (the numerical box below the ceiling of " + competition + ")";
                    return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, detail, c.id(),
                            temperature, pressure, approximation, evaluations);
                }
                next = Math.clamp(next, xLow, xHigh);
            }
            x = next;
        }
        double t = temperatureSearch ? 1.0 / x : temperature;
        double p = temperatureSearch ? pressure : DeterministicMath.exp(x);
        if (single != null) {
            fluid = fluidAnswer(t, p, competition, w);
            evaluations++;
            if (!fluid.converged()) {
                return DepositionOnset.without(EquilibriumResult.Status.NOT_CONVERGED, null, "the fluid equilibrium at the one-phase onset "
                        + t + " K, " + p + " Pa of " + c.id() + ": " + fluid.detail(), c.id(), t, p, approximation, evaluations);
            }
        }
        if (fluid.classification() == Classification.LIQUID_LIQUID) {
            String detail = EquilibriumResult.CRYSTAL_PHASES + ": the onset of " + c.id() + " at " + t + " K, " + p + " Pa lies where the"
                    + " fluid is two liquids; that phase set beside a crystal is not implemented";
            return DepositionOnset.without(EquilibriumResult.Status.UNSUPPORTED, UnsupportedReason.NOT_IMPLEMENTED, detail, c.id(),
                    t, p, approximation, evaluations);
        }
        EquilibriumResult atOnset = engine.graded(engine.assessed(fluid, "onset of " + c.id() + ": |mu_i - mu_s|/RT = " + Math.abs(d), w), c, w);
        String kind = freezing
                ? holdsLiquid(fluid) && fluid.phases().size() == 1 ? "a liquidus point (the liquid is the stable fluid, " + fluid.classification() + ")"
                        : "a liquidus point of the liquid as one phase (the stable fluid of the feed there is " + fluid.classification() + ")"
                : single != null ? !holdsLiquid(fluid) ? "a frost point (the gas is the stable fluid, " + fluid.classification() + ")"
                        : "a frost point of the gas as one phase (the stable fluid of the feed there is " + fluid.classification() + ")"
                : holdsLiquid(fluid) ? fluid.phases().size() > 1 ? "a three-phase point (the fluid is " + fluid.classification() + ")"
                        : "a liquidus point (the fluid is " + fluid.classification() + ")" : "a frost point (the fluid is " + fluid.classification() + ")";
        String detail = "onset of " + c.id() + " at " + t + " K, " + p + " Pa by fugacity (" + evaluations + " fluid equilibria), "
                + kind + (freezing ? "" : "; the partial-pressure approximation " + approximation + (temperatureSearch ? " K" : " Pa")
                + " is a diagnostic, not the answer");
        var coverage = new EquilibriumResult.Coverage(atOnset.coverage().grade(), atOnset.coverage().evidence()
                + "; onset by fugacity" + (freezing ? "" : ", the partial-pressure approximation a diagnostic"));
        return new DepositionOnset(EquilibriumResult.Status.CONVERGED, null, detail, c.id(), t, p, approximation, Math.abs(d),
                evaluations, atOnset, coverage);
    }

    /**
     * {@code (mu_i - mu_s)/(R T)} of the feed as one phase at (t, p) on {@code root} (its only root where it has one), the
     * function of the freezing and one-phase frost searches (P5); {@code NaN} when the equation of state refuses the state.
     */
    private double singlePhaseDrive(double t, double p, Crystal c, Root root, Workspace w) {
        try {
            PhaseState phase = evaluator.evaluate(t, p, w.request.amounts, root, w.phases, w.liquidScratch);
            w.kernelEvaluations++;
            return (phase.chemicalPotential(c.component()) - c.anchored().chemicalPotential(t, p)) / (R * t);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return Double.NaN;
        }
    }

    /** The fluid answer of the workspace's material at (t, p) with the crystals left out (onset searches). */
    private EquilibriumResult fluidAnswer(double t, double p, PhaseCompetition competition, Workspace w) {
        int active = w.activeCount;
        w.activeCount = 0;
        try {
            return engine.evaluateTp(t, p, competition, w);
        } finally {
            w.activeCount = active;
        }
    }
}
