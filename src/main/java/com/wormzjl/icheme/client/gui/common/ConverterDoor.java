package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A fluid converter's groups on its process vessel's door column (batch 2026-11-12-fluid-conversion, W5, plan 3.6), the
 * rows the device column leads with ({@code DeviceColumn.Input.lead}): Mode (Off, Balance, To IChemE, To Forge, a
 * selector), Rate (the most it converts, its rated flow, the deadband in Balance), Alarm levels (the four thresholds), then
 * the texts that follow the plant: Tanks (the Forge tank, then the process tank, each a bar and its fill and amount), Rule
 * (the rule it last read, each range beside the value it reads, one outside its range in the warning colour) and
 * Converting (the booked rate and the direction). The status word and the alarms stand on the message display. No Minecraft
 * classes.
 *
 * <p>Widget keys: {@code converter_mode:off}, {@code converter_mode:balance}, {@code converter_mode:to_icheme},
 * {@code converter_mode:to_forge}; the fields' draft keys {@code converterRate}, {@code converterDeadband} (%),
 * {@code converterIchemeHigh}, {@code converterIchemeLow}, {@code converterForgeHigh}, {@code converterForgeLow} (%,
 * blank for none).
 */
public final class ConverterDoor {
    private ConverterDoor() {
    }

    /** The modes, in the selector's order, each by its settings name. */
    public enum Mode {
        OFF, BALANCE, TO_ICHEME, TO_FORGE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The four thresholds' draft keys, in {@code ConverterUnit.ICHEME_HIGH} order. */
    public static final List<String> THRESHOLDS = List.of("converterIchemeHigh", "converterIchemeLow", "converterForgeHigh", "converterForgeLow");
    /** The thresholds' label keys, in the same order. */
    private static final List<String> LABELS = List.of("icheme_high", "icheme_low", "forge_high", "forge_low");

    /** A tank as shown: its fill (0 to 1, NaN while unknown), its fill in % and what it holds, as shown. */
    public record Tank(double share, String fill, String amount) {
    }

    /** A rule's row as shown: its name, the value it reads, its bounds' words, its unit, and whether the value lies outside. */
    public record Range(String name, String value, String bounds, String unit, boolean outside) {
    }

    /**
     * What the groups read: whether this client may edit; the drafted mode; the rate's unit (L per the player's time basis),
     * the rated flow as shown; for a reader the settings as shown (the rate, the deadband in %, the thresholds in %, "—" for
     * none); the Forge tank, its fluid's name (null for none), the process tank; the rule's id (null for none), whether it
     * is a reverse rule, its rows; the booked rate as shown and the direction's key ({@code none}, {@code to_icheme},
     * {@code to_forge}).
     */
    public record Input(boolean canEdit, Mode mode, String rateUnit, String rated, String rate, String deadband, List<String> thresholds,
                        Tank forge, String fluid, Tank process, String rule, boolean reverse, List<Range> ranges, String booked, String direction) {
        public Input {
            thresholds = List.copyOf(thresholds);
            ranges = List.copyOf(ranges);
        }
    }

    public static final String KEY = "converter_";

    public static List<Column.Row> rows(Input in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        var mode = new ArrayList<Column.Row>();
        mode.add(new Column.Buttons(List.of(position(Mode.OFF, in, t), position(Mode.BALANCE, in, t))));
        mode.add(new Column.Buttons(List.of(position(Mode.TO_ICHEME, in, t), position(Mode.TO_FORGE, in, t))));
        rows.add(new Column.Group(t.get(KEY + "group.mode"), t.get(KEY + "group.mode.hint"), mode));
        var rate = new ArrayList<Column.Row>();
        rate.add(in.canEdit() ? new Column.Field("converterRate", t.get(KEY + "field.rate"), in.rateUnit(), t.get(KEY + "field.rate.hint", in.rated(), in.rateUnit()))
                : new Column.Reading("converter:rate", t.get(KEY + "field.rate"), in.rate(), in.rateUnit(), t.get(KEY + "field.rate.hint", in.rated(), in.rateUnit())));
        rate.add(new Column.Reading("converter:rated", t.get(KEY + "rated"), in.rated(), in.rateUnit(), t.get(KEY + "rated.hint")));
        if (in.mode() == Mode.BALANCE)
            rate.add(in.canEdit() ? new Column.Field("converterDeadband", t.get(KEY + "field.deadband"), "%", t.get(KEY + "field.deadband.hint"))
                    : new Column.Reading("converter:deadband", t.get(KEY + "field.deadband"), in.deadband(), "%", t.get(KEY + "field.deadband.hint")));
        rows.add(new Column.Group(t.get(KEY + "group.rate"), t.get(KEY + "group.rate.hint"), rate));
        var levels = new ArrayList<Column.Row>();
        for (int i = 0; i < THRESHOLDS.size(); i++) {
            String key = THRESHOLDS.get(i), label = t.get(KEY + "field." + LABELS.get(i)), hint = t.get(KEY + "field.threshold.hint");
            levels.add(in.canEdit() ? new Column.Field(key, label, "%", hint) : new Column.Reading("converter:" + key, label, in.thresholds().get(i), "%", hint));
        }
        rows.add(new Column.Group(t.get(KEY + "group.levels"), t.get(KEY + "group.levels.hint"), levels));
        // From here the texts follow the plant: these groups hold no widget.
        var tanks = new ArrayList<Column.Row>();
        tanks.add(new Column.Line(t.get(KEY + "tank.forge"), Palette.MUTED));
        tanks.add(bar(in.forge()));
        tanks.add(new Column.Line(t.get(KEY + "tank.line", in.forge().fill(), in.forge().amount()), Palette.TEXT));
        tanks.add(in.fluid() == null ? new Column.Line(t.get(KEY + "tank.empty"), Palette.MUTED) : new Column.Paragraph(in.fluid(), Palette.TEXT, null));
        tanks.add(new Column.Line(t.get(KEY + "tank.process"), Palette.MUTED));
        tanks.add(bar(in.process()));
        tanks.add(new Column.Line(t.get(KEY + "tank.line", in.process().fill(), in.process().amount()), Palette.TEXT));
        rows.add(new Column.Group(t.get(KEY + "group.tanks"), t.get(KEY + "group.tanks.hint"), tanks));
        var rule = new ArrayList<Column.Row>();
        if (in.rule() == null) rule.add(new Column.Line(t.get(KEY + "rule.none"), Palette.MUTED));
        else {
            rule.add(new Column.Paragraph(in.rule(), Palette.TEXT, t.get(KEY + (in.reverse() ? "rule.reverse.hint" : "rule.forward.hint"))));
            for (var r : in.ranges())
                rule.add(new Column.Paragraph(t.get(KEY + "range", r.name(), r.value(), r.unit(), r.bounds()), r.outside() ? Palette.WARN : Palette.TEXT,
                        r.outside() ? t.get(KEY + "range.outside.hint") : null));
        }
        rows.add(new Column.Group(t.get(KEY + "group.rule"), t.get(KEY + "group.rule.hint"), rule));
        rows.add(new Column.Group(t.get(KEY + "group.converting"), null, List.of(
                new Column.Reading("converter:booked", t.get(KEY + "booked"), in.booked(), in.rateUnit(), t.get(KEY + "booked.hint")),
                new Column.Line(t.get(KEY + "direction." + in.direction()), Palette.TEXT))));
        return rows;
    }

    private static Column.Bar bar(Tank tank) {
        return new Column.Bar(Double.isFinite(tank.share()) ? Math.clamp(tank.share(), 0, 1) : 0);
    }

    /** A position of the mode selector: latched where it stands, a click moves it there while this client edits. */
    private static Column.Button position(Mode mode, Input in, HeatControlColumn.Texts t) {
        boolean current = in.mode() == mode;
        return new Column.Button("converter_mode:" + mode.key(), t.get(KEY + "mode." + mode.key()), in.canEdit() && !current, t.get(KEY + "mode." + mode.key() + ".hint"), current);
    }
}
