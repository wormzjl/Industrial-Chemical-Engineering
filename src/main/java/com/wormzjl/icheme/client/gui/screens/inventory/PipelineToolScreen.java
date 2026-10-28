package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.PaintSwatch;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.world.inventory.PipelineToolMenu;
import com.wormzjl.icheme.world.item.PipelineToolItem;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The pipeline tools' settings on a panel door (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 2); all displayed data
 * comes from the opening menu, without process requests. Both tools set the bore; the laying tool also sets the paints
 * of the lines it lays (batch 2026-10-26-pipe-laying-system): a primary for the pipe and a secondary for the flanges,
 * the edited one latched on the channel selector and picked as on the equipment constructor's Paint page, from its
 * palette, as RRGGBB, or bare steel.
 */
public final class PipelineToolScreen extends AbstractContainerScreen<PipelineToolMenu> {
    private static final int WIDTH = 230;
    private final KitDoor door = new KitDoor();
    private String bore;
    private @Nullable Double parsed;
    /** The paint drafts, saved with the bore, each 0xRRGGBB or {@link Paint#BARE}: the primary ({@link Paint#BODY}) and the secondary ({@link Paint#STRUCTURE}). */
    private final int[] paints = new int[2];
    /** The channel the palette, the custom field and bare steel edit. */
    private int channel = Paint.BODY;
    private @Nullable EditBox custom;
    private @Nullable SkinButton bare, save;
    private @Nullable String hovered;

    public PipelineToolScreen(PipelineToolMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        inventoryLabelY = 1000;
        bore = Double.toString(menu.initialSettings().diameter());
        paints[Paint.BODY] = menu.initialPaint();
        paints[Paint.STRUCTURE] = menu.initialSecondary();
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    private List<Column.Row> rows(boolean valid) {
        return DialogColumns.pipelineTool(new DialogColumns.PipelineTool(menu.paints(), channel, paints[channel] == Paint.BARE, valid), PipelineToolScreen::tr);
    }

    @Override
    protected void init() {
        var m = GuiCanvas.measure(font);
        // Sized for the column with its warning line, so the panel keeps its size while the player types.
        var tallest = rows(false);
        int column = Column.place(tallest, 10, DialogColumns.TOP, WIDTH - 20, m, Column.Metrics.COMPACT).bounds().h();
        imageHeight = Math.min(DialogColumns.TOP + column + DialogColumns.FOOTER, height - 8);
        super.init();
        custom = null;
        bare = null;
        door.layout(new Rect(10, DialogColumns.TOP, WIDTH - 20, imageHeight - DialogColumns.TOP - DialogColumns.FOOTER), tallest, m);
        var placed = door.place(rows(true), m);
        door.keep(this::box, (spec, slot) -> {
            var button = addRenderableWidget(SkinButton.of(spec, slot, this::act, PipelineToolScreen::skin));
            if (spec.key().equals("bare")) bare = button;
            return button;
        });
        // The palette in the Paint group's gap: each swatch outlined while it is the edited colour.
        var swatches = DialogColumns.palette(placed, Paint.PALETTE.size());
        for (int i = 0; i < swatches.size(); i++) {
            var slot = swatches.get(i);
            var swatch = new PaintSwatch(slot.x(), slot.y(), Paint.PALETTE.get(i), () -> paints[channel], this::paint);
            swatch.setWidth(slot.w());
            door.keep(addRenderableWidget(swatch), slot);
        }
        var footer = DialogColumns.footer(WIDTH, imageHeight, 2);
        addRenderableWidget(push(CommonComponents.GUI_CANCEL.getString(), footer.get(0), this::onClose));
        save = addRenderableWidget(push(tr("pipeline_tool.save"), footer.get(1), this::save).primary(true));
        validate();
        door.place(leftPos, topPos);
    }

    private SkinButton push(String text, Rect at, Runnable run) {
        return new SkinButton(leftPos + at.x(), topPos + at.y(), at.w(), at.h(), Component.literal(text), b -> run.run(), PipelineToolScreen::skin);
    }

    /** The bore's LCD entry, or the custom colour's: the edited channel's colour as RRGGBB, painting it once six digits are typed. */
    private AbstractWidget box(Column.Field f, Rect slot) {
        var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(f.label()), PipelineToolScreen::skin);
        box.setTooltip(Tooltip.create(Component.literal(f.hint() == null ? f.label() : f.label() + "\n" + f.hint())));
        if (f.key().equals("bore")) {
            box.setMaxLength(32);
            box.setValue(bore);
            box.setResponder(text -> {
                bore = text;
                validate();
            });
            setInitialFocus(box);
        } else {
            box.setMaxLength(7);
            box.setFilter(text -> text.matches("#?[0-9a-fA-F]{0,6}"));
            box.setValue(rgb(paints[channel]));
            box.setResponder(text -> {
                if (Paint.isHex(text)) paint(Paint.parseHex(text));
            });
            custom = box;
        }
        return addRenderableWidget(box);
    }

    /** A colour as the custom field shows it: RRGGBB (its # left out, so six digits fit the box), blank for bare steel. */
    private static String rgb(int colour) {
        return colour == Paint.BARE ? "" : Paint.hex(colour).substring(1);
    }

    private void act(String key) {
        if (key.startsWith("channel:")) {
            channel = Integer.parseInt(key.substring(8));
            rebuildWidgets();
        } else if (key.equals("bare")) paint(Paint.BARE);
    }

    /**
     * Paints the edited channel {@code rgb}: bare steel latches while it is bare, and the custom field shows the colour
     * unless typed in. As on the constructor's Paint page, an unchanged colour stops here: the field's responder hears
     * its own setValue.
     */
    private void paint(int rgb) {
        if (paints[channel] == rgb) return;
        paints[channel] = rgb;
        if (bare != null) {
            bare.active = rgb != Paint.BARE;
            bare.latched(rgb == Paint.BARE);
        }
        if (custom != null && !custom.isFocused()) custom.setValue(rgb(rgb));
    }

    private void validate() {
        parsed = null;
        try {
            double value = Double.parseDouble(bore.trim());
            if (PipelineToolItem.Settings.valid(value)) parsed = value;
        } catch (NumberFormatException invalid) {
            // Incomplete text is a normal editing state; retain it until the player enters a valid value.
        }
        if (save != null) save.active = parsed != null;
    }

    private void save() {
        validate();
        if (parsed == null) return;
        FluidNetwork.savePipelineTool(menu.containerId, parsed, menu.paints() ? paints[Paint.BODY] : Paint.BARE, menu.paints() ? paints[Paint.STRUCTURE] : Paint.BARE);
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        door.place(leftPos, topPos);
        hovered = null;
        super.render(g, mouseX, mouseY, partialTick);
        KitDoor.hint(g, font, hovered, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        skin().page(new GuiCanvas(g, font, leftPos, topPos), new Rect(0, 0, imageWidth, imageHeight));
        g.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var skin = skin();
        var canvas = new GuiCanvas(g, font, leftPos, topPos);
        skin.title(canvas, 10, 10, font.plainSubstrByWidth(title.getString(), imageWidth - 30));
        // Placed again for this frame's texts: the warning line follows the bore; the widgets keep their slots.
        door.place(rows(parsed != null), GuiCanvas.measure(font));
        hovered = door.draw(canvas, skin, mouseX - leftPos, mouseY - topPos);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return door.press(x - leftPos, y - topPos, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return door.drag(y - topPos) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        door.release();
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return door.wheel(x - leftPos, y - topPos, vertical) || super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        if (ProcessUi.textKeyPressed(this, key, scan, modifiers)) return true;
        return super.keyPressed(key, scan, modifiers);
    }
}
