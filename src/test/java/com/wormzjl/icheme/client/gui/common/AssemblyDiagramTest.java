package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.science.assembly.Blueprint;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ColumnTrays.Pumparound;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A column assembly's parts and ports over the design table (batch 2026-10-26-multi-machine-framework, plan sections 5.5
 * and 8): one part per slot of the blueprint; a port for every nozzle a line starts from or ends at, each with its own
 * colour for the laying tool.
 */
class AssemblyDiagramTest {
    private static final int TRAYS = 20, SUMP = TRAYS + 1;

    private static List<ColumnTrays> designs() {
        var steams = List.<List<Integer>>of(List.of(), List.of(18), List.of(17, 18), List.of(SUMP), List.of(18, SUMP));
        var out = new ArrayList<ColumnTrays>();
        for (var condenser : Condenser.values())
            for (boolean ejector : new boolean[]{false, true})
                for (boolean kettle : new boolean[]{false, true})
                    for (var steam : steams) {
                        // Without a kettle the sump takes steam (the calculator's requirement rules).
                        if (!kettle && !steam.contains(SUMP)) continue;
                        for (var reflux : Pump.values())
                            for (int draws = 0; draws <= ColumnTrays.MAX_DRAWS; draws++)
                                for (int n = 0; n <= ColumnTrays.MAX_PUMPAROUNDS; n++)
                                    for (var pa : Pump.values()) {
                                        var pumps = new ArrayList<Pump>();
                                        var pas = new ArrayList<Pumparound>();
                                        for (int i = 0; i < n; i++) {
                                            // Each pump of the other kind than the one before it, starting with pa.
                                            pumps.add(i % 2 == 0 ? pa : pa == Pump.STANDARD ? Pump.IN_LINE : Pump.STANDARD);
                                            pas.add(new Pumparound(4 + 4 * i, 2 + 4 * i));
                                        }
                                        out.add(new ColumnTrays(TRAYS, 10, List.of(3, 6, 9, 12).subList(0, draws), steam, pas,
                                                new ColumnComponents(condenser, reflux, pumps, ejector, kettle)));
                                    }
                    }
        return out;
    }

    @Test
    void everySlotHasOnePart() {
        int checked = 0;
        for (var trays : designs()) {
            var blueprint = ColumnBlueprint.of(trays);
            var parts = AssemblyDiagram.of(trays).parts();
            assertEquals(blueprint.slots().size(), parts.size(), trays::toString);
            assertEquals(new HashSet<>(blueprint.slots()), new HashSet<>(parts.stream().map(AssemblyDiagram.Part::slot).toList()), trays::toString);
            checked++;
        }
        assertEquals(1400 * Condenser.values().length, checked);
    }

    /**
     * Every text the parts name, and every text the screen builds from a report (its status line, its words, a part's
     * state with each detail the matcher writes, a port's state), has its English line: the hover texts are judged here,
     * as the bridge cannot hover in the game.
     */
    @Test
    void everyTextHasItsEnglishLine() throws java.io.IOException {
        java.util.Set<String> lang;
        try (var in = AssemblyDiagramTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            lang = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(java.util.Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().keySet();
        }
        var keys = new HashSet<String>();
        for (var trays : designs()) {
            var d = AssemblyDiagram.of(trays);
            for (var p : d.parts()) {
                for (var t : java.util.Arrays.asList(p.name(), p.where(), p.wanted())) if (t != null) keys.add(t.key());
            }
        }
        // The screen's own texts (AssemblyScreen).
        keys.addAll(List.of("title", "validate", "open_unit", "datasheet", "tab.operation", "tab.structure", "no_design", "operation", "queued",
                "status.no_design", "status.not_validated", "status.changed", "status.checked", "status.complete", "word.not_validated", "word.connected",
                "word.misplaced", "word.missing", "word.not_loaded", "state.not_validated", "state.ok", "state.missing", "state.missing.terminal",
                "state.missing.returns", "state.missing.found", "state.open_end", "state.open_end.unconnected", "state.not_loaded", "state.wrong_kind",
                "state.backwards", "state.wrong_port", "state.wrong_port.returns", "state.valve_reversed", "state.extra_valve", "state.wrong_order",
                "state.branched", "state.shared", "state.unit_count", "state.unit_shapes", "tool.line"));
        for (var f : List.of("drum", "drum_no_boot", "drum_standing", "control_valve", "inline_pump", "other")) keys.add("found." + f);
        var missing = keys.stream().map(k -> AssemblyDiagram.KEY + k).filter(k -> !lang.contains(k)).sorted().toList();
        assertEquals(List.of(), missing);
        // A unit the matcher names by its kind takes the datasheet's title; a role its datasheet name.
        for (var kind : com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.values())
            if (kind.designed() || kind == com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.DRUM)
                assertTrue(lang.contains("gui.icheme.vessel.title." + kind.name().toLowerCase(java.util.Locale.ROOT)), kind::name);
        for (var role : Role.values()) assertTrue(lang.contains("gui.icheme.vessel.role." + role.name().toLowerCase(java.util.Locale.ROOT)), role::name);
    }

    /** An air-then-water condenser (COOLER_MODEL_PLAN.md section 6): the air cooler, then its trim, which feeds the drum. */
    @Test
    void anAirThenWaterCondenserPipesItsAirCoolerToItsTrim() {
        var units = new ColumnComponents.Units(2, 1, List.of(), 3);
        var d = AssemblyDiagram.of(new ColumnTrays(TRAYS, 10, List.of(), List.of(18), List.of(), new ColumnComponents(Condenser.AIR_THEN_WATER, Pump.STANDARD, List.of(), true, true, units)));
        assertTrue(d.ports().stream().anyMatch(p -> p.slot().equals(ColumnBlueprint.CONDENSER) && p.partner().equals(ColumnBlueprint.TRIM_CONDENSER)), "the air cooler feeds the trim");
        assertTrue(d.ports().stream().anyMatch(p -> p.slot().equals(ColumnBlueprint.RECEIVER) && p.partner().equals(ColumnBlueprint.TRIM_CONDENSER)), "the trim feeds the drum");
    }

    /** The laying tool tells a column's nozzles apart by colour: each port its own, found by its role and number; a nozzle the design pipes nothing to, none. */
    @Test
    void everyPortHasItsOwnColourForTheLayingTool() {
        for (var trays : designs()) {
            var diagram = AssemblyDiagram.of(trays);
            var colours = new HashSet<Integer>();
            for (var port : diagram.ports()) {
                // A receiver SIDE port takes any nozzle number; other roles match exactly.
                int colour = PortColours.assembly(diagram, port.slot(), port.role(), port.ordinal() < 0 ? 3 : port.ordinal());
                assertTrue(colour >= 0, port::toString);
                assertTrue(colours.add(colour), () -> "a repeated colour: " + port + " " + trays);
            }
        }
        var diagram = AssemblyDiagram.of(new ColumnTrays(TRAYS, 10, List.of(), List.of(18), List.of(), new ColumnComponents(Condenser.EXCHANGER, Pump.STANDARD, List.of(), false, true)));
        assertEquals(-1, PortColours.assembly(diagram, ColumnBlueprint.CONDENSER, Role.TUBE_INLET, 0));
        assertEquals(-1, PortColours.assembly(diagram, ColumnBlueprint.COLUMN, Role.DRAW, ColumnTrays.MAX_DRAWS));
        assertEquals(-1, PortColours.assembly(diagram, ColumnBlueprint.pumparoundCooler(5), Role.SHELL_INLET, 0));
    }

    @Test
    void everyPortALineStartsFromOrEndsAtIsListed() {
        for (var trays : designs()) {
            var blueprint = ColumnBlueprint.of(trays);
            var diagram = AssemblyDiagram.of(trays);
            var marks = new HashSet<Blueprint.PortSpec>();
            for (var m : diagram.ports()) {
                marks.add(new Blueprint.PortSpec(m.slot(), m.role(), Math.max(0, m.ordinal())));
                assertTrue(blueprint.slots().contains(m.slot()), m::toString);
                assertTrue(blueprint.slots().contains(m.partner()), m::toString);
            }
            for (var link : blueprint.links()) {
                var start = link.start();
                if (start.role() != null) assertTrue(marks.contains(new Blueprint.PortSpec(start.slot(), start.role(), start.ordinal())), () -> link.name() + " " + trays);
                if (link.finish() instanceof Blueprint.ToPort to) assertTrue(marks.contains(to.port()), () -> link.name() + " " + trays);
            }
        }
    }
}
