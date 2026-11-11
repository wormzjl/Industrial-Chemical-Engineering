package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialCoverage;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import org.junit.jupiter.api.Test;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * G3 fixture family F7 (plan {@code P3_PILOT_ENGINE_PLAN.md} sections 5 and 8.2; D2, D8): CO2 in liquid methane, the
 * fluid states of the P5 freeze-out case at 91 to 120 K on the pilot package's evaluator. The fluid-only states are
 * <b>research-only</b> and recorded as before (the phase the engine finds, its label, density and the CO2 fugacity
 * coefficient, on an open research contract); the fluid-only network contract answers them the same since the owner rule
 * of 2026-10-16 (it refused them below CO2's 216.592 K triple point before: a freezing point is no validity floor).
 * Since P5 the pilot's crystal competition <b>answers</b> them (P4 stage 2b held them typed): the CO2 beyond its
 * solubility freezes as the CO2-I crystal beside the methane liquid ({@code LIQUID_SOLID}), the rest stays one liquid,
 * and the pilot network adopts every state (the supersaturated liquid in its liquid slot). Its own bound: every state
 * answered, the crystal's species conserved to 1e-13 and at the crystal's chemical potential to 1e-9 in the liquid, the liquid beside the crystal on the engine's own liquidus
 * ({@code freezingTemperature} of the liquid's composition at the state's pressure within 1e-6 K of the state), and the
 * solubility at 112 and 120 K within a factor 1.6 of Shen 2012 / Gao 2012 (the accuracy itself is G5 family 2). CO2's
 * ideal gas below the triple point is the spine's extended alpha0 segment, graded estimated-with-declared-error (WP3).
 */
class G3F7CarbonDioxideInLiquidMethaneTest {
    @Test
    void fluidStatesOfCarbonDioxideInLiquidMethaneAreResearchOnly() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        FluidTpEquilibrium research = researchEngine(evaluator, "pilot-co2-in-liquid-methane");
        var production = new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, PILOT), evaluator, FluidTpEquilibrium.Settings.DEFAULT, null);
        var ws = research.newWorkspace();
        var productionWs = production.newWorkspace();
        var spine = catalog.requireSpine(PILOT, "CarbonDioxide");
        var table = new Table("G3 F7", "T K", "P MPa", "x_CO2", "Classification (label)", "Feed verdict", "rho kg/m3", "ln phi_CO2",
                "f_CO2 Pa", "Grade (research contract)", "CO2 spine grade at T", "Production paths");
        var splits = new Table("G3 F7 split", "T K", "P MPa", "x_CO2", "Classification", "Phases (engine label: amount, x_CO2, density)", "Grade");
        int states = 0;
        for (double t : new double[] {91, 95, 100, 110, 120}) {
            for (double p : new double[] {0.5e6, 2e6}) {
                for (double x : new double[] {1e-4, 1e-3, 1e-2}) {
                    double[] z = {0, 1 - x, 0, x};
                    var result = research.tp(EquilibriumRequest.tp(t, p, research.contract().components(), z, PhaseCompetition.FLUID_ONLY), ws);
                    assertTrue(result.converged(), t + " K " + p + " Pa x " + x + ": " + result.detail());
                    if (result.phases().size() != 1) {
                        // A fluid-only split: the CO2 that would freeze (P5) appears as a subcooled CO2-rich liquid beside the
                        // methane liquid, both liquid-like: LIQUID_LIQUID (P3 WP7d; VAPOR_LIQUID with the methane liquid as
                        // VAPOR before).
                        assertEquals(EquilibriumResult.Classification.LIQUID_LIQUID, result.classification(), result.toString());
                        for (var phase : result.phases()) assertEquals(com.wormzjl.icheme.science.thermo.phase.PhaseKind.LIQUID, phase.kind());
                        var cells = new StringBuilder();
                        for (var ph : result.phases()) {
                            double n = ph.total(), m = 0;
                            for (int i = 0; i < 4; i++) m += ph.amount(i) / n * evaluator.component(i).molarMassKilogramPerMol();
                            cells.append(cells.isEmpty() ? "" : "; ").append(f("%s: fraction %.4f, x_CO2 %.4f, rho %.1f kg/m3", ph.kind(), n,
                                    ph.amount(3) / n, m / ph.state().molarVolume()));
                        }
                        splits.row(number(t), f("%.1f", p / 1e6), f("%.0e", x), result.classification(), cells, result.coverage().grade());
                        states++;
                        continue;
                    }
                    assertEquals(EquilibriumResult.CoverageGrade.RESEARCH_ONLY, result.coverage().grade());
                    assertFalse(result.solidsAssessed(), "fluid-only: solid phases not assessed");
                    var phase = result.phases().get(0);
                    var state = phase.state();
                    double mw = 0;
                    for (int i = 0; i < 4; i++) mw += z[i] * evaluator.component(i).molarMassKilogramPerMol();
                    double lnPhi = state.logFugacityCoefficient(3);
                    // The engine on the fluid-only network contract answers the same phase (no freezing-point floor since the
                    // owner rule of 2026-10-16); the pilot network (its crystal competition) answers since P5 (checked below).
                    var contract = production.tp(EquilibriumRequest.tp(t, p, production.contract().components(), new double[] {0, 1 - x, 0, x, 0},
                            PhaseCompetition.FLUID_ONLY), productionWs);
                    assertTrue(contract.converged(), contract.detail());
                    assertEquals(result.classification(), contract.classification());
                    var grade = spine.coverageAt(t).grade();
                    assertEquals(MaterialCoverage.Grade.ESTIMATED_DECLARED_ERROR, grade);
                    if (p == 2e6 || x == 1e-3) {
                        table.row(number(t), f("%.1f", p / 1e6), f("%.0e", x), result.classification() + " (" + phase.kind() + ")",
                                result.diagnostics().feedVerdict(), f("%.1f", mw / state.molarVolume()), f("%+.4f", lnPhi),
                                f("%.4g", x * p * Math.exp(lnPhi)), result.coverage().grade(), grade, "network: answered (P5); contract: " + contract.coverage().grade());
                    }
                    states++;
                }
            }
        }
        table.print(f("CO2 in liquid methane, %d fluid states (one-phase rows at 2 MPa or x_CO2 1e-3 printed); the crystal competition below", states));
        splits.print("fluid-only splits: a subcooled CO2-rich liquid beside the methane liquid (both liquid-like: LIQUID_LIQUID since P3 WP7d)");
        crystalCompetitionAnswersEveryState();
    }

    /** Shen 2012 (binary, 112 K) and Gao 2012 (binary, 123.15 K; Shen's 124 K is the same measurement): CO2 solubility in liquid CH4. */
    static final double[][] SOLUBILITY = {{112.0, 2.13e-4}, {123.15, 8.23e-4}};

    /** P5: the pilot's crystal competition answers every F7 state; its own bound (class comment). */
    static void crystalCompetitionAnswersEveryState() {
        var engine = FluidTpEquilibrium.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        var ws = engine.newWorkspace();
        var basis = engine.contract().components();
        var table = new Table("G3 F7 crystal competition (P5)", "T K", "P MPa", "x_CO2", "Classification", "crystal mol", "liquid x_CO2",
                "freezing T of the liquid - T, K", "|mu - mu_s|/RT", "conservation", "network");
        int answered = 0, frozen = 0;
        double worstMu = 0, worstConservation = 0, worstLiquidus = 0;
        for (double t : new double[] {91, 95, 100, 110, 120}) {
            for (double p : new double[] {0.5e6, 2e6}) {
                for (double x : new double[] {1e-4, 1e-3, 1e-2}) {
                    double[] z = {0, 1 - x, 0, x, 0};
                    var r = engine.tp(EquilibriumRequest.tp(t, p, basis, z, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
                    assertTrue(r.converged(), t + " K " + p + " Pa x " + x + ": " + r.detail());
                    var state = pilot().flashTP(t, p, z, () -> { });
                    assertTrue(TestPhases.liquidProperties(state) != null, "the pilot network adopts the liquid: " + t + " K " + p + " Pa x " + x);
                    answered++;
                    worstConservation = Math.max(worstConservation, r.conservationDefect(z));
                    if (r.classification() == EquilibriumResult.Classification.LIQUID_SOLID) {
                        frozen++;
                        var liquid = r.phases().get(0);
                        var solid = r.phases().get(1);
                        double mu = Math.abs(liquid.state().chemicalPotential(3) - solid.state().molarGibbsEnergy()) / (8.31446261815324 * t);
                        worstMu = Math.max(worstMu, mu);
                        double[] y = new double[5];
                        double n = liquid.total();
                        for (int i = 0; i < 4; i++) y[i] = liquid.amount(i) / n;
                        var onset = engine.freezingTemperature(null, p, basis, y, ws);
                        assertTrue(onset.converged(), onset.detail());
                        worstLiquidus = Math.max(worstLiquidus, Math.abs(onset.temperature() - t));
                        table.row(number(t), f("%.1f", p / 1e6), f("%.0e", x), r.classification(), f("%.4g", solid.amount(3)), f("%.4g", y[3]),
                                f("%+.2e", onset.temperature() - t), f("%.1e", mu), f("%.1e", r.conservationDefect(z)), "liquid slot");
                        for (double[] data : SOLUBILITY) {
                            if (t == Math.round(data[0]) || Math.abs(t - data[0]) < 0.2) {
                                assertTrue(y[3] / data[1] < 1.6 && data[1] / y[3] < 1.6, "solubility at " + t + " K: " + y[3] + " against " + data[1]);
                            }
                        }
                    } else {
                        assertEquals(1, r.phases().size(), r.toString());
                        assertEquals(com.wormzjl.icheme.science.thermo.phase.PhaseKind.LIQUID, r.phases().get(0).kind(), r.toString());
                        table.row(number(t), f("%.1f", p / 1e6), f("%.0e", x), r.classification(), "", f("%.4g", x), "", "", f("%.1e", r.conservationDefect(z)),
                                "liquid slot");
                    }
                }
            }
        }
        // The solubility at the data's temperatures (the rows above are 110 and 120 K): the liquid beside the crystal.
        var solubility = new Table("G3 F7 solubility against Shen 2012 / Gao 2012", "T K", "P MPa", "x_CO2 engine", "x_CO2 data", "ratio");
        for (double[] data : SOLUBILITY) {
            var r = engine.tp(EquilibriumRequest.tp(data[0], 1e6, basis, new double[] {0, 0.99, 0, 0.01, 0}, PhaseCompetition.FLUID_AND_CRYSTALS), ws);
            assertEquals(EquilibriumResult.Classification.LIQUID_SOLID, r.classification(), r.toString());
            double xs = r.phases().get(0).state().moleFraction(3);
            solubility.row(number(data[0]), "1.0", f("%.4g", xs), f("%.4g", data[1]), f("%.3f", xs / data[1]));
            assertTrue(xs / data[1] < 1.6 && data[1] / xs < 1.6, "solubility at " + data[0] + " K: " + xs + " against " + data[1]);
        }
        table.print(f("CO2 in liquid methane on the pilot's crystal competition: %d states answered, %d with the crystal; worst |mu - mu_s|/RT %.1e,"
                + " conservation %.1e, liquidus consistency %.1e K", answered, frozen, worstMu, worstConservation, worstLiquidus));
        solubility.print("solubility of CO2 in liquid CH4 beside the crystal (G5F2 scores the liquidus)");
        assertEquals(30, answered);
        assertTrue(frozen >= 10, "the crystal forms beyond the solubility: " + frozen);
        assertTrue(worstMu <= 1.0e-9 && worstConservation <= 1.0e-13 && worstLiquidus <= 1.0e-6,
                worstMu + " / " + worstConservation + " / " + worstLiquidus);
    }
}
