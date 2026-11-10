package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.column.operation.Condition;
import com.wormzjl.icheme.science.column.operation.DriftBands;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A column's Operation tab (batch 2026-10-28-column-operation, C7, plan section 13; the owner chose layout B on
 * 2026-10-29: a page {@link #HEIGHT} high, everything in one view). On the door: Control (Off and On) and the set points
 * per unit feed, which Apply on the footer sends. On the HMI glass: the ports (rate, temperature, pressure at the port,
 * the buffer's fill, the boundary valve's opening), the duties booked against the last solve's, the last solve. The status
 * once, on the message display, the engine's line in its hover. The frame is the assembly screen's ({@link AssemblyColumn}).
 * Texts are keys under {@code gui.icheme.}; quantities arrive in SI and show in the client's units. No Minecraft classes:
 * the screen, the gates and the render tool place the same door.
 */
public final class OperationColumn {
    private OperationColumn() {
    }

    /** The page's keys under {@code gui.icheme.}. */
    public static final String KEY = "assembly.operation.";
    /** The Operation page's design height, GUI px (owner's choice B: 640 by 360 at 1920 by 1080 and 2560 by 1440). */
    public static final int HEIGHT = 360;
    /** A table line's pitch on the glass, and the gap between tables. */
    public static final int PITCH = 12, TABLE_GAP = 8;

    /** The client's display units. */
    public record Units(TemperatureUnit temperature, PressureUnit pressure, FlowRateUnit time) {
    }

    // ---- The door ----

    /** The set point entries' keys, in {@code AssemblyNetwork.OperationPayload}'s order. */
    public static List<String> fields(Setpoints s) {
        var out = new ArrayList<>(List.of("feed", "condenser", "reflux", "reboiler"));
        for (int j = 0; j < s.steamPerFeed().length; j++) out.add("steam:" + j);
        for (int j = 0; j < s.drawsPerFeed().length; j++) out.add("draw:" + j);
        for (int j = 0; j < s.pumparoundsPerFeed().length; j++) out.add("pumparound:" + j);
        return out;
    }

    /** Whether entry {@code key} shows: the reboiler only where the design has one. */
    static boolean shown(String key, Setpoints s) {
        return !key.equals("reboiler") || s.reboilerPerFeed() != 0;
    }

    /** The set points as the entries show them, by key, in the client's units. */
    public static Map<String, String> values(Setpoints s, Units u) {
        var out = new LinkedHashMap<String, String>();
        out.put("feed", fixed(u.time().display(s.feedRate()) / 1000, 2));
        out.put("condenser", fixed(u.temperature().display(s.condenserTemperature()), 1));
        out.put("reflux", fixed(s.refluxRatio(), 3));
        out.put("reboiler", fixed(s.reboilerPerFeed() / 1000, 2));
        double[] steam = s.steamPerFeed(), draws = s.drawsPerFeed(), pa = s.pumparoundsPerFeed();
        for (int j = 0; j < steam.length; j++) out.put("steam:" + j, fixed(steam[j], 4));
        for (int j = 0; j < draws.length; j++) out.put("draw:" + j, fixed(draws[j], 4));
        for (int j = 0; j < pa.length; j++) out.put("pumparound:" + j, fixed(pa[j] / 1000, 2));
        return out;
    }

    /**
     * The values Apply sends, in SI and the payload's order, from the entries' texts {@code drafts}; null where one does not
     * read as a number. An entry not shown, or whose text is still the one it showed, sends the set point it holds exactly:
     * a rounded text sent back would move the set point and solve the column again for nothing.
     */
    public static @Nullable List<Double> parse(Setpoints s, Map<String, String> drafts, Units u) {
        var out = new ArrayList<Double>();
        var shown = values(s, u);
        var exact = exact(s);
        for (var key : fields(s)) {
            String text = drafts.get(key);
            if (!shown(key, s) || text == null || text.equals(shown.get(key))) {
                out.add(exact.get(key));
                continue;
            }
            double v;
            try {
                v = Double.parseDouble(text.trim().replace(',', '.'));
            } catch (NumberFormatException notANumber) {
                return null;
            }
            if (!Double.isFinite(v)) return null;
            String kind = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
            out.add(switch (kind) {
                case "feed" -> u.time().perSecond(v * 1000);
                case "condenser" -> u.temperature().kelvin(v);
                case "reboiler", "pumparound" -> v * 1000;
                default -> v;
            });
        }
        return out;
    }

    /** The set points in SI by entry key, as {@link #parse} sends them. */
    private static Map<String, Double> exact(Setpoints s) {
        var out = new LinkedHashMap<String, Double>();
        out.put("feed", s.feedRate());
        out.put("condenser", s.condenserTemperature());
        out.put("reflux", s.refluxRatio());
        out.put("reboiler", s.reboilerPerFeed());
        double[] steam = s.steamPerFeed(), draws = s.drawsPerFeed(), pa = s.pumparoundsPerFeed();
        for (int j = 0; j < steam.length; j++) out.put("steam:" + j, steam[j]);
        for (int j = 0; j < draws.length; j++) out.put("draw:" + j, draws[j]);
        for (int j = 0; j < pa.length; j++) out.put("pumparound:" + j, pa[j]);
        return out;
    }

    /** The door's input: the snapshot, whether a command awaits its bucket, the client's units. */
    public record Input(OperationView view, boolean queued, Units units) {
    }

    /**
     * The door's rows: Control (Off, On; the position latched, nothing active while a command awaits its answer), then the
     * set points: entries where the column can take them (it ran once), meters where it cannot yet. Widget rows only, so no
     * widget moves while the readings change.
     */
    public static List<Column.Row> rows(Input in, HeatControlColumn.Texts t) {
        var v = in.view();
        boolean on = v.running();
        var control = new Column.Buttons(List.of(new Column.Button("stop", t.get(KEY + "off"), on && !in.queued(), t.get(KEY + "off.hint"), !on),
                new Column.Button("start", t.get(KEY + "on"), !on && !in.queued(), t.get(KEY + "on.hint"), on)));
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(t.get(KEY + "group.control"), t.get(KEY + "group.control.hint"), List.of(control)));
        var s = v.setpoints();
        var values = values(s, in.units());
        // Where the table in force was solved at other set points (W7): the accepted value on the entry, and why the
        // requested one was refused.
        var accepted = v.accepted() == null ? null : values(v.accepted(), in.units());
        var refusal = Condition.find(v.conditions(), Condition.SETPOINT_REFUSED);
        var entries = new ArrayList<Column.Row>();
        for (var key : fields(s)) {
            if (!shown(key, s)) continue;
            String label = label(key, s, t), unit = unit(key, in.units(), t), hint = t.get(KEY + "hint." + kind(key));
            if (accepted != null && !accepted.get(key).equals(values.get(key))) {
                hint = hint + "\n" + t.get(KEY + "hint.accepted", (accepted.get(key) + " " + unit).trim());
                if (refusal != null && !refusal.text().isEmpty()) hint = hint + "\n" + t.get(KEY + "hint.refused", refusal.text());
            }
            entries.add(v.editable() ? new Column.Field(key, label, unit, hint) : new Column.Reading(key, label, values.get(key), unit, hint));
        }
        rows.add(new Column.Group(t.get(KEY + "group.setpoints"), t.get(KEY + (v.editable() ? "group.setpoints.hint" : "group.setpoints.reader")), entries));
        return rows;
    }

    /** Whether Apply acts: the column takes set points, no command awaits its answer, every entry reads as a number. */
    public static boolean applies(Input in, Map<String, String> drafts) {
        return in.view().editable() && !in.queued() && parse(in.view().setpoints(), drafts, in.units()) != null;
    }

    /** The footer's push buttons: Close, then Apply (keys {@code close}, {@code apply}), Apply the confirming one. */
    public static List<Column.Button> footerButtons(boolean applies, HeatControlColumn.Texts t) {
        return List.of(new Column.Button("close", t.get("fluid.close"), true, null), new Column.Button("apply", t.get(KEY + "apply"), applies, t.get(KEY + "apply.hint")));
    }

    private static String kind(String key) {
        return key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
    }

    /** An entry's label; a steam, draw or pumparound numbered where the design has more than one. */
    static String label(String key, Setpoints s, HeatControlColumn.Texts t) {
        String kind = kind(key);
        if (!key.contains(":")) return t.get(KEY + "label." + kind);
        int j = Integer.parseInt(key.substring(key.indexOf(':') + 1));
        int n = switch (kind) {
            case "steam" -> s.steamPerFeed().length;
            case "draw" -> s.drawsPerFeed().length;
            default -> s.pumparoundsPerFeed().length;
        };
        return n == 1 ? t.get(KEY + "label." + kind) : t.get(KEY + "label." + kind + ".n", j + 1);
    }

    static String unit(String key, Units u, HeatControlColumn.Texts t) {
        return switch (kind(key)) {
            case "feed" -> u.time().unit("kmol");
            case "condenser" -> u.temperature().symbol();
            case "reflux" -> "";
            case "reboiler", "pumparound" -> "kJ/mol";
            default -> "mol/mol";
        };
    }

    // ---- The status ----

    /**
     * The status once: the phase in words (the binding limit with its share; then a refused solve, else rule A, else a solve
     * in flight), its tone, the engine's line in the hover.
     */
    public static AssemblyColumn.Status status(OperationView v, HeatControlColumn.Texts t) {
        var binding = v.binding();
        String text = v.phase() == OperationView.Phase.LIMITED && binding != null
                ? t.get(KEY + "status.limited", t.get(KEY + "limit." + limit(binding.condition())), Math.round(100 * v.alpha()))
                : t.get(KEY + "status." + v.phase().name().toLowerCase(Locale.ROOT));
        // One line: the phase and the one note that matters most; the engine's line in the hover says the rest.
        if (v.refused()) text = t.get(KEY + "status.failed", text);
        else if (v.ruleA()) text = t.get(KEY + "status.rule_a", text);
        else if (v.resolving() != null) text = t.get(KEY + "status.resolving", text, trigger(v.resolving(), t));
        int palette = switch (v.phase()) {
            case RUNNING -> Palette.ACCENT;
            case STOPPED, STARTING -> Palette.MUTED;
            case LIMITED, NOT_READY -> Palette.WARN;
            case UPSET -> Palette.RED;
        };
        return new AssemblyColumn.Status(text, palette, v.engine());
    }

    /** A LIMIT's word as its key: {@code limit.<word>}. */
    static String limit(Condition c) {
        return c.word.replace(' ', '_');
    }

    static String trigger(@Nullable DriftBands.Trigger trigger, HeatControlColumn.Texts t) {
        return trigger == null ? "—" : t.get(KEY + "trigger." + trigger.name().toLowerCase(Locale.ROOT));
    }

    // ---- The glass ----

    /** The ports' table: a header, then each port's name, rate, temperature, pressure, buffer fill and valve opening. */
    public static List<String[]> ports(OperationView v, Units u, HeatControlColumn.Texts t) {
        var out = new ArrayList<String[]>();
        out.add(new String[]{t.get(KEY + "column.port"), u.time().unit("kmol"), u.temperature().symbol(), u.pressure().symbol(), t.get(KEY + "column.held"), t.get(KEY + "column.valve")});
        var count = new LinkedHashMap<Port, Integer>();
        for (var s : v.streams()) count.merge(s.port(), 1, Integer::sum);
        for (var s : v.streams()) {
            String name = count.get(s.port()) > 1 || s.port() == Port.DRAW ? t.get(KEY + "port." + s.port().name().toLowerCase(Locale.ROOT) + ".n", s.index() + 1)
                    : t.get(KEY + "port." + s.port().name().toLowerCase(Locale.ROOT));
            out.add(new String[]{name, fixed(u.time().display(s.rate()) / 1000, 1), fixed(Double.isNaN(s.temperature()) ? Double.NaN : u.temperature().display(s.temperature()), 1),
                    pressure(s.pressure(), u.pressure()), fixed(100 * s.held(), 0), fixed(100 * s.opening(), 0)});
        }
        return out;
    }

    /** The duties' table: what the last cycle booked against what the last solve gave at the set feed, MW; an air condenser's fans in kW. */
    public static List<String[]> duties(OperationView v, HeatControlColumn.Texts t) {
        var out = new ArrayList<String[]>();
        out.add(new String[]{t.get(KEY + "column.duty"), t.get(KEY + "column.booked"), t.get(KEY + "column.solved")});
        int pumparounds = 0;
        for (var d : v.duties()) if (d.kind() == OperationView.DutyKind.PUMPAROUND) pumparounds++;
        for (var d : v.duties()) {
            String kind = d.kind().name().toLowerCase(Locale.ROOT);
            String name = d.kind() == OperationView.DutyKind.PUMPAROUND && pumparounds > 1 ? t.get(KEY + "duty." + kind + ".n", d.index() + 1) : t.get(KEY + "duty." + kind);
            // The fans draw power, not heat: kW, which their row's name says, where the heads read MW (U2).
            boolean fans = d.kind() == OperationView.DutyKind.FANS;
            out.add(new String[]{name, fans ? fixed(d.booked() / 1e3, 1) : fixed(d.booked() / 1e6, 2), fans ? fixed(d.solved() / 1e3, 1) : fixed(d.solved() / 1e6, 2)});
        }
        return out;
    }

    /**
     * The conditions' table (W7): a header, then each condition the last cycle and the solves found, in the list's order:
     * its word, its value (a LIMIT's share of the set feed, a high load's fraction of flood) and its member (a tray as the
     * player counts, a steam inlet, a pumparound, a product). Empty where none holds. A free text stays in the engine's
     * hover, where the status says it, so no cell outgrows the glass.
     */
    public static List<String[]> conditions(OperationView v, HeatControlColumn.Texts t) {
        if (v.conditions().isEmpty()) return List.of();
        var out = new ArrayList<String[]>();
        out.add(new String[]{t.get(KEY + "column.condition"), t.get(KEY + "column.value"), t.get(KEY + "column.member")});
        for (var a : v.conditions()) {
            var c = a.condition();
            boolean share = c.rank == Condition.Rank.LIMIT || c == Condition.HIGH_LOAD;
            out.add(new String[]{t.get(KEY + "condition." + c.name().toLowerCase(Locale.ROOT)), share ? fixed(100 * a.value(), 0) + " %" : "—", member(a, t)});
        }
        return out;
    }

    /** A condition's member: a tray, a steam inlet, a pumparound, a product or a member, numbered from 1; a dash for none. */
    static String member(Condition.Active a, HeatControlColumn.Texts t) {
        if (a.index() < 0) return "—";
        String kind = switch (a.condition()) {
            case FLOODING, HIGH_LOAD -> "tray";
            case STEAM_SHORT, UNMETERED -> "steam";
            case PUMPAROUND_SHORT -> "pumparound";
            case PRODUCT_FULL -> "product";
            default -> "member";
        };
        return t.get(KEY + "member." + kind, a.index() + 1);
    }

    /** A condition's colour by its rank: a notice muted, a limit or a stale table a warning, a trip red. */
    static int colour(Condition.Rank rank) {
        return switch (rank) {
            case NOTICE -> Palette.MUTED;
            case NOT_READY, LIMIT, OFF_TABLE -> Palette.WARN;
            case TRIP -> Palette.RED;
        };
    }

    /**
     * The duties' table with the trays beside them (W7): after the duties, the pressure drop over the trays and the worst
     * tray's fraction of its flood limit, at the feed the last cycle took and at the set feed, each row naming its unit as
     * the fans' does. No tray rows where the table in force has no hydraulics.
     */
    public static List<String[]> duties(OperationView v, Units u, HeatControlColumn.Texts t) {
        var out = duties(v, t);
        var trays = v.trays();
        if (!trays.known()) return out;
        out.add(new String[]{t.get(KEY + "trays.drop", u.pressure().symbol()), pressure(trays.drop(), u.pressure()), pressure(trays.dropAtSet(), u.pressure())});
        out.add(new String[]{trays.tray() > 0 ? t.get(KEY + "trays.flood.n", trays.tray()) : t.get(KEY + "trays.flood"), fixed(100 * trays.flood(), 0), fixed(100 * trays.floodAtSet(), 0)});
        return out;
    }

    /** Water's molar mass, kg/mol: a steam ratio in mol/mol shows as kg of steam. */
    static final double WATER_KG_PER_MOL = 0.018015;

    /**
     * The set points' table (W7): each ratio entry's absolute value at the set feed (the reboiler and the pumparounds in MW,
     * steam in kg per the client's time, draws in kmol per it), and where the table in force was solved at other set points,
     * the accepted value beside it. Empty where the design has no ratio entries.
     */
    public static List<String[]> absolutes(OperationView v, Units u, HeatControlColumn.Texts t) {
        var s = v.setpoints();
        var a = v.accepted();
        var rows = new ArrayList<String[]>();
        for (var key : fields(s)) {
            String kind = kind(key);
            if (!shown(key, s) || kind.equals("feed") || kind.equals("condenser") || kind.equals("reflux")) continue;
            String name = label(key, s, t), unit = switch (kind) {
                case "steam" -> u.time().unit("kg");
                case "draw" -> u.time().unit("kmol");
                default -> "MW";
            };
            String requested = absolute(key, s, s.feedRate(), u), row = name + ", " + unit;
            rows.add(a == null ? new String[]{row, requested} : new String[]{row, requested, absolute(key, a, s.feedRate(), u)});
        }
        if (rows.isEmpty()) return List.of();
        var out = new ArrayList<String[]>();
        out.add(a == null ? new String[]{t.get(KEY + "column.setpoint"), t.get(KEY + "column.at_feed")}
                : new String[]{t.get(KEY + "column.setpoint"), t.get(KEY + "column.requested"), t.get(KEY + "column.accepted")});
        out.addAll(rows);
        return out;
    }

    /** Ratio entry {@code key} of {@code s} at feed {@code feed}, mol/s, in the client's units. */
    static String absolute(String key, Setpoints s, double feed, Units u) {
        String kind = kind(key);
        int j = key.contains(":") ? Integer.parseInt(key.substring(key.indexOf(':') + 1)) : 0;
        return switch (kind) {
            case "reboiler" -> fixed(s.reboilerPerFeed() * feed / 1e6, 2);
            case "pumparound" -> fixed(s.pumparoundsPerFeed()[j] * feed / 1e6, 2);
            case "steam" -> fixed(u.time().display(s.steamPerFeed()[j] * feed * WATER_KG_PER_MOL), 0);
            default -> fixed(u.time().display(s.drawsPerFeed()[j] * feed) / 1000, 1);
        };
    }

    /** The last solve: its route in the heading, what asked it, its age. */
    public static List<String[]> solve(OperationView v, HeatControlColumn.Texts t) {
        var s = v.solve();
        if (s == null) return List.<String[]>of(new String[]{t.get(KEY + "solve"), t.get(KEY + "solve.none")});
        long age = Math.round(s.age());
        return List.<String[]>of(new String[]{t.get(KEY + "solve"), t.get(KEY + (s.warm() ? "solve.warm" : "solve.cold"))}, new String[]{t.get(KEY + "solve.trigger"), trigger(s.trigger(), t)},
                new String[]{t.get(KEY + "solve.age"), age < 120 ? t.get(KEY + "seconds", age) : t.get(KEY + "minutes", age / 60)});
    }

    /** A table's cell pad (the text's room in its cell is its width less this), and the widest a number cell grows. */
    public static final int PAD = 5, CELL_MAX = 60;

    /**
     * A table's columns on a glass {@code w} wide: each as wide as its widest text and its pad; the room left widens the
     * number cells up to {@link #CELL_MAX} each, then the name. No cell is narrower than its widest text, so no text is cut
     * where the needs fit (the gates hold that at every width the Operation page takes).
     */
    public static int[] widths(List<String[]> rows, int w, Measure m) {
        int n = rows.getFirst().length;
        int[] out = new int[n];
        for (var row : rows) for (int i = 0; i < n; i++) out[i] = Math.max(out[i], m.width(row[i]) + PAD);
        int left = w - Arrays.stream(out).sum();
        for (int i = 1; i < n && left > 0; i++) {
            int grow = Math.min(left / (n - i), Math.max(0, CELL_MAX - out[i]));
            out[i] += grow;
            left -= grow;
        }
        if (left > 0) out[0] += left;
        return out;
    }

    /**
     * The glass's tables for {@code v}, top to bottom (W7): the ports, the duties with the trays, the last solve (the tables of
     * before, which the design size shows in one view), then the conditions where any hold and the set points' absolute values.
     */
    public static List<List<String[]>> tables(OperationView v, Units u, HeatControlColumn.Texts t) {
        var out = new ArrayList<List<String[]>>();
        for (var table : List.of(ports(v, u, t), duties(v, u, t), solve(v, t), conditions(v, t), absolutes(v, u, t))) if (!table.isEmpty()) out.add(table);
        return out;
    }

    /** The glass's height the tables take for {@code v}: their lines, the gaps between. */
    public static int height(OperationView v, Units u, HeatControlColumn.Texts t) {
        var tables = tables(v, u, t);
        int lines = 0;
        for (var table : tables) lines += table.size();
        return PITCH * lines + TABLE_GAP * (tables.size() - 1) + 2 * tables.size();
    }

    /** The tables on the glass's inside {@code box}, one under the other, scrolled up by {@code offset} (a glass shorter than {@link #height}: the screen clips it and scrolls it). */
    public static void glass(Canvas c, Rect box, int offset, OperationView v, Units u, HeatControlColumn.Texts t, Measure m) {
        box = new Rect(box.x(), box.y() - offset, box.w(), Math.max(box.h(), height(v, u, t)));
        // The conditions' words in their ranks' colours (W7); every other table's names in the text colour.
        int[] colours = new int[v.conditions().size() + 1];
        for (int r = 1; r < colours.length; r++) colours[r] = colour(v.conditions().get(r - 1).condition().rank);
        int y = box.y() - TABLE_GAP;
        var tables = tables(v, u, t);
        for (int k = 0; k < tables.size(); k++)
            y = table(c, new Rect(box.x(), y + TABLE_GAP, box.w(), box.bottom() - y - TABLE_GAP), widths(tables.get(k), box.w(), m), tables.get(k), m,
                    tables.get(k).getFirst()[0].equals(t.get(KEY + "column.condition")) ? colours : null);
    }

    /** {@link #table(Canvas, Rect, int[], List, Measure, int[])} with every name in the text colour. */
    public static int table(Canvas c, Rect box, int[] widths, List<String[]> rows, Measure m) {
        return table(c, box, widths, rows, m, null);
    }

    /**
     * A table on the glass: its header muted under a rule, every second entry on a band ({@link DialogColumns#BAND}), the
     * name left (in {@code names[r]}'s colour where given) and the numbers right-aligned in their cells; returns the top of
     * the line after its last.
     */
    public static int table(Canvas c, Rect box, int[] widths, List<String[]> rows, Measure m, int @Nullable [] names) {
        int y = box.y() + 2;
        for (int r = 0; r < rows.size() && y + 10 <= box.bottom(); r++, y += PITCH) {
            if (r > 0 && r % 2 == 0) c.fill(box.x(), y - 2, box.right(), y + PITCH - 2, DialogColumns.BAND);
            int x = box.x() + 3;
            for (int i = 0; i < widths.length; i++) {
                String text = rows.get(r)[i];
                int colour = r == 0 ? Palette.MUTED : i == 0 ? names != null && r < names.length ? names[r] : Palette.TEXT : Palette.ACCENT;
                c.text(text, i == 0 ? x : x + widths[i] - PAD - m.width(text), y, colour);
                x += widths[i];
            }
            if (r == 0) c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        }
        return y;
    }

    // ---- Numbers ----

    static String fixed(double v, int decimals) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%." + decimals + "f", v == 0 ? 0 : v) : "—";
    }

    /** A pressure in {@code unit}, to a resolution a reader needs: Pa whole, kPa to 0.1, bar to 0.01, MPa to 0.001. */
    static String pressure(double pascal, PressureUnit unit) {
        int decimals = switch (unit) {
            case PASCAL -> 0;
            case KILOPASCAL -> 1;
            case BAR -> 2;
            case MEGAPASCAL -> 3;
        };
        return fixed(Double.isFinite(pascal) ? unit.display(pascal) : Double.NaN, decimals);
    }
}
