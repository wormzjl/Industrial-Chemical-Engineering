package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * The dialogs over the simulator (batch 2026-11-02-process-simulator, sheets 13 and 17): a list on a dialog's glass under its title, Open's files, the changes after an
 * update. Open and Save as use the player's own folder, {@code .minecraft/IChemE/flowsheets/<name>.json}; a name is one file's, so separators and the system's reserved names are refused.
 */
public final class FileColumns {
    public static final int W = 533, H = 280;
    private static final String AUTOSAVE = "autosave", AUTOSAVE_OF = ".autosave", AUTOSAVE_BLOCK = AUTOSAVE + "-";
    /** The longest name a file takes; a changes line's height and the room under it. */
    private static final int LONGEST = 64, LINE = 11, GAP = 6;
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    /**
     * A list dialog.
     *
     * @param chosen  the row in hand, or -1
     * @param warn    the row's last cell stands in the warning colour
     * @param primary the key that confirms
     * @param off     why a key is off, by its place; null where it is on
     */
    public record Listing(String title, String[] head, int[] at, List<Row> rows, int chosen, String[] keys, int primary, String[] off) {
        public Listing(String title, String[] head, int[] at, List<Row> rows, int chosen, String[] keys, int primary) {
            this(title, head, at, rows, chosen, keys, primary, new String[keys.length]);
        }
    }

    public record Row(String[] cells, boolean warn) {
    }

    /** A flowsheet file as Open lists it. */
    public record Entry(Path file, String name, String size, String saved, String state, boolean warn, boolean opens) {
    }

    private FileColumns() {
    }

    /** Whether a save name is one file's: no separator, no control, nothing the system reserves, no trailing dot or space. */
    public static boolean nameAllowed(String name) {
        if (name.isBlank() || name.length() > LONGEST || name.endsWith(".") || name.endsWith(" ")) return false;
        for (char c : name.toCharArray()) if (c < ' ' || "/\\:*?\"<>|".indexOf(c) >= 0) return false;
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String lower = name.toLowerCase(Locale.ROOT);
        return !RESERVED.contains(stem.toUpperCase(Locale.ROOT)) && !name.equals("..") && !lower.equals(AUTOSAVE) && !lower.startsWith(AUTOSAVE_BLOCK) && !lower.endsWith(AUTOSAVE_OF);
    }

    /** The file of {@code name} in {@code folder}. */
    public static Path file(Path folder, String name) {
        return folder.resolve(name + ".json");
    }

    /** The autosave beside {@code name}'s file, or for a sheet that has none, {@code block}'s own: a second block's unnamed sheet must not overwrite the first's. A player's name holds neither form. */
    public static Path autosave(Path folder, @Nullable String name, String block) {
        return file(folder, name == null ? AUTOSAVE_BLOCK + block : name + AUTOSAVE_OF);
    }

    /** The folder's flowsheets, newest first. A file that does not read is listed, and says why. */
    public static List<Entry> entries(Path folder, HeatControlColumn.Texts t) throws IOException {
        var entries = new ArrayList<Entry>();
        if (!Files.isDirectory(folder)) return entries;
        try (var files = Files.list(folder)) {
            for (var file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted(Comparator.comparing(FileColumns::modified).reversed()).toList()) entries.add(entry(file, t));
        }
        return entries;
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException gone) {
            return 0;
        }
    }

    private static Entry entry(Path file, HeatControlColumn.Texts t) {
        String stem = file.getFileName().toString().replaceFirst("\\.json$", ""), when = WHEN.format(Instant.ofEpochMilli(modified(file)));
        String name = stem.equals(AUTOSAVE) ? t.get("flowsheet.file.autosave") : stem.startsWith(AUTOSAVE_BLOCK) ? t.get("flowsheet.file.autosave_block", stem.substring(AUTOSAVE_BLOCK.length(), Math.min(stem.length(), AUTOSAVE_BLOCK.length() + 8))) : stem.endsWith(AUTOSAVE_OF) ? t.get("flowsheet.file.autosave_of", stem.substring(0, stem.length() - AUTOSAVE_OF.length())) : stem;
        try {
            var sheet = FlowsheetFile.open(file);
            long unknown = sheet.units().stream().filter(u -> Tiles.symbol(u.type()) == null).count();
            String size = t.get(sheet.units().size() == 1 ? "flowsheet.file.unit" : "flowsheet.file.units", sheet.units().size());
            return new Entry(file, name, size, when, unknown == 0 ? t.get("flowsheet.file.ready") : t.get(unknown == 1 ? "flowsheet.file.look_one" : "flowsheet.file.look", unknown), unknown != 0, true);
        } catch (FlowsheetFailure refused) {
            String state = switch (refused.key()) {
                case "file.newer" -> "flowsheet.file.newer_state";
                case "file.older" -> "flowsheet.file.older_state";
                default -> "flowsheet.file.damaged_state";
            };
            return new Entry(file, name, "", when, t.get(state), true, false);
        } catch (IOException gone) {
            return new Entry(file, name, "", when, t.get("flowsheet.file.damaged_state"), true, false);
        }
    }

    public static Listing open(List<Entry> entries, int chosen, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Row>();
        for (var entry : entries) rows.add(new Row(new String[]{entry.name(), entry.size(), entry.saved(), entry.state()}, entry.warn()));
        return new Listing(t.get("flowsheet.file.open_title"), new String[]{t.get("flowsheet.file.name"), t.get("flowsheet.file.size"), t.get("flowsheet.file.saved"), t.get("flowsheet.file.state")}, new int[]{0, 170, 226, 330}, rows, chosen,
                new String[]{t.get("flowsheet.file.cancel"), t.get("flowsheet.file.folder"), t.get("flowsheet.file.from_clipboard"), t.get("flowsheet.file.open")}, 3);
    }

    /** What the unsaved-changes prompt says. */
    public static List<Change> unsaved(HeatControlColumn.Texts t) {
        return List.of(new Change(t.get("flowsheet.file.unsaved_kind"), t.get("flowsheet.file.unsaved_line"), true));
    }

    /** The stream templates a feed loads, each a mixture. */
    public static Listing templates(List<String[]> templates, int chosen, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Row>();
        for (var template : templates) rows.add(new Row(template, false));
        return new Listing(t.get("flowsheet.file.templates_title"), new String[]{t.get("flowsheet.file.name"), t.get("flowsheet.file.parts")}, new int[]{0, 460}, rows, chosen, new String[]{t.get("flowsheet.file.cancel"), t.get("flowsheet.file.load")}, 1);
    }

    /**
     * A stream template as a feed's door lists it: a mixture by mole, with its conditions where it has them, NaN where it has not.
     *
     * @param amounts     parts by component id
     * @param temperature K
     * @param pressure    Pa
     * @param flow        mol/s
     */
    public record StreamTemplate(String name, Map<String, Double> amounts, double temperature, double pressure, double flow) {
        public StreamTemplate {
            amounts = Map.copyOf(new LinkedHashMap<>(amounts));
        }
    }

    /** The stream templates a feed loads, with their conditions. */
    public static Listing streamTemplates(List<StreamTemplate> templates, int chosen, SheetUnits units, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Row>();
        for (var template : templates) {
            var conditions = new ArrayList<String>();
            if (!Double.isNaN(template.temperature())) conditions.add(units.t(template.temperature()) + " " + units.temperature().symbol());
            if (!Double.isNaN(template.pressure())) conditions.add(units.p(template.pressure()) + " " + units.pressure().symbol());
            if (!Double.isNaN(template.flow())) conditions.add(units.moles(template.flow()) + " " + units.molesUnit());
            rows.add(new Row(new String[]{template.name(), Integer.toString(template.amounts().size()), String.join(", ", conditions)}, false));
        }
        return new Listing(t.get("flowsheet.file.templates_title"), new String[]{t.get("flowsheet.file.name"), t.get("flowsheet.file.parts"), t.get("flowsheet.file.conditions")}, new int[]{0, 250, 300}, rows, chosen, new String[]{t.get("flowsheet.file.cancel"), t.get("flowsheet.file.load")}, 1);
    }

    /** A column template as the door lists it: {@code bundled} with the mod, or the player's own, in {@code file}. */
    public record ColumnTemplate(String name, V3ColumnTemplate template, boolean bundled, String file) {
    }

    /** The column templates a column's door loads. */
    public static Listing columnTemplates(List<ColumnTemplate> templates, int chosen, SheetUnits units, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Row>();
        for (var template : templates) {
            var column = template.template();
            String feed = column.designFeed() > 0 ? units.moles(column.designFeed() / 3.6) + " " + units.molesUnit() : "";
            rows.add(new Row(new String[]{template.name(), Integer.toString(column.trays()), connections(column, t), feed, t.get(template.bundled() ? "flowsheet.file.bundled" : "flowsheet.file.yours")}, false));
        }
        var off = new String[4];
        if (chosen < 0 || chosen >= templates.size()) off[1] = t.get("flowsheet.file.delete_choose");
        else if (templates.get(chosen).bundled()) off[1] = t.get("flowsheet.file.delete_bundled");
        return new Listing(t.get("flowsheet.file.column_templates_title"), new String[]{t.get("flowsheet.file.name"), t.get("flowsheet.file.trays"), t.get("flowsheet.file.connections"), t.get("flowsheet.file.design_feed"), t.get("flowsheet.file.from")},
                new int[]{0, 170, 212, 352, 460}, rows, chosen, new String[]{t.get("flowsheet.file.cancel"), t.get("flowsheet.file.delete"), t.get("flowsheet.file.save_current"), t.get("flowsheet.file.load")}, 3, off);
    }

    private static String connections(V3ColumnTemplate column, HeatControlColumn.Texts t) {
        var parts = new ArrayList<String>();
        if (!column.draws().isEmpty()) parts.add(t.get("flowsheet.file.connections_draws", column.draws().size()));
        if (!column.pumparounds().isEmpty()) parts.add(t.get("flowsheet.file.connections_pa", column.pumparounds().size()));
        if (!column.steam().isEmpty()) parts.add(t.get("flowsheet.file.connections_steam", column.steam().size()));
        return parts.isEmpty() ? t.get("flowsheet.file.connections_none") : String.join(", ", parts);
    }

    /** A line of what changed on opening a sheet: its kind, what it says, and whether it took something out of the solve. */
    public record Change(String kind, String text, boolean warn) {
    }

    /** The feed that holds most of a parked component. */
    private record Parked(String stream, double percent) {
    }

    /**
     * What changed on opening a sheet (sheet 17), by reading it against this version: nothing is written. A removed unit, what the package no longer has, each setting the file does not hold, and a
     * revision other than the sheet's.
     *
     * @param column a new column's door, which holds a column's settings
     */
    public static List<Change> changes(Flowsheet sheet, StreamProps.Fluids fluids, Supplier<Params> column, HeatControlColumn.Texts t) {
        var lines = new ArrayList<Change>();
        for (var unit : sheet.units()) if (Tiles.symbol(unit.type()) == null) lines.add(new Change(t.get("flowsheet.file.change_unit"), t.get("flowsheet.file.change_removed", unit.name(), Tiles.typeName(unit.type())), true));
        var parked = new LinkedHashMap<String, Parked>();
        var solids = new LinkedHashMap<String, String>();
        for (var stream : sheet.streams()) {
            if (stream.feed() == null) continue;
            double all = stream.feed().amounts().values().stream().mapToDouble(Double::doubleValue).sum();
            stream.feed().amounts().forEach((id, amount) -> {
                if (amount > 0 && !fluids.axis().contains(fluids.pack().canonicalId(id))) parked.merge(id, new Parked(stream.name(), 100 * amount / all), (a, b) -> a.percent() >= b.percent() ? a : b);
            });
            for (var solid : stream.feed().solids()) if (solid.massFlow() > 0 && !fluids.model().solids.materials().containsKey(solid.material())) solids.putIfAbsent(solid.material(), stream.name());
        }
        String component = t.get("flowsheet.file.change_component");
        parked.forEach((id, held) -> lines.add(new Change(component, t.get("flowsheet.file.change_parked", id, SheetUnits.number(2, held.percent()), held.stream()), true)));
        solids.forEach((id, stream) -> lines.add(new Change(component, t.get("flowsheet.file.change_solid", id, stream), true)));
        for (var unit : sheet.units()) {
            if (UnitTypes.model(unit.type()) == null) continue;
            for (var key : SheetEditor.defaults(unit.type(), column).json().keySet()) if (!unit.params().json().has(key)) lines.add(new Change(t.get("flowsheet.file.change_field"), t.get("flowsheet.file.change_setting", unit.name(), Tiles.typeName(key)), false));
        }
        if (!sheet.basis().revision().equals(fluids.pack().revision())) lines.add(new Change(t.get("flowsheet.file.change_data"), t.get("flowsheet.file.change_revision", sheet.basis().revision(), fluids.pack().revision()), false));
        return lines;
    }

    /** How many of {@code lines} the dialog's well holds above its foot, in {@code width} of text. */
    static int fits(List<Change> lines, Measure m, int width, int height) {
        int y = 0, shown = 0;
        for (var line : lines) {
            y += m.wrap(line.text(), width).size() * LINE + GAP;
            if (y > height) return shown;
            shown++;
        }
        return shown;
    }

    /** Draws a list dialog's page, under its title, and its keys, as the skin draws them. */
    public static void draw(Canvas c, Skin skin, Listing l, int w, int h) {
        skin.title(c, 10, 10, l.title());
        var box = skin.well(c, new Rect(5, 26, w - 10, h - 26 - 40));
        int y = box.y() + 4, x0 = box.x() + 4;
        for (int i = 0; i < l.head().length; i++) c.text(l.head()[i], x0 + l.at()[i], y, Palette.MUTED);
        c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        y += 14;
        for (int r = 0; r < l.rows().size(); r++, y += 14) {
            if (r % 2 == 1) c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
            if (r == l.chosen()) c.outline(box.x() + 1, y - 3, box.w() - 2, 14, Palette.TEXT);
            var row = l.rows().get(r);
            for (int i = 0; i < l.head().length; i++) {
                boolean last = i == l.head().length - 1;
                c.text(row.cells()[i], x0 + l.at()[i], y, i == 0 ? Palette.TEXT : last && row.warn() ? Palette.WARN : Palette.ACCENT);
            }
        }
    }

    /** The row at a point of the page, or -1. */
    public static int rowAt(Listing l, int w, int h, double x, double y) {
        var box = new Rect(5, 26, w - 10, h - 26 - 40);
        if (!box.contains(x, y)) return -1;
        int r = (int) Math.floor((y - box.y() - 15) / 14);
        return r >= 0 && r < l.rows().size() ? r : -1;
    }

    /** The changes dialog's lines. */
    public static void drawChanges(Canvas c, Skin skin, String title, List<Change> lines, String foot, int w, int h) {
        skin.title(c, 10, 10, title);
        var box = skin.well(c, new Rect(5, 26, w - 10, h - 26 - 40));
        var m = c.measure();
        int y = box.y() + 4, x0 = box.x() + 4, shown = fits(lines, m, box.w() - 80, box.h() - 22);
        for (int i = 0; i < shown; i++) {
            var line = lines.get(i);
            var wrapped = m.wrap(line.text(), box.w() - 80);
            if (i % 2 == 1) c.fill(box.x(), y - 2, box.right(), y + wrapped.size() * LINE, DialogColumns.BAND);
            c.text(line.kind(), x0, y, line.warn() ? Palette.WARN : Palette.MUTED);
            for (int j = 0; j < wrapped.size(); j++) c.text(wrapped.get(j), x0 + 68, y + j * LINE, Palette.TEXT);
            y += wrapped.size() * LINE + GAP;
        }
        if (shown < lines.size()) c.text("...", x0 + 68, y, Palette.MUTED);
        c.text(foot, x0, box.bottom() - 13, Palette.MUTED);
    }

    /** A placed key of a dialog's footer, or null. */
    public static @Nullable String keyAt(Listing l, int w, int h, double x, double y) {
        var slots = DialogColumns.footer(w, h, l.keys().length);
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).contains(x, y)) return Integer.toString(i);
        return null;
    }
}
