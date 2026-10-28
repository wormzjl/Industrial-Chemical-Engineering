package com.wormzjl.icheme.client.gui.screens;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.EquipmentGhost;
import com.wormzjl.icheme.client.gui.common.ColumnDiagram;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn.Category;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn.Equipment;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.PaintSwatch;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.client.gui.common.kit.WindowScale;
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
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
import java.util.function.IntUnaryOperator;

import static com.wormzjl.icheme.science.equipment.UnitDesign.number;

/**
 * The equipment constructor's screen: first what to build (a vertical drum, a horizontal drum, a distillation column, or
 * a unit: a heat exchanger, a kettle reboiler, an air cooler bay, a steam ejector, a centrifugal pump, a reciprocating
 * compressor, a vertical or a cabin heater; owner requests 2026-10-16 and 2026-10-17), then its
 * design, on a panel door at the left beside the world and the vessel's ghost; a drum's side connections and every kind's
 * paint have pages of their own (owner requests 2026-10-16). It edits a local draft; the server receives the design when
 * the screen closes, or with a build request on Build. A column is imported whole (its diameter, trays, feed, draws,
 * pumparounds, steam and components) from the design saved in the nearest column calculator and built from it only
 * (batch 2026-10-26-multi-machine-framework); its schematic is the calculator's ({@link ColumnDiagram}), on the HMI glass
 * beside the world. A bottom outlet leaving through a skirt turns to the sides its form allows (owner request
 * 2026-10-25, {@link EquipmentShape#outlets}): a standing drum's on its Nozzles page, a trickle-bed reactor's on its
 * design page. A column's side nozzles take their sides by themselves; its Nozzles page turns any of them to a side
 * the player picks (finding F3, owner decision 2026-10-27).
 *
 * <p>The door draws through the GUI kit in the client's theme (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 3): each
 * page's rows come from {@link ConstructorColumn}; the settings' steppers, the selectors and the push buttons are kept at
 * their slots ({@link KitDoor}), the texts and values placed again each frame. An edit that changes what a widget shows
 * or where it stands rebuilds the page.
 */
public final class EquipmentConstructorScreen extends Screen {
    /** The screen's pages: what to build, its design, a drum's side connections, a column's side nozzles and the paint. */
    private enum Page {
        CHOOSE, DESIGN, CONNECTIONS, NOZZLES, PAINT
    }

    /** The schematic's width on its glass: the steam lines (90 pixels left of the axis) to the draw marks (39 right of it), and margins. */
    private static final int STRIP = 140;
    private static final String KEY = "gui.icheme." + ConstructorColumn.KEY;

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
    /** The door's column: its view, its kept widgets, its hover. */
    private final KitDoor door = new KitDoor();
    private @Nullable EditBox custom;
    private @Nullable SkinButton build;
    /** What the page's widgets showed and where they stood at the last rebuild ({@link #signature}). */
    private List<String> built = List.of();
    /** The hint under the pointer this frame, drawn after the widgets. */
    private @Nullable String hovered;
    private ShapeRasterizer.Layout layout;
    /** Set by Import until the constructor's synced design changes; the draft then takes it. */
    private @Nullable Design importing;
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

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
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

    /** Each side's word ({@link EquipmentShape#side}) as seen from the constructor: a connection's, a side nozzle's or the bottom outlet's. */
    private List<String> words() {
        var out = new ArrayList<String>();
        for (int side = 0; side < EquipmentShape.CONNECTION_SIDES; side++) {
            int[] s = EquipmentShape.side(side);
            out.add(toward(s[0], s[1]));
        }
        return out;
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

    // ---- The pages ----

    /** The page's rows, footer and message display as the builder makes them from the draft. */
    private ConstructorColumn.Page panel() {
        return switch (page) {
            case CHOOSE -> ConstructorColumn.choose(category != null ? category : Equipment.of(form).category, Equipment.of(form), EquipmentConstructorScreen::tr);
            case DESIGN -> ConstructorColumn.design(new ConstructorColumn.Design(form, diameter, tangentLength, support, forward, right, up, bank,
                    form.column() ? new ConstructorColumn.Imported(column != null ? column.diameter() : diameter, shownTrays().trays() + 2, shownTrays().feed() + 1) : null,
                    new ConstructorColumn.Outlet(outlets(), outlet(), words()), EquipmentNetwork.IMPORT_REACH), EquipmentConstructorScreen::tr);
            case CONNECTIONS -> {
                var c = connections.isEmpty() ? null : connection();
                int[] stations = stations();
                yield ConstructorColumn.connections(new ConstructorColumn.Connections(connections.size(), selected, c != null ? c.side() : 0, words(),
                        c != null ? number(c.station() + .5) : "", form.orientation().horizontal(), c != null ? c.offset() : 0,
                        c != null && c.offset() != 0 ? toward(c.across(), Integer.signum(c.offset())) : null,
                        connections.size() < EquipmentShape.MAX_CONNECTIONS && stations[0] <= stations[1], EquipmentShape.bootFor(diameter, tangentLength) != null, hasBoot(),
                        new ConstructorColumn.Outlet(outlets(), outlet(), words())), EquipmentConstructorScreen::tr);
            }
            case NOZZLES -> {
                var sides = sideNozzles();
                nozzle = clamp(nozzle, 0, Math.max(0, sides.size() - 1));
                ConstructorColumn.Nozzle shown = null;
                if (!sides.isEmpty()) {
                    var n = sides.get(nozzle);
                    shown = new ConstructorColumn.Nozzle(n.role().name().toLowerCase(Locale.ROOT), n.index(), n.placed(), n.placed() ? number(n.nozzle().base()[1]) : "-",
                            place(n).where(), n.turned(), n.side(), n.home());
                }
                yield ConstructorColumn.nozzles(new ConstructorColumn.Nozzles(sides.size(), nozzle, shown, words(), (int) sides.stream().filter(ColumnDesign.SideNozzle::turned).count()),
                        EquipmentConstructorScreen::tr);
            }
            case PAINT -> ConstructorColumn.paint(channel, EquipmentConstructorScreen::tr);
        };
    }

    @Override
    protected void init() {
        WindowScale.apply(minecraft, this);
        build = null;
        custom = null;
        var m = GuiCanvas.measure(font);
        var shown = panel();
        door.layout(ConstructorColumn.area(height, shown.display()), shown.rows(), m);
        door.keep(this::box, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, EquipmentConstructorScreen::skin)));
        // The palette in the Paint group's gap: each swatch outlined while it is the edited colour.
        if (page == Page.PAINT) {
            var swatches = DialogColumns.palette(door.column(), Paint.PALETTE.size());
            for (int i = 0; i < swatches.size(); i++) {
                var slot = swatches.get(i);
                var swatch = new PaintSwatch(slot.x(), slot.y(), Paint.PALETTE.get(i), () -> paint.colour(channel), this::colour);
                swatch.setWidth(slot.w());
                door.keep(addRenderableWidget(swatch), slot);
            }
        }
        // The footer's push buttons, Build the primary one; Change (the design page) or Back (the choice page) beside the title.
        var footer = ConstructorColumn.footer(height, shown.footer().size());
        for (int i = 0; i < shown.footer().size(); i++) {
            var spec = shown.footer().get(i);
            var button = addRenderableWidget(SkinButton.of(spec, footer.get(i), this::act, EquipmentConstructorScreen::skin));
            if (spec.key().equals("build")) build = button.primary(true);
        }
        if (page == Page.DESIGN || page == Page.CHOOSE) {
            String key = page == Page.DESIGN ? "change" : "back";
            var spec = new Column.Button(key, tr(ConstructorColumn.KEY + key), true, tr(ConstructorColumn.KEY + key + ".hint"));
            addRenderableWidget(SkinButton.of(spec, ConstructorColumn.TITLE_KEY, this::act, EquipmentConstructorScreen::skin));
        }
        if (build != null) build.active = buildable();
        built = signature(shown);
        door.place(0, 0);
    }

    /** The custom colour's LCD entry: the edited channel's colour as RRGGBB, painting it once six digits are typed. */
    private AbstractWidget box(Column.Field f, Rect slot) {
        var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(f.label()), EquipmentConstructorScreen::skin);
        box.setTooltip(Tooltip.create(Component.literal(f.hint() == null ? f.label() : f.label() + "\n" + f.hint())));
        box.setMaxLength(7);
        box.setFilter(text -> text.matches("#?[0-9a-fA-F]{0,6}"));
        box.setValue(rgb(paint.colour(channel)));
        box.setResponder(text -> {
            if (Paint.isHex(text)) colour(Paint.parseHex(text));
        });
        custom = box;
        return addRenderableWidget(box);
    }

    /** A colour as the custom field shows it: RRGGBB, its # left out so six digits fit the box. */
    private static String rgb(int colour) {
        return Paint.hex(colour).substring(1);
    }

    /**
     * What the page's widgets show and where they stand: every slot of the column, every button's text and state, the
     * footer's. An edit that changes it rebuilds the page; values and labels that keep it are placed each frame.
     */
    private List<String> signature(ConstructorColumn.Page shown) {
        var out = new ArrayList<String>();
        for (var e : door.place(shown.rows(), GuiCanvas.measure(font)).slots().entrySet()) out.add(e.getKey() + "@" + e.getValue());
        var buttons = new ArrayList<>(ConstructorColumn.buttons(shown.rows()));
        buttons.addAll(shown.footer());
        for (var b : buttons) out.add(b.key() + "|" + b.text() + "|" + b.active() + "|" + b.latched() + "|" + b.hint());
        return out;
    }

    private void show(Page next) {
        page = next;
        rebuildWidgets();
    }

    /** A press on a key of the door, by its key ({@link ConstructorColumn}): a stepper's ends in - or +, a selector's names its position after a colon. */
    private void act(String key) {
        if (key.endsWith("-") || key.endsWith("+")) {
            step(key.substring(0, key.length() - 1), key.endsWith("+") ? 1 : -1);
            return;
        }
        int colon = key.indexOf(':');
        String what = colon < 0 ? key : key.substring(0, colon), at = colon < 0 ? "" : key.substring(colon + 1);
        switch (what) {
            case "category" -> {
                category = Category.valueOf(at.toUpperCase(Locale.ROOT));
                rebuildWidgets();
            }
            case "kind" -> choose(Equipment.valueOf(at.toUpperCase(Locale.ROOT)));
            case "back" -> {
                category = null;
                show(Page.DESIGN);
            }
            case "change" -> show(Page.CHOOSE);
            case "nozzles" -> show(form.column() ? Page.NOZZLES : Page.CONNECTIONS);
            case "paint" -> show(Page.PAINT);
            case "build" -> build();
            case "import" -> importColumn();
            case "axis" -> {
                form = at.equals("crosswise") ? VesselPlacement.Form.CROSSWISE : VesselPlacement.Form.LENGTHWISE;
                refresh();
            }
            case "outlet" -> {
                outlet = Integer.parseInt(at);
                refresh();
            }
            case "add" -> add();
            case "remove" -> {
                connections.remove(selected);
                selected = clamp(selected, 0, Math.max(0, connections.size() - 1));
                refresh();
            }
            case "side" -> {
                var c = connection();
                connections.set(selected, new EquipmentShape.Connection(Integer.parseInt(at), c.station(), c.offset()));
                refresh();
            }
            case "boot" -> {
                boot = at.equals("on");
                refresh();
            }
            case "face" -> {
                var sides = sideNozzles();
                if (!sides.isEmpty()) face(sides.get(clamp(nozzle, 0, sides.size() - 1)), at.equals("auto") ? -1 : Integer.parseInt(at));
                refresh();
            }
            case "reset" -> {
                turns.clear();
                refresh();
            }
            case "channel" -> {
                channel = Integer.parseInt(at);
                rebuildWidgets();
            }
            case "default" -> {
                // Rebuilt, so the custom field shows the edited channel's default colour.
                paint = IChemE.defaultPaint();
                follow();
                rebuildWidgets();
            }
            default -> {
            }
        }
    }

    /** A stepper's press on setting {@code key}, {@code direction} one way or the other; Shift steps by five. */
    private void step(String key, int direction) {
        int step = direction * (hasShiftDown() ? 5 : 1);
        var kind = form.kind();
        switch (key) {
            case "size" -> {
                if (kind == EquipmentShape.Kind.AIR_COOLER) bay(step);
                else diameter(step);
            }
            case "length" -> {
                if (form.designed()) {
                    // A compressor's throws come in opposed pairs.
                    int pair = UnitDesign.of(kind).lengthStep();
                    tangentLength = unitLength(kind, tangentLength + (pair > 1 ? pair * Integer.signum(step) : step));
                } else tangentLength = clamp(tangentLength + step, 1, (int) EquipmentShape.MAX_TANGENT_LENGTH);
            }
            case "support" -> support = clamp(support + step, minimumSupport(), form.designed() ? ProcessUnit.maxSupport(kind) : EquipmentShape.MAX_SUPPORT);
            case "parallel" -> bank = new ProcessUnit.Bank(clamp(bank.parallel() + step, 1, ProcessUnit.Bank.MAX_PARALLEL), bank.series());
            case "series" -> bank = new ProcessUnit.Bank(bank.parallel(), clamp(bank.series() + step, 1, ProcessUnit.Bank.MAX_SERIES));
            case "forward" -> forward = clamp(forward + step, 1, VesselPlacement.MAX_FORWARD);
            case "right" -> right = clamp(right + step, -VesselPlacement.MAX_SHIFT, VesselPlacement.MAX_SHIFT);
            case "up" -> up = clamp(up + step, -VesselPlacement.MAX_SHIFT, VesselPlacement.MAX_SHIFT);
            case "connection" -> selected = clamp(selected + step, 0, Math.max(0, connections.size() - 1));
            case "station" -> {
                if (connections.isEmpty()) break;
                var c = connection();
                int[] stations = stations();
                connections.set(selected, new EquipmentShape.Connection(c.side(), clamp(c.station() + step, stations[0], stations[1]), c.offset()));
            }
            case "offset" -> {
                if (connections.isEmpty()) break;
                var c = connection();
                int across = EquipmentShape.maximumOffset(diameter);
                connections.set(selected, new EquipmentShape.Connection(c.side(), c.station(), clamp(c.offset() + step, -across, across)));
            }
            case "nozzle" -> nozzle = clamp(nozzle + step, 0, Math.max(0, sideNozzles().size() - 1));
            default -> {
            }
        }
        refresh();
    }

    /** Whether Build may send the draft: an imported column or another form, no side connection clashing, every column side nozzle placed. */
    private boolean buildable() {
        return clash() == null && unplaced() == null && design().buildable();
    }

    /** Paints the edited channel {@code rgb}: the custom field shows it unless typed in. */
    private void colour(int rgb) {
        if (paint.colour(channel) == rgb) return;
        paint = paint.with(channel, rgb);
        refresh();
        if (custom != null && !custom.isFocused()) custom.setValue(rgb(rgb));
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

    /** Turns side nozzle {@code n} to side {@code side}, or lets it take its side by itself again ({@code side} -1). */
    private void face(ColumnDesign.SideNozzle n, int side) {
        turns.removeIf(t -> t.role() == n.role() && t.index() == n.index());
        if (side >= 0) turns.add(new ColumnTrays.Turn(n.role(), n.index(), side));
    }

    /** A side nozzle's name: its role and, where its role has several, its number. */
    private static String nozzleName(ColumnDesign.SideNozzle n) {
        return tr(ConstructorColumn.KEY + "nozzle.role." + n.role().name().toLowerCase(Locale.ROOT), n.index());
    }

    /** Where a side nozzle stands, in words and in tray numbers for the schematic's mark: k - 0.5 over tray k, k + 0.5 under it, the tray count plus one in the sump. */
    private record Place(UnitDesign.Line where, double stage) {
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
            return new Place(out ? new UnitDesign.Line("nozzle.sump_out") : new UnitDesign.Line("nozzle.sump_in", t.trays() + 1), t.trays() + 1);
        }
        return switch (n.role()) {
            case REFLUX, FEED, RETURN -> new Place(new UnitDesign.Line("nozzle.over", k + 1), k - .5);
            case STEAM -> new Place(new UnitDesign.Line("nozzle.under_in", k + 1), k + .5);
            default -> new Place(new UnitDesign.Line("nozzle.under_out", k + 1), k + .5);
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
    private static String clashText(EquipmentShape.Clash clash) {
        int k = clash.connection() + 1;
        String key = ConstructorColumn.KEY + "clash.";
        return switch (clash.other()) {
            case EquipmentShape.Clash.VAPOUR -> tr(key + "vapour", k);
            case EquipmentShape.Clash.LIQUID -> tr(key + "liquid", k);
            case EquipmentShape.Clash.SADDLE -> tr(key + "saddle", k);
            case EquipmentShape.Clash.GROUND -> tr(key + "ground", k);
            case EquipmentShape.Clash.MANWAY -> tr(key + "manway", k);
            case EquipmentShape.Clash.BOOT -> tr(key + "boot", k);
            default -> tr(key + "connection", k, clash.other() + 1);
        };
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
        follow();
        show(Page.DESIGN);
    }

    private void importColumn() {
        var constructor = constructor();
        if (constructor == null) return;
        importing = constructor.design();
        EquipmentNetwork.requestImport(position);
    }

    private int minimumSupport() {
        if (form.designed()) return ProcessUnit.minSupport(form.kind(), diameter, tangentLength);
        if (form.column()) return EquipmentShape.minimumSkirt(column != null ? column.diameter() : diameter);
        if (form.orientation() == EquipmentShape.Orientation.VERTICAL) return EquipmentShape.minimumSkirt(diameter);
        return hasBoot() ? EquipmentShape.minimumBootSupport(diameter, tangentLength) : 1;
    }

    /**
     * Follows an edit: a boot raises the saddles to what its drain needs; the connections fit the drum again; the ghost's
     * layout follows the shape; Build acts while the draft is buildable.
     */
    private void follow() {
        support = Math.max(support, minimumSupport());
        fitConnections();
        var shape = design().shape(facing);
        if (!shape.equals(layout.shape())) layout = ShapeRasterizer.layout(shape);
        if (build != null) build.active = buildable();
    }

    /** Follows an edit on the page, and rebuilds it where its widgets would show or stand otherwise (a side turned, the bottom outlet refused, a selection moved). */
    private void refresh() {
        follow();
        if (minecraft != null && !signature(panel()).equals(built)) rebuildWidgets();
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private void build() {
        EquipmentNetwork.send(position, design(), true);
        sent = true;
        onClose();
    }

    @Override
    public void tick() {
        var constructor = constructor();
        if (constructor == null) {
            onClose();
            return;
        }
        // An import arrives as the constructor's synced design: the draft takes it whole.
        if (importing != null && !constructor.design().equals(importing)) {
            importing = null;
            load(constructor.design());
            rebuildWidgets();
        }
    }

    /** Closing keeps the draft as the constructor's design (unless Build already sent it). */
    @Override
    public void removed() {
        if (!sent && !design().equals(saved)) EquipmentNetwork.send(position, design(), false);
        sent = true;
        WindowScale.restore(minecraft);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- Drawing ----

    /** The message display's line: an import under way, what keeps Build waiting, blocked cells, else what the equipment holds or does. */
    private record Status(String text, boolean warn) {
    }

    /** What the equipment holds or does ({@link UnitDesign#held}: a vessel's volume, an exchanger's tube surface, a pump's flow and motor, a heater's duty, ...). */
    private UnitDesign.Line held() {
        var shape = layout.shape();
        return form.designed() ? UnitDesign.of(form.kind()).held(shape) : new UnitDesign.Line("volume", number(shape.volume()), number(shape.surfaceArea()));
    }

    private Status status() {
        int blocked = EquipmentGhost.blockedCount();
        var clash = clash();
        var unplaced = unplaced();
        String k = ConstructorColumn.KEY;
        if (importing != null) return new Status(tr(k + "importing"), false);
        if (!design().buildable()) return new Status(tr(k + "import_needed"), true);
        if (clash != null) return new Status(clashText(clash), true);
        if (unplaced != null) return new Status(tr(k + "nozzle.unplaced", nozzleName(unplaced)), true);
        if (EquipmentGhost.standing()) return new Status(tr(k + "standing"), true);
        if (blocked > 0) return new Status(tr(k + "blocked", blocked), true);
        var held = held();
        return new Status(tr(k + held.key(), held.args()), false);
    }

    /** The message display's hover: what the equipment holds and why, its top and length, its cells, what Build needs on this page. */
    private String statusHint() {
        var shape = layout.shape();
        var held = held();
        String k = ConstructorColumn.KEY;
        return String.join("\n", tr(k + held.key(), held.args()), tr(k + held.key() + ".hint"), tr(k + "extent", number(shape.topElevation()), number(shape.overallLength())),
                tr(k + "cells", layout.shell().size(), layout.interior().size()),
                tr(k + (page == Page.CONNECTIONS ? "clash.hint" : page == Page.NOZZLES ? "nozzle.status.hint" : "status.hint")));
    }

    /** No blur: the ghost in the world is half of this screen. The door, its title, column and message display; a column's schematic on its glass. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        var skin = skin();
        var canvas = new GuiCanvas(g, font, 0, 0);
        var m = GuiCanvas.measure(font);
        skin.page(canvas, new Rect(0, 0, ConstructorColumn.PANEL, height));
        var equipment = Equipment.of(form);
        String title = page == Page.CHOOSE ? this.title.getString()
                : ConstructorColumn.title(tr(ConstructorColumn.KEY + "equipment." + equipment.key()), tr(ConstructorColumn.KEY + "equipment." + equipment.key() + ".short"), m);
        skin.title(canvas, 10, 10, title);
        // Placed again for this frame's texts and values; the widgets keep their slots.
        var shown = panel();
        door.place(shown.rows(), m);
        hovered = door.draw(canvas, skin, mouseX, mouseY);
        if (page != Page.CHOOSE && new Rect(6, 6, m.width(title) + 8, 16).contains(mouseX, mouseY))
            hovered = tr(ConstructorColumn.KEY + "equipment." + equipment.key() + ".hint");
        if (shown.display()) {
            var line = ConstructorColumn.display(height);
            var status = status();
            skin.status(canvas, line, m.cut(status.text(), line.w()), status.warn() ? Palette.WARN : Palette.ACCENT);
            if (new Rect(line.x() - 4, line.y() - 5, line.w() + 8, 15).contains(mouseX, mouseY)) hovered = statusHint();
        }
        if ((page == Page.DESIGN || page == Page.NOZZLES) && form.column()) {
            double marked = Double.NaN;
            var sides = sideNozzles();
            if (page == Page.NOZZLES && !sides.isEmpty()) marked = place(sides.get(clamp(nozzle, 0, sides.size() - 1))).stage();
            schematic(g, skin.well(canvas, new Rect(width - 16 - STRIP, 16, STRIP + 12, height - 32)), marked);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        door.place(0, 0);
        hovered = null;
        super.render(g, mouseX, mouseY, partialTick);
        KitDoor.hint(g, font, hovered, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return door.press(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return door.drag(y) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        door.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return door.wheel(x, y, vertical) || super.mouseScrolled(x, y, horizontal, vertical);
    }

    /**
     * The column's trays, feed, side draws, pumparounds and steam, drawn as the column calculator draws them, on the HMI
     * glass {@code box}: the draw marks end 4 pixels inside its right edge, the steam lines start at least 7 inside its
     * left one. {@code marked}: where a side nozzle stands, in tray numbers ({@link Place#stage}), a bright bar across the
     * shell; NaN for none.
     */
    private void schematic(GuiGraphics g, Rect box, double marked) {
        var trays = shownTrays();
        var c = new GuiCanvas(g, font, 0, 0);
        int x = box.right() - 4 - ColumnDiagram.MARK - 13, top = box.y() + 10, bottom = box.bottom() - 31, n = trays.trays();
        double pitch = (bottom - top - 34) / (double) Math.max(1, n);
        IntUnaryOperator at = k -> top + 14 + (int) Math.round((k - 1) * pitch);
        ColumnDiagram.shell(c, x, top, bottom, 0xFF2B3740, ProcessUi.LINE);
        for (int k = 1; k <= n; k++) if (pitch >= 3 || k % 5 == 0 || k == 1 || k == n) ColumnDiagram.tray(c, x, at.applyAsInt(k), ProcessUi.MUTED);
        int fy = at.applyAsInt(trays.feed());
        ColumnDiagram.feed(c, x, x - 70, fy, ProcessUi.ACCENT);
        g.drawString(font, tr(ConstructorColumn.KEY + "schematic.feed", trays.feed() + 1), x - 70, fy - 10, ProcessUi.ACCENT);
        for (int draw : trays.draws()) ColumnDiagram.draw(c, x, at.applyAsInt(draw), ProcessUi.ACCENT);
        for (int i = 0; i < trays.pumparounds().size(); i++) {
            var p = trays.pumparounds().get(i);
            ColumnDiagram.pumparound(c, x, i, at.applyAsInt(p.draw()), at.applyAsInt(p.ret()), ProcessUi.WARN);
        }
        for (int i = 0; i < trays.steam().size(); i++) ColumnDiagram.steam(c, x, i, at.applyAsInt(trays.steam().get(i)), ProcessUi.WARN);
        if (!Double.isNaN(marked)) {
            int y = top + 14 + (int) Math.round((marked - 1) * pitch);
            g.fill(x - ColumnDiagram.HALF + 2, y - 1, x + ColumnDiagram.HALF, y + 2, 0xFFFFFFFF);
        }
        g.drawString(font, tr(ConstructorColumn.KEY + "schematic.draws", trays.draws().size()), box.x() + 2, box.bottom() - 23, ProcessUi.MUTED);
        g.drawString(font, tr(ConstructorColumn.KEY + "schematic.extras", trays.pumparounds().size(), trays.steam().size()), box.x() + 2, box.bottom() - 12, ProcessUi.MUTED);
    }
}
