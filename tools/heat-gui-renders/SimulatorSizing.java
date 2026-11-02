import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The process simulator's sizing stage (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md}): the Sizing tab, its four
 * views and the constructor's list. Nothing here is built. Every number comes from {@code tools/process-simulator/SizingProbe.java}
 * through the table it writes, so a sheet cannot drift from the probe.
 */
final class SimulatorSizing {
    static final Path DATA = Path.of("tools/process-simulator/sizing-sample.tsv");
    static final int DOOR = 168;

    /** The rows of kind {@code kind} of the probe's table, without their kind. */
    static List<String[]> rows(String kind) {
        var out = new ArrayList<String[]>();
        try {
            for (var line : Files.readAllLines(DATA)) {
                var cells = line.split("\t", -1);
                if (cells[0].equals(kind)) {
                    var rest = new String[cells.length - 1];
                    System.arraycopy(cells, 1, rest, 0, rest.length);
                    out.add(rest);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** A lamp's colour: 0 fits, 1 outside good practice, 2 will not run or cannot be built. */
    static int lamp(String code) {
        return switch (code) {
            case "0" -> Palette.ACCENT;
            case "1" -> Palette.WARN;
            default -> Palette.RED;
        };
    }

    static String status() {
        return rows("STATUS").getFirst()[0];
    }

    static boolean clean() {
        return rows("STATUS").getFirst()[1].equals("0");
    }

    /** The tab's frame: its views, the sizing basis, the selection's own rows, the footer; returns the glass's bottom. */
    static int frame(GuiPrototypes.Door d, int view, List<Column.Row> item, Map<String, String> values) {
        return frame(d, view, item, values, status(), clean() ? Palette.ACCENT : Palette.WARN, true);
    }

    static int frame(GuiPrototypes.Door d, int view, List<Column.Row> item, Map<String, String> values, String status, int tone, boolean sendable) {
        SimulatorPrototypes.frame(d, 2, status, tone, false);
        int bottom = d.h - 38;
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group("View", null, List.of(new Column.Buttons(List.of(SimulatorPrototypes.key("units", "Units", view == 0), SimulatorPrototypes.key("lines", "Lines", view == 1))),
                new Column.Buttons(List.of(SimulatorPrototypes.key("path", "Pressure", view == 2), SimulatorPrototypes.key("build", "Build", view == 3))))));
        rows.add(new Column.Group("Design", "What every size is worked out for", List.of(SimulatorPrototypes.entry("margin", "Design flow", "%"), SimulatorPrototypes.entry("length", "Line length", "m"),
                SimulatorPrototypes.entry("fall", "PA fall", "K"))));
        rows.addAll(item);
        var all = new HashMap<>(values);
        var basis = rows("BASIS").getFirst();
        all.put("margin", basis[0]);
        all.put("length", basis[1]);
        all.put("fall", basis[2]);
        d.column(rows, 10, 74, DOOR - 10, all);
        d.skin.vDivider(d.c, DOOR + 3, 74, bottom);
        footer(d, sendable);
        return bottom;
    }

    /** The footer: Close, Size all, Send to block, Undo and Redo; the display keys on their tray. */
    static void footer(GuiPrototypes.Door d, boolean sendable) {
        d.skin.hDivider(d.c, 8, d.w - 8, d.h - 35);
        int x = 10, y = d.h - 27;
        d.skin.button(d.c, new Rect(x, y, 50, 18), "Close", Skin.Press.REST, false);
        x += 54;
        d.skin.button(d.c, new Rect(x, y, 60, 18), "Size all", Skin.Press.PRIMARY, false);
        x += 64;
        d.skin.button(d.c, new Rect(x, y, 86, 18), "Send to block", sendable ? Skin.Press.REST : Skin.Press.OFF, false);
        x += 90;
        for (var o : List.of("Undo", "Redo")) {
            d.skin.button(d.c, new Rect(x, y, 50, 18), o, Skin.Press.REST, false);
            x += 54;
        }
        String[] keys = {"Mole", "°C", "bar", "/h"};
        int kw = 40, strip = 4 * kw + 18, kx = d.w - 10 - strip;
        d.skin.softKeys(d.c, new Rect(kx - 4, d.h - 31, strip + 8, 26));
        for (int i = 0; i < 4; i++) d.skin.softKey(d.c, new Rect(kx + i * (kw + 6), y, kw, 18), keys[i], false);
    }

    /** One table row's text, its cells at {@code at}; an entry of {@code right} ends there rather than starting. */
    static void cell(GuiPrototypes.Door d, String text, int x, int y, boolean right, int colour) {
        d.c.text(text, right ? x - d.m.width(text) : x, y, colour);
    }

    /** The left door's rows for a selected unit: how its size is held, and the sizes themselves. */
    static List<Column.Row> held(String tag, List<Column.Row> sizes, boolean fixed) {
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Buttons(List.of(SimulatorPrototypes.key("auto", "Auto", !fixed), SimulatorPrototypes.key("fixed", "Fixed", fixed))));
        rows.addAll(sizes);
        return List.of(new Column.Group(tag, "Auto takes the smallest size that meets every limit. Fixed keeps your size and rates it.", rows));
    }

    /** The Units view: {@code fixed} shows the probe's kept size for {@code selected}, rated instead of sized. */
    static void units(GuiPrototypes.Door d, String selected, List<Column.Row> sizes, Map<String, String> values, boolean fixed) {
        String[] kept = fixed ? rows("RATED").stream().filter(r -> r[0].equals(selected)).findFirst().orElseThrow() : null;
        int bottom = fixed ? frame(d, 0, held(selected, sizes, true), values, "Sized at 110 % of the steady flow: 1 unit will not build: " + selected + " " + kept[2].toLowerCase() + " " + kept[3] + ", " + kept[4], Palette.WARN, false)
                : frame(d, 0, held(selected, sizes, false), values);
        var box = d.well(new Rect(DOOR + 9, 71, d.w - DOOR - 14, bottom - 71));
        int x0 = box.x() + 16, y = box.y() + 3, value = 588, limit = 660;
        d.c.text("Tag", x0, y, Palette.MUTED);
        d.c.text("Equipment", x0 + 84, y, Palette.MUTED);
        d.c.text("Size", x0 + 176, y, Palette.MUTED);
        d.c.text("Closest limit", x0 + 390, y, Palette.MUTED);
        cell(d, "Value", x0 + value, y, true, Palette.MUTED);
        cell(d, "Limit", x0 + limit, y, true, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 13;
        var units = rows("UNIT");
        for (int r = 0; r < units.size(); r++, y += 12) {
            var u = units.get(r);
            if (fixed && u[1].equals(selected)) u = new String[]{u[0], u[1], u[2], kept[1], kept[2], kept[3], kept[4], "2"};
            boolean sub = u[0].equals("1");
            if (r % 2 == 1) d.c.fill(box.x(), y - 2, box.right(), y + 10, DialogColumns.BAND);
            d.c.fill(box.x() + 5, y + 1, box.x() + 11, y + 7, lamp(u[7]));
            if (u[1].equals(selected)) d.c.outline(box.x() + 1, y - 2, box.w() - 2, 12, Palette.TEXT);
            d.c.text(u[1], x0 + (sub ? 8 : 0), y, Palette.TEXT);
            d.c.text(u[2], x0 + 84, y, Palette.MUTED);
            d.c.text(u[3], x0 + 176, y, Palette.ACCENT);
            d.c.text(u[4], x0 + 390, y, Palette.MUTED);
            cell(d, u[5], x0 + value, y, true, u[7].equals("0") ? Palette.ACCENT : lamp(u[7]));
            cell(d, u[6], x0 + limit, y, true, Palette.MUTED);
        }
        y += 3;
        d.c.hLine(box.x() + 2, box.right() - 2, y, Palette.LINE);
        y += 3;
        d.c.text(selected + ": every limit", x0, y, Palette.TEXT);
        d.c.text("Kind", x0 + 176, y, Palette.MUTED);
        cell(d, "Value", x0 + 330, y, true, Palette.MUTED);
        cell(d, "Limit", x0 + 410, y, true, Palette.MUTED);
        d.c.text("From", x0 + 424, y, Palette.MUTED);
        y += 11;
        var limits = rows("CRIT").stream().filter(c -> c[0].equals(fixed ? selected + " kept" : selected)).toList();
        for (int r = 0; r < limits.size() && y + 10 <= box.bottom(); r++, y += 11) {
            var c = limits.get(r);
            d.c.fill(box.x() + 5, y + 1, box.x() + 11, y + 7, lamp(c[5]));
            d.c.text(c[1], x0, y, Palette.TEXT);
            d.c.text(c[2], x0 + 176, y, c[2].equals("Must") ? Palette.TEXT : Palette.MUTED);
            cell(d, c[3], x0 + 330, y, true, c[5].equals("0") ? Palette.ACCENT : lamp(c[5]));
            cell(d, c[4], x0 + 410, y, true, Palette.MUTED);
            d.c.text(d.m.cut(c[6], box.right() - x0 - 424 - 4), x0 + 424, y, Palette.MUTED);
        }
    }

    static void lines(GuiPrototypes.Door d, String selected) {
        var picked = rows("LINE").stream().filter(l -> l[0].equals(selected)).findFirst().orElseThrow();
        var rowsLeft = List.<Column.Row>of(new Column.Buttons(List.of(SimulatorPrototypes.key("service", picked[1], false))), SimulatorPrototypes.entry("bore", "Bore", "m"), SimulatorPrototypes.entry("len", "Length", "m"));
        int bottom = frame(d, 1, held(selected, rowsLeft, false), Map.of("bore", picked[3], "len", rows("BASIS").getFirst()[1]));
        var box = d.well(new Rect(DOOR + 9, 71, d.w - DOOR - 14, bottom - 71));
        int x0 = box.x() + 16, y = box.y() + 3;
        d.c.text("Line", x0, y, Palette.MUTED);
        d.c.text("Service", x0 + 92, y, Palette.MUTED);
        cell(d, "m³/h", x0 + 256, y, true, Palette.MUTED);
        cell(d, "Bore, m", x0 + 316, y, true, Palette.MUTED);
        cell(d, "m/s", x0 + 366, y, true, Palette.MUTED);
        cell(d, "bar/100 m", x0 + 436, y, true, Palette.MUTED);
        cell(d, "ρv²", x0 + 490, y, true, Palette.MUTED);
        d.c.text("Closest", x0 + 510, y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 13;
        var lines = rows("LINE");
        for (int r = 0; r < lines.size() && y + 10 <= box.bottom(); r++, y += 12) {
            var l = lines.get(r);
            if (r % 2 == 1) d.c.fill(box.x(), y - 2, box.right(), y + 10, DialogColumns.BAND);
            d.c.fill(box.x() + 5, y + 1, box.x() + 11, y + 7, lamp(l[7]));
            if (l[0].equals(selected)) d.c.outline(box.x() + 1, y - 2, box.w() - 2, 12, Palette.TEXT);
            d.c.text(l[0], x0, y, Palette.TEXT);
            d.c.text(l[1], x0 + 92, y, Palette.MUTED);
            cell(d, l[2], x0 + 256, y, true, Palette.ACCENT);
            cell(d, l[3], x0 + 316, y, true, Palette.TEXT);
            cell(d, l[4], x0 + 366, y, true, Palette.ACCENT);
            cell(d, l[5], x0 + 436, y, true, Palette.ACCENT);
            cell(d, l[6], x0 + 490, y, true, Palette.ACCENT);
            d.c.text(l[8], x0 + 510, y, Palette.MUTED);
        }
    }

    static void pressure(GuiPrototypes.Door d, int path) {
        var sums = rows("PATHSUM");
        var sum = sums.get(path);
        var keys = new ArrayList<Column.Row>();
        for (int k = 0; k < sums.size(); k++) keys.add(new Column.Buttons(List.of(SimulatorPrototypes.key("path" + k, sums.get(k)[0], k == path))));
        keys.add(SimulatorPrototypes.meter("From", sum[4], "bar"));
        if (!sum[5].equals("0.00")) keys.add(SimulatorPrototypes.meter("Static head", sum[5], "bar"));
        keys.add(SimulatorPrototypes.meter("To", sum[6], "bar"));
        keys.add(SimulatorPrototypes.meter("Available", sum[1], "bar"));
        keys.add(SimulatorPrototypes.meter("Lost", sum[2], "bar"));
        keys.add(SimulatorPrototypes.meter("Spare", sum[3], "bar"));
        var left = List.<Column.Row>of(new Column.Group("Path", "From a machine's outlet to the next pressure the sheet sets", keys));
        int bottom = frame(d, 2, left, Map.of());
        var box = d.well(new Rect(DOOR + 9, 71, d.w - DOOR - 14, bottom - 71));
        int x0 = box.x() + 16, y = box.y() + 3;
        d.c.text("Step", x0, y, Palette.MUTED);
        d.c.text("What", x0 + 100, y, Palette.MUTED);
        cell(d, "Lost, bar", x0 + 400, y, true, Palette.MUTED);
        cell(d, "After it, bar", x0 + 500, y, true, Palette.MUTED);
        d.c.text("Sheet says", x0 + 520, y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 13;
        var steps = rows("PATH").stream().filter(p -> p[0].equals("" + path)).toList();
        for (int r = 0; r < steps.size(); r++, y += 13) {
            var p = steps.get(r);
            if (r % 2 == 1) d.c.fill(box.x(), y - 2, box.right(), y + 11, DialogColumns.BAND);
            d.c.fill(box.x() + 5, y + 1, box.x() + 11, y + 7, lamp(p[6]));
            d.c.text(p[1], x0, y, Palette.TEXT);
            d.c.text(p[2], x0 + 100, y, Palette.MUTED);
            cell(d, p[3], x0 + 400, y, true, Palette.ACCENT);
            cell(d, p[4], x0 + 500, y, true, Palette.TEXT);
            d.c.text(p[5], x0 + 520, y, Palette.MUTED);
        }
        // The path as a stair: each step's bar stands at the pressure after it, the sheet's own pressure across them.
        double from = Double.parseDouble(sum[4]) + Double.parseDouble(sum[5]), to = Double.parseDouble(sum[6]), last = Double.parseDouble(steps.getLast()[4]);
        double base = to - .25 * (from - to);
        int top = y + 22, low = box.bottom() - 24, wide = (box.w() - 136) / steps.size(), x = x0, targetY = low - (int) Math.round((to - base) / (from - base) * (low - top));
        for (var p : steps) {
            int bar = low - (int) Math.round((Double.parseDouble(p[4]) - base) / (from - base) * (low - top));
            d.c.fill(x, bar, x + wide - 6, low, Palette.TEAL_FILL);
            d.c.hLine(x, x + wide - 6, bar, Palette.ACCENT);
            d.c.text(p[1].replace(" cooler", ""), x, low + 4, Palette.MUTED);
            x += wide;
        }
        d.c.hLine(x0 - 8, box.right() - 8, targetY, Palette.WARN);
        d.c.text("The sheet's " + sum[6] + " bar", x + 4, targetY - 11, Palette.WARN);
        int lastY = low - (int) Math.round((last - base) / (from - base) * (low - top));
        d.c.text("Spare " + sum[3] + " bar", x + 4, (lastY + targetY) / 2 - 4, Palette.ACCENT);
    }

    static void build(GuiPrototypes.Door d) {
        var left = List.<Column.Row>of(new Column.Group("Lines", null, rows("BUILDLINES").stream().map(l -> (Column.Row) SimulatorPrototypes.meter(l[0], l[1], l[2])).toList()));
        int bottom = frame(d, 3, left, Map.of());
        var box = d.well(new Rect(DOOR + 9, 71, d.w - DOOR - 14, bottom - 71));
        int x0 = box.x() + 16, y = box.y() + 3;
        d.c.text("Tag", x0, y, Palette.MUTED);
        d.c.text("Count", x0 + 72, y, Palette.MUTED);
        d.c.text("Constructor", x0 + 112, y, Palette.MUTED);
        d.c.text("Sizes", x0 + 236, y, Palette.MUTED);
        d.c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 13;
        var items = rows("BUILD");
        for (int r = 0; r < items.size(); r++, y += 13) {
            var b = items.get(r);
            if (r % 2 == 1) d.c.fill(box.x(), y - 2, box.right(), y + 11, DialogColumns.BAND);
            d.c.fill(box.x() + 5, y + 1, box.x() + 11, y + 7, lamp(b[4]));
            d.c.text(b[0], x0, y, Palette.TEXT);
            d.c.text(b[1], x0 + 72, y, Palette.TEXT);
            d.c.text(b[2], x0 + 112, y, Palette.MUTED);
            d.c.text(b[3], x0 + 236, y, Palette.ACCENT);
        }
    }

    /** The constructor's Import, listing what Send to block stored. */
    static void constructor(GuiPrototypes.Door d) {
        var items = rows("BUILD");
        var table = new String[Math.min(items.size(), 13)][];
        for (int r = 0; r < table.length; r++) {
            var b = items.get(r);
            table[r] = new String[]{b[0], b[2], b[3], "Ready"};
        }
        SimulatorPrototypes.list(d, "Import a design", new String[]{"Tag", "Equipment", "Sizes", "State"}, new int[]{0, 76, 180, 450}, table, new String[]{"Close", "Import"}, 1);
    }
}
