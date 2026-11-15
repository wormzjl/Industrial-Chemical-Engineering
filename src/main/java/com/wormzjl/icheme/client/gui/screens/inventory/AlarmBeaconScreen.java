package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.AlarmColumns;
import com.wormzjl.icheme.client.gui.common.ControlColumns;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.ScreenTours;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.DoorInput;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.network.ControlNetwork;
import com.wormzjl.icheme.runtime.control.AlarmSettings;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.world.inventory.AlarmBeaconMenu;

import java.util.*;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * An alarm beacon's settings (batch 2026-11-15-fluid-interfaces, W9, plan section 5.3) on a panel door, as a controller's:
 * Name, Off and On, the Source, the Condition and Threshold in the player's display unit, the Input chosen from the paged
 * inputs on its cable, and the Status with the reading. Opening it replays the last published view (no view yet: it
 * waits for the next publication); Apply queues the settings as an engine event, answered at the next publication.
 */
public final class AlarmBeaconScreen extends AbstractContainerScreen<AlarmBeaconMenu> {
    private static final int WIDTH = 246, HEIGHT = 300;

    /** The settings being edited: the published ones until the player changes one. */
    private static final class Draft {
        String name = "", threshold = "0";
        boolean enabled, anyAlarm, above = true;
        @Nullable UUID sensor;
        Sensor.Unit unit = Sensor.Unit.METRE;
        long revision = -1;
    }

    private final KitDoor door = new KitDoor();
    private final com.wormzjl.icheme.client.gui.tutorial.TourHost tour =
            new com.wormzjl.icheme.client.gui.tutorial.TourHost(() -> ScreenTours.ALARM_BEACON, this::anchor);
    private Draft draft = new Draft();
    private CompoundTag view = new CompoundTag();
    private long sequence = -1, ack;
    private boolean dirty, pending, choosing;
    private String reply = "", signature = "";
    private @Nullable String hovered;

    public AlarmBeaconScreen(AlarmBeaconMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        inventoryLabelY = 1000;
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    // ---- The player's display units: a sensor's SI unit shown as the client's pressure, temperature and flow units ----

    static DisplayUnit display(Sensor.Unit unit) {
        return switch (unit) {
            case ABSOLUTE_PASCAL -> ClientConfig.pressure();
            case KELVIN -> ClientConfig.temperature();
            case KILOGRAM_PER_SECOND, CUBIC_METRE_PER_SECOND -> ClientConfig.flowTime();
            default -> new DisplayUnit() {
                public double display(double si) {
                    return si;
                }

                public double si(double displayed) {
                    return displayed;
                }
            };
        };
    }

    static String symbol(Sensor.Unit unit) {
        return switch (unit) {
            case ABSOLUTE_PASCAL -> ClientConfig.pressure().symbol() + "(a)";
            case KELVIN -> ClientConfig.temperature().symbol();
            case KILOGRAM_PER_SECOND -> ClientConfig.flowTime().unit("kg");
            case CUBIC_METRE_PER_SECOND -> ClientConfig.flowTime().unit("m³");
            case METRE -> "m";
            case HEIGHT_PERCENT -> "%";
            case REVOLUTIONS_PER_MINUTE -> "rpm";
            case STATE -> "";
        };
    }

    // ---- The published view ----

    private List<CompoundTag> inputs() {
        var out = new ArrayList<CompoundTag>();
        for (var t : view.getList("inputs", Tag.TAG_COMPOUND)) out.add((CompoundTag) t);
        return out;
    }

    private @Nullable CompoundTag input() {
        if (draft.sensor == null) return null;
        for (var t : inputs()) if (t.getUUID("id").equals(draft.sensor)) return t;
        return null;
    }

    private boolean alarmChannel() {
        var in = input();
        return in != null && in.getString("kind").equals("ALARM");
    }

    /** Takes the menu's newest view: the drafts follow it until the player edits; an answer ends a pending Apply. */
    private void receive() {
        if (sequence == menu.sequence()) return;
        sequence = menu.sequence();
        view = menu.snapshot();
        if (!view.contains("settings")) return;
        if (view.getLong("ack") != ack) {
            ack = view.getLong("ack");
            pending = false;
            reply = view.getString("reply").equals("APPLIED") ? "" : view.getString("reply");
            if (reply.isEmpty()) dirty = false;
        }
        if (!dirty) draft = draft(view);
    }

    private static Draft draft(CompoundTag view) {
        var d = new Draft();
        try {
            var s = AlarmSettings.load(view.getCompound("settings"));
            d.name = s.name();
            d.enabled = s.enabled();
            d.anyAlarm = s.source() == AlarmSettings.Source.ANY_ALARM;
            d.sensor = s.sensor();
            d.unit = s.unit();
            d.above = s.comparison() == AlarmSettings.Comparison.ABOVE;
            d.threshold = ProcessUi.number(display(s.unit()).display(s.threshold()));
        } catch (IllegalArgumentException damaged) {
            // A view this client cannot read keeps the empty draft; Apply then sends empty settings.
        }
        d.revision = view.getLong("revision");
        return d;
    }

    private AlarmColumns.View doorView() {
        var in = input();
        String status = view.contains("status") ? view.getString("status") : "WAITING";
        String reading = view.contains("value") ? ProcessUi.number(display(draft.unit).display(view.getDouble("value"))) : "";
        boolean channel = alarmChannel() || draft.anyAlarm;
        return new AlarmColumns.View(draft.enabled, draft.anyAlarm, in == null ? "" : in.getString("label"), alarmChannel(), draft.above,
                symbol(draft.unit), status, reading, channel ? "" : symbol(draft.unit), view.getBoolean("onBus"), pending ? "…" : reply);
    }

    private List<Column.Row> rows() {
        if (!choosing) return AlarmColumns.rows(doorView(), (k, a) -> tr(k, a));
        var choices = new ArrayList<ControlColumns.Choice>();
        for (var t : inputs())
            choices.add(new ControlColumns.Choice("sensor:" + t.getUUID("id"), t.getString("label"), t.getUUID("id").equals(draft.sensor)));
        return AlarmColumns.choices(choices, view.getInt("inputPage"), Math.max(1, view.getInt("inputPages")), (k, a) -> tr(k, a));
    }

    /** What the widgets show and where they stand: a change rebuilds them. */
    private String signature() {
        var v = doorView();
        return choosing + "|" + inputs().size() + "|" + view.getInt("inputPage") + "|" + v.enabled() + v.anyAlarm() + v.alarmChannel() + v.above() + "|" + v.unit()
                + "|" + draft.sensor + "|" + pending + "|" + reply + "|" + !v.reading().isEmpty();
    }

    @Override
    protected void init() {
        receive();
        var m = GuiCanvas.measure(font);
        imageHeight = Math.min(HEIGHT, height - 8);
        super.init();
        var rows = rows();
        door.layout(new Rect(10, DialogColumns.TOP, WIDTH - 20, imageHeight - DialogColumns.TOP - DialogColumns.FOOTER), rows, m);
        door.place(rows, m);
        door.keep(this::field, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, this::act, AlarmBeaconScreen::skin)));
        var footer = DialogColumns.footer(WIDTH, imageHeight, 1);
        var apply = addRenderableWidget(new SkinButton(leftPos + footer.get(0).x(), topPos + footer.get(0).y(), footer.get(0).w(), footer.get(0).h(),
                Component.literal(tr("alarm_beacon.apply")), b -> apply(), AlarmBeaconScreen::skin).primary(true));
        apply.active = !pending && view.contains("settings") && menu.player().mayBuild();
        var help = tour.key(ScreenTours.help(imageWidth).moved(leftPos, topPos), AlarmBeaconScreen::skin);
        if (help != null) addRenderableWidget(help);
        door.place(leftPos, topPos);
        signature = signature();
    }

    /** A tour's anchor: the title or Apply, else a door row's widget or group. */
    private @Nullable Rect anchor(String key, boolean reveal) {
        var frame = ScreenTours.dialog(imageWidth, imageHeight, title.getString(), GuiCanvas.measure(font), List.of("apply"));
        return com.wormzjl.icheme.client.gui.tutorial.TourHost.resolve(frame.get(key), door, key, reveal, AlarmBeaconScreen::tr, leftPos, topPos);
    }

    /** The name's and the threshold's entries. */
    private AbstractWidget field(Column.Field f, Rect r) {
        boolean name = f.key().equals("name");
        var box = new SkinField(font, r.x(), r.y(), r.w(), r.h(), Component.literal(name ? tr("alarm_beacon.name") : f.label()), AlarmBeaconScreen::skin);
        box.setMaxLength(name ? 48 : 32);
        box.setValue(name ? draft.name : draft.threshold);
        box.setResponder(v -> {
            if (name) draft.name = v;
            else draft.threshold = v;
            dirty = true;
        });
        if (f.hint() != null) box.setTooltip(Tooltip.create(Component.literal(f.hint())));
        return addRenderableWidget(box);
    }

    private void act(String key) {
        if (key.equals("input")) {
            choosing = true;
            door.reset();
        } else if (key.equals("back")) {
            choosing = false;
            door.reset();
        } else if (key.startsWith("inputs:")) {
            int page = view.getInt("inputPage") + (key.endsWith("next") ? 1 : -1);
            ControlNetwork.browse(menu.containerId, 0, Math.max(0, page));
        } else {
            dirty = true;
            switch (key) {
                case "off", "on" -> draft.enabled = key.equals("on");
                case "source:input" -> draft.anyAlarm = false;
                case "source:any" -> {
                    draft.anyAlarm = true;
                    draft.sensor = null;
                }
                case "above", "below" -> draft.above = key.equals("above");
                default -> {
                    if (key.startsWith("sensor:")) pick(UUID.fromString(key.substring(7)));
                }
            }
        }
        rebuildWidgets();
    }

    /** Chooses an input: a level in % of its span, any other in its primary unit; the threshold starts at its reading. */
    private void pick(UUID id) {
        draft.sensor = id;
        choosing = false;
        door.reset();
        var in = input();
        if (in == null) return;
        boolean level = in.getString("kind").equals("LEVEL") && in.contains("secondUnit");
        try {
            draft.unit = Sensor.Unit.valueOf(in.getString(level ? "secondUnit" : "unit"));
        } catch (IllegalArgumentException unknown) {
            return;
        }
        if (in.contains("value")) draft.threshold = ProcessUi.number(display(draft.unit).display(in.getDouble(level ? "secondary" : "value")));
    }

    private void apply() {
        if (pending || !view.contains("settings")) return;
        if (draft.enabled && !draft.anyAlarm && draft.sensor == null) {
            reply = tr("alarm_beacon.no_inputs");
            rebuildWidgets();
            return;
        }
        try {
            double threshold = draft.anyAlarm ? 0 : display(draft.unit).si(Double.parseDouble(draft.threshold.replace(",", "").strip()));
            var settings = new AlarmSettings(draft.name, draft.enabled, draft.anyAlarm ? AlarmSettings.Source.ANY_ALARM : AlarmSettings.Source.INPUT,
                    draft.anyAlarm ? null : draft.sensor, draft.unit, draft.above ? AlarmSettings.Comparison.ABOVE : AlarmSettings.Comparison.BELOW, threshold);
            ControlNetwork.editAlarm(menu.containerId, menu.alarm(), draft.revision, settings);
            pending = true;
        } catch (IllegalArgumentException invalid) {
            reply = tr("control.invalid");
        }
        rebuildWidgets();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        receive();
        if (!signature.equals(signature())) rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int x, int y, float partial) {
        door.place(leftPos, topPos);
        int px = tour.pointer(x), py = tour.pointer(y);
        GuiCanvas.of(g, font).batch(() -> super.render(g, px, py, partial));
        DoorInput.overlay(this, g, font, tour, hovered, x, y);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        skin().page(new GuiCanvas(g, font, leftPos, topPos), new Rect(0, 0, imageWidth, imageHeight));
        g.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int x, int y) {
        var canvas = new GuiCanvas(g, font, leftPos, topPos);
        String id = view.getString("beaconId");
        skin().title(canvas, 10, 10, font.plainSubstrByWidth(id.isEmpty() ? title.getString() : title.getString() + " " + id, ScreenTours.titleRoom(imageWidth)));
        // Placed again for this frame's texts (the status and the reading follow the plant); the widgets keep their slots.
        door.place(rows(), GuiCanvas.measure(font));
        hovered = door.draw(canvas, skin(), x - leftPos, y - topPos);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (tour.mouseClicked(font, width, height, x, y)) return true;
        SkinField.clickOutside(this, x, y);
        double rx = x - leftPos, ry = y - topPos;
        if (button == 0) {
            var action = door.action(rx, ry);
            if (action != null) {
                act(action);
                return true;
            }
        }
        return door.press(rx, ry, button) || super.mouseClicked(x, y, button);
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
    public boolean mouseScrolled(double x, double y, double h, double v) {
        return DoorInput.wheel(tour, door, x, y, leftPos, topPos, v) || super.mouseScrolled(x, y, h, v);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return tour.isOpen() || super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (tour.keyPressed(key)) return true;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            apply();
            return true;
        }
        return ProcessUi.textKeyPressed(this, key, scan, mods) || super.keyPressed(key, scan, mods);
    }
}
