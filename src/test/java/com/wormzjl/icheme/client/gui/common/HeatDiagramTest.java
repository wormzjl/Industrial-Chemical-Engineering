package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The heat units' Control-tab flowsheet over every unit and every exchanger line design (batch
 * 2026-10-27-heat-exchanger-design, HEAT_GUI_PLAN.md section 4): its parts inside the drawing and apart, one part per slot
 * the exchanger's lines ask for and a flowmeter on each valve, a square and a pill for every nozzle of the unit, readings
 * that name only the unit's nozzles and parts, and an English line for every text the drawing and the Control tab name.
 */
class HeatDiagramTest {
    private record Case(HeatSides.Unit unit, HeatSides.Side side, ExchangerBlueprint.Design design) {
    }

    private static List<Case> cases() {
        var out = new ArrayList<Case>();
        for (var tube : ExchangerBlueprint.Lines.values())
            for (var shell : ExchangerBlueprint.Lines.values())
                for (var side : List.of(HeatSides.Side.TUBE, HeatSides.Side.SHELL))
                    out.add(new Case(HeatSides.Unit.EXCHANGER, side, new ExchangerBlueprint.Design(tube, shell)));
        out.add(new Case(HeatSides.Unit.AIR_COOLER, HeatSides.Side.PROCESS, ExchangerBlueprint.Design.NONE));
        out.add(new Case(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.CONVECTION, ExchangerBlueprint.Design.NONE));
        out.add(new Case(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT, ExchangerBlueprint.Design.NONE));
        out.add(new Case(HeatSides.Unit.KETTLE, HeatSides.Side.TUBE, ExchangerBlueprint.Design.NONE));
        out.add(new Case(HeatSides.Unit.KETTLE, HeatSides.Side.SHELL, ExchangerBlueprint.Design.NONE));
        return out;
    }

    /** Every nozzle a unit's streams pass, both sides. */
    private static Set<String> roles(HeatSides.Unit unit) {
        var sides = switch (unit) {
            case EXCHANGER -> List.of(HeatSides.Side.TUBE, HeatSides.Side.SHELL);
            case AIR_COOLER -> List.of(HeatSides.Side.PROCESS);
            case FIRED_HEATER -> List.of(HeatSides.Side.CONVECTION, HeatSides.Side.RADIANT);
            case KETTLE -> List.of(HeatSides.Side.TUBE, HeatSides.Side.SHELL);
        };
        var roles = new HashSet<String>();
        for (var side : sides) roles.addAll(HeatDiagram.roles(unit, side));
        return roles;
    }

    @Test
    void everyPartLiesInsideTheDrawingAndNoPartCoversAnother() {
        for (var c : cases()) {
            var d = HeatDiagram.of(c.unit(), c.side(), c.design());
            for (var p : d.parts()) {
                assertTrue(p.x() >= 0 && p.y() >= 0 && p.x() + p.w() <= HeatDiagram.WIDTH && p.y() + p.h() <= HeatDiagram.HEIGHT, () -> c + " " + p);
                for (var q : d.parts())
                    if (p != q)
                        assertFalse(p.x() < q.x() + q.w() && q.x() < p.x() + p.w() && p.y() < q.y() + q.h() && q.y() < p.y() + p.h(), () -> c + ": " + p + " over " + q);
            }
            for (var p : d.pills()) assertTrue(p.x() >= 0 && p.x() + p.w() <= HeatDiagram.WIDTH && p.y() - 9 >= 0 && p.y() + 9 <= HeatDiagram.HEIGHT, () -> c + " " + p);
            for (var w : d.wires()) {
                assertEquals(0, w.points().length % 2);
                for (int i = 0; i + 3 < w.points().length; i += 2)
                    assertTrue(w.points()[i] == w.points()[i + 2] || w.points()[i + 1] == w.points()[i + 3], () -> c + ": lines run across or down");
                for (int v : w.points()) assertTrue(v >= 0 && v <= Math.max(HeatDiagram.WIDTH, HeatDiagram.HEIGHT), () -> c + ": a line inside the drawing");
            }
        }
    }

    @Test
    void anExchangerDrawsEverySlotItsLinesAskForAndAFlowmeterOnEachValve() {
        int checked = 0;
        for (var c : cases()) {
            if (c.unit() != HeatSides.Unit.EXCHANGER) continue;
            var d = HeatDiagram.of(c.unit(), c.side(), c.design());
            var drawn = new HashSet<String>();
            for (var p : d.parts()) if (p.line()) assertTrue(drawn.add(p.slot()), () -> c + ": one part per slot " + p.slot());
            var asked = new HashSet<String>();
            if (c.design().any())
                for (var s : ExchangerBlueprint.of(c.design()).slots()) if (!s.equals(ExchangerBlueprint.EXCHANGER)) asked.add(s.part());
            var valves = asked.stream().filter(s -> s.endsWith("_valve")).toList();
            var expected = new HashSet<>(asked);
            for (var v : valves) expected.add(v + "_meter");
            assertEquals(expected, drawn, c::toString);
            checked++;
        }
        assertEquals(18, checked);
    }

    @Test
    void everyNozzleHasItsSquareAndItsPillAndEveryReadingNamesTheUnit() {
        for (var c : cases()) {
            var d = HeatDiagram.of(c.unit(), c.side(), c.design());
            var roles = roles(c.unit());
            assertEquals(roles, new HashSet<>(d.nozzles().stream().map(HeatDiagram.Nozzle::role).toList()), c::toString);
            assertEquals(roles, new HashSet<>(d.pills().stream().map(HeatDiagram.Pill::role).toList()), c::toString);
            assertEquals(d.pills().size(), roles.size(), c::toString);
            for (var n : d.nozzles()) EquipmentShape.Role.valueOf(n.role());
            var slots = new HashSet<String>();
            for (var p : d.parts()) slots.add(p.slot());
            var values = new ArrayList<HeatDiagram.Value>();
            for (var p : d.pills()) values.add(p.value());
            for (var t : d.tags()) values.add(t.value());
            for (var v : values)
                for (var s : v.sources()) {
                    if (s.startsWith("port:")) assertTrue(roles.contains(s.substring(5)), () -> c + " reads " + s);
                    else if (s.startsWith("part:")) {
                        // A reading of a part the design does not draw falls through to the next source.
                        if (slots.contains(s.substring(5))) continue;
                        assertTrue(v.sources().indexOf(s) < v.sources().size() - 1, () -> c + " reads only an absent part " + s);
                    } else assertTrue(s.equals("duty") || s.equals("air"), () -> c + " reads " + s);
                }
        }
    }

    /** Every text the drawing names and the Control tab and stream switch build has its English line. */
    @Test
    void everyTextHasItsEnglishLine() throws java.io.IOException {
        Set<String> lang;
        try (var in = HeatDiagramTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            lang = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(java.util.Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().keySet();
        }
        var keys = new HashSet<String>();
        for (var c : cases()) {
            var d = HeatDiagram.of(c.unit(), c.side(), c.design());
            for (var p : d.parts()) {
                if (p.label() != null) keys.add(HeatDiagram.KEY + p.label().key());
                if (p.line()) {
                    keys.add("gui.icheme.assembly.part." + p.slot());
                    keys.add("gui.icheme.assembly.where." + p.slot());
                }
            }
            for (var l : d.labels()) keys.add(HeatDiagram.KEY + l.text().key());
            for (var p : d.pills()) keys.add(HeatDiagram.KEY + p.name().key());
        }
        keys.add(HeatDiagram.KEY + "air");
        // The Control tab's own texts (FluidDeviceScreen): sides, modes, actuators, fields, effort, notes, status words.
        for (var k : List.of("control", "control.hint", "heat_side.tube", "heat_side.shell", "heat_side.convection", "heat_side.radiant", "heat_side.kettle_tube",
                "heat_side.pool", "heat_side.hint", "heat_holds", "heat_field.temperature", "heat_field.duty", "heat_ratio_input", "heat_field.bypass", "heat_field.low_limit",
                "heat_field.low_limit.hint", "heat_field.off", "heat_acting", "pool_note", "reader_note", "legend.in_place", "legend.misplaced", "legend.missing",
                "heat_status.manual", "heat_status.holding", "heat_status.short", "heat_status.low_limit", "heat_status.ratio", "heat_status.ratio_waiting",
                "heat_status.firing", "heat_status.firing_short", "heat_status.firing_most", "heat_status.burners_out", "heat_status.boiling", "status_base.steady",
                "status_base.no_flow", "status_base.full", "status.low_limit", "status.duty_short", "stream.contents", "stream.hint", "stream.empty", "reading.no_flow",
                "line_temperature.hint", "check_lines", "check_lines.hint", "fans_on", "fans_off", "fans_on.hint", "fans_off.hint"))
            keys.add("gui.icheme.fluid." + k);
        for (var m : List.of("off", "outlet_temperature", "duty", "ratio", "burners_out", "firing")) {
            keys.add("gui.icheme.fluid.heat_mode." + m + ".title");
            keys.add("gui.icheme.fluid.heat_mode." + m + ".line");
        }
        for (var a : com.wormzjl.icheme.runtime.fluid.FluidView.Heat.ACTUATORS) {
            keys.add("gui.icheme.fluid.heat_actuator." + a);
            keys.add("gui.icheme.fluid.heat_acting." + a);
            if (!a.equals("none")) keys.add("gui.icheme.fluid.heat_effort." + a);
        }
        for (var kind : List.of("bypass", "ratio"))
            for (var s : com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.Lines.Status.values())
                keys.add("gui.icheme.fluid.lines_note." + kind + "." + s.name().toLowerCase(Locale.ROOT));
        var missing = keys.stream().filter(k -> !lang.contains(k)).sorted().toList();
        assertEquals(List.of(), missing);
        // The exchanger's ratio field has its own line; the compressor's keeps its own.
        assertTrue(lang.contains("gui.icheme.fluid.ratio_input"));
    }

    /** No key is defined twice: the language file keeps the last, so a second definition silently renames a field. */
    @Test
    void noLanguageKeyIsDefinedTwice() throws java.io.IOException {
        String text;
        try (var in = HeatDiagramTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            text = new String(java.util.Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var seen = new HashSet<String>();
        var twice = new ArrayList<String>();
        var m = java.util.regex.Pattern.compile("(?m)^\\s*\"([^\"]+)\"\\s*:").matcher(text);
        while (m.find()) if (!seen.add(m.group(1))) twice.add(m.group(1));
        assertEquals(List.of(), twice);
    }
}
