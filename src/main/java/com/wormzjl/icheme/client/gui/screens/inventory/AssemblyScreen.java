package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Align;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Glyph;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Part;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.PortMark;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Text;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.network.AssemblyNetwork;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.world.inventory.AssemblyMenu;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A column's GUI (batch 2026-10-26-multi-machine-framework, plan section 8): the plant drawing of its assembly, generated
 * from the column's component design, each part teal, yellow or red, each port square in its class colour while it still
 * needs a pipe; the parts list with each part's state in words; "Validate structure", whose answer comes with the engine's
 * next bucket. A complete structure shows the column's operation (a placeholder in this stage), with the drawing on a
 * Structure tab. Before the first view arrives, the drawing shows the design all red.
 */
public final class AssemblyScreen extends AbstractContainerScreen<AssemblyMenu> {
    private static final String KEY = AssemblyDiagram.KEY;
    private static final int ROW = 11, LIST_MIN = 150, LIST_MAX = 260;
    /** The design's labels against the 9-pixel font. */
    private static final double FONT = 1.2;

    private final @Nullable ColumnTrays trays;
    private final @Nullable AssemblyDiagram diagram;
    private final List<Slot> slots;
    private final ProcessUi.ProcessScroll scroll = new ProcessUi.ProcessScroll();
    private @Nullable Slot selected;
    private boolean selectPending = true, structureTab, queued;
    private @Nullable AssemblyView seen, pressedAt;
    private Button validate, openUnit, datasheet, operationTab, structureTabButton;
    /** The drawing's origin and scale, the list's left edge and width: set in {@link #init}. */
    private int ox, oy, listX, listW, bodyTop, bodyBottom;
    private double scale;

    public AssemblyScreen(AssemblyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        var level = Minecraft.getInstance().level;
        var column = level == null ? null : EquipmentStructure.find(level, menu.cell());
        var t = column == null ? null : column.shape().trays();
        trays = t != null && t.components() != null ? t : null;
        diagram = trays == null ? null : AssemblyDiagram.of(trays);
        slots = trays == null ? List.of() : ColumnBlueprint.of(trays).slots();
        inventoryLabelY = 10_000;
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

    /** The view shown: the last delivered, or, before the first, the design as not validated. */
    private AssemblyView view() {
        var view = menu.view();
        if (view != null) return view;
        return trays == null ? AssemblyView.NO_DESIGN : AssemblyView.NOT_VALIDATED;
    }

    private @Nullable AssemblyReport report() {
        return view().report();
    }

    @Override
    protected void init() {
        imageWidth = Math.max(320, Math.min(760, width - 16));
        imageHeight = Math.max(220, height - 16);
        super.init();
        bodyTop = 34;
        bodyBottom = imageHeight - 30;
        // The drawing is sized by the height first; the parts list takes the width it leaves, at least LIST_MIN.
        int areaH = bodyBottom - bodyTop;
        scale = Math.min(areaH / (double) AssemblyDiagram.HEIGHT, (imageWidth - 24 - LIST_MIN) / (double) AssemblyDiagram.WIDTH);
        listW = Math.min(LIST_MAX, imageWidth - 24 - (int) Math.ceil(AssemblyDiagram.WIDTH * scale));
        listX = imageWidth - listW - 8;
        int areaW = listX - 16;
        ox = leftPos + 8 + (int) ((areaW - AssemblyDiagram.WIDTH * scale) / 2);
        oy = topPos + bodyTop + (int) ((areaH - AssemblyDiagram.HEIGHT * scale) / 2);
        int y = topPos + imageHeight - 25;
        validate = addRenderableWidget(Button.builder(tr("validate"), b -> press()).bounds(leftPos + 8, y, 130, 20).build());
        openUnit = addRenderableWidget(Button.builder(tr("open_unit"), b -> open(member(selected))).bounds(leftPos + imageWidth - 196, y, 92, 20).build());
        datasheet = addRenderableWidget(Button.builder(tr("datasheet"), b -> open(menu.origin().asLong())).bounds(leftPos + imageWidth - 100, y, 92, 20).build());
        operationTab = addRenderableWidget(Button.builder(tr("tab.operation"), b -> structureTab = false).bounds(leftPos + imageWidth - 172, topPos + 6, 80, 16).build());
        structureTabButton = addRenderableWidget(Button.builder(tr("tab.structure"), b -> structureTab = true).bounds(leftPos + imageWidth - 88, topPos + 6, 80, 16).build());
        containerTick();
    }

    private void press() {
        PacketDistributor.sendToServer(new AssemblyNetwork.ValidatePayload(menu.containerId));
        queued = true;
        pressedAt = menu.view();
    }

    private void open(long member) {
        if (member != AssemblyReport.NONE) PacketDistributor.sendToServer(new AssemblyNetwork.OpenUnitPayload(menu.containerId, member));
    }

    /** The unit the slot holds, if the report holds it as a member (the column always); else none. */
    private long member(@Nullable Slot slot) {
        if (slot == null) return AssemblyReport.NONE;
        if (slot.equals(ColumnBlueprint.COLUMN)) return menu.origin().asLong();
        var report = report();
        var r = report == null ? null : slotReport(slot);
        return r != null && r.member() != AssemblyReport.NONE && report.members().contains(r.member()) ? r.member() : AssemblyReport.NONE;
    }

    private @Nullable SlotReport slotReport(Slot slot) {
        var report = report();
        if (report == null) return null;
        for (var s : report.slots()) if (s.slot().equals(slot)) return s;
        return null;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        var view = view();
        if (menu.view() != seen) {
            // A new view: a pending press is answered; a structure that turned complete opens on its operation.
            if (queued && menu.view() != pressedAt) queued = false;
            if (view.status() == AssemblyView.Status.COMPLETE && (seen == null || seen.status() != AssemblyView.Status.COMPLETE)) structureTab = false;
            seen = menu.view();
        }
        if (selectPending && report() != null) {
            selectPending = false;
            for (var s : slots) if (menu.selected() != AssemblyReport.NONE && member(s) == menu.selected()) selected = s;
        }
        boolean designed = view.status() != AssemblyView.Status.NO_DESIGN, complete = view.status() == AssemblyView.Status.COMPLETE;
        validate.visible = designed && !complete;
        validate.active = !queued;
        openUnit.visible = designed && drawing();
        openUnit.active = selected != null && !selected.equals(ColumnBlueprint.COLUMN) && member(selected) != AssemblyReport.NONE;
        operationTab.visible = structureTabButton.visible = complete;
        operationTab.active = structureTab;
        structureTabButton.active = !structureTab;
        datasheet.visible = true;
    }

    /** Whether the drawing shows: a designed column, not on its operation tab. */
    private boolean drawing() {
        var status = view().status();
        return diagram != null && status != AssemblyView.Status.NO_DESIGN && (status != AssemblyView.Status.COMPLETE || structureTab);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, ProcessUi.BG);
        g.renderOutline(leftPos, topPos, imageWidth, imageHeight, ProcessUi.LINE);
        if (!drawing()) return;
        drawDiagram(g);
        drawList(g);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, tr("heading", menu.origin().toShortString()), 8, 8, ProcessUi.TEXT, false);
        var view = view();
        int colour = switch (view.status()) {
            case NO_DESIGN, NOT_VALIDATED -> ProcessUi.WARN;
            case CHECKED -> ProcessUi.MUTED;
            case COMPLETE -> ProcessUi.ACCENT;
        };
        g.drawString(font, font.plainSubstrByWidth(status(view).getString(), imageWidth - 16), 8, 20, colour, false);
        if (view.status() == AssemblyView.Status.NO_DESIGN) {
            int y = bodyTop + 10;
            for (var line : font.split(tr("no_design"), imageWidth - 32)) {
                g.drawString(font, line, 16, y, ProcessUi.TEXT, false);
                y += 11;
            }
        } else if (view.status() == AssemblyView.Status.COMPLETE && !structureTab)
            g.drawCenteredString(font, tr("operation"), imageWidth / 2, (bodyTop + bodyBottom) / 2, ProcessUi.TEXT);
        if (queued) g.drawString(font, tr("queued"), 144, imageHeight - 19, ProcessUi.MUTED, false);
    }

    private Component status(AssemblyView view) {
        var report = view.report();
        return switch (view.status()) {
            case NO_DESIGN -> tr("status.no_design");
            case NOT_VALIDATED -> view.changedAt() != AssemblyReport.NONE ? tr("status.changed", BlockPos.of(view.changedAt()).toShortString())
                    : tr("status.not_validated", Math.max(0, slots.size() - 1));
            case CHECKED -> tr("status.checked", report.connected(), report.misplaced(), report.missing());
            case COMPLETE -> tr("status.complete");
        };
    }

    // The drawing, through the kit's flowsheet painter (batch 2026-10-28-gui-toolkit).

    /** A part's look: fill, line, text. */
    private Look look(Part part) {
        var r = slotReport(part.slot());
        if (part.glyph() == Glyph.COLUMN && (r == null || r.state() == AssemblyReport.State.OK)) return new Look(FlowsheetPainter.BODY, ProcessUi.LINE, ProcessUi.TEXT);
        return r == null ? Look.RED : Look.of(r.state().colour);
    }

    private static float anchor(Align align) {
        return switch (align) {
            case LEFT -> 0;
            case CENTRE -> 0.5f;
            case RIGHT -> 1;
        };
    }

    private void drawDiagram(GuiGraphics g) {
        var d = diagram;
        var p = new FlowsheetPainter(new GuiCanvas(g, font, 0, 0), ox, oy, scale, FONT);
        for (var w : d.wires()) p.wire(w.points(), w.arrow(), w.utility(), w.utility() ? ProcessUi.LINE : ProcessUi.MUTED);
        for (var t : d.trays()) g.hLine(p.x(t[0]), p.x(t[2]), p.y(t[1]), ProcessUi.LINE);
        for (var pill : d.pills()) {
            p.pill(pill.x(), pill.y(), pill.w(), 11);
            p.fitted(text(pill.text()).getString(), pill.x() + pill.w() / 2, pill.y(), pill.w(), ProcessUi.MUTED);
        }
        for (var part : d.parts()) part(p, part);
        // Free labels only where the font keeps the design's proportion; below it they would cover the parts.
        if (p.labels()) for (var l : d.labels()) p.label(text(l.text()).getString(), l.x(), l.y(), anchor(l.align()), ProcessUi.MUTED);
        for (var m : d.ports()) port(p, m);
    }

    private void part(FlowsheetPainter p, Part part) {
        var look = look(part);
        int x0 = p.x(part.x()), y0 = p.y(part.y()), x1 = p.x(part.x() + part.w()), y1 = p.y(part.y() + part.h());
        switch (part.glyph()) {
            case COLUMN, UNIT, DRUM -> {
                if (part.glyph() == Glyph.DRUM) p.box(p.x(part.x() + 60), p.y(part.y() + 26), p.x(part.x() + 82), p.y(part.y() + 64), look);
                p.box(x0, y0, x1, y1, look);
            }
            case TEE -> p.tee(x0, y0, x1, y1, look);
            case VALVE -> p.valve((x0 + x1) / 2, p.y(part.y() + part.h() - 6), look);
            default -> {
                boolean down = part.glyph() == Glyph.PUMP_DOWN || part.glyph() == Glyph.INLINE_PUMP_DOWN;
                boolean standard = part.glyph() == Glyph.PUMP_DOWN || part.glyph() == Glyph.PUMP_ACROSS;
                int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
                if (standard) {
                    int m = p.px(4), o = p.px(10), s = p.px(8);
                    if (down) p.canvas().fill(cx + o, cy - m, cx + o + s, cy + m, look.stroke());
                    else p.canvas().fill(cx - m, cy + o, cx + m, cy + o + s, look.stroke());
                }
                p.circle(cx, cy, Math.max(2, (x1 - x0) / 2), look);
            }
        }
        if (part.label() != null) p.fitted(text(part.label()).getString(), part.x() + part.w() / 2, part.y() + part.h() / 2, part.w(), look.text());
        if (part.slot().equals(selected)) p.canvas().outline(x0 - 2, y0 - 2, x1 - x0 + 4, y1 - y0 + 4, ProcessUi.TEXT);
    }

    /** A port's state, or null while there is no report or no unit in its slot: it still needs its pipe. */
    private @Nullable PortState portState(PortMark m) {
        var report = report();
        var r = report == null ? null : slotReport(m.slot());
        if (r == null || r.member() == AssemblyReport.NONE) return null;
        PortState found = null;
        for (var p : report.ports()) {
            if (p.unit() != r.member() || p.role() != m.role() || m.ordinal() >= 0 && p.ordinal() != m.ordinal()) continue;
            if (m.ordinal() >= 0 || p.state() == PortState.CONNECTED) return p.state();
            found = p.state();
        }
        return found;
    }

    private void port(FlowsheetPainter p, PortMark m) {
        var state = portState(m);
        if (state == PortState.UNUSED) return;
        p.square(m.x(), m.y(), PortMark.SIZE, state == PortState.CONNECTED ? PortColours.CONNECTED : PortColours.of(m.role().port));
    }

    // The parts list.

    private int rows() {
        return Math.max(1, (bodyBottom - bodyTop - 4) / ROW);
    }

    private void drawList(GuiGraphics g) {
        int lx = leftPos + listX, top = topPos + bodyTop;
        g.fill(lx, top, lx + listW, topPos + bodyBottom, ProcessUi.PANEL);
        scroll.configure(lx + listW - 9, top + 1, bodyBottom - bodyTop - 2, rows(), slots.size());
        for (int i = 0; i < rows() && scroll.value() + i < slots.size(); i++) {
            var slot = slots.get(scroll.value() + i);
            var part = diagram.part(slot);
            int y = top + 3 + i * ROW;
            if (slot.equals(selected)) g.fill(lx, y - 2, lx + listW - 10, y + ROW - 2, ProcessUi.LINE);
            var word = word(slotReport(slot));
            int wordWidth = font.width(word);
            var name = part == null ? Component.literal(slot.part()) : text(part.name());
            g.drawString(font, font.plainSubstrByWidth(name.getString(), listW - wordWidth - 22), lx + 4, y, ProcessUi.TEXT, false);
            g.drawString(font, word, lx + listW - 12 - wordWidth, y, wordColour(slotReport(slot)), false);
        }
        scroll.draw(g);
    }

    /** A slot state in one word: connected, misplaced, missing or not checked; not validated without a report. */
    public static Component word(@Nullable SlotReport r) {
        if (r == null) return tr("word.not_validated");
        return tr("word." + switch (r.state().colour) {
            case TEAL -> "connected";
            case YELLOW -> "misplaced";
            case RED -> r.state() == AssemblyReport.State.NOT_LOADED ? "not_checked" : "missing";
        });
    }

    private static int wordColour(@Nullable SlotReport r) {
        return (r == null ? Look.RED : Look.of(r.state().colour)).text();
    }

    private @Nullable Slot listSlot(double mx, double my) {
        int lx = leftPos + listX, top = topPos + bodyTop + 1;
        if (mx < lx || mx >= lx + listW - 10 || my < top) return null;
        int i = (int) ((my - top) / ROW);
        return i < rows() && scroll.value() + i < slots.size() ? slots.get(scroll.value() + i) : null;
    }

    private @Nullable Part diagramPart(double mx, double my) {
        double dx = (mx - ox) / scale, dy = (my - oy) / scale;
        Part found = null;
        // The smallest box under the mouse: a valve on a unit's line before the line's unit.
        for (var p : diagram.parts()) if (p.contains(dx, dy) && (found == null || p.w() * p.h() < found.w() * found.h())) found = p;
        return found;
    }

    private @Nullable PortMark diagramPort(double mx, double my) {
        double dx = (mx - ox) / scale, dy = (my - oy) / scale, slack = Math.max(0, 2 / scale - PortMark.SIZE / 2.0);
        for (var m : diagram.ports())
            if (Math.abs(dx - m.x()) <= PortMark.SIZE / 2.0 + slack && Math.abs(dy - m.y()) <= PortMark.SIZE / 2.0 + slack) return m;
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (drawing()) {
            if (scroll.click(mx, my, button)) return true;
            var row = listSlot(mx, my);
            var part = row == null ? diagramPart(mx, my) : null;
            if (button == 0 && (row != null || part != null)) {
                selected = row != null ? row : part.slot();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return scroll.drag(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        scroll.release();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        return drawing() && mx >= leftPos + listX && scroll.wheel(vertical) || super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (!drawing()) return;
        List<Component> tip = new ArrayList<>();
        var port = diagramPort(mouseX, mouseY);
        var row = port == null ? listSlot(mouseX, mouseY) : null;
        var part = port != null ? null : row != null ? diagram.part(row) : diagramPart(mouseX, mouseY);
        if (port != null) tip.addAll(portText(port));
        else if (part != null) tip.add(stateText(part));
        if (tip.isEmpty()) return;
        var lines = new ArrayList<net.minecraft.util.FormattedCharSequence>();
        for (var line : tip) lines.addAll(font.split(line, 240));
        g.renderTooltip(font, lines, mouseX, mouseY);
    }

    // Texts (plan section 5.3).

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

    private Component stateText(Part part) {
        var name = text(part.name());
        var where = text(part.where());
        var r = slotReport(part.slot());
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

    private List<Component> portText(PortMark m) {
        var partner = diagram.part(m.partner());
        var head = tr("port", role(m.role().name()), Component.translatable("gui.icheme.vessel.port." + m.role().port.name().toLowerCase(Locale.ROOT)),
                partner == null ? Component.empty() : text(partner.name()));
        var state = portState(m);
        return List.of(head, tr("port." + (state == null ? "needed" : state.name().toLowerCase(Locale.ROOT))));
    }
}
