package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.PortGraph.Port;
import com.wormzjl.icheme.science.assembly.PortGraph.Size;
import com.wormzjl.icheme.science.assembly.PortGraph.Unit;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.wormzjl.icheme.science.assembly.ColumnBlueprint.*;
import static com.wormzjl.icheme.science.assembly.TestGraph.*;
import static com.wormzjl.icheme.science.equipment.EquipmentShape.Role.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Identical units in parallel on a column's member steps (batch 2026-11-02-process-simulator, S3b): a design that asks for N
 * condenser banks or shells, kettles or pumparound cooler shells is complete on N identical units between a split and a
 * merge on the step's link, and the report says when the number of units, their sizes or the branches do not match.
 */
class AssemblyParallelTest {
    private static final Size SIZE = new Size(2, 5, ProcessUnit.Bank.ONE), OTHER = new Size(2.5, 5, ProcessUnit.Bank.ONE);

    @Test void parallelExchangersIdentifyAConfigurationMismatch() {
        var cross=com.wormzjl.icheme.science.equipment.ExchangerType.CROSSFLOW;
        var standard=com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD;
        for(int standardCount=0;standardCount<=2;standardCount++) {
            var g=new TestGraph();var master=g.unit(Kind.COLUMN,VAPOUR_OUTLET);var split=g.hub();g.leg(split,port(master,VAPOUR_OUTLET));
            for(int i=0;i<2;i++)g.leg(split,port(g.unit(Kind.EXCHANGER,new Size(2,6,ProcessUnit.Bank.ONE,i<standardCount?standard:cross),SHELL_INLET),SHELL_INLET));
            var root=new Blueprint.Slot("column",0);var member=new Blueprint.Slot("condenser",0);
            var form=new Blueprint.UnitForm(Kind.EXCHANGER,false,SHELL_INLET,null,Set.of()).withExchangerType(cross);
            var step=Blueprint.Step.of(member,form).withCount(2);
            var blueprint=new Blueprint(root,List.of(root,member),List.of(new Blueprint.Link("overhead",Blueprint.Start.at(root,VAPOUR_OUTLET,0),List.of(step),new Blueprint.AtUnit(),false,member)));
            var report=AssemblyMatcher.match(blueprint,g,master,Set.of());
            assertEquals(standardCount==0?State.OK:State.CONFIGURATION,report.slot(member).state(),"standard shells="+standardCount);
        }
    }

    /** A plant: its graph, its column, and its units by part (the condenser's and the kettle's and cooler's in parallel numbered). */
    private record Plant(ColumnTrays trays, TestGraph graph, Unit column, Map<String, List<Unit>> units) {
        AssemblyReport match() {
            return AssemblyMatcher.match(ColumnBlueprint.of(trays), graph, column, Set.of());
        }
    }

    /** What a test builds: how many units stand on each step, and whether the last condenser has another size or the split an open branch. */
    private record Build(int condensers, int kettles, int coolers, boolean oddSize, boolean openBranch, boolean chained, int trims) {
        Build(int condensers, int kettles, int coolers, boolean oddSize, boolean openBranch, boolean chained) {
            this(condensers, kettles, coolers, oddSize, openBranch, chained, 1);
        }

        static Build of(int condensers, int kettles, int coolers) {
            return new Build(condensers, kettles, coolers, false, false, false);
        }
    }

    private static ColumnTrays trays(Condenser condenser, ColumnComponents.Units units) {
        return new ColumnTrays(20, 10, List.of(), List.of(), List.of(new ColumnTrays.Pumparound(6, 4)),
                new ColumnComponents(condenser, Pump.STANDARD, List.of(Pump.STANDARD), false, true, units));
    }

    /** {@code n} units of {@code kind} between a split from {@code from} and, where {@code to} is given, a merge to it. */
    private static List<Unit> parallel(TestGraph g, Port from, Kind kind, Role in, Role out, Port to, int n, boolean odd, boolean open, boolean chained, Role... nozzles) {
        var units = new ArrayList<Unit>();
        for (int i = 0; i < n; i++) units.add(g.unit(kind, odd && i == n - 1 ? OTHER : SIZE, nozzles));
        if (n == 1 && !open) {
            g.pipe(from, port(units.getFirst(), in));
            if (to != null) g.pipe(port(units.getFirst(), out), to);
            return units;
        }
        // A split: one hub, or a hub per unit in a row (tees along a header).
        var split = g.hub();
        g.leg(split, from);
        var at = split;
        for (int i = 0; i < n; i++) {
            if (chained && i > 0 && i < n - 1) {
                var next = g.hub();
                g.join(at, next);
                at = next;
            }
            g.leg(at, port(units.get(i), in));
        }
        if (open) g.openLeg(split);
        if (to != null) {
            var merge = g.hub();
            for (var u : units) g.leg(merge, port(u, out));
            g.leg(merge, to);
        }
        return units;
    }

    /** The column {@code trays} asks for, with {@code b}'s numbers of units on its steps; everything else as designed. */
    private static Plant plant(ColumnTrays trays, Build b) {
        var g = new TestGraph();
        var c = trays.components();
        var units = new HashMap<String, List<Unit>>();
        var column = g.unit(Kind.COLUMN, VAPOUR_OUTLET, REFLUX, FEED, PUMPAROUND_DRAW, RETURN, VAPOUR_RETURN, BOTTOMS);
        var receiver = g.drum(true, true, VAPOUR_OUTLET, LIQUID_OUTLET, BOOT_DRAIN, SIDE, SIDE);
        units.put("receiver", List.of(receiver));
        boolean air = c.condenser() == Condenser.AIR_COOLER;
        if (c.condenser() == Condenser.AIR_THEN_WATER) {
            // The air coolers' banks, then the water trim's shells, each set between a split and a merge.
            var banks = parallel(g, port(column, VAPOUR_OUTLET), Kind.AIR_COOLER, PROCESS_INLET, PROCESS_OUTLET, null, b.condensers(), b.oddSize(), b.openBranch(), false,
                    PROCESS_INLET, PROCESS_OUTLET);
            var shells = new ArrayList<Unit>();
            for (int i = 0; i < b.trims(); i++) shells.add(g.unit(Kind.EXCHANGER, SIZE, TUBE_INLET, TUBE_OUTLET, SHELL_INLET, SHELL_OUTLET));
            if (banks.size() == 1 && shells.size() == 1) g.pipe(port(banks.getFirst(), PROCESS_OUTLET), port(shells.getFirst(), SHELL_INLET));
            else {
                var merge = g.hub();
                if (banks.size() > 1) for (var u : banks) g.leg(merge, port(u, PROCESS_OUTLET));
                else g.leg(merge, port(banks.getFirst(), PROCESS_OUTLET));
                var split = merge;
                if (banks.size() > 1 && shells.size() > 1) {
                    split = g.hub();
                    g.join(merge, split);
                }
                for (var u : shells) g.leg(split, port(u, SHELL_INLET));
            }
            if (shells.size() == 1) g.pipe(port(shells.getFirst(), SHELL_OUTLET), port(receiver, SIDE, 1));
            else {
                var merge = g.hub();
                for (var u : shells) g.leg(merge, port(u, SHELL_OUTLET));
                g.leg(merge, port(receiver, SIDE, 1));
            }
            units.put("condenser", banks);
            units.put("trim", shells);
        } else
            units.put("condenser", parallel(g, port(column, VAPOUR_OUTLET), air ? Kind.AIR_COOLER : Kind.EXCHANGER, air ? PROCESS_INLET : SHELL_INLET,
                    air ? PROCESS_OUTLET : SHELL_OUTLET, port(receiver, SIDE, 1), b.condensers(), b.oddSize(), b.openBranch(), b.chained(),
                    air ? new Role[]{PROCESS_INLET, PROCESS_OUTLET} : new Role[]{TUBE_INLET, TUBE_OUTLET, SHELL_INLET, SHELL_OUTLET}));
        g.outside(port(receiver, VAPOUR_OUTLET), valve());
        g.outside(port(receiver, BOOT_DRAIN), valve());
        var pump = g.unit(Kind.PUMP, SUCTION, DISCHARGE);
        g.pipe(port(receiver, LIQUID_OUTLET), port(pump, SUCTION));
        g.tee(port(pump, DISCHARGE), List.of(), new Out(List.of(valve()), port(column, REFLUX)), new Out(List.of(valve()), null));
        g.outside(port(column, FEED), valveBack());
        // The pumparound: its pump, then its coolers in parallel back to the return.
        var paPump = g.unit(Kind.PUMP, SUCTION, DISCHARGE);
        g.pipe(port(column, PUMPAROUND_DRAW), port(paPump, SUCTION));
        units.put("pa_cooler", parallel(g, port(paPump, DISCHARGE), Kind.EXCHANGER, SHELL_INLET, SHELL_OUTLET, port(column, RETURN), b.coolers(), false, false, false,
                TUBE_INLET, TUBE_OUTLET, SHELL_INLET, SHELL_OUTLET));
        // The kettles: the bottoms split to them, their vapour joins to the return, their liquid to the bottoms valve.
        var kettles = parallel(g, port(column, BOTTOMS), Kind.KETTLE, FEED, null, null, b.kettles(), false, false, false, TUBE_INLET, TUBE_OUTLET, FEED, VAPOUR_OUTLET, LIQUID_OUTLET);
        units.put("kettle", kettles);
        if (kettles.size() == 1) {
            g.pipe(port(kettles.getFirst(), VAPOUR_OUTLET), port(column, VAPOUR_RETURN));
            g.outside(port(kettles.getFirst(), LIQUID_OUTLET), valve());
        } else {
            var vapour = g.hub();
            for (var k : kettles) g.leg(vapour, port(k, VAPOUR_OUTLET));
            g.leg(vapour, port(column, VAPOUR_RETURN));
            // The bottoms valve stands beyond the merge, on the way out.
            var liquid = g.hub();
            for (var k : kettles) g.leg(liquid, port(k, LIQUID_OUTLET));
            g.outsideLeg(liquid, valve());
        }
        return new Plant(trays, g, column, units);
    }

    private static List<Long> origins(List<Unit> units) {
        return units.stream().map(Unit::origin).toList();
    }

    @Test
    void identicalUnitsInParallelFillTheirStepsAndTheAssemblyIsComplete() {
        for (var condenser : Condenser.values()) {
            var p = plant(trays(condenser, new ColumnComponents.Units(3, 1, List.of(2))), new Build(3, 1, 2, false, false, condenser == Condenser.EXCHANGER));
            var r = p.match();
            assertTrue(r.complete(), condenser + ": " + r.slots());
            var slot = r.slot(CONDENSER);
            assertEquals(origins(p.units().get("condenser")), slot.units(), "the condenser's three units, the member first");
            assertEquals(origins(p.units().get("pa_cooler")), r.slot(pumparoundCooler(1)).units());
            assertTrue(r.members().containsAll(origins(p.units().get("condenser"))) && r.members().containsAll(origins(p.units().get("pa_cooler"))));
            // Every unit's process ports are piped; the exchanger's water ports stay utilities.
            for (var u : p.units().get("condenser"))
                for (var port : r.ports())
                    if (port.unit() == u.origin()) assertTrue(port.state() == PortState.CONNECTED || port.state() == PortState.UTILITY, u + ": " + port);
        }
    }

    /**
     * An air-then-water condenser (COOLER_MODEL_PLAN.md section 6): the air cooler's banks, then the trim's shells, in
     * series, each set in parallel; the assembly is complete on the design's counts, and another number of trim shells is
     * the trim's count fault.
     */
    @Test
    void anAirThenWaterCondenserMatchesItsBanksThenItsTrimShells() {
        for (int[] counts : new int[][]{{1, 1}, {3, 1}, {1, 2}, {2, 3}}) {
            var trays = trays(Condenser.AIR_THEN_WATER, new ColumnComponents.Units(counts[0], 1, List.of(1), counts[1]));
            var p = plant(trays, new Build(counts[0], 1, 1, false, false, false, counts[1]));
            var r = p.match();
            assertTrue(r.complete(), java.util.Arrays.toString(counts) + ": " + r.slots());
            assertEquals(origins(p.units().get("condenser")), r.slot(CONDENSER).units());
            assertEquals(origins(p.units().get("trim")), r.slot(TRIM_CONDENSER).units());
        }
        var two = trays(Condenser.AIR_THEN_WATER, new ColumnComponents.Units(2, 1, List.of(1), 2));
        var short1 = plant(two, new Build(2, 1, 1, false, false, false, 1)).match().slot(TRIM_CONDENSER);
        assertEquals(State.UNIT_COUNT, short1.state());
        assertEquals("1:2", short1.detail());
    }

    @Test
    void parallelKettlesJoinTheirVapourAndTheirLiquidAgain() {
        var p = plant(trays(Condenser.AIR_COOLER, new ColumnComponents.Units(1, 2, List.of(1))), Build.of(1, 2, 1));
        var r = p.match();
        assertEquals(origins(p.units().get("kettle")), r.slot(REBOILER).units());
        assertEquals(State.OK, r.slot(REBOILER).state(), r.slots().toString());
        for (var k : p.units().get("kettle"))
            for (var port : r.ports())
                if (port.unit() == k.origin() && port.role() != TUBE_INLET && port.role() != TUBE_OUTLET) assertEquals(PortState.CONNECTED, port.state(), port.toString());
    }

    @Test
    void anotherNumberOfUnitsIsACountFault() {
        var two = trays(Condenser.AIR_COOLER, new ColumnComponents.Units(2, 1, List.of(1)));
        var one = plant(two, Build.of(1, 1, 1)).match().slot(CONDENSER);
        assertEquals(State.UNIT_COUNT, one.state());
        assertEquals("1:2", one.detail());
        var three = plant(two, Build.of(3, 1, 1)).match();
        assertEquals(State.UNIT_COUNT, three.slot(CONDENSER).state());
        assertEquals("3:2", three.slot(CONDENSER).detail());
        assertFalse(three.complete());
        assertEquals(AssemblyReport.Colour.YELLOW, State.UNIT_COUNT.colour);
    }

    @Test
    void unitsOfAnotherSizeAndABranchToAnythingElseAreFaults() {
        var two = trays(Condenser.EXCHANGER, new ColumnComponents.Units(2, 1, List.of(1)));
        var odd = plant(two, new Build(2, 1, 1, true, false, false)).match().slot(CONDENSER);
        assertEquals(State.UNIT_SHAPES, odd.state());
        var open = plant(two, new Build(2, 1, 1, false, true, false)).match().slot(CONDENSER);
        assertEquals(State.BRANCHED, open.state());
    }

    /** A design of one unit a step matches as before the counts: a split to two condensers is a branch, and the line stops. */
    @Test
    void aSingleUnitStepStillStopsAtABranch() {
        var one = trays(Condenser.AIR_COOLER, ColumnComponents.Units.ONE);
        var r = plant(one, Build.of(2, 1, 1)).match();
        assertEquals(State.BRANCHED, r.slot(CONDENSER).state());
        assertEquals(List.of(), r.slot(CONDENSER).parallel());
        assertTrue(plant(one, Build.of(1, 1, 1)).match().complete());
    }
}
