package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The free-water tray contract on a hand-built three-tray column.
 *
 * <p>The fixture is the manufactured binary of {@link V3MeshResidualEvaluatorTest} lifted to 1.5 MPa with
 * 10 mol/s of sump steam. At the tray-one temperature of 410 K water saturates at 349 kPa, so a vapour
 * carrying 10 of 40 mol/s of water at 1.5 MPa is supersaturated and the tray takes a free-water phase.</p>
 *
 * <p>The drum at 400 K and 1.5 MPa decants free water, so tray one is a carry-over tray (E31 of batch
 * 2026-11-04-open-thermo-data): its free water leaves for the drum, which {@link V3CondenserCarryOverTest} covers.
 * The contract of water that falls to the tray below is pinned here on tray two.</p>
 */
class V3FreeWaterTrayTest {
    static final double STEAM_MOL_PER_SECOND = 10.0;
    static final double TOP_PRESSURE_PASCAL = 1_500_000.0;

    @Test
    void aWetTrayAddsExactlyOneUnknownAndOneRowToItsOwnStageBlock() {
        V3ColumnProblem dry = dryProblem();
        V3ColumnProblem wet = wetProblem(2);

        assertEquals(dry.degreeOfFreedomLedger().unknownCount() + 1, wet.degreeOfFreedomLedger().unknownCount());
        assertEquals(dry.degreeOfFreedomLedger().equationCount() + 1, wet.degreeOfFreedomLedger().equationCount());
        assertTrue(wet.degreeOfFreedomLedger().isValid(), wet.degreeOfFreedomLedger().humanReadableDiagnostic());
        assertTrue(wet.degreeOfFreedomLedger().hasFullStructuralRank());
        assertEquals(new V3StageBlockLayout(dry).size(2) + 1, new V3StageBlockLayout(wet).size(2));
        for (int node = 0; node < wet.topology().nodeCount(); node++) {
            if (node == 2) continue;
            assertEquals(new V3StageBlockLayout(dry).size(node), new V3StageBlockLayout(wet).size(node),
                    "only the wet tray's block changes size");
        }

        V3DegreeOfFreedomLedger.UnknownId freeWater = new V3DegreeOfFreedomLedger.UnknownId(
                V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW, 2, -1);
        assertTrue(wet.degreeOfFreedomLedger().unknowns().stream().anyMatch(unknown -> unknown.id().equals(freeWater)));
        V3DegreeOfFreedomLedger.Equation saturation = wet.degreeOfFreedomLedger().equations().stream()
                .filter(equation -> equation.id().family() == V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION)
                .findFirst().orElseThrow();
        assertEquals(2, saturation.id().node());
        // Water condensed on tray two falls to tray three and comes back up, so the row cannot reference it.
        assertFalse(saturation.referencedUnknowns().contains(freeWater));
        assertTrue(saturation.referencedUnknowns().contains(new V3DegreeOfFreedomLedger.UnknownId(
                V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE, 2, -1)));
        assertTrue(saturation.referencedUnknowns().contains(new V3DegreeOfFreedomLedger.UnknownId(
                V3DegreeOfFreedomLedger.UnknownFamily.VAPOR_COMPONENT_FLOW, 2, 0)));
        // The tray below is where the shed water goes, so that tray's rows carry it.
        V3DegreeOfFreedomLedger.Equation energyBelow = wet.degreeOfFreedomLedger().equations().stream()
                .filter(equation -> equation.id().family() == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE
                        && equation.id().node() == 3).findFirst().orElseThrow();
        assertTrue(energyBelow.referencedUnknowns().contains(freeWater));
    }

    @Test
    void theSaturationRowIsZeroWhenTheTrayVaporSitsExactlyOnTheWaterSaturationLine() {
        V3ColumnProblem problem = wetProblem(1);
        V3DryMeshState saturated = saturatedState(problem);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, new AffineEnthalpyThermo(), 0.0);

        V3MeshResidual.Row row = evaluator.evaluate(saturated, new com.wormzjl.icheme.science.column.v3.thermo
                .V3ThermoWorkspace(2)).rows().stream()
                .filter(candidate -> candidate.equation().family()
                        == V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION).findFirst().orElseThrow();

        assertEquals(1.0, row.scale(), 0.0, "a saturation row is scaled like the equilibrium rows");
        assertEquals(0.0, row.physicalValue(), 1.0e-12);
    }

    @Test
    void theTrayEnergyRowsCarryTheFreeWaterLatentHeatFromTheTrayBelowToTheWetTray() {
        V3ColumnProblem problem = wetProblem(2);
        V3DryMeshState base = V3ColumnInitializer.withFreeWater(state(problem.topology()), problem.topology(),
                freeWaterProfile(problem, 2, 1.5));
        double increment = 0.25;
        V3DryMeshState raised = V3ColumnInitializer.withFreeWater(base, problem.topology(),
                freeWaterProfile(problem, 2, 1.5 + increment));
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, new AffineEnthalpyThermo(), 0.0);

        // The steam is at its partial pressure on tray three, which the extra water raises, so its enthalpy is the
        // difference of two rates rather than one molar value.
        double water = problem.waterVaporFlow(base, 3);
        double hydrocarbon = V3WetTraySet.hydrocarbonVaporTotal(base, 3);
        double pressure = problem.nodePressurePascal(3);
        double latent = (V3WaterProperties.steamEnthalpyRate(base.temperatureKelvin(3), pressure, water + increment, hydrocarbon)
                - V3WaterProperties.steamEnthalpyRate(base.temperatureKelvin(3), pressure, water, hydrocarbon)) / increment
                - V3WaterProperties.liquidMolarEnthalpy(base.temperatureKelvin(2), problem.nodePressurePascal(2));
        // Free water leaves tray two as liquid and comes back up out of tray three as vapour, so admitting more of it
        // moves exactly its latent heat from the tray below into the wet tray.
        assertEquals(increment * latent, energy(evaluator, raised, 2) - energy(evaluator, base, 2), 1.0e-6);
        assertEquals(-increment * latent, energy(evaluator, raised, 3) - energy(evaluator, base, 3), 1.0e-6);
        // Nothing below the tray that receives it sees the circulation at all, and nothing above the wet tray.
        assertEquals(0.0, energy(evaluator, raised, 4) - energy(evaluator, base, 4), 1.0e-9);
        assertEquals(0.0, energy(evaluator, raised, 1) - energy(evaluator, base, 1), 1.0e-9);
    }

    @Test
    void theFreeWaterCoordinateRoundTripsThroughTheScaledLogFlowMap() {
        V3ColumnProblem problem = wetProblem(1);
        V3DryMeshState state = saturatedState(problem);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);

        double[] encoded = coordinates.encode(state);
        V3DryMeshState decoded = coordinates.decode(encoded);

        assertEquals(new V3StageBlockLayout(problem).size(0) + new V3StageBlockLayout(problem).size(1)
                + new V3StageBlockLayout(problem).size(2) + new V3StageBlockLayout(problem).size(3)
                + new V3StageBlockLayout(problem).size(4), encoded.length);
        assertEquals(state.freeWaterFlow(1), decoded.freeWaterFlow(1), 1.0e-12 * state.freeWaterFlow(1));
        for (int node = 0; node < problem.topology().nodeCount(); node++) {
            if (node != 1) assertEquals(0.0, decoded.freeWaterFlow(node), 0.0, "a dry node decodes exactly zero");
            assertEquals(state.temperatureKelvin(node), decoded.temperatureKelvin(node), 1.0e-12);
        }
        // The free-water coordinate is the log of the flow against the total steam fed.
        int index = 0;
        for (V3DegreeOfFreedomLedger.Unknown unknown : coordinates.unknowns()) {
            if (unknown.id().family() == V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW) break;
            index++;
        }
        assertEquals(Math.log(state.freeWaterFlow(1) / STEAM_MOL_PER_SECOND), encoded[index], 1.0e-12);
        assertEquals(STEAM_MOL_PER_SECOND, problem.freeWaterFlowScaleMolPerSecond(), 0.0);
    }

    @Test
    void aStateCannotCarryFreeWaterOnANodeThatIsNotAnEquilibriumTray() {
        V3ColumnTopology topology = wetProblem(1).topology();
        double[] onTheSump = new double[topology.nodeCount()];
        onTheSump[topology.reboilerNode()] = 1.0;

        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnInitializer.withFreeWater(saturatedState(wetProblem(1)), topology, onTheSump));
    }

    /**
     * The derivation admits tray one from its ratio with all the steam, and seeds it with exactly the water it
     * must carry over to sit on the line; that water goes to the drum, so tray two still reads the authored steam.
     */
    @Test
    void theWetSetIsDerivedFromTheSaturationRatioAndTrayOnesSeedPutsItOnTheLine() {
        V3ColumnProblem problem = dryProblem();
        V3DryMeshState supersaturated = state(problem.topology());

        V3WetTraySet derived = V3WetTraySet.derive(problem, supersaturated, V3WetTraySet.dry(problem.topology()));

        assertEquals(List.of(1), derived.wetTrays());
        assertFalse(derived.isWet(problem.topology().reboilerNode()), "the sump is never wet");
        V3ColumnProblem wet = V3ColumnProblemResolver.withTruncation(problem, problem.truncationSupport(), derived);
        V3DryMeshState seeded = derived.seed(wet, supersaturated);
        assertTrue(seeded.freeWaterFlow(1) > 0.0);
        assertEquals(1.0, ratio(wet, seeded, 1, wet.waterVaporFlow(seeded, 1)), 1.0e-12,
                "the seed carries over exactly the water above the line");
        assertEquals(wet.waterVaporFlowMolPerSecond(2), wet.waterVaporFlow(seeded, 2), 0.0,
                "carried water never reaches tray two");
    }

    @Test
    void aWetTrayStaysWetUntilItsFreeWaterFallsBelowTheDryFloorAndADryTrayNeedsTheEntryHysteresis() {
        V3ColumnProblem wet = wetProblem(1);
        V3WetTraySet current = wet.wetTraySet();
        V3DryMeshState saturated = saturatedState(wet);

        // Exactly saturated: the ratio is one, so a dry tray would not be admitted, but a wet one stays.
        assertTrue(V3WetTraySet.derive(wet, saturated, current).isWet(1));
        V3DryMeshState emptied = V3ColumnInitializer.withFreeWater(saturated, wet.topology(),
                freeWaterProfile(wet, 1, 0.5 * V3WetTraySet.DRY_EXIT_FRACTION * STEAM_MOL_PER_SECOND));
        assertFalse(V3WetTraySet.derive(wet, emptied, current).isWet(1), "a dried-out wet tray leaves the set");

        V3ColumnProblem dry = dryProblem();
        V3DryMeshState onTheLine = saturatedState(dry);
        assertFalse(V3WetTraySet.derive(dry, onTheLine, V3WetTraySet.dry(dry.topology())).isWet(1),
                "a tray exactly on the saturation line is not admitted without the entry hysteresis");
        assertTrue(V3WetTraySet.derive(dry, state(dry.topology()), V3WetTraySet.dry(dry.topology())).isWet(1));
    }

    @Test
    void aColumnWithoutSteamHasNoWetTraysAndNoFreeWaterCoordinate() {
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION,
                "test:manufactured", "test:binary", new V3ComponentBasis(List.of("component-a", "component-b")),
                new double[] {30.0, 60.0}, 400.0, 3, 2, TOP_PRESSURE_PASCAL, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(1.0)), List.of(), List.of(), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER),
                V3CondenserPhaseBranch.TWO_PHASE);

        assertFalse(problem.hasWetTrays());
        assertEquals(1.0, problem.freeWaterFlowScaleMolPerSecond(), 0.0);
        assertFalse(V3WetTraySet.derive(problem, state(problem.topology()), V3WetTraySet.dry(problem.topology()))
                .hasWetTrays());
        assertTrue(problem.degreeOfFreedomLedger().unknowns().stream().noneMatch(unknown -> unknown.id().family()
                == V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW));
    }

    /**
     * A parametric wet set is the dry ledger plus a known water flow: no unknown, no row, same blocks.
     *
     * <p>This is the sub-problem {@link V3FreeWaterContinuation} solves. The free water still has to reach
     * every row that reads it, and it is no longer in the Newton vector, so the coordinate map has to put it
     * back on decode — without that a single Newton step would silently dry the tray out.</p>
     */
    @Test
    void aParametricWetTrayCarriesItsFreeWaterThroughTheRowsWithoutAnUnknownOrASaturationRow() {
        V3ColumnProblem dry = dryProblem();
        V3ColumnProblem parametric = parametricProblem(2, 1.5);

        assertTrue(parametric.isWetTray(2));
        assertFalse(parametric.hasFreeWaterUnknown(2));
        assertEquals(dry.degreeOfFreedomLedger().unknownCount(), parametric.degreeOfFreedomLedger().unknownCount());
        assertEquals(dry.degreeOfFreedomLedger().equationCount(), parametric.degreeOfFreedomLedger().equationCount());
        assertTrue(parametric.degreeOfFreedomLedger().hasFullStructuralRank());
        assertTrue(parametric.degreeOfFreedomLedger().unknowns().stream().noneMatch(unknown -> unknown.id().family()
                == V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW));
        assertTrue(parametric.degreeOfFreedomLedger().equations().stream().noneMatch(equation -> equation.id().family()
                == V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION));
        for (int node = 0; node < parametric.topology().nodeCount(); node++) {
            assertEquals(new V3StageBlockLayout(dry).size(node), new V3StageBlockLayout(parametric).size(node));
        }

        // Seeding writes the parameter, and a coordinate round trip has to preserve it.
        V3DryMeshState seeded = parametric.wetTraySet().seed(parametric, state(parametric.topology()));
        assertEquals(1.5, seeded.freeWaterFlow(2), 1.0e-12);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(parametric);
        assertEquals(1.5, coordinates.decode(coordinates.encode(seeded)).freeWaterFlow(2), 1.0e-12);

        // The tray below sees the latent heat of the frozen flow exactly as it sees a solved one.
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(parametric, new AffineEnthalpyThermo(), 0.0);
        V3DryMeshState empty = V3ColumnInitializer.withFreeWater(seeded, parametric.topology(),
                freeWaterProfile(parametric, 2, 0.0));
        assertTrue(Math.abs(energy(evaluator, seeded, 3) - energy(evaluator, empty, 3)) > 1.0,
                "the frozen free water reaches the tray below's energy row");
    }

    /**
     * A dry stage below its dew point is a warning; an inconsistent wet tray is a rejection, and the larger
     * advisory must not decide the severity of the other check.
     *
     * <p>Both regimes are reported on one scale — a dry stage as its saturation ratio against one, a wet tray as
     * its deviation from the saturation line divided by the convergence closure — and the audit used to fail
     * only when the single worst node happened to be wet. Here tray one misses saturation by 1.5 closures while
     * dry tray two, pushed to 390 K, reads a ratio of about 2.1. Ranking them together names tray two and
     * publishes a warning; the wet-set inconsistency the state actually carries would be lost.</p>
     */
    @Test
    void aSupersaturatedDryTrayDoesNotDowngradeAnInconsistentWetTrayToAWarning() {
        V3ColumnProblem problem = wetProblem(1);
        double closure = 0.001;
        V3AcceptanceAuditor auditor = new V3AcceptanceAuditor(problem, new AffineEnthalpyThermo(), 0.0, closure);
        V3DryMeshState mixed = withWetRatioAndHotDryTray(problem, 1.0 + 1.5 * closure, 390.0);

        V3AcceptanceAudit.Check check = auditor.waterDewPoint(mixed);

        assertFalse(check.passed(), check::toString);
        assertTrue(check.detail().contains("tray 2"), check::detail);
        assertFalse(check.detail().startsWith("warning: "), check::detail);
        assertEquals(1.5, check.value(), 1.0e-9, check::detail);
        // The dry advisory that used to win the ranking is still the larger number on the shared scale.
        assertTrue(saturationRatio(problem, mixed, 2) > check.value(),
                () -> "dry tray 2 ratio " + saturationRatio(problem, mixed, 2));
    }

    /** With every wet tray consistent, the same supersaturated dry tray is published as its warning. */
    @Test
    void aConsistentWetTrayLeavesTheSupersaturatedDryTrayAsAPassingWarning() {
        V3ColumnProblem problem = wetProblem(1);
        V3AcceptanceAuditor auditor = new V3AcceptanceAuditor(problem, new AffineEnthalpyThermo(), 0.0, 0.001);
        V3DryMeshState mixed = withWetRatioAndHotDryTray(problem, 1.0, 390.0);

        V3AcceptanceAudit.Check check = auditor.waterDewPoint(mixed);

        assertTrue(check.passed(), check::toString);
        assertTrue(check.detail().startsWith("warning: tray 3 "), check::detail);
        assertEquals(saturationRatio(problem, mixed, 2), check.value(), 1.0e-9, check::detail);
        assertTrue(check.value() > 1.0, check::detail);
    }

    /** Tray one's hydrocarbon vapour is retuned to the requested saturation ratio and tray two is heated. */
    private static V3DryMeshState withWetRatioAndHotDryTray(
            V3ColumnProblem problem, double wetSaturationRatio, double dryTemperatureKelvin) {
        V3DryMeshState base = saturatedState(problem);
        double water = problem.waterVaporFlow(base, 1);
        double saturationPressure = V3WaterProperties.saturationPressurePascal(base.temperatureKelvin(1));
        double hydrocarbon = problem.nodePressurePascal(1) * water / (saturationPressure * wetSaturationRatio) - water;
        double[][] vapor = V3ColumnInitializer.flows(base, false);
        vapor[1][0] = 0.5 * hydrocarbon;
        vapor[1][1] = 0.5 * hydrocarbon;
        double[] temperatures = V3ColumnInitializer.temperatures(base);
        temperatures[2] = dryTemperatureKelvin;
        return new V3DryMeshState(problem.topology(), 2, V3ColumnInitializer.flows(base, true), vapor, temperatures,
                freeWaterProfile(problem, 1, base.freeWaterFlow(1)));
    }

    private static double saturationRatio(V3ColumnProblem problem, V3DryMeshState state, int node) {
        return ratio(problem, state, node, problem.waterVaporFlow(state, node));
    }

    static double energy(V3MeshResidualEvaluator evaluator, V3DryMeshState state, int node) {
        return evaluator.evaluate(state, new com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace(2))
                .rows().stream()
                .filter(row -> row.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE
                        && row.equation().node() == node)
                .findFirst().orElseThrow().physicalValue();
    }

    static double ratio(V3ColumnProblem problem, V3DryMeshState state, int node, double water) {
        double hydrocarbon = V3WetTraySet.hydrocarbonVaporTotal(state, node);
        return problem.nodePressurePascal(node) * water / ((hydrocarbon + water)
                * V3WaterProperties.saturationPressurePascal(state.temperatureKelvin(node)));
    }

    static double[] freeWaterProfile(V3ColumnProblem problem, int tray, double flow) {
        double[] profile = new double[problem.topology().nodeCount()];
        profile[tray] = flow;
        return profile;
    }

    /**
     * Tray one carries 1.5 mol/s of free water and exactly the hydrocarbon vapour that puts its water on the
     * saturation line: with all the steam on a dry tray one, with the steam less the carried water on a wet one.
     */
    static V3DryMeshState saturatedState(V3ColumnProblem problem) {
        V3DryMeshState base = V3ColumnInitializer.withFreeWater(state(problem.topology()), problem.topology(),
                freeWaterProfile(problem, 1, 1.5));
        double water = problem.waterVaporFlow(base, 1);
        double saturationPressure = V3WaterProperties.saturationPressurePascal(base.temperatureKelvin(1));
        double hydrocarbon = water * (problem.nodePressurePascal(1) - saturationPressure) / saturationPressure;
        double[][] vapor = V3ColumnInitializer.flows(base, false);
        double scale = hydrocarbon / (vapor[1][0] + vapor[1][1]);
        vapor[1][0] *= scale;
        vapor[1][1] *= scale;
        return new V3DryMeshState(problem.topology(), 2, V3ColumnInitializer.flows(base, true), vapor,
                V3ColumnInitializer.temperatures(base), freeWaterProfile(problem, 1, 1.5));
    }

    static V3ColumnProblem dryProblem() {
        return V3ColumnProblemResolver.resolve(new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured",
                "test:binary", new V3ComponentBasis(List.of("component-a", "component-b")),
                new double[] {30.0, 60.0}, 400.0, 3, 2, TOP_PRESSURE_PASCAL, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)), List.of(),
                List.of(new V3SteamFeedSpec(4, STEAM_MOL_PER_SECOND, 520.0)), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER),
                V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3ColumnProblem parametricProblem(int tray, double freeWaterMolPerSecond) {
        V3ColumnProblem dry = dryProblem();
        boolean[] wet = new boolean[dry.topology().nodeCount()];
        wet[tray] = true;
        double[] flows = new double[dry.topology().nodeCount()];
        flows[tray] = freeWaterMolPerSecond;
        return V3ColumnProblemResolver.withTruncation(dry, dry.truncationSupport(),
                V3WetTraySet.parametric(dry.topology(), wet, flows));
    }

    static V3ColumnProblem wetProblem(int... trays) {
        V3ColumnProblem dry = dryProblem();
        boolean[] wet = new boolean[dry.topology().nodeCount()];
        for (int tray : trays) wet[tray] = true;
        return V3ColumnProblemResolver.withTruncation(dry, dry.truncationSupport(),
                V3WetTraySet.of(dry.topology(), wet));
    }

    /** Affine molar enthalpy and unit fugacity coefficients: the energy rows are exact arithmetic. */
    static final class AffineEnthalpyThermo implements com.wormzjl.icheme.science.column.v3.thermo.V3ThermoModel {
        private final V3ComponentBasis basis = new V3ComponentBasis(List.of("component-a", "component-b"));

        @Override public V3ComponentBasis componentBasis() { return basis; }

        @Override public com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace newWorkspace() {
            return new com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace(2);
        }

        @Override
        public com.wormzjl.icheme.science.column.v3.thermo.V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition,
                Root phase,
                com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace workspace) {
            return new com.wormzjl.icheme.science.column.v3.thermo.V3FugacityResult(phase,
                    new double[] {0.0, 0.0}, 1.0,
                    molarEnthalpy(temperatureKelvin, pressurePascal, composition, phase, workspace), 1, 0.0);
        }

        @Override
        public double molarEnthalpy(
                double temperatureKelvin, double pressurePascal, double[] composition,
                Root phase,
                com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace workspace) {
            return temperatureKelvin * (30.0 * composition[0] + 40.0 * composition[1]);
        }

        @Override
        public com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult flashTP(
                double temperatureKelvin, double pressurePascal, double[] overallComposition,
                com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace workspace) {
            throw new UnsupportedOperationException("The affine enthalpy test model does not implement a flash");
        }
    }

    static V3DryMeshState state(V3ColumnTopology topology) {
        return new V3DryMeshState(topology, 2, new double[][] {
                {10.0, 10.0}, {5.0, 5.0}, {35.0, 65.0}, {35.0, 65.0}, {17.0, 53.0}
        }, new double[][] {
                {8.0, 2.0}, {15.0, 15.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}
        }, new double[] {400.0, 410.0, 420.0, 430.0, 440.0});
    }
}
