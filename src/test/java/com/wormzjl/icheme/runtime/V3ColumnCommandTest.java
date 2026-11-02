package com.wormzjl.icheme.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3TestInputs;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3ColumnCommandTest {
    @Test
    void admittedCommandKeepsItsInitializerPolicyAndModel() {
        var forced = new com.wormzjl.icheme.science.column.v3.V3InitializationOptions(
                com.wormzjl.icheme.science.column.v3.V3InitializationOptions.Mode.LNN_ONLY,
                com.wormzjl.icheme.science.column.v3.V3InitializationOptions.WetStart.PREDICTED_WET, 8, 500,
                com.wormzjl.icheme.science.column.v3.V3InitializationOptions.Correction.PROGRESS);
        var settings = new AtomicReference<>(forced);
        var command = new ProcessSolveServices.V3ColumnCommand(input(), 0, 0, settings.get(),
                com.wormzjl.icheme.science.column.v3.V3NeuralInitializer.UNAVAILABLE,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, MaterialRuntime.current());
        settings.set(com.wormzjl.icheme.science.column.v3.V3InitializationOptions.CURRENT);
        assertSame(forced, command.initialization());
        var result = assertInstanceOf(ProcessSolveServices.V3ColumnSolveResult.class, command.solve(unbounded()));
        assertEquals(com.wormzjl.icheme.science.column.v3.V3SolverFailureCode.INITIALIZATION_FAILURE,
                assertInstanceOf(V3ColumnOutcome.Failure.class, result.outcome()).code());
    }
    @Test
    void immutableCommandCarriesSideDrawsIntoThePublicFacade() {
        V3ColumnInput base = input();
        V3ColumnInput drawn = new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(), base.feedStageNumber(),
                base.topPressurePascal(), base.stagePressureDropPascal(), base.specifications(),
                List.of(new com.wormzjl.icheme.science.column.v3.V3SideDrawSpec(1, 5)));
        var command = command(drawn, 0.0, 0.0);
        var result = assertInstanceOf(ProcessSolveServices.V3ColumnSolveResult.class, command.solve(unbounded()));
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, result.outcome());
        assertEquals(drawn, success.result().problem().input());
        assertTrue(success.result().streams().stream().anyMatch(stream -> stream.streamId().equals("side_liquid_tray_01")));
    }

    @Test
    void admittedCommandRetainsItsCutoffAfterConfigurationChangesBeforeExecution() {
        V3ColumnInput input = input();
        AtomicReference<Double> configMolPercent = new AtomicReference<>(0.0001);
        ProcessSolveServices.V3ColumnCommand command = command(input, configMolPercent.get() / 100.0, 0.0);
        configMolPercent.set(0.0); // Simulated reload while this immutable command is awaiting execution.

        assertEquals(1.0e-6, command.stageTraceCutoffMoleFraction());
        assertSame(input, command.input());
        ProcessSolveServices.V3ColumnSolveResult result = assertInstanceOf(ProcessSolveServices.V3ColumnSolveResult.class,
                command.solve(unbounded()));
        V3ColumnOutcome.Success actual = assertInstanceOf(V3ColumnOutcome.Success.class, result.outcome());
        V3ColumnOutcome.Success expected = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3ColumnCalculator.calculate(input, () -> {}, 1.0e-6, 0.0, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        V3ColumnOutcome.Success disabled = assertInstanceOf(V3ColumnOutcome.Success.class, V3TestInputs.calculate(input));
        assertEquals(expected.result().inputDigest(), actual.result().inputDigest());
        assertNotEquals(disabled.result().inputDigest(), actual.result().inputDigest());
        assertEquals(expected.result().streams(), actual.result().streams());
    }

    @Test
    void immutableCommandRevalidatesTheFractionIndependentlyOfTheConfigRange() {
        for (double invalid : new double[] {-Double.MIN_VALUE, Math.nextUp(0.01), Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> command(input(), invalid, 0.0));
        }
        assertDoesNotThrow(() -> command(input(), 0.0, 0.0));
        assertDoesNotThrow(() -> command(input(), 0.01, 0.0));
        assertThrows(NullPointerException.class, () -> command(null, 0.0, 0.0));
    }

    @Test
    void immutableCommandRevalidatesTheClosureAndCarriesItIntoTheFacade() {
        for (double invalid : new double[] {-Double.MIN_VALUE, Math.nextUp(1.0e-3), 1.0, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> command(input(), 0.0, invalid));
        }
        assertDoesNotThrow(() -> command(input(), 0.0, 0.0));
        assertDoesNotThrow(() -> command(input(), 0.0, 1.0e-3));

        V3ColumnInput input = input();
        ProcessSolveServices.V3ColumnCommand command =
                command(input, 0.0, 1.0e-3);
        V3ColumnOutcome.Success loose = assertInstanceOf(V3ColumnOutcome.Success.class,
                assertInstanceOf(ProcessSolveServices.V3ColumnSolveResult.class, command.solve(unbounded())).outcome());
        V3ColumnOutcome.Success frozen = assertInstanceOf(V3ColumnOutcome.Success.class,
                V3TestInputs.calculate(input));
        assertEquals(1.0e-3, loose.result().closureTolerance());
        assertTrue(loose.result().formulationRevision().endsWith("-closure1e-3"));
        assertNotEquals(frozen.result().inputDigest(), loose.result().inputDigest());
        assertEquals(V3ColumnCalculator.calculate(input, () -> {}, 0.0, 1.0e-3, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO) instanceof V3ColumnOutcome.Success
                        expected ? expected.result().inputDigest() : null,
                loose.result().inputDigest());
    }

    @Test
    void callerCancellationStillEscapesBeforeScientificWork() {
        CancellationException cancellation = new CancellationException("cancelled worker");
        BoundedCpuSolveService.CancellationToken token = new BoundedCpuSolveService.CancellationToken() {
            @Override public void throwIfCancellationRequested() { throw cancellation; }
        };
        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> command(input(), 1.0e-6, 0.0).solve(token)));
    }

    private static BoundedCpuSolveService.CancellationToken unbounded() {
        return new BoundedCpuSolveService.CancellationToken() {
            @Override public void throwIfCancellationRequested() {}
        };
    }

    /** The classical admitted command at the default liquid-supply screen and the current catalog. */
    private static ProcessSolveServices.V3ColumnCommand command(V3ColumnInput input, double cutoff, double closure) {
        return new ProcessSolveServices.V3ColumnCommand(input, cutoff, closure,
                com.wormzjl.icheme.science.column.v3.V3InitializationOptions.CURRENT,
                com.wormzjl.icheme.science.column.v3.V3NeuralInitializer.UNAVAILABLE,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, MaterialRuntime.current());
    }

    private static V3ColumnInput input() {
        return V3TestInputs.cdu17BoilingBinary("test:command-snapshot");
    }
}
