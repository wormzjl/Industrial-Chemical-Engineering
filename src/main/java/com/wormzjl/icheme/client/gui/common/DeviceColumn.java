package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Format;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A fluid device's door column on every page but the Control tab (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 1,
 * GUI_TOOLKIT_PLAN.md section 14): function groups of the GUI kit in place of the old rail. First come the groups that
 * hold widgets, whose places do not move with what the plant reads: the side selector, Control (Off and On), the set
 * points (Set point, a valve's with its target first, Limit, a pipe's Size), a filter's Solids, the Phases with their basis key. Then
 * the groups whose texts change: a tank's Level, a Valve's opening, the Readings (LED meters). The status is the door's
 * message display's alone.
 *
 * <p>Widget keys: {@code power:off} and {@code power:on} (the switch), {@code recover}, {@code phase_basis}, the side
 * selector's {@code side:0} and {@code side:1}, and the fields' draft keys. Choice actions: {@code valve:mass} and
 * {@code valve:volume}. No Minecraft classes.
 */
public final class DeviceColumn {
    private DeviceColumn() {
    }

    /** How a valve is set: none, not configured yet, to a pressure, or to a mass or a volume flow. */
    public enum Valve {
        NONE, UNCONFIGURED, PRESSURE, MASS_FLOW, VOLUME_FLOW
    }

    /** A reading: its label's key, its value as shown, its unit, its hover (or null). */
    public record Meter(String key, String value, String unit, @Nullable String hint) {
    }

    /** The phases' split: its basis (volume, mole or mass), the four phases' amounts, whether custom solids are left out, its hover. */
    public record Phases(String basis, double[] amounts, boolean customSolidsExcluded, String hint) {
    }

    /** A valve's opening (0 to 1) and its controller's words. */
    public record Opening(double share, String controller) {
    }

    /** A tank's level: the share of its capacity filled (NaN while unknown), and its contents and capacity as shown, in litres. */
    public record Level(double share, String contents, String capacity) {
    }

    /**
     * What the column reads: the device's kind; whether this client may edit; whether it has a switch (a generator, a
     * built mover) and where it stands; the player's pressure and temperature symbols and flow time (/s, /min, /h); how a
     * valve is set and how open it stands; whether a filter holds solids to recover; a tank's level; a junction's line;
     * the rows that lead (a heat side's side selector); the phases; the readings.
     */
    public record Input(Kind kind, boolean canEdit, boolean switchable, boolean powerOn, String pressureUnit, String temperatureUnit, String flowTime,
                        Valve valve, @Nullable Opening opening, boolean recoverable, @Nullable Level level, @Nullable String junction,
                        List<Column.Row> lead, @Nullable Phases phases, List<Meter> readings) {
        public Input {
            lead = List.copyOf(lead);
            readings = List.copyOf(readings);
        }
    }

    /** The phases' colours, as the old rail drew them: organic, aqueous, vapour, solids. */
    public static final int[] PHASE_COLOURS = {0xFFD6A65A, 0xFF549BD2, 0xFFB3C0CA, 0xFFA28565};

    public static List<Column.Row> rows(Input in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<>(in.lead());
        if (in.switchable()) rows.add(new Column.Group(t.get("heat_group.control"), null, List.of(new Column.Buttons(List.of(
                position("power:off", t.get("heat_switch.off"), t.get("device_switch.off.hint"), !in.powerOn(), in.canEdit()),
                position("power:on", t.get("heat_switch.on"), t.get("device_switch.on.hint"), in.powerOn(), in.canEdit()))))));
        if (in.canEdit()) rows.addAll(settings(in, t));
        if (in.canEdit() && in.kind() == Kind.FILTER)
            rows.add(new Column.Group(t.get("device_group.solids"), null, List.of(new Column.Buttons(List.of(new Column.Button("recover", t.get("recover"), in.recoverable(), t.get("recover.hint")))))));
        if (in.phases() != null) rows.add(phases(in.phases(), t));
        // From here the texts follow the plant: these groups hold no widget.
        if (in.level() != null) {
            var level = in.level();
            boolean known = Double.isFinite(level.share());
            double share = known ? Math.clamp(level.share(), 0, 1) : 0;
            rows.add(new Column.Group(t.get("device_group.level"), t.get("volume_capacity.hint"), List.of(new Column.Bar(share),
                    new Column.Line(t.get("level_line", known ? Format.number(100 * share) : "—", level.contents(), level.capacity()), Palette.TEXT))));
        }
        if (in.opening() != null) {
            var valve = new ArrayList<Column.Row>();
            valve.add(new Column.Bar(Math.clamp(in.opening().share(), 0, 1)));
            valve.add(new Column.Line(t.get("valve_open", Format.number(100 * in.opening().share())), Palette.TEXT));
            if (!in.opening().controller().isBlank()) valve.add(new Column.Paragraph(in.opening().controller(), Palette.MUTED));
            rows.add(new Column.Group(t.get("device_group.valve"), null, valve));
        }
        var readings = new ArrayList<Column.Row>();
        if (in.junction() != null) readings.add(new Column.Line(in.junction(), Palette.ACCENT));
        for (int i = 0; i < in.readings().size(); i++) {
            var m = in.readings().get(i);
            readings.add(new Column.Reading("meter:" + i, t.get(m.key()), m.value(), m.unit(), m.hint()));
        }
        if (!readings.isEmpty()) rows.add(new Column.Group(t.get("device_group.readings"), null, readings));
        return rows;
    }

    /** What the player sets on the device: its set points, a mover's limit, a valve's target, a pipe's bore. */
    static List<Column.Row> settings(Input in, HeatControlColumn.Texts t) {
        String p = in.pressureUnit(), volume = "m³" + in.flowTime(), mass = "kg" + in.flowTime();
        var setPoint = t.get("heat_group.set_point");
        return switch (in.kind()) {
            case GENERATOR -> List.of(group(setPoint, field("pressure", t, "pressure", p), field("temperature", t, "temperature", in.temperatureUnit())));
            case PUMP -> List.of(group(setPoint, field("volumeFlow", t, "suction_flow", volume)), group(t.get("device_group.limit"), field("maximumAddedPressure", t, "max_rise", p)));
            case COMPRESSOR -> List.of(group(setPoint, field("volumeFlow", t, "suction_flow", volume)), group(t.get("device_group.limit"), field("maximumPressureRatio", t, "max_ratio", "")));
            case VOID -> List.of(group(setPoint, field("pressure", t, "pressure", p)));
            case PIPE -> List.of(group(t.get("device_group.size"), field("diameter", t, "bore", "m")));
            case VALVE -> switch (in.valve()) {
                // What the set point holds (mass or volume flow) and its value in one group (owner 2026-10-28, option b:
                // one plate fewer, so the valve's page fits the device screen's design height).
                case MASS_FLOW, VOLUME_FLOW -> {
                    boolean byMass = in.valve() == Valve.MASS_FLOW;
                    yield List.of(group(setPoint,
                            new Column.Choice(t.get("valve_target.mass_flow"), t.get("valve_target.mass_flow.hint"), byMass, "valve:mass"),
                            new Column.Choice(t.get("valve_target.volume_flow"), t.get("valve_target.volume_flow.hint"), !byMass, "valve:volume"),
                            field(byMass ? "massFlow" : "volumeFlow", t, "flow", byMass ? mass : volume)));
                }
                case PRESSURE -> List.of(group(setPoint, field("pressure", t, "pressure", p)));
                default -> List.of();
            };
            default -> List.of();
        };
    }

    /**
     * The Phases group: the basis key (a display key: it changes the readings, not the plant), then the split with one
     * share a line, then a warning where custom solids are left out of a mole basis.
     */
    static Column.Row phases(Phases ph, HeatControlColumn.Texts t) {
        double total = 0;
        for (double a : ph.amounts()) total += Math.max(0, a);
        var shares = new ArrayList<Column.Share>();
        for (int i = 0; i < 4; i++) {
            String label = i == 3 && ph.basis().equals("mole") ? t.get("phase_ratio.crystals") : t.get("phase_short." + i);
            shares.add(new Column.Share(label, total > 0 ? Format.number(100 * ph.amounts()[i] / total) + " %" : "—", ph.amounts()[i], PHASE_COLOURS[i]));
        }
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Buttons(List.of(new Column.Button("phase_basis", t.get("phase_ratio." + ph.basis() + "_basis"), true, t.get("phase_ratio.basis_hint"), false, true))));
        rows.add(new Column.Split(shares, ph.hint()));
        if (ph.customSolidsExcluded()) rows.add(new Column.Line(t.get("phase_ratio.custom_solids_excluded"), Palette.WARN));
        return new Column.Group(t.get("device_group.phases"), ph.hint(), rows);
    }

    private static Column.Group group(String title, Column.Row... rows) {
        return new Column.Group(title, null, List.of(rows));
    }

    /** A set point's field: its label and hover under {@code device_field.}. */
    private static Column.Field field(String key, HeatControlColumn.Texts t, String label, String unit) {
        return new Column.Field(key, t.get("device_field." + label), unit, t.get("device_field." + label + ".hint"));
    }

    /** A position of a switch: latched where it stands, a click acts on the other while this client edits. */
    private static Column.Button position(String key, String text, String hint, boolean current, boolean canEdit) {
        return new Column.Button(key, text, canEdit && !current, hint, current);
    }
}
