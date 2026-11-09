package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Condenser carry-over (E31 of batch 2026-11-04-open-thermo-data) on the three-tray column of
 * {@link V3FreeWaterTrayTest}, whose drum at 400 K and 1.5 MPa decants free water.
 *
 * <p>Tray one's free water {@code F_1} leaves upward to the drum as entrained liquid: tray one's vapour carries
 * {@code W_1 = S - F_1}, nothing reaches tray two, the latent heat of the carried water stays on tray one, and the
 * condenser duty falls by exactly that heat while the drum decants the same total water.</p>
 */
class V3CondenserCarryOverTest {
    private static final double STEAM = V3FreeWaterTrayTest.STEAM_MOL_PER_SECOND;

    @Test
    void aDecantingDrumCarriesTrayOnesFreeWaterOverAndNoneOfItFallsToTrayTwo() {
        V3ColumnProblem problem = V3FreeWaterTrayTest.wetProblem(1);
        assertEquals(V3ColumnProblem.WaterCondenserRegime.FREE_WATER, problem.waterCondenserRegime());
        assertTrue(problem.carriesTopTrayWaterOver());
        assertFalse(problem.freeWaterFalls(1));
        assertTrue(problem.freeWaterFalls(2));
        double authored = problem.waterVaporFlowMolPerSecond(1);
        assertEquals(STEAM, authored, 0.0);

        for (double flow : new double[] {1.0e-9, 0.5, 1.5, 4.0, 9.0}) {
            V3DryMeshState state = carrying(problem, flow);
            assertEquals(authored - flow, problem.waterVaporFlow(state, 1), 0.0, "tray one's vapour keeps the rest");
            assertEquals(problem.waterVaporFlowMolPerSecond(2), problem.waterVaporFlow(state, 2), 0.0,
                    "tray two never sees the carried water");
            assertEquals(flow, problem.carriedOverWaterMolPerSecond(state), 0.0);
            assertEquals(0.0, problem.fallingFreeWaterMolPerSecond(state, 1), 0.0);
        }
        // Carrying over all the steam leaves no vapour water: the trial is refused, it is not a state.
        V3DryMeshState everything = carrying(problem, authored);
        assertThrows(IllegalArgumentException.class, () -> problem.waterVaporFlow(everything, 1));

        V3DegreeOfFreedomLedger.Equation saturation = problem.degreeOfFreedomLedger().equations().stream()
                .filter(equation -> equation.id().family() == V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION)
                .findFirst().orElseThrow();
        assertEquals(1, saturation.id().node());
        assertTrue(saturation.referencedUnknowns().contains(new V3DegreeOfFreedomLedger.UnknownId(
                V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW, 1, -1)), "the row's lever is its own water");
    }

    /** {@code SAT(1) = ln(P (S - F) / ((V + S - F) P_sat(T_1)))}: it falls with the carried water. */
    @Test
    void trayOnesSaturationRowReadsTheWaterItCarriesOver() {
        V3ColumnProblem problem = V3FreeWaterTrayTest.wetProblem(1);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, new V3FreeWaterTrayTest.AffineEnthalpyThermo(), 0.0);
        double previous = Double.POSITIVE_INFINITY;
        for (double flow : new double[] {0.5, 1.5, 4.0}) {
            V3DryMeshState state = carrying(problem, flow);
            double row = row(evaluator, state, V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION, 1);
            assertEquals(Math.log(V3FreeWaterTrayTest.ratio(problem, state, 1, STEAM - flow)), row, 1.0e-12);
            assertTrue(row < previous, "more carried water, less supersaturation");
            previous = row;
        }
    }

    @Test
    void theCarriedWatersLatentHeatStaysOnTrayOneAndNeverReachesTrayTwo() {
        V3ColumnProblem problem = V3FreeWaterTrayTest.wetProblem(1);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, new V3FreeWaterTrayTest.AffineEnthalpyThermo(), 0.0);
        double increment = 0.25;
        V3DryMeshState base = carrying(problem, 1.5);
        V3DryMeshState raised = carrying(problem, 1.5 + increment);

        double temperature = base.temperatureKelvin(1);
        double pressure = problem.nodePressurePascal(1);
        double hydrocarbon = V3WetTraySet.hydrocarbonVaporTotal(base, 1);
        double water = problem.waterVaporFlow(base, 1);
        // Out of tray one: less steam in the vapour, more liquid water to the drum.
        double outletChange = V3WaterProperties.steamEnthalpyRate(temperature, pressure, water - increment, hydrocarbon)
                - V3WaterProperties.steamEnthalpyRate(temperature, pressure, water, hydrocarbon)
                + increment * V3WaterProperties.liquidMolarEnthalpy(temperature, pressure);
        assertTrue(-outletChange > 0.0, "condensing water releases its latent heat on tray one");
        assertEquals(-outletChange, V3FreeWaterTrayTest.energy(evaluator, raised, 1)
                - V3FreeWaterTrayTest.energy(evaluator, base, 1), 1.0e-6);
        for (int node = 2; node <= problem.topology().reboilerNode(); node++) {
            assertEquals(0.0, V3FreeWaterTrayTest.energy(evaluator, raised, node)
                    - V3FreeWaterTrayTest.energy(evaluator, base, node), 0.0, "node " + node + " sees none of it");
        }
    }

    @Test
    void waterAndEnergyCloseAcrossTrayOneAndTheDrum() {
        V3FreeWaterTrayTest.AffineEnthalpyThermo thermo = new V3FreeWaterTrayTest.AffineEnthalpyThermo();
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        V3ColumnProblem problem = V3FreeWaterTrayTest.wetProblem(1);
        V3ColumnProblem dry = V3FreeWaterTrayTest.dryProblem();
        double carried = 1.5;
        V3DryMeshState state = carrying(problem, carried);

        // Water: tray one's vapour and its carried liquid are all the steam, and the drum decants the same total.
        V3ColumnProblem.WaterCondenserSplit split = problem.waterCondenserSplit(state);
        assertEquals(STEAM, problem.waterVaporFlow(state, 1) + problem.carriedOverWaterMolPerSecond(state), 1.0e-12);
        assertEquals(STEAM, split.vaporFlowMolPerSecond() + split.freeWaterFlowMolPerSecond(), 1.0e-12);
        assertEquals(dry.waterCondenserSplit(state).freeWaterFlowMolPerSecond(), split.freeWaterFlowMolPerSecond(), 0.0);

        // Energy: the duty falls by exactly the carried water's latent heat at tray one.
        double duty = V3ColumnDutyLedger.condenserDutyWatts(problem, state,
                new V3MeshResidualEvaluator(problem, thermo, 0.0), workspace);
        double dryDuty = V3ColumnDutyLedger.condenserDutyWatts(dry, state,
                new V3MeshResidualEvaluator(dry, thermo, 0.0), workspace);
        double temperature = state.temperatureKelvin(1);
        double pressure = problem.nodePressurePascal(1);
        double hydrocarbon = V3WetTraySet.hydrocarbonVaporTotal(state, 1);
        double latent = V3WaterProperties.steamEnthalpyRate(temperature, pressure, STEAM, hydrocarbon)
                - V3WaterProperties.steamEnthalpyRate(temperature, pressure, STEAM - carried, hydrocarbon)
                - carried * V3WaterProperties.liquidMolarEnthalpy(temperature, pressure);
        assertTrue(latent > 0.0);
        assertEquals(latent, duty - dryDuty, 1.0e-9 * Math.max(Math.abs(duty), Math.abs(dryDuty)));

        // The independent drum closure accepts the duty and refuses one that forgot the carried water.
        double closure = V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE;
        assertTrue(V3AcceptanceAuditor.condenserEnergyBalance(problem, thermo, state, workspace, duty, closure).passed());
        assertFalse(V3AcceptanceAuditor.condenserEnergyBalance(problem, thermo, state, workspace, dryDuty, closure).passed());

        // The whole column: the energy rows sum to the boundary closure, with the carried water leaving the trays
        // at tray one and arriving at the drum, and with falling free water on tray two for comparison.
        assertColumnEnergyCloses(problem, state);
        V3ColumnProblem falling = V3FreeWaterTrayTest.wetProblem(2);
        assertColumnEnergyCloses(falling, V3ColumnInitializer.withFreeWater(V3FreeWaterTrayTest.state(falling.topology()),
                falling.topology(), V3FreeWaterTrayTest.freeWaterProfile(falling, 2, 1.5)));
    }

    /** A drum that cannot decant (all vapour, here the vapour-only branch) takes no carry-over: tray one's water falls. */
    @Test
    void aDrumThatCannotDecantKeepsTrayOnesFreeWaterFalling() {
        V3ColumnProblem dry = V3ColumnProblemResolver.resolve(new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION,
                "test:manufactured", "test:binary", new V3ComponentBasis(List.of("component-a", "component-b")),
                new double[] {30.0, 60.0}, 400.0, 3, 2, V3FreeWaterTrayTest.TOP_PRESSURE_PASCAL, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(0.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)), List.of(),
                List.of(new V3SteamFeedSpec(4, STEAM, 520.0)), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER),
                V3CondenserPhaseBranch.VAPOR_ONLY);
        assertEquals(V3ColumnProblem.WaterCondenserRegime.ALL_VAPOR, dry.waterCondenserRegime());
        assertFalse(dry.carriesTopTrayWaterOver());
        boolean[] mask = new boolean[dry.topology().nodeCount()];
        mask[1] = true;
        V3ColumnProblem wet = V3ColumnProblemResolver.withTruncation(dry, dry.truncationSupport(),
                V3WetTraySet.of(dry.topology(), mask));
        // The shared template carries condenser liquid, which a vapour-only drum refuses: take it on the two-phase
        // twin (same nodes and components) and hand the vapour-only state no condenser liquid.
        V3DryMeshState template = V3FreeWaterTrayTest.state(V3FreeWaterTrayTest.dryProblem().topology());
        double[][] liquid = V3ColumnInitializer.flows(template, true);
        for (int node = 0; node < liquid.length; node++) {
            if (!wet.topology().hasLiquidPhase(node)) liquid[node] = new double[] {0.0, 0.0};
        }
        V3DryMeshState state = new V3DryMeshState(wet.topology(), 2, liquid, V3ColumnInitializer.flows(template, false),
                V3ColumnInitializer.temperatures(template), V3FreeWaterTrayTest.freeWaterProfile(wet, 1, 1.5));

        assertEquals(STEAM, wet.waterVaporFlow(state, 1), 0.0, "the top tray of the block keeps all the steam");
        assertEquals(wet.waterVaporFlowMolPerSecond(2) + 1.5, wet.waterVaporFlow(state, 2), 1.0e-12);
        assertEquals(0.0, wet.carriedOverWaterMolPerSecond(state), 0.0);
        assertEquals(1.5, wet.fallingFreeWaterMolPerSecond(state, 1), 0.0);
    }

    private static void assertColumnEnergyCloses(V3ColumnProblem problem, V3DryMeshState state) {
        V3FreeWaterTrayTest.AffineEnthalpyThermo thermo = new V3FreeWaterTrayTest.AffineEnthalpyThermo();
        V3ThermoWorkspace workspace = thermo.newWorkspace();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        double rows = 0.0;
        for (V3MeshResidual.Row row : evaluator.evaluate(state, workspace).rows()) {
            if (row.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) rows += row.physicalValue();
        }
        V3MeshResidualEvaluator.LocalNodeTerms condenser = evaluator.localTerms(state, 0, workspace);
        double bottoms = evaluator.localTerms(state, problem.topology().reboilerNode(), workspace).liquidPhaseEnergy();
        double duty = V3ColumnDutyLedger.condenserDutyWatts(problem, state, evaluator, workspace);
        double steam = V3ColumnDutyLedger.steamEnthalpyWatts(problem);
        double decanted = V3ColumnDutyLedger.freeWaterEnergyWatts(problem, state);
        // Reflux ratio one returns half the drum liquid; the feed enters at zero enthalpy and the reboiler duty is zero.
        double distillate = 0.5 * condenser.liquidPhaseEnergy();
        double closure = steam + duty - distillate - condenser.vaporPhaseEnergy() - decanted - bottoms;
        double scale = Math.max(Math.max(Math.abs(steam), Math.abs(duty)), Math.max(Math.abs(bottoms),
                Math.abs(condenser.vaporPhaseEnergy())));
        assertEquals(closure, rows, 1.0e-9 * scale, problem.wetTraySet()::toString);
    }

    private static double row(V3MeshResidualEvaluator evaluator, V3DryMeshState state,
            V3DegreeOfFreedomLedger.EquationFamily family, int node) {
        return evaluator.evaluate(state, new V3ThermoWorkspace(2)).rows().stream()
                .filter(row -> row.equation().family() == family && row.equation().node() == node)
                .findFirst().orElseThrow().physicalValue();
    }

    private static V3DryMeshState carrying(V3ColumnProblem problem, double flow) {
        return V3ColumnInitializer.withFreeWater(V3FreeWaterTrayTest.state(problem.topology()), problem.topology(),
                V3FreeWaterTrayTest.freeWaterProfile(problem, 1, flow));
    }
}
