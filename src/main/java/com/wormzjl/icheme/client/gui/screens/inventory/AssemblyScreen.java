package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Part;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.PortMark;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Text;
import com.wormzjl.icheme.client.gui.common.AssemblyFlowsheet;
import com.wormzjl.icheme.client.gui.common.ConstructorColumn;
import com.wormzjl.icheme.client.gui.common.OperationColumn;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.client.gui.common.kit.WindowScale;
import com.wormzjl.icheme.network.AssemblyNetwork;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
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
 * drawing of its assembly, generated from the column's component design ({@link AssemblyFlowsheet}), each part teal,
 * yellow or red, each port square in its class colour while it still needs a pipe, and the parts list with each part's
 * state in a word; every part reads what it is and its state on hover. On the door, Check parts, whose answer comes with
 * the engine's next bucket, and the selected part with Open unit. A complete structure opens on its Operation tab (a
 * placeholder in this stage), the drawing on its Structure tab. Before the first view arrives, the drawing shows the design
 * all red. Opening replays the last published view; the screen asks for nothing until the player presses Check parts.
 */
public final class AssemblyScreen extends AbstractContainerScreen<AssemblyMenu> {
    private static final String KEY = AssemblyDiagram.KEY;

    private final @Nullable AssemblyFlowsheet flowsheet;
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
        flowsheet = trays == null ? null : new AssemblyFlowsheet(AssemblyDiagram.of(trays));
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
        return flowsheet == null ? AssemblyView.NO_DESIGN : AssemblyView.NOT_VALIDATED;
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
        return flowsheet != null && view().status() != AssemblyView.Status.NO_DESIGN && !operation();
    }

    private AssemblyColumn.Input input() {
        var part = selected == null || flowsheet == null ? null : flowsheet.diagram.part(selected);
        String name = selected == null ? null : part == null ? selected.part() : AssemblyColumn.text(part.name(), AssemblyScreen::lang);
        boolean opens = selected != null && !selected.equals(ColumnBlueprint.COLUMN) && member(selected) != AssemblyReport.NONE;
        return AssemblyColumn.input(slots.size(), report(), queued, name, opens);
    }

    private List<Column.Row> rows() {
        return AssemblyColumn.rows(input(), AssemblyScreen::lang);
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

    /**
     * Whether the Operation page fits a window {@code width} by {@code height} GUI px (WindowScale.Fit): its door column in
     * its area, its tables on the glass.
     */
    private boolean fits(int width, int height) {
        var view = operationView();
        if (view == null) return true;
        int w = Math.max(320, Math.min(760, width - 16)), h = Math.max(220, height - 16);
        var m = GuiCanvas.measure(font);
        var area = AssemblyColumn.area(w, h);
        int tall = Column.place(OperationColumn.rows(operationInput(view), AssemblyScreen::lang), area.x(), area.y(), area.w(), m, Column.Metrics.COMPACT).bounds().h();
        return tall <= area.h() && OperationColumn.height(view) <= AssemblyColumn.glass(w, h, true).h() - 4;
    }

    @Override
    public void removed() {
        super.removed();
        WindowScale.restore(minecraft);
    }

    @Override
    protected void init() {
        follow();
        // The Operation page is designed taller (owner's choice B), at the scale that holds it; the Structure page at the design size.
        if (operationView() != null) WindowScale.apply(minecraft, this, OperationColumn.HEIGHT, this::fits);
        else WindowScale.apply(minecraft, this);
        imageWidth = Math.max(320, Math.min(760, width - 16));
        imageHeight = Math.max(220, height - 16);
        super.init();
        var tabs = AssemblyColumn.tabs(operation(), complete(), AssemblyScreen::lang);
        var tabSlots = AssemblyColumn.tabSlots(imageWidth);
        for (int i = 0; i < tabs.size(); i++) push(tabs.get(i), tabSlots.get(i));
        var footer = AssemblyColumn.footer(imageWidth, imageHeight);
        var operating = operationView();
        var buttons = operating != null ? OperationColumn.footerButtons(OperationColumn.applies(operationInput(operating), drafts), AssemblyScreen::lang)
                : AssemblyColumn.footerButtons(AssemblyScreen::lang);
        apply = null;
        for (int i = 0; i < buttons.size(); i++) {
            var button = SkinButton.of(buttons.get(i), footer.get(i).moved(leftPos, topPos), this::act, AssemblyScreen::skin);
            addRenderableWidget(button);
            if (buttons.get(i).key().equals("apply")) apply = button;
        }
        // The theme key shows the contrast sign; its hover names the panel shown.
        String theme = lang(ClientConfig.theme() == ClientConfig.Theme.DARK ? "fluid.theme.dark" : "fluid.theme.light");
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
        out.add(drawing() + " " + operation());
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
                var view = operationView();
                var values = view == null ? null : OperationColumn.parse(view.setpoints(), drafts, units());
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
        var r = AssemblyFlowsheet.slot(report, slot);
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

    @Override
    protected void containerTick() {
        super.containerTick();
        follow();
        refresh();
        var operating = operationView();
        if (operating == null) return;
        // An entry the player has not edited follows the set points in force; Apply follows what is typed, without a rebuild.
        var values = OperationColumn.values(operating.setpoints(), units());
        for (var e : entries.entrySet())
            if (!edited.contains(e.getKey()) && !e.getValue().isFocused() && !e.getValue().getValue().equals(values.get(e.getKey()))) e.getValue().setValue(values.get(e.getKey()));
        if (apply != null) apply.active = OperationColumn.applies(operationInput(operating), drafts);
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
            flowsheet.fit(split.drawing());
            canvas.clip(split.drawing());
            flowsheet.draw(canvas, report(), selected, t -> AssemblyColumn.text(t, AssemblyScreen::lang));
            canvas.unclip();
            var area = split.list();
            canvas.vLine(area.x() - AssemblyColumn.GAP / 2, box.y() - 1, box.bottom(), Palette.LINE);
            var lines = new Rect(area.x(), area.y(), area.w() - ScrollView.BAR - 2, area.h());
            var parts = AssemblyColumn.parts(AssemblyColumn.parts(slots, flowsheet.diagram, report(), AssemblyScreen::lang), AssemblyColumn.words(AssemblyScreen::lang),
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
            int need = OperationColumn.height(operating);
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
        String name = title.getString(), shown = font.plainSubstrByWidth(name, AssemblyColumn.title(imageWidth));
        skin.title(canvas, 10, 10, shown);
        if (shown.length() < name.length() && x >= 6 && x < AssemblyColumn.title(imageWidth) + 14 && y >= 6 && y < 22) hovered = name;
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
        super.render(g, mouseX, mouseY, partialTick);
        if (drawing()) glassHover(mouseX - leftPos, mouseY - topPos);
        KitDoor.hint(g, font, hovered, mouseX, mouseY);
    }

    /** What a point on the glass reads (door coordinates): a part in the list, a port, a part or a stream's end on the drawing. */
    private void glassHover(double x, double y) {
        if (list != null && listBox != null && listBox.contains(x, y)) {
            int i = AssemblyColumn.entry(list, listBox, listView.offset(), y);
            var part = i < 0 ? null : flowsheet.diagram.part(slots.get(i));
            if (part != null) hovered = stateText(part).getString();
            return;
        }
        if (drawingBox == null || !drawingBox.contains(x, y)) return;
        var port = flowsheet.port(x, y);
        if (port != null) {
            hovered = portText(port);
            return;
        }
        var part = flowsheet.part(x, y);
        if (part != null) {
            hovered = stateText(part).getString();
            return;
        }
        var pill = flowsheet.pill(x, y);
        if (pill != null) hovered = text(pill.text()).getString();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double x = mx - leftPos, y = my - topPos;
        if (door() && door.press(x, y, button) || drawing() && listView.press(x, y, button) || operationView() != null && glassView.press(x, y, button)) return true;
        if (drawing() && button == 0) {
            Slot hit = null;
            if (list != null && listBox != null && listBox.contains(x, y)) {
                int i = AssemblyColumn.entry(list, listBox, listView.offset(), y);
                if (i >= 0) hit = slots.get(i);
            } else if (drawingBox != null && drawingBox.contains(x, y)) {
                var part = flowsheet.part(x, y);
                if (part != null) hit = part.slot();
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
        var r = AssemblyFlowsheet.slot(report(), part.slot());
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
                    : tr("state.missing.found", name, found(detail), position(at));
            case OPEN_END -> detail.equals("unconnected") || at == AssemblyReport.NONE ? tr("state.open_end.unconnected", name, where)
                    : tr("state.open_end", name, position(at));
            case NOT_LOADED -> {
                var pos = BlockPos.of(at);
                yield tr("state.not_loaded", name, pos.getX() >> 4, pos.getZ() >> 4);
            }
            case WRONG_KIND -> tr("state.wrong_kind", name, found(detail), wanted == null ? where : wanted);
            case WRONG_PORT -> detail.equals("backwards") ? tr("state.backwards", name)
                    : detail.startsWith("returns:") ? tr("state.wrong_port.returns", name, role(detail.substring(8)))
                    : tr("state.wrong_port", name, role(detail), where);
            case VALVE_REVERSED -> tr("state.valve_reversed", name);
            case EXTRA_VALVE -> tr("state.extra_valve", name, found(detail), position(at));
            case WRONG_ORDER -> tr("state.wrong_order", name, where);
            case BRANCHED -> tr("state.branched", name, position(at));
            case SHARED -> tr("state.shared", name);
        };
    }

    /** A port's hover: what it is and where it pipes to, then its state. */
    private String portText(PortMark m) {
        var partner = flowsheet.diagram.part(m.partner());
        var head = tr("port", role(m.role().name()), Component.translatable("gui.icheme.vessel.port." + m.role().port.name().toLowerCase(Locale.ROOT)),
                partner == null ? Component.empty() : text(partner.name()));
        PortState state = AssemblyFlowsheet.port(report(), m);
        return head.getString() + "\n" + tr("port." + (state == null ? "needed" : state.name().toLowerCase(Locale.ROOT))).getString();
    }
}
