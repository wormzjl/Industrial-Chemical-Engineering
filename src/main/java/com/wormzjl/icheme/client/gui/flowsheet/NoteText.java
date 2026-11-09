package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Note;
import java.util.ArrayList;
import java.util.List;

/**
 * A run's note as text (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 6). The science names a key under
 * {@code gui.icheme.flowsheet.} with its arguments raw and in SI, and this one place formats them in the player's units, and turns a
 * subject's id into the name on the sheet.
 */
public final class NoteText {
    public static final String KEY = "flowsheet.";

    private NoteText() {
    }

    /** The note's sentence: {@code T-101: ...}, or the plain line for the sheet. */
    public static String text(Note note, HeatControlColumn.Texts t, Flowsheet sheet, SheetUnits units) {
        String line = t.get(KEY + note.key(), arguments(note, sheet, units));
        String subject = subject(note.subject(), sheet);
        return subject.isEmpty() ? line : subject + ": " + line;
    }

    private static Object[] arguments(Note note, Flowsheet sheet, SheetUnits units) {
        var raw = note.arguments();
        var out = new ArrayList<Object>(raw);
        switch (note.key()) {
            case "mixer.pressures", "ejector.motive_pressure" -> {
                out.set(0, pressure(raw.get(0), units));
                out.set(1, pressure(raw.get(1), units));
            }
            case "flare.flame_out" -> {
                out.set(0, temperature(raw.get(0), units));
                out.set(1, temperature(raw.get(1), units));
            }
            case "still.hot_charge" -> {
                out.set(0, temperature(raw.get(0), units));
                out.set(1, temperature(raw.get(1), units));
            }
            case "still.overheat" -> out.set(0, temperature(raw.get(0), units));
            case "flare.over_capacity" -> {
                out.set(0, flow(raw.get(0)));
                out.set(1, flow(raw.get(1)));
            }
            case "heater.limit" -> {
                out.set(0, watts(raw.get(0)));
                out.set(1, watts(raw.get(1)));
            }
            case "ejector.motive_short" -> {
                out.set(0, hourly(raw.get(0)));
                out.set(1, hourly(raw.get(1)));
            }
            case "run.mass_gap" -> {
                out.set(0, flow(raw.get(0)));
                out.set(1, flow(raw.get(1)));
            }
            case "column.steam_pressure" -> {
                out.set(1, pressure(raw.get(1), units));
                out.set(2, pressure(raw.get(2), units));
            }
            case "column.steam_cool" -> {
                out.set(1, temperature(raw.get(1), units));
                out.set(2, temperature(raw.get(2), units));
            }
            case "column.steam_rate" -> {
                out.set(1, flow(raw.get(1)));
                out.set(2, flow(raw.get(2)));
            }
            case "flow.set_by" -> out.set(0, subject(raw.get(0), sheet));
            default -> {
            }
        }
        return out.toArray();
    }

    private static String pressure(String pascals, SheetUnits units) {
        return units.p(Double.parseDouble(pascals)) + " " + units.pressure().symbol();
    }

    private static String temperature(String kelvin, SheetUnits units) {
        return units.t(Double.parseDouble(kelvin)) + " " + units.temperature().symbol();
    }

    private static String watts(String watts) {
        return SheetUnits.megawatts(Double.parseDouble(watts)) + " MW";
    }

    private static String hourly(String kilograms) {
        return SheetUnits.number(0, 3600 * Double.parseDouble(kilograms)) + " kg/h";
    }

    private static String flow(String kilograms) {
        return SheetUnits.number(2, Double.parseDouble(kilograms)) + " kg/s";
    }

    /** The name a subject goes by: a unit's or a stream's, with a part kept; empty for the sheet. */
    public static String subject(String subject, Flowsheet sheet) {
        if (subject.equals("sheet")) return "";
        String id = subject.startsWith("feed ") ? subject.substring(5) : subject;
        int slash = id.indexOf('/');
        String head = slash < 0 ? id : id.substring(0, slash), tail = slash < 0 ? "" : id.substring(slash);
        for (var unit : sheet.units()) if (unit.id().equals(head)) return unit.name() + tail;
        for (var stream : sheet.streams()) if (stream.id().equals(head)) return stream.name();
        return subject;
    }

    /** Every note a run left, its units' too, worst first within each kind's order of arrival. */
    public static List<Note> all(FlowsheetRun run) {
        var notes = new ArrayList<>(run.log());
        for (var report : run.reports().values()) for (var note : report.notes()) if (!notes.contains(note)) notes.add(note);
        notes.sort((a, b) -> b.severity().compareTo(a.severity()));
        return notes;
    }
}
