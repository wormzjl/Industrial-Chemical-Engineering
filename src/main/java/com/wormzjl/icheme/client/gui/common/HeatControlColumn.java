package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Format;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.science.equipment.HeatSides;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * The heat Control tab's column (HEAT_GUI_PLAN.md section 2) as rows of the GUI kit (batch 2026-10-28-gui-toolkit): the
 * side selector, the modes each naming the unit's actuator, the fields, what acts now with its effort, and the notes.
 * Built from the side's view, its applied control and the screen's drafts; no Minecraft classes, so the gates and the
 * render tool build the same column the screen draws.
 * <p>
 * Widget keys: {@code side:0} and {@code side:1} (the side selector; the other side opens its own screen), {@code power}
 * (an air cooler's fans by hand), {@code check_lines} (an exchanger's lines, W8), and the fields' draft keys. Choice
 * actions: {@code mode:<mode>}.
 */
public final class HeatControlColumn {
    private HeatControlColumn() {
    }

    /** A text under {@code gui.icheme.fluid.}, its arguments filled in. */
    public interface Texts {
        String get(String key, Object... args);
    }

    /**
     * What the column reads: the side's view; its applied control (null for a side without one); whether this client may
     * edit; whether the side has a control of its own (an exchanger side, an air cooler, a kettle's tubes); the drafted
     * mode and power; the player's temperature unit symbol.
     */
    public record Input(FluidView.Heat heat, @Nullable HeatUnit.Control applied, boolean canEdit, boolean controllable,
                        HeatUnit.Mode mode, boolean powerOn, String temperatureUnit) {
        HeatSides.Unit unit() {
            return HeatSides.Unit.valueOf(heat.unit());
        }

        HeatSides.Side side() {
            return HeatSides.Side.valueOf(heat.side());
        }

        boolean burners() {
            return heat.burners() != null;
        }

        boolean fans() {
            return heat.fans() != null;
        }

        /** A kettle's shell: its pool, which the tubes heat; no control of its own. */
        boolean pool() {
            return unit() == HeatSides.Unit.KETTLE && side() == HeatSides.Side.SHELL;
        }
    }

    /** A mode of the list and whether it is the drafted one. */
    public record Mode(String key, boolean selected) {
    }

    public static List<Column.Row> rows(Input in, Texts t) {
        var rows = new ArrayList<Column.Row>();
        var sides = sides(in);
        if (sides != null) {
            var buttons = new ArrayList<Column.Button>();
            for (int i = 0; i < 2; i++) buttons.add(new Column.Button("side:" + i, t.get("heat_side." + sides[i]), i != own(in), t.get("heat_side.hint")));
            rows.add(new Column.Buttons(buttons));
            rows.add(new Column.Gap(2));
        }
        if (in.pool()) rows.add(new Column.Paragraph(t.get("pool_note"), Palette.MUTED));
        else {
            rows.add(new Column.Heading(t.get("heat_holds")));
            rows.add(new Column.Gap(2));
            for (var m : modes(in))
                rows.add(new Column.Choice(t.get("heat_mode." + m.key() + ".title"), t.get("heat_mode." + m.key() + ".line", t.get("heat_actuator." + actuator(in, m.key()))),
                        m.selected(), in.canEdit() ? "mode:" + m.key() : null));
            // An air cooler in manual: its fans switch by hand.
            if (in.fans() && in.mode() == HeatUnit.Mode.OFF && in.canEdit()) {
                String key = in.powerOn() ? "fans_on" : "fans_off";
                rows.add(new Column.Buttons(List.of(new Column.Button("power", t.get(key), true, t.get(key + ".hint")))));
            }
            rows.addAll(fields(in, t));
        }
        // What acts now, named by the server, and how hard it works.
        String acting = in.heat().actuator();
        if (!acting.equals("none")) {
            rows.add(new Column.Line(t.get("heat_acting", t.get("heat_acting." + acting)), Palette.MUTED));
            Double effort = in.heat().effort();
            if (effort != null) {
                rows.add(new Column.Bar(Math.clamp(effort, 0, 1)));
                rows.add(new Column.Line(t.get("heat_effort." + acting, Format.number(effort * 100)), Palette.TEXT));
            } else if (acting.equals("feed_valve")) {
                double r = measuredRatio(in);
                rows.add(new Column.Line(t.get("heat_effort.feed_valve", Double.isFinite(r) ? Format.number(r) : "—"), Palette.TEXT));
            }
            rows.add(new Column.Gap(2));
        }
        String note = linesNote(in, t);
        if (note != null) rows.add(new Column.Paragraph(note, "COMPLETE".equals(in.heat().lines().status()) ? Palette.ACCENT : Palette.WARN));
        // Check lines (W8) under the lines' note: the drawing keeps its width for the legend.
        if (in.canEdit() && in.heat().lines() != null)
            rows.add(new Column.Buttons(List.of(new Column.Button("check_lines", t.get("check_lines"), true, t.get("check_lines.hint")))));
        if (!in.canEdit() && !in.pool()) rows.add(new Column.Paragraph(t.get("reader_note"), Palette.MUTED));
        return rows;
    }

    /** The column in two (layout C): the side selector and the modes, or the pool's note; then the rest. */
    public record Columns(List<Column.Row> left, List<Column.Row> right) {
    }

    /**
     * Layout C (owner: "C looks better", GUI_TOOLKIT_PLAN.md sections 7 and 10): the side selector and the modes (or the
     * pool's note) on the left; the fans, the fields, what acts, the notes and Check lines on the right. The small gaps go,
     * except under a heading.
     */
    public static Columns columns(List<Column.Row> rows) {
        int cut = 0;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Column.Choice) cut = i + 1;
        for (int i = 0; i < rows.size() && cut == 0; i++) if (rows.get(i) instanceof Column.Paragraph) cut = i + 1;
        return new Columns(gapless(rows.subList(0, cut)), gapless(rows.subList(cut, rows.size())));
    }

    private static List<Column.Row> gapless(List<Column.Row> rows) {
        var kept = new ArrayList<Column.Row>();
        for (int i = 0; i < rows.size(); i++) if (!(rows.get(i) instanceof Column.Gap) || i > 0 && rows.get(i - 1) instanceof Column.Heading) kept.add(rows.get(i));
        return kept;
    }

    /** The side selector's two sides, or null for a one-sided unit (an air cooler). */
    static String @Nullable [] sides(Input in) {
        if (!in.heat().paired()) return null;
        return switch (in.unit()) {
            case EXCHANGER -> new String[]{"tube", "shell"};
            case FIRED_HEATER -> new String[]{"convection", "radiant"};
            case KETTLE -> new String[]{"kettle_tube", "pool"};
            default -> null;
        };
    }

    /** Which of the selector's two sides this is. */
    static int own(Input in) {
        return switch (in.side()) {
            case TUBE, CONVECTION, PROCESS -> 0;
            default -> 1;
        };
    }

    /** The modes this side lists (Q-G2 (a): a heater's burners as two modes, out and firing). */
    public static List<Mode> modes(Input in) {
        if (in.burners()) return List.of(new Mode("burners_out", !in.powerOn()), new Mode("firing", in.powerOn()));
        if (!in.controllable()) return List.of();
        var list = new ArrayList<Mode>();
        var all = in.unit() == HeatSides.Unit.EXCHANGER
                ? List.of(HeatUnit.Mode.OFF, HeatUnit.Mode.OUTLET_TEMPERATURE, HeatUnit.Mode.DUTY, HeatUnit.Mode.RATIO)
                : List.of(HeatUnit.Mode.OFF, HeatUnit.Mode.OUTLET_TEMPERATURE, HeatUnit.Mode.DUTY);
        for (var m : all) list.add(new Mode(m.name().toLowerCase(Locale.ROOT), in.mode() == m));
        return list;
    }

    /** What a mode moves, named from the server's view (fans or louvers, burners), else the bypass; a ratio its feed valve. */
    static String actuator(Input in, String mode) {
        if (mode.equals("ratio")) return "feed_valve";
        String a = in.heat().actuator();
        return a.equals("fans") || a.equals("louvers") || a.equals("burners") ? a : "bypass";
    }

    /** The fields of the drafted mode, then the low limit. */
    public static List<Column.Field> fields(Input in, Texts t) {
        var list = new ArrayList<Column.Field>();
        String unit = in.temperatureUnit();
        if (in.burners()) list.add(new Column.Field("temperature", t.get("heat_field.temperature"), unit, null));
        if (in.controllable()) {
            switch (in.mode()) {
                case OUTLET_TEMPERATURE -> list.add(new Column.Field("heatTemperature", t.get("heat_field.temperature"), unit, null));
                case DUTY -> list.add(new Column.Field("heatDuty", t.get("heat_field.duty"), "kW", null));
                case RATIO -> list.add(new Column.Field("heatRatio", t.get("heat_ratio_input"), "", null));
                default -> {
                    if (!in.fans()) list.add(new Column.Field("bypass", t.get("heat_field.bypass"), "%", null));
                }
            }
            list.add(new Column.Field("lowLimit", t.get("heat_field.low_limit"), unit, t.get("heat_field.low_limit.hint")));
        }
        return list;
    }

    /** This feed over the other side's, as the ports read: what a ratio holds. */
    static double measuredRatio(Input in) {
        var heat = in.heat();
        var own = HeatDiagram.roles(in.unit(), in.side());
        var other = HeatDiagram.roles(in.unit(), in.side() == HeatSides.Side.TUBE ? HeatSides.Side.SHELL : HeatSides.Side.TUBE);
        var a = heat.port(own.getFirst());
        var b = heat.port(other.getFirst());
        return a == null || b == null || b.massFlow() == 0 ? Double.NaN : Math.abs(a.massFlow()) / Math.abs(b.massFlow());
    }

    /** The note on this side's lines (W8), or null where it asks for none. */
    static @Nullable String linesNote(Input in, Texts t) {
        var lines = in.heat().lines();
        if (lines == null || lines.status().equals("NONE") || in.applied() == null) return null;
        String kind = in.applied().mode() == HeatUnit.Mode.RATIO ? "ratio" : "bypass";
        return t.get("lines_note." + kind + "." + lines.status().toLowerCase(Locale.ROOT), lines.missing());
    }
}
