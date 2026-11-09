package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The entrainment limit of condenser carry-over (E31 of batch 2026-11-04-open-thermo-data).
 *
 * <p>The limit is {@code f * G / M_water}: tray one's vapour lifts at most its own mass of liquid at flood, in
 * proportion to its Fair flood fraction below it. Above the limit the column still publishes (owner decision E31,
 * 2026-11-05, option (a)), with a warning that names the tray, the carried water and the limit. Without a column
 * diameter there are no hydraulics and the carry-over is unlimited.</p>
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3CarryOverEntrainmentTest {
    private static final double WATER_MOLECULAR_WEIGHT_KG_PER_MOL = 0.01801528;
    private static final double CARRIED_MOL_PER_SECOND = 30.0;

    @Test
    void theEntrainmentLimitIsTheFloodFractionTimesTheVapourMassAndFallsWithTheVapourLoad() {
        V3TrayHydraulics.Tray tray = new V3TrayHydraulics.Tray(1_200.0, 60.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);
        double previous = Double.POSITIVE_INFINITY;
        for (double diameter : new double[] {3.0, 8.0, 20.0}) {
            V3TrayHydraulics hydraulics = V3TrayHydraulics.ofDiameter(diameter);
            double flood = hydraulics.terms(tray, 200_000.0).floodFraction();
            double limit = hydraulics.entrainmentLimitMolPerSecond(tray, 200_000.0);
            assertEquals(flood * 60.0 / WATER_MOLECULAR_WEIGHT_KG_PER_MOL, limit, 1.0e-12 * limit);
            assertTrue(limit < previous, "a wider column moves the vapour slower and lifts less");
            previous = limit;
        }
        V3TrayHydraulics.Tray still = new V3TrayHydraulics.Tray(0.0, 0.0, 420.0, 90.0, 0.12, 650.0, 0.45, 0.0);
        assertEquals(0.0, V3TrayHydraulics.ofDiameter(8.0).entrainmentLimitMolPerSecond(still, 200_000.0), 0.0,
                "no vapour lifts no water");

        String warning = V3TrayHydraulics.ofDiameter(8.0).carryOverWarning(1, 100.0 / 3.6, 50.0 / 3.6, 0.24);
        assertTrue(warning.startsWith("tray 2 carries 100.0 kmol/h of free water over to the condenser drum"), warning);
        assertTrue(warning.contains("above the 50.0 kmol/h") && warning.contains("24% of flood in a 8.0 m column"), warning);
        assertTrue(("Warning: " + warning).length() <= 256, "the advisory publishes it whole");
    }

    /** The widest column the input admits, with a tenth of the initializer's tray-one vapour: a slow vapour. */
    @Test
    void carriedWaterAboveTheLimitPublishesAWarningThatNamesTheTrayTheWaterAndTheLimit() {
        Case wide = carryOverCase(V3ColumnInput.MAX_COLUMN_DIAMETER_METRES, 0.1);
        V3AcceptanceAudit.Check check = wide.auditor().carryOver(wide.state());
        assertTrue(check.passed(), "above the limit the column still publishes (option (a))");
        assertTrue(check.value() > 1.0, check::detail);
        assertTrue(check.detail().startsWith("warning: tray 2 carries 108.0 kmol/h of free water over"), check::detail);
        assertTrue(check.detail().contains("of flood in a 15.0 m column"), check::detail);

        V3AcceptanceAudit audit = wide.auditor().audit(wide.state(), wide.thermo().newWorkspace(), V3SolveControl.UNBOUNDED);
        assertTrue(audit.checks().stream().anyMatch(candidate -> candidate.family().equals("CARRY_OVER")));
        String advisory = audit.advisoryEvidence().stream().filter(line -> line.startsWith("Warning: tray 2 carries"))
                .findFirst().orElseThrow(() -> new AssertionError(String.join(" | ", audit.advisoryEvidence())));
        assertTrue(advisory.endsWith("or cut the stripping steam"), advisory);
    }

    @Test
    void carriedWaterBelowTheLimitOrWithoutHydraulicsIsAPlainReading() {
        Case narrow = carryOverCase(2.0, 1.0);
        V3AcceptanceAudit.Check check = narrow.auditor().carryOver(narrow.state());
        assertTrue(check.passed() && check.value() <= 1.0, check::detail);
        assertFalse(check.detail().startsWith("warning: "), check::detail);
        assertTrue(check.detail().contains("its vapor can lift"), check::detail);

        Case prescribed = carryOverCase(V3ColumnInput.PRESCRIBED_DROP_DIAMETER, 0.1);
        V3AcceptanceAudit.Check unlimited = prescribed.auditor().carryOver(prescribed.state());
        assertTrue(unlimited.passed(), unlimited::detail);
        assertEquals(0.0, unlimited.value(), 0.0);
        assertTrue(unlimited.detail().endsWith("no entrainment limit without a column diameter"), unlimited::detail);
    }

    private record Case(V3PengRobinsonThermo thermo, V3AcceptanceAuditor auditor, V3DryMeshState state) {}

    /**
     * A 30-tray crude column with 60 mol/s of steam whose tray one carries 30 mol/s over, at one diameter, with tray
     * one's vapour scaled by {@code trayOneVaporScale}. The limit grows with the square of the vapour (flood
     * fraction times vapour mass), so a tenth of it in the widest column lifts far less than 30 mol/s, and the full
     * vapour in a 2 m column far more.
     */
    private static Case carryOverCase(double diameterMetres, double trayOneVaporScale) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("icheme:tia_juana_light_kl1976");
        double[] flows = crude.moleFractions();
        double totalFlowMolPerSecond = 2_000.0 * 1_000.0 / 3_600.0;
        for (int component = 0; component < flows.length; component++) flows[component] *= totalFlowMolPerSecond;
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)), List.of(),
                List.of(new V3SteamFeedSpec(31, 60.0, 533.15)), List.of(), diameterMetres);
        V3ColumnProblem dry = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
        assertTrue(dry.carriesTopTrayWaterOver(), "the drum at 50 C decants free water");
        // The sequential initializer is dry-only, so the tray traffic comes from the same column without steam
        // (same nodes and components); the steam problem resolves the drum's regime and the water itself.
        V3ColumnInput steamless = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 250_000.0, 750.0, input.specifications(), List.of(),
                List.of(), List.of(), diameterMetres);
        V3DryMeshState state = V3ColumnInitializer.initialize(
                V3ColumnProblemResolver.resolve(steamless, V3CondenserPhaseBranch.TWO_PHASE), thermo,
                thermo.newWorkspace(), V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
        boolean[] wet = new boolean[dry.topology().nodeCount()];
        wet[1] = true;
        V3ColumnProblem problem = V3ColumnProblemResolver.withTruncation(dry, dry.truncationSupport(),
                V3WetTraySet.of(dry.topology(), wet));
        double[] freeWater = new double[problem.topology().nodeCount()];
        freeWater[1] = CARRIED_MOL_PER_SECOND;
        double[][] vapor = V3ColumnInitializer.flows(state, false);
        for (int component = 0; component < vapor[1].length; component++) vapor[1][component] *= trayOneVaporScale;
        state = new V3DryMeshState(problem.topology(), state.componentCount(), V3ColumnInitializer.flows(state, true),
                vapor, V3ColumnInitializer.temperatures(state), freeWater);
        double feed = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
        return new Case(thermo, new V3AcceptanceAuditor(problem, thermo, feed), state);
    }
}
