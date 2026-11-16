package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;

import java.util.*;
import java.util.function.BiFunction;

/**
 * The alarm beacon's door (batch 2026-11-15-fluid-interfaces, W9, plan section 5.3), with no Minecraft class: its
 * Settings (name, Off and On), its Source (an input or any alarm), its Condition (above or below a threshold, in the
 * player's display unit; hidden for an alarm channel and for any alarm), its Input (the chosen input and its reading)
 * and its Status. The input's choices are a page of their own, as a controller's ({@link ControlColumns#choices}).
 * Texts are keys under {@code gui.icheme.}.
 *
 * <p>Keys: the entries {@code name} and {@code threshold}; the buttons {@code off}, {@code on}, {@code source:input},
 * {@code source:any}, {@code above}, {@code below}, {@code input} (the choices page), and on that page
 * {@code sensor:<id>}, {@code inputs:prev}, {@code inputs:next} and {@code back}.</p>
 */
public final class AlarmColumns {
    private AlarmColumns() {}

    /**
     * What the door shows: the drafts (enabled, any alarm, above), the chosen input's label (empty: none) and whether it
     * is an alarm channel, the threshold's unit, the status word, the reading and its unit (empty: no signal), whether
     * the socket is on a cable, and the server's last answer (empty: none).
     */
    public record View(
            boolean enabled,
            boolean anyAlarm,
            String input,
            boolean alarmChannel,
            boolean above,
            String unit,
            String status,
            String reading,
            String readingUnit,
            boolean onCable,
            String reply) {}

    private static Column.Button key(String key, String text, boolean latched) {
        return new Column.Button(key, text, true, null, latched, false);
    }

    /** The door's rows: the widget rows first, the texts that follow the plant last. */
    public static List<Column.Row> rows(View v, BiFunction<String, Object[], String> text) {
        java.util.function.Function<String, String> t = k -> text.apply(k, new Object[0]);
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(t.apply("alarm_beacon.settings"), null, List.of(
                new Column.Line(t.apply("alarm_beacon.name"), Palette.TEXT),
                new Column.Wide("name", t.apply("alarm_beacon.name")),
                new Column.Buttons(List.of(key("off", t.apply("control.off"), !v.enabled()), key("on", t.apply("control.on"), v.enabled()))))));
        rows.add(new Column.Group(t.apply("alarm_beacon.source"), v.anyAlarm() ? t.apply("alarm_beacon.any_alarm") : null, List.of(
                new Column.Buttons(List.of(
                        key("source:input", t.apply("alarm_beacon.source.input"), !v.anyAlarm()),
                        key("source:any", t.apply("alarm_beacon.source.any_alarm"), v.anyAlarm()))))));
        if (!v.anyAlarm()) {
            if (!v.alarmChannel())
                rows.add(new Column.Group(t.apply("alarm_beacon.condition"), t.apply("alarm_beacon.band"), List.of(
                        new Column.Buttons(List.of(
                                key("above", t.apply("alarm_beacon.condition.above"), v.above()),
                                key("below", t.apply("alarm_beacon.condition.below"), !v.above()))),
                        new Column.Field("threshold", t.apply("alarm_beacon.threshold"), v.unit(), t.apply("alarm_beacon.band")))));
            var input = new ArrayList<Column.Row>();
            input.add(new Column.Buttons(List.of(key("input", t.apply("alarm_beacon.choose_input"), false))));
            input.add(new Column.Paragraph(v.input().isEmpty() ? t.apply("alarm_beacon.no_inputs") : v.input(), Palette.TEXT));
            rows.add(new Column.Group(t.apply("alarm_beacon.input"), v.alarmChannel() ? t.apply("alarm_beacon.alarm_channel") : null, input));
        }
        var status = new ArrayList<Column.Row>();
        status.add(new Column.Paragraph(t.apply("alarm_beacon.status." + v.status().toLowerCase(Locale.ROOT)), v.status().equals("ACTIVE") ? Palette.RED : Palette.TEXT,
                v.onCable() ? null : t.apply("alarm_beacon.not_on_cable")));
        if (!v.reading().isEmpty())
            status.add(new Column.Reading("reading", t.apply("alarm_beacon.reading"), v.reading(), v.readingUnit(), null));
        if (!v.reply().isEmpty()) status.add(new Column.Line(v.reply(), Palette.TEXT));
        rows.add(new Column.Group(t.apply("alarm_beacon.status"), null, status));
        return List.copyOf(rows);
    }

    /** The choices page: a page of the inputs on the beacon's cable, the chosen one marked, its page keys and Back. */
    public static List<Column.Row> choices(List<ControlColumns.Choice> choices, int page, int pages, BiFunction<String, Object[], String> text) {
        var rows = new ArrayList<Column.Row>(ControlColumns.choices(text.apply("alarm_beacon.input", new Object[0]), choices, text));
        rows.addAll(ControlColumns.navigation(page, pages, "inputs", text));
        return List.copyOf(rows);
    }
}
