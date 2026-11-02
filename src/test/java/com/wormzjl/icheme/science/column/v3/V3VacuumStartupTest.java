package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Captured false-phase regression, physical phase controls and the zero-reflux/dry-section contract. */
class V3VacuumStartupTest {
    private static JsonObject fixture() throws Exception {
        try (var stream = V3VacuumStartupTest.class.getResourceAsStream("/column/vacuum-startup.json")) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static double[] vector(com.google.gson.JsonArray array) {
        double[] result = new double[array.size()];
        for (int i = 0; i < result.length; i++) result[i] = array.get(i).getAsDouble();
        return result;
    }

    private static V3ColumnInput input(JsonObject fixture, int stages, int feed, double pressure, double reflux,
            double duty, List<V3SteamFeedSpec> steam, List<V3PumparoundSpec> cooling) {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane");
        var source = fixture.getAsJsonObject("source_input");
        return new V3ColumnInput(1,thermo.packageId(),source.get("assayId").getAsString(),thermo.componentBasis(),
                vector(source.getAsJsonArray("feedComponentMolarFlowsMolPerSecond")),628.5790727093818,
                stages,feed,pressure,0,List.of(new V3ColumnSpecification.CondenserOutletTemperature(333.15),
                new V3ColumnSpecification.OrganicRefluxRatio(reflux),new V3ColumnSpecification.ReboilerDuty(duty)),
                List.of(),steam,cooling,15);
    }

    private static V3DryMeshState state(V3ColumnProblem problem, JsonObject captured) {
        int nodes = problem.topology().nodeCount(), components = problem.activeComponentBasis().componentCount();
        double[][] liquid = new double[nodes][], vapor = new double[nodes][];
        for (int n = 0; n < nodes; n++) {
            liquid[n] = vector(captured.getAsJsonArray("liquid").get(n).getAsJsonArray());
            vapor[n] = vector(captured.getAsJsonArray("vapor").get(n).getAsJsonArray());
        }
        return new V3DryMeshState(problem.topology(),components,liquid,vapor,vector(captured.getAsJsonArray("temperature")));
    }

    @Test
    void rejectsTheCapturedLiquidMasqueradingAsVaporEvenThoughItsMeshBalancesPass() throws Exception {
        var fixture = fixture();
        var input = input(fixture,4,3,150000,0.5,1878930.312805997,List.of(),List.of());
        var original = V3ColumnProblemResolver.resolve(input,V3CondenserPhaseBranch.LIQUID_ONLY);
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        var captured = state(original,fixture.getAsJsonObject("collapsed"));
        var support = V3TruncationSupport.derive(original,0,captured);
        var problem = V3ColumnProblemResolver.withTruncation(original,support,V3WetTraySet.dry(original.topology()));
        var audit = new V3AcceptanceAuditor(problem,thermo,229401.2363470333,0.001)
                .audit(support.projectSeed(original,captured),thermo.newWorkspace(),V3SolveControl.UNBOUNDED);
        var identity = audit.checks().stream().filter(c -> c.family().equals("INTERIOR_PHASE_IDENTITY")).findFirst().orElseThrow();
        assertFalse(identity.passed(),identity.detail());
        assertFalse(audit.accepted());
        assertTrue(audit.checks().stream().filter(c -> !c.family().equals("INTERIOR_PHASE_IDENTITY")).allMatch(V3AcceptanceAudit.Check::passed));
    }

    @Test
    void rejectsTheEnergyWorseningProjectionOfTheCapturedStageHandoff() throws Exception {
        var fixture = fixture();
        var input = input(fixture,8,6,150000,0.5,1878930.312805997,List.of(),List.of());
        var problem = V3ColumnProblemResolver.resolve(input,V3CondenserPhaseBranch.LIQUID_ONLY);
        var seed = state(problem,fixture.getAsJsonObject("interpolated"));
        assertSame(seed,V3ColumnCalculator.projectedSeedOrPrevious(problem,
                V3PengRobinsonThermo.fromRegisteredPackage(input.packageId()),seed,V3SolveControl.UNBOUNDED));
    }

    @Test
    void compilesTheDryTopAsVaporMaterialAndEnergyEquationsWithoutDummyLiquidOrVle() throws Exception {
        var input = input(fixture(),10,8,2000,0,0,List.of(),List.of(new V3PumparoundSpec(2,3,-1e6,V3PumparoundSpec.Split.UNIFORM)));
        var problem = V3ColumnProblemResolver.resolve(input,V3CondenserPhaseBranch.LIQUID_ONLY);
        assertEquals(1,problem.topology().dryTopTrayCount());
        assertFalse(problem.topology().hasLiquidPhase(1));
        assertTrue(problem.topology().hasVaporPhase(1));
        assertTrue(problem.degreeOfFreedomLedger().isValid(),problem.degreeOfFreedomLedger().humanReadableDiagnostic());
        assertTrue(problem.degreeOfFreedomLedger().unknowns().stream().noneMatch(u -> u.id().node() == 1
                && u.id().family() == V3DegreeOfFreedomLedger.UnknownFamily.LIQUID_COMPONENT_FLOW));
        assertTrue(problem.degreeOfFreedomLedger().equations().stream().noneMatch(e -> e.id().node() == 1
                && e.id().family() == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM));
    }

    @Test
    void aZeroRefluxSteamColumnPublishesWithAnExactlyDryUpperSection() throws Exception {
        var input = input(fixture(),10,8,2000,0,0,List.of(new V3SteamFeedSpec(11,52.369498711815055,533.15)),List.of());
        var outcome = V3ColumnCalculator.calculateWithStrategy(input,V3SolveControl.UNBOUNDED,V3SolveStrategy.AGGRESSIVE,null,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        var success = assertInstanceOf(V3ColumnOutcome.Success.class,outcome,outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(7,success.result().problem().topology().dryTopTrayCount());
        for (int n = 1; n <= 7; n++) {
            var node = success.result().inspection().orElseThrow().nodes().get(n);
            assertEquals(0.0,node.liquidMolPerSecond());
            assertTrue(node.vaporMolPerSecond() > 0);
            assertDryVapor(input,node);
        }
    }

    @Test
    void aDryTrayHasOnlyGasPressureDrop() {
        var terms = V3TrayHydraulics.ofDiameter(15).terms(new V3TrayHydraulics.Tray(200,80,600,0,0,700,0.5,0),2000);
        assertTrue(terms.dryPascal() > 0);
        assertEquals(0.0,terms.liquidPascal());
        assertEquals(0.0,terms.residualPascal());
    }

    @Test
    void aZeroRefluxVacuumColumnWithSideCutsAndPumparoundsPublishesADryTop() throws Exception {
        var base = input(fixture(),10,8,2000,0,0,List.of(new V3SteamFeedSpec(11,52.369498711815055,533.15)),List.of(
                new V3PumparoundSpec(2,3,-10e6,V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(5,6,-15e6,V3PumparoundSpec.Split.UNIFORM)));
        double total = java.util.Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum();
        var input = new V3ColumnInput(base.schemaVersion(),base.packageId(),base.assayId(),base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(),base.feedTemperatureKelvin(),10,8,2000,0,base.specifications(),
                List.of(new V3SideDrawSpec(3,0.1*total),new V3SideDrawSpec(6,0.25*total)),base.steamFeeds(),base.pumparounds(),15);
        var outcome = V3ColumnCalculator.calculateWithStrategy(input,V3SolveControl.UNBOUNDED,V3SolveStrategy.AGGRESSIVE,null,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        var success = assertInstanceOf(V3ColumnOutcome.Success.class,outcome,outcome::toString);
        assertEquals(0.0,success.result().inspection().orElseThrow().nodes().get(1).liquidMolPerSecond());
        assertDryVapor(input,success.result().inspection().orElseThrow().nodes().get(1));
        assertTrue(success.result().acceptanceAudit().accepted());
        assertEquals(2,success.result().problem().input().sideDraws().size());
        assertTrue(success.result().streams().stream().anyMatch(s -> s.streamId().equals("side_liquid_tray_06")));
    }

    private static void assertDryVapor(V3ColumnInput input, V3ColumnInspection.Node node) {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        double hydrocarbonPressure = node.pressurePascal() * node.vaporMolPerSecond()
                / (node.vaporMolPerSecond() + node.waterVaporMolPerSecond());
        var flash = thermo.flashTP(node.temperatureKelvin(),hydrocarbonPressure,
                node.vaporFractions().stream().mapToDouble(Double::doubleValue).toArray(),thermo.newWorkspace());
        assertEquals(com.wormzjl.icheme.science.column.v3.thermo.V3FeedPhase.VAPOR,flash.phase());
    }

    @Test
    void continuesAcrossTheCaloricFitEdgeWhileKeepingTheNumericalBox() {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane");
        double[] z = new double[thermo.componentBasis().componentCount()]; z[15] = 1;
        double eps = 1e-3;
        double below = thermo.molarEnthalpy(900-eps,2000,z,Root.VAPOR,thermo.newWorkspace());
        double edge = thermo.molarEnthalpy(900,2000,z,Root.VAPOR,thermo.newWorkspace());
        double above = thermo.molarEnthalpy(900+eps,2000,z,Root.VAPOR,thermo.newWorkspace());
        assertEquals((edge-below)/eps,(above-edge)/eps,0.1);
        assertThrows(com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException.class,
                () -> thermo.fugacity(NumericalBox.MAXIMUM_TEMPERATURE+1,2000,z,Root.VAPOR,thermo.newWorkspace()));
    }
}
