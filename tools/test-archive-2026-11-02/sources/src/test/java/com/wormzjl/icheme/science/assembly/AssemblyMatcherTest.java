package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.PortGraph.NotLoaded;
import com.wormzjl.icheme.science.assembly.PortGraph.OpenEnd;
import com.wormzjl.icheme.science.assembly.PortGraph.Port;
import com.wormzjl.icheme.science.assembly.PortGraph.Unit;
import com.wormzjl.icheme.science.assembly.TestGraph.F;
import com.wormzjl.icheme.science.assembly.TestGraph.Out;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ColumnTrays.Pumparound;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.wormzjl.icheme.science.assembly.ColumnBlueprint.*;
import static com.wormzjl.icheme.science.assembly.TestGraph.*;
import static com.wormzjl.icheme.science.equipment.EquipmentShape.Role.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The assembly matcher on hand-built pipes (batch 2026-10-26-multi-machine-framework, plan sections 5 and 6): every case
 * of the column's design table is complete on its plant built as designed, and each fault a player can build gives its
 * state on the slot it concerns.
 */
class AssemblyMatcherTest {
    private static final int TRAYS = 20, SUMP = TRAYS + 1;

    /** A column plant: its graph and its units by part. */
    private record Plant(ColumnTrays trays, TestGraph graph, Map<String, Unit> units) {
        Unit unit(String part) {
            return Objects.requireNonNull(units.get(part), part);
        }

        AssemblyReport match(Long... taken) {
            return AssemblyMatcher.match(ColumnBlueprint.of(trays), graph, unit("column"), Set.of(taken));
        }
    }

    private static ColumnTrays trays(Condenser condenser, boolean ejector, boolean kettle, List<Integer> steam, Pump reflux, int draws, List<Pump> pumparounds) {
        var drawTrays = List.of(3, 6, 9, 12).subList(0, draws);
        var pas = new ArrayList<Pumparound>();
        for (int i = 0; i < pumparounds.size(); i++) pas.add(new Pumparound(4 + 4 * i, 2 + 4 * i));
        return new ColumnTrays(TRAYS, 10, drawTrays, steam, pas, new ColumnComponents(condenser, reflux, pumparounds, ejector, kettle));
    }

    /** One case of each kind: air cooler, standard pumps, a kettle, two draws, one tray steam stage, two pumparounds. */
    private static ColumnTrays basic() {
        return trays(Condenser.AIR_COOLER, false, true, List.of(18), Pump.STANDARD, 2, List.of(Pump.STANDARD, Pump.STANDARD));
    }

    /**
     * The plant {@code trays} asks for, piped as designed. The receiver's condensate enters its second side connection;
     * an even pumparound passes its cooler before its pump, and an In-line Pump of an even pumparound sits on its return.
     */
    private static Plant ideal(ColumnTrays trays) {
        var g = new TestGraph();
        var c = trays.components();
        var units = new HashMap<String, Unit>();
        var roles = new ArrayList<>(List.of(VAPOUR_OUTLET, REFLUX, FEED));
        for (int i = 0; i < trays.pumparounds().size(); i++) roles.addAll(List.of(PUMPAROUND_DRAW, RETURN));
        if (c.kettle()) roles.add(VAPOUR_RETURN);
        for (int i = 0; i < trays.draws().size(); i++) roles.add(DRAW);
        for (int i = 0; i < trays.steam().size(); i++) roles.add(STEAM);
        roles.add(BOTTOMS);
        var column = g.unit(Kind.COLUMN, roles.toArray(Role[]::new));
        units.put("column", column);

        var overhead = port(column, VAPOUR_OUTLET);
        if (c.ejector()) {
            var ejector = g.unit(Kind.EJECTOR, SUCTION, DISCHARGE, MOTIVE);
            units.put("ejector", ejector);
            g.pipe(overhead, port(ejector, SUCTION));
            overhead = port(ejector, DISCHARGE);
        }
        Unit condenser;
        Port condensate;
        if (c.condenser() == Condenser.AIR_COOLER) {
            condenser = g.unit(Kind.AIR_COOLER, PROCESS_INLET, PROCESS_OUTLET);
            g.pipe(overhead, port(condenser, PROCESS_INLET));
            condensate = port(condenser, PROCESS_OUTLET);
        } else {
            condenser = g.unit(Kind.EXCHANGER, TUBE_INLET, TUBE_OUTLET, SHELL_INLET, SHELL_OUTLET);
            g.pipe(overhead, port(condenser, SHELL_INLET));
            condensate = port(condenser, SHELL_OUTLET);
        }
        units.put("condenser", condenser);
        var receiver = g.drum(true, true, VAPOUR_OUTLET, LIQUID_OUTLET, BOOT_DRAIN, SIDE, SIDE);
        units.put("receiver", receiver);
        g.pipe(condensate, port(receiver, SIDE, 1));
        g.outside(port(receiver, VAPOUR_OUTLET), valve());
        g.outside(port(receiver, BOOT_DRAIN), valve());

        var refluxBranches = new Out[]{new Out(List.of(valve()), port(column, REFLUX)), new Out(List.of(valve()), null)};
        if (c.refluxPump() == Pump.STANDARD) {
            var pump = g.unit(Kind.PUMP, SUCTION, DISCHARGE);
            units.put("reflux_pump", pump);
            g.pipe(port(receiver, LIQUID_OUTLET), port(pump, SUCTION));
            g.tee(port(pump, DISCHARGE), List.of(), refluxBranches);
        } else g.tee(port(receiver, LIQUID_OUTLET), List.of(inlinePump()), refluxBranches);

        // The feed and the steam come in: walked out from the column, their arrows point back along the walk.
        g.outside(port(column, FEED), valveBack());
        for (int i = 0; i < trays.steam().size(); i++) g.outside(port(column, STEAM, i), valveBack());
        for (int i = 0; i < trays.draws().size(); i++) g.outside(port(column, DRAW, i), valve());
        for (int i = 0; i < trays.pumparounds().size(); i++) {
            var cooler = g.unit(Kind.EXCHANGER, TUBE_INLET, TUBE_OUTLET, SHELL_INLET, SHELL_OUTLET);
            units.put("pa_cooler" + (i + 1), cooler);
            boolean even = (i + 1) % 2 == 0;
            if (c.pumparoundPumps().get(i) == Pump.IN_LINE) {
                g.pipe(port(column, PUMPAROUND_DRAW, i), port(cooler, SHELL_INLET), even ? new F[0] : new F[]{inlinePump()});
                g.pipe(port(cooler, SHELL_OUTLET), port(column, RETURN, i), even ? new F[]{inlinePump()} : new F[0]);
                continue;
            }
            var pump = g.unit(Kind.PUMP, SUCTION, DISCHARGE);
            units.put("pa_pump" + (i + 1), pump);
            if (even) {
                g.pipe(port(column, PUMPAROUND_DRAW, i), port(cooler, SHELL_INLET));
                g.pipe(port(cooler, SHELL_OUTLET), port(pump, SUCTION));
                g.pipe(port(pump, DISCHARGE), port(column, RETURN, i));
            } else {
                g.pipe(port(column, PUMPAROUND_DRAW, i), port(pump, SUCTION));
                g.pipe(port(pump, DISCHARGE), port(cooler, SHELL_INLET));
                g.pipe(port(cooler, SHELL_OUTLET), port(column, RETURN, i));
            }
        }
        if (c.kettle()) {
            var kettle = g.unit(Kind.KETTLE, TUBE_INLET, TUBE_OUTLET, FEED, VAPOUR_OUTLET, LIQUID_OUTLET);
            units.put("kettle", kettle);
            g.pipe(port(column, BOTTOMS), port(kettle, FEED));
            g.pipe(port(kettle, VAPOUR_OUTLET), port(column, VAPOUR_RETURN));
            g.outside(port(kettle, LIQUID_OUTLET), valve());
        } else g.outside(port(column, BOTTOMS), valve());
        return new Plant(trays, g, units);
    }

    private static void assertSlot(AssemblyReport report, Slot slot, State state) {
        assertEquals(state, report.slot(slot).state(), () -> slot + " in " + report.slots());
    }

    private static PortState portState(AssemblyReport report, Unit unit, Role role, int ordinal) {
        return report.ports().stream().filter(p -> p.unit() == unit.origin() && p.role() == role && p.ordinal() == ordinal).findFirst()
                .orElseThrow(() -> new AssertionError("No port " + role + " " + ordinal)).state();
    }

    /** Every slot in place except {@code faulty}, which has {@code state}. */
    private static void assertOnly(AssemblyReport report, Slot faulty, State state) {
        for (var s : report.slots())
            assertEquals(s.slot().equals(faulty) ? state : State.OK, s.state(), () -> s.slot() + " in " + report.slots());
    }

    @Test
    void everyCaseOfTheDesignTableIsCompleteOnItsPlantBuiltAsDesigned() {
        // Plan section 5.5: condenser x top pressure x column bottom with tray steam x reflux pump x draws x pumparound pumps.
        var bottoms = List.<Map.Entry<Boolean, List<Integer>>>of(Map.entry(true, List.of()), Map.entry(true, List.of(18)), Map.entry(true, List.of(17, 18)),
                Map.entry(true, List.of(SUMP)), Map.entry(true, List.of(18, SUMP)), Map.entry(false, List.of(SUMP)), Map.entry(false, List.of(18, SUMP)));
        var pumpSets = new ArrayList<List<Pump>>();
        for (int n = 0; n <= ColumnTrays.MAX_PUMPAROUNDS; n++)
            for (int bits = 0; bits < 1 << n; bits++) {
                var pumps = new ArrayList<Pump>();
                for (int i = 0; i < n; i++) pumps.add((bits >> i & 1) == 1 ? Pump.IN_LINE : Pump.STANDARD);
                pumpSets.add(pumps);
            }
        int cases = 0;
        for (var condenser : Condenser.values())
            for (boolean ejector : new boolean[]{false, true})
                for (var bottom : bottoms)
                    for (var reflux : Pump.values())
                        for (int draws = 0; draws <= ColumnTrays.MAX_DRAWS; draws++)
                            for (var pumps : pumpSets) {
                                var trays = trays(condenser, ejector, bottom.getKey(), bottom.getValue(), reflux, draws, pumps);
                                var plant = ideal(trays);
                                var report = plant.match();
                                assertTrue(report.complete(), () -> trays + " -> " + report.slots());
                                assertTrue(report.ports().stream().noneMatch(p -> p.state() == PortState.NEEDED), () -> trays + " -> " + report.ports());
                                assertEquals(plant.units().size(), report.members().size(), trays::toString);
                                cases++;
                            }
        assertEquals(8680, cases);
    }

    @Test
    void theColumnAloneLeavesEveryOtherSlotMissingAndItsLinePortsNeeded() {
        var g = new TestGraph();
        var column = g.unit(Kind.COLUMN, VAPOUR_OUTLET, REFLUX, FEED, PUMPAROUND_DRAW, RETURN, PUMPAROUND_DRAW, RETURN, VAPOUR_RETURN, DRAW, DRAW, STEAM, BOTTOMS);
        var report = AssemblyMatcher.match(ColumnBlueprint.of(basic()), g, column, Set.of());
        for (var s : report.slots()) assertEquals(s.slot().equals(COLUMN) ? State.OK : State.MISSING, s.state(), s.slot()::toString);
        for (var p : report.ports()) assertEquals(PortState.NEEDED, p.state(), () -> p.role() + " " + p.ordinal());
        assertEquals(List.of(column.origin()), report.members());
        assertFalse(report.complete());
    }

    @Test
    void aPartlyConnectedUnitIsFoundAndItsFreePortStaysNeeded() {
        var plant = ideal(basic());
        plant.graph().cut(port(plant.unit("receiver"), BOOT_DRAIN));
        var report = plant.match();
        assertOnly(report, WATER_VALVE, State.MISSING);
        assertEquals(PortState.NEEDED, portState(report, plant.unit("receiver"), BOOT_DRAIN, 0));
        assertEquals(PortState.CONNECTED, portState(report, plant.unit("receiver"), SIDE, 1));
        assertEquals(PortState.UNUSED, portState(report, plant.unit("receiver"), SIDE, 0));
    }

    @Test
    void anOverheadPipeThatStopsShortIsAnOpenEndAtItsLastBlock() {
        var plant = ideal(basic());
        long end = plant.graph().end(port(plant.unit("column"), VAPOUR_OUTLET), OpenEnd::new, 4);
        var report = plant.match();
        assertSlot(report, CONDENSER, State.OPEN_END);
        assertEquals(end, report.slot(CONDENSER).at());
        // The rest of the overhead system is not reached from the column, so it is missing.
        assertSlot(report, RECEIVER, State.MISSING);
        assertSlot(report, REFLUX_PUMP, State.MISSING);
        assertSlot(report, FEED_VALVE, State.OK);
    }

    @Test
    void aLineIntoAnUnloadedChunkIsNotChecked() {
        var plant = ideal(basic());
        plant.graph().end(port(plant.unit("column"), DRAW, 1), last -> new NotLoaded(last + 1), 2);
        assertOnly(plant.match(), drawValve(2), State.NOT_LOADED);
    }

    @Test
    void aRefluxPumpTurnedRoundIsOnTheWrongPortAndTheLineGoesOnThroughIt() {
        var plant = ideal(basic());
        var g = plant.graph();
        var pump = plant.unit("reflux_pump");
        g.pipe(port(plant.unit("receiver"), LIQUID_OUTLET), port(pump, DISCHARGE));
        g.tee(port(pump, SUCTION), List.of(), new Out(List.of(valve()), port(plant.unit("column"), REFLUX)), new Out(List.of(valve()), null));
        var report = plant.match();
        assertOnly(report, REFLUX_PUMP, State.WRONG_PORT);
        assertEquals("backwards", report.slot(REFLUX_PUMP).detail());
    }

    @Test
    void aPumparoundOnItsCoolersTubeSideIsOnTheWrongPort() {
        var plant = ideal(basic());
        var g = plant.graph();
        var cooler = plant.unit("pa_cooler1");
        g.pipe(port(plant.unit("pa_pump1"), DISCHARGE), port(cooler, TUBE_INLET));
        g.pipe(port(cooler, TUBE_OUTLET), port(plant.unit("column"), RETURN, 0));
        g.cut(port(cooler, SHELL_INLET));
        g.cut(port(cooler, SHELL_OUTLET));
        var report = plant.match();
        assertOnly(report, pumparoundCooler(1), State.WRONG_PORT);
        assertEquals("TUBE_INLET", report.slot(pumparoundCooler(1)).detail());
        assertEquals(PortState.CONNECTED, portState(report, cooler, TUBE_INLET, 0));
        assertEquals(PortState.UNUSED, portState(report, cooler, SHELL_OUTLET, 0));
    }

    @Test
    void aStandingDrumIsTheWrongKindOfReceiver() {
        var plant = ideal(basic());
        var g = plant.graph();
        var drum = g.drum(false, false, VAPOUR_OUTLET, LIQUID_OUTLET, SIDE);
        g.pipe(port(plant.unit("condenser"), PROCESS_OUTLET), port(drum, SIDE));
        g.outside(port(drum, VAPOUR_OUTLET), valve());
        g.pipe(port(drum, LIQUID_OUTLET), port(plant.unit("reflux_pump"), SUCTION));
        var report = plant.match();
        assertSlot(report, RECEIVER, State.WRONG_KIND);
        assertEquals("DRUM_STANDING", report.slot(RECEIVER).detail());
        // A standing drum has no boot to drain the water; the rest of the line is found through it.
        assertSlot(report, WATER_VALVE, State.MISSING);
        assertSlot(report, OFFGAS_VALVE, State.OK);
        assertSlot(report, REFLUX_PUMP, State.OK);
        assertSlot(report, REFLUX_VALVE, State.OK);
    }

    @Test
    void aFeedValvePointingOutOfTheColumnIsReversed() {
        var plant = ideal(basic());
        plant.graph().outside(port(plant.unit("column"), FEED), valve());
        var report = plant.match();
        assertOnly(report, FEED_VALVE, State.VALVE_REVERSED);
        assertEquals("backwards", report.slot(FEED_VALVE).detail());
    }

    @Test
    void aValveBeforeTheCondenserIsExtraAndOneBeyondABoundaryValveIsOutside() {
        var plant = ideal(basic());
        var g = plant.graph();
        g.pipe(port(plant.unit("column"), VAPOUR_OUTLET), port(plant.unit("condenser"), PROCESS_INLET), valve());
        g.outside(port(plant.unit("column"), DRAW, 0), valve(), valve(), other());
        var report = plant.match();
        assertOnly(report, CONDENSER, State.EXTRA_VALVE);
        assertEquals("CONTROL_VALVE", report.slot(CONDENSER).detail());
    }

    @Test
    void anEjectorAfterTheCondenserIsInTheWrongOrder() {
        var plant = ideal(trays(Condenser.AIR_COOLER, true, true, List.of(18), Pump.STANDARD, 0, List.of()));
        var g = plant.graph();
        var condenser = plant.unit("condenser");
        var ejector = plant.unit("ejector");
        g.pipe(port(plant.unit("column"), VAPOUR_OUTLET), port(condenser, PROCESS_INLET));
        g.pipe(port(condenser, PROCESS_OUTLET), port(ejector, SUCTION));
        g.pipe(port(ejector, DISCHARGE), port(plant.unit("receiver"), SIDE, 1));
        assertOnly(plant.match(), EJECTOR, State.WRONG_ORDER);
    }

    @Test
    void aBranchBeforeTheFeedValveIsBranched() {
        var plant = ideal(basic());
        long tee = plant.graph().tee(port(plant.unit("column"), FEED), List.of(), new Out(List.of(valveBack()), null), new Out(List.of(valveBack()), null));
        var report = plant.match();
        assertOnly(report, FEED_VALVE, State.BRANCHED);
        assertEquals(tee, report.slot(FEED_VALVE).at());
    }

    @Test
    void theRefluxTeesBranchesAreTakenByWhereTheyGo() {
        var plant = ideal(basic());
        // The distillate branch first: the matcher assigns branches by their ends, not their order.
        plant.graph().tee(port(plant.unit("reflux_pump"), DISCHARGE), List.of(), new Out(List.of(valve()), null),
                new Out(List.of(valve()), port(plant.unit("column"), REFLUX)));
        assertTrue(plant.match().complete());
    }

    @Test
    void aRefluxLineWithoutItsTeeLeavesTheTeeAndBothValvesMissing() {
        var plant = ideal(basic());
        plant.graph().pipe(port(plant.unit("reflux_pump"), DISCHARGE), port(plant.unit("column"), REFLUX), valve());
        var report = plant.match();
        // The valve on the way is extra, but the missing tee outranks it.
        assertSlot(report, REFLUX_TEE, State.MISSING);
        assertEquals("returns:REFLUX", report.slot(REFLUX_TEE).detail());
        assertSlot(report, REFLUX_VALVE, State.MISSING);
        assertSlot(report, DISTILLATE_VALVE, State.MISSING);
    }

    @Test
    void aColumnStrippedBySumpSteamHasEveryPortPipedAndNoVapourReturn() {
        var plant = ideal(trays(Condenser.AIR_COOLER, false, false, List.of(SUMP), Pump.STANDARD, 0, List.of()));
        var column = plant.unit("column");
        var report = plant.match();
        assertTrue(report.complete());
        var ports = report.ports().stream().filter(p -> p.unit() == column.origin()).toList();
        assertEquals(List.of(VAPOUR_OUTLET, REFLUX, FEED, STEAM, BOTTOMS), ports.stream().map(AssemblyReport.PortReport::role).toList());
        for (var p : ports) assertEquals(PortState.CONNECTED, p.state(), p::toString);
    }

    @Test
    void aCondenserAnotherAssemblyHoldsIsShared() {
        var plant = ideal(basic());
        var report = plant.match(plant.unit("condenser").origin());
        assertSlot(report, CONDENSER, State.SHARED);
        assertSlot(report, RECEIVER, State.MISSING);
        assertFalse(report.members().contains(plant.unit("condenser").origin()));
    }

    @Test
    void anOutsideLineEndsOpenOnlySixteenBlocksBeyondItsValve() {
        var plant = ideal(basic());
        var draw = port(plant.unit("column"), DRAW, 0);
        plant.graph().end(draw, OpenEnd::new, AssemblyMatcher.EXTERNAL_REACH - 2, valve());
        assertTrue(plant.match().complete());
        long end = plant.graph().end(draw, OpenEnd::new, AssemblyMatcher.EXTERNAL_REACH - 3, valve());
        var report = plant.match();
        assertOnly(report, drawValve(1), State.OPEN_END);
        assertEquals(end, report.slot(drawValve(1)).at());
    }

    @Test
    void anInlinePumpWhereTheDesignHasAStandardPumpIsTheWrongKind() {
        var plant = ideal(basic());
        plant.graph().tee(port(plant.unit("receiver"), LIQUID_OUTLET), List.of(inlinePump()), new Out(List.of(valve()), port(plant.unit("column"), REFLUX)),
                new Out(List.of(valve()), null));
        var report = plant.match();
        assertOnly(report, REFLUX_PUMP, State.WRONG_KIND);
        assertEquals("INLINE_PUMP", report.slot(REFLUX_PUMP).detail());
    }

    @Test
    void theSamePipesGiveTheSameReport() {
        var plant = ideal(basic());
        plant.graph().cut(port(plant.unit("receiver"), VAPOUR_OUTLET));
        var report = plant.match();
        assertEquals(report, plant.match());
        // The footprint holds every walked block: the feed line's here.
        assertTrue(report.footprint().containsAll(plant.graph().run(port(plant.unit("column"), FEED)).positions()));
    }
}
