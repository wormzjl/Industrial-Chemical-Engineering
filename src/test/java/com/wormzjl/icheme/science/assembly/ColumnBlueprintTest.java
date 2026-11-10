package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.Blueprint.*;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ColumnTrays.Pumparound;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.wormzjl.icheme.science.assembly.ColumnBlueprint.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The column's blueprint (batch 2026-10-26-multi-machine-framework, plan sections 5.1 and 5.2): which slots and links a
 * saved component design asks for.
 */
class ColumnBlueprintTest {
    private static ColumnTrays trays(ColumnComponents components, List<Integer> draws, List<Integer> steam, List<Pumparound> pumparounds) {
        return new ColumnTrays(20, 10, draws, steam, pumparounds, components);
    }

    private static Link link(Blueprint blueprint, String name) {
        return blueprint.links().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("No link " + name));
    }

    @Test
    void aPlainColumnAsksForItsOverheadSystemTheFeedAndTheBottoms() {
        var blueprint = ColumnBlueprint.of(trays(new ColumnComponents(Condenser.AIR_COOLER, Pump.STANDARD, List.of(), false, true), List.of(), List.of(), List.of()));
        assertEquals(List.of(COLUMN, CONDENSER, RECEIVER, OFFGAS_VALVE, WATER_VALVE, REFLUX_PUMP, REFLUX_TEE, REFLUX_VALVE, DISTILLATE_VALVE, FEED_VALVE,
                REBOILER, BOTTOMS_VALVE), blueprint.slots());
        assertEquals(COLUMN, blueprint.master());
        assertEquals(List.of("overhead", "condensate", "offgas", "water", "oil", "feed", "reboiler", "vapour_return", "bottoms"),
                blueprint.links().stream().map(Link::name).toList());
    }

    /**
     * A vacuum top held by a vacuum pump (batch 2026-11-10-vacuum-pump, owner V5): no ejector; the pump of the design's type on the
     * receiver's vapour outlet before the offgas valve, and a pump of the reflux pump's kind on the boot's water before its valve.
     */
    @Test
    void aVacuumPumpStandsOnTheReceiversVapourOutlet() {
        for (var vacuum : List.of(ColumnComponents.Vacuum.LIQUID_RING, ColumnComponents.Vacuum.DRY_SCREW)) {
            var c = new ColumnComponents(Condenser.AIR_COOLER, Pump.IN_LINE, List.of(), true, true).withVacuum(vacuum);
            var blueprint = ColumnBlueprint.of(trays(c, List.of(), List.of(), List.of()));
            assertEquals(List.of(COLUMN, CONDENSER, RECEIVER, VACUUM_PUMP, OFFGAS_VALVE, WATER_PUMP, WATER_VALVE, REFLUX_PUMP, REFLUX_TEE, REFLUX_VALVE, DISTILLATE_VALVE,
                    FEED_VALVE, REBOILER, BOTTOMS_VALVE), blueprint.slots(), vacuum::name);
            assertEquals(List.of(CONDENSER), link(blueprint, "overhead").steps().stream().map(Step::slot).toList(), "no ejector");
            var offgas = link(blueprint, "offgas");
            assertEquals(List.of(VACUUM_PUMP, OFFGAS_VALVE), offgas.steps().stream().map(Step::slot).toList());
            var pump = (UnitForm) offgas.steps().getFirst().form();
            assertEquals(vacuum == ColumnComponents.Vacuum.DRY_SCREW ? Kind.DRY_SCREW_PUMP : Kind.LIQUID_RING_PUMP, pump.kind());
            assertEquals(Role.SUCTION, pump.in());
            assertEquals(Role.DISCHARGE, pump.out());
            var water = link(blueprint, "water");
            assertEquals(List.of(WATER_PUMP, WATER_VALVE), water.steps().stream().map(Step::slot).toList());
            assertEquals(new FittingForm(PortGraph.Fitting.INLINE_PUMP), water.steps().getFirst().form(), "the reflux pump's kind");
        }
        // An ejector design keeps its ejector, whatever field it was saved with.
        var ejected = ColumnBlueprint.of(trays(new ColumnComponents(Condenser.AIR_COOLER, Pump.STANDARD, List.of(), true, true), List.of(), List.of(), List.of()));
        assertTrue(ejected.slots().contains(EJECTOR));
        assertFalse(ejected.slots().contains(VACUUM_PUMP));
        assertFalse(ejected.slots().contains(WATER_PUMP));
    }

    /**
     * An air-then-water condenser (COOLER_MODEL_PLAN.md section 6): the overhead passes the air cooler's banks, then the
     * water trim's shells, and ends there; the condensate starts after the trim. The trim's water sides cool the condenser.
     */
    @Test
    void anAirThenWaterCondenserRunsItsAirCoolerThenItsTrim() {
        for (boolean ejector : new boolean[]{false, true}) {
            var units = ColumnComponents.Units.ONE.withCondenser(3).withTrim(2);
            var blueprint = ColumnBlueprint.of(trays(new ColumnComponents(Condenser.AIR_THEN_WATER, Pump.STANDARD, List.of(), ejector, true, units), List.of(), List.of(), List.of()));
            int at = blueprint.slots().indexOf(CONDENSER);
            assertEquals(TRIM_CONDENSER, blueprint.slots().get(at + 1), "the trim's slot after the condenser's");
            var overhead = link(blueprint, "overhead");
            assertEquals(ejector ? List.of(EJECTOR, CONDENSER, TRIM_CONDENSER) : List.of(CONDENSER, TRIM_CONDENSER), overhead.steps().stream().map(Step::slot).toList());
            assertEquals(TRIM_CONDENSER, overhead.owner());
            var air = overhead.steps().get(ejector ? 1 : 0);
            var trim = overhead.steps().getLast();
            assertEquals(new UnitForm(Kind.AIR_COOLER, false, Role.PROCESS_INLET, Role.PROCESS_OUTLET, Set.of()), air.form(), "the air cooler passes the overhead on");
            assertEquals(3, air.count());
            assertEquals(Kind.EXCHANGER, ((UnitForm) air.family().getFirst()).kind(), "an exchanger fills the air cooler's slot as the wrong kind");
            var water = (UnitForm) trim.form();
            assertEquals(Kind.EXCHANGER, water.kind());
            assertEquals(Role.SHELL_INLET, water.in());
            assertNull(water.out(), "the overhead link ends at the trim");
            assertEquals(Set.of(Role.TUBE_INLET, Role.TUBE_OUTLET), water.utilities());
            assertEquals(2, trim.count());
            assertEquals(Start.after(TRIM_CONDENSER), link(blueprint, "condensate").start());
        }
        assertNull(ColumnBlueprint.waterCooled(Condenser.AIR_COOLER));
        assertEquals(CONDENSER, ColumnBlueprint.waterCooled(Condenser.EXCHANGER));
        assertEquals(TRIM_CONDENSER, ColumnBlueprint.waterCooled(Condenser.AIR_THEN_WATER));
        // The other condensers take no trim, whatever its count.
        for (var other : List.of(Condenser.AIR_COOLER, Condenser.EXCHANGER)) {
            var blueprint = ColumnBlueprint.of(trays(new ColumnComponents(other, Pump.STANDARD, List.of(), false, true, ColumnComponents.Units.ONE.withTrim(4)), List.of(), List.of(), List.of()));
            assertFalse(blueprint.slots().contains(TRIM_CONDENSER), other::name);
            assertEquals(Start.after(CONDENSER), link(blueprint, "condensate").start(), other::name);
        }
    }

    /**
     * The column's ports its links start from or end at, tee branches included. Owner answer Q9 (a): these are exactly the
     * nozzles of the column built from the same design, so no nozzle stays without a line and no line lacks its nozzle.
     */
    @Test
    void theBlueprintNamesEveryNozzleOfTheBuiltColumnAndNoOther() {
        var pumparounds = List.of(new Pumparound(5, 3), new Pumparound(9, 7));
        for (boolean kettle : new boolean[]{true, false})
            for (var steam : List.of(List.of(21), List.of(18, 21)))
                for (var draws : List.of(List.<Integer>of(), List.of(4, 12))) {
                    var components = new ColumnComponents(Condenser.AIR_COOLER, Pump.STANDARD, List.of(Pump.STANDARD, Pump.IN_LINE), false, kettle);
                    var trays = trays(components, draws, steam, pumparounds);
                    var column = EquipmentShape.column(4, trays, EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(4));
                    assertEquals(List.of(), ColumnDesign.misfit(column).stream().toList(), trays::toString);
                    var nozzles = new HashSet<PortSpec>();
                    var seen = new EnumMap<Role, Integer>(Role.class);
                    for (var nozzle : column.nozzles()) nozzles.add(new PortSpec(COLUMN, nozzle.role(), seen.merge(nozzle.role(), 1, Integer::sum) - 1));
                    assertEquals(nozzles, columnPorts(ColumnBlueprint.of(trays)), trays::toString);
                    assertEquals(kettle, seen.containsKey(Role.VAPOUR_RETURN), trays::toString);
                }
    }

    private static Set<PortSpec> columnPorts(Blueprint blueprint) {
        var ports = new HashSet<PortSpec>();
        for (var link : blueprint.links()) {
            if (link.start().slot().equals(COLUMN)) ports.add(new PortSpec(COLUMN, link.start().role(), link.start().ordinal()));
            var finishes = new ArrayList<Finish>(List.of(link.finish()));
            for (var step : link.steps()) if (step.form() instanceof TeeForm tee) for (var branch : tee.branches()) finishes.add(branch.finish());
            for (var finish : finishes) if (finish instanceof ToPort to && to.port().slot().equals(COLUMN)) ports.add(to.port());
        }
        return ports;
    }
}
