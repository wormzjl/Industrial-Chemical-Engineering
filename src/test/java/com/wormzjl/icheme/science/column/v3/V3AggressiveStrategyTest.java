package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The interactive aggressive route uses a bounded closure while retaining the normal acceptance audit. */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3AggressiveStrategyTest {
    @Test
    void coldFeatureColumnPublishesTheBoundedFastCertificate() {
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculateWithStrategy(V3TestInputs.literatureCduInput(),
                        V3SolveControl.UNBOUNDED, V3SolveStrategy.AGGRESSIVE, null, 0.0));
        assertEquals(1.0e-3, success.result().closureTolerance(), 0.0);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.diagnostics().convergenceEvidence().satisfiesGates());
    }

    @Test
    void fortyTwoTrayAggressivePathUsesTheIntermediateSeed() {
        V3ColumnInput base = V3TestInputs.literatureCduInput();
        double scale = 736.744575843224 / java.util.Arrays.stream(base.feedComponentMolarFlowsMolPerSecond()).sum();
        double[] feed = base.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < feed.length; i++) feed[i] *= scale;
        java.util.List<V3ColumnSpecification> specs = java.util.List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                new V3ColumnSpecification.ReboilerDuty(6.0e6));
        V3ColumnInput input = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(),
                base.componentBasis(), feed, base.feedTemperatureKelvin(), 42, 38, base.topPressurePascal(),
                base.stagePressureDropPascal(), specs,
                base.sideDraws(), java.util.List.of(new V3SteamFeedSpec(43,
                        base.steamFeeds().getFirst().molarFlowMolPerSecond(),
                        base.steamFeeds().getFirst().temperatureKelvin())), base.pumparounds(),
                base.columnDiameterMetres());
        V3ColumnOutcome.Success success = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculateWithStrategy(input, V3SolveControl.UNBOUNDED,
                        V3SolveStrategy.AGGRESSIVE, null, 0.3));
        assertTrue(success.diagnostics().solvePath().contains("15-30-42"), success.diagnostics()::solvePath);
        assertTrue(success.diagnostics().acceptanceAudit().accepted());
    }

}
