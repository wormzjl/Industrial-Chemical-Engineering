package com.wormzjl.icheme.client.gui.screens;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.EquipmentGhost;
import com.wormzjl.icheme.client.gui.common.ColumnDiagram;
import com.wormzjl.icheme.client.gui.common.PaintSwatch;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.network.EquipmentNetwork;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.Design;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

import static com.wormzjl.icheme.science.equipment.UnitDesign.number;
import static com.wormzjl.icheme.science.equipment.UnitDesign.size;
import static com.wormzjl.icheme.science.equipment.UnitDesign.whole;

/**
 * The equipment constructor's screen: first what to build (a vertical drum, a horizontal drum, a distillation column, or
 * a unit: a heat exchanger, a kettle reboiler, an air cooler bay, a steam ejector, a centrifugal pump, a reciprocating
 * compressor, a vertical or a cabin heater; owner requests 2026-10-16 and 2026-10-17), then its
 * design, in a panel on the left beside the world and the vessel's ghost; a drum's side connections and every kind's
 * paint have pages of their own (owner requests 2026-10-16). It edits a local draft; the server receives the design when
 * the screen closes, or with a build request on Build. A column is imported whole (its diameter, trays, feed, draws,
 * pumparounds, steam and components) from the design saved in the nearest column calculator and built from it only
 * (batch 2026-10-26-multi-machine-framework); its schematic is the calculator's ({@link ColumnDiagram}). A bottom outlet leaving through a skirt turns to the sides its form allows (owner request
 * 2026-10-25, {@link EquipmentShape#outlets}): a standing drum's on its Nozzles page, a trickle-bed reactor's effluent on
 * its design page. A column's side nozzles take their sides by themselves; its Nozzles page turns any of them to a side
 * the player picks (finding F3, owner decision 2026-10-27).
 */
public final class EquipmentConstructorScreen extends Screen {
    /** The screen's pages: what to build, its design, a drum's side connections, a column's side nozzles and the paint. */
    private enum Page {
        CHOOSE, DESIGN, CONNECTIONS, NOZZLES, PAINT
    }

    /** The paint page's palette: two rows of eight swatches across the panel. */
    private static final int SWATCHES_PER_ROW = 8;
    private static final String PAINT_KEY = "gui.icheme.paint.";

    /** Panel layout in GUI pixels, top-down, so it fits a 240-pixel-high GUI (854×480 at scale 2). */
    /** The schematic strip's width: the steam lines (90 pixels left of the axis) to the draw marks (39 right of it), and margins. */
    private static final int STRIP = 140;
    private static final int PANEL = 196, TOP = 24, ROW = 17, ROWS = 8, LINE = 11, LINES = TOP + ROWS * ROW + 4, STATUS = LINES + 3 * LINE + 2, BUTTONS = STATUS + 14;
    /** The choice page: the groups' buttons in a left column, the shown group's kinds in a right one, each kind's description in its hover text. */
    private static final int CHOICE = 38, CHOICE_PITCH = 24, GROUP_WIDTH = 64;
    /** The width of a column's Nozzles button, left of Change on its design page's title row. */
    private static final int NOZZLES_BUTTON = 50;
    private static final String KEY = "gui.icheme.equipment_constructor.";

    /** The choice page's groups: their buttons in the left column, the chosen group's kinds in the right one. */
    private enum Category {
        VESSELS, HEAT, MACHINES, REACTORS, SOLID_FUEL;

        String key() {
            return KEY + "category." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the constructor builds, by group; a horizontal drum lies along or across the constructor's facing, every other is its placement form. */
    private enum Equipment {
        VERTICAL_DRUM(Category.VESSELS), HORIZONTAL_DRUM(Category.VESSELS), COLUMN(Category.VESSELS), FLOATING_ROOF_TANK(Category.VESSELS), SPHERE(Category.VESSELS), ADSORBER(Category.VESSELS), CYCLONE(Category.VESSELS),
        EXCHANGER(Category.HEAT), KETTLE(Category.HEAT), AIR_COOLER(Category.HEAT), VERTICAL_HEATER(Category.HEAT), CABIN_HEATER(Category.HEAT),
        PUMP(Category.MACHINES), COMPRESSOR(Category.MACHINES), CENTRIFUGAL_COMPRESSOR(Category.MACHINES), EJECTOR(Category.MACHINES),
        STEAM_TURBINE(Category.MACHINES), CONDENSING_TURBINE(Category.MACHINES),
        STIRRED_REACTOR(Category.REACTORS), TRICKLE_BED_REACTOR(Category.REACTORS), EBULLATED_BED_REACTOR(Category.REACTORS), FCC_CONVERTER(Category.REACTORS),
        DELAYED_COKER(Category.REACTORS),
        FLUIDIZED_BED_BOILER(Category.SOLID_FUEL), FLUIDIZED_BED_GASIFIER(Category.SOLID_FUEL), GRATE_BOILER(Category.SOLID_FUEL), FIXED_BED_GASIFIER(Category.SOLID_FUEL);

        final Category category;

        Equipment(Category category) {
            this.category = category;
        }

        static Equipment of(VesselPlacement.Form form) {
            return switch (form) {
                case VERTICAL -> VERTICAL_DRUM;
                case LENGTHWISE, CROSSWISE -> HORIZONTAL_DRUM;
                default -> valueOf(form.name());
            };
        }

        String key() {
            return KEY + "equipment." + name().toLowerCase(Locale.ROOT);
        }
    }

    private final BlockPos position;
    private final Direction facing;
    private Design saved;
    /** The screen opens on the choice page. */
    private Page page = Page.CHOOSE;
    /** The group the choice page shows: the current kind's until another is picked, again so each time the page opens. */
    private @Nullable Category category;
    private VesselPlacement.Form form;
    /** The diameter (a unit's size), always on its kind's grid: 0.5 m steps for a vessel ({@link ProcessUnit#snap} for a unit). */
    private double diameter;
    private int tangentLength, support, forward, right, up;
    private ColumnTrays trays;
    /** The column design imported from a column calculator, the only one a column is built from (plan 2026-10-26, section 4.5); null before an import. */
    private @Nullable ColumnDesign column;
    /** A drum's side connections, fitted to the drum ({@link #fitConnections}), and the one the page edits. */
    private final List<EquipmentShape.Connection> connections = new ArrayList<>();
    /** The drum's diameter, length, support and orientation the connections were last fitted to. */
    private @Nullable List<Double> fittedFor;
    /** The default connections of the drum {@link #fittedFor} describes: while the player's are these, they follow the design. */
    private List<EquipmentShape.Connection> defaults = List.of();
    private int selected;
    /** A column's side nozzles the player turned ({@link Design#turns}), and the one its Nozzles page shows, in placing order. */
    private final List<ColumnTrays.Turn> turns = new ArrayList<>();
    private int nozzle;
    /** The paint, and the channel the paint page edits ({@link Paint#BODY} or {@link Paint#STRUCTURE}). */
    private Paint paint;
    /** An air cooler's bays in parallel and in series, kept for the other kinds. */
    private ProcessUnit.Bank bank;
    /** Whether a lying drum has a boot, the player's choice kept for the other forms ({@link #hasBoot}). */
    private boolean boot;
    /** The bottom outlet's quarter turns from the front, the player's choice kept for the other forms ({@link #outlet()}). */
    private int outlet;
    private int channel = Paint.BODY;
    private @Nullable EditBox custom;
    /** The paint page's body and structure buttons (null off that page). */
    private final Button[] channelButtons = new Button[2];
    private @Nullable Button build;
    /** A lying drum's boot toggle, on the Nozzles page with the drain it carries (null off that page). */
    private @Nullable Button bootButton;
    /**
     * The bottom outlet's side (owner request 2026-10-25): a standing drum's on the Nozzles page where a lying drum's boot
     * stands, a trickle-bed reactor's on its design page under its skirt (null elsewhere).
     */
    private @Nullable Button outletButton;
    private ShapeRasterizer.Layout layout;
    /** Set by Import until the server answers ({@link #answered}). */
    private boolean importing;
    /** Why the last import brought no design, on the status line until the next edit; null otherwise. */
    private @Nullable Component refused;
    private boolean sent;

    public EquipmentConstructorScreen(BlockPos position, Direction facing, Design design) {
        super(Component.translatable("block.icheme.equipment_constructor"));
        this.position = position.immutable();
        this.facing = facing;
        load(design);
    }

    private void load(Design design) {
        saved = design;
        form = design.form();
        column = design.column();
        diameter = design.diameter();
        tangentLength = design.tangentLength();
        boot = design.boot();
        outlet = design.outlet();
        support = Math.max(design.support(), minimumSupport());
        forward = design.placement().forward();
        right = design.placement().right();
        up = design.placement().up();
        trays = design.trays();
        connections.clear();
        connections.addAll(design.connections());
        selected = 0;
        turns.clear();
        turns.addAll(design.turns());
        nozzle = 0;
        paint = design.paint();
        bank = design.bank();
        fitConnections();
        layout = ShapeRasterizer.layout(design().shape(facing));
    }

    /** The constructor this screen designs for. */
    public BlockPos position() {
        return position;
    }

    /** The draft; always valid, because every stepper is clamped to the bounds of its parameter. */
    public Design design() {
        return new Design(diameter, tangentLength, form, support, new VesselPlacement(forward, right, up), trays, List.copyOf(connections), paint, bank, hasBoot(), outlet(),
                column, List.copyOf(turns));
    }

    /** The trays the column form shows and builds: the imported design's, else the constructor's own (which build nothing). */
    private ColumnTrays shownTrays() {
        return column != null ? column.trays() : trays;
    }

    /** Whether the draft has a boot: a lying drum's, where it has room for one. */
    private boolean hasBoot() {
        return boot && form.kind() == EquipmentShape.Kind.DRUM && form.orientation().horizontal() && EquipmentShape.bootFor(diameter, tangentLength) != null;
    }

    /** The sides the draft's bottom outlet may face, as bits ({@link EquipmentShape#outlets}): the front only but on a standing drum or a trickle-bed reactor. */
    private int outlets() {
        return form.column() ? 1 : EquipmentShape.outlets(form.kind(), diameter, tangentLength, form.orientation(), support);
    }

    /** The draft's bottom outlet turns: the player's where the form turns it that way, else the front. */
    private int outlet() {
        return (outlets() >> outlet & 1) != 0 ? outlet : 0;
    }

    /** What the bottom outlet's button says: the side it faces as seen from the constructor. */
    private Component outletLabel() {
        int[] side = EquipmentShape.side(outlet());
        return Component.translatable(KEY + "side." + toward(side[0], side[1]));
    }

    /** The bottom outlet's button on row {@code row}: a click turns the outlet to the next side its form allows. */
    private void addOutletButton(int row) {
        outletButton = addRenderableWidget(Button.builder(outletLabel(), b -> {
            int allowed = outlets(), next = outlet();
            do next = (next + 1) % EquipmentShape.CONNECTION_SIDES; while ((allowed >> next & 1) == 0);
            outlet = next;
            refresh();
        }).bounds(PANEL - 80, TOP + row * ROW, 74, 16).tooltip(Tooltip.create(Component.translatable(KEY + "outlet.hint"))).build());
    }

    /** The draft's drum with no side connections, which fitting and adding them place around. */
    private EquipmentShape template() {
        return EquipmentShape.drum(diameter, tangentLength, form.orientation(), front(), support, List.of(), hasBoot(), outlet());
    }

    /** The vessel the draft would build, for the ghost; null on the choice page and once the constructor is gone. */
    public @Nullable EquipmentStructure.Vessel vessel() {
        var constructor = constructor();
        return constructor != null && page != Page.CHOOSE ? constructor.vessel(design()) : null;
    }

    /**
     * Fits a drum's side connections to its design once its size, support, form or boot changed ({@link EquipmentShape#fit}):
     * connections the player has not changed from the drum's defaults become the new design's defaults (owner report
     * 2026-10-25: a lying drum lengthened kept its inlet mid-shell instead of near its far head); others each stay where
     * they meet nothing, or move to the nearest free place. Moving one on the Nozzles page changes no size, so the player
     * may still put it where it clashes and read the status. A column or a unit keeps them for when it is a drum again.
     */
    private void fitConnections() {
        if (form.kind() != EquipmentShape.Kind.DRUM) return;
        var drum = List.of(diameter, (double) tangentLength, (double) support, (double) form.orientation().ordinal(), hasBoot() ? 1.0 : 0, (double) outlet());
        if (drum.equals(fittedFor)) return;
        boolean untouched = fittedFor != null && connections.equals(defaults);
        fittedFor = drum;
        defaults = EquipmentShape.defaultConnections(diameter, tangentLength, form.orientation(), support, hasBoot(), outlet());
        var fitted = untouched ? defaults : EquipmentShape.fit(connections, template());
        connections.clear();
        connections.addAll(fitted);
        selected = clamp(selected, 0, Math.max(0, connections.size() - 1));
    }

    private @Nullable EquipmentConstructorBlockEntity constructor() {
        return minecraft != null && minecraft.level != null && minecraft.level.getBlockEntity(position) instanceof EquipmentConstructorBlockEntity constructor ? constructor : null;
    }

    @Override
    protected void init() {
        build = null;
        bootButton = null;
        outletButton = null;
        custom = null;
        channelButtons[0] = channelButtons[1] = null;
        switch (page) {
            case CHOOSE -> {
                // The groups down the left, the shown group's kinds down the right: each button says the kind's short name,
                // its hover text the full name and what it is; the current kind and its group are marked.
                var current = Equipment.of(form);
                var shown = category != null ? category : current.category;
                for (var group : Category.values()) {
                    Component name = Component.translatable(group.key());
                    if (group == shown) name = Component.translatable(KEY + "current", name);
                    addRenderableWidget(Button.builder(name, b -> {
                        category = group;
                        rebuildWidgets();
                    }).bounds(8, CHOICE + group.ordinal() * CHOICE_PITCH, GROUP_WIDTH, 20).tooltip(Tooltip.create(Component.translatable(group.key() + ".hint"))).build());
                }
                int row = 0, left = 8 + GROUP_WIDTH + 4;
                for (var equipment : Equipment.values()) {
                    if (equipment.category != shown) continue;
                    Component name = Component.translatable(equipment.key() + ".short");
                    if (equipment == current) name = Component.translatable(KEY + "current", name);
                    addRenderableWidget(Button.builder(name, b -> choose(equipment)).bounds(left, CHOICE + row++ * CHOICE_PITCH, PANEL - 8 - left, 20)
                            .tooltip(Tooltip.create(Component.translatable(equipment.key()).copy().append("\n").append(Component.translatable(equipment.key() + ".hint"))))
                            .build());
                }
                addRenderableWidget(Button.builder(Component.translatable(KEY + "done"), b -> onClose()).bounds(100, BUTTONS, 88, 20).build());
            }
            case DESIGN -> designPage();
            case CONNECTIONS -> connectionsPage();
            case NOZZLES -> nozzlesPage();
            case PAINT -> paintPage();
        }
    }

    private void show(Page next) {
        page = next;
        rebuildWidgets();
    }

    private void designPage() {
        var change = Button.builder(Component.translatable(KEY + "change"), b -> show(Page.CHOOSE)).bounds(PANEL - 80, TOP - 1, 74, 16).build();
        change.setTooltip(Tooltip.create(Component.translatable(KEY + "change.hint")));
        addRenderableWidget(change);
        if (form.designed()) {
            unitDesign();
            return;
        }
        int next = 3;
        // A column's diameter, trays and feed are its imported design's: shown, not edited (plan 2026-10-26, section 4.5);
        // its side nozzles have a page of their own, opened beside Change.
        if (form.column()) {
            next = 4;
            addRenderableWidget(Button.builder(Component.translatable(KEY + "nozzles"), b -> show(Page.NOZZLES)).bounds(PANEL - 80 - 4 - NOZZLES_BUTTON, TOP - 1, NOZZLES_BUTTON, 16)
                    .tooltip(Tooltip.create(Component.translatable(KEY + "column_nozzles.hint"))).build());
        } else {
            stepper(1, this::diameter);
            stepper(2, step -> tangentLength = clamp(tangentLength + step, 1, (int) EquipmentShape.MAX_TANGENT_LENGTH));
        }
        if (Equipment.of(form) == Equipment.HORIZONTAL_DRUM) {
            var axis = Button.builder(Component.translatable(KEY + "form." + form.name().toLowerCase(Locale.ROOT)), b -> {
                form = form == VesselPlacement.Form.LENGTHWISE ? VesselPlacement.Form.CROSSWISE : VesselPlacement.Form.LENGTHWISE;
                refresh();
                rebuildWidgets();
            }).bounds(PANEL - 80, TOP + 3 * ROW, 74, 16).build();
            axis.setTooltip(Tooltip.create(Component.translatable(KEY + "axis.hint")));
            addRenderableWidget(axis);
            next = 4;
        }
        stepper(next, step -> support = clamp(support + step, minimumSupport(), EquipmentShape.MAX_SUPPORT));
        stepper(next + 1, step -> forward = clamp(forward + step, 1, VesselPlacement.MAX_FORWARD));
        stepper(next + 2, step -> right = clamp(right + step, -VesselPlacement.MAX_SHIFT, VesselPlacement.MAX_SHIFT));
        stepper(next + 3, step -> up = clamp(up + step, -VesselPlacement.MAX_SHIFT, VesselPlacement.MAX_SHIFT));
        // A column imports its design; a drum's side connections have their own page, as the paint does.
        if (form.column())
            bottomRow(Button.builder(Component.translatable(KEY + "import"), b -> importColumn()).tooltip(Tooltip.create(Component.translatable(KEY + "import.hint", EquipmentNetwork.IMPORT_REACH))),
                    paintButton());
        else bottomRow(Button.builder(Component.translatable(KEY + "nozzles"), b -> show(Page.CONNECTIONS)).tooltip(Tooltip.create(Component.translatable(KEY + "connections.hint"))),
                paintButton());
    }

    private Button.Builder paintButton() {
        return Button.builder(Component.translatable(KEY + "paint"), b -> show(Page.PAINT)).tooltip(Tooltip.create(Component.translatable(KEY + "paint.hint")));
    }

    /**
     * The paint page (owner request 2026-10-16): the body's and the structure's colours, each a button that picks it for
     * editing; the palette; a custom colour as #RRGGBB; the defaults.
     */
    private void paintPage() {
        for (int c : new int[]{Paint.BODY, Paint.STRUCTURE}) {
            int k = c;
            channelButtons[c] = addRenderableWidget(Button.builder(colourName(paint.colour(c)), b -> {
                channel = k;
                rebuildWidgets();
            }).bounds(PANEL - 80, TOP + (1 + c) * ROW, 74, 16).tooltip(Tooltip.create(hint(1 + c))).build());
        }
        for (int i = 0; i < Paint.PALETTE.size(); i++) {
            int x = 8 + i % SWATCHES_PER_ROW * (PaintSwatch.WIDTH + PaintSwatch.GAP), y = TOP + (3 + i / SWATCHES_PER_ROW) * ROW;
            addRenderableWidget(new PaintSwatch(x, y, Paint.PALETTE.get(i), () -> paint.colour(channel), this::colour));
        }
        custom = new EditBox(font, PANEL - 80, TOP + 5 * ROW, 74, 16, Component.translatable(KEY + "custom"));
        custom.setMaxLength(7);
        custom.setFilter(text -> text.matches("#?[0-9a-fA-F]{0,6}"));
        custom.setValue(Paint.hex(paint.colour(channel)));
        custom.setResponder(text -> {
            if (Paint.isHex(text)) colour(Paint.parseHex(text));
        });
        custom.setTooltip(Tooltip.create(hint(5)));
        addRenderableWidget(custom);
        addRenderableWidget(Button.builder(Component.translatable(KEY + "paint.default"), b -> {
            paint = IChemE.defaultPaint();
            refresh();
            rebuildWidgets();
        }).bounds(PANEL - 80, TOP + 6 * ROW, 74, 16).tooltip(Tooltip.create(hint(6))).build());
        bottomRow(Button.builder(Component.translatable(KEY + "back"), b -> show(Page.DESIGN)).tooltip(Tooltip.create(Component.translatable(KEY + "back.hint"))));
    }

    /** Paints the edited channel {@code rgb}: its button names the colour, and the custom field shows it unless typed in. */
    private void colour(int rgb) {
        if (paint.colour(channel) == rgb) return;
        paint = paint.with(channel, rgb);
        refresh();
        if (channelButtons[channel] != null) channelButtons[channel].setMessage(colourName(rgb));
        if (custom != null && !custom.isFocused()) custom.setValue(Paint.hex(rgb));
    }

    /** A colour as the page names it: its palette number, or #RRGGBB for a custom one. */
    private static Component colourName(int rgb) {
        var swatch = Paint.swatch(rgb);
        return swatch != null ? Component.literal("RAL " + swatch.ral()) : Component.literal(Paint.hex(rgb));
    }

    /**
     * A unit's design: its size (an exchanger's shell, a kettle's bundle, an ejector's suction, a pump's discharge, a
     * compressor's bore, a heater's radiant section; an air cooler's bay, its width and length together, and its bays in
     * parallel and in series), its tube length (a compressor's throws, a heater's radiant height or length), its support's
     * height (none for a pump, which stands at grade) and its placement.
     */
    private void unitDesign() {
        var kind = form.kind();
        int row = 1;
        if (kind == EquipmentShape.Kind.AIR_COOLER) {
            stepper(row++, this::bay);
            stepper(row++, step -> bank = new ProcessUnit.Bank(clamp(bank.parallel() + step, 1, ProcessUnit.Bank.MAX_PARALLEL), bank.series()));
            stepper(row, step -> bank = new ProcessUnit.Bank(bank.parallel(), clamp(bank.series() + step, 1, ProcessUnit.Bank.MAX_SERIES)));
        } else stepper(row, this::diameter);
        row++;
        int lengthStep = UnitDesign.of(kind).lengthStep();
        if (lengthRow(kind))
            // A compressor's throws come in opposed pairs.
            stepper(row++, step -> tangentLength = unitLength(kind, tangentLength + (lengthStep > 1 ? lengthStep * Integer.signum(step) : step)));
        if (ProcessUnit.maxSupport(kind) > 0) stepper(row++, step -> support = clamp(support + step, minimumSupport(), ProcessUnit.maxSupport(kind)));
        // A bottom outlet leaving through the skirt turns where the design lets it (a trickle-bed reactor's effluent).
        if (outletRow()) addOutletButton(row++);
        stepper(row, step -> forward = clamp(forward + step, 1, VesselPlacement.MAX_FORWARD));
        stepper(row + 1, step -> right = clamp(right + step, -VesselPlacement.MAX_SHIFT, VesselPlacement.MAX_SHIFT));
        stepper(row + 2, step -> up = clamp(up + step, -VesselPlacement.MAX_SHIFT, VesselPlacement.MAX_SHIFT));
        bottomRow(paintButton());
    }

    /** Whether a unit's design has a length row: tubes, throws, a heater's radiant height or length (not a bay's, an ejector's or a pump's). */
    private static boolean lengthRow(EquipmentShape.Kind kind) {
        return UnitDesign.of(kind).lengthRow() != null;
    }

    /** Whether a unit's design page has a bottom outlet row: its design turns the outlet (a trickle-bed reactor's effluent). */
    private boolean outletRow() {
        return form.designed() && Integer.bitCount(outlets()) > 1;
    }

    /**
     * The connections page: which connection, the side it faces, its height (a lying drum's place along its axis) and its
     * offset across the shell; Add places a new one where it meets nothing, Remove takes the selected one away.
     */
    private void connectionsPage() {
        stepper(1, step -> {
            selected = clamp(selected + step, 0, Math.max(0, connections.size() - 1));
            rebuildWidgets();
        });
        if (!connections.isEmpty()) {
            var side = Button.builder(Component.translatable(KEY + "side." + toward(connection().axis(), connection().sign())), b -> {
                var c = connection();
                connections.set(selected, new EquipmentShape.Connection((c.side() + 1) % EquipmentShape.CONNECTION_SIDES, c.station(), c.offset()));
                refresh();
                rebuildWidgets();
            }).bounds(PANEL - 80, TOP + 2 * ROW, 74, 16).tooltip(Tooltip.create(hint(2))).build();
            addRenderableWidget(side);
            stepper(3, step -> {
                var c = connection();
                int[] stations = stations();
                connections.set(selected, new EquipmentShape.Connection(c.side(), clamp(c.station() + step, stations[0], stations[1]), c.offset()));
            });
            stepper(4, step -> {
                var c = connection();
                int across = EquipmentShape.maximumOffset(diameter);
                connections.set(selected, new EquipmentShape.Connection(c.side(), c.station(), clamp(c.offset() + step, -across, across)));
            });
        }
        int half = (PANEL - 16 - 4) / 2, y = TOP + 5 * ROW;
        var add = Button.builder(Component.translatable(KEY + "add"), b -> add()).bounds(8, y, half, 16)
                .tooltip(Tooltip.create(Component.translatable(KEY + "add.hint", EquipmentShape.MAX_CONNECTIONS))).build();
        int[] stations = stations();
        add.active = connections.size() < EquipmentShape.MAX_CONNECTIONS && stations[0] <= stations[1];
        addRenderableWidget(add);
        var remove = Button.builder(Component.translatable(KEY + "remove"), b -> {
            connections.remove(selected);
            selected = clamp(selected, 0, Math.max(0, connections.size() - 1));
            refresh();
            rebuildWidgets();
        }).bounds(8 + half + 4, y, half, 16).tooltip(Tooltip.create(Component.translatable(KEY + "remove.hint"))).build();
        remove.active = !connections.isEmpty();
        addRenderableWidget(remove);
        if (form.orientation().horizontal()) {
            bootButton = addRenderableWidget(Button.builder(bootLabel(), b -> {
                boot = !boot;
                refresh();
            }).bounds(PANEL - 80, TOP + 6 * ROW, 74, 16).tooltip(Tooltip.create(Component.translatable(KEY + "boot.hint"))).build());
            bootButton.active = EquipmentShape.bootFor(diameter, tangentLength) != null;
        } else if (Integer.bitCount(outlets()) > 1) addOutletButton(6);
        bottomRow(Button.builder(Component.translatable(KEY + "back"), b -> show(Page.DESIGN)).tooltip(Tooltip.create(Component.translatable(KEY + "back.hint"))));
    }

    /** The bottom row: the page's own buttons beside Build and Done. Build waits while a side connection clashes or a column's side nozzle has no row. */
    private void bottomRow(Button.Builder... own) {
        int gap = 4, count = own.length + 2, span = (PANEL - 16 - (count - 1) * gap) / count;
        for (int i = 0; i < own.length; i++) addRenderableWidget(own[i].bounds(8 + i * (span + gap), BUTTONS, span, 20).build());
        build = addRenderableWidget(Button.builder(Component.translatable(KEY + "build"), b -> build()).bounds(8 + own.length * (span + gap), BUTTONS, span, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "done"), b -> onClose()).bounds(8 + (own.length + 1) * (span + gap), BUTTONS, span, 20).build());
        build.active = buildable();
    }

    /** Whether Build may send the draft: an imported column or another form, no side connection clashing, every column side nozzle placed. */
    private boolean buildable() {
        return clash() == null && unplaced() == null && design().buildable();
    }

    /**
     * A column's side nozzles page (finding F3, owner decision 2026-10-27): which nozzle and where it stands, the side it
     * faces (a click turns it: automatic, then the front, the left, the back and the right as seen from the constructor,
     * then automatic again), its height; All auto lets every nozzle take its side by itself again.
     */
    private void nozzlesPage() {
        var sides = sideNozzles();
        nozzle = clamp(nozzle, 0, Math.max(0, sides.size() - 1));
        stepper(1, step -> {
            nozzle = clamp(nozzle + step, 0, Math.max(0, sideNozzles().size() - 1));
            rebuildWidgets();
        });
        if (!sides.isEmpty()) {
            var shown = sides.get(nozzle);
            addRenderableWidget(Button.builder(turnLabel(shown), b -> {
                turn(shown);
                refresh();
                rebuildWidgets();
            }).bounds(PANEL - 80, TOP + 3 * ROW, 74, 16).tooltip(Tooltip.create(hint(3))).build());
        }
        var automatic = Button.builder(Component.translatable(KEY + "nozzle.reset"), b -> {
            turns.clear();
            refresh();
            rebuildWidgets();
        }).bounds(PANEL - 80, TOP + 5 * ROW, 74, 16).tooltip(Tooltip.create(hint(5))).build();
        automatic.active = !turns.isEmpty();
        addRenderableWidget(automatic);
        bottomRow(Button.builder(Component.translatable(KEY + "back"), b -> show(Page.DESIGN)).tooltip(Tooltip.create(Component.translatable(KEY + "back.hint"))));
    }

    /** The draft column's side nozzles in placing order, placed or not; none for another form. */
    private List<ColumnDesign.SideNozzle> sideNozzles() {
        return form.column() ? ColumnDesign.sides(layout.shape()) : List.of();
    }

    /** The draft column's first side nozzle with no row, or null. */
    private @Nullable ColumnDesign.SideNozzle unplaced() {
        for (var n : sideNozzles()) if (!n.placed()) return n;
        return null;
    }

    /** Turns side nozzle {@code n} on: from automatic to the front, then each side in turn, from the right back to automatic. */
    private void turn(ColumnDesign.SideNozzle n) {
        int side = -1;
        for (int i = 0; i < turns.size(); i++)
            if (turns.get(i).role() == n.role() && turns.get(i).index() == n.index()) side = turns.remove(i).side();
        if (side + 1 < EquipmentShape.CONNECTION_SIDES) turns.add(new ColumnTrays.Turn(n.role(), n.index(), side + 1));
    }

    /** What a side nozzle's Faces button says: the side the player turned it to, or Auto and the side it takes by itself. */
    private Component turnLabel(ColumnDesign.SideNozzle n) {
        return n.turned() ? sideName(n.side()) : Component.translatable(KEY + "nozzle.auto", sideName(n.side()));
    }

    /** Side {@code side} ({@link EquipmentShape#side}) as seen from the constructor. */
    private Component sideName(int side) {
        int[] s = EquipmentShape.side(side);
        return Component.translatable(KEY + "side." + toward(s[0], s[1]));
    }

    /** A side nozzle's name: its role and, where its role has several, its number. */
    private static Component nozzleName(ColumnDesign.SideNozzle n) {
        return Component.translatable(KEY + "nozzle.role." + n.role().name().toLowerCase(Locale.ROOT), n.index());
    }

    /** Where a side nozzle stands, in words and in tray numbers for the schematic's mark: k - 0.5 over tray k, k + 0.5 under it, the tray count plus one in the sump. */
    private record Place(Component words, double stage) {
    }

    /** Where side nozzle {@code n} stands, its trays numbered as the column calculator numbers them (tray 1 is the condenser). */
    private Place place(ColumnDesign.SideNozzle n) {
        var t = shownTrays();
        int k = switch (n.role()) {
            case REFLUX -> 1;
            case FEED -> t.feed();
            case PUMPAROUND_DRAW -> t.pumparounds().get(n.index() - 1).draw();
            case RETURN -> t.pumparounds().get(n.index() - 1).ret();
            case DRAW -> t.draws().get(n.index() - 1);
            case STEAM -> t.steam().get(n.index() - 1);
            default -> t.trays() + 1;
        };
        if (k > t.trays()) {
            boolean out = n.role() == EquipmentShape.Role.BOTTOMS;
            return new Place(out ? Component.translatable(KEY + "nozzle.sump_out") : Component.translatable(KEY + "nozzle.sump_in", t.trays() + 1), t.trays() + 1);
        }
        return switch (n.role()) {
            case REFLUX, FEED, RETURN -> new Place(Component.translatable(KEY + "nozzle.over", k + 1), k - .5);
            case STEAM -> new Place(Component.translatable(KEY + "nozzle.under_in", k + 1), k + .5);
            default -> new Place(Component.translatable(KEY + "nozzle.under_out", k + 1), k + .5);
        };
    }

    private EquipmentShape.Connection connection() {
        return connections.get(selected);
    }

    private int[] stations() {
        return EquipmentShape.stations(diameter, tangentLength, form.orientation(), support);
    }

    /**
     * Adds a side connection where it meets nothing ({@link EquipmentShape#place}): a standing drum's as high as it goes,
     * a lying drum's nearest its far head (an inlet's usual place, away from the outlets at the front, owner decision
     * 2026-10-25), each on its form's preferred sides. Where every place clashes it is added anyway at the first, and the
     * status says what it meets.
     */
    private void add() {
        var wanted = new EquipmentShape.Connection(EquipmentShape.preferredSides(form.orientation())[0], stations()[1], 0);
        connections.add(EquipmentShape.place(connections, wanted, template()));
        selected = connections.size() - 1;
        refresh();
        rebuildWidgets();
    }

    private EquipmentShape.Heading front() {
        return form.front(facing.getStepX(), facing.getStepZ());
    }

    /**
     * The way frame direction {@code sign} along {@code axis} points in the world as seen from the constructor: front
     * (back towards it), back, left, right, top or bottom.
     */
    private String toward(int axis, int sign) {
        var d = new double[3];
        d[axis] = sign;
        var w = layout.shape().transform().direction(d);
        long x = Math.round(w[0]), y = Math.round(w[1]), z = Math.round(w[2]);
        if (y != 0) return y > 0 ? "top" : "bottom";
        int fx = facing.getStepX(), fz = facing.getStepZ();
        if (x == -fx && z == -fz) return "front";
        if (x == fx && z == fz) return "back";
        // The constructor's right is its facing turned clockwise seen from above.
        return x == -fz && z == fx ? "right" : "left";
    }

    /** The first clash of the draft's side connections, or null. */
    private @Nullable EquipmentShape.Clash clash() {
        var clashes = layout.shape().clashes();
        return clashes.isEmpty() ? null : clashes.getFirst();
    }

    /** What clash {@code clash} meets, as the status says it. */
    private static Component clashText(EquipmentShape.Clash clash) {
        int k = clash.connection() + 1;
        return switch (clash.other()) {
            case EquipmentShape.Clash.VAPOUR -> Component.translatable(KEY + "clash.vapour", k);
            case EquipmentShape.Clash.LIQUID -> Component.translatable(KEY + "clash.liquid", k);
            case EquipmentShape.Clash.SADDLE -> Component.translatable(KEY + "clash.saddle", k);
            case EquipmentShape.Clash.GROUND -> Component.translatable(KEY + "clash.ground", k);
            case EquipmentShape.Clash.MANWAY -> Component.translatable(KEY + "clash.manway", k);
            case EquipmentShape.Clash.BOOT -> Component.translatable(KEY + "clash.boot", k);
            default -> Component.translatable(KEY + "clash.connection", k, clash.other() + 1);
        };
    }

    /** The page's rows, top-down: its title, then what each row sets. */
    private String[] rows() {
        if (page == Page.NOZZLES) return new String[]{"column_nozzles", "nozzle", "", "column_faces", "nozzle_height", "nozzle.turned"};
        if (page == Page.CONNECTIONS)
            return form.orientation().horizontal() ? new String[]{"connections", "connection", "faces", "along", "offset", "", "boot"}
                    : Integer.bitCount(outlets()) > 1 ? new String[]{"connections", "connection", "faces", "height", "offset", "", "outlet"}
                    : new String[]{"connections", "connection", "faces", "height", "offset"};
        if (page == Page.PAINT) return new String[]{"paint", "paint.body", "paint.structure", "paint.palette", "paint.palette", "custom", "paint.default"};
        return switch (Equipment.of(form)) {
            case VERTICAL_DRUM -> new String[]{"equipment", "diameter", "tangent_length", "skirt", "forward", "right", "up"};
            case HORIZONTAL_DRUM -> new String[]{"equipment", "diameter", "tangent_length", "axis", "saddles", "forward", "right", "up"};
            case COLUMN -> new String[]{"equipment", "diameter", "trays", "feed", "skirt", "forward", "right", "up"};
            case AIR_COOLER -> new String[]{"equipment", "bay", "parallel", "series", "bundle_height", "forward", "right", "up"};
            default -> {
                // A unit's rows, as its design names them: its size, its second size and its support where it has them.
                var design = UnitDesign.of(form.kind());
                var names = new ArrayList<String>(List.of("equipment", design.sizeRow()));
                if (design.lengthRow() != null) names.add(design.lengthRow());
                if (design.supportRow() != null) names.add(design.supportRow());
                if (outletRow()) names.add("outlet");
                names.addAll(List.of("forward", "right", "up"));
                yield names.toArray(String[]::new);
            }
        };
    }

    /** What row {@code row} sets, as its hover text says it. */
    private Component hint(int row) {
        String name = rows()[row];
        return Component.translatable(name.equals("equipment") ? Equipment.of(form).key() + ".hint" : KEY + name + ".hint");
    }

    /** A row of [−] value [+], each button saying what the row sets; shift steps by five. */
    private void stepper(int row, IntConsumer change) {
        int y = TOP + row * ROW;
        var hint = Tooltip.create(hint(row).copy().append("\n").append(Component.translatable(KEY + "shift")));
        var minus = Button.builder(Component.literal("−"), b -> edit(change, -1)).bounds(PANEL - 80, y, 20, 16).build();
        var plus = Button.builder(Component.literal("+"), b -> edit(change, 1)).bounds(PANEL - 26, y, 20, 16).build();
        minus.setTooltip(hint);
        plus.setTooltip(hint);
        addRenderableWidget(minus);
        addRenderableWidget(plus);
    }

    private void edit(IntConsumer change, int direction) {
        change.accept(direction * (hasShiftDown() ? 5 : 1));
        refresh();
    }

    /** The air cooler's bay {@code steps} steps on along {@link ProcessUnit#bays}: narrowest and shortest first. */
    private void bay(int steps) {
        var bays = ProcessUnit.bays();
        int at = 0;
        for (int i = 0; i < bays.size(); i++) if (bays.get(i)[0] == diameter && bays.get(i)[1] == tangentLength) at = i;
        var bay = bays.get(clamp(at + steps, 0, bays.size() - 1));
        diameter = bay[0];
        tangentLength = (int) bay[1];
    }

    /**
     * The diameter (a unit's size) {@code steps} steps on: 0.5 m for a vessel, whose skirt left at its default follows the
     * diameter's default and stays tall enough to enclose the bottom head; a unit's step, its tubes kept at least three
     * bundle diameters long.
     */
    private void diameter(int steps) {
        if (form.designed()) {
            var kind = form.kind();
            diameter = ProcessUnit.snap(kind, diameter + steps * ProcessUnit.diameterStep(kind));
            tangentLength = unitLength(kind, tangentLength);
            return;
        }
        boolean standing = form.orientation() == EquipmentShape.Orientation.VERTICAL;
        boolean followed = standing && support == Math.max(EquipmentShape.defaultSupport(diameter, EquipmentShape.Orientation.VERTICAL), minimumSupport());
        diameter = vesselDiameter(diameter + .5 * steps);
        support = Math.max(followed ? EquipmentShape.defaultSupport(diameter, EquipmentShape.Orientation.VERTICAL) : support, minimumSupport());
    }

    /** A unit's length (tubes, throws, radiant height or length) within its kind's range at the draft's size, on its step from the least (a compressor's throws even). */
    private int unitLength(EquipmentShape.Kind kind, int value) {
        int least = ProcessUnit.minLength(kind, diameter), length = clamp(value, least, ProcessUnit.maxLength(kind, diameter));
        return length - (length - least) % UnitDesign.of(kind).lengthStep();
    }

    /** {@code value} on the vessels' 0.5 m grid, within this form's range. */
    private double vesselDiameter(double value) {
        double maximum = form.column() ? EquipmentShape.MAX_COLUMN_DIAMETER : EquipmentShape.MAX_DIAMETER;
        return Math.max(EquipmentShape.MIN_DIAMETER, Math.min(maximum, Math.round(value * 2) / 2.0));
    }

    /**
     * Goes to the design of {@code equipment}, keeping the draft's sizes within the new kind's ranges; a horizontal drum
     * keeps its axis. A change between standing and lying, or to or from a unit, proposes that support's default height.
     */
    private void choose(Equipment equipment) {
        var before = form;
        category = null;
        form = switch (equipment) {
            case VERTICAL_DRUM -> VesselPlacement.Form.VERTICAL;
            case HORIZONTAL_DRUM -> Equipment.of(form) == Equipment.HORIZONTAL_DRUM ? form : VesselPlacement.Form.LENGTHWISE;
            default -> VesselPlacement.Form.valueOf(equipment.name());
        };
        if (form.designed()) {
            var kind = form.kind();
            diameter = ProcessUnit.snap(kind, diameter);
            // A new kind starts at its design's second size and support; the same kind keeps the player's.
            tangentLength = unitLength(kind, before.kind() != kind ? ProcessUnit.defaultLength(kind, diameter) : tangentLength);
            if (before.kind() != kind) support = ProcessUnit.defaultSupport(kind, diameter, tangentLength);
        } else {
            diameter = vesselDiameter(diameter);
            tangentLength = clamp(tangentLength, 1, (int) EquipmentShape.MAX_TANGENT_LENGTH);
            if (before.designed() || before.orientation() != form.orientation()) support = EquipmentShape.defaultSupport(diameter, form.orientation());
        }
        support = Math.max(support, minimumSupport());
        // A drum laid down or stood up takes that form's connection: its sides face other ways (a standing drum's left
        // is a lying drum's underside, onto its saddles).
        if (form.kind() == EquipmentShape.Kind.DRUM && before.orientation() != form.orientation()) {
            connections.clear();
            connections.addAll(EquipmentShape.defaultConnections(diameter, tangentLength, form.orientation(), support, hasBoot(), outlet()));
            selected = 0;
        }
        refresh();
        show(Page.DESIGN);
    }

    /** Sends the draft with an import: the server keeps it, imports onto it and answers ({@link #answered}). */
    private void importColumn() {
        importing = true;
        refused = null;
        saved = design();
        EquipmentNetwork.send(position, saved, EquipmentNetwork.Action.IMPORT);
    }

    /**
     * The server's answer to an import. An open screen for that constructor stops waiting: it takes the imported design
     * whole (the draft it sent with the calculator's column), or shows why there is none on its status line. The message
     * goes to chat when a design came, or when no such screen is open.
     */
    public static void answered(BlockPos at, Component message, Optional<Design> design) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof EquipmentConstructorScreen screen && screen.position.equals(at)) {
            screen.importing = false;
            if (design.isEmpty()) {
                screen.refused = message;
                return;
            }
            screen.load(design.get());
            screen.rebuildWidgets();
        }
        if (minecraft.player != null) minecraft.player.displayClientMessage(design.isPresent() ? message : message.copy().withStyle(ChatFormatting.RED), false);
    }

    private int minimumSupport() {
        if (form.designed()) return ProcessUnit.minSupport(form.kind(), diameter, tangentLength);
        if (form.column()) return EquipmentShape.minimumSkirt(column != null ? column.diameter() : diameter);
        if (form.orientation() == EquipmentShape.Orientation.VERTICAL) return EquipmentShape.minimumSkirt(diameter);
        return hasBoot() ? EquipmentShape.minimumBootSupport(diameter, tangentLength) : 1;
    }

    /** What the boot toggle says: whether the drum has one, or that it is too short for one. */
    private Component bootLabel() {
        return Component.translatable(KEY + (EquipmentShape.bootFor(diameter, tangentLength) == null ? "boot.none" : hasBoot() ? "boot.on" : "boot.off"));
    }

    /**
     * Follows an edit: a boot raises the saddles to what its drain needs; the connections fit the drum again; the bottom
     * outlet's button says its side (the front where the edit left the player's side refused).
     */
    private void refresh() {
        refused = null;
        support = Math.max(support, minimumSupport());
        if (bootButton != null) {
            bootButton.active = EquipmentShape.bootFor(diameter, tangentLength) != null;
            bootButton.setMessage(bootLabel());
        }
        fitConnections();
        var shape = design().shape(facing);
        if (!shape.equals(layout.shape())) layout = ShapeRasterizer.layout(shape);
        if (outletButton != null) outletButton.setMessage(outletLabel());
        if (build != null) build.active = buildable();
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private void build() {
        EquipmentNetwork.send(position, design(), EquipmentNetwork.Action.BUILD);
        sent = true;
        onClose();
    }

    @Override
    public void tick() {
        if (constructor() == null) onClose();
    }

    /** Closing keeps the draft as the constructor's design (unless Build already sent it). */
    @Override
    public void removed() {
        if (!sent && !design().equals(saved)) EquipmentNetwork.send(position, design(), EquipmentNetwork.Action.KEEP);
        sent = true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** No blur: the ghost in the world is half of this screen. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, PANEL, height, 0xE0202A31);
        graphics.fill(PANEL, 0, PANEL + 1, height, ProcessUi.LINE);
        if ((page == Page.DESIGN || page == Page.NOZZLES) && form.column()) graphics.fill(width - 4 - STRIP, 20, width - 4, height - 20, 0xC0202A31);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, 8, 8, ProcessUi.TEXT);
        switch (page) {
            case CHOOSE -> choices(graphics);
            case DESIGN -> designRows(graphics, mouseX, mouseY);
            case CONNECTIONS -> connectionRows(graphics, mouseX, mouseY);
            case NOZZLES -> nozzleRows(graphics, mouseX, mouseY);
            case PAINT -> paintRows(graphics, mouseX, mouseY);
        }
    }

    /**
     * The paint page: each channel's label (the edited one marked) and colour; the edited colour's name, what it paints,
     * and what keeps its own colour; the ghost shows the paint.
     */
    private void paintRows(GuiGraphics graphics, int mouseX, int mouseY) {
        String[] names = rows();
        graphics.drawString(font, Component.translatable(KEY + "paint"), 8, TOP + 4, ProcessUi.ACCENT);
        for (int c : new int[]{Paint.BODY, Paint.STRUCTURE}) {
            int y = TOP + (1 + c) * ROW;
            graphics.drawString(font, (c == channel ? "▶ " : "  ") + Component.translatable(KEY + names[1 + c]).getString(), 8, y + 4, c == channel ? ProcessUi.ACCENT : ProcessUi.TEXT);
            graphics.fill(PANEL - 100, y + 1, PANEL - 84, y + 15, ProcessUi.LINE);
            graphics.fill(PANEL - 99, y + 2, PANEL - 85, y + 14, 0xFF000000 | paint.colour(c));
        }
        graphics.drawString(font, Component.translatable(KEY + "custom"), 8, TOP + 5 * ROW + 4, ProcessUi.TEXT);
        var swatch = Paint.swatch(paint.colour(channel));
        Component name = swatch != null ? Component.translatable(KEY + "paint.named", swatch.ral(), Component.translatable(PAINT_KEY + swatch.key()))
                : Component.translatable(KEY + "paint.custom", Paint.hex(paint.colour(channel)));
        graphics.drawString(font, name, 8, LINES, ProcessUi.MUTED);
        graphics.drawString(font, Component.translatable(KEY + (channel == Paint.BODY ? "paint.body.parts" : "paint.structure.parts")), 8, LINES + LINE, ProcessUi.MUTED);
        graphics.drawString(font, Component.translatable(KEY + "paint.unpainted"), 8, LINES + 2 * LINE, ProcessUi.MUTED);
        status(graphics);
        describe(mouseX, mouseY, names.length, new String[]{"paint.named", "paint.parts", "paint.unpainted"});
    }

    /**
     * The status line: an import under way, why the last import brought no design, the first clash, a column's first side
     * nozzle with no row, or whether the ghost's cells are free.
     */
    private void status(GuiGraphics graphics) {
        int blocked = EquipmentGhost.blockedCount();
        var clash = clash();
        var unplaced = unplaced();
        var status = importing ? Component.translatable(KEY + "importing")
                : refused != null ? refused
                : !design().buildable() ? Component.translatable(KEY + "import_needed")
                : clash != null ? clashText(clash)
                : unplaced != null ? Component.translatable(KEY + "nozzle.unplaced", nozzleName(unplaced))
                : EquipmentGhost.standing() ? Component.translatable(KEY + "standing")
                : blocked == 0 ? Component.translatable(KEY + "clear") : Component.translatable(KEY + "blocked", blocked);
        graphics.drawString(font, status, 8, STATUS, refused == null && clash == null && unplaced == null && blocked == 0 && !EquipmentGhost.standing() ? ProcessUi.ACCENT : ProcessUi.WARN);
    }

    private void designRows(GuiGraphics graphics, int mouseX, int mouseY) {
        var equipment = Equipment.of(form);
        String[] names = rows();
        String place = Integer.toString(forward), shift = Integer.toString(right), lift = Integer.toString(up), across = size(diameter) + " m";
        String[] values = switch (equipment) {
            case VERTICAL_DRUM -> new String[]{"", across, tangentLength + " m", support + " m", place, shift, lift};
            case HORIZONTAL_DRUM -> new String[]{"", across, tangentLength + " m", "", support + " m", place, shift, lift};
            // The calculator's numbering: its trays and the condenser and reboiler; the feed counted from the condenser.
            case COLUMN -> new String[]{"", size(column != null ? column.diameter() : diameter) + " m", Integer.toString(shownTrays().trays() + 2),
                    Integer.toString(shownTrays().feed() + 1), support + " m", place, shift, lift};
            // A bay's width × length in m, without spaces or unit: a value stands in the 34 pixels between its − and +.
            case AIR_COOLER -> new String[]{"", size(diameter) + "×" + tangentLength, Integer.toString(bank.parallel()),
                    Integer.toString(bank.series()), support + " m", place, shift, lift};
            default -> {
                var design = UnitDesign.of(form.kind());
                var shown = new ArrayList<String>(List.of("", across));
                if (design.lengthRow() != null) shown.add(design.lengthInMetres() ? tangentLength + " m" : Integer.toString(tangentLength));
                if (design.supportRow() != null) shown.add(support + " m");
                if (outletRow()) shown.add("");
                shown.addAll(List.of(place, shift, lift));
                yield shown.toArray(String[]::new);
            }
        };
        // The kind's full name, or its short one where the full one would run under the Change button (a column's Nozzles button).
        Component title = Component.translatable(equipment.key());
        if (font.width(title) > PANEL - 80 - 12 - (form.column() ? NOZZLES_BUTTON + 4 : 0)) title = Component.translatable(equipment.key() + ".short");
        graphics.drawString(font, title, 8, TOP + 4, ProcessUi.ACCENT);
        for (int row = 1; row < names.length; row++) {
            int y = TOP + row * ROW + 4;
            graphics.drawString(font, Component.translatable(KEY + names[row]), 8, y, ProcessUi.TEXT);
            if (!values[row].isEmpty()) graphics.drawCenteredString(font, values[row], PANEL - 43, y, ProcessUi.ACCENT);
        }
        var shape = layout.shape();
        // The middle line says what the equipment holds or does: a vessel's volume, a unit's as its design says it
        // ({@link UnitDesign#held}: an exchanger's tube surface, a pump's flow and motor, a heater's duty, ...).
        var does = form.designed() ? UnitDesign.of(form.kind()).held(shape) : new UnitDesign.Line("volume", number(shape.volume()), number(shape.surfaceArea()));
        String middle = does.key();
        Component held = Component.translatable(KEY + middle, does.args());
        int y = LINES;
        for (var line : new Component[]{
                Component.translatable(KEY + "extent", number(shape.topElevation()), number(shape.overallLength())),
                held,
                Component.translatable(KEY + "cells", layout.shell().size(), layout.interior().size())}) {
            graphics.drawString(font, line, 8, y, ProcessUi.MUTED);
            y += LINE;
        }
        status(graphics);
        if (form.column()) schematic(graphics, Double.NaN);
        describe(mouseX, mouseY, names.length, new String[]{"extent", middle, "cells"});
    }

    /**
     * A column's side nozzles page: which nozzle (its name, red where it has no row), its height; where it stands in the
     * column and the side it takes first by itself; how many the player turned; the status. The schematic marks where
     * the shown nozzle stands.
     */
    private void nozzleRows(GuiGraphics graphics, int mouseX, int mouseY) {
        var sides = sideNozzles();
        graphics.drawString(font, Component.translatable(KEY + "column_nozzles"), 8, TOP + 4, ProcessUi.ACCENT);
        graphics.drawString(font, Component.translatable(KEY + "nozzle"), 8, TOP + ROW + 4, ProcessUi.TEXT);
        graphics.drawCenteredString(font, sides.isEmpty() ? "0" : (nozzle + 1) + " / " + sides.size(), PANEL - 43, TOP + ROW + 4, ProcessUi.ACCENT);
        double marked = Double.NaN;
        if (!sides.isEmpty()) {
            var n = sides.get(clamp(nozzle, 0, sides.size() - 1));
            var place = place(n);
            marked = place.stage();
            graphics.drawString(font, nozzleName(n), 8, TOP + 2 * ROW + 4, n.placed() ? ProcessUi.ACCENT : ProcessUi.WARN);
            graphics.drawString(font, Component.translatable(KEY + "column_faces"), 8, TOP + 3 * ROW + 4, ProcessUi.TEXT);
            graphics.drawString(font, Component.translatable(KEY + "nozzle_height"), 8, TOP + 4 * ROW + 4, ProcessUi.TEXT);
            graphics.drawCenteredString(font, n.placed() ? number(n.nozzle().base()[1]) + " m" : "-", PANEL - 43, TOP + 4 * ROW + 4, n.placed() ? ProcessUi.ACCENT : ProcessUi.WARN);
            graphics.drawString(font, place.words(), 8, LINES, ProcessUi.MUTED);
            graphics.drawString(font, Component.translatable(KEY + "nozzle.home", sideName(n.home())), 8, LINES + LINE, ProcessUi.MUTED);
        }
        graphics.drawString(font, Component.translatable(KEY + "nozzle.turned", sides.stream().filter(ColumnDesign.SideNozzle::turned).count()), 8, TOP + 5 * ROW + 4, ProcessUi.TEXT);
        status(graphics);
        schematic(graphics, marked);
        describe(mouseX, mouseY, rows().length, sides.isEmpty() ? new String[0] : new String[]{"nozzle.where", "nozzle.home"});
    }

    /**
     * The connections page: the selected connection's side (on its button), height or place along the axis, and offset;
     * that its nozzle takes any pipe and what its port draws; the first clash, if any.
     */
    private void connectionRows(GuiGraphics graphics, int mouseX, int mouseY) {
        String[] names = rows();
        graphics.drawString(font, Component.translatable(KEY + "connections"), 8, TOP + 4, ProcessUi.ACCENT);
        graphics.drawString(font, Component.translatable(KEY + "connection"), 8, TOP + ROW + 4, ProcessUi.TEXT);
        graphics.drawCenteredString(font, connections.isEmpty() ? "0" : (selected + 1) + " / " + connections.size(), PANEL - 43, TOP + ROW + 4, ProcessUi.ACCENT);
        Component status;
        if (connections.isEmpty()) {
            graphics.drawString(font, Component.translatable(KEY + "connections.none"), 8, TOP + 2 * ROW + 4, ProcessUi.MUTED);
            status = Component.translatable(KEY + "clash.none");
        } else {
            var c = connection();
            graphics.drawString(font, Component.translatable(KEY + "faces"), 8, TOP + 2 * ROW + 4, ProcessUi.TEXT);
            graphics.drawString(font, Component.translatable(KEY + names[3]), 8, TOP + 3 * ROW + 4, ProcessUi.TEXT);
            graphics.drawCenteredString(font, number(c.station() + .5) + " m", PANEL - 43, TOP + 3 * ROW + 4, ProcessUi.ACCENT);
            // The offset's label says which way it runs, as the side's button does.
            graphics.drawString(font, c.offset() == 0 ? Component.translatable(KEY + "offset")
                    : Component.translatable(KEY + "offset." + toward(c.across(), Integer.signum(c.offset()))), 8, TOP + 4 * ROW + 4, ProcessUi.TEXT);
            graphics.drawCenteredString(font, Math.abs(c.offset()) + " m", PANEL - 43, TOP + 4 * ROW + 4, ProcessUi.ACCENT);
            graphics.drawString(font, Component.translatable(KEY + "connection.size"), 8, LINES, ProcessUi.MUTED);
            graphics.drawString(font, Component.translatable(KEY + "connection.port"), 8, LINES + LINE, ProcessUi.MUTED);
            var clash = clash();
            status = clash != null ? clashText(clash) : Component.translatable(KEY + "clash.none");
        }
        if (form.orientation().horizontal()) graphics.drawString(font, Component.translatable(KEY + "boot"), 8, TOP + 6 * ROW + 4, ProcessUi.TEXT);
        else if (outletButton != null) graphics.drawString(font, Component.translatable(KEY + "outlet"), 8, TOP + 6 * ROW + 4, ProcessUi.TEXT);
        graphics.drawString(font, status, 8, STATUS, clash() == null ? ProcessUi.ACCENT : ProcessUi.WARN);
        describe(mouseX, mouseY, connections.isEmpty() ? 2 : names.length, connections.isEmpty() ? new String[0] : new String[]{"connection.size", "connection.port"});
    }

    /**
     * Hover text for the panel's words: a row's label or value says what the row sets (its buttons say it too), the
     * datasheet lines ({@code lines}, by key) and the status line what they report.
     */
    private void describe(int mouseX, int mouseY, int rows, String[] lines) {
        if (mouseX < 4 || mouseX >= PANEL - 4) return;
        Component text = null;
        int row = Math.floorDiv(mouseY - TOP, ROW);
        boolean label = mouseX < PANEL - 82, value = mouseX >= PANEL - 60 && mouseX < PANEL - 26;
        if (mouseY >= TOP && row < rows && (label || value && row > 0) && !rows()[row].isEmpty()) text = hint(row);
        else if (mouseY >= LINES && mouseY < LINES + lines.length * LINE) text = Component.translatable(KEY + lines[(mouseY - LINES) / LINE] + ".hint");
        else if (mouseY >= STATUS - 1 && mouseY < STATUS + 10)
            text = Component.translatable(KEY + (page == Page.CONNECTIONS ? "clash.hint" : page == Page.NOZZLES ? "nozzle.status.hint" : "status.hint"));
        if (text != null) setTooltipForNextRenderPass(font.split(text, 200));
    }

    /** The choice page's heading; what each kind of equipment is shows over its button (the draft's kind is marked on it). */
    private void choices(GuiGraphics g) {
        g.drawString(font, Component.translatable(KEY + "choose"), 8, TOP + 4, ProcessUi.TEXT);
    }

    /**
     * The column's trays, feed, side draws, pumparounds and steam, drawn as the column calculator draws them, inside the
     * right strip: the draw marks end 4 pixels inside its right edge, the steam lines start at least 7 inside its left one.
     * {@code marked}: where a side nozzle stands, in tray numbers ({@link Place#stage}), a bright bar across the shell; NaN
     * for none.
     */
    private void schematic(GuiGraphics g, double marked) {
        var trays = shownTrays();
        int x = width - 8 - ColumnDiagram.MARK - 13, top = 32, bottom = height - 54, n = trays.trays();
        double pitch = (bottom - top - 34) / (double) Math.max(1, n);
        IntUnaryOperator at = k -> top + 14 + (int) Math.round((k - 1) * pitch);
        ColumnDiagram.shell(g, x, top, bottom, 0xFF2B3740, ProcessUi.LINE);
        for (int k = 1; k <= n; k++) if (pitch >= 3 || k % 5 == 0 || k == 1 || k == n) ColumnDiagram.tray(g, x, at.applyAsInt(k), ProcessUi.MUTED);
        int fy = at.applyAsInt(trays.feed());
        ColumnDiagram.feed(g, x, x - 70, fy, ProcessUi.ACCENT);
        g.drawString(font, Component.translatable(KEY + "schematic.feed", trays.feed() + 1), x - 70, fy - 10, ProcessUi.ACCENT);
        for (int draw : trays.draws()) ColumnDiagram.draw(g, x, at.applyAsInt(draw), ProcessUi.ACCENT);
        for (int i = 0; i < trays.pumparounds().size(); i++) {
            var p = trays.pumparounds().get(i);
            ColumnDiagram.pumparound(g, x, i, at.applyAsInt(p.draw()), at.applyAsInt(p.ret()), ProcessUi.WARN);
        }
        for (int i = 0; i < trays.steam().size(); i++) ColumnDiagram.steam(g, x, i, at.applyAsInt(trays.steam().get(i)), ProcessUi.WARN);
        if (!Double.isNaN(marked)) {
            int y = top + 14 + (int) Math.round((marked - 1) * pitch);
            g.fill(x - ColumnDiagram.HALF + 2, y - 1, x + ColumnDiagram.HALF, y + 2, 0xFFFFFFFF);
        }
        int left = width - STRIP;
        g.drawString(font, Component.translatable(KEY + "schematic.draws", trays.draws().size()), left, height - 46, ProcessUi.MUTED);
        g.drawString(font, Component.translatable(KEY + "schematic.extras", trays.pumparounds().size(), trays.steam().size()), left, height - 35, ProcessUi.MUTED);
    }
}
