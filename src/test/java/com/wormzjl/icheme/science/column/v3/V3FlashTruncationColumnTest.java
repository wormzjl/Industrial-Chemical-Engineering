package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3FlashTruncationColumnTest {
    @Test
    void positiveCutoffFeedFlashIsObservableWithoutRelaxingTheColumnAudit() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("icheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 2610.7 * 1000.0 / 3600.0;
        V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 30, 24, 110000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(323.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                        new V3ColumnSpecification.ReboilerDuty(8000000.0)));
        long started = System.nanoTime();
        V3ColumnOutcome outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - started >= 45_000_000_000L) {
                throw new CancellationException("flash-trace column integration exceeded the client deadline");
            }
        }, 1.0e-6, 0.0, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);

        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        assertTrue(success.result().acceptanceAudit().checks().stream()
                .anyMatch(check -> check.family().equals("CONDENSER_PHASE") && check.passed()));
        assertEquals(V3CondenserPhaseBranch.TWO_PHASE, success.result().problem().topology().condenserPhaseBranch());
        List<String> flashEvents = success.diagnostics().events().stream()
                .filter(event -> event.startsWith("flash-trace ")).toList();
        assertFalse(flashEvents.isEmpty(), () -> success.diagnostics().events().toString());
        assertTrue(flashEvents.stream().allMatch(event -> event.contains("status=") && event.contains("omitted=")
                && event.contains("feed-H=reference")));
        assertTrue(success.diagnostics().events().size() <= V3SolverDiagnostics.MAX_EVENTS);
        assertTrue(success.diagnostics().events().stream().allMatch(event -> event.length() <= 256));
        assertEquals("v3-dry-mesh-r24-flash-trace-phase-r1", success.result().formulationRevision());
        assertEquals(V3InputDigest.of(success.result().problem(), V3ColumnCalculator.FORMULATION_REVISION,
                thermo.datasetRevision(), V3ColumnCalculator.ASSUMPTIONS_REVISION, 1.0e-6), success.result().inputDigest());
        assertArrayEquals(flows, input.feedComponentMolarFlowsMolPerSecond(), 0.0);
    }
}
