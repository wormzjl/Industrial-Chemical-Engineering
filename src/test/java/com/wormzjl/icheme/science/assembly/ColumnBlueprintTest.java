package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.assembly.Blueprint.*;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ColumnTrays.Pumparound;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
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
