package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.flowsheet.metallurgy.SheetMetallurgy;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.integrity.Mechanism;
import com.wormzjl.icheme.science.integrity.Metallurgy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/**
 * The Materials tab (batch 2026-11-16-fouling-corrosion, WP6): each unit of the last rating rated for the four materials of
 * construction ({@link SheetMetallurgy}), Auto's choice or the player's, and the picked unit's choice on the door. The rows are
 * the rating's ({@code SimulatorRunner.Rated#materials}): every material is rated there once, so a choice changes the sheet's facts
 * only and needs no new rating. The glass is {@link MaterialsGlass}. Keys start {@code materials/}. Minecraft-free.
 */
public final class MaterialsPage {
    /** What the player picked. */
    public static final class State {
        public @Nullable String unit;
        /** The first row the unit table shows. */
        public int offset;
    }

    public static final String KEY = "materials/";

    private MaterialsPage() {
    }

    // ---- What the tab reads ----

    /** The rows on show: the last rating's, where it holds for the run on show; null before the first. */
    public static SheetMetallurgy.@Nullable Rows rows(SheetContext x) {
        var rated = x.host().rated();
        var last = x.host().last();
        return rated != null && last != null && rated.run() == last ? rated.materials() : null;
    }

    /** Whether the tab opens: as the Sizing tab, on a steady run (or a sheet already sized). */
    public static boolean open(SheetContext x) {
        return SizingPage.open(x);
    }

    /** The player's choice for {@code unit}, null for Auto. */
    public static @Nullable Metallurgy manual(SheetContext x, String unit) {
        return x.editor().sheet().sizing().unit(unit).material();
    }

    /** What {@code unit} is built of: the player's choice, else Auto's on the rows on show, else carbon steel (no rating yet). */
    public static Metallurgy chosen(SheetContext x, String unit) {
        var manual = manual(x, unit);
        if (manual != null) return manual;
        var rows = rows(x);
        var row = rows == null ? null : rows.rows().get(unit);
        return row == null ? Metallurgy.CARBON_STEEL : row.auto();
    }

    static @Nullable String unit(State state, SheetMetallurgy.Rows rows) {
        if (state.unit != null && rows.rows().containsKey(state.unit)) return state.unit;
        return rows.rows().isEmpty() ? null : rows.rows().keySet().iterator().next();
    }

    // ---- Words and numbers ----

    public static String material(SheetContext x, Metallurgy m) {
        return x.t().get("metallurgy." + m.name().toLowerCase(Locale.ROOT));
    }

    /** A mechanism's name, the same key the device pages use; a dash where nothing acts. */
    public static String mechanism(SheetContext x, @Nullable Mechanism m) {
        return m == null ? x.text("materials.no_mechanism") : x.t().get("mechanism." + m.name().toLowerCase(Locale.ROOT));
    }

    /** A rate, mm/y: three decimals below 1, never in powers of ten; a trace under 0.001 as "<0.001". */
    public static String rate(double rate) {
        if (rate > 0 && rate < .001) return "<0.001";
        return SheetUnits.number(rate < 1 ? 3 : rate < 100 ? 2 : 0, rate);
    }

    /** A life in process hours; "lasting" where it does not end. */
    public static String life(SheetContext x, double hours) {
        return Double.isFinite(hours) ? SizingPage.number(hours) : x.text("materials.lasting");
    }

    public static String cost(Metallurgy m) {
        return SheetUnits.number(0, m.cost());
    }

    // ---- The door ----

    public static List<Column.Row> rows(SheetContext x, State state) {
        var out = new ArrayList<Column.Row>();
        var rows = rows(x);
        if (rows == null) return out;
        String id = unit(state, rows);
        if (id == null) return out;
        var row = rows.rows().get(id);
        if (!row.wall()) {
            var trim = row.trim();
            out.add(new Column.Group(x.text("materials.trim"), x.text("materials.trim_hint"), List.of(new Column.Line(SizingPage.name(x, id), Palette.TEXT),
                    Door.meter("speed", x.text("materials.trim_speed"), SizingPage.number(trim.speed()), "m/s"),
                    Door.meter("trim_life", x.text("materials.life"), life(x, trim.life()), "h"))));
            return out;
        }
        boolean edits = x.editor().editable();
        var manual = manual(x, id);
        var built = row.chosen(manual);
        var keys = new ArrayList<Column.Row>();
        keys.add(new Column.Buttons(List.of(position("auto", x.text("sizing.auto"), manual == null, edits),
                position("manual", x.text("sizing.fixed"), manual != null, edits))));
        var materials = Metallurgy.values();
        for (int i = 0; i < materials.length; i += 2) {
            var pair = new ArrayList<Column.Button>();
            for (int k = i; k < Math.min(i + 2, materials.length); k++)
                pair.add(new Column.Button(KEY + "material/" + materials[k].name(), material(x, materials[k]), edits && materials[k] != manual,
                        x.text("materials.material_hint"), materials[k] == built));
            keys.add(new Column.Buttons(pair));
        }
        out.add(new Column.Group(x.text("materials.choice"), x.text("materials.choice_hint"), keys));
        var rated = row.rated(built);
        out.add(new Column.Group(x.text("materials.service"), SizingPage.name(x, id), List.of(
                Door.meter("temperature", x.text("materials.temperature"), x.units().t(row.temperature()), x.units().temperature().symbol()),
                Door.meter("rate", x.text("materials.rate"), rate(rated.rate()), "mm/y"),
                Door.meter("life", x.text("materials.life"), life(x, rated.life()), "h"),
                new Column.Line(mechanism(x, rated.mechanism()), SizingPage.colour(rated.lamp())))));
        return out;
    }

    private static Column.Button position(String key, String text, boolean latched, boolean edits) {
        return new Column.Button(KEY + key, text, edits && !latched, null, latched);
    }

    /** A key pressed on the door: Auto, Manual (at the material on show), or a material (Manual at it). One editor step each. */
    public static boolean press(SheetContext x, State state, String key) {
        if (!key.startsWith(KEY)) return false;
        key = key.substring(KEY.length());
        var rows = rows(x);
        String id = rows == null ? null : unit(state, rows);
        if (id == null || !rows.rows().get(id).wall()) return true;
        var facts = x.editor().sheet().sizing();
        var held = facts.unit(id);
        Metallurgy next;
        if (key.equals("auto")) next = null;
        else if (key.equals("manual")) next = chosen(x, id);
        else if (key.startsWith("material/")) next = Metallurgy.named(key.substring(9));
        else return true;
        if (next == null && !key.equals("auto")) return true;
        if (next != held.material()) x.editor().sizing(facts.with(id, held.withMaterial(next)), null);
        return true;
    }

    /** What the status line says on the tab: the rows, how many stand red or amber at their chosen material, and the cost index. */
    public static SheetState.Status status(SheetContext x) {
        var rows = rows(x);
        if (rows == null) return new SheetState.Status(x.text(x.host().rating() ? "sizing.rating" : "materials.none"), Palette.MUTED);
        int red = 0, amber = 0, walls = 0;
        double cost = 0;
        for (var row : rows.rows().values()) {
            if (!row.wall()) continue;
            walls++;
            var built = row.chosen(manual(x, row.unit()));
            cost += built.cost();
            var lamp = row.rated(built).lamp();
            if (lamp == Lamp.RED) red++;
            else if (lamp == Lamp.YELLOW) amber++;
        }
        String text = x.text("materials.status", walls, SheetUnits.number(0, cost), red, amber);
        return new SheetState.Status(text, red > 0 || amber > 0 ? Palette.WARN : Palette.ACCENT);
    }
}
