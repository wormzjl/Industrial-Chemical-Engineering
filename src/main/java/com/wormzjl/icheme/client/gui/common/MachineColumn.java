package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Format;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.runtime.fluid.MachineView;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * A machine's own page (batch 2026-11-03-rotating-machinery, W7, owner decision D31: option A): the door column of a motor,
 * a steam turbine's drive, a generator, a gearbox or a creative motor, on the device page's frame. Rows with widgets come
 * first (Off and On, the drive, what it regulates and its set point, a limit or a ratio), then what the plant reads (the
 * governor, the shaft, the supply). Minecraft-free, so the gates and the render tool place the rows the screen draws.
 */
public final class MachineColumn {
    /** The keys' prefix under {@code gui.icheme.}. */
    public static final String KEY = "machine.";

    private MachineColumn() {
    }

    /** The page's kinds: the shaft parts that have a page of their own. */
    public enum Kind {
        MOTOR, STEAM_TURBINE, GENERATOR, GEARBOX, CREATIVE_MOTOR;

        /** The page of shaft part {@code kind}, or null for a shaft block or a casing (the casing has its fluid page). */
        public static @Nullable Kind of(ShaftNetwork.Kind kind) {
            return switch (kind) {
                case MOTOR -> MOTOR;
                case STEAM_TURBINE -> STEAM_TURBINE;
                case GENERATOR -> GENERATOR;
                case GEARBOX -> GEARBOX;
                case CREATIVE_MOTOR -> CREATIVE_MOTOR;
                default -> null;
            };
        }

        /** Whether it has a switch (a gearbox does not run on its own, reading A99). */
        public boolean switched() {
            return this != GEARBOX;
        }

        /** Whether it drives its train, so it sets a speed or regulates. */
        public boolean driver() {
            return this == MOTOR || this == STEAM_TURBINE || this == CREATIVE_MOTOR;
        }

        /** The speed entry's unit: a motor's share in %, a turbine's and a creative motor's rpm (reading A90). */
        public String speedUnit() {
            return this == MOTOR ? "%" : "rpm";
        }
    }

    /** What a variable-speed driver holds: a set speed, its first casing's flow or its discharge pressure. */
    public enum Mode {
        SPEED, FLOW, PRESSURE;

        public static Mode of(ShaftTrain.Mode mode) {
            return switch (mode) {
                case SPEED -> SPEED;
                case REGULATE_FLOW -> FLOW;
                case REGULATE_PRESSURE -> PRESSURE;
            };
        }

        public ShaftTrain.Mode engine() {
            return switch (this) {
                case SPEED -> ShaftTrain.Mode.SPEED;
                case FLOW -> ShaftTrain.Mode.REGULATE_FLOW;
                case PRESSURE -> ShaftTrain.Mode.REGULATE_PRESSURE;
            };
        }
    }

    /** A meter: its key under {@code machine.}, its value as shown, its unit, its hover (or null). */
    public record Meter(String key, String value, String unit, @Nullable String hint) {
    }

    /**
     * What the page shows: its kind; whether this client edits; the switch's position (drafted); a motor's drive (variable or
     * fixed, the design's); what a variable driver regulates (drafted); whether it stands tripped (Reset shows, reading A94);
     * the governor line of a turbine (or null); the shaft's and the supply's meters; how long the store lasts (or null).
     */
    public record Input(Kind kind, boolean canEdit, boolean running, boolean variable, Mode mode, boolean tripped, @Nullable String governor,
                        List<Meter> shaft, List<Meter> supply, @Nullable String lasts) {
        public Input {
            shaft = List.copyOf(shaft);
            supply = List.copyOf(supply);
        }

        /** Whether the page sets a speed or regulates: a variable motor, a turbine, a creative motor. */
        public boolean regulates() {
            return kind == Kind.MOTOR ? variable : kind.driver();
        }
    }

    public static List<Column.Row> rows(Input in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        if (in.kind().switched()) {
            var control = new ArrayList<Column.Row>();
            // A turbine's switch is its trip and run (plan section 3); the others' Off and On.
            String off = in.kind() == Kind.STEAM_TURBINE ? "trip" : "off", on = in.kind() == Kind.STEAM_TURBINE ? "run" : "on";
            control.add(new Column.Buttons(List.of(position("power:off", t.get(KEY + off), t.get(KEY + off + ".hint"), !in.running(), in.canEdit()),
                    position("power:on", t.get(KEY + on), t.get(KEY + on + ".hint"), in.running(), in.canEdit()))));
            if (in.tripped() && in.canEdit())
                control.add(new Column.Buttons(List.of(new Column.Button("reset", t.get(KEY + "reset"), true, t.get(KEY + "reset.hint")))));
            rows.add(new Column.Group(t.get(KEY + "control"), null, control));
        }
        if (in.canEdit()) rows.addAll(settings(in, t));
        // From here the texts follow the plant: these groups hold no widget.
        if (in.governor() != null)
            rows.add(new Column.Group(t.get(KEY + "governor"), t.get(KEY + "governor.hint"), List.of(new Column.Line(in.governor(), Palette.TEXT))));
        if (!in.shaft().isEmpty()) rows.add(new Column.Group(t.get(KEY + "shaft"), null, meters(in.shaft(), t)));
        var supply = meters(in.supply(), t);
        if (in.lasts() != null) supply.add(new Column.Line(t.get(KEY + "store_lasts", in.lasts()), Palette.TEXT));
        if (!supply.isEmpty()) rows.add(new Column.Group(t.get(KEY + "supply"), null, supply));
        return rows;
    }

    /** What an editor sets: a motor's drive and what it regulates, a turbine's or a creative motor's speed, a limit, a ratio. */
    static List<Column.Row> settings(Input in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        if (in.kind() == Kind.MOTOR)
            // The drive is the motor's design (the constructor's duty): shown where it stands, as a reader sees a selector.
            rows.add(new Column.Group(t.get(KEY + "drive"), t.get(KEY + "drive.hint"), List.of(new Column.Buttons(List.of(
                    new Column.Button("drive:fixed", t.get(KEY + "drive.fixed"), false, t.get(KEY + "drive.design"), !in.variable()),
                    new Column.Button("drive:variable", t.get(KEY + "drive.variable"), false, t.get(KEY + "drive.design"), in.variable()))))));
        if (in.regulates()) {
            var regulate = new ArrayList<Column.Row>();
            var keys = new ArrayList<Column.Button>();
            for (var m : Mode.values())
                keys.add(position("regulate:" + m.name().toLowerCase(Locale.ROOT), t.get(KEY + "regulate." + m.name().toLowerCase(Locale.ROOT)),
                        t.get(KEY + "regulate." + m.name().toLowerCase(Locale.ROOT) + ".hint"), in.mode() == m, true));
            regulate.add(new Column.Buttons(keys));
            // Flow and pressure targets are the casing's own set points: their keys' hovers say so (panel rule 1).
            if (in.mode() == Mode.SPEED) regulate.add(new Column.Field("speed", t.get(KEY + "speed"), in.kind().speedUnit(), t.get(KEY + "speed." + in.kind().speedUnit().replace("%", "share") + ".hint")));
            rows.add(new Column.Group(t.get(KEY + "regulate"), t.get(KEY + "regulate.hint"), regulate));
        }
        if (in.kind() == Kind.CREATIVE_MOTOR)
            rows.add(new Column.Group(t.get(KEY + "limit"), null, List.of(new Column.Field("powerLimit", t.get(KEY + "power_limit"), "kW", t.get(KEY + "power_limit.hint")))));
        if (in.kind() == Kind.GENERATOR)
            rows.add(new Column.Group(t.get(KEY + "limit"), null, List.of(new Column.Field("powerLimit", t.get(KEY + "output_limit"), "kW", t.get(KEY + "output_limit.hint")))));
        if (in.kind() == Kind.GEARBOX)
            rows.add(new Column.Group(t.get(KEY + "ratio"), null, List.of(new Column.Field("ratio", t.get(KEY + "ratio"), "", t.get(KEY + "ratio.hint")))));
        return rows;
    }

    private static List<Column.Row> meters(List<Meter> meters, HeatControlColumn.Texts t) {
        var out = new ArrayList<Column.Row>();
        for (var m : meters) out.add(new Column.Reading("meter:" + m.key(), t.get(KEY + m.key()), m.value(), m.unit(), m.hint()));
        return out;
    }

    /** A position of a selector: latched where it stands, a click acts on another while this client edits. */
    private static Column.Button position(String key, String text, String hint, boolean current, boolean canEdit) {
        return new Column.Button(key, text, canEdit && !current, hint, current);
    }

    // ---------------- the published view as the page shows it ----------------

    /**
     * The page of {@code v} for an editor ({@code canEdit}) or a reader, with the drafted switch and mode: the meters in the
     * units the plan names (rpm, kW, FE/t, %), the store's time at the current draw (reading A92).
     */
    public static Input input(MachineView v, boolean canEdit, boolean running, Mode mode, HeatControlColumn.Texts t) {
        var kind = java.util.Objects.requireNonNull(Kind.of(v.kind()), "A machine page for a shaft part with none");
        var shaft = new ArrayList<Meter>();
        var supply = new ArrayList<Meter>();
        shaft.add(new Meter("speed", whole(v.speed()), "rpm", null));
        if (kind == Kind.GEARBOX) shaft.add(new Meter("ratio_now", v.ratio() > 0 ? Format.number(v.ratio()) : "—", "", t.get(KEY + "ratio_now.hint")));
        shaft.add(new Meter("power", Format.number(v.shaftPower() / 1000), "kW", null));
        if (kind == Kind.MOTOR || kind == Kind.GENERATOR) {
            supply.add(new Meter(kind == Kind.MOTOR ? "electric" : "output", Format.number(v.electricPower() / 1000), "kW", null));
            supply.add(new Meter(kind == Kind.MOTOR ? "draw" : "give", whole(v.energyPerTick()), "FE/t", t.get(KEY + "fe.hint")));
            if (Double.isFinite(v.storeShare())) supply.add(new Meter("store", whole(100 * v.storeShare()), "%", t.get(KEY + "store.hint")));
        }
        String lasts = kind == Kind.MOTOR && Double.isFinite(v.storeSeconds()) ? duration(v.storeSeconds(), t) : null;
        // A turbine's supply is its steam: flow, inlet and exhaust pressure (plan section 3).
        if (kind == Kind.STEAM_TURBINE) {
            supply.add(new Meter("steam_flow", Format.number(v.steamFlow()), "kg/s", null));
            supply.add(new Meter("inlet", Format.number(v.inletPressure() / 1e5), "bar", null));
            supply.add(new Meter("exhaust", Format.number(v.exhaustPressure() / 1e5), "bar", null));
        }
        // UNGOVERNED is a status word, said on the display (MachineView.of); the group names a bound valve only.
        String governor = kind == Kind.STEAM_TURBINE && !v.governor().isEmpty() ? t.get(KEY + "governed", v.governor()) : null;
        return new Input(kind, canEdit, running, v.variable(), mode, v.trip() != ShaftTrain.Trip.NONE && v.trip().latched(), governor, shaft, supply, lasts);
    }

    /** {@code seconds} as a player reads a time: {@code 6 min 10 s}, {@code 2 h 5 min}, {@code 40 s}. */
    public static String duration(double seconds, HeatControlColumn.Texts t) {
        long s = Math.round(Math.max(0, seconds));
        if (s >= 3600) return t.get(KEY + "hours", s / 3600, s % 3600 / 60);
        if (s >= 60) return t.get(KEY + "minutes", s / 60, s % 60);
        return t.get(KEY + "seconds", s);
    }

    private static String whole(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.0f", value) : "—";
    }

    /** The speed entry's value for {@code v}: a motor's share in %, a turbine's set speed in rpm, a creative motor's rpm. */
    public static double speedEntry(MachineView v) {
        double set = v.settings().setPoint();
        return switch (Kind.of(v.kind())) {
            case MOTOR -> 100 * (set > 0 ? set : 1);
            case STEAM_TURBINE -> (set > 0 ? set : 1) * v.designSpeed();
            case CREATIVE_MOTOR -> set > 0 ? set : ShaftTrain.CREATIVE_SPEED;
            case null, default -> 0;
        };
    }

    /** The engine's set point for the speed entry {@code entry} of {@code kind} (the inverse of {@link #speedEntry}). */
    public static double setPoint(Kind kind, double entry, double designSpeed) {
        return switch (kind) {
            case MOTOR -> entry / 100;
            case STEAM_TURBINE -> designSpeed > 0 ? entry / designSpeed : 0;
            default -> entry;
        };
    }

    // ---------------- the gates' and the render tool's cases ----------------

    /** A case: its name and its input. */
    public record Case(String name, Input in) {
    }

    /** Every kind as an editor and a reader, each mode, running and stopped, tripped; illustrative readings. */
    public static List<Case> cases(HeatControlColumn.Texts t) {
        var out = new ArrayList<Case>();
        for (var kind : Kind.values())
            for (boolean edit : new boolean[]{true, false})
                for (boolean variable : kind == Kind.MOTOR ? new boolean[]{true, false} : new boolean[]{true})
                    for (var mode : kind.driver() ? List.of(Mode.values()) : List.of(Mode.SPEED))
                        for (boolean tripped : kind == Kind.MOTOR || kind == Kind.STEAM_TURBINE ? new boolean[]{false, true} : new boolean[]{false})
                            out.add(new Case(kind + (variable ? "" : " fixed") + " " + mode + (tripped ? " tripped" : "") + (edit ? "" : " reader"),
                                    sample(kind, edit, variable, mode, tripped, t)));
        return out;
    }

    /**
     * Option A's sheet: a 30 kW 2-pole IE3 motor on a variable drive at 2950 rpm, shaft 24.9 kW, electric 26.7 kW, store
     * 72 %; a 2500 kW turbine at 4200 rpm giving 1980 kW from 2.9 kg/s of steam, 40 bar in and 4 bar out.
     */
    public static Input sample(Kind kind, boolean edit, boolean variable, Mode mode, boolean tripped, HeatControlColumn.Texts t) {
        boolean turbine = kind == Kind.STEAM_TURBINE;
        var shaft = new ArrayList<Meter>(List.of(new Meter("speed", tripped ? "0" : turbine ? "4200" : "2950", "rpm", null)));
        if (kind == Kind.GEARBOX) shaft.add(new Meter("ratio_now", "4.27", "", t.get(KEY + "ratio_now.hint")));
        shaft.add(new Meter("power", tripped ? "0.0" : turbine ? "1980" : "24.9", "kW", null));
        var supply = new ArrayList<Meter>();
        if (turbine) supply.addAll(List.of(new Meter("steam_flow", tripped ? "0.0" : "2.9", "kg/s", null), new Meter("inlet", "40.0", "bar", null),
                new Meter("exhaust", "4.0", "bar", null)));
        if (kind == Kind.MOTOR) supply.addAll(List.of(new Meter("electric", "26.7", "kW", null), new Meter("draw", "534", "FE/t", t.get(KEY + "fe.hint")),
                new Meter("store", "72", "%", t.get(KEY + "store.hint"))));
        if (kind == Kind.GENERATOR) supply.addAll(List.of(new Meter("output", "23.4", "kW", null), new Meter("give", "468", "FE/t", t.get(KEY + "fe.hint")),
                new Meter("store", "40", "%", t.get(KEY + "store.hint"))));
        return new Input(kind, edit, !tripped, variable, mode, tripped, kind == Kind.STEAM_TURBINE ? t.get(KEY + "governed", "FV-101") : null, shaft, supply,
                kind == Kind.MOTOR ? duration(370, t) : null);
    }
}
