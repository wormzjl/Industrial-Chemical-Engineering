package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Design;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint.Lines;
import com.wormzjl.icheme.science.assembly.PortGraph.Unit;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.HeatSides.Side;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.wormzjl.icheme.science.assembly.TestGraph.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The exchanger's control lines (batch 2026-10-27-heat-exchanger-design, plan section 7, W8): the matcher's cases on a
 * hand-built graph. A bypass is an inlet tee, a valve on the bypass and an outlet tee; a ratio station a valve on the
 * controlled feed and a flowmeter on the reference feed.
 */
class ExchangerBlueprintTest {
    private static Unit exchanger(TestGraph g) {
        return g.unit(Kind.EXCHANGER, Role.TUBE_INLET, Role.TUBE_OUTLET, Role.SHELL_INLET, Role.SHELL_OUTLET);
    }

    private static AssemblyReport match(TestGraph g, Unit master, Design design) {
        return AssemblyMatcher.match(ExchangerBlueprint.of(design), g, master, Set.of());
    }

    /** A bypass around the shell side, a shutoff before the inlet tee, the feed's own valve upstream: complete, both tee orders. */
    @Test
    void aBuiltBypassIsComplete() {
        for (boolean feedFirst : new boolean[]{true, false}) {
            var g = new TestGraph();
            var ex = exchanger(g);
            g.bypass(port(ex, Role.SHELL_INLET), port(ex, Role.SHELL_OUTLET), List.of(other()), List.of(valveBack()), List.of(valve()), feedFirst);
            var report = match(g, ex, new Design(Lines.NONE, Lines.BYPASS));
            assertTrue(report.complete(), "feed first " + feedFirst + ": " + report.slots());
            assertEquals(4, report.slots().size());
            assertNotEquals(AssemblyReport.NONE, report.slot(ExchangerBlueprint.bypassValve(Side.SHELL)).member(), "the bypass valve is bound");
        }
    }

    /** A bypass with no valve, a reversed one, a valve before the inlet tee: each named on its slot, none complete. */
    @Test
    void aBypassFaultIsNamedOnItsSlot() {
        var g = new TestGraph();
        var ex = exchanger(g);
        g.bypass(port(ex, Role.TUBE_INLET), port(ex, Role.TUBE_OUTLET), List.of(), List.of(), List.of(), true);
        var report = match(g, ex, new Design(Lines.BYPASS, Lines.NONE));
        assertFalse(report.complete());
        assertNotEquals(State.OK, report.slot(ExchangerBlueprint.bypassValve(Side.TUBE)).state(), "no valve on the bypass");

        g = new TestGraph();
        ex = exchanger(g);
        g.bypass(port(ex, Role.TUBE_INLET), port(ex, Role.TUBE_OUTLET), List.of(), List.of(), List.of(valveBack()), true);
        report = match(g, ex, new Design(Lines.BYPASS, Lines.NONE));
        assertEquals(State.VALVE_REVERSED, report.slot(ExchangerBlueprint.bypassValve(Side.TUBE)).state());

        g = new TestGraph();
        ex = exchanger(g);
        g.bypass(port(ex, Role.TUBE_INLET), port(ex, Role.TUBE_OUTLET), List.of(valveBack()), List.of(), List.of(valve()), true);
        report = match(g, ex, new Design(Lines.BYPASS, Lines.NONE));
        assertEquals(State.EXTRA_VALVE, report.slot(ExchangerBlueprint.inletTee(Side.TUBE)).state(), "a valve between the tee and the nozzle");
        assertEquals(State.OK, report.slot(ExchangerBlueprint.bypassValve(Side.TUBE)).state());

        g = new TestGraph();
        ex = exchanger(g);
        g.outside(port(ex, Role.TUBE_INLET));
        report = match(g, ex, new Design(Lines.BYPASS, Lines.NONE));
        assertEquals(State.MISSING, report.slot(ExchangerBlueprint.inletTee(Side.TUBE)).state(), "no tee before the inlet");
    }

    /** A ratio on the tube side: its feed valve and the shell feed's flowmeter; without the meter, the meter is missing. */
    @Test
    void aRatioStationNeedsTheReferenceMeter() {
        var g = new TestGraph();
        var ex = exchanger(g);
        g.outside(port(ex, Role.TUBE_INLET), other(), valveBack());
        g.outside(port(ex, Role.SHELL_INLET), meter());
        var report = match(g, ex, new Design(Lines.RATIO, Lines.NONE));
        assertTrue(report.complete(), report.slots().toString());

        g = new TestGraph();
        ex = exchanger(g);
        g.outside(port(ex, Role.TUBE_INLET), meter(), valveBack());
        g.outside(port(ex, Role.SHELL_INLET));
        report = match(g, ex, new Design(Lines.RATIO, Lines.NONE));
        assertEquals(State.OK, report.slot(ExchangerBlueprint.feedValve(Side.TUBE)).state(), "a flowmeter no step asks for passes");
        assertEquals(State.MISSING, report.slot(ExchangerBlueprint.meter(Side.SHELL)).state());
    }

    /** A bypass on one side and a ratio on the other share the shell inlet's line: the meter sits before the tee. */
    @Test
    void aBypassAndARatioShareALine() {
        var g = new TestGraph();
        var ex = exchanger(g);
        g.bypass(port(ex, Role.SHELL_INLET), port(ex, Role.SHELL_OUTLET), List.of(meter()), List.of(), List.of(valve()), true);
        g.outside(port(ex, Role.TUBE_INLET), valveBack());
        var report = match(g, ex, new Design(Lines.RATIO, Lines.BYPASS));
        assertTrue(report.complete(), report.slots().toString());
        assertThrows(IllegalArgumentException.class, () -> ExchangerBlueprint.of(Design.NONE));
    }
}
