package com.wormzjl.icheme.client.gui.screens;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.MachineAnimation;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import static com.wormzjl.icheme.science.equipment.UnitDesign.number;

/**
 * A column's or a process unit's datasheet on a panel door (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 2): its
 * sizes on the door (the ones the player chose as LCD entries, the rest as LED meters), a moving unit's switch; its
 * kind, support, cells and nozzles on the HMI glass, which scrolls. A drum shows the same datasheet on a tab of its tank
 * screen (owner request 2026-10-16), from {@link #sheet}. It shows geometry only, read from the cells the client already
 * has, so it requests nothing.
 */
public final class VesselScreen extends Screen {
    /** The panel's size where the window has room (wide enough for the nozzle table's three columns), and the door column's width. */
    private static final int WIDTH = 400, HEIGHT = 250, DOOR = 150;
    private static final String KEY = "gui.icheme.vessel.";
    private final DialogColumns.Sheet sheet;
    private final boolean moving;
    private final KitDoor door = new KitDoor();
    private final ScrollView glassView = new ScrollView();
    private int left, top, panelWidth, panelHeight;
    private @Nullable Rect glassBox;
    private @Nullable String hovered;

    public VesselScreen(EquipmentStructure.Vessel vessel) {
        super(Component.translatable(KEY + "title." + vessel.shape().kind().name().toLowerCase(Locale.ROOT)));
        sheet = sheet(vessel);
        moving = vessel.shape().unit() && ProcessUnit.moves(vessel.shape().kind());
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    /**
     * The vessel's datasheet: its sizes (a drum's or a column's diameter and length as chosen, then what follows from
     * them); its notes, its kind first, then a unit's design lines, the support, the cells and the controller; the cells
     * other blocks hold as a warning; each nozzle with its port and the side it faces seen from the vessel's front.
     */
    public static DialogColumns.Sheet sheet(EquipmentStructure.Vessel vessel) {
        var shape = vessel.shape();
        var layout = ShapeRasterizer.layout(shape);
        var sizes = new ArrayList<DialogColumns.Size>();
        var notes = new ArrayList<String>();
        if (shape.designed()) {
            var design = UnitDesign.of(shape.kind());
            String kind = shape.kind().name().toLowerCase(Locale.ROOT);
            notes.add(text(KEY + "kind." + kind + design.variant(shape)));
            for (var line : design.datasheet(shape)) notes.add(text(KEY + line.key(), line.args()));
            notes.add(text(KEY + "unit_support." + kind, shape.support()));
        } else {
            notes.add(shape.column() ? text(KEY + "kind.column", shape.trays().trays())
                    : text(KEY + (shape.saddles() ? "kind.saddles" : shape.skirt() ? "kind.skirt" : "kind.bare")));
            notes.add(shape.saddles() ? text(KEY + "saddles", shape.support(), number(EquipmentShape.SADDLE_POSITION * shape.tangentLength()))
                    : shape.skirt() ? text(KEY + "skirt", shape.support()) : text(KEY + "unsupported"));
            sizes.add(new DialogColumns.Size("diameter", number(shape.diameter()), "m", true));
            sizes.add(new DialogColumns.Size("length", number(shape.tangentLength()), "m", true));
        }
        sizes.add(new DialogColumns.Size("overall", number(shape.overallLength()), "m", false));
        sizes.add(new DialogColumns.Size("top", number(shape.topElevation()), "m", false));
        // A storage vessel's volume and area, as a drum's.
        if (!shape.designed() || shape.storage()) {
            sizes.add(new DialogColumns.Size("volume", number(shape.volume()), "m³", false));
            sizes.add(new DialogColumns.Size("area", number(shape.surfaceArea()), "m²", false));
        }
        notes.add(text(KEY + "cells", layout.shell().size(), layout.interior().size()));
        var cell = layout.controller();
        notes.add(text(KEY + "controller", vessel.origin().offset(cell.x(), cell.y(), cell.z()).toShortString()));
        int blocked = blocked(vessel, layout);
        // Each nozzle: what it is for, the port its position makes it and the side it faces (any pipe connects; its neck
        // takes the pipe's size, owner decision 2026-10-21: a 1 m pipe has a 1 m root).
        var nozzles = new ArrayList<DialogColumns.Nozzle>();
        for (var nozzle : shape.nozzles())
            nozzles.add(new DialogColumns.Nozzle(text(KEY + "role." + nozzle.role().name().toLowerCase(Locale.ROOT)),
                    text(KEY + "port." + nozzle.role().port.name().toLowerCase(Locale.ROOT)), text("gui.icheme.equipment_constructor.side." + side(shape, nozzle))));
        return new DialogColumns.Sheet(sizes, notes, blocked > 0 ? text(KEY + "blocked", blocked) : null, nozzles);
    }

    private static String text(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    /**
     * The side {@code nozzle} faces, as the constructor names sides: top or bottom, else seen from in front of the
     * vessel's front ({@link EquipmentShape#front()}: a standing vessel's liquid outlet side, a lying drum's front head).
     */
    private static String side(EquipmentShape shape, EquipmentShape.Nozzle nozzle) {
        var out = shape.transform().direction(nozzle.direction());
        long x = Math.round(out[0]), y = Math.round(out[1]), z = Math.round(out[2]);
        if (y != 0) return y > 0 ? "top" : "bottom";
        var f = shape.front();
        if (x == f.dx && z == f.dz) return "front";
        if (x == -f.dx && z == -f.dz) return "back";
        // Facing the front, the viewer looks along -front; their right is that turned clockwise seen from above.
        return x == f.dz && z == -f.dx ? "right" : "left";
    }

    /**
     * The layout's cells the client finds no part of the vessel in: cells a refresh left out where other blocks stand
     * ({@code EquipmentRefresh}, batch 2026-10-26-backward-compat), until the player clears them and the world loads again.
     */
    private static int blocked(EquipmentStructure.Vessel vessel, ShapeRasterizer.Layout layout) {
        var level = Minecraft.getInstance().level;
        if (level == null) return 0;
        int found = 0;
        for (var cell : layout.shell())
            if (!(level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell && vessel.origin().equals(shell.origin()))) found++;
        for (var cell : layout.interior())
            if (!level.getBlockState(vessel.origin().offset(cell.x(), cell.y(), cell.z())).is(ModBlocks.EQUIPMENT_INTERIOR.get())) found++;
        return found;
    }

    private List<Column.Row> rows() {
        return DialogColumns.vessel(sheet.sizes(), moving, MachineAnimation.running(), VesselScreen::tr);
    }

    @Override
    protected void init() {
        panelWidth = Math.min(WIDTH, width - 8);
        panelHeight = Math.min(HEIGHT, height - 8);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        var m = GuiCanvas.measure(font);
        var rows = rows();
        door.layout(new Rect(10, DialogColumns.TOP, DOOR, panelHeight - DialogColumns.TOP - DialogColumns.FOOTER), rows, m);
        door.keep(this::size, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, VesselScreen::skin)));
        var done = DialogColumns.footer(panelWidth, panelHeight, 1).getFirst();
        addRenderableWidget(new SkinButton(left + done.x(), top + done.y(), done.w(), done.h(), Component.translatable("gui.done"), b -> onClose(), VesselScreen::skin));
        door.place(left, top);
    }

    /** A size the player chose, as the constructor's LCD entry shows it: read here, not edited. */
    private AbstractWidget size(Column.Field f, Rect slot) {
        var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(f.label()), VesselScreen::skin);
        sheet.sizes().stream().filter(s -> s.key().equals(f.key())).findFirst().ifPresent(s -> box.setValue(s.value()));
        box.setEditable(false);
        box.active = false;
        box.setTooltip(Tooltip.create(Component.literal(f.hint() == null ? f.label() : f.label() + "\n" + f.hint())));
        return addRenderableWidget(box);
    }

    /** The moving parts' switch: every unit's fans, shafts and rods move, or stand still as a stopped mover's do. */
    private void act(String key) {
        if (key.equals("motion:running") != MachineAnimation.running()) MachineAnimation.toggle();
        rebuildWidgets();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        var skin = skin();
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        var canvas = new GuiCanvas(g, font, left, top);
        skin.page(canvas, new Rect(0, 0, panelWidth, panelHeight));
        // The HMI glass right of the door column, from the title row to the footer.
        glassBox = skin.well(canvas, new Rect(DOOR + 16, 25, panelWidth - DOOR - 21, panelHeight - 60));
        g.pose().popPose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        door.place(left, top);
        super.render(g, mouseX, mouseY, partialTick);
        var skin = skin();
        var canvas = new GuiCanvas(g, font, left, top);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        skin.title(canvas, 10, 10, font.plainSubstrByWidth(title.getString(), panelWidth - 20));
        hovered = door.draw(canvas, skin, mouseX - left, mouseY - top);
        if (glassBox != null) {
            // The glass's lines, its bar inside its right edge where they are taller than it.
            var glass = DialogColumns.glass(sheet, false, glassBox.w() - ScrollView.BAR - 2, GuiCanvas.measure(font), VesselScreen::tr);
            glassView.layout(glassBox, glassBox.right() - ScrollView.BAR, glass.height());
            canvas.clip(glassBox);
            DialogColumns.draw(canvas, glass, new Rect(glassBox.x(), glassBox.y(), glassBox.w() - ScrollView.BAR - 2, glassBox.h()), glassView.offset());
            canvas.unclip();
            glassView.drawBar(canvas, skin);
        }
        g.pose().popPose();
        KitDoor.hint(g, font, hovered, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return door.press(x - left, y - top, button) || glassView.press(x - left, y - top, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return door.drag(y - top) || glassView.drag(y - top) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        door.release();
        glassView.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return door.wheel(x - left, y - top, vertical) || glassView.wheel(x - left, y - top, vertical) || super.mouseScrolled(x, y, horizontal, vertical);
    }
}
