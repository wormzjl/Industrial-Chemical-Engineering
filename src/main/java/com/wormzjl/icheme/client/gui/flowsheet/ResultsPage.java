package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Note;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * The Results tab (batch 2026-11-02-process-simulator, sheets 14 and 15): pick streams and read their properties; what each unit did and the sheet's balance;
 * the Log, each note in the player's units. Rows for the door column, and what the glass draws. Keys: {@code view/<i>}, {@code export}; a click on the list ticks.
 */
public final class ResultsPage {
    public enum View {
        STREAMS, UNITS, LOG
    }

    /** What the player picked. */
    public static final class State {
        public View view = View.STREAMS;
        public final Set<String> ticked = new LinkedHashSet<>();
        public int offset;
    }

    private static final int FIRST = 160, ROW = 12, LINE = 14;
    /** A CSV holds what the table does. */
    private static final int MOST_SHOWN = 6;

    private ResultsPage() {
    }

    public static List<Column.Row> rows(SheetContext x, State state) {
        var rows = new ArrayList<Column.Row>();
        rows.add(Door.group(x.text("results.view"), new Column.Buttons(List.of(Door.position("view/0", x.text("results.streams"), state.view == View.STREAMS), Door.position("view/1", x.text("results.units"), state.view == View.UNITS),
                Door.position("view/2", x.text("results.log"), state.view == View.LOG)))));
        rows.add(Door.group(x.text("results.sheet"), new Column.Buttons(List.of(Door.key("export", x.text("results.export"), x.state().results() != null, null)))));
        var results = x.state().results();
        if (state.view == View.UNITS && results != null) {
            var run = results.run();
            var u = x.units();
            rows.add(Door.group(x.text("results.mass"), Door.meter(x.text("results.in"), u.tonnes(run.massIn()), u.tonnesUnit()), Door.meter(x.text("results.out"), u.tonnes(run.massOut()), u.tonnesUnit()), Door.meter(x.text("results.gap"), SheetUnits.number(2, results.massGap()), "%")));
            var heat = heat(x);
            rows.add(Door.group(x.text("results.heat"), Door.meter(x.text("results.fired"), SheetUnits.megawatts(heat[0]), "MW"), Door.meter(x.text("results.recovered"), SheetUnits.megawatts(heat[1]), "MW"), Door.meter(x.text("results.cooled"), SheetUnits.megawatts(heat[2]), "MW"),
                    Door.meter("heat_gap", x.text("results.gap"), SheetUnits.number(2, results.heatGap()), "%")));
        }
        return rows;
    }

    /** Fired, recovered between streams, and cooled away, W. */
    private static double[] heat(SheetContext x) {
        double fired = 0, recovered = 0, cooled = 0;
        var results = x.state().results();
        for (var unit : x.editor().sheet().units()) {
            double duty = results.reading(unit.id(), "duty");
            if (Double.isNaN(duty)) continue;
            switch (unit.type()) {
                case "fired_heater" -> fired += duty;
                case "exchanger", "kettle" -> recovered += Math.abs(duty);
                case "air_cooler" -> cooled += Math.abs(duty);
                default -> {
                }
            }
            for (var part : parts(results, unit)) if (part.endsWith("/condenser")) cooled += Math.abs(results.reading(unit.id(), "condenser"));
        }
        for (var unit : x.editor().sheet().units())
            if (unit.type().equals("column")) for (var id : parts(results, unit)) if (id.contains("/pa ")) {
                var report = results.report(id);
                if (report != null) recovered += Math.abs(report.readings().getOrDefault("duty", 0.0));
            }
        return new double[]{fired, recovered, cooled};
    }

    private static List<String> parts(SheetResults results, Flowsheet.Unit unit) {
        var ids = new ArrayList<String>();
        for (var id : results.run().reports().keySet()) if (id.startsWith(unit.id() + "/")) ids.add(id);
        ids.sort(String::compareTo);
        return ids;
    }

    /** What the status says. */
    public static String status(SheetContext x, State state) {
        var results = x.state().results();
        if (results == null) return x.text("results.none");
        return switch (state.view) {
            case STREAMS -> x.text("results.showing", shown(x, state).size(), x.editor().sheet().streams().size(), results.passes());
            case UNITS -> {
                long warnings = NoteText.all(results.run()).stream().filter(n -> n.severity() == Note.Severity.WARNING).count();
                yield x.text("results.units_status", x.editor().sheet().units().size(), warnings);
            }
            case LOG -> x.text("results.log_status");
        };
    }

    /** The ticked streams the table holds, in the sheet's order, the player's first picks when too many are ticked. */
    public static List<Flowsheet.Stream> shown(SheetContext x, State state) {
        var picked = new ArrayList<Flowsheet.Stream>();
        for (var stream : order(x)) if (state.ticked.contains(stream.id()) && picked.size() < MOST_SHOWN) picked.add(stream);
        return picked;
    }

    /** Streams in list order: feeds, lines, products. */
    public static List<Flowsheet.Stream> order(SheetContext x) {
        var streams = new ArrayList<Flowsheet.Stream>();
        var all = x.editor().sheet().streams();
        for (var stream : all) if (stream.from() == null) streams.add(stream);
        for (var stream : all) if (stream.from() != null && stream.to() != null) streams.add(stream);
        for (var stream : all) if (stream.from() != null && stream.to() == null) streams.add(stream);
        return streams;
    }

    /** The list's well, under the door's rows. */
    public static Rect list(Column column, int bottom) {
        int top = column.bounds().bottom() + 4;
        return new Rect(5, top, SimulatorFrame.RESULT_DOOR - 2, bottom - top);
    }

    /** The stream ticked at a point of the list, or null. */
    public static @Nullable String tick(SheetContext x, Rect list, double px, double py) {
        if (!list.contains(px, py)) return null;
        int i = (int) Math.floor((py - list.y() - 13) / LINE);
        var streams = order(x);
        return i >= 0 && i < streams.size() ? streams.get(i).id() : null;
    }

    public static void toggle(State state, String id) {
        if (!state.ticked.remove(id)) state.ticked.add(id);
    }

    public static void drawList(Canvas c, Skin skin, SheetContext x, State state, Rect list) {
        c.text(x.text("results.streams"), list.x() + 4, list.y() + 3, Palette.MUTED);
        c.clip(list);
        int i = 0;
        for (var stream : order(x)) {
            int y = list.y() + 16 + i * LINE;
            boolean ticked = state.ticked.contains(stream.id());
            if (i % 2 == 1) c.fill(list.x(), y - 3, list.right(), y + 11, DialogColumns.BAND);
            skin.mark(c, list.x() + 5, y, ticked);
            c.text(stream.name(), list.x() + 18, y, ticked ? Palette.TEXT : Palette.MUTED);
            i++;
        }
        c.unclip();
    }

    /** The glass of the view shown. */
    public static void draw(Canvas c, Skin skin, SheetContext x, State state, Rect box) {
        var results = x.state().results();
        if (results == null) {
            c.text(x.text("results.none"), box.x() + 4, box.y() + 5, Palette.MUTED);
            return;
        }
        c.clip(box);
        switch (state.view) {
            case STREAMS -> streams(c, skin, x, state, box);
            case UNITS -> units(c, x, box);
            case LOG -> log(c, x, box);
        }
        c.unclip();
    }

    /** A name in its cell: a bracket that does not fit goes first. */
    private static String fitted(Measure m, String name, int w) {
        int bracket = name.lastIndexOf(" (");
        return m.cut(m.width(name) > w && bracket > 0 ? name.substring(0, bracket) : name, w);
    }

    private static int table(Canvas c, Rect box, List<String[]> rows, int x0, int y, int cell) {
        var m = c.measure();
        for (int r = 0; r < rows.size() && y + 10 < box.bottom(); r++, y += ROW) {
            if (r % 2 == 1) c.fill(box.x(), y - 2, box.right() - 10, y + 10, DialogColumns.BAND);
            var cells = rows.get(r);
            c.text(fitted(m, cells[0], FIRST - 4), x0, y, Palette.TEXT);
            for (int i = 1; i < cells.length; i++) c.text(cells[i], x0 + FIRST + i * cell - m.width(cells[i]), y, Palette.ACCENT);
        }
        return y;
    }

    private static String[] row(String label, List<@Nullable StreamProps> props, Function<StreamProps, String> value) {
        var cells = new String[props.size() + 1];
        cells[0] = label;
        for (int i = 0; i < props.size(); i++) cells[i + 1] = props.get(i) == null ? "" : value.apply(props.get(i));
        return cells;
    }

    private static String mPa(double value) {
        return Double.isNaN(value) ? "" : SheetUnits.number(value * 1e3 < .1 ? 3 : 2, value * 1e3);
    }

    private static void streams(Canvas c, Skin skin, SheetContext x, State state, Rect box) {
        var m = c.measure();
        var picked = shown(x, state);
        if (picked.isEmpty()) {
            c.text(x.text("results.pick"), box.x() + 4, box.y() + 5, Palette.MUTED);
            return;
        }
        int x0 = box.x() + 4, cell = (box.w() - FIRST - 10) / Math.max(picked.size(), MOST_SHOWN), y = box.y() + 4;
        for (int i = 0; i < picked.size(); i++) {
            String name = m.cut(picked.get(i).name(), cell - 4);
            c.text(name, x0 + FIRST + (i + 1) * cell - m.width(name), y, Palette.TEXT);
        }
        c.hLine(box.x() + 2, box.right() - 12, y + 10, Palette.LINE);
        y += LINE;
        c.text(x.text("results.properties"), x0, y, Palette.MUTED);
        y += ROW;
        var u = x.units();
        var props = new ArrayList<@Nullable StreamProps>();
        var solved = new ArrayList<@Nullable FlowStream>();
        for (var stream : picked) {
            props.add(x.props(stream));
            solved.add(x.solved(stream));
        }
        var rows = new ArrayList<String[]>();
        rows.add(row(x.text("label.temperature", u.temperature().symbol()), props, p -> u.t(p.temperature())));
        rows.add(row(x.text("label.pressure", u.pressure().symbol()), props, p -> u.p(p.pressure())));
        rows.add(row(x.text("label.vapour"), props, p -> SheetUnits.number(1, p.vapour())));
        rows.add(row(x.text("label.flow", u.molesUnit()), props, p -> u.moles(p.moles())));
        rows.add(row(x.text("label.flow", u.tonnesUnit()), props, p -> u.tonnes(p.mass())));
        rows.add(row(x.text("label.liquid_volume", u.time().unit("m³")), props, p -> SheetUnits.number(1, u.time().display(p.liquidVolume()))));
        rows.add(row(x.text("label.density"), props, p -> SheetUnits.number(1, p.density())));
        rows.add(row(x.text("label.liquid_viscosity"), props, p -> mPa(p.liquidMu())));
        rows.add(row(x.text("label.gas_viscosity"), props, p -> mPa(p.gasMu())));
        rows.add(row(x.text("label.mean_weight"), props, p -> SheetUnits.number(1, p.weight())));
        rows.add(row(x.text("label.enthalpy"), props, p -> SheetUnits.number(1, p.enthalpy() / 1e3)));
        y = table(c, box, rows, x0, y, cell);
        y += 4;
        c.hLine(box.x() + 2, box.right() - 12, y, Palette.LINE);
        y += 4;
        c.text(x.text("results.composition"), x0, y, Palette.MUTED);
        y += ROW;
        var names = x.fluids().axis();
        var comp = new ArrayList<String[]>();
        for (int i = 0; i < names.size(); i++) {
            var cells = new String[picked.size() + 1];
            cells[0] = x.fluids().names().apply(names.get(i));
            boolean used = false;
            for (int k = 0; k < picked.size(); k++) {
                var stream = solved.get(k);
                double total = stream == null ? 0 : stream.total();
                double share = total == 0 ? 0 : 100 * stream.amountsView()[i] / total;
                used |= share > 0;
                cells[k + 1] = stream == null ? "" : SheetUnits.number(2, share);
            }
            if (used) comp.add(cells);
        }
        table(c, box, comp, x0, y, cell);
        var view = new ScrollView();
        view.layout(box, box.right() - ScrollView.BAR, y + comp.size() * ROW - box.y());
        view.drawBar(c, skin);
    }

    private static String stateOf(SheetContext x, @Nullable Flowsheet.Stream in, @Nullable Flowsheet.Stream out) {
        return stateOf(x, in, out, true);
    }

    private static String stateOf(SheetContext x, @Nullable Flowsheet.Stream in, @Nullable Flowsheet.Stream out, boolean pressure) {
        var u = x.units();
        var a = x.props(in);
        var b = x.props(out);
        if (in == null || out == null) return in != null ? in.name() : out != null ? out.name() : "";
        String head = x.text("results.from_to", in.name(), out.name());
        if (a == null || b == null) return head;
        if (!pressure) return head + ": " + x.text("results.span_t", u.t(a.temperature()), u.t(b.temperature()), u.temperature().symbol());
        return head + ": " + x.text("results.span", u.t(a.temperature()), u.t(b.temperature()), u.temperature().symbol(), u.p(a.pressure()), u.p(b.pressure()), u.pressure().symbol());
    }

    private static void units(Canvas c, SheetContext x, Rect box) {
        var m = c.measure();
        String[] head = {x.text("results.unit"), x.text("results.kind"), x.text("results.heat_work"), x.text("results.streams")};
        int[] at = {0, 46, 150, 250};
        int y = box.y() + 4, x0 = box.x() + 4;
        for (int i = 0; i < 4; i++) c.text(head[i], x0 + at[i], y, Palette.MUTED);
        c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += LINE;
        var results = x.state().results();
        int r = 0;
        for (var unit : x.editor().sheet().units()) {
            var rows = unitRows(x, results, unit);
            boolean warn = x.state().tone(unit.id()) == SheetState.Tone.WARNING;
            for (int i = 0; i < rows.size(); i++, r++, y += LINE) {
                if (r % 2 == 1) c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
                if (i == 0) {
                    c.text(unit.name(), x0 + at[0], y, warn ? Palette.WARN : Palette.TEXT);
                    c.text(m.cut(x.t().get(Tiles.name(unit.type())), at[2] - at[1] - 4), x0 + at[1], y, Palette.MUTED);
                }
                c.text(rows.get(i)[0], x0 + at[2], y, Palette.ACCENT);
                c.text(m.cut(rows.get(i)[1], box.right() - x0 - at[3] - 4), x0 + at[3], y, Palette.MUTED);
            }
        }
    }

    /** A unit's lines: its heat or work, what went through. */
    private static List<String[]> unitRows(SheetContext x, SheetResults results, Flowsheet.Unit unit) {
        var rows = new ArrayList<String[]>();
        String id = unit.id();
        double power = results.reading(id, "power"), duty = results.reading(id, "duty");
        // A cooler takes heat out of its stream, and reads below zero as the sheet's other coolers would.
        if (unit.type().equals("air_cooler")) duty = -Math.abs(duty);
        String work = !Double.isNaN(power) ? SheetUnits.number(1, power / 1e3) + " kW" : !Double.isNaN(duty) ? SheetUnits.megawatts(duty) + " MW" : "0";
        switch (unit.type()) {
            case "exchanger", "kettle" -> rows.add(new String[]{SheetUnits.megawatts(Math.abs(duty)) + " MW", x.text("results.sides", stateOf(x, x.into(id, "tube_in"), x.outOf(id, "tube_out"), false), stateOf(x, x.into(id, "shell_in"), x.outOf(id, "shell_out"), false))});
            case "column" -> {
                for (var part : parts(results, unit)) {
                    var report = results.report(part);
                    if (report == null || !report.readings().containsKey("duty")) continue;
                    String row = part.substring(part.indexOf('/') + 1);
                    if (row.startsWith("pa ")) {
                        String pa = row.substring(3);
                        rows.add(new String[]{SheetUnits.megawatts(Math.abs(report.readings().get("duty"))) + " MW", x.text("results.pa", pa, stateOf(x, x.into(id, "pa/" + pa + "/in"), x.outOf(id, "pa/" + pa + "/out")))});
                    }
                }
                double condenser = results.reading(id, "condenser");
                rows.add(new String[]{SheetUnits.megawatts(Math.abs(condenser)) + " MW", x.text("results.condenser", stateOf(x, x.into(id, "condenser_in"), x.outOf(id, "condenser_out")))});
                var out = new ArrayList<String>();
                for (var stream : x.streams(id, false)) if (!stream.from().port().equals("condenser_out") && !stream.from().port().contains("/out")) out.add(stream.name());
                rows.add(new String[]{"0", x.text("results.column_out", String.join(", ", out))});
            }
            default -> {
                var in = x.streams(id, true);
                var out = x.streams(id, false);
                rows.add(new String[]{work, stateOf(x, in.isEmpty() ? null : in.getFirst(), out.isEmpty() ? null : out.getFirst())});
            }
        }
        return rows;
    }

    private static void log(Canvas c, SheetContext x, Rect box) {
        var m = c.measure();
        var results = x.state().results();
        var sheet = x.editor().sheet();
        int y = box.y() + 5, i = 0, width = box.w() - 8;
        var lines = new ArrayList<String>();
        var tones = new ArrayList<Integer>();
        for (var loop : results.run().loops()) {
            var tears = new ArrayList<String>();
            for (var tear : loop.tears()) tears.add(NoteText.subject(tear, sheet));
            lines.add(x.text(loop.closed() ? "results.loop_closed" : "results.loop_open", String.join(", ", tears), loop.passes()));
            tones.add(Palette.TEXT);
        }
        lines.add(x.state().status(x.t(), x.units(), false).text());
        tones.add(Palette.TEXT);
        for (var note : NoteText.all(results.run())) {
            lines.add(NoteText.text(note, x.t(), sheet, x.units()));
            tones.add(note.severity() == Note.Severity.ERROR ? Palette.RED : note.severity() == Note.Severity.WARNING ? Palette.WARN : Palette.TEXT);
        }
        for (int k = 0; k < lines.size(); k++)
            for (var line : m.wrap(lines.get(k), width)) {
                if (i % 2 == 1) c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
                c.text(line, box.x() + 4, y, tones.get(k));
                y += LINE;
                i++;
            }
    }

    /** The table as CSV: one line a stream, in SI. */
    public static String csv(SheetContext x, State state) {
        var out = new StringBuilder("stream,temperature_K,pressure_Pa,vapour_percent,mol_per_s,kg_per_s,density_kg_m3,mean_weight,enthalpy_J_kg\n");
        for (var stream : order(x)) {
            var p = x.props(stream);
            if (p == null) continue;
            out.append('"').append(stream.name().replace("\"", "\"\"")).append('"');
            for (double value : new double[]{p.temperature(), p.pressure(), p.vapour(), p.moles(), p.mass(), p.density(), p.weight(), p.enthalpy()}) out.append(',').append(value);
            out.append('\n');
        }
        return out.toString();
    }

    /** Keys the page itself answers. */
    public static boolean press(State state, String key) {
        if (!key.startsWith("view/")) return false;
        state.view = View.values()[Integer.parseInt(key.substring(5))];
        return true;
    }

    /** Ticks the first streams, so the table opens with something to read. */
    public static void first(SheetContext x, State state) {
        if (!state.ticked.isEmpty()) return;
        for (var stream : order(x)) if (state.ticked.size() < MOST_SHOWN) state.ticked.add(stream.id());
    }
}
