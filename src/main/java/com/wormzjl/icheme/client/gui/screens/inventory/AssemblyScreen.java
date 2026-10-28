package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Align;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Glyph;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Part;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.PortMark;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Text;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
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
    private static final int TEAL_FILL = 0xFF12392F, TEAL = 0xFF1D9E75, TEAL_TEXT = 0xFF9FE1CB, YELLOW_FILL = 0xFF3D2E0F, YELLOW = 0xFFEF9F27,
            YELLOW_TEXT = 0xFFFAC775, RED_FILL = 0xFF3F1A1A, RED = 0xFFE24B4A, RED_TEXT = 0xFFF7C1C1, BODY = 0xFF33434D, EDGE = 0xFF444441, ROW = 11,
            LIST_MIN = 150, LIST_MAX = 260;
    /** The design's labels against the 9-pixel font, and the smallest font drawn. */
    private static final double FONT = 1.2, MIN_FONT = 0.5;

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

    // The drawing.

    private int x(int design) {
        return ox + (int) Math.round(design * scale);
    }

    private int y(int design) {
        return oy + (int) Math.round(design * scale);
    }

    private int px(int design) {
        return Math.max(1, (int) Math.round(design * scale));
    }

    /** A part's look: fill, line, text. */
    private int[] look(Part part) {
        var r = slotReport(part.slot());
        if (part.glyph() == Glyph.COLUMN && (r == null || r.state() == AssemblyReport.State.OK)) return new int[]{BODY, ProcessUi.LINE, ProcessUi.TEXT};
        if (r == null) return new int[]{RED_FILL, RED, RED_TEXT};
        return switch (r.state().colour) {
            case TEAL -> new int[]{TEAL_FILL, TEAL, TEAL_TEXT};
            case YELLOW -> new int[]{YELLOW_FILL, YELLOW, YELLOW_TEXT};
            case RED -> new int[]{RED_FILL, RED, RED_TEXT};
        };
    }

    private void drawDiagram(GuiGraphics g) {
        var d = diagram;
        for (var w : d.wires()) wire(g, w);
        for (var t : d.trays()) g.hLine(x(t[0]), x(t[2]), y(t[1]), ProcessUi.LINE);
        for (var p : d.pills()) {
            g.fill(x(p.x()), y(p.y() - 11), x(p.x() + p.w()), y(p.y() + 11), ProcessUi.PANEL);
            g.renderOutline(x(p.x()), y(p.y() - 11), x(p.x() + p.w()) - x(p.x()), y(p.y() + 11) - y(p.y() - 11), ProcessUi.LINE);
            fitted(g, text(p.text()), p.x() + p.w() / 2, p.y(), p.w(), ProcessUi.MUTED);
        }
        for (var part : d.parts()) part(g, part);
        // Free labels only where the font keeps the design's proportion; below it they would cover the parts.
        if (scale * FONT >= MIN_FONT) for (var l : d.labels()) label(g, text(l.text()), l.x(), l.y(), l.align(), ProcessUi.MUTED);
        for (var m : d.ports()) port(g, m);
    }

    private void wire(GuiGraphics g, AssemblyDiagram.Wire w) {
        int[] p = w.points();
        int colour = w.utility() ? ProcessUi.LINE : ProcessUi.MUTED;
        for (int i = 0; i + 3 < p.length; i += 2) {
            int x0 = x(p[i]), y0 = y(p[i + 1]), x1 = x(p[i + 2]), y1 = y(p[i + 3]);
            boolean across = y0 == y1;
            int from = across ? Math.min(x0, x1) : Math.min(y0, y1), to = across ? Math.max(x0, x1) : Math.max(y0, y1);
            // A utility line is dashed: two pixels on, one off.
            for (int a = from; a <= to; a += w.utility() ? 3 : to - from + 1) {
                int b = w.utility() ? Math.min(a + 1, to) : to;
                if (across) g.hLine(a, b, y0, colour);
                else g.fill(x0, a, x0 + 1, b + 1, colour);
            }
        }
        if (!w.arrow() || p.length < 4) return;
        int n = p.length;
        int ex = x(p[n - 2]), ey = y(p[n - 1]), dx = Integer.signum(x(p[n - 2]) - x(p[n - 4])), dy = Integer.signum(y(p[n - 1]) - y(p[n - 3]));
        for (int k = 0; k < 3; k++) {
            int bx = ex - dx * k, by = ey - dy * k;
            if (dx != 0) g.vLine(bx, by - k - 1, by + k + 1, colour);
            else g.hLine(bx - k, bx + k, by, colour);
        }
    }

    private void part(GuiGraphics g, Part part) {
        int[] look = look(part);
        int x0 = x(part.x()), y0 = y(part.y()), x1 = x(part.x() + part.w()), y1 = y(part.y() + part.h());
        switch (part.glyph()) {
            case COLUMN, UNIT, DRUM -> {
                if (part.glyph() == Glyph.DRUM) box(g, x(part.x() + 60), y(part.y() + 26), x(part.x() + 82), y(part.y() + 64), look);
                box(g, x0, y0, x1, y1, look);
            }
            case TEE -> g.fill(x0, y0, Math.max(x1, x0 + 2), Math.max(y1, y0 + 2), look[1]);
            case VALVE -> {
                int cx = (x0 + x1) / 2, cy = y(part.y() + part.h() - 6), hw = Math.max(2, px(8)), hh = Math.max(2, px(6));
                for (int dx = -hw; dx <= hw; dx++) {
                    int h = Math.round(hh * Math.abs(dx) / (float) hw);
                    g.vLine(cx + dx, cy - h - 1, cy + h + 1, look[1]);
                }
                g.vLine(cx, cy - px(11) - 1, cy, look[1]);
                g.fill(cx - px(5), cy - px(16), cx + px(5) + 1, cy - px(11), look[1]);
            }
            default -> {
                boolean down = part.glyph() == Glyph.PUMP_DOWN || part.glyph() == Glyph.INLINE_PUMP_DOWN;
                boolean standard = part.glyph() == Glyph.PUMP_DOWN || part.glyph() == Glyph.PUMP_ACROSS;
                int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, r = Math.max(2, (x1 - x0) / 2);
                if (standard) {
                    int m = px(4), o = px(10), s = px(8);
                    if (down) g.fill(cx + o, cy - m, cx + o + s, cy + m, look[1]);
                    else g.fill(cx - m, cy + o, cx + m, cy + o + s, look[1]);
                }
                for (int dy = -r; dy <= r; dy++) {
                    int half = (int) Math.round(Math.sqrt(r * r - dy * dy));
                    g.hLine(cx - half, cx + half, cy + dy, look[0]);
                    g.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, look[1]);
                    g.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, look[1]);
                }
                g.hLine(cx - 1, cx + 1, cy - r, look[1]);
                g.hLine(cx - 1, cx + 1, cy + r, look[1]);
            }
        }
        if (part.label() != null) fitted(g, text(part.label()), part.x() + part.w() / 2, part.y() + part.h() / 2, part.w(), look[2]);
        if (part.slot().equals(selected)) g.renderOutline(x0 - 2, y0 - 2, x1 - x0 + 4, y1 - y0 + 4, ProcessUi.TEXT);
    }

    private static void box(GuiGraphics g, int x0, int y0, int x1, int y1, int[] look) {
        g.fill(x0, y0, x1, y1, look[0]);
        g.renderOutline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), look[1]);
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

    private void port(GuiGraphics g, PortMark m) {
        var state = portState(m);
        if (state == PortState.UNUSED) return;
        int half = Math.max(2, px(PortMark.SIZE / 2)), cx = x(m.x()), cy = y(m.y());
        int fill = state == PortState.CONNECTED ? PortColours.CONNECTED : PortColours.of(m.role().port);
        g.fill(cx - half - 1, cy - half - 1, cx + half + 1, cy + half + 1, EDGE);
        g.fill(cx - half, cy - half, cx + half, cy + half, 0xFF000000 | fill);
    }

    private float fontScale() {
        return (float) Math.clamp(scale * FONT, MIN_FONT, 1.0);
    }

    /** A label centred on design point ({@code x}, {@code y}), drawn only if it fits {@code width} design units. */
    private void fitted(GuiGraphics g, Component text, int x, int y, int width, int colour) {
        if (font.width(text) * fontScale() <= width * scale - 2) label(g, text, x, y, Align.CENTRE, colour);
    }

    /** A label at design point ({@code x}, {@code y}), in a font scaled with the drawing, never below {@link #MIN_FONT}. */
    private void label(GuiGraphics g, Component text, int x, int y, Align align, int colour) {
        float fs = fontScale();
        float w = font.width(text) * fs;
        float tx = switch (align) {
            case LEFT -> x(x);
            case CENTRE -> x(x) - w / 2;
            case RIGHT -> x(x) - w;
        };
        g.pose().pushPose();
        g.pose().translate(tx, y(y) - font.lineHeight * fs / 2 + fs, 0);
        g.pose().scale(fs, fs, 1);
        g.drawString(font, text, 0, 0, colour, false);
        g.pose().popPose();
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
        if (r == null) return RED_TEXT;
        return switch (r.state().colour) {
            case TEAL -> TEAL_TEXT;
            case YELLOW -> YELLOW_TEXT;
            case RED -> RED_TEXT;
        };
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
