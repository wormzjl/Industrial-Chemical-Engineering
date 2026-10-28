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
 * The heat Control tab's column (HEAT_GUI_PLAN.md section 2) as function groups of the GUI kit (batch
 * 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 11): the side selector; Control, an Off and On switch (owner,
 * 2026-10-28: "a On/off switch plus a target duty mode and target temperature mode"); Target, what the controller holds;
 * Set point, or By hand while Off; and the actuator that acts now, with its effort and its lines. Plates name each group
 * in a word or two; the sentences are hovers. Built from the side's view, its applied control and the screen's drafts; no
 * Minecraft classes, so the gates and the render tool build the same column the screen draws.
 * <p>
 * Widget keys: {@code side:0} and {@code side:1} (the side selector; the other side opens its own screen),
 * {@code power:off} and {@code power:on} (the switch), {@code check_lines} (an exchanger's lines, W8), and the fields'
 * draft keys. Choice actions: {@code target:<mode>}, and an air cooler's {@code target:fan_speed}.
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
     * mode (OFF while switched off, or for an air cooler's fixed fan speed) and power (a heater's burners, an air
     * cooler's switch); the player's temperature unit symbol.
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

        /** Whether the switch stands at On: a heater's burners lit, an air cooler's fans running, a controller holding a target. */
        boolean on() {
            return burners() || fans() ? powerOn : mode != HeatUnit.Mode.OFF;
        }
    }

    /** The whole column, as one column on a narrow screen: the left's rows, then the right's. */
    public static List<Column.Row> rows(Input in, Texts t) {
        var two = columns(in, t);
        var rows = new ArrayList<>(two.left());
        rows.addAll(two.right());
        return rows;
    }

    /** The column in two (layout C): what to operate on the left, what to set and what acts on the right. */
    public record Columns(List<Column.Row> left, List<Column.Row> right) {
    }

    /**
     * Layout C (owner: "C looks better", GUI_TOOLKIT_PLAN.md sections 7, 10 and 11): the side selector, Control and
     * Target (or the pool's note) on the left; Set point or By hand, the actuator's group and the reader's note on the
     * right.
     */
    public static Columns columns(Input in, Texts t) {
        var left = new ArrayList<Column.Row>(sideSelector(in, t));
        var right = new ArrayList<Column.Row>();
        if (in.pool()) left.add(new Column.Paragraph(t.get("pool_note"), Palette.MUTED));
        else if (in.burners() || in.controllable()) {
            boolean on = in.on();
            String actuator = t.get("heat_actuator." + actuator(in)), hints = in.burners() ? "heat_switch.burners_" : in.fans() ? "heat_switch.fans_" : "heat_switch.";
            left.add(new Column.Group(t.get("heat_group.control"), null, List.of(new Column.Buttons(List.of(
                    position("power:off", t.get("heat_switch.off"), t.get(hints + "off.hint", actuator), !on, in.canEdit()),
                    position("power:on", t.get("heat_switch.on"), t.get(hints + "on.hint", actuator), on, in.canEdit()))))));
            if (on && in.controllable()) {
                var targets = new ArrayList<Column.Row>();
                for (var key : targets(in))
                    targets.add(new Column.Choice(t.get("heat_target." + key), t.get("heat_target." + key + ".hint", actuator), key.equals(target(in)), in.canEdit() ? "target:" + key : null));
                left.add(new Column.Group(t.get("heat_group.target"), t.get("heat_group.target.hint"), targets));
            }
            var settings = settings(in, t);
            if (!settings.isEmpty()) right.add(new Column.Group(t.get(on ? "heat_group.set_point" : "heat_group.manual"), null, settings));
        }
        // What acts now, named by the server, how hard it works, and the lines it works through.
        var acting = new ArrayList<Column.Row>();
        String now = in.heat().actuator();
        if (!now.equals("none")) {
            Double effort = in.heat().effort();
            if (effort != null) {
                acting.add(new Column.Bar(Math.clamp(effort, 0, 1)));
                acting.add(new Column.Line(t.get("heat_effort." + now, Format.number(effort * 100)), Palette.TEXT));
            } else if (now.equals("feed_valve")) {
                double r = measuredRatio(in);
                acting.add(new Column.Line(t.get("heat_effort.feed_valve", Double.isFinite(r) ? Format.number(r) : "—"), Palette.TEXT));
            }
        }
        String note = linesNote(in, t);
        if (note != null) acting.add(new Column.Paragraph(note, Palette.WARN));
        // Check lines (W8) where the drafted target needs lines: a bypass, or a ratio's feed valve.
        if (in.canEdit() && in.heat().lines() != null && in.mode() != HeatUnit.Mode.OFF)
            acting.add(new Column.Buttons(List.of(new Column.Button("check_lines", t.get("check_lines"), true, t.get("check_lines.hint")))));
        // The plate names what acts: the real valve once the lines are complete, else the internal bypass.
        String plate = now.equals("none") ? "heat_group.lines" : "heat_acting." + now;
        if (!acting.isEmpty()) right.add(new Column.Group(t.get(plate), t.get(plate + ".hint"), acting));
        if (!in.canEdit() && !in.pool()) right.add(new Column.Paragraph(t.get("reader_note"), Palette.MUTED));
        return new Columns(left, right);
    }

    /**
     * The side selector, this side latched, and a gap under it; none for a one-sided unit (an air cooler). The Control tab
     * and the side's other pages both lead with it ({@code DeviceColumn}).
     */
    public static List<Column.Row> sideSelector(Input in, Texts t) {
        var sides = sides(in);
        if (sides == null) return List.of();
        var buttons = new ArrayList<Column.Button>();
        for (int i = 0; i < 2; i++) buttons.add(position("side:" + i, t.get("heat_side." + sides[i]), t.get("heat_side.hint"), i == own(in), true));
        return List.of(new Column.Buttons(buttons), new Column.Gap(4));
    }

    /** A position of a selector or a switch: latched where it stands, a click acts on another while this client edits. */
    private static Column.Button position(String key, String text, String hint, boolean current, boolean canEdit) {
        return new Column.Button(key, text, canEdit && !current, hint, current);
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

    /**
     * What a switched-on side holds: the outlet temperature or the duty; an exchanger side also its feed's ratio, an air
     * cooler also a fixed fan speed (owner, 2026-10-28: "AC should have duty ratio mode and target temperature mode as
     * well").
     */
    public static List<String> targets(Input in) {
        return switch (in.unit()) {
            case EXCHANGER -> List.of("outlet_temperature", "duty", "ratio");
            case AIR_COOLER -> List.of("outlet_temperature", "duty", "fan_speed");
            default -> List.of("outlet_temperature", "duty");
        };
    }

    /** The drafted target: its mode's key; an air cooler running with no controller holds a fixed fan speed. */
    static String target(Input in) {
        return in.mode() == HeatUnit.Mode.OFF ? "fan_speed" : in.mode().name().toLowerCase(Locale.ROOT);
    }

    /** What the control moves, named from the server's view (fans or louvers, burners), else the bypass. */
    static String actuator(Input in) {
        String a = in.heat().actuator();
        return a.equals("fans") || a.equals("louvers") || a.equals("burners") ? a : "bypass";
    }

    /**
     * Set point (On): the drafted target's field (an air cooler's fixed fan speed in % of full), and the low limit. By
     * hand (Off): an exchanger side's bypass, and the low limit. A heater's burners out and an air cooler's fans still set
     * nothing. An air cooler has no low limit (owner, 2026-10-28: "you should never cool below target"); a limit an older
     * world stored stays as it was.
     */
    static List<Column.Row> settings(Input in, Texts t) {
        var list = new ArrayList<Column.Row>();
        String unit = in.temperatureUnit();
        if (in.burners() && in.on()) list.add(new Column.Field("temperature", t.get("heat_field.outlet"), unit, null));
        if (in.controllable() && !(in.fans() && !in.on())) {
            switch (in.mode()) {
                case OUTLET_TEMPERATURE -> list.add(new Column.Field("heatTemperature", t.get("heat_field.outlet"), unit, null));
                case DUTY -> list.add(new Column.Field("heatDuty", t.get("heat_field.duty"), "kW", null));
                case RATIO -> list.add(new Column.Field("heatRatio", t.get("heat_field.ratio"), "", null));
                default -> list.add(in.fans() ? new Column.Field("fans", t.get("heat_field.fans"), "%", null) : new Column.Field("bypass", t.get("heat_field.bypass"), "%", null));
            }
            if (!in.fans()) list.add(new Column.Field("lowLimit", t.get("heat_field.low_limit"), unit, t.get("heat_field.low_limit.hint")));
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

    /** The note on this side's lines (W8) while they are not complete, or null: complete, the plate names the real valve. */
    static @Nullable String linesNote(Input in, Texts t) {
        var lines = in.heat().lines();
        if (lines == null || lines.status().equals("NONE") || lines.status().equals("COMPLETE") || in.applied() == null) return null;
        String kind = in.applied().mode() == HeatUnit.Mode.RATIO ? "ratio" : "bypass";
        return t.get("lines_note." + kind + "." + lines.status().toLowerCase(Locale.ROOT), lines.missing());
    }
}
