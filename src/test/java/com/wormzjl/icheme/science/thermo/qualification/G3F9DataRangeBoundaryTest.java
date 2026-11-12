package com.wormzjl.icheme.science.thermo.qualification;

import static com.wormzjl.icheme.science.thermo.qualification.G3Support.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CubicPhaseEvaluator;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.CoverageGrade;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseContract;
import com.wormzjl.icheme.science.thermo.phase.PhaseState;
import org.junit.jupiter.api.Test;

/**
 * G3 fixture family F9 (plan {@code P3_PILOT_ENGINE_PLAN.md} section 8.2; D8, D13), since the owner rule of 2026-10-16
 * ({@code RANGE_EXTRAPOLATION_PLAN.md}): just outside each data boundary the request is answered on the models'
 * continuations, next to the answer just inside (the enthalpy within 100 J/mol, the volume within 2 %, for steps of at
 * most 0.5 K or 0.5 %), where it was refused typed before. The engine's network contract grades an answer outside its
 * envelope {@code EXTRAPOLATED}; only its competition rules (D8) refuse. Free water across its melting line freezes (WP4):
 * the network carries the engine's ice as its stand-in, continuously. Paths: the pilot network ({@code icheme:pilot_cryogenic}),
 * the bundled network ({@code icheme:tjl20_methane_nitrogen}), the pilot evaluator (spine ranges) and the pilot
 * engine's contract.
 */
class G3F9DataRangeBoundaryTest {
    private final Table table = new Table("G3 F9", "Boundary", "Path", "Just inside", "Just outside", "|h out - h in| J/mol, |v out / v in - 1|");

    private void continued(String boundary, FluidThermodynamics model, String label, double[] z, double tIn, double pIn, double tOut, double pOut) {
        var inside = model.flashTP(tIn, pIn, z, () -> { });
        var outside = model.flashTP(tOut, pOut, z, () -> { });
        row(boundary, label, tIn, pIn, tOut, pOut, outside.enthalpy() - inside.enthalpy(), outside.volume() / inside.volume() - 1);
    }

    private void spine(String boundary, CubicPhaseEvaluator evaluator, double[] x, Root root, double tIn, double tOut, double p) {
        var ws = evaluator.newWorkspace();
        PhaseState inside = evaluator.evaluate(tIn, p, x, root, ws, new PhaseState(x.length)).copy();
        PhaseState outside = evaluator.evaluate(tOut, p, x, root, ws, new PhaseState(x.length)).copy();
        row(boundary, "pilot evaluator", tIn, p, tOut, p, outside.molarEnthalpy() - inside.molarEnthalpy(),
                outside.molarVolume() / inside.molarVolume() - 1);
    }

    private void row(String boundary, String label, double tIn, double pIn, double tOut, double pOut, double dh, double dv) {
        assertTrue(Math.abs(dh) <= 100 && Math.abs(dv) <= 0.02, boundary + ": h moved " + dh + " J/mol, v " + dv);
        table.row(boundary, label, f("%s K, %s MPa", number(tIn), f("%.4g", pIn / 1e6)), f("%s K, %s MPa", tOut, f("%.4g", pOut / 1e6)),
                f("%.3g, %.2e", Math.abs(dh), Math.abs(dv)));
    }

    @Test
    void answeredContinuouslyJustOutsideEveryDataBoundary() {
        var pilot = pilot();
        var net = G3Support.network();
        double[] n2 = {1, 0, 0, 0, 0}, ch4 = {0, 1, 0, 0, 0}, c2h6 = {0, 0, 1, 0, 0}, co2 = {0, 0, 0, 1, 0};
        var pkg = net.hydrocarbon.components();
        double[] netN2 = new double[net.componentCount()], netCh4 = new double[net.componentCount()], netC2h6 = new double[net.componentCount()];
        netN2[pkg.indexOf("Nitrogen")] = 1;
        netCh4[pkg.indexOf("Methane")] = 1;
        netC2h6[pkg.indexOf("Ethane")] = 1;
        // The 10 MPa envelope and the records' 2 MPa: a compressed state continues.
        continued("10 MPa, N2", pilot, "pilot network", n2, 300, 1e7, 300, 1.005e7);
        continued("10 MPa, CO2", pilot, "pilot network", co2, 400, 1e7, 400, 1.005e7);
        continued("10 MPa, N2 (bundled)", net, "bundled network", netN2, 300, 1e7, 300, 1.005e7);
        continued("10 MPa, CH4 (bundled)", net, "bundled network", netCh4, 300, 1e7, 300, 1.005e7);
        continued("2 MPa record, C2H6 (bundled)", net, "bundled network", netC2h6, 350, 2e6, 350, 2.01e6);
        // Temperature floors: a freezing point is no validity floor (the liquids stay liquid, supercooled).
        continued("63.151 K, N2", pilot, "pilot network", n2, 63.2, 1e5, 63.1, 1e5);
        continued("63.151 K, N2 (bundled)", net, "bundled network", netN2, 63.2, 1e5, 63.1, 1e5);
        continued("90.694 K, CH4", pilot, "pilot network", ch4, 90.7, 1e5, 90.69, 1e5);
        continued("90.368 K, C2H6", pilot, "pilot network", c2h6, 90.37, 1e5, 90.36, 1e5);
        continued("90 K, CO2 gas (crystal competition)", pilot, "pilot network", co2, 90.1, 100, 89.9, 100);
        continued("293.15 K, CH4 fit (bundled)", net, "bundled network", netCh4, 293.15, 1e5, 293.1, 1e5);
        // Temperature ceilings: the pilot envelope 1200 K (methane's ideal gas ends there, D13), the bundled 900 K.
        continued("1200 K, N2", pilot, "pilot network", n2, 1200, 2e5, 1200.5, 2e5);
        continued("1200 K, CH4", pilot, "pilot network", ch4, 1200, 2e5, 1200.5, 2e5);
        continued("900 K (bundled)", net, "bundled network", netN2, 900, 2e5, 900.5, 2e5);
        // Liquid water above Region 1's 623.15 K: held cp, the volume along dv/dT.
        var hotIn = pilot.waterLiquid(623.1, 2e7);
        var hotOut = pilot.waterLiquid(623.2, 2e7);
        row("Region 1 623.15 K, liquid water", "pilot network waterLiquid", 623.1, 2e7, 623.2, 2e7,
                hotOut.molarEnthalpy() - hotIn.molarEnthalpy(), hotOut.molarVolume() / hotIn.molarVolume() - 1);
        // Free water across its melting line (273.1526 K at 0.1 MPa, 273.0859 K at 1 MPa; WP4): below it the engine states
        // ice, which the network carries as its stand-in (free liquid water held at its 273.16 K values) until a vessel's
        // refresh freezes it: no hold, and continuous (the interim ice hold before WP4).
        // W3b: water is a component of the network model's cubic (the pilot and the crude packages hold none).
        var wetModel = com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        double[] netWater = new double[wetModel.componentCount()];
        netWater[wetModel.componentCount() - 1] = 1;
        continued("melting line, water (network, WP4)", wetModel, "refinery network", netWater, 273.1, 1e6, 273.05, 1e6);

        // The spine ranges through the evaluator: methane 90.6941-1200 K (D13), CO2 from 90 K (WP3), N2 to 6000 K.
        var evaluator = CubicPhaseEvaluator.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        spine("methane spine 1200 K (D13)", evaluator, new double[] {0, 1, 0, 0}, Root.VAPOR, 1200, 1200.5, 2e5);
        spine("methane spine 90.6941 K", evaluator, new double[] {0, 1, 0, 0}, Root.LIQUID, 90.7, 90.69, 1e5);
        spine("CO2 spine 90 K (WP3 extension)", evaluator, new double[] {0, 0, 0, 1}, Root.LIQUID, 90.0, 89.9, 1e5);
        spine("N2 spine to 6000 K (envelope 1200 K)", evaluator, new double[] {1, 0, 0, 0}, Root.VAPOR, 1500, 1500.5, 2e5);

        // A fluid-only contract of the package: no competition with the CO2-I crystal is qualified on it.
        var whole = PhaseContract.forPackage(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), PILOT);
        var engine = new FluidTpEquilibrium(new PhaseContract(whole.packageId(), whole.identity(), whole.components(), whole.water(),
                whole.waterComponent(), java.util.Set.of(PhaseCompetition.FLUID_ONLY), whole.waterChemistrySpecies(), whole.domain(),
                whole.fluidCoverage()), evaluator, FluidTpEquilibrium.Settings.DEFAULT);
        var ews = engine.newWorkspace();
        var components = engine.contract().components();
        var crystal = engine.tp(EquilibriumRequest.tp(180, 1e5, components, new double[] {0.99, 0, 0, 0.01},
                PhaseCompetition.withCrystals("icheme:crystal_carbon_dioxide_i")), ews);
        assertEquals(EquilibriumResult.Status.UNSUPPORTED, crystal.status());
        assertEquals(EquilibriumResult.UnsupportedReason.PHASE_COMPETITION_NOT_QUALIFIED, crystal.reason());
        table.row("CO2-I crystal (research-only record)", "pilot engine contract", "-", "competition with CO2-I at 180 K", crystal.status() + ", "
                + crystal.reason());
        // Past the envelope the contract's answer is graded extrapolated, the envelope named; just inside it is not.
        var below = engine.tp(EquilibriumRequest.tp(300, 9.95e6, components, new double[] {1, 0, 0, 0}, PhaseCompetition.FLUID_ONLY), ews);
        assertTrue(below.converged() && below.coverage().grade() != CoverageGrade.EXTRAPOLATED, below.coverage().toString());
        var pressure = engine.tp(EquilibriumRequest.tp(300, 1.005e7, components, new double[] {1, 0, 0, 0}, PhaseCompetition.FLUID_ONLY), ews);
        assertTrue(pressure.converged(), pressure.detail());
        assertEquals(CoverageGrade.EXTRAPOLATED, pressure.coverage().grade());
        assertTrue(pressure.coverage().evidence().contains("extrapolated past the package envelope"), pressure.coverage().evidence());
        table.row("10 MPa, N2", "pilot engine contract", "300 K, 9.95 MPa: " + below.coverage().grade(), "300 K, 10.05 MPa", pressure.coverage().grade().toString());
        // A closed vessel whose state lies above the envelope (P3 WP7d, the F8 finding): the UV search answers it, graded
        // extrapolated; its (U, V) are those of the liquid-like CH4/C2H6 0.5/0.5 at 283 K on an open contract.
        var open = researchEngine(evaluator, "f9-closed-vessel");
        double[] vessel = {0, 0.5, 0.5, 0};
        var beyond = open.tp(EquilibriumRequest.tp(283, 1.005e7, open.contract().components(), vessel, PhaseCompetition.FLUID_ONLY));
        var within = open.tp(EquilibriumRequest.tp(283, 9.95e6, open.contract().components(), vessel, PhaseCompetition.FLUID_ONLY));
        double[] closed = {0, 0.5, 0.5, 0};
        var uvIn = engine.uv(EquilibriumRequest.uv(within.internalEnergy(), within.volume(), components, closed, PhaseCompetition.FLUID_ONLY), ews);
        assertTrue(uvIn.converged() && uvIn.coverage().grade() != CoverageGrade.EXTRAPOLATED, uvIn.detail());
        var uvOut = engine.uv(EquilibriumRequest.uv(beyond.internalEnergy(), beyond.volume(), components, closed, PhaseCompetition.FLUID_ONLY), ews);
        assertTrue(uvOut.converged(), uvOut.detail());
        assertEquals(CoverageGrade.EXTRAPOLATED, uvOut.coverage().grade());
        assertEquals(283, uvOut.phases().get(0).temperature(), 1e-3);
        assertEquals(1.005e7, uvOut.phases().get(0).pressure(), 1e3);
        table.row("10 MPa, closed CH4/C2H6 vessel (UV)", "pilot engine contract", f("(U, V) of 283 K, 9.95 MPa: %.4g MPa", uvIn.phases().get(0).pressure() / 1e6),
                f("(U, V) of 283 K, 10.05 MPa: %.4g MPa", uvOut.phases().get(0).pressure() / 1e6), uvOut.coverage().grade()
                        + " (" + uvOut.diagnostics().outerIterations() + " TP equilibria)");
        // CO2 below its triple point as a fluid: inside its spine and the envelope, so not even extrapolated.
        var co2Contract = engine.tp(EquilibriumRequest.tp(216.5, 1e5, components, new double[] {0, 0, 0, 1}, PhaseCompetition.FLUID_ONLY), ews);
        assertTrue(co2Contract.converged(), co2Contract.detail());
        assertNotEquals(CoverageGrade.EXTRAPOLATED, co2Contract.coverage().grade(), co2Contract.coverage().evidence());
        table.row("216.592 K, CO2 (no floor)", "pilot engine contract", "-", "216.5 K, 0.1 MPa", co2Contract.classification() + ", "
                + co2Contract.coverage().grade());
        table.print("answers just outside each data boundary next to the answer just inside (refused typed before 2026-10-16)");
    }
}
