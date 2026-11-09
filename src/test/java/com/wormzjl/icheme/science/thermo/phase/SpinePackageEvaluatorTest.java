package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.fluid.thermo.CoefficientIdealGas;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P3 WP5 gate (batch 2026-09-24-coolprop-low-temperature, plan {@code P3_PILOT_ENGINE_PLAN.md} section 5, decision
 * D10): the phase engine's evaluator of the pilot package ({@link CubicPhaseEvaluator#forPackage}) and the fluid
 * network's model of the same package ({@code HydrocarbonModel} on its spine path) are one thermodynamics on two energy
 * datums. On every state: the molar volume and every {@code ln phi_i} bit for bit; the network's enthalpy is the
 * evaluator's minus {@code sum x_i DeltafH_i} to 1e-15 of the larger magnitude (rounding of sums of about 4e5 J/mol);
 * the heat capacity and the volumetric derivatives agree; rebasing the network's energy from the sensible datum to
 * the package's formation {@link EnergyReference} gives the evaluator's.
 */
class SpinePackageEvaluatorTest {
    static final String PILOT = "icheme:pilot_cryogenic";

    @Test
    void theNetworkPhaseIsTheEvaluatorPhaseOnTheSensibleDatum() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var network = FluidTestSupport.model(catalog, PILOT).hydrocarbon;
        assertEquals(network.components(), evaluator.components());
        var formation = network.spine().orElseThrow().formationReference();
        var sensible = EnergyReference.sensible(network.components());
        int n = network.componentCount();
        double[] offsets = new double[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = formation.offsetJoulesPerMole(i);
            assertEquals(Double.doubleToRawLongBits(evaluator.idealGas(i).enthalpy(298.15)), Double.doubleToRawLongBits(offsets[i]),
                    "the offset is the spine's h(298.15 K), its formation enthalpy");
            assertEquals(Double.doubleToRawLongBits(network.spine().orElseThrow().translations()[i]),
                    Double.doubleToRawLongBits(evaluator.translation(i)), "one translation per species, the same in both");
        }
        double[][] compositions = {
                {1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1},
                {0.5, 0.5, 0, 0}, {0.2, 0.8, 0, 0}, {0, 0.6, 0.4, 0}, {0.3, 0, 0, 0.7}, {0, 0.7, 0, 0.3}, {0, 0, 0.5, 0.5},
                {0.1, 0.6, 0.2, 0.1}, {0.25, 0.25, 0.25, 0.25}, {0.02, 0.9, 0.05, 0.03}};
        double[] temperatures = {100, 120, 150, 190, 220, 250, 300, 400, 600, 900, 1200};
        double[] pressures = {1.0e5, 5.0e5, 1.0e6, 2.0e6, 5.0e6, 1.0e7};
        var workspace = evaluator.newWorkspace();
        var state = new PhaseState(n);
        var derivatives = new PhaseDerivatives(n);
        int compared = 0;
        double worstEnthalpy = 0, worstHeatCapacity = 0, worstRebase = 0;
        for (double t : temperatures) {
            var terms = network.prepare(t);
            for (double p : pressures) {
                for (double[] x : compositions) {
                    for (Root root : Root.values()) {
                        // Below a carried species' data (CO2 below its spine's range) both continue on the same held cp.
                        HydrocarbonModel.Phase phase = network.phase(t, p, x, root, terms);
                        evaluator.evaluate(t, p, x, root, workspace, state);
                        String at = t + " K, " + p + " Pa, " + java.util.Arrays.toString(x) + ", " + root;
                        assertEquals(Double.doubleToRawLongBits(state.molarVolume()), Double.doubleToRawLongBits(phase.molarVolume()), "v at " + at);
                        double[] lnPhi = phase.logFugacityView();
                        for (int i = 0; i < n; i++) {
                            assertEquals(Double.doubleToRawLongBits(state.logFugacityCoefficient(i)), Double.doubleToRawLongBits(lnPhi[i]),
                                    "ln phi_" + i + " at " + at);
                        }
                        double formationSum = 0;
                        for (int i = 0; i < n; i++) formationSum += x[i] / sum(x) * offsets[i];
                        double scale = Math.max(Math.abs(state.molarEnthalpy()), Math.max(Math.abs(formationSum), Math.abs(phase.molarEnthalpy())));
                        double defect = Math.abs(state.molarEnthalpy() - formationSum - phase.molarEnthalpy()) / scale;
                        worstEnthalpy = Math.max(worstEnthalpy, defect);
                        assertTrue(defect <= 1.0e-15, "h_network = h_evaluator - sum x DeltafH at " + at + ": " + defect);
                        double[] moles = x.clone();
                        double total = sum(x);
                        for (int i = 0; i < n; i++) moles[i] /= total;
                        double rebased = sensible.rebase(phase.molarEnthalpy(), moles, formation);
                        double rebaseDefect = Math.abs(rebased - state.molarEnthalpy()) / scale;
                        worstRebase = Math.max(worstRebase, rebaseDefect);
                        assertTrue(rebaseDefect <= 1.0e-15, "sensible -> formation rebase at " + at + ": " + rebaseDefect);
                        evaluator.derivatives(t, p, x, root, workspace, derivatives);
                        var values = com.wormzjl.icheme.science.fluid.thermo.HydrocarbonValues.of(network, t, p, x, root);
                        assertEquals(Double.doubleToRawLongBits(derivatives.volumeTemperatureDerivative()),
                                Double.doubleToRawLongBits(values.volumeTemperatureDerivative()), "dv/dT at " + at);
                        assertEquals(Double.doubleToRawLongBits(derivatives.volumePressureDerivative()),
                                Double.doubleToRawLongBits(values.volumePressureDerivative()), "dv/dP at " + at);
                        double cpDefect = Math.abs(derivatives.enthalpyTemperatureDerivative() - values.heatCapacity())
                                / Math.abs(derivatives.enthalpyTemperatureDerivative());
                        worstHeatCapacity = Math.max(worstHeatCapacity, cpDefect);
                        assertTrue(cpDefect <= 1.0e-13, "cp at " + at + ": " + cpDefect);
                        compared++;
                    }
                }
            }
        }
        System.out.printf("WP5 evaluator parity: %d phases, worst |h_eval - sum x DfH - h_net|/scale %.2e,"
                + " rebase %.2e, cp %.2e; v, ln phi, dv/dT, dv/dP bit for bit%n", compared, worstEnthalpy, worstRebase,
                worstHeatCapacity);
        assertTrue(compared > 1000, "compared " + compared);
    }

    @Test
    void theEvaluatorTakesEitherIdealGasAndContinuesOutsideIt() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        // A package without a spine takes its fits on the network's datum (decision E27; it was refused before).
        assertTrue(CubicPhaseEvaluator.forPackage(catalog, PhaseTestSupport.NETWORK).idealGas(0) instanceof CoefficientIdealGas);
        var evaluator = CubicPhaseEvaluator.forPackage(catalog, PILOT);
        var workspace = evaluator.newWorkspace();
        var out = new PhaseState(4);
        // Liquid methane at 85 K, below its spine's data: evaluated on the held cp (owner rule of 2026-10-16), not refused.
        var methane = evaluator.idealGas(1);
        double end = methane.minimumTemperature();
        assertTrue(85.0 < end, "the spine's methane starts at " + end + " K");
        evaluator.evaluate(85.0, 1.0e5, new double[] {0, 1, 0, 0}, Root.LIQUID, workspace, out);
        assertTrue(Double.isFinite(out.molarEnthalpy()) && out.molarVolume() > 0);
        assertEquals(methane.enthalpy(end) + methane.heatCapacity(end) * (85.0 - end), methane.enthalpy(85.0),
                1.0e-12 * Math.abs(methane.enthalpy(end)), "h continues on cp held at " + end + " K");
        // Absent species are not evaluated: pure nitrogen at 70 K is below every other spine.
        evaluator.evaluate(70.0, 1.0e5, new double[] {1, 0, 0, 0}, Root.LIQUID, workspace, out);
        assertTrue(Double.isFinite(out.molarEnthalpy()));
    }

    @Test
    void theContractCarriesThePackageSpineWaterAndCompetition() {
        var catalog = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
        var pilot = catalog.requirePackage(PILOT);
        var contract = PhaseContract.forNetworkPackage(catalog, PILOT);
        assertEquals(catalog.spineRevision(PILOT), contract.identity().spineRevision());
        assertEquals(CubicPhaseEvaluator.FAMILY, contract.identity().evaluatorFamily());
        assertEquals(catalog.fluidRevision(PILOT), contract.identity().packageRevision());
        assertEquals(WaterParticipation.SEPARATE_FREE_WATER, contract.water());
        assertEquals(ThermoIdentity.SEPARATE_FREE_WATER_FORMULATION + ":" + pilot.water().revision(), contract.identity().waterModelRevision());
        assertEquals(Set.of(PhaseCompetition.FLUID_ONLY), contract.qualifiedCompetitions(),
                "the research-only CO2-I crystal qualifies no competition");
        assertEquals(java.util.List.of("Nitrogen", "Methane", "Ethane", "CarbonDioxide", "Water"), contract.components());
        // The network's own identity is the same package on another family (the sensible datum): a different revision.
        var network = FluidTestSupport.model(catalog, PILOT).hydrocarbon;
        var networkIdentity = ThermoIdentity.of(catalog, PILOT, HydrocarbonModel.NETWORK_FAMILY, catalog.spineRevision(PILOT),
                WaterParticipation.SEPARATE_FREE_WATER);
        assertEquals(networkIdentity.revision(), network.revision(), "the network's revision is its identity's");
        assertEquals(HydrocarbonModel.NETWORK_FAMILY, networkIdentity.evaluatorFamily());
        assertEquals(contract.identity().packageRevision(), networkIdentity.packageRevision());
        assertEquals(contract.identity().spineRevision(), networkIdentity.spineRevision());
        assertEquals(contract.identity().waterModelRevision(), networkIdentity.waterModelRevision());
        assertNotEquals(contract.identity().revision(), networkIdentity.revision());
        // A package without a spine: the overload is the P2 form with NO_SPINE.
        var bundled = PhaseContract.forNetworkPackage(catalog, PhaseTestSupport.NETWORK);
        var explicit = PhaseContract.forNetworkPackage(catalog, PhaseTestSupport.NETWORK, ThermoIdentity.NO_SPINE);
        assertEquals(explicit.identity().revision(), bundled.identity().revision());
        assertEquals(explicit.components(), bundled.components());
        assertEquals(explicit.qualifiedCompetitions(), bundled.qualifiedCompetitions());
        assertEquals(ThermoIdentity.NO_SPINE, bundled.identity().spineRevision());
    }

    private static double sum(double[] values) {
        double total = 0;
        for (double value : values) total += value;
        return total;
    }
}
