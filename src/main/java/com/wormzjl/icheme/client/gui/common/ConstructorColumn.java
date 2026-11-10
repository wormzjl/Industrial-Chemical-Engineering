package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.SentDesign;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
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
    /**
     * The design page's title with the design's name first (N1), through {@code named} (a line "%s · %s"): the full kind where it
     * fits, else the short one, else that cut to the title's room; the kind alone for no name.
     */
    public static String title(String name, String full, String shorter, Measure m, java.util.function.BiFunction<String, String, String> named) {
        if (name.isEmpty()) return title(full, shorter, m);
        String whole = named.apply(name, full);
        if (m.width(whole) <= TITLE) return whole;
        String brief = named.apply(name, shorter);
        return m.width(brief) <= TITLE ? brief : m.cut(brief, TITLE);
    }

    public static String title(String full, String shorter, Measure m) {
        return m.width(full) <= TITLE ? full : shorter;
    }

    /** The choice page's groups of kinds. */
    public enum Category {
        VESSELS, HEAT, MACHINES, REACTORS, SOLID_FUEL,
        // The vacuum sub-category (batch 2026-11-10-vacuum-pump, owner V4 A): the steam ejector and the vacuum pump casings.
        VACUUM,
        // The power category (batch 2026-11-10-prime-movers, owner P3 A): the steam turbine and the generator moved in, the engines and gas turbines.
        POWER;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the constructor builds, by group; a horizontal drum lies along or across the constructor's facing, every other is its placement form. */
    public enum Equipment {
        VERTICAL_DRUM(Category.VESSELS), HORIZONTAL_DRUM(Category.VESSELS), COLUMN(Category.VESSELS), FLOATING_ROOF_TANK(Category.VESSELS), SPHERE(Category.VESSELS),
        ADSORBER(Category.VESSELS), CYCLONE(Category.VESSELS),
        EXCHANGER(Category.HEAT), KETTLE(Category.HEAT), AIR_COOLER(Category.HEAT), VERTICAL_HEATER(Category.HEAT), CABIN_HEATER(Category.HEAT), INDUCED_DRAFT_COOLING_TOWER(Category.HEAT), NATURAL_DRAFT_COOLING_TOWER(Category.HEAT), FLARE_STACK(Category.HEAT), BATCH_STILL(Category.HEAT), STEEL_STACK(Category.HEAT), BRICK_CHIMNEY(Category.HEAT),
        PUMP(Category.MACHINES), COMPRESSOR(Category.MACHINES), CENTRIFUGAL_COMPRESSOR(Category.MACHINES), EJECTOR(Category.VACUUM),
        STEAM_TURBINE(Category.POWER), MOTOR(Category.MACHINES), GENERATOR(Category.POWER), GEARBOX(Category.MACHINES),
        STIRRED_REACTOR(Category.REACTORS), TRICKLE_BED_REACTOR(Category.REACTORS), EBULLATED_BED_REACTOR(Category.REACTORS), FCC_CONVERTER(Category.REACTORS),
        DELAYED_COKER(Category.REACTORS),
        FLUIDIZED_BED_BOILER(Category.SOLID_FUEL), FLUIDIZED_BED_GASIFIER(Category.SOLID_FUEL), GRATE_BOILER(Category.SOLID_FUEL), FIXED_BED_GASIFIER(Category.SOLID_FUEL),
        LIQUID_RING_PUMP(Category.VACUUM), DRY_SCREW_PUMP(Category.VACUUM),
        COMBUSTION_ENGINE(Category.POWER), GAS_TURBINE(Category.POWER);

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

    /** The physical removal target; null means no structure crosses the placement line. */
    public record Removal(@Nullable String target, String position, int cells, boolean waiting, @Nullable String message, boolean warn) {
        public Removal(String target, String position, int cells, boolean waiting) { this(target, position, cells, waiting, null, false); }
    }

    public static Page withRemoval(Page page, Removal target, HeatControlColumn.Texts t) {
        var rows = new ArrayList<>(page.rows());
        var removal = new ArrayList<Column.Row>();
        removal.add(new Column.Paragraph(target.target() == null ? t.get(KEY + "deconstruct.none") : target.target(), Palette.TEXT));
        if (target.target() != null) removal.add(new Column.Paragraph(t.get(KEY + "deconstruct.target", target.position(), target.cells()), Palette.MUTED));
        removal.add(new Column.Buttons(List.of(new Column.Button("deconstruct", t.get(KEY + "deconstruct"),
                target.target() != null && !target.waiting(), t.get(KEY + "deconstruct.hint")))));
        if (target.message() != null) removal.add(new Column.Paragraph(target.message(), target.warn() ? Palette.WARN : Palette.ACCENT));
        rows.add(new Column.Group(t.get(KEY + "group.deconstruct"), t.get(KEY + "deconstruct.hint"), removal));
        return new Page(rows, page.footer(), page.display());
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
     * report 2026-10-28: the green kind could not be pressed). A kind's hover gives its full name and what it is. After the
     * last group, Import (batch 2026-11-02-process-simulator, S5): a process simulator's design within {@code reach} blocks, which sets the
     * kind with its sizes. Keys: {@code category:<category>}, {@code kind:<equipment>}, {@code import}.
     */
    public static Page choose(Category shown, Equipment current, int reach, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        var categories = Category.values();
        for (int i = 0; i < categories.length; i += 3) {
            var row = new ArrayList<Column.Button>();
            for (int j = i; j < Math.min(i + 3, categories.length); j++) {
                String key = KEY + "category." + categories[j].key();
                row.add(position("category:" + categories[j].key(), t.get(key), categories[j] == shown, t.get(key + ".hint")));
            }
            // Import takes the last row's free place: a row of its own would scroll the largest groups.
            if (i + 3 >= categories.length) row.add(importKey(reach, t));
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

    // ---- The designs a process simulator holds ----

    /** A design as the Import page lists it: its name and count (cut to the column by the screen), and its hover: what it builds and at what sizes. */
    public record Listed(String name, String hint) {
    }

    /**
     * {@code sent} as the Import page lists it: its tag, a block member's own name after it ({@code flowsheet.sizing.member.*}), and its count
     * where more than one stands on its row; the hover says what it builds, a drum standing or lying, at what sizes, and how many to build.
     * The calculator's untagged design is named as such.
     */
    public static Listed listed(SentDesign sent, HeatControlColumn.Texts t) {
        String name = sent.tag().isEmpty() ? t.get(KEY + "import.saved") : label(sent, t);
        String shown = sent.count() > 1 ? t.get(KEY + "import.count", name, sent.count()) : name;
        var shape = sent.shape();
        var column = sent.column();
        String hint = column != null ? t.get(KEY + "import.column_hint", name, UnitDesign.number(column.diameter()), column.trays().trays() + 2)
                : t.get(KEY + "import.unit_hint", name, kind(shape, t), UnitDesign.number(shape.diameter()), UnitDesign.number(shape.tangentLength()), sent.count());
        return new Listed(shown, hint);
    }

    /**
     * The name {@code sent} gives the structure it builds (N1): its tag, a block member's own name after it, as the Import page lists it
     * without its count; empty for the calculator's untagged design.
     */
    public static String label(SentDesign sent, HeatControlColumn.Texts t) {
        return sent.tag().isEmpty() ? "" : sent.member().isEmpty() ? sent.tag() : t.get(KEY + "import.member", sent.tag(), member(sent.member(), t));
    }

    /** A block member's own name: {@code condenser}, {@code pa 2 cooler}. */
    private static String member(String member, HeatControlColumn.Texts t) {
        var pa = SheetSizing.PumparoundMember.of(member);
        if (pa != null) return t.get("flowsheet.sizing.member." + pa.key(), pa.pumparound());
        return t.get("flowsheet.sizing.member." + member.replace(' ', '_'));
    }

    /** What a shape builds, as the constructor names it: a drum standing or lying, else its kind's title. */
    private static String kind(EquipmentShape shape, HeatControlColumn.Texts t) {
        if (shape.kind() == EquipmentShape.Kind.DRUM) return t.get(KEY + "equipment." + (shape.orientation() == EquipmentShape.Orientation.VERTICAL ? "vertical_drum" : "horizontal_drum"));
        return t.get("vessel.title." + shape.kind().name().toLowerCase(Locale.ROOT));
    }

    /**
     * The designs the nearest process simulator holds (batch 2026-11-02-process-simulator, S5): one key a row, the column door's saved design
     * first, then what Send to block stored, each lands on the draft. Back, right of the title, returns to the design. Keys:
     * {@code design:<index>}.
     */
    public static Page imports(List<Listed> designs, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        for (int i = 0; i < designs.size(); i++) rows.add(new Column.Buttons(List.of(new Column.Button("design:" + i, designs.get(i).name(), true, designs.get(i).hint()))));
        if (designs.isEmpty()) rows.add(new Column.Line(t.get(KEY + "imports.none"), Palette.MUTED));
        return new Page(List.of(new Column.Group(t.get(KEY + "group.imports"), t.get(KEY + "group.imports.hint"), rows)), List.of(), false);
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
     * the reach of a column's import in blocks, and a machine's design point ({@link MachineDuty#NONE} for another form).
     */
    public record Design(VesselPlacement.Form form, double diameter, int length, int support, int forward, int right, int up, ProcessUnit.Bank bank,
                         @Nullable Imported column, Outlet outlet, int reach,com.wormzjl.icheme.science.equipment.TowerBank towerBank, MachineDuty duty,com.wormzjl.icheme.science.equipment.ExchangerType exchangerType,com.wormzjl.icheme.science.equipment.BatchStillOptions still) {
        /** The draft of a form with the default still options. */
        public Design(VesselPlacement.Form form,double diameter,int length,int support,int forward,int right,int up,ProcessUnit.Bank bank,@Nullable Imported column,Outlet outlet,int reach,com.wormzjl.icheme.science.equipment.TowerBank towerBank,MachineDuty duty,com.wormzjl.icheme.science.equipment.ExchangerType exchangerType) {
            this(form,diameter,length,support,forward,right,up,bank,column,outlet,reach,towerBank,duty,exchangerType,com.wormzjl.icheme.science.equipment.BatchStillOptions.DEFAULT);
        }
        public Design(VesselPlacement.Form form,double diameter,int length,int support,int forward,int right,int up,ProcessUnit.Bank bank,@Nullable Imported column,Outlet outlet,int reach,com.wormzjl.icheme.science.equipment.TowerBank towerBank,MachineDuty duty) {
            this(form,diameter,length,support,forward,right,up,bank,column,outlet,reach,towerBank,duty,com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD);
        }
        /** The draft of a form without a design point. */
        public Design(VesselPlacement.Form form, double diameter, int length, int support, int forward, int right, int up, ProcessUnit.Bank bank,
                      @Nullable Imported column, Outlet outlet, int reach, com.wormzjl.icheme.science.equipment.TowerBank towerBank) {
            this(form, diameter, length, support, forward, right, up, bank, column, outlet, reach, towerBank, MachineDuty.NONE);
        }
    }

    /**
     * The design page: Size (the form's sizes and its support; a column's imported diameter, trays and feed tray shown,
     * with Import and Templates), a unit's Bottom outlet where its design turns it, then Place (a lying drum's axis, then forward, right
     * and up); every other form imports from the choice page. A machine shows its design point instead of Size (option A,
     * owner pick 2026-11-04, {@link #machine}). Footer: Paint and Build; the main door overload inserts nozzle controls before Place. Keys: the settings
     * {@code size}, {@code length}, {@code support}, {@code parallel}, {@code series}, {@code forward}, {@code right}, {@code up} (their
     * steppers {@code <key>-} and {@code <key>+}); a column's {@code import} and {@code templates}; {@code axis:lengthwise}, {@code axis:crosswise};
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
                size.add(new Column.Buttons(List.of(importKey(in.reach(), t),
                        new Column.Button("templates", t.get(KEY + "templates"), true, t.get(KEY + "templates.hint")))));
            }
            case INDUCED_DRAFT_COOLING_TOWER, NATURAL_DRAFT_COOLING_TOWER -> {
                var design=UnitDesign.of(form.kind());boolean fan=form.kind()==EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER;
                size.add(step("size",design.sizeRow(),size(in.diameter()),"m",t));
                if(fan){
                    size.add(step("length",design.lengthRow(),length,"m",t));
                    size.add(step("tower_columns","tower_columns",Integer.toString(in.towerBank().columns()),"",t));
                    size.add(step("tower_rows","tower_rows",Integer.toString(in.towerBank().rows()),"",t));
                }
                var towerShape=EquipmentShape.unit(form.kind(),in.diameter(),in.length(),EquipmentShape.Heading.NORTH,0)
                    .withTowerBank(fan?in.towerBank():com.wormzjl.icheme.science.equipment.TowerBank.ONE);
                var tower=towerShape.coolingTower();
                size.add(new Column.Paragraph(t.get(KEY+"tower_summary",tower.cells(),towerShape.sizeX(),towerShape.sizeY(),towerShape.sizeZ(),
                    UnitDesign.number(tower.area()),UnitDesign.number(tower.referenceDuty()/1e6),UnitDesign.number(tower.fanPower(1)/1000)),Palette.MUTED));
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
                if (design.hasDuty()) break;
                size.add(step("size", design.sizeRow(), design.sizeText(in.diameter()), design.sizeUnit(), t));
                if (design.lengthRow() != null) size.add(step("length", design.lengthRow(), length, design.lengthInMetres() ? "m" : "", t));
                if (design.supportRow() != null) size.add(step("support", design.supportRow(), support, "m", t));
            }
        }
        var rows = new ArrayList<Column.Row>();
        if(form.kind()==EquipmentShape.Kind.EXCHANGER) {
            var types=new ArrayList<Column.Button>();
            for(var type:com.wormzjl.icheme.science.equipment.ExchangerType.CATALOG)types.add(position("exchanger:"+type.name(),t.get("exchanger.type."+type.key()),type==in.exchangerType(),t.get("exchanger.type_hint")));
            rows.add(new Column.Group(t.get(KEY+"group.subtype"),null,List.of(new Column.Buttons(types))));
        }
        if (form.designed() && UnitDesign.of(form.kind()).hasDuty()) rows.addAll(machine(in, t));
        else rows.add(new Column.Group(t.get(KEY + "group.size"), t.get(KEY + "shift"), size));
        if (form.kind() == EquipmentShape.Kind.BATCH_STILL) rows.addAll(still(in.still(), t));
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
        footer.add(push("paint", "paint.hint", t));
        footer.add(build(t));
        return new Page(rows, footer, true);
    }

    /** The same design door includes its nozzle editor before placement; one footer and scroll view own all controls. */
    public static Page design(Design in,@Nullable Connections connections,@Nullable Nozzles nozzles,HeatControlColumn.Texts t){
        var base=design(in,t);if(in.form().designed())return base;
        Page editor=in.form().column()?ConstructorColumn.nozzles(java.util.Objects.requireNonNull(nozzles),t):ConstructorColumn.connections(java.util.Objects.requireNonNull(connections),t);
        var rows=new ArrayList<>(base.rows());int beforePlacement=rows.size()-1;rows.addAll(beforePlacement,editor.rows());
        if(in.form().column()){
            var reset=editor.footer().stream().filter(button->button.key().equals("reset")).findFirst().orElseThrow();
            var group=(Column.Group)rows.get(beforePlacement);var controls=new ArrayList<>(group.rows());controls.add(new Column.Buttons(List.of(reset)));
            rows.set(beforePlacement,new Column.Group(group.title(),group.hint(),controls));
        }
        return new Page(rows,base.footer(),base.display());
    }

    /**
     * A batch still's options (batch 2026-11-09-batch-distiller, W6; shape fields 11): Heater (solid, liquid, electric:
     * latched positions), Condenser (built-in or none) and the column's Stages (0 for none, 2 to 8, stepped; shown, not
     * stepped, without the built-in condenser, which a column needs). Keys {@code still_heater:<heater>},
     * {@code still_condenser:<condenser>}, the setting {@code stages} (its steppers {@code stages-}, {@code stages+}).
     */
    private static List<Column.Row> still(com.wormzjl.icheme.science.equipment.BatchStillOptions o, HeatControlColumn.Texts t) {
        var heaters = new ArrayList<Column.Button>();
        for (var h : com.wormzjl.icheme.science.equipment.BatchStillOptions.Heater.values())
            heaters.add(position("still_heater:" + h.name(), t.get(KEY + "still.heater." + h.key()), h == o.heater(), t.get(KEY + "still.heater." + h.key() + ".hint")));
        var condensers = new ArrayList<Column.Button>();
        for (var c : com.wormzjl.icheme.science.equipment.BatchStillOptions.Condenser.values())
            condensers.add(position("still_condenser:" + c.name(), t.get(KEY + "still.condenser." + c.key()), c == o.condenser(), t.get(KEY + "still.condenser." + c.key() + ".hint")));
        String stages = Integer.toString(o.stages());
        var column = o.condenses() ? step("stages", "still.stages", stages, "", t) : shown("stages", "still.stages", stages, "", t);
        return List.of(new Column.Group(t.get(KEY + "group.still.heater"), t.get(KEY + "group.still.heater.hint"), List.of(new Column.Buttons(heaters))),
                new Column.Group(t.get(KEY + "group.still.condenser"), t.get(KEY + "group.still.condenser.hint"), List.of(new Column.Buttons(condensers), column)));
    }

    private static Column.Button importKey(int reach, HeatControlColumn.Texts t) {
        return new Column.Button("import", t.get(KEY + "import"), true, t.get(KEY + "import.hint", reach));
    }

    /**
     * A machine's design point (batch 2026-11-03-rotating-machinery, option A): Duty (a pump's flow and head, a
     * centrifugal compressor's inlet flow and polytropic head, else the rated power; then the sizes they give, shown,
     * and the table), an electric machine's or a pump's rated RPM, a motor's Drive,
     * a turbine's Inlet steam (latched keys) and Exhaust. Keys: the settings {@code flow}, {@code head},
     * {@code power}, {@code exhaust}, {@code support}; {@code base_speed},
     * {@code steam:mp}, {@code steam:hp}, {@code steam:vhp}.
     */
    private static List<Column.Row> machine(Design in, HeatControlColumn.Texts t) {
        var kind = in.form().kind();
        var design = UnitDesign.of(kind);
        var d = in.duty();
        var duty = new ArrayList<Column.Row>();
        switch (kind) {
            case PUMP -> {
                duty.add(step("flow", "duty.flow", amount(d.flow()), "m³/h", t));
                duty.add(step("head", "duty.head", amount(d.head()), "m", t));
            }
            case CENTRIFUGAL_COMPRESSOR -> {
                duty.add(step("flow", "duty.flow", amount(d.flow()), "m³/h", t));
                duty.add(step("head", "duty.polytropic_head", amount(d.polytropicHead()), "kJ/kg", t));
            }
            default -> duty.add(step("power", "duty.power", amount(d.power()), "kW", t));
        }
        // The sizes the design point gives, where they are not the power itself.
        if (!"kW".equals(design.sizeUnit())) duty.add(shown("size", design.sizeRow(), design.sizeText(in.diameter()), design.sizeUnit(), t));
        if (design.lengthRow() != null) duty.add(shown("length", design.lengthRow(), Integer.toString(in.length()), design.lengthInMetres() ? "m" : "", t));
        if (design.supportRow() != null) duty.add(step("support", design.supportRow(), Integer.toString(in.support()), "m", t));
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(t.get(KEY + "group.duty"), t.get(KEY + "shift"), duty));
        if (kind == EquipmentShape.Kind.MOTOR || kind == EquipmentShape.Kind.PUMP || kind == EquipmentShape.Kind.GENERATOR)
            rows.add(new Column.Group(t.get(KEY+"group.speed"),t.get(KEY+(kind==EquipmentShape.Kind.MOTOR?"motor.vfd.hint":"rated_speed.hint")),List.of(step("base_speed",kind==EquipmentShape.Kind.MOTOR?"motor.base_speed":"rated_speed",amount(UnitDesign.designSpeed(kind,d)),"rpm",t))));
        if (d.steam() != null) {
            var classes = new ArrayList<Column.Button>();
            for (var steam : MachineDuty.Steam.values()) {
                String name = steam.name().toLowerCase(Locale.ROOT);
                classes.add(position("steam:" + name, t.get(KEY + "steam." + name), steam == d.steam(),
                        t.get(KEY + "steam.hint", UnitDesign.whole(steam.pressure), UnitDesign.whole(steam.temperature - 273.15))));
            }
            rows.add(new Column.Group(t.get(KEY + "group.steam"), t.get(KEY + "group.steam.hint"), List.of(new Column.Buttons(classes))));
            rows.add(new Column.Group(t.get(KEY + "group.exhaust"), null, List.of(step("exhaust", "duty.exhaust", amount(d.exhaust()), "kPa", t))));
        }
        return rows;
    }

    /** A rated quantity as its LCD shows it, in segments: to two decimals below 10 (1.25, 6.3), else whole, with no separator (2500). */
    static String amount(double value) {
        if (value >= 10) return Long.toString(Math.round(value));
        return new java.math.BigDecimal(String.format(Locale.ROOT, "%.2f", value)).stripTrailingZeros().toPlainString();
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
