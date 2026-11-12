package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseDerivatives;
import com.wormzjl.icheme.science.thermo.reference.HelmholtzFluid;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * G3 fixture family F5 (plan {@code P3_PILOT_ENGINE_PLAN.md} sections 6 and 8.2; WP6a band, D14 corner): paths through the
 * critical region on the pilot package's equilibrium engine ({@link FluidTpEquilibrium} on
 * {@code PhaseContract.forNetworkPackage} and {@code CubicPhaseEvaluator.forPackage}, the network's phase decision since
 * WP7).
 *
 * <ul>
 *   <li>N2 and CO2 isotherms at Tc - 5, - 2, 0, + 2 and + 5 K from 2 to 10 MPa in 50 kPa steps, and the P2 near-critical
 *   N2/CH4 path (x_N2 0.67 at 150 K, 3 to 5.5 MPa in 10 kPa steps). Every answer is converged or typed: no
 *   {@code NOT_CONVERGED} outside the declared band, and inside it only the typed {@code CRITICAL_BAND}. A converged
 *   answer inside the band is graded research-only, outside it the contract's estimated-with-declared-error.</li>
 *   <li>Derivatives of every converged phase are present (finite) or refused typed ({@code IllegalArgumentException} of
 *   the kernel at root coalescence).</li>
 *   <li>Outside the band the single-phase densities meet the F4 rules against the oracle.</li>
 * </ul>
 *
 * The network-level reference is WP7's {@code science.fluid.network.NearCriticalNitrogenIslandTest} (a nitrogen island
 * crossing the band, only typed holds allowed inside it; none occurred), run with this family in the G3 Gradle
 * invocation. Since P3 WP7d the engine's band carries D14's corner box (Tr 1.0-1.1 x Pr 1.5-2) and D15's widening of box
 * 2 (Tr 0.95-0.97 below Pr 0.8): every corner state of the paths (by the oracle's reduced variables) must lie in the
 * engine's band and be graded research-only; the liquid between boxes 1 and 2 is in the band (not scored).
 */
class G3F5CriticalRegionPathTest {
    /**
     * States the engine leaves {@code NOT_CONVERGED} outside its band, held as known defects: none since P3 WP7d. WP9b held
     * one (N2/CH4 0.67/0.33 at 150 K and 4.69 MPa, "the feed's stability test is unresolved" at {@code 8e5274e} and
     * {@code f2d5221}): a stable single phase whose Wilson liquid-like trial crawls to the trivial solution in 186
     * iterations, past the 100-iteration budget; the engine now repeats an unresolved test on ten times the budget and
     * answers it (one phase, {@code FluidTpEquilibriumP3Test.anUnresolvedStabilityTestIsRepeatedOnTheLongerBudget}).
     */
    static final java.util.Set<String> KNOWN_UNTYPED = java.util.Set.of();

    static FluidTpEquilibrium engine() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        return new FluidTpEquilibrium(PhaseContract.forPackage(catalog, PILOT), CubicPhaseEvaluator.forPackage(catalog, PILOT), FluidTpEquilibrium.Settings.DEFAULT);
    }

    static final class Path {
        final String name;
        int states, converged, bandConverged, bandTyped, outsideFailed, coexistence, twoPhase, corner, cornerResearch;
        int derivatives, derivativeRefusals, scored, gradeErrors;
        final Worst density = new Worst();
        final List<String> failures = new ArrayList<>();
        Path(String name) { this.name = name; }
    }

    static Path run(FluidTpEquilibrium engine, String name, double[] amounts, double t, double[] pressures, Integer pureSpecies) {
        var path = new Path(name);
        var ws = engine.newWorkspace();
        var evaluator = engine.evaluator();
        var derivatives = new PhaseDerivatives(4);
        var evaluatorWs = evaluator.newWorkspace();
        var contract = engine.contract();
        for (double p : pressures) {
            path.states++;
            var result = engine.tp(EquilibriumRequest.tp(t, p, contract.components(), amounts, PhaseCompetition.FLUID_ONLY), ws);
            boolean band = result.diagnostics().criticalBand();
            String at = f("%.2f K %.2f MPa", t, p / 1e6);
            if (pureSpecies != null) {
                var fluid = oracle(pureSpecies);
                if (d14Corner(t / fluid.criticalTemperature(), p / fluid.criticalPressure())) {
                    path.corner++;
                    // D14: the corner is in the band (P3 WP7d encodes it as the engine's box 4).
                    if (band) path.cornerResearch++;
                    else path.failures.add(at + ": D14 corner state outside the engine's band");
                }
            }
            if (!result.converged()) {
                if (band && result.detail().startsWith(EquilibriumResult.CRITICAL_BAND)) path.bandTyped++;
                else {
                    path.outsideFailed++;
                    path.failures.add(at + ": " + result.status() + " " + result.detail());
                }
                continue;
            }
            path.converged++;
            if (band) path.bandConverged++;
            var grade = result.coverage().grade();
            var expected = band ? EquilibriumResult.CoverageGrade.RESEARCH_ONLY : EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR;
            if (grade != expected) {
                path.gradeErrors++;
                path.failures.add(at + ": grade " + grade + " (band " + band + ")");
            }
            if (result.classification() == EquilibriumResult.Classification.PURE_COEXISTENCE_UNDERDETERMINED) path.coexistence++;
            if (result.phases().size() == 2) path.twoPhase++;
            for (var phase : result.phases()) {
                double[] x = java.util.Arrays.copyOf(phase.amountsView(), 4);
                try {
                    evaluator.derivatives(t, p, x, phase.state().root(), evaluatorWs, derivatives);
                    boolean finite = Double.isFinite(derivatives.volumePressureDerivative()) && Double.isFinite(derivatives.enthalpyTemperatureDerivative());
                    for (double value : derivatives.logFugacityTemperatureDerivativeView()) finite &= Double.isFinite(value);
                    if (!finite) path.failures.add(at + ": non-finite derivative without a refusal");
                    else path.derivatives++;
                } catch (IllegalArgumentException refused) {
                    path.derivativeRefusals++;
                }
            }
            // Outside the band a pure single phase meets the F4 density rules against the oracle.
            if (pureSpecies != null && !band && result.phases().size() == 1) {
                var fluid = oracle(pureSpecies);
                double tc = fluid.criticalTemperature();
                boolean liquid = t < tc && p > fluid.saturation(t).pressure();
                var reference = fluid.stateAtPressure(t, p, liquid ? HelmholtzFluid.Root.LIQUID : HelmholtzFluid.Root.VAPOUR);
                double rho = 100 * (1 / (result.phases().get(0).state().molarVolume() * reference.density()) - 1);
                var rule = densityRule(fluid, t, p, reference.compressibility());
                if (rule.scored()) {
                    path.scored++;
                    path.density.add(rho, at + " " + rule.region());
                    if (Math.abs(rho) > rule.held()) path.failures.add(at + ": density " + f("%+.2f", rho) + " beyond " + rule.held() + " (" + rule.region() + ")");
                }
            }
        }
        return path;
    }

    @Test
    void pathsThroughTheCriticalRegionAreConvergedOrTyped() {
        var engine = engine();
        var table = new Table("G3 F5", "Path", "States", "Converged (in band)", "Typed CRITICAL_BAND", "NOT_CONVERGED outside", "Two-phase",
                "Pure coexistence", "Grade errors", "Derivatives present / refused", "D14 corner (in engine band)", "Scored density, worst %");
        List<String> failures = new ArrayList<>();
        for (int s : new int[] {0, 3}) {
            double tc = oracle(s).criticalTemperature();
            double[] pressures = new double[161];
            for (int k = 0; k <= 160; k++) pressures[k] = 2e6 + k * 5e4;
            double[] amounts = new double[4];
            amounts[s] = 1;
            for (double dt : new double[] {-5, -2, 0, 2, 5}) {
                var path = run(engine, f("%s Tc%+.0f K", SHORT[s], dt), amounts, tc + dt, pressures, s);
                table.row(path.name, path.states, path.converged + " (" + path.bandConverged + ")", path.bandTyped, path.outsideFailed, path.twoPhase,
                        path.coexistence, path.gradeErrors, path.derivatives + " / " + path.derivativeRefusals, path.corner + " (" + path.cornerResearch + ")",
                        path.scored + ", " + path.density.signed("%+.2f"));
                failures.addAll(path.failures.stream().map(failure -> path.name + " " + failure).toList());
            }
        }
        double[] mixture = {0.67, 0.33, 0, 0};
        double[] pressures = new double[251];
        for (int k = 0; k <= 250; k++) pressures[k] = 3e6 + k * 1e4;
        var path = run(engine, "N2/CH4 0.67/0.33 150 K", mixture, 150, pressures, null);
        table.row(path.name, path.states, path.converged + " (" + path.bandConverged + ")", path.bandTyped, path.outsideFailed, path.twoPhase,
                path.coexistence, path.gradeErrors, path.derivatives + " / " + path.derivativeRefusals, "-", "-");
        failures.addAll(path.failures.stream().map(failure -> path.name + " " + failure).toList());
        table.print("critical-region paths on the pilot engine (band = the engine's pure boxes and mixture tie-line/stationary-distance rule)");
        List<String> unexpected = new ArrayList<>();
        for (String failure : failures) {
            boolean known = KNOWN_UNTYPED.stream().anyMatch(state -> failure.startsWith(state + ": NOT_CONVERGED"));
            System.out.println((known ? "G3 F5 NOT MET (known, engine owner): " : "G3 F5 FAILURE: ") + failure);
            if (!known) unexpected.add(failure);
        }
        for (String state : KNOWN_UNTYPED) {
            if (failures.stream().noneMatch(failure -> failure.startsWith(state))) System.out.println("G3 F5 known state now converged or typed: " + state);
        }
        assertEquals(List.of(), unexpected);
    }
}
