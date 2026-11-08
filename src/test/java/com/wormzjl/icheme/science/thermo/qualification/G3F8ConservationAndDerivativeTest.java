package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.network.InventoryEquilibrium;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseDerivatives;
import com.wormzjl.icheme.science.thermo.phase.PhaseState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * G3 fixture family F8 (plan {@code P3_PILOT_ENGINE_PLAN.md} sections 7 and 8.2; P0 section 3.1): conservation and
 * derivatives on the pilot package {@code icheme:pilot_cryogenic}, re-asserted at G3.
 *
 * <ul>
 *   <li>TP, PH and UV on the engine of the pilot's network contract over a grid of binary and quaternary pilot states
 *   (outside the band): conservation defect per flash at most 2e-16 (plan), T and P recovered by PH and UV to 1e-9.</li>
 *   <li>Analytic derivatives of the pilot evaluator (E-PPR78 kij(T), spines, translations) against central differences:
 *   {@code d ln phi/dT}, {@code d ln phi/dP}, {@code d ln phi/dn}, {@code dv/dT}, {@code dv/dP}, {@code c_p = dh/dT} at 24
 *   binary and quaternary states on both roots, within 1e-6 of scale.</li>
 *   <li>Closed vessels: CH4/C2H6 heated at fixed volume across its bubble and its dew point on the engine's UV
 *   (conservation, specification closure, T and P monotone; the liquid-full vessel's first answer beyond the 10 MPa
 *   envelope converges graded {@code EXTRAPOLATED}, owner rule of 2026-10-16, a typed out-of-domain answer before); N2
 *   heated from 300 to 1143 K at fixed volume through the network's own inventory solve ({@code InventoryEquilibrium})
 *   at the network gates (components exact to 1e-10 + 1e-8 scale, energy 1e-4 J + 1e-6 scale), cross-checked with the engine's UV. The closed liquid-nitrogen compression
 *   0.5-8 MPa is WP4's {@code DirectLiquidContinuityTest} (not repeated).</li>
 * </ul>
 */
class G3F8ConservationAndDerivativeTest {
    static FluidTpEquilibrium engine() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        return new FluidTpEquilibrium(PhaseContract.forNetworkPackage(catalog, PILOT), CubicPhaseEvaluator.forPackage(catalog, PILOT), FluidTpEquilibrium.Settings.DEFAULT, null);
    }

    static EquilibriumRequest tp(FluidTpEquilibrium engine, double t, double p, double[] z) {
        return EquilibriumRequest.tp(t, p, engine.contract().components(), z, PhaseCompetition.FLUID_ONLY);
    }

    @Test
    void tpPhUvRoundTripsConserve() {
        var engine = engine();
        var ws = engine.newWorkspace();
        double[][] feeds = {{0.5, 0.5, 0, 0, 0}, {0, 0.6, 0.4, 0, 0}, {0.2, 0, 0, 0.8, 0}, {0, 0.7, 0, 0.3, 0}, {0.05, 0.8, 0.1, 0.05, 0},
                {0.25, 0.25, 0.25, 0.25, 0}};
        double worstDefect = 0, worstT = 0, worstP = 0;
        int tp = 0, twoPhase = 0, band = 0, phCalls = 0, uvCalls = 0;
        List<String> failures = new ArrayList<>();
        for (double[] z : feeds) {
            boolean co2 = z[3] > 0;
            for (double t : co2 ? new double[] {220, 250, 280, 320, 400, 600} : new double[] {100, 120, 150, 180, 220, 300, 600}) {
                for (double p : new double[] {1e5, 5e5, 2e6, 5e6, 8e6}) {
                    var result = engine.tp(tp(engine, t, p, z), ws);
                    if (!result.converged() || result.diagnostics().criticalBand()) {
                        band++;
                        continue;
                    }
                    tp++;
                    if (result.phases().size() == 2) twoPhase++;
                    double defect = result.conservationDefect(z);
                    worstDefect = Math.max(worstDefect, defect);
                    String at = f("%.0f K %.1f MPa %s", t, p / 1e6, java.util.Arrays.toString(z));
                    var ph = engine.ph(EquilibriumRequest.ph(p, result.enthalpy(), engine.contract().components(), z, PhaseCompetition.FLUID_ONLY), ws);
                    var uv = engine.uv(EquilibriumRequest.uv(result.internalEnergy(), result.volume(), engine.contract().components(), z,
                            PhaseCompetition.FLUID_ONLY), ws);
                    if (!ph.converged() || !uv.converged()) {
                        failures.add(at + ": PH " + ph.status() + " " + ph.detail() + " / UV " + uv.status() + " " + uv.detail());
                        continue;
                    }
                    phCalls += ph.diagnostics().outerIterations();
                    uvCalls += uv.diagnostics().outerIterations();
                    double dT = Math.max(Math.abs(ph.phases().get(0).temperature() / t - 1), Math.abs(uv.phases().get(0).temperature() / t - 1));
                    double dP = Math.abs(uv.phases().get(0).pressure() / p - 1);
                    worstT = Math.max(worstT, dT);
                    worstP = Math.max(worstP, dP);
                    worstDefect = Math.max(worstDefect, Math.max(ph.conservationDefect(z), uv.conservationDefect(z)));
                    if (dT > 1e-9 || dP > 1e-9) failures.add(at + f(": PH/UV recovered T to %.1e, P to %.1e", dT, dP));
                }
            }
        }
        System.out.println(f("G3 F8 round trips: %d TP answers (%d two-phase, %d in the band or refused skipped), PH and UV on each: worst"
                + " conservation defect %.2e (plan 2e-16), worst dT/T %.1e, dP/P %.1e; TP calls per PH %.1f, per UV %.1f", tp, twoPhase, band,
                worstDefect, worstT, worstP, (double) phCalls / tp, (double) uvCalls / tp));
        failures.forEach(failure -> System.out.println("G3 F8 FAILURE: " + failure));
        assertEquals(List.of(), failures);
        assertTrue(worstDefect <= 2.3e-16, "conservation defect " + worstDefect);
        assertTrue(tp > 150 && twoPhase > 10, tp + " / " + twoPhase);
    }

    @Test
    void analyticDerivativesAgainstCentralDifferences() {
        var evaluator = CubicPhaseEvaluator.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        var ws = evaluator.newWorkspace();
        var d = new PhaseDerivatives(4);
        var plus = new PhaseState(4);
        var minus = new PhaseState(4);
        double[][] states = { // T, P, x...
                {100, 5e5, 0.5, 0.5, 0, 0}, {120, 2e6, 0.3, 0.7, 0, 0}, {150, 1e5, 0.2, 0.8, 0, 0}, {180, 5e6, 0, 0.6, 0.4, 0},
                {220, 1e6, 0, 0.5, 0.5, 0}, {250, 3e6, 0.2, 0, 0, 0.8}, {280, 8e6, 0, 0.7, 0, 0.3}, {300, 2e6, 0, 0, 0.6, 0.4},
                {400, 1e7, 0.25, 0.25, 0.25, 0.25}, {600, 1e6, 0.1, 0.6, 0.2, 0.1}, {900, 2e5, 0.1, 0.4, 0.5, 0}, {240, 4e6, 0.05, 0.8, 0.1, 0.05}};
        double worst = 0;
        String worstAt = "";
        int count = 0;
        for (double[] s : states) {
            double t = s[0], p = s[1];
            double[] x = {s[2], s[3], s[4], s[5]};
            for (Root root : Root.values()) {
                evaluator.derivatives(t, p, x, root, ws, d);
                double v = d.state().molarVolume(), h = d.state().molarEnthalpy();
                double[] lnPhi = d.state().logFugacityCoefficientsView().clone();
                double ht = 1e-5 * t, hp = 1e-5 * p; // central steps small enough for strongly curved liquids (1e-4 T leaves 5e-6)
                evaluator.evaluate(t + ht, p, x, root, ws, plus);
                evaluator.evaluate(t - ht, p, x, root, ws, minus);
                double[] errors = new double[4];
                errors[0] = rel((plus.molarVolume() - minus.molarVolume()) / (2 * ht), d.volumeTemperatureDerivative(), v / t);
                errors[1] = rel((plus.molarEnthalpy() - minus.molarEnthalpy()) / (2 * ht), d.enthalpyTemperatureDerivative(), d.enthalpyTemperatureDerivative());
                double phiT = 0;
                for (int i = 0; i < 4; i++) {
                    if (x[i] <= 0) continue;
                    phiT = Math.max(phiT, rel((plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * ht),
                            d.logFugacityTemperatureDerivativeView()[i], 1 / t));
                }
                evaluator.evaluate(t, p + hp, x, root, ws, plus);
                evaluator.evaluate(t, p - hp, x, root, ws, minus);
                errors[2] = rel((plus.molarVolume() - minus.molarVolume()) / (2 * hp), d.volumePressureDerivative(), v / p);
                double phiP = 0, phiN = 0;
                for (int i = 0; i < 4; i++) {
                    if (x[i] <= 0) continue;
                    phiP = Math.max(phiP, rel((plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * hp),
                            d.logFugacityPressureDerivativeView()[i], 1 / p));
                }
                // d ln phi_i / d n_j at a total of one mole (the kernel's per-mole row), by perturbing n_j.
                for (int j = 0; j < 4; j++) {
                    if (x[j] <= 0) continue;
                    double hn = 1e-6;
                    double[] up = x.clone(), down = x.clone();
                    up[j] += hn;
                    down[j] -= hn;
                    evaluator.evaluate(t, p, up, root, ws, plus);
                    evaluator.evaluate(t, p, down, root, ws, minus);
                    for (int i = 0; i < 4; i++) {
                        if (x[i] <= 0) continue;
                        phiN = Math.max(phiN, rel((plus.logFugacityCoefficient(i) - minus.logFugacityCoefficient(i)) / (2 * hn),
                                d.logFugacityCompositionDerivative(i, j), 1));
                    }
                }
                errors[3] = Math.max(phiT, Math.max(phiP, phiN));
                for (double e : errors) {
                    if (e > worst) {
                        worst = e;
                        worstAt = f("%.0f K %.1f MPa %s %s", t, p / 1e6, java.util.Arrays.toString(x), root);
                    }
                }
                count++;
                assertTrue(Double.isFinite(h) && lnPhi.length == 4);
            }
        }
        System.out.println(f("G3 F8 derivatives: %d phases (12 states x both roots), worst |analytic - central difference| / scale %.2e at %s"
                + " (plan 1e-6)", count, worst, worstAt));
        assertTrue(worst <= 1e-6, "derivatives " + worst + " at " + worstAt);
    }

    static double rel(double numeric, double analytic, double scale) {
        return Math.abs(numeric - analytic) / Math.max(Math.abs(analytic), Math.abs(scale));
    }

    @Test
    void closedMethaneEthaneVesselsHeatAcrossTheirBubbleAndDewPoints() {
        var engine = engine();
        var ws = engine.newWorkspace();
        double[] z = {0, 0.5, 0.5, 0, 0};
        var table = new Table("G3 F8 vessel", "Vessel", "V m3/mol", "Steps", "T K start -> end", "P MPa start -> end", "Phase counts seen",
                "Crossing (T K, P MPa)", "Worst defect", "Worst spec residual", "Next step beyond 10 MPa");
        for (double vapourFraction : new double[] {0.03, 0.9}) {
            // The start: two phases at 150 K with the given vapour fraction (bisection in P on TP answers).
            double lo = 1e4, hi = 8e6;
            EquilibriumResult start = null;
            for (int k = 0; k < 80; k++) {
                double p = Math.sqrt(lo * hi);
                var r = engine.tp(tp(engine, 150, p, z), ws);
                double beta = r.phases().size() == 2 ? vapourAmount(r) : r.phases().get(0).kind().name().equals("VAPOR") ? 1 : 0;
                if (beta > vapourFraction) lo = p; else hi = p;
                start = r;
            }
            double volume = start.volume(), energy = start.internalEnergy();
            double t0 = start.phases().get(0).temperature(), p0 = start.phases().get(0).pressure(), previousT = t0, previousP = p0;
            var counts = new StringBuilder("2");
            String crossing = "-";
            double worstDefect = 0, worstSpec = 0, t = t0, p = p0;
            int steps = 0, lastCount = 2;
            String edge = "-";
            for (int k = 1; k <= 120 && t < 330; k++) {
                var r = engine.uv(EquilibriumRequest.uv(energy + k * 120.0, volume, engine.contract().components(), z, PhaseCompetition.FLUID_ONLY), ws);
                assertTrue(r.converged(), "UV step " + k + ": " + r.detail());
                if (r.phases().get(0).pressure() > 1e7) {
                    // The liquid-full vessel passes the package's 10 MPa envelope: the answer continues, graded extrapolated
                    // (owner rule of 2026-10-16; a typed out-of-domain answer before, NOT_CONVERGED "a step of U(T)" before
                    // P3 WP7d). The vessel stops there.
                    edge = r.status() + " " + r.coverage().grade();
                    System.out.println("G3 F8 vessel edge at " + f("%.2f K %.3f MPa", r.phases().get(0).temperature(),
                            r.phases().get(0).pressure() / 1e6) + ": " + edge + "; " + r.coverage().evidence());
                    assertEquals(EquilibriumResult.CoverageGrade.EXTRAPOLATED, r.coverage().grade(), r.coverage().evidence());
                    break;
                }
                t = r.phases().get(0).temperature();
                p = r.phases().get(0).pressure();
                assertTrue(t > previousT && p > previousP, "T and P rise with the energy at fixed volume");
                worstDefect = Math.max(worstDefect, r.conservationDefect(z));
                worstSpec = Math.max(worstSpec, r.diagnostics().specificationResidual());
                int count = r.phases().size();
                if (count != lastCount) {
                    counts.append(" -> ").append(count);
                    crossing = f("%.2f, %.3f", t, p / 1e6);
                    lastCount = count;
                }
                previousT = t;
                previousP = p;
                steps++;
            }
            assertEquals("2 -> 1", counts.toString(), "one crossing");
            assertTrue(worstDefect <= 1e-13 && worstSpec <= 1e-6);
            table.row(vapourFraction < 0.5 ? "CH4/C2H6 0.5/0.5, liquid-rich (bubble side)" : "CH4/C2H6 0.5/0.5, vapour-rich (dew side)",
                    f("%.3e", volume), steps, f("%.2f -> %.2f", t0, t), f("%.3f -> %.3f", p0 / 1e6, p / 1e6), counts, crossing,
                    f("%.1e", worstDefect), f("%.1e", worstSpec), edge.length() > 60 ? edge.substring(0, edge.indexOf(':')) : edge);
        }
        table.print("closed CH4/C2H6 vessels heated at fixed volume on the engine's UV (120 J per step)");
    }

    static double vapourAmount(EquilibriumResult r) {
        for (var phase : r.phases()) if (phase.kind().name().equals("VAPOR")) return phase.total() / r.totalAmount();
        return 0;
    }

    @Test
    void closedNitrogenVesselHeatedToTheCoilOutletTemperature() {
        FluidThermodynamics model = pilot();
        var engine = engine();
        var ws = engine.newWorkspace();
        double[] n = {1, 0, 0, 0, 0};
        var state = model.flashTP(300, 2e5, n, () -> { });
        double volume = state.volume(), energy = state.internalEnergy();
        var spine = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot().requireSpine(PILOT, "Nitrogen");
        // Energy to about 1143 K at fixed volume: the ideal-gas u(1143) - u(300).
        double target = spine.enthalpy(1143) - spine.enthalpy(300) - R * (1143 - 300);
        int steps = 40;
        double worstEnergy = 0, worstMoles = 0, worstEngineT = 0;
        double previousT = 300;
        for (int k = 1; k <= steps; k++) {
            double u = energy + k * target / steps;
            state = com.wormzjl.icheme.fluid.support.FluidTestSupport.inventoryState(model, state, new PassiveNetwork.Inventory(volume, n, u));
            double moles = 0;
            for (int i = 0; i < 4; i++) moles += state.liquidView()[i] + state.vaporView()[i];
            worstMoles = Math.max(worstMoles, Math.abs(moles - 1));
            worstEnergy = Math.max(worstEnergy, Math.abs(state.internalEnergy() - u) / (1e-4 + 1e-6 * Math.abs(u)));
            assertTrue(state.temperature() > previousT, "heating raises T");
            assertEquals(volume, state.volume(), 1e-8 * volume, "volume closes");
            previousT = state.temperature();
            // The engine's UV at the same (U, V): N2's formation enthalpy is zero, so the two datums coincide.
            var uv = engine.uv(EquilibriumRequest.uv(u, volume, engine.contract().components(), n, PhaseCompetition.FLUID_ONLY), ws);
            assertTrue(uv.converged(), uv.detail());
            worstEngineT = Math.max(worstEngineT, Math.abs(uv.phases().get(0).temperature() / state.temperature() - 1));
        }
        System.out.println(f("G3 F8 N2 vessel 300 K 0.2 MPa heated at fixed volume to %.2f K, %.4f MPa in %d steps: moles |dn| %.1e"
                + " (gate 1e-10 + 1e-8 scale), energy residual %.2e of the gate 1e-4 J + 1e-6 |U|, engine UV temperature within %.1e",
                state.temperature(), state.pressure() / 1e6, steps, worstMoles, worstEnergy, worstEngineT));
        assertTrue(worstMoles <= 1e-10 + 1e-8, "components");
        assertTrue(worstEnergy <= 1, "energy gate");
        assertTrue(worstEngineT <= 1e-7, "network inventory = engine UV");
        assertTrue(state.temperature() > 1100 && state.temperature() < 1200, "reached the coil-outlet range: " + state.temperature());
    }
}
