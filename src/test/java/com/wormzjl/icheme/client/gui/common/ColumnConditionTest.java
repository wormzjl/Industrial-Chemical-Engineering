package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.tutorial.guide.AssemblyCondition;
import com.wormzjl.icheme.science.tutorial.guide.ColumnCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The criteria {@code icheme:assembly_state} and {@code icheme:column_state}'s conditions (batch 2026-11-12-tutorial-system,
 * P5 stage B), read on the views the assembly screen is sent: a status among those named; a phase among those named, and a
 * product leaving. A column that never started has no Operation view and meets nothing.
 */
class ColumnConditionTest {
    @Test
    void theAssemblyStepWaitsForAllTeal() {
        var complete = new AssemblyCondition(Optional.of(List.of("COMPLETE")));
        assertTrue(complete.matches("COMPLETE"));
        assertFalse(complete.matches("CHECKED"), "a check with parts to fix");
        assertFalse(complete.matches("NOT_VALIDATED"));
        assertTrue(new AssemblyCondition(Optional.empty()).matches("CHECKED"), "no status named: any");
    }

    @Test
    void theStartStepsReadThePhaseAndTheProducts() {
        var s = OperationColumnTest.setpoints(1, 1, 1, false);
        var started = new ColumnCondition(Optional.of(ColumnCondition.STARTED), Optional.empty());
        assertFalse(started.matches(null), "never started: no Operation view");
        assertFalse(started.matches(OperationView.idle(OperationView.Phase.STOPPED, "STOPPED", s)), "stopped");
        assertFalse(started.matches(OperationView.idle(OperationView.Phase.NOT_READY, "a start refused", s)), "a refused start");
        assertTrue(started.matches(OperationView.idle(OperationView.Phase.STARTING, "STARTING", s)), "a taken start");
        var running = new ColumnCondition(Optional.of(List.of("RUNNING")), Optional.empty());
        assertTrue(running.matches(OperationColumnTest.running(s, OperationView.Phase.RUNNING)));
        assertFalse(running.matches(OperationColumnTest.running(s, OperationView.Phase.LIMITED)), "held back by a limit");
        var product = new ColumnCondition(Optional.empty(), Optional.of(true));
        assertTrue(product.matches(OperationColumnTest.running(s, OperationView.Phase.LIMITED)), "products leave a limited column too");
        assertFalse(product.matches(OperationView.idle(OperationView.Phase.STARTING, "STARTING", s)), "nothing leaves before the first table");
    }
}
