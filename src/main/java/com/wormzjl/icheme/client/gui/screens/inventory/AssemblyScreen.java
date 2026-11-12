package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.AssemblyTour;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Part;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Text;
import com.wormzjl.icheme.client.gui.flowsheet.AssemblyBlock;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.OperationColumn;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.network.AssemblyNetwork;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.world.inventory.AssemblyMenu;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A column's GUI (batch 2026-10-26-multi-machine-framework, plan section 8), a panel door on the GUI kit since batch
 * 2026-10-28-gui-toolkit (GUI_REVIEW.md order 4; the door's parts from {@link AssemblyColumn}): on the HMI glass, the plant
 * drawing of its assembly as the process simulator draws a column ({@link AssemblyBlock}, the owner's option A, 2026-11-03), each part teal,
 * yellow or red by its check, and the parts list with each part's
 * state in a word; every part reads what it is and its state on hover. On the door, Check parts, whose answer comes with
 * the engine's next bucket, and the selected part with Open unit. A complete structure opens on its Operation tab,
 * the drawing on its Structure tab. Before the first view arrives, the drawing shows the design
 * all red. Opening replays the last published view; the screen asks for nothing until the player presses Check parts.
 */
public final class AssemblyScreen extends AbstractContainerScreen<AssemblyMenu> {
    private static final String KEY = AssemblyDiagram.KEY;

    /** The parts' names and places, which the list and the hovers read. */
    private final @Nullable AssemblyDiagram diagram;
    /** The drawing: the sheet's column block of the column's component design. */
    private final @Nullable AssemblyBlock block;
    private final List<Slot> slots;
    private final KitDoor door = new KitDoor();
    private final ScrollView listView = new ScrollView();
    /** The Operation page's glass, where its tables are taller than it. */
    private final ScrollView glassView = new ScrollView();
    private @Nullable Slot selected;
    private boolean selectPending = true, structureTab, queued;
    private @Nullable AssemblyView seen, pressedAt;
    /** What the widgets show and where they stand, as built: a change rebuilds them. */
    private List<String> built = List.of();
    /** The parts list's lines as renderBg placed them this frame, and their box, and the drawing's box (null while no drawing shows). */
    private @Nullable AssemblyColumn.PartList list;
    private @Nullable Rect listBox, drawingBox;
    private @Nullable String hovered;
    /** The Operation page (C7): the entries' texts as typed, the keys the player edited, the entries kept, Apply; an Operation command awaiting its answer. */
    private final Map<String, String> drafts = new HashMap<>();
    private final Set<String> edited = new HashSet<>();
    private final Map<String, SkinField> entries = new HashMap<>();
    private @Nullable SkinButton apply;
    private boolean commanded, sentApply;
    private @Nullable AssemblyView commandedAt;

    public AssemblyScreen(AssemblyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        var level = Minecraft.getInstance().level;
        var column = level == null ? null : EquipmentStructure.find(level, menu.cell());
        var t = column == null ? null : column.shape().trays();
        ColumnTrays trays = t != null && t.components() != null ? t : null;
        diagram = trays == null ? null : AssemblyDiagram.of(trays);
        block = trays == null ? null : new AssemblyBlock(trays);
        slots = trays == null ? List.of() : ColumnBlueprint.of(trays).slots();
        inventoryLabelY = 10_000;
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    /** A text under {@code gui.icheme.}, as the builders name them. */
    private static String lang(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable(KEY + key, args);
    }

    /** A diagram text as a component, its nested texts translated too. */
    public static Component text(Text text) {
        var args = new Object[text.args().size()];
        for (int i = 0; i < args.length; i++) args[i] = text.args().get(i) instanceof Text t ? text(t) : text.args().get(i);
        return Component.translatable(KEY + text.key(), args);
    }

    /** The view shown: the last delivered, or, before the first, the design as not checked. */
    private AssemblyView view() {
        var view = menu.view();
        if (view != null) return view;
        return diagram == null ? AssemblyView.NO_DESIGN : AssemblyView.NOT_VALIDATED;
    }

    private @Nullable AssemblyReport report() {
        return view().report();
    }

    private boolean complete() {
        return view().status() == AssemblyView.Status.COMPLETE;
    }

    /** Whether the Operation tab shows: a complete structure, unless the player chose its Structure tab. */
    private boolean operation() {
        return complete() && !structureTab;
    }

    /** Whether the drawing shows, with the door column: a designed column, on its Structure tab. */
    private boolean drawing() {
        return diagram != null && view().status() != AssemblyView.Status.NO_DESIGN && !operation();
    }

    private AssemblyColumn.Input input() {
        var part = selected == null || diagram == null ? null : diagram.part(selected);
        String name = selected == null ? null : part == null ? selected.part() : AssemblyColumn.text(part.name(), AssemblyScreen::lang);
        boolean opens = selected != null && !selected.equals(ColumnBlueprint.COLUMN) && member(selected) != AssemblyReport.NONE;
        return AssemblyColumn.input(slots.size(), report(), queued, name, opens);
    }

    private List<Column.Row> rows() {
        return AssemblyColumn.rows(input(), AssemblyScreen::lang);
    }

    /** The column's structure, from the client's cells; null where none is loaded. */
    private EquipmentStructure.@Nullable Vessel column() {
        var level = minecraft == null ? null : minecraft.level;
        return level == null ? null : EquipmentStructure.find(level, menu.cell());
    }

    /** Whether the title strip holds the name's entry (N1): a player who may build, the column loaded. The server checks the same, and its reach. */
    private boolean names() {
        return minecraft != null && minecraft.player != null && minecraft.player.mayBuild() && column() != null;
    }

    /** The column's name as the client's cells hold it. */
    private String currentName() {
        var column = column();
        return column == null || minecraft == null || minecraft.level == null ? "" : EquipmentStructure.name(minecraft.level, column);
    }

    /** Whether Apply has a name to send: the typed name, cleaned, differs from the column's. */
    private boolean renames() {
        return nameDraft != null && names() && !com.wormzjl.icheme.science.equipment.DeviceName.clean(nameDraft).equals(currentName());
    }

    /** The name as typed, sent with Apply, and the name it last followed: the draft follows the column's name while the player has not changed it. */
    private @Nullable String nameDraft, nameShown;
    private @Nullable SkinField nameBox;

    /** The title strip (N1, the owner 2026-11-03: "You can put the equipment name here"): the kind's plate, the name after it, the help and theme keys. */
    private TitleStrip.Strip strip() {
        return TitleStrip.placeKeys(imageWidth, 2, title.getString(), GuiCanvas.measure(font));
    }

    /** The tab's help tour (batch 2026-11-12-tutorial-system, P5 stage B), opened by the ? key left of the theme key. */
    private final com.wormzjl.icheme.client.gui.tutorial.HelpTour tour = new com.wormzjl.icheme.client.gui.tutorial.HelpTour();

    /** The help sign: the tab's tour. A tour sends nothing to the server. */
    private void helpKey(Rect at) {
        if (com.wormzjl.icheme.client.gui.tutorial.HelpTour.of(AssemblyTour.page(operation())) == null) return;
        String hint = Component.translatable("tutorial.icheme.guide.help").getString();
        var key = new SkinButton(leftPos + at.x(), topPos + at.y(), at.w(), at.h(), Component.literal(hint), b -> openTour(), AssemblyScreen::skin).icon(Skin.Icon.HELP);
        key.setTooltip(Tooltip.create(Component.literal(hint)));
        addRenderableWidget(key);
    }

    /** Opens the tab's tour; finishing the Structure tab's completes the guide's reading step of the column's lines. */
    private void openTour() {
        var t = com.wormzjl.icheme.client.gui.tutorial.HelpTour.of(AssemblyTour.page(operation()));
        if (t == null) return;
        Runnable done = operation() ? null : () -> com.wormzjl.icheme.client.gui.tutorial.GuideClient.report(LINES_STEP);
        tour.open(t, this::tourAnchor, done);
    }

    /** The guide's reading step the Structure tab's tour completes. */
    public static final String LINES_STEP = "building/lines";

    /** A tour's anchor on the screen: a frame place (AssemblyTour.frame), else a door row's widget, the door scrolled to show it where {@code reveal}; null for none. */
    private @Nullable Rect tourAnchor(String key, boolean reveal) {
        var at = AssemblyTour.frame(imageWidth, imageHeight, operation()).get(key);
        if (at != null) return at.moved(leftPos, topPos);
        if (!door()) return null;
        Rect r;
        try {
            r = AssemblyTour.door(door.column()).get(key);
        } catch (IllegalStateException none) {
            return null;
        }
        if (r == null) return null;
        var v = door.view();
        for (int i = 0; reveal && i < 64 && !v.visible(r); i++) {
            var port = v.viewport();
            int before = v.offset();
            v.wheel(port.x() + 2, port.y() + 2, v.onScreen(r).y() < port.y() ? 1 : -1);
            if (v.offset() == before) break;
        }
        return v.onScreen(r).moved(leftPos, topPos);
    }

    /** Saves the typed name by the rename path (N1): Enter in the entry, and Apply. */
    private void saveName() {
        var column = column();
        if (column != null && renames()) com.wormzjl.icheme.network.EquipmentNetwork.rename(column.controller(), nameDraft);
    }

    /** The name's entry in the title strip (N1): the name as typed, saved by Enter or Apply; its hint while empty. */
    private SkinField nameEntry(Rect slot) {
        if (nameDraft == null) nameDraft = nameShown = currentName();
        var box = new SkinField(font, leftPos + slot.x(), topPos + slot.y(), slot.w(), slot.h(), Component.literal(lang("fluid.name")), AssemblyScreen::skin);
        box.setMaxLength(com.wormzjl.icheme.science.equipment.DeviceName.MAX);
        box.setValue(nameDraft);
        box.setResponder(text -> nameDraft = text);
        box.setHint(Component.literal(lang("fluid.name")));
        box.onEnter(this::saveName);
        box.setTooltip(Tooltip.create(Component.literal(lang("fluid.name") + "\n" + lang("fluid.name.hint"))));
        nameBox = box;
        return addRenderableWidget(box);
    }

    /** The Operation page's snapshot where its door shows: a complete structure on its Operation tab, with an operation to show. */
    private @Nullable OperationView operationView() {
        return operation() ? view().operation() : null;
    }

    private static OperationColumn.Units units() {
        return new OperationColumn.Units(ClientConfig.temperature(), ClientConfig.pressure(), ClientConfig.flowTime());
    }

    private OperationColumn.Input operationInput(OperationView view) {
        return new OperationColumn.Input(view, commanded, units());
    }

    /** A focused entry keeps its keys: the name's letters (the inventory key among them) do not close the screen; Enter saves the name. */
    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (tour.isOpen()) return tour.keyPressed(key);
        if (ProcessUi.textKeyPressed(this, key, scan, modifiers)) return true;
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    protected void init() {
        follow();
        // Use the supplied GUI dimensions; the operation and structure doors keep their own scrolling view.
        imageWidth = Math.max(320, Math.min(760, width - 16));
        imageHeight = Math.max(220, height - 16);
        super.init();
        var tabs = AssemblyColumn.tabs(operation(), complete(), AssemblyScreen::lang);
        var tabSlots = AssemblyColumn.tabSlots(imageWidth);
        for (int i = 0; i < tabs.size(); i++) push(tabs.get(i), tabSlots.get(i));
        var footer = AssemblyColumn.footer(imageWidth, imageHeight, operationView() == null && names() ? 3 : 2);
        nameBox = null;
        var namePlace = strip().name();
        if (names() && namePlace != null) nameEntry(namePlace);
        var operating = operationView();
        var buttons = operating != null ? OperationColumn.footerButtons(OperationColumn.applies(operationInput(operating), drafts), AssemblyScreen::lang)
                : names() ? AssemblyColumn.footerButtons(renames(), AssemblyScreen::lang) : AssemblyColumn.footerButtons(AssemblyScreen::lang);
        apply = null;
        for (int i = 0; i < buttons.size(); i++) {
            var button = SkinButton.of(buttons.get(i), footer.get(i).moved(leftPos, topPos), this::act, AssemblyScreen::skin);
            addRenderableWidget(button);
            if (buttons.get(i).key().equals("apply")) apply = button;
        }
        // The theme key shows the contrast sign; its hover names the panel shown.
        String theme = lang(ClientConfig.theme() == ClientConfig.Theme.DARK ? "fluid.theme.dark" : "fluid.theme.light");
        helpKey(TitleStrip.keys(imageWidth, 2).get(1));
        var at = AssemblyColumn.theme(imageWidth);
        var themeKey = new SkinButton(leftPos + at.x(), topPos + at.y(), at.w(), at.h(), Component.literal(theme), b -> {
            ClientConfig.toggleTheme();
            rebuildWidgets();
        }, AssemblyScreen::skin).icon(Skin.Icon.THEME);
        themeKey.setTooltip(Tooltip.create(Component.literal(theme + "\n").append(Component.translatable("gui.icheme.fluid.theme.hint"))));
        addRenderableWidget(themeKey);
        if (drawing()) {
            var rows = rows();
            door.layout(AssemblyColumn.area(imageWidth, imageHeight), rows, GuiCanvas.measure(font));
            door.keep((field, slot) -> {
                throw new IllegalStateException("No entry on the assembly door");
            }, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, AssemblyScreen::skin)));
            door.place(leftPos, topPos);
        }
        entries.clear();
        if (operating != null) {
            var rows = OperationColumn.rows(operationInput(operating), AssemblyScreen::lang);
            door.layout(AssemblyColumn.area(imageWidth, imageHeight), rows, GuiCanvas.measure(font));
            door.keep(this::entry, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, AssemblyScreen::skin)));
            door.place(leftPos, topPos);
        }
        built = signature();
    }

    /** A set point's LCD entry: what the player typed, else the set point in force; a typed text is kept until Apply. */
    private SkinField entry(Column.Field f, Rect slot) {
        var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(f.label()), AssemblyScreen::skin);
        box.setTooltip(Tooltip.create(Component.literal(f.hint() == null ? f.label() : f.label() + "\n" + f.hint())));
        box.setMaxLength(12);
        box.setFilter(text -> text.matches("-?[0-9]*[.,]?[0-9]*"));
        var view = operationView();
        String shown = view == null ? "" : OperationColumn.values(view.setpoints(), units()).getOrDefault(f.key(), "");
        box.setValue(edited.contains(f.key()) ? drafts.getOrDefault(f.key(), shown) : shown);
        box.setResponder(text -> {
            if (text.equals(drafts.get(f.key()))) return;
            drafts.put(f.key(), text);
            edited.add(f.key());
        });
        entries.put(f.key(), box);
        return addRenderableWidget(box);
    }

    /** A push button or a tab at door rect {@code at}, as {@code spec} says. */
    private void push(Column.Button spec, Rect at) {
        addRenderableWidget(SkinButton.of(spec, at.moved(leftPos, topPos), this::act, AssemblyScreen::skin));
    }

    /** What the widgets show and where they stand: the page, the tabs, the selected part (its name may wrap), the door's buttons. */
    private List<String> signature() {
        var out = new ArrayList<String>();
        out.add(drawing() + " " + operation() + " " + names());
        for (var b : AssemblyColumn.tabs(operation(), complete(), AssemblyScreen::lang)) out.add(b.key() + " " + b.active() + " " + b.latched());
        if (drawing()) {
            var in = input();
            out.add(String.valueOf(in.selected()));
            for (var b : ConstructorColumn.buttons(AssemblyColumn.rows(in, AssemblyScreen::lang))) out.add(b.key() + " " + b.active());
        }
        var operating = operationView();
        if (operating != null) {
            // The entries' keys and kinds, the switch's states; Apply's state follows the typing without a rebuild (containerTick).
            for (var row : OperationColumn.rows(operationInput(operating), AssemblyScreen::lang))
                if (row instanceof Column.Group g) for (var r : g.rows()) out.add(r instanceof Column.Field f ? "field " + f.key() + " " + f.label() + " " + f.unit()
                        : r instanceof Column.Reading m ? "meter " + m.key() + " " + m.label() + " " + m.unit() : r.getClass().getSimpleName());
            for (var b : ConstructorColumn.buttons(OperationColumn.rows(operationInput(operating), AssemblyScreen::lang))) out.add(b.key() + " " + b.active() + " " + b.latched());
        }
        return out;
    }

    /** Rebuilds the widgets where they would show or stand otherwise. */
    private void refresh() {
        if (minecraft != null && !signature().equals(built)) rebuildWidgets();
    }

    private void act(String key) {
        switch (key) {
            case "close" -> {
                onClose();
                return;
            }
            case "check" -> press();
            case "open" -> open(member(selected));
            case "datasheet" -> open(menu.origin().asLong());
            case "tab:structure" -> structureTab = true;
            case "tab:operation" -> structureTab = false;
            case "start" -> command(AssemblyNetwork.Action.START, List.of());
            case "stop" -> command(AssemblyNetwork.Action.STOP, List.of());
            case "apply" -> {
                // Apply sends the name typed in the title strip (N1), and on the Operation page the set points.
                saveName();
                var view = operationView();
                if (view == null || !OperationColumn.applies(operationInput(view), drafts)) break;
                var values = OperationColumn.parse(view.setpoints(), drafts, units());
                if (values != null) {
                    command(AssemblyNetwork.Action.APPLY, values);
                    sentApply = true;
                }
            }
            default -> {
            }
        }
        refresh();
    }

    private void press() {
        PacketDistributor.sendToServer(new AssemblyNetwork.ValidatePayload(menu.containerId));
        queued = true;
        pressedAt = menu.view();
    }

    /** An Operation command: queued for the engine's next bucket; the switch and Apply stay off until its answer comes. */
    private void command(AssemblyNetwork.Action action, List<Double> values) {
        PacketDistributor.sendToServer(new AssemblyNetwork.OperationPayload(menu.containerId, action, values));
        commanded = true;
        commandedAt = menu.view();
    }

    private void open(long member) {
        if (member != AssemblyReport.NONE) PacketDistributor.sendToServer(new AssemblyNetwork.OpenUnitPayload(menu.containerId, member));
    }

    /** The unit the slot holds, if the report holds it as a member (the column always); else none. */
    private long member(@Nullable Slot slot) {
        if (slot == null) return AssemblyReport.NONE;
        if (slot.equals(ColumnBlueprint.COLUMN)) return menu.origin().asLong();
        var report = report();
        var r = AssemblyColumn.slot(report, slot);
        return r != null && r.member() != AssemblyReport.NONE && report.members().contains(r.member()) ? r.member() : AssemblyReport.NONE;
    }

    /** Follows the views delivered: a pending check is answered by a new one; a structure that turned complete opens on its operation; the part opened at is selected. */
    private void follow() {
        var view = view();
        if (menu.view() != seen) {
            if (queued && menu.view() != pressedAt) queued = false;
            if (commanded && menu.view() != commandedAt) {
                commanded = false;
                // The answer to Apply shows the set points the column took: the entries follow them again.
                if (sentApply) {
                    edited.clear();
                    drafts.clear();
                    sentApply = false;
                }
            }
            if (view.status() == AssemblyView.Status.COMPLETE && (seen == null || seen.status() != AssemblyView.Status.COMPLETE)) structureTab = false;
            seen = menu.view();
        }
        if (selectPending && report() != null) {
            selectPending = false;
            for (var s : slots) if (menu.selected() != AssemblyReport.NONE && member(s) == menu.selected()) selected = s;
        }
    }

    /** The name entry follows the column's name (another player's rename, this one's answered) while the player has not changed it. */
    private void followName() {
        String now = currentName();
        if (nameShown == null || now.equals(nameShown)) return;
        if (nameDraft != null && nameDraft.equals(nameShown) && (nameBox == null || !nameBox.isFocused())) {
            nameDraft = now;
            if (nameBox != null) nameBox.setValue(now);
        }
        nameShown = now;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        follow();
        refresh();
        var operating = operationView();
        followName();
        if (operating == null) {
            if (apply != null) apply.active = renames();
            return;
        }
        // An entry the player has not edited follows the set points in force; Apply follows what is typed, without a rebuild.
        var values = OperationColumn.values(operating.setpoints(), units());
        for (var e : entries.entrySet())
            if (!edited.contains(e.getKey()) && !e.getValue().isFocused() && !e.getValue().getValue().equals(values.get(e.getKey()))) e.getValue().setValue(values.get(e.getKey()));
        if (apply != null) apply.active = OperationColumn.applies(operationInput(operating), drafts) || renames();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        var skin = skin();
        var canvas = new GuiCanvas(g, font, leftPos, topPos);
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        skin.page(canvas, new Rect(0, 0, imageWidth, imageHeight));
        skin.hDivider(canvas, 8, imageWidth - 8, imageHeight - AssemblyColumn.DIVIDER);
        var operating = operationView();
        var box = skin.well(canvas, AssemblyColumn.glass(imageWidth, imageHeight, drawing() || operating != null));
        list = null;
        listBox = null;
        drawingBox = null;
        if (drawing()) {
            // The drawing at the left of the glass, the parts list at its right, its bar inside its right edge.
            var split = AssemblyColumn.split(box);
            block.fit(split.drawing());
            canvas.clip(split.drawing());
            block.draw(canvas, split.drawing(), report(), selected, currentName(), AssemblyScreen::lang);
            canvas.unclip();
            var area = split.list();
            canvas.vLine(area.x() - AssemblyColumn.GAP / 2, box.y() - 1, box.bottom(), Palette.LINE);
            var lines = new Rect(area.x(), area.y(), area.w() - ScrollView.BAR - 2, area.h());
            var parts = AssemblyColumn.parts(AssemblyColumn.parts(slots, diagram, report(), AssemblyScreen::lang), AssemblyColumn.words(AssemblyScreen::lang),
                    lines.w(), GuiCanvas.measure(font));
            listView.layout(area, area.right() - ScrollView.BAR, parts.height());
            canvas.clip(area);
            AssemblyColumn.draw(canvas, parts, lines, listView.offset(), selected == null ? -1 : slots.indexOf(selected));
            canvas.unclip();
            listView.drawBar(canvas, skin);
            list = parts;
            listBox = lines;
            drawingBox = split.drawing();
        } else if (operating != null) {
            // The tables on the glass; where they are taller than it (a large design in a short window), it scrolls.
            int need = OperationColumn.height(operating, units(), AssemblyScreen::lang);
            glassView.layout(box, box.right() - ScrollView.BAR, need);
            var lines = need > box.h() ? new Rect(box.x(), box.y(), box.w() - ScrollView.BAR - 2, box.h()) : box;
            canvas.clip(box);
            OperationColumn.glass(canvas, lines, glassView.offset(), operating, units(), AssemblyScreen::lang, GuiCanvas.measure(font));
            canvas.unclip();
            glassView.drawBar(canvas, skin);
        } else AssemblyColumn.note(canvas, lang(view().status() == AssemblyView.Status.NO_DESIGN ? "assembly.no_design" : "assembly.operation"), box);
        g.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        int x = mouseX - leftPos, y = mouseY - topPos;
        var skin = skin();
        var canvas = new GuiCanvas(g, font, leftPos, topPos);
        // The title strip: the kind on its plate, the name after it (the entry, or a reader's text).
        var strip = strip();
        skin.title(canvas, TitleStrip.TITLE_X, TitleStrip.TITLE_Y, strip.kind());
        if (strip.kind().length() < title.getString().length() && strip.plate().contains(x, y)) hovered = title.getString();
        if (strip.name() != null && nameBox == null) {
            String name = currentName(), shown = TitleStrip.text(name, strip.name(), GuiCanvas.measure(font));
            int[] at = TitleStrip.textAt(strip.name());
            skin.text(canvas, shown, at[0], at[1], Palette.TEXT);
            if (shown.length() < name.length() && strip.name().contains(x, y)) hovered = name;
        }
        // The status once, on the message display; its detail (where the structure changed) on hover.
        var operating = operationView();
        var status = operating != null ? OperationColumn.status(operating, AssemblyScreen::lang) : AssemblyColumn.status(view(), AssemblyScreen::position, AssemblyScreen::lang);
        var line = AssemblyColumn.display(imageWidth);
        String words = font.plainSubstrByWidth(status.text(), line.w());
        skin.status(canvas, line, words, status.palette());
        if (new Rect(line.x() - 4, line.y() - 5, line.w() + 8, 15).contains(x, y))
            hovered = words.length() < status.text().length() ? status.text() + (status.hint() == null ? "" : "\n" + status.hint()) : status.hint();
        if (drawing()) {
            // Placed again for this frame's texts (the summary follows the engine); the widgets keep their slots.
            door.place(rows(), GuiCanvas.measure(font));
            var hint = door.draw(canvas, skin, x, y);
            if (hint != null) hovered = hint;
        } else if (operating != null) {
            door.place(OperationColumn.rows(operationInput(operating), AssemblyScreen::lang), GuiCanvas.measure(font));
            var hint = door.draw(canvas, skin, x, y);
            if (hint != null) hovered = hint;
        }
    }

    /** Whether a door column shows: the drawing's, or the Operation page's. */
    private boolean door() {
        return drawing() || operationView() != null;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        hovered = null;
        if (door()) door.place(leftPos, topPos);
        // An open tour takes the pointer: the page under it shows no hover (P5 stage B).
        boolean touring = tour.isOpen();
        int mx = touring ? -1 : mouseX, my = touring ? -1 : mouseY;
        super.render(g, mx, my, partialTick);
        if (touring) {
            tour.render(g, font, width, height, mouseX, mouseY);
            return;
        }
        if (drawing()) glassHover(mouseX - leftPos, mouseY - topPos);
        KitDoor.hint(g, font, hovered, mouseX, mouseY);
    }

    /** What a point on the glass reads (door coordinates): a part in the list or on the drawing. */
    private void glassHover(double x, double y) {
        if (list != null && listBox != null && listBox.contains(x, y)) {
            int i = AssemblyColumn.entry(list, listBox, listView.offset(), y);
            var part = i < 0 ? null : diagram.part(slots.get(i));
            if (part != null) hovered = stateText(part).getString();
            return;
        }
        if (drawingBox == null || !drawingBox.contains(x, y)) return;
        var hit = block.part(x, y);
        var part = hit == null ? null : diagram.part(hit);
        if (part != null) hovered = stateText(part).getString();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (tour.isOpen() && tour.mouseClicked(font, width, height, mx, my)) return true;
        SkinField.clickOutside(this, mx, my);
        double x = mx - leftPos, y = my - topPos;
        if (door() && door.press(x, y, button) || drawing() && listView.press(x, y, button) || operationView() != null && glassView.press(x, y, button)) return true;
        if (drawing() && button == 0) {
            Slot hit = null;
            if (list != null && listBox != null && listBox.contains(x, y)) {
                int i = AssemblyColumn.entry(list, listBox, listView.offset(), y);
                if (i >= 0) hit = slots.get(i);
            } else if (drawingBox != null && drawingBox.contains(x, y)) {
                var part = block.part(x, y);
                if (part != null) hit = part;
            }
            if (hit != null) {
                selected = hit;
                refresh();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return door.drag(my - topPos) || listView.drag(my - topPos) || glassView.drag(my - topPos) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        door.release();
        listView.release();
        glassView.release();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (tour.isOpen()) return true;
        double x = mx - leftPos, y = my - topPos;
        return door() && door.wheel(x, y, vertical) || drawing() && listView.wheel(x, y, vertical) || operationView() != null && glassView.wheel(x, y, vertical) || super.mouseScrolled(mx, my, horizontal, vertical);
    }

    // Texts (plan section 5.3).

    /** A slot state in one word: connected, misplaced, missing or not loaded; not checked without a report. */
    public static Component word(@Nullable SlotReport r) {
        return Component.translatable(KEY + AssemblyColumn.word(r));
    }

    private static String position(long at) {
        return at == AssemblyReport.NONE ? "?" : BlockPos.of(at).toShortString();
    }

    private static Component role(String name) {
        return Component.translatable("gui.icheme.vessel.role." + name.toLowerCase(Locale.ROOT));
    }

    /** {@link #found(String)}, the name of the device at {@code at} first where it has one (N1), read from the client's blocks. */
    private static Component found(String detail, long at) {
        var kind = found(detail);
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null || at == AssemblyReport.NONE) return kind;
        String name = com.wormzjl.icheme.world.level.block.EquipmentStructure.name(level, BlockPos.of(at));
        return name.isEmpty() ? kind : Component.translatable("gui.icheme.device.named", name, kind);
    }

    /** A unit or fitting the matcher found, by the word it wrote. */
    private static Component found(String detail) {
        return switch (detail) {
            case "DRUM", "DRUM_NO_BOOT", "DRUM_STANDING", "CONTROL_VALVE", "INLINE_PUMP", "OTHER" -> tr("found." + detail.toLowerCase(Locale.ROOT));
            default -> Component.translatable("gui.icheme.vessel.title." + detail.toLowerCase(Locale.ROOT));
        };
    }

    /** A part's hover: what it is, and its state (not checked yet, with where it goes, before a report). */
    private Component stateText(Part part) {
        var name = text(part.name());
        var where = text(part.where());
        var r = AssemblyColumn.slot(report(), part.slot());
        if (r == null) return tr("state.not_validated", name, where);
        return stateText(name, where, part.wanted() == null ? null : text(part.wanted()), r.state(), r.detail(), r.at());
    }

    /**
     * A slot's state in words (plan section 5.3), for a column's part or an exchanger's line part (the heat GUI): its
     * {@code name}, {@code where} it goes, the kind its slot asks for ({@code wanted}, null: where), and the report's
     * state, detail and the place of its fault.
     */
    public static Component stateText(Component name, Component where, @Nullable Component wanted, AssemblyReport.State state, String detail, long at) {
        return switch (state) {
            case OK -> tr("state.ok", name);
            case MISSING -> detail.isEmpty() ? tr("state.missing", name, where)
                    : detail.equals("terminal") ? tr("state.missing.terminal", name, position(at))
                    : detail.startsWith("returns:") ? tr("state.missing.returns", name, role(detail.substring(8)))
                    : tr("state.missing.found", name, found(detail, at), position(at));
            case OPEN_END -> detail.equals("unconnected") || at == AssemblyReport.NONE ? tr("state.open_end.unconnected", name, where)
                    : tr("state.open_end", name, position(at));
            case NOT_LOADED -> {
                var pos = BlockPos.of(at);
                yield tr("state.not_loaded", name, pos.getX() >> 4, pos.getZ() >> 4);
            }
            case WRONG_KIND -> tr("state.wrong_kind", name, found(detail, at), wanted == null ? where : wanted);
            case CONFIGURATION -> tr("state.configuration", name, position(at));
            case WRONG_PORT -> detail.equals("backwards") ? tr("state.backwards", name)
                    : detail.startsWith("returns:") ? tr("state.wrong_port.returns", name, role(detail.substring(8)))
                    : tr("state.wrong_port", name, role(detail), where);
            case VALVE_REVERSED -> tr("state.valve_reversed", name);
            case EXTRA_VALVE -> tr("state.extra_valve", name, found(detail, at), position(at));
            case WRONG_ORDER -> tr("state.wrong_order", name, where);
            case BRANCHED -> tr("state.branched", name, position(at));
            case SHARED -> tr("state.shared", name);
            case UNIT_COUNT -> {
                var counts = detail.split(":");
                yield tr("state.unit_count", name, counts[0], counts.length > 1 ? counts[1] : "?");
            }
            case UNIT_SHAPES -> tr("state.unit_shapes", name, position(at));
        };
    }
}
