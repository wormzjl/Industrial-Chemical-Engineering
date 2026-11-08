package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.gui.common.DeviceColumn;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.MachineColumn;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Format;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.network.MachineNetwork;
import com.wormzjl.icheme.runtime.fluid.MachineView;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.world.inventory.MachineMenu;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * A machine's own page (batch 2026-11-03-rotating-machinery, W7, owner decision D31: option A): the device page's frame
 * ({@link FluidGuiLayout}: the title strip with the device's name, one tab, the message display, the door column, the HMI
 * glass, the footer), the door column from {@link MachineColumn}, the nameplate on the glass. It shows the view its
 * session last received; before the first it says it waits. Apply and Reset send an edit; the page says it waits until the
 * bucket that answers it, and a refusal's reason goes on the message display (reading A87).
 */
public final class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    private final KitDoor door = new KitDoor();
    /** The drafts: the switch's position, what a driver regulates, the entries as typed; taken from the view until edited. */
    private boolean running = true, drafted;
    private MachineColumn.Mode mode = MachineColumn.Mode.SPEED;
    private String speed = "", limit = "", ratio = "", nameDraft, message = "";
    private int sequence;
    private @Nullable String hovered;
    private String signature = "";

    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        inventoryLabelY = 1000;
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    private static String mt(String key, Object... args) {
        return tr(MachineColumn.KEY + key, args);
    }

    private boolean canEdit() {
        return menu.view() != null && minecraft != null && minecraft.player != null && minecraft.player.mayBuild();
    }

    /** Whether an edit awaits its answer. */
    private boolean waiting() {
        return sequence > menu.answered();
    }

    private MachineColumn.Kind kind() {
        return Objects.requireNonNull(MachineColumn.Kind.of(menu.kind()));
    }

    /** The page's rows: the view with the drafts, or, before the first view, the kind's rows with nothing read yet. */
    private List<Column.Row> rows() {
        var v = menu.view();
        if (v == null) return MachineColumn.rows(new MachineColumn.Input(kind(), false, running, true, mode, false, null, List.of(), List.of(), null), MachineScreen::tr);
        var rows=new java.util.ArrayList<>(MachineColumn.rows(MachineColumn.input(v, canEdit(), running, mode, MachineScreen::tr), MachineScreen::tr));
        rows.addAll(DeviceColumn.instrumentIds(com.wormzjl.icheme.client.InstrumentRenderer.identities(minecraft.level,menu.origin()),(key,args)->tr("fluid."+key,args)));
        return java.util.List.copyOf(rows);
    }

    /** The structure's name, as its cells hold it (empty for a creative motor or none). */
    private String currentName() {
        return minecraft == null || minecraft.level == null || !menu.datasheet() ? "" : EquipmentStructure.name(minecraft.level, menu.origin());
    }

    private TitleStrip.Strip strip() {
        return TitleStrip.place(imageWidth, 18, title.getString(), GuiCanvas.measure(font));
    }

    @Override
    protected void init() {
        imageWidth = FluidGuiLayout.imageWidth(width);
        imageHeight = FluidGuiLayout.imageHeight(height);
        super.init();
        titleLabelY = -1000;
        if (nameDraft == null) nameDraft = currentName();
        follow();
        var m = GuiCanvas.measure(font);
        var rows = rows();
        int side = FluidGuiLayout.side(imageWidth);
        door.layout(new Rect(10, FluidGuiLayout.DOOR_TOP, side - 10, imageHeight - FluidGuiLayout.DOOR_FOOT - FluidGuiLayout.DOOR_TOP), rows, m);
        door.keep(this::box, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, MachineScreen::skin)));
        signature = signature(rows);
        // The one tab, latched; the theme key.
        var tab = FluidGuiLayout.tabs(imageWidth, 1).getFirst();
        var overview = push(tr("fluid.overview"), new Rect(tab.x(), 29, tab.width(), 18), () -> {}).latched(true);
        overview.active = false;
        String theme = tr(ClientConfig.theme() == ClientConfig.Theme.DARK ? "fluid.theme.dark" : "fluid.theme.light");
        push(theme, new Rect(imageWidth - 28, 5, 18, 18), () -> {
            ClientConfig.toggleTheme();
            rebuildWidgets();
        }).icon(Skin.Icon.THEME).setTooltip(Tooltip.create(Component.literal(theme + "\n").append(Component.translatable("gui.icheme.fluid.theme.hint"))));
        var place = strip().name();
        if (place != null && menu.datasheet() && canEdit()) nameBox(place);
        // Close, the Datasheet push button (A89) and Apply, the confirm key, under the footer's divider; a coupling has no Apply (W6b).
        int x = 10, by = imageHeight - 27;
        push(tr("fluid.close"), new Rect(x, by, 50, 18), this::onClose);
        x += 56;
        if (menu.datasheet()) {
            push(tr("fluid.datasheet"), new Rect(x, by, 60, 18), () -> MachineNetwork.datasheet(menu.containerId))
                    .setTooltip(Tooltip.create(Component.translatable("gui.icheme.fluid.datasheet.hint")));
            x += 66;
        }
        if (canEdit() && kind().settable()) push(tr("fluid.apply"), new Rect(x, by, 60, 18), this::apply).primary(true).active = !waiting();
        door.place(leftPos, topPos);
    }

    private SkinButton push(String text, Rect at, Runnable run) {
        return addRenderableWidget(new SkinButton(leftPos + at.x(), topPos + at.y(), at.w(), at.h(), Component.literal(text), b -> run.run(), MachineScreen::skin));
    }

    private void nameBox(Rect slot) {
        var box = new SkinField(font, leftPos + slot.x(), topPos + slot.y(), slot.w(), slot.h(), Component.literal(tr("fluid.name")), MachineScreen::skin);
        box.setMaxLength(com.wormzjl.icheme.science.equipment.DeviceName.MAX);
        box.setValue(nameDraft);
        box.setResponder(v -> nameDraft = v);
        box.setHint(Component.literal(tr("fluid.name")));
        box.setTooltip(Tooltip.create(Component.literal(tr("fluid.name") + "\n" + tr("fluid.name.hint"))));
        addRenderableWidget(box);
    }

    /** An entry of the door column: its draft's text. */
    private AbstractWidget box(Column.Field f, Rect slot) {
        var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(f.label()), MachineScreen::skin);
        box.setMaxLength(24);
        box.setValue(switch (f.key()) {
            case "speed" -> speed;
            case "powerLimit" -> limit;
            default -> ratio;
        });
        box.setResponder(text -> {
            switch (f.key()) {
                case "speed" -> speed = text;
                case "powerLimit" -> limit = text;
                default -> ratio = text;
            }
        });
        box.setTooltip(Tooltip.create(Component.literal(f.hint() == null ? f.label() : f.label() + "\n" + f.hint())));
        return addRenderableWidget(box);
    }

    /** Takes the drafts from the view: at the first view, and when an answer arrives while the player has not edited since. */
    private void follow() {
        var v = menu.view();
        if (v == null || drafted || waiting()) return;
        drafted = true;
        running = v.settings().running();
        mode = MachineColumn.Mode.of(v.settings().mode());
        speed = String.format(Locale.ROOT, "%.0f", MachineColumn.speedEntry(v));
        limit = v.settings().powerLimit() > 0 ? Format.number(v.settings().powerLimit() / 1000) : "";
        ratio = v.settings().ratio() > 0 ? String.format(Locale.ROOT, "%.2f", v.settings().ratio()) : "0";
    }

    private void act(String key) {
        message = "";
        if (key.equals("power:off") || key.equals("power:on")) running = key.equals("power:on");
        else if(key.equals("mode:manual"))mode=MachineColumn.Mode.SPEED;
        else if(key.equals("mode:auto")&&mode==MachineColumn.Mode.SPEED)mode=MachineColumn.Mode.FLOW;
        else if (key.startsWith("regulate:")) mode = MachineColumn.Mode.valueOf(key.substring(9).toUpperCase(Locale.ROOT));
        else if (key.equals("reset")) send(MachineNetwork.Action.RESET, Objects.requireNonNull(menu.view()).settings());
        rebuildWidgets();
    }

    /** Apply: the drafts in the engine's terms, and the name typed in the title strip. */
    private void apply() {
        var v = menu.view();
        if (v == null || waiting()) return;
        if (menu.datasheet() && nameDraft != null && !com.wormzjl.icheme.science.equipment.DeviceName.clean(nameDraft).equals(currentName()))
            com.wormzjl.icheme.network.EquipmentNetwork.rename(menu.origin(), nameDraft);
        try {
            double setPoint = v.settings().setPoint(), power = v.settings().powerLimit(), gear = v.settings().ratio();
            if (mode == MachineColumn.Mode.SPEED && kind().driver() && (kind() != MachineColumn.Kind.MOTOR || v.variable()))
                setPoint = MachineColumn.setPoint(kind(), number(speed), v.designSpeed());
            if (kind() == MachineColumn.Kind.MOTOR || kind() == MachineColumn.Kind.CREATIVE_MOTOR || kind() == MachineColumn.Kind.GENERATOR) power = limit.isBlank() ? 0 : 1000 * number(limit);
            if (kind() == MachineColumn.Kind.GEARBOX) gear = number(ratio);
            send(MachineNetwork.Action.APPLY, new ShaftUnit.Settings(running, mode.engine(), setPoint, power, gear));
        } catch (IllegalArgumentException invalid) {
            message = mt("invalid");
        }
    }

    private static double number(String text) {
        try {
            double value = Double.parseDouble(text.trim());
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Not a number");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number");
        }
    }

    private void send(MachineNetwork.Action action, ShaftUnit.Settings settings) {
        MachineNetwork.edit(menu.containerId, ++sequence, action, settings);
        // The drafts follow the answer that comes.
        drafted = false;
        rebuildWidgets();
    }

    /** What the widgets show and where they stand: the screen rebuilds them only when this changes. */
    private String signature(List<Column.Row> rows) {
        var out = new StringBuilder().append(menu.view() != null).append(canEdit()).append(waiting());
        for (var p : Column.place(rows, 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(imageWidth) - 10, GuiCanvas.measure(font), Column.Metrics.COMPACT).rows()) {
            if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) out.append('|').append(spec.key()).append(spec.active()).append(spec.latched()).append(p.slots().get(spec.key()));
            if (p.row() instanceof Column.Field f) out.append('|').append(f.key()).append(p.slots().get(f.key()));
        }
        return out.toString();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        follow();
        if (!signature(rows()).equals(signature)) rebuildWidgets();
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
        var canvas = new GuiCanvas(g, font, leftPos, topPos);
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        skin().page(canvas, new Rect(0, 0, imageWidth, imageHeight));
        skin().well(canvas, glass());
        g.pose().popPose();
    }

    /** The HMI glass right of the door column. */
    private Rect glass() {
        int side = FluidGuiLayout.side(imageWidth);
        return new Rect(side + 15, 72, imageWidth - side - 20, imageHeight - 50 - 72);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var skin = skin();
        var canvas = new GuiCanvas(g, font, leftPos, topPos);
        int hx = mouseX - leftPos, hy = mouseY - topPos;
        var strip = strip();
        skin.title(canvas, TitleStrip.TITLE_X, TitleStrip.TITLE_Y, strip.kind());
        if (strip.name() != null && !(menu.datasheet() && canEdit())) {
            String name = currentName();
            int[] at = TitleStrip.textAt(strip.name());
            skin.text(canvas, TitleStrip.text(name, strip.name(), GuiCanvas.measure(font)), at[0], at[1], Palette.TEXT);
        }
        // The status, once, on the message display: waiting, a refusal, or the part's own word.
        var v = menu.view();
        String status;
        int tone = Palette.MUTED;
        if (v == null) status = mt("waiting_first");
        else if (waiting()) status = mt("waiting");
        else if (!message.isEmpty()) {
            status = message;
            tone = Palette.WARN;
        } else if (!menu.refusal().isEmpty() && menu.answered() == sequence && sequence > 0) {
            status = mt("refused", menu.refusal());
            tone = Palette.WARN;
        } else {
            status = v.status();
            if (MachineColumn.warns(status)) tone = Palette.WARN;
        }
        int width = imageWidth - 20;
        String shown = font.plainSubstrByWidth(status, width);
        skin.status(canvas, new Rect(10, 59, width, 9), shown, tone);
        if (hx >= 10 && hx < 10 + width && hy >= 55 && hy < 69 && shown.length() < status.length()) hovered = status;
        int side = FluidGuiLayout.side(imageWidth);
        skin.vDivider(canvas, side + 13, FluidGuiLayout.DOOR_TOP, imageHeight - FluidGuiLayout.DOOR_FOOT);
        skin.hDivider(canvas, 8, imageWidth - 8, imageHeight - 35);
        // The door column, placed again for this frame's texts; the widgets keep their slots.
        door.place(rows(), GuiCanvas.measure(font));
        String hint = door.draw(canvas, skin, hx, hy);
        if (hint != null) hovered = hint;
        if (v != null) {
            if(kind()==MachineColumn.Kind.MOTOR)com.wormzjl.icheme.client.gui.common.MotorCurve.draw(canvas,glass(),v.ratedPower(),v.designSpeed(),v.speed(),v.shaftPower(),MachineScreen::tr);
            else nameplate(canvas,v);
        }
    }

    /** The nameplate on the glass ({@link MachineColumn#nameplate}: rating, poles, drive, a coupling's stress rate, rated speed). */
    private void nameplate(GuiCanvas c, MachineView v) {
        var box = glass();
        int x = box.x() + 6, y = box.y() + 6, w = Math.min(box.w() - 12, 240);
        var lines = new ArrayList<String[]>();
        lines.add(new String[]{mt("nameplate"), ""});
        lines.addAll(MachineColumn.nameplate(v, MachineScreen::tr));
        for (int i = 0; i < lines.size() && y + 10 <= box.bottom(); i++, y += 12) {
            var line = lines.get(i);
            c.text(font.plainSubstrByWidth(line[0], w - 80), x, y, i == 0 ? Palette.MUTED : Palette.TEXT);
            c.text(line[1], x + w - font.width(line[1]), y, Palette.ACCENT);
            if (i == 0) c.hLine(x - 2, x + w + 2, y + 10, Palette.LINE);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        SkinField.clickOutside(this, x, y);
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
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && canEdit() && kind().settable()) {
            apply();
            return true;
        }
        if (ProcessUi.textKeyPressed(this, key, scan, modifiers)) return true;
        return super.keyPressed(key, scan, modifiers);
    }
}
