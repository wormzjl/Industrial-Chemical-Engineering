package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.jetbrains.annotations.Nullable;

import static com.wormzjl.icheme.science.equipment.UnitDesign.size;

/**
 * The equipment constructor's panel on the GUI kit (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 3): a door
 * {@link #PANEL} px wide at the left of the world and the ghost, its title (the kind, Change beside it on the design
 * page), its column of function groups from {@link DialogColumns#TOP}, the message display under them, and its push
 * buttons on the footer, Build the primary one. Sizes and places are set points the player steps
 * ({@link Column.Setting}: [−] and [+] before an LCD box), Shift stepping by five, said once in the hover of the page's
 * first group; selector positions are latched; sentences go to hovers. The rows of each page come from here, so the
 * gates and the render tool place what the screen draws. Texts are keys under {@code gui.icheme.}. No Minecraft classes.
 */
public final class ConstructorColumn {
    private ConstructorColumn() {
    }

    /** The constructor's keys under {@code gui.icheme.}. */
    public static final String KEY = "equipment_constructor.";
    /** The door's width; its column's left edge and width; the room the kind's name takes on the title plate before Change. */
    public static final int PANEL = 200, LEFT = 8, WIDTH = PANEL - 2 * LEFT, TITLE = 122;
    /** The key right of the title: Change on the design page, Back on the choice page. */
    public static final Rect TITLE_KEY = new Rect(PANEL - 60, 5, 50, 18);
    /** The order a side selector shows its sides in, by word: opposite sides side by side. */
    private static final List<String> SIDES = List.of("front", "back", "left", "right", "top", "bottom");

    /** The message display's text line under the groups, at {@code height}; its window lies 4 px round it ({@code Skin.status}). */
    public static Rect display(int height) {
        return new Rect(10, height - 47, PANEL - 20, 9);
    }

    /** The column's area on a door {@code height} high: from the top to the message display, or to the door's foot where a page shows none. */
    public static Rect area(int height, boolean display) {
        int bottom = display ? display(height).y() - 9 : height - 8;
        return new Rect(LEFT, DialogColumns.TOP, WIDTH, bottom - DialogColumns.TOP);
    }

    /** The footer's {@code count} push buttons sharing the door's width; none on the choice page. */
    public static List<Rect> footer(int height, int count) {
        return DialogColumns.footer(PANEL, height, count);
    }

    /** The kind's name on the title plate: the full one where it leaves room for Change, else the short one. */
    public static String title(String full, String shorter, Measure m) {
        return m.width(full) <= TITLE ? full : shorter;
    }

    /** The choice page's groups of kinds. */
    public enum Category {
        VESSELS, HEAT, MACHINES, REACTORS, SOLID_FUEL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the constructor builds, by group; a horizontal drum lies along or across the constructor's facing, every other is its placement form. */
    public enum Equipment {
        VERTICAL_DRUM(Category.VESSELS), HORIZONTAL_DRUM(Category.VESSELS), COLUMN(Category.VESSELS), FLOATING_ROOF_TANK(Category.VESSELS), SPHERE(Category.VESSELS),
        ADSORBER(Category.VESSELS), CYCLONE(Category.VESSELS),
        EXCHANGER(Category.HEAT), KETTLE(Category.HEAT), AIR_COOLER(Category.HEAT), VERTICAL_HEATER(Category.HEAT), CABIN_HEATER(Category.HEAT),
        PUMP(Category.MACHINES), COMPRESSOR(Category.MACHINES), CENTRIFUGAL_COMPRESSOR(Category.MACHINES), EJECTOR(Category.MACHINES),
        STEAM_TURBINE(Category.MACHINES), CONDENSING_TURBINE(Category.MACHINES),
        STIRRED_REACTOR(Category.REACTORS), TRICKLE_BED_REACTOR(Category.REACTORS), EBULLATED_BED_REACTOR(Category.REACTORS), FCC_CONVERTER(Category.REACTORS),
        DELAYED_COKER(Category.REACTORS),
        FLUIDIZED_BED_BOILER(Category.SOLID_FUEL), FLUIDIZED_BED_GASIFIER(Category.SOLID_FUEL), GRATE_BOILER(Category.SOLID_FUEL), FIXED_BED_GASIFIER(Category.SOLID_FUEL);

        public final Category category;

        Equipment(Category category) {
            this.category = category;
        }

        public static Equipment of(VesselPlacement.Form form) {
            return switch (form) {
                case VERTICAL -> VERTICAL_DRUM;
                case LENGTHWISE, CROSSWISE -> HORIZONTAL_DRUM;
                default -> valueOf(form.name());
            };
        }

        /** Its keys' name: {@code equipment.<key>}, {@code .short} and {@code .hint}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A page: its rows; its footer's push buttons, Build the last (the primary one); whether it shows the message display. */
    public record Page(List<Column.Row> rows, List<Column.Button> footer, boolean display) {
        public Page {
            rows = List.copyOf(rows);
            footer = List.copyOf(footer);
        }
    }

    /** A selector's position: latched where it stands, else a key that moves it there. */
    private static Column.Button position(String key, String text, boolean current, @Nullable String hint) {
        return new Column.Button(key, text, !current, hint, current);
    }

    /** A set point stepped by [−] and [+]: its key, its label's key (and the hint's, {@code .hint}), its value and unit. */
    private static Column.Setting step(String key, String label, String value, String unit, HeatControlColumn.Texts t) {
        return new Column.Setting(key, t.get(KEY + label), value, unit, t.get(KEY + label + ".hint"), true);
    }

    /** A set point shown, set elsewhere (a column's imported design). */
    private static Column.Setting shown(String key, String label, String value, String unit, HeatControlColumn.Texts t) {
        return new Column.Setting(key, t.get(KEY + label), value, unit, t.get(KEY + label + ".hint"), false);
    }

    private static Column.Button push(String key, String hint, HeatControlColumn.Texts t) {
        return new Column.Button(key, t.get(KEY + key), true, hint == null ? null : t.get(KEY + hint));
    }

    /** Build: it sends the draft; the screen lets it act only while the draft is buildable. */
    private static Column.Button build(HeatControlColumn.Texts t) {
        return new Column.Button("build", t.get(KEY + "build"), true, null);
    }

    /** Every button of {@code rows}, top to bottom (a setting's steppers aside). */
    public static List<Column.Button> buttons(List<? extends Column.Row> rows) {
        var out = new ArrayList<Column.Button>();
        for (var row : rows) {
            if (row instanceof Column.Group g) out.addAll(buttons(g.rows()));
            if (row instanceof Column.Buttons b) out.addAll(b.buttons());
        }
        return out;
    }

    /** The four sides' indices ({@link EquipmentShape#side}) in the order a selector shows them, by their words. */
    private static List<Integer> order(List<String> words) {
        return IntStream.range(0, words.size()).boxed().sorted(Comparator.comparingInt(i -> SIDES.indexOf(words.get(i)))).toList();
    }

    /** Which of {@code count} a selection stepper shows, on its LCD: the selected one counted from 1, 0 for none. */
    private static String which(int selected, int count) {
        return count == 0 ? "0" : Integer.toString(selected + 1);
    }

    /** A selection stepper's unit: how many there are ("/4"), so its LCD shows a number in segments. */
    private static String of(int count) {
        return count == 0 ? "" : "/" + count;
    }

    /** A side selector's keys in two rows of two, so each stays 40 px wide in a narrowed column. */
    private static List<Column.Row> pairs(List<Column.Button> sides) {
        var rows = new ArrayList<Column.Row>();
        for (int i = 0; i < sides.size(); i += 2) rows.add(new Column.Buttons(sides.subList(i, Math.min(i + 2, sides.size()))));
        return rows;
    }

    // ---- The choice page ----

    /**
     * What to build: the groups as a latched selector, three a row, then the shown group's kinds, one a row, the draft's
     * latched. The draft's kind still acts: pressed, it returns to its design, as Back right of the title does (owner
     * report 2026-10-28: the green kind could not be pressed). A kind's hover gives its full name and what it is. Keys:
     * {@code category:<category>}, {@code kind:<equipment>}.
     */
    public static Page choose(Category shown, Equipment current, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        var categories = Category.values();
        for (int i = 0; i < categories.length; i += 3) {
            var row = new ArrayList<Column.Button>();
            for (int j = i; j < Math.min(i + 3, categories.length); j++) {
                String key = KEY + "category." + categories[j].key();
                row.add(position("category:" + categories[j].key(), t.get(key), categories[j] == shown, t.get(key + ".hint")));
            }
            rows.add(new Column.Buttons(row));
        }
        rows.add(new Column.Gap(4));
        for (var e : Equipment.values()) {
            if (e.category != shown) continue;
            String key = KEY + "equipment." + e.key();
            rows.add(new Column.Buttons(List.of(new Column.Button("kind:" + e.key(), t.get(key + ".short"), true, t.get(key) + "\n" + t.get(key + ".hint"), e == current))));
        }
        return new Page(List.of(new Column.Group(t.get(KEY + "group.equipment"), t.get(KEY + "group.equipment.hint"), rows)), List.of(), false);
    }

    // ---- The column templates ----

    /** A column template as its page lists it: its name (cut to the column by the screen), whether it builds a column, its hover. */
    public record Template(String name, boolean usable, String hint) {
    }

    /**
     * A column's templates (owner request 2026-10-28: "it should allow default templates"): one key a row, the bundled
     * templates first, then the player's; a template that builds no column is off, its hover saying why. Then a warning
     * where files could not be read. Back, right of the title, returns to the design. Keys: {@code template:<index>}.
     */
    public static Page templates(List<Template> templates, int unreadable, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        for (int i = 0; i < templates.size(); i++) {
            var template = templates.get(i);
            rows.add(new Column.Buttons(List.of(new Column.Button("template:" + i, template.name(), template.usable(), template.hint()))));
        }
        if (templates.isEmpty()) rows.add(new Column.Line(t.get(KEY + "templates.none"), Palette.MUTED));
        if (unreadable > 0) rows.add(new Column.Line(t.get(KEY + "templates.unreadable", unreadable), Palette.WARN));
        return new Page(List.of(new Column.Group(t.get(KEY + "group.templates"), t.get(KEY + "group.templates.hint"), rows)), List.of(), false);
    }

    // ---- The design page ----

    /**
     * A bottom outlet's sides: the quarter turns from the front its form allows (bits), the one it faces, and each turn's
     * word as seen from the constructor ({@code side.<word>}).
     */
    public record Outlet(int allowed, int current, List<String> words) {
        public Outlet {
            words = List.copyOf(words);
            if (words.size() != EquipmentShape.CONNECTION_SIDES) throw new IllegalArgumentException("A word for each side");
        }
    }

    /** A column's imported design as the column calculator counts it: its diameter, its trays with the condenser and reboiler, its feed tray from the condenser. */
    public record Imported(double diameter, int trays, int feed) {
    }

    /**
     * The draft as the design page shows it: its form, sizes (diameter or a unit's size, tangent length or a unit's second
     * size, support), place, an air cooler's bank, a column's design (null for another form), the bottom outlet's sides,
     * and the reach of a column's import in blocks.
     */
    public record Design(VesselPlacement.Form form, double diameter, int length, int support, int forward, int right, int up, ProcessUnit.Bank bank,
                         @Nullable Imported column, Outlet outlet, int reach) {
    }

    /**
     * The design page: Size (the form's sizes and its support; a column's imported diameter, trays and feed tray shown,
     * with Import and Templates), a unit's Bottom outlet where its design turns it, then Place (a lying drum's axis, then forward, right
     * and up). Footer: a drum's or a column's Nozzles, Paint, Build. Keys: the settings {@code size}, {@code length},
     * {@code support}, {@code parallel}, {@code series}, {@code forward}, {@code right}, {@code up} (their steppers
     * {@code <key>-} and {@code <key>+}); a column's {@code import} and {@code templates}; {@code axis:lengthwise}, {@code axis:crosswise};
     * {@code outlet:<turn>}.
     */
    public static Page design(Design in, HeatControlColumn.Texts t) {
        var form = in.form();
        var equipment = Equipment.of(form);
        var size = new ArrayList<Column.Row>();
        String length = Integer.toString(in.length()), support = Integer.toString(in.support());
        switch (equipment) {
            case VERTICAL_DRUM, HORIZONTAL_DRUM -> {
                size.add(step("size", "diameter", size(in.diameter()), "m", t));
                size.add(step("length", "tangent_length", length, "m", t));
                size.add(step("support", equipment == Equipment.VERTICAL_DRUM ? "skirt" : "saddles", support, "m", t));
            }
            case COLUMN -> {
                // The imported design's diameter, trays and feed: shown, not edited (plan 2026-10-26, section 4.5).
                var c = in.column() != null ? in.column() : new Imported(in.diameter(), 0, 0);
                size.add(shown("size", "diameter", size(c.diameter()), "m", t));
                size.add(shown("trays", "trays", Integer.toString(c.trays()), "", t));
                size.add(shown("feed", "feed", Integer.toString(c.feed()), "", t));
                size.add(step("support", "skirt", support, "m", t));
                size.add(new Column.Buttons(List.of(new Column.Button("import", t.get(KEY + "import"), true, t.get(KEY + "import.hint", in.reach())),
                        new Column.Button("templates", t.get(KEY + "templates"), true, t.get(KEY + "templates.hint")))));
            }
            case AIR_COOLER -> {
                // A bay's width × length in m, without spaces: it stands whole in the box.
                size.add(step("size", "bay", size(in.diameter()) + "×" + in.length(), "m", t));
                size.add(step("parallel", "parallel", Integer.toString(in.bank().parallel()), "", t));
                size.add(step("series", "series", Integer.toString(in.bank().series()), "", t));
                size.add(step("support", "bundle_height", support, "m", t));
            }
            default -> {
                // A unit's rows, as its design names them: its size, its second size and its support where it has them.
                var design = UnitDesign.of(form.kind());
                size.add(step("size", design.sizeRow(), size(in.diameter()), "m", t));
                if (design.lengthRow() != null) size.add(step("length", design.lengthRow(), length, design.lengthInMetres() ? "m" : "", t));
                if (design.supportRow() != null) size.add(step("support", design.supportRow(), support, "m", t));
            }
        }
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(t.get(KEY + "group.size"), t.get(KEY + "shift"), size));
        // A bottom outlet leaving through the skirt turns where the design lets it (a trickle-bed reactor's effluent).
        if (form.designed() && Integer.bitCount(in.outlet().allowed()) > 1) rows.add(outlet(in.outlet(), t));
        var place = new ArrayList<Column.Row>();
        if (equipment == Equipment.HORIZONTAL_DRUM)
            place.add(new Column.Buttons(List.of(
                    position("axis:lengthwise", t.get(KEY + "form.lengthwise"), form == VesselPlacement.Form.LENGTHWISE, t.get(KEY + "axis.hint")),
                    position("axis:crosswise", t.get(KEY + "form.crosswise"), form == VesselPlacement.Form.CROSSWISE, t.get(KEY + "axis.hint")))));
        place.add(step("forward", "forward", Integer.toString(in.forward()), "", t));
        place.add(step("right", "right", Integer.toString(in.right()), "", t));
        place.add(step("up", "up", Integer.toString(in.up()), "", t));
        rows.add(new Column.Group(t.get(KEY + "group.place"), t.get(KEY + "group.place.hint"), place));
        var footer = new ArrayList<Column.Button>();
        if (!form.designed()) footer.add(push("nozzles", form.column() ? "column_nozzles.hint" : "connections.hint", t));
        footer.add(push("paint", "paint.hint", t));
        footer.add(build(t));
        return new Page(rows, footer, true);
    }

    /** The Bottom outlet group: its four sides latched where it faces, those its form does not allow switched off. Keys {@code outlet:<turn>}. */
    private static Column.Group outlet(Outlet o, HeatControlColumn.Texts t) {
        var buttons = new ArrayList<Column.Button>();
        for (int q : order(o.words())) {
            boolean current = q == o.current();
            buttons.add(new Column.Button("outlet:" + q, t.get(KEY + "side." + o.words().get(q)), !current && (o.allowed() >> q & 1) != 0, null, current));
        }
        return new Column.Group(t.get(KEY + "outlet"), t.get(KEY + "outlet.hint"), pairs(buttons));
    }

    // ---- A drum's side connections ----

    /**
     * A drum's side connections as the page shows them: how many and the selected one; its side ({@link
     * EquipmentShape#side}) and each side's word; its place (along a lying drum's axis, else its height) as shown, in m;
     * its offset across the shell and the way it runs (its word, null at 0); whether Add finds a place; a lying drum's
     * boot (room for one, and whether it has one); the bottom outlet's sides (a standing drum's).
     */
    public record Connections(int count, int selected, int side, List<String> words, String station, boolean horizontal, int offset, @Nullable String way,
                              boolean addable, boolean bootRoom, boolean boot, Outlet outlet) {
        public Connections {
            words = List.copyOf(words);
        }
    }

    /**
     * The drum's Nozzles page: Side connections (which one, Add and Remove); while it has one, its Faces (a latched
     * selector) and Position (its place along or up the shell, its offset across); a lying drum's Boot, or a standing
     * drum's Bottom outlet where it turns. Footer: Back, Build. Keys: the settings {@code connection}, {@code station},
     * {@code offset}; {@code add}, {@code remove}, {@code side:<side>}, {@code boot:off}, {@code boot:on},
     * {@code outlet:<turn>}.
     */
    public static Page connections(Connections in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        String hint = String.join("\n", t.get(KEY + "connections.hint"), t.get(KEY + "connection.size.hint"), t.get(KEY + "connection.port.hint"), t.get(KEY + "shift"));
        rows.add(new Column.Group(t.get(KEY + "connections"), hint, List.of(
                step("connection", "connection", which(in.selected(), in.count()), of(in.count()), t),
                new Column.Buttons(List.of(new Column.Button("add", t.get(KEY + "add"), in.addable(), t.get(KEY + "add.hint", EquipmentShape.MAX_CONNECTIONS)),
                        new Column.Button("remove", t.get(KEY + "remove"), in.count() > 0, t.get(KEY + "remove.hint")))))));
        if (in.count() > 0) {
            var sides = new ArrayList<Column.Button>();
            for (int i : order(in.words())) sides.add(position("side:" + i, t.get(KEY + "side." + in.words().get(i)), i == in.side(), null));
            rows.add(new Column.Group(t.get(KEY + "faces"), t.get(KEY + "faces.hint"), pairs(sides)));
            // The offset's label says which way it runs, as the sides do.
            String offset = in.offset() == 0 || in.way() == null ? "offset" : "offset." + in.way();
            rows.add(new Column.Group(t.get(KEY + "group.position"), t.get(KEY + "group.position.hint"), List.of(
                    step("station", in.horizontal() ? "along" : "height", in.station(), "m", t),
                    new Column.Setting("offset", t.get(KEY + offset), Integer.toString(Math.abs(in.offset())), "m", t.get(KEY + "offset.hint"), true))));
        }
        if (in.horizontal())
            rows.add(new Column.Group(t.get(KEY + "boot"), t.get(KEY + "boot.hint"), List.of(new Column.Buttons(List.of(
                    position("boot:off", t.get(KEY + "boot.off"), !in.boot(), null),
                    new Column.Button("boot:on", t.get(KEY + (in.bootRoom() ? "boot.on" : "boot.none")), !in.boot() && in.bootRoom(), null, in.boot()))))));
        else if (Integer.bitCount(in.outlet().allowed()) > 1) rows.add(outlet(in.outlet(), t));
        return new Page(rows, List.of(push("back", "back.hint", t), build(t)), true);
    }

    // ---- A column's side nozzles ----

    /**
     * A column's side nozzle as its page shows it: its role (its key under {@code nozzle.role.}) and number; whether it
     * found a row by its tray and its height there as shown, in m; where it stands, in words; whether the player turned
     * it; the side it faces and its own side ({@link EquipmentShape#side}).
     */
    public record Nozzle(String role, int number, boolean placed, String height, UnitDesign.Line where, boolean turned, int side, int home) {
    }

    /** A column's side nozzles: how many, the shown one's index and the shown one (null for none), each side's word, how many the player turned. */
    public record Nozzles(int count, int index, @Nullable Nozzle shown, List<String> words, int turned) {
        public Nozzles {
            words = List.copyOf(words);
        }
    }

    /**
     * A column's Nozzles page: Side nozzles (which one; its name and where it stands, as lines, so the labels stay short
     * and beside their boxes in a narrowed column; its height); while it has one, its Faces (Auto and the side it takes
     * by itself, the four sides latched where the player turned it). Footer: Back, All auto (every nozzle, so the page's
     * own key), Build. Keys: the setting {@code nozzle}, the meter {@code height}; {@code face:auto},
     * {@code face:<side>}, {@code reset}.
     */
    public static Page nozzles(Nozzles in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        var n = in.shown();
        var first = new ArrayList<Column.Row>();
        first.add(new Column.Setting("nozzle", t.get(KEY + "nozzle"), which(in.index(), in.count()), of(in.count()), t.get(KEY + "nozzle.hint"), true));
        if (n != null) {
            first.add(new Column.Line(t.get(KEY + "nozzle.role." + n.role(), n.number()), Palette.TEXT));
            first.add(new Column.Line(t.get(KEY + n.where().key(), n.where().args()), Palette.MUTED));
            first.add(new Column.Reading("height", t.get(KEY + "nozzle_height"), n.placed() ? n.height() : "-", "m", t.get(KEY + "nozzle_height.hint")));
        }
        rows.add(new Column.Group(t.get(KEY + "column_nozzles"), String.join("\n", t.get(KEY + "column_nozzles.hint"), t.get(KEY + "nozzle.where.hint"), t.get(KEY + "shift")),
                first));
        if (n != null) {
            var words = in.words();
            String auto = t.get(KEY + "nozzle.auto", t.get(KEY + "side." + words.get(n.turned() ? n.home() : n.side())));
            String home = t.get(KEY + "nozzle.home", t.get(KEY + "side." + words.get(n.home()))) + "\n" + t.get(KEY + "nozzle.home.hint");
            var sides = new ArrayList<Column.Button>();
            for (int i : order(words)) sides.add(position("face:" + i, t.get(KEY + "side." + words.get(i)), n.turned() && n.side() == i, null));
            var faces = new ArrayList<Column.Row>();
            faces.add(new Column.Buttons(List.of(position("face:auto", auto, !n.turned(), home))));
            faces.addAll(pairs(sides));
            rows.add(new Column.Group(t.get(KEY + "column_faces"), t.get(KEY + "column_faces.hint"), faces));
        }
        var reset = new Column.Button("reset", t.get(KEY + "nozzle.reset"), in.turned() > 0, t.get(KEY + "nozzle.reset.hint", in.turned()));
        return new Page(rows, List.of(push("back", "back.hint", t), reset, build(t)), true);
    }

    // ---- The paint ----

    /**
     * The Paint page: the channel edited (Primary or Secondary, latched), the palette in the group's gap
     * ({@link DialogColumns#palette}), a custom colour as RRGGBB, the defaults. What keeps its own colour is in the
     * group's hover. Footer: Back, Build. Keys: {@code channel:0}, {@code channel:1}, the field {@code custom},
     * {@code default}.
     */
    public static Page paint(int channel, HeatControlColumn.Texts t) {
        var rows = List.<Column.Row>of(new Column.Group(t.get(KEY + "paint"), t.get(KEY + "paint.hint") + "\n" + t.get(KEY + "paint.unpainted.hint"), List.of(
                new Column.Buttons(List.of(position("channel:0", t.get(KEY + "paint.body"), channel == 0, t.get(KEY + "paint.body.hint")),
                        position("channel:1", t.get(KEY + "paint.structure"), channel == 1, t.get(KEY + "paint.structure.hint")))),
                new Column.Gap(DialogColumns.SWATCH_ROWS * (DialogColumns.SWATCH + DialogColumns.SWATCH_GAP)),
                new Column.Field("custom", t.get(KEY + "custom"), "RGB", t.get(KEY + "custom.hint")),
                new Column.Buttons(List.of(new Column.Button("default", t.get(KEY + "paint.default"), true, t.get(KEY + "paint.default.hint")))))));
        return new Page(rows, List.of(push("back", "back.hint", t), build(t)), true);
    }
}
