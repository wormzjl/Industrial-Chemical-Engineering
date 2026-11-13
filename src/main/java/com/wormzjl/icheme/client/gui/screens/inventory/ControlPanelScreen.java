package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.*;
import com.wormzjl.icheme.client.gui.common.kit.*;
import com.wormzjl.icheme.network.ControlNetwork;
import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.*;

/** Client drafts survive scheduled reading updates. Only Apply queues a server input. */
public final class ControlPanelScreen extends AbstractContainerScreen<ControlPanelMenu> {
    private int controllerPage, controllerPages = 1;
    private ControlColumns.Layout layout;
    private final KitDoor door = new KitDoor();
    private UUID selected;
    private String page = "loop", hovered, signature = "";
    private long sequence = -1;
    private final Map<UUID, ControlSettings> submitted = new HashMap<>();
    private List<CompoundTag> controllers = List.of();
    private List<CompoundTag> alarms = List.of();
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private boolean pending;
    private long ack;
    private String reply = "";
    /** The page's help tour (batch 2026-11-12-tutorial-system, P6), opened by the ? key at the title row's right; none before the first view. */
    private final com.wormzjl.icheme.client.gui.tutorial.TourHost tour = new com.wormzjl.icheme.client.gui.tutorial.TourHost(
            () -> tourReady() ? ScreenTours.control(page) : null, this::anchor);

    /** Whether the page shows what its tour points at: a loop's pages a selected controller, a column's its view, a converter's its tag. */
    private boolean tourReady() {
        return switch (page) {
            case "controllers" -> true;
            case "column" -> operation != null;
            case "converter" -> converterTag() != null;
            default -> draft() != null;
        };
    }

    /** A tour's anchor on the screen: the title, the list key, Close or Apply, else a door row's widget or group (ScreenTours.controlFrame). */
    private com.wormzjl.icheme.client.gui.common.kit.Rect anchor(String key, boolean reveal) {
        var frame = ScreenTours.controlFrame(layout, title.getString(), GuiCanvas.measure(font));
        if (key.equals("page") && !door.columns().isEmpty())
            return com.wormzjl.icheme.client.gui.common.TourAnchors.reveal(door.view(), ScreenTours.page(door.columns()), reveal).moved(leftPos, topPos);
        return com.wormzjl.icheme.client.gui.tutorial.TourHost.resolve(frame.get(key), door, key, reveal, ControlPanelScreen::tr, leftPos, topPos);
    }

    private static final class Draft {
        String name, target;
        boolean enabled, dirty;
        UUID sensor;
        long output, revision;
        Sensor.Unit unit;
        ControlSettings.Action action;

        Draft(ControlSettings s, long revision) {
            name = s.name();
            target = Double.toString(s.target());
            enabled = s.enabled();
            sensor = s.sensor();
            output = s.output();
            unit = s.unit();
            action = s.action();
            this.revision = revision;
        }

        ControlSettings settings() {
            return new ControlSettings(
                    name, enabled, sensor, output, unit, Double.parseDouble(target), action);
        }
    }

    public ControlPanelScreen(ControlPanelMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = menu.dcs() ? 464 : 246;
        imageHeight = 390;
        inventoryLabelY = 1000;
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    // A DCS's column page (W6.2): the columns its bus reaches, the one chosen, its Operation view and its entries' drafts.
    private List<CompoundTag> columns = List.of();
    private long column;
    private com.wormzjl.icheme.science.column.operation.OperationView operation;
    private final Map<String, String> columnDrafts = new HashMap<>();

    private static OperationColumn.Units units() {
        return new OperationColumn.Units(ClientConfig.temperature(), ClientConfig.pressure(), ClientConfig.flowTime());
    }

    private String columnLabel() {
        return columns.stream().filter(c -> c.getLong("id") == column).map(c -> c.getString("label")).findFirst().orElse("");
    }

    /** The columns' group in a DCS's list: one choice per column its bus reaches. */
    private List<Column.Row> columnRows() {
        var rows = new ArrayList<Column.Row>();
        for (var c : columns)
            rows.add(new Column.Choice(c.getString("label"), tr("control.column.hint"), page.equals("column") && c.getLong("id") == column, "column:" + c.getLong("id")));
        if (rows.isEmpty()) rows.add(new Column.Paragraph(tr("control.no_columns"), Palette.TEXT));
        return List.of(new Column.Group(tr("control.columns"), tr("control.columns.hint"), rows));
    }

    /** The column page: the column's status, then its Operation door (Control and the set points), as its tab defines them. */
    private List<Column.Row> columnPage() {
        if (operation == null) return List.of(new Column.Group(columnLabel(), null, List.of(new Column.Paragraph(tr("control.waiting"), Palette.TEXT))));
        var status = OperationColumn.status(operation, ControlPanelScreen::tr);
        boolean note = pending || !reply.isBlank();
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(columnLabel(), status.hint(),
                List.of(new Column.Paragraph(pending ? tr("control.pending") : !reply.isBlank() ? reply : status.text(), note ? Palette.TEXT : status.palette(), status.hint()))));
        rows.addAll(OperationColumn.rows(new OperationColumn.Input(operation, pending, units()), ControlPanelScreen::tr));
        return rows;
    }

    // A DCS's converter page (batch 2026-11-12-fluid-conversion, W4/W5): the converters its bus reaches, the one chosen and
    // its entries' drafts. A mode key sends SET_MODE at once; Apply sends the rate, the deadband in Balance and the thresholds.
    private List<CompoundTag> converters = List.of();
    private long converter;
    private final Map<String, String> converterDrafts = new HashMap<>();

    private CompoundTag converterTag() {
        return converters.stream().filter(c -> c.getLong("id") == converter).findFirst().orElse(null);
    }

    /** The converters' group in a DCS's list: one choice per converter its bus reaches. */
    private List<Column.Row> converterRows() {
        var rows = new ArrayList<Column.Row>();
        for (var c : converters)
            rows.add(new Column.Choice(c.getString("label"), tr("control.converter.hint"), page.equals("converter") && c.getLong("id") == converter, "converter:" + c.getLong("id")));
        if (rows.isEmpty()) rows.add(new Column.Paragraph(tr("control.no_converters"), Palette.TEXT));
        return List.of(new Column.Group(tr("control.converters"), tr("control.converters.hint"), rows));
    }

    /** A converter's draft values as shown, from its published settings: the rate in L per the player's time basis, the deadband and thresholds in %. */
    private static Map<String, String> converterValues(CompoundTag c) {
        var out = new HashMap<String, String>();
        out.put("converterRate", ProcessUi.number(ClientConfig.flowTime().display(c.getDouble("rate"))));
        out.put("converterDeadband", ProcessUi.number(100 * c.getDouble("deadband")));
        var t = c.getList("thresholds", 6);
        for (int i = 0; i < ConverterDoor.THRESHOLDS.size(); i++) {
            double v = i < t.size() ? t.getDouble(i) : -1;
            out.put(ConverterDoor.THRESHOLDS.get(i), v < 0 ? "" : ProcessUi.number(100 * v));
        }
        return out;
    }

    /** The converter page: its status (or the console's answer), then the converter's own groups as its page shows them. */
    private List<Column.Row> converterPage() {
        var c = converterTag();
        if (c == null) return List.of(new Column.Paragraph(tr("control.waiting"), Palette.TEXT));
        var words = new ArrayList<String>();
        if (!c.getString("status").isEmpty()) words.add(c.getString("status").replace('_', ' '));
        for (var a : c.getList("alarms", 8)) words.add(tr("control.alarm." + a.getAsString().toLowerCase(Locale.ROOT)));
        boolean alarm = !c.getList("alarms", 8).isEmpty();
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(c.getString("label"), null, List.of(new Column.Paragraph(pending || c.getBoolean("pending") ? tr("control.pending") : !reply.isBlank() ? reply : String.join(" / ", words),
                alarm && reply.isBlank() && !pending ? Palette.RED : Palette.TEXT, null))));
        var flow = ClientConfig.flowTime();
        java.util.function.DoubleFunction<String> shown = v -> ProcessUi.number(flow.display(v));
        double forge = c.getDouble("forge"), fill = c.getDouble("icheme");
        var t = c.getList("thresholds", 6);
        var thresholds = new ArrayList<String>();
        for (int i = 0; i < ConverterDoor.THRESHOLDS.size(); i++) thresholds.add(i < t.size() && t.getDouble(i) >= 0 ? ProcessUi.number(100 * t.getDouble(i)) : "—");
        String rule = c.getString("rule");
        var in = new ConverterDoor.Input(!pending, ConverterDoor.Mode.valueOf(c.getString("mode")), flow.unit("L"), shown.apply(c.getDouble("rated")), shown.apply(c.getDouble("rate")),
                ProcessUi.number(100 * c.getDouble("deadband")), thresholds,
                new ConverterDoor.Tank(forge, ProcessUi.number(100 * forge), tr("fluid.converter_tank.forge_amount", ProcessUi.number(c.getLong("stored")), ProcessUi.number(c.getLong("capacity")))),
                c.getString("fluid").isEmpty() ? null : c.getString("fluid"),
                new ConverterDoor.Tank(fill < 0 ? Double.NaN : fill, fill < 0 ? "—" : ProcessUi.number(100 * fill), c.getBoolean("closed") ? tr("control.converter.closed") : tr("control.converter.open")),
                rule.isEmpty() ? null : rule, false, List.of(), shown.apply(c.getDouble("booked")), c.getString("direction").toLowerCase(Locale.ROOT));
        rows.addAll(ConverterDoor.rows(in, (key, args) -> tr("fluid." + key, args)));
        return rows;
    }

    private CompoundTag selected() {
        return controllers.stream()
                .filter(c -> c.getUUID("id").equals(selected))
                .findFirst()
                .orElse(null);
    }

    private Draft draft() {
        return drafts.get(selected);
    }

    private static List<CompoundTag> tags(CompoundTag t, String key) {
        return t == null
                ? List.of()
                : t.getList(key, 10).stream().map(x -> (CompoundTag) x).toList();
    }

    private CompoundTag input() {
        var d = draft();
        return d == null
                ? null
                : tags(selected(), "inputs").stream()
                        .filter(t -> t.getUUID("id").equals(d.sensor))
                        .findFirst()
                        .orElse(null);
    }

    private static String symbol(String unit) {
        try {
            return switch (Sensor.Unit.valueOf(unit)) {
                case ABSOLUTE_PASCAL -> "Pa(a)";
                case KELVIN -> "K";
                case KILOGRAM_PER_SECOND -> "kg/s";
                case CUBIC_METRE_PER_SECOND -> "m³/s";
                case METRE -> "m";
                case HEIGHT_PERCENT -> "%";
                case REVOLUTIONS_PER_MINUTE -> "rpm";
                case STATE -> "";
            };
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private List<Column.Row> right() {
        if (page.equals("column")) return columnPage();
        if (page.equals("converter")) return converterPage();
        var row = selected();
        var d = draft();
        if (row == null || d == null)
            return List.of(new Column.Paragraph(tr("control.waiting"), Palette.TEXT));
        if (page.equals("input"))
            return withNavigation(
                    ControlColumns.choices(
                            tr("control.input"),
                            tags(row, "inputs").stream()
                                    .map(
                                            t ->
                                                    new ControlColumns.Choice(
                                                            "sensor:" + t.getUUID("id"),
                                                            t.getString("label"),
                                                            Objects.equals(
                                                                    d.sensor, t.getUUID("id")),
                                                            sensorHint(t)))
                                    .toList(),
                            ControlPanelScreen::tr),
                    row.getInt("inputPage"),
                    row.getInt("inputPages"),
                    "devices");
        if (page.equals("output"))
            return withNavigation(
                    ControlColumns.choices(
                            tr("control.output"),
                            tags(row, "outputs").stream()
                                    .map(
                                            t ->
                                                    new ControlColumns.Choice(
                                                            "device:" + t.getLong("id"),
                                                            t.getString("label"),
                                                            d.output == t.getLong("id")))
                                    .toList(),
                            ControlPanelScreen::tr),
                    row.getInt("outputPage"),
                    row.getInt("outputPages"),
                    "devices");
        var input = input();
        String primary = input == null ? symbol(d.unit.name()) : symbol(input.getString("unit")),
                secondary = input == null ? "" : symbol(input.getString("secondUnit"));
        boolean second = input != null && input.getString("secondUnit").equals(d.unit.name());
        String value =
                input == null
                        ? "—"
                        : input.getString("quality").equals("AVAILABLE")
                                ? String.format(
                                        Locale.ROOT,
                                        "%.4g",
                                        input.getDouble(second ? "secondary" : "value"))
                                : "—";
        String output =
                tags(row, "outputs").stream()
                        .filter(t -> t.getLong("id") == d.output)
                        .map(t -> t.getString("label"))
                        .findFirst()
                        .orElse(tr(d.output == 0 ? "control.none" : "control.disconnected"));
        String state =
                pending
                        ? tr("control.pending")
                        : !reply.isBlank() ? reply : row.getString("status");
        if (state.isBlank()) state = tr("control.waiting");
        var variable =
                input == null
                        ? ControlColumns.Variable.UNKNOWN
                        : ControlColumns.Variable.valueOf(input.getString("kind"));
        return ControlColumns.loop(
                new ControlColumns.View(
                        d.enabled,
                        primary,
                        secondary,
                        second,
                        input == null
                                ? d.sensor != null ? tr("control.disconnected") : tr("control.none")
                                : input.getString("label"),
                        value,
                        output,
                        state,
                        d.action == ControlSettings.Action.OUTLET,
                        variable),
                sensorHint(input),
                ControlPanelScreen::tr);
    }

    private static String sensorHint(CompoundTag input) {
        boolean located = input != null && input.contains("position", 4);
        var p =
                located
                        ? net.minecraft.core.BlockPos.of(input.getLong("position"))
                        : net.minecraft.core.BlockPos.ZERO;
        return ControlColumns.sensorHint(
                new ControlColumns.SensorLocation(
                        located,
                        input != null && input.getBoolean("mountLocated"),
                        p.getX(),
                        p.getY(),
                        p.getZ(),
                        input == null ? 0 : input.getLong("equipment")),
                ControlPanelScreen::tr);
    }

    private static List<Column.Row> withNavigation(
            List<Column.Row> rows, int page, int pages, String key) {
        var out = new ArrayList<>(rows);
        out.addAll(ControlColumns.navigation(page, pages, key, ControlPanelScreen::tr));
        return out;
    }

    private List<Column.Row> controllerRows() {
        return withNavigation(
                ControlColumns.controllers(
                        controllers.stream()
                                .map(
                                        t -> {
                                            var id = t.getUUID("id");
                                            String name =
                                                    t.getCompound("settings").getString("name");
                                            return new ControlColumns.Choice(
                                                    "controller:" + id,
                                                    name.isBlank()
                                                            ? t.getString("controllerId")
                                                            : name,
                                                    id.equals(selected));
                                        })
                                .toList(),
                        ControlPanelScreen::tr),
                controllerPage,
                controllerPages,
                "controllers");
    }

    /** The alarms group: last in the controller list on a DCS, last in the loop page on a controller box. */
    private List<Column.Row> alarmRows() {
        return ControlColumns.alarms(
                alarms.stream().map(t -> new ControlColumns.Alarm(t.getString("label"), t.getString("alarm"), sensorHint(t))).toList(),
                ControlPanelScreen::tr);
    }

    private List<List<Column.Row>> rows() {
        var r = right();
        if (!menu.dcs()) {
            if (!page.equals("loop")) return List.of(r);
            var all = new ArrayList<>(r);
            all.addAll(alarmRows());
            return List.of(all);
        }
        var list = new ArrayList<>(controllerRows());
        list.addAll(columnRows());
        list.addAll(converterRows());
        list.addAll(alarmRows());
        return layout.split()
                ? List.of(list, r)
                : List.of(page.equals("controllers") ? list : r);
    }

    private void receive() {
        if (sequence == menu.sequence()) return;
        sequence = menu.sequence();
        var view = menu.snapshot();
        controllers = tags(view, "controllers");
        alarms = tags(view, "alarms");
        columns = tags(view, "columns");
        if (columns.stream().noneMatch(c -> c.getLong("id") == column)) {
            column = 0;
            operation = null;
            if (page.equals("column")) page = "loop";
        } else if (view.contains("operation") && view.getLong("column") == column)
            operation = com.wormzjl.icheme.network.AssemblyNetwork.decodeOperation(view.getByteArray("operation"));
        converters = tags(view, "converters");
        if (converters.stream().noneMatch(c -> c.getLong("id") == converter)) {
            converter = 0;
            if (page.equals("converter")) page = "loop";
        }
        controllerPage = view.getInt("controllerPage");
        controllerPages = Math.max(1, view.getInt("controllerPages"));
        if (view.getLong("ack") != ack) {
            ack = view.getLong("ack");
            pending = false;
            reply = view.getString("reply").equals("APPLIED") ? "" : view.getString("reply");
            if (page.equals("column") && reply.isEmpty()) columnDrafts.clear();
            if (page.equals("converter") && reply.isEmpty()) converterDrafts.clear();
            if (view.hasUUID("ackPanel") && view.getString("reply").equals("APPLIED")) {
                var d = drafts.get(view.getUUID("ackPanel"));
                if (d != null)
                    try {
                        if (d.settings().equals(submitted.remove(view.getUUID("ackPanel"))))
                            d.dirty = false;
                    } catch (IllegalArgumentException incomplete) {
                    }
            }
        }
        for (var t : controllers) {
            var id = t.getUUID("id");
            var d = drafts.get(id);
            if (d == null || !d.dirty)
                drafts.put(
                        id,
                        new Draft(
                                ControlSettings.load(t.getCompound("settings")),
                                t.getLong("revision")));
            else d.revision = t.getLong("revision");
        }
        if (controllers.stream().noneMatch(t -> t.getUUID("id").equals(selected))) {
            selected = controllers.isEmpty() ? null : controllers.getFirst().getUUID("id");
            if (!page.equals("column") && !page.equals("converter")) page = "loop";
        }
    }

    @Override
    protected void init() {
        layout = ControlColumns.layout(width, height, menu.dcs());
        imageWidth = layout.width();
        imageHeight = layout.height();
        if (layout.split() && page.equals("controllers")) page = "loop";
        super.init();
        receive();
        var rows = rows();
        var m = GuiCanvas.measure(font);
        if (layout.split()) door.layout(layout.areas(), layout.bar(), rows, m);
        else door.layout(layout.areas().getFirst(), rows.getFirst(), m);
        door.placeAll(rows, m);
        door.keep(
                this::field,
                (spec, slot) ->
                        addRenderableWidget(
                                SkinButton.of(spec, slot, this::act, ControlPanelScreen::skin)));
        if (layout.chooser() != null) {
            var r = layout.chooser();
            addRenderableWidget(
                    new SkinButton(
                            leftPos + r.x(),
                            topPos + r.y(),
                            r.w(),
                            r.h(),
                            Component.translatable(
                                    "gui.icheme.control."
                                            + (page.equals("controllers")
                                                    ? "back"
                                                    : "controllers")),
                            b -> act("controller_list"),
                            ControlPanelScreen::skin));
        }
        var footer = DialogColumns.footer(imageWidth, imageHeight, 2);
        addRenderableWidget(
                new SkinButton(
                        leftPos + footer.get(0).x(),
                        topPos + footer.get(0).y(),
                        footer.get(0).w(),
                        footer.get(0).h(),
                        Component.translatable("gui.icheme.control.close"),
                        b -> onClose(),
                        ControlPanelScreen::skin));
        var apply =
                new SkinButton(
                                leftPos + footer.get(1).x(),
                                topPos + footer.get(1).y(),
                                footer.get(1).w(),
                                footer.get(1).h(),
                                Component.translatable("gui.icheme.control.apply"),
                                b -> apply(),
                                ControlPanelScreen::skin)
                        .primary(true);
        apply.active = page.equals("converter") ? converterTag() != null && !pending
                : page.equals("column")
                ? operation != null && operation.editable() && !pending
                : draft() != null && !pending && !page.equals("controllers");
        addRenderableWidget(apply);
        var help = tour.key(ScreenTours.help(imageWidth).moved(leftPos, topPos), ControlPanelScreen::skin);
        if (help != null) addRenderableWidget(help);
        door.place(leftPos, topPos);
        signature = signature();
    }

    private String signature() {
        var d = draft();
        return controllerPage
                + "/"
                + controllerPages
                + "/"
                + page
                + "/"
                + selected
                + "/"
                + controllers.stream()
                        .map(
                                t ->
                                        t.getUUID("id")
                                                + ":"
                                                + t.getLong("revision")
                                                + ":"
                                                + t.getInt("inputPages")
                                                + ":"
                                                + t.getInt("outputPages")
                                                + ":"
                                                + tags(t, "inputs").stream()
                                                        .map(x -> x.getUUID("id"))
                                                        .toList()
                                                + ":"
                                                + tags(t, "outputs").stream()
                                                        .map(x -> x.getLong("id"))
                                                        .toList())
                        .toList()
                + "/"
                + (d == null
                        ? ""
                        : d.enabled + ":" + d.sensor + ":" + d.output + ":" + d.unit + ":"
                                + d.action)
                + "/"
                + column
                + ":"
                + columns.stream().map(c -> c.getLong("id")).toList()
                + ":"
                + (operation == null ? "" : operation.running() + ":" + operation.editable() + ":" + OperationColumn.fields(operation.setpoints()))
                + "/"
                + converter
                + ":"
                + converters.stream().map(c -> c.getLong("id") + c.getString("mode") + c.getBoolean("pending") + c.getString("rule") + c.getString("fluid").isEmpty()).toList()
                + "/"
                + pending;
    }

    private AbstractWidget field(Column.Field f, Rect r) {
        if (page.equals("converter") && converterTag() != null) {
            var box = new SkinField(font, r.x(), r.y(), r.w(), r.h(), Component.literal(f.label()), ControlPanelScreen::skin);
            box.setMaxLength(32);
            box.setValue(converterDrafts.getOrDefault(f.key(), converterValues(converterTag()).getOrDefault(f.key(), "")));
            box.setResponder(v -> converterDrafts.put(f.key(), v));
            return addRenderableWidget(box);
        }
        if (page.equals("column") && operation != null) {
            var box = new SkinField(font, r.x(), r.y(), r.w(), r.h(), Component.literal(f.label()), ControlPanelScreen::skin);
            box.setMaxLength(32);
            box.setValue(columnDrafts.getOrDefault(f.key(), OperationColumn.values(operation.setpoints(), units()).getOrDefault(f.key(), "")));
            box.setResponder(v -> columnDrafts.put(f.key(), v));
            return addRenderableWidget(box);
        }
        var d = draft();
        var box =
                new SkinField(
                        font,
                        r.x(),
                        r.y(),
                        r.w(),
                        r.h(),
                        Component.literal(f.key().equals("name") ? tr("control.name") : f.label()),
                        ControlPanelScreen::skin);
        box.setMaxLength(f.key().equals("name") ? 48 : 32);
        box.setValue(f.key().equals("name") ? d.name : d.target);
        box.setResponder(
                v -> {
                    if (f.key().equals("name")) d.name = v;
                    else d.target = v;
                    d.dirty = true;
                });
        return addRenderableWidget(box);
    }

    private void act(String key) {
        var d = draft();
        if (key.startsWith("controllers:") || key.startsWith("devices:")) {
            int direction = key.endsWith("next") ? 1 : -1;
            if (key.startsWith("controllers:"))
                ControlNetwork.browse(menu.containerId, controllerPage + direction, 0);
            else {
                var row = selected();
                if (row != null)
                    ControlNetwork.browse(
                            menu.containerId,
                            controllerPage,
                            row.getInt(page.equals("input") ? "inputPage" : "outputPage")
                                    + direction);
            }
            return;
        }
        if (key.startsWith("converter:")) {
            converter = Long.parseLong(key.substring(10));
            converterDrafts.clear();
            reply = "";
            page = "converter";
        } else if (page.equals("converter") && key.startsWith("converter_mode:")) {
            if (converterTag() != null && !pending) {
                var mode = com.wormzjl.icheme.runtime.fluid.ConverterUnit.Mode.valueOf(key.substring(15).toUpperCase(Locale.ROOT));
                ControlNetwork.converter(menu.containerId, new ConverterCommands.Command(converter, ConverterCommands.Action.SET_MODE, mode, List.of()));
                pending = true;
            }
        } else if (key.startsWith("column:")) {
            column = Long.parseLong(key.substring(7));
            operation = null;
            columnDrafts.clear();
            reply = "";
            page = "column";
            ControlNetwork.selectColumn(menu.containerId, column);
        } else if (page.equals("column") && (key.equals("start") || key.equals("stop"))) {
            if (operation != null && !pending) {
                ControlNetwork.column(menu.containerId, new ColumnCommands.Command(column, key.equals("start") ? ColumnCommands.Action.START : ColumnCommands.Action.STOP, List.of()));
                pending = true;
            }
        } else if (key.startsWith("controller:")) {
            selected = UUID.fromString(key.substring(11));
            page = "loop";
        } else if (key.equals("controller_list")) {
            page = page.equals("controllers") ? "loop" : "controllers";
            door.reset();
        } else if (key.equals("input") || key.equals("output")) page = key;
        else if (key.equals("back")) page = "loop";
        else if (d != null) {
            d.dirty = true;
            if (key.equals("on") || key.equals("off")) d.enabled = key.equals("on");
            else if (key.startsWith("sensor:")) {
                var prior = input();
                d.sensor = UUID.fromString(key.substring(7));
                var input = input();
                if (input != null
                        && (prior == null
                                || !prior.getString("kind").equals(input.getString("kind")))) {
                    boolean level = input.getString("kind").equals("LEVEL");
                    d.unit = Sensor.Unit.valueOf(input.getString(level ? "secondUnit" : "unit"));
                    d.target =
                            Double.toString(
                                    Math.max(0, input.getDouble(level ? "secondary" : "value")));
                }
                page = "loop";
            } else if (key.startsWith("device:")) {
                d.output = Long.parseLong(key.substring(7));
                page = "loop";
            } else if (key.equals("inlet") || key.equals("outlet"))
                d.action =
                        key.equals("inlet")
                                ? ControlSettings.Action.INLET
                                : ControlSettings.Action.OUTLET;
            else if (key.equals("primary") || key.equals("secondary")) {
                var in = input();
                if (in != null)
                    convert(
                            d,
                            in,
                            Sensor.Unit.valueOf(
                                    in.getString(key.equals("primary") ? "unit" : "secondUnit")));
            }
        }
        rebuildWidgets();
    }

    private void convert(Draft d, CompoundTag input, Sensor.Unit unit) {
        if (d.unit == unit) return;
        try {
            double value = Double.parseDouble(d.target);
            boolean toSecondary = unit.name().equals(input.getString("secondUnit"));
            double factor =
                    input.getString("kind").equals("LEVEL")
                            ? (input.getDouble("span") > 0
                                    ? 100 / input.getDouble("span")
                                    : Double.NaN)
                            : input.getDouble("value") == 0
                                    ? Double.NaN
                                    : input.getDouble("secondary") / input.getDouble("value");
            if (value != 0 && (!(factor > 0) || !Double.isFinite(factor))) {
                reply = tr("control.no_conversion");
                return;
            }
            d.target =
                    Double.toString(value == 0 ? 0 : toSecondary ? value * factor : value / factor);
            d.unit = unit;
        } catch (NumberFormatException incomplete) {
            reply = tr("control.invalid");
        }
    }

    private void apply() {
        if (page.equals("converter")) {
            var c = converterTag();
            if (c == null || pending) return;
            var values = converterValues(c);
            values.putAll(converterDrafts);
            try {
                double rate = ClientConfig.flowTime().perSecond(Double.parseDouble(values.get("converterRate").replace(",", "")));
                ControlNetwork.converter(menu.containerId, new ConverterCommands.Command(converter, ConverterCommands.Action.SET_RATE, null, List.of(rate)));
                if (c.getString("mode").equals("BALANCE"))
                    ControlNetwork.converter(menu.containerId, new ConverterCommands.Command(converter, ConverterCommands.Action.SET_DEADBAND, null, List.of(Double.parseDouble(values.get("converterDeadband").replace(",", "")) / 100)));
                var t = new ArrayList<Double>();
                for (var k : ConverterDoor.THRESHOLDS) {
                    var v = values.get(k).replace(",", "").strip();
                    t.add(v.isEmpty() ? -1 : Double.parseDouble(v) / 100);
                }
                ControlNetwork.converter(menu.containerId, new ConverterCommands.Command(converter, ConverterCommands.Action.SET_THRESHOLDS, null, t));
            } catch (NumberFormatException e) {
                reply = tr("control.invalid");
                rebuildWidgets();
                return;
            }
            pending = true;
            rebuildWidgets();
            return;
        }
        if (page.equals("column")) {
            if (operation == null || pending) return;
            var values = OperationColumn.parse(operation.setpoints(), columnDrafts, units());
            if (values == null) {
                reply = tr("control.invalid");
                return;
            }
            ControlNetwork.column(menu.containerId, new ColumnCommands.Command(column, ColumnCommands.Action.APPLY, values));
            pending = true;
            rebuildWidgets();
            return;
        }
        var d = draft();
        if (d == null || pending) return;
        if (d.enabled
                && (input() == null
                        || tags(selected(), "outputs").stream()
                                .noneMatch(t -> t.getLong("id") == d.output))) {
            reply = tr("control.no_devices");
            return;
        }
        try {
            var settings = d.settings();
            submitted.put(selected, settings);
            ControlNetwork.edit(menu.containerId, selected, d.revision, settings);
            pending = true;
            rebuildWidgets();
        } catch (IllegalArgumentException invalid) {
            if (minecraft.player != null)
                minecraft.player.displayClientMessage(
                        Component.translatable("gui.icheme.control.invalid"), true);
        }
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
        // An open tour takes the pointer: the page under it shows no hover.
        super.render(g, tour.pointer(x), tour.pointer(y), partial);
        if (tour.isOpen()) tour.render(g, font, width, height, x, y);
        else KitDoor.hint(g, font, hovered, x, y);
    }

    @Override
    protected void renderBg(GuiGraphics g, float p, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(leftPos, topPos, 0);
        skin().page(
                        new GuiCanvas(g, font, leftPos, topPos),
                        new Rect(0, 0, imageWidth, imageHeight));
        g.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics g, int x, int y) {
        var canvas = new GuiCanvas(g, font, leftPos, topPos);
        skin().title(canvas, 10, 10, font.plainSubstrByWidth(title.getString(), ScreenTours.titleRoom(imageWidth)));
        door.placeAll(rows(), GuiCanvas.measure(font));
        hovered = door.draw(canvas, skin(), x - leftPos, y - topPos);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (tour.mouseClicked(font, width, height, x, y)) return true;
        SkinField.clickOutside(this, x, y);
        double rx = x - leftPos, ry = y - topPos;
        if (button == 0 && door.view().inside(rx, ry))
            for (var column : door.columns()) {
                var action = column.action(rx, door.view().contentY(ry));
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
        if (tour.isOpen()) return true;
        return door.wheel(x - leftPos, y - topPos, v) || super.mouseScrolled(x, y, h, v);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return tour.isOpen() || super.charTyped(c, modifiers);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        return tour.keyPressed(key) || ProcessUi.textKeyPressed(this, key, scan, mods) || super.keyPressed(key, scan, mods);
    }
}
