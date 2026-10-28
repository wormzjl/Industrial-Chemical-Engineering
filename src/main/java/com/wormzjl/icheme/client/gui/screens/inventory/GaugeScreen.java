package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.world.inventory.GaugeMenu;
import com.wormzjl.icheme.world.item.GaugeSettings;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * A gauge's settings on a panel door (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 2): the Range (its minimum and
 * maximum in the dial's unit, the unit's selector) and a pressure gauge's Reading (gauge or absolute), then Cancel and
 * Save. A unit or reading switch keeps both physical endpoints. The settings come from the opening menu, without process
 * requests.
 */
public final class GaugeScreen extends AbstractContainerScreen<GaugeMenu> {
    private static final int WIDTH = 246;
    private GaugeSettings display;
    private @Nullable GaugeSettings parsed;
    private ProcessUi.NumericDraft minimumDraft, maximumDraft;
    private final KitDoor door = new KitDoor();
    private @Nullable SkinButton save;
    private @Nullable String hovered;

    public GaugeScreen(GaugeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        display = menu.initial();
        draft(display);
        imageWidth = WIDTH;
        inventoryLabelY = 1000;
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    private List<Column.Row> rows(boolean valid) {
        var units = GaugeSettings.Unit.of(display.kind());
        return DialogColumns.gauge(new DialogColumns.Gauge(display.kind() == GaugeSettings.Kind.PRESSURE, units.stream().map(u -> u.symbol).toList(),
                units.indexOf(display.unit()), display.relative(), valid), GaugeScreen::tr);
    }

    @Override
    protected void init() {
        var m = GuiCanvas.measure(font);
        // Sized for the column with its warning line, so the panel keeps its size while the player types.
        var tallest = rows(false);
        int column = Column.place(tallest, 10, DialogColumns.TOP, WIDTH - 20, m, Column.Metrics.COMPACT).bounds().h();
        imageHeight = Math.min(DialogColumns.TOP + column + DialogColumns.FOOTER, height - 8);
        super.init();
        door.layout(new Rect(10, DialogColumns.TOP, WIDTH - 20, imageHeight - DialogColumns.TOP - DialogColumns.FOOTER), tallest, m);
        door.place(rows(true), m);
        door.keep(this::box, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, GaugeScreen::skin)));
        var footer = DialogColumns.footer(WIDTH, imageHeight, 2);
        addRenderableWidget(push(CommonComponents.GUI_CANCEL.getString(), footer.get(0), this::onClose));
        save = addRenderableWidget(push(tr("pipeline_tool.save"), footer.get(1), this::save).primary(true));
        validate();
        door.place(leftPos, topPos);
    }

    private SkinButton push(String text, Rect at, Runnable run) {
        return new SkinButton(leftPos + at.x(), topPos + at.y(), at.w(), at.h(), Component.literal(text), b -> run.run(), GaugeScreen::skin);
    }

    /** The minimum's or the maximum's LCD entry: its draft's text, parsed as the player types. */
    private AbstractWidget box(Column.Field f, Rect slot) {
        var draft = f.key().equals("minimum") ? minimumDraft : maximumDraft;
        var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(f.label()), GaugeScreen::skin);
        box.setMaxLength(40);
        box.setValue(draft.text());
        box.setResponder(text -> {
            draft.text(text);
            validate();
        });
        box.setTooltip(Tooltip.create(Component.literal(f.label())));
        if (f.key().equals("minimum")) setInitialFocus(box);
        return addRenderableWidget(box);
    }

    private void act(String key) {
        var units = GaugeSettings.Unit.of(display.kind());
        if (key.startsWith("unit:")) switchDisplay(units.get(Integer.parseInt(key.substring(5))), display.relative());
        else if (key.startsWith("reading:")) switchDisplay(display.unit(), key.equals("reading:gauge"));
    }

    private void draft(GaugeSettings s) {
        var unit = new DisplayUnit() {
            public double display(double si) {
                return s.display(si);
            }

            public double si(double value) {
                return s.si(value);
            }
        };
        minimumDraft = new ProcessUi.NumericDraft(s.minimum(), unit);
        maximumDraft = new ProcessUi.NumericDraft(s.maximum(), unit);
    }

    private void validate() {
        parsed = null;
        try {
            parsed = new GaugeSettings(display.kind(), minimumDraft.si(), maximumDraft.si(), display.unit(), display.relative());
        } catch (IllegalArgumentException ignored) {
            // An incomplete or crossed range is a normal editing state: the warning line says so and Save waits.
        }
        if (save != null) save.active = parsed != null;
    }

    /** Shows the range in {@code unit}, gauge or absolute: both physical endpoints stay. A range the drafts do not form stays as typed. */
    private void switchDisplay(GaugeSettings.Unit unit, boolean relative) {
        validate();
        if (parsed == null) return;
        display = parsed.displayAs(unit, relative);
        draft(display);
        rebuildWidgets();
    }

    private void save() {
        validate();
        if (parsed == null) return;
        FluidNetwork.saveGauge(menu.containerId, parsed);
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
        // Placed again for this frame's texts: the warning line follows the drafts; the widgets keep their slots.
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
