package com.wormzjl.icheme.science.column.v3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3ColumnDisplayResultTest {
    @Test
    void acceptedResultCreatesOnlyABoundedPresentationCertificate() {
        V3ColumnInput input = V3TestInputs.crudeBinary("test:registered-pr-binary");
        double[] feedFlows = input.feedComponentMolarFlowsMolPerSecond();

        V3ColumnOutcome.Success success = (V3ColumnOutcome.Success) V3TestInputs.calculate(input);
        V3ColumnDisplayResult view = V3ColumnDisplayResult.fromAccepted(success);

        assertEquals(success.result().inputDigest().hexadecimalSha256(), view.inputDigest());
        assertEquals(success.diagnostics().newtonIterations(), view.newtonIterations());
        assertEquals(success.diagnostics().maximumScaledResidual(), view.maximumScaledResidual());
        assertTrue(view.acceptanceCheckCount() > 0);
        assertEquals(success.result().streams(), view.streams());
        assertEquals(2, view.streams().size());
        var inspection = view.inspection().orElseThrow();
        assertEquals(input, inspection.input());
        assertEquals(input.stageCount() + 2, inspection.nodes().size());
        assertEquals(input.componentBasis().componentCount(), inspection.nodes().getFirst().liquidFractions().size());
        for (var node : inspection.nodes()) for (int c = 0; c < feedFlows.length; c++) {
            if (feedFlows[c] == 0) {
                assertEquals(0.0, node.liquidFractions().get(c));
                assertEquals(0.0, node.vaporFractions().get(c));
            }
        }
        assertEquals(success.result().acceptanceAudit(), inspection.audit());
        for (int n = 0; n < inspection.nodes().size(); n++) {
            assertEquals(success.result().problem().nodePressurePascal(n), inspection.nodes().get(n).pressurePascal());
            assertEquals(0, inspection.nodes().get(n).waterVaporMolPerSecond());
            assertEquals(0, inspection.nodes().get(n).freeWaterMolPerSecond());
        }
        assertEquals(view.streams().getFirst().temperatureKelvin(), inspection.nodes().getFirst().temperatureKelvin());
        assertEquals(view.streams().getLast().temperatureKelvin(), inspection.nodes().getLast().temperatureKelvin());

        for (V3ColumnStreamProperties stream : view.streams()) {
            assertTrue(stream.molarFlowMolPerSecond() > 0.0);
            assertTrue(stream.massFlowKgPerSecond() > 0.0);
            assertEquals("VAPOR".equals(stream.phase()) ? 1.0 : 0.0, stream.vaporMoleFraction());
            assertEquals(1.0, stream.moleFractions().stream()
                    .mapToDouble(V3ColumnStreamProperties.ComponentFraction::moleFraction).sum(), 1.0e-8);
            assertEquals(1.0, stream.moleFractions().stream()
                    .mapToDouble(V3ColumnStreamProperties.ComponentFraction::massFraction).sum(), 1.0e-8);
        }
    }
}
