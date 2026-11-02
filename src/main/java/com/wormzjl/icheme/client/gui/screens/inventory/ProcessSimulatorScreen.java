package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.ClientConfig;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.GuiCanvas;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.SkinButton;
import com.wormzjl.icheme.client.gui.common.kit.SkinField;
import com.wormzjl.icheme.client.gui.common.kit.WindowScale;
import com.wormzjl.icheme.client.gui.flowsheet.BasisPage;
import com.wormzjl.icheme.client.gui.flowsheet.ColumnDoors;
import com.wormzjl.icheme.client.gui.flowsheet.Door;
import com.wormzjl.icheme.client.gui.flowsheet.DoorFrame;
import com.wormzjl.icheme.client.gui.flowsheet.FileColumns;
import com.wormzjl.icheme.client.gui.flowsheet.ResultsPage;
import com.wormzjl.icheme.client.gui.flowsheet.SelectionColumn;
import com.wormzjl.icheme.client.gui.flowsheet.SheetContext;
import com.wormzjl.icheme.client.gui.flowsheet.SheetEditor;
import com.wormzjl.icheme.client.gui.flowsheet.SheetGestures;
import com.wormzjl.icheme.client.gui.flowsheet.SheetPainter;
import com.wormzjl.icheme.client.gui.flowsheet.SheetState;
import com.wormzjl.icheme.client.gui.flowsheet.SheetUnits;
import com.wormzjl.icheme.client.gui.flowsheet.SheetView;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorFrame;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorHost;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorPage;
import com.wormzjl.icheme.client.gui.flowsheet.StreamDoors;
import com.wormzjl.icheme.client.gui.flowsheet.StreamProps;
import com.wormzjl.icheme.client.gui.flowsheet.UnitDoors;
import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The process simulator (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2): the whole window at {@link WindowScale}'s design height 480, with tabs, the canvas, the palette,
 * the doors. It only forwards events and draws: the sheet, its gestures and every page are Minecraft-free in {@code client.gui.flowsheet}, and {@link SimulatorHost} holds what
 * W7 and W8 fill in. A change of what stands on screen rebuilds the widgets; the door's rows are placed again each frame for their texts.
 */
public final class ProcessSimulatorScreen extends Screen {
    private static final int DESIGN_HEIGHT = 480;
    private static final String FOLDER = "IChemE/flowsheets";

    private enum Dialog {
        NONE, OPEN, SAVE_AS, TEMPLATES, REPLACE, CHANGES
    }

    private final SimulatorHost host;
    private final SheetEditor editor;
    private final SheetView view = new SheetView();
    private final SheetGestures gestures;
    private final StreamProps.Fluids fluids;
    private final KitDoor kit = new KitDoor();
    private final ResultsPage.State resultsState = new ResultsPage.State();
    private final Map<String, SkinField> entries = new HashMap<>();
    private SimulatorFrame.Tab tab = SimulatorFrame.Tab.FLOWSHEET;
    private int category;
    private SimulatorFrame frame = new SimulatorFrame(SimulatorFrame.DOOR_W, SimulatorFrame.DOOR_H);
    private SheetGestures.@Nullable Selection door;
    private Door.View doorView = new Door.View();
    private Dialog dialog = Dialog.NONE;
    private int chosen = -1;
    private List<FileColumns.Entry> files = List.of();
    private String signature = "";
    private @Nullable String hovered;
    private Rect canvasBox = new Rect(0, 0, 0, 0);
    private boolean placed;
    private @Nullable Flowsheet opening;
    private String openingName = "";

    public ProcessSimulatorScreen(Component title, Flowsheet sheet, SimulatorHost host) {
        super(title);
        this.host = host;
        var catalog = MaterialRuntime.active();
        String network = sheet.basis().packageId();
        var pack = catalog.requirePackage(network);
        String packName = Component.translatableWithFallback("gui.icheme.flowsheet.basis.pack." + MaterialCatalog.Package.label(pack.id()).replace(':', '.'), MaterialCatalog.Package.label(pack.id())).getString();
        fluids = new StreamProps.Fluids(FluidThermodynamics.forNetwork(catalog, network), pack, id -> ProcessUi.materialName(catalog.name(id)), packName);
        editor = new SheetEditor(sheet, measure(), ProcessSimulatorScreen::columnDefaults);
        editor.lock(host.reader());
        gestures = new SheetGestures(editor, view, this::open);
    }

    private static Measure measure() {
        return GuiCanvas.measure(Minecraft.getInstance().font);
    }

    private static Params columnDefaults() {
        var catalog = MaterialRuntime.active();
        var network = catalog.presets().network();
        V3ColumnInput input = catalog.presets().column(network.defaultColumnPreset()).input(catalog);
        return ColumnDoor.from(input, ColumnComponents.Choices.DEFAULT.condenser());
    }

    private static Skin skin() {
        return ClientConfig.skin();
    }

    private static String lang(String key, Object... args) {
        return Component.translatable("gui.icheme." + key, args).getString();
    }

    private static SheetUnits units() {
        return new SheetUnits(ClientConfig.temperature(), ClientConfig.pressure(), ClientConfig.flowTime(), ClientConfig.basis() == ClientConfig.Basis.MASS);
    }

    private SheetContext context() {
        var last = host.last();
        var state = new SheetState(editor.sheet(), last == null ? null : last.sheet(), last == null ? null : last.run(), last == null ? 0 : last.seconds(), host.running());
        return new SheetContext(editor, state, units(), ProcessSimulatorScreen::lang, GuiCanvas.measure(font), fluids);
    }

    private void open(SheetGestures.Selection target) {
        door = target;
        doorView = new Door.View();
        rebuildWidgets();
    }

    private @Nullable Door shownDoor(SheetContext x) {
        if (door == null) return null;
        if (door.unit()) {
            if (editor.unit(door.id()) == null) return null;
            return editor.unit(door.id()).type().equals("column") ? ColumnDoors.door(x, door.id(), doorView) : UnitDoors.door(x, door.id(), doorView);
        }
        return editor.stream(door.id()) == null ? null : StreamDoors.door(x, door.id(), doorView);
    }

    // ---- Widgets ----

    @Override public void removed() {
        super.removed();
        WindowScale.restore(minecraft);
    }

    @Override protected void init() {
        WindowScale.apply(minecraft, this, DESIGN_HEIGHT, (w, h) -> h >= SimulatorFrame.LEAST_H && w >= SimulatorFrame.LEAST_W);
        frame = new SimulatorFrame(width, height);
        entries.clear();
        var x = context();
        signature = signature(x);
        var shown = shownDoor(x);
        if (dialog != Dialog.NONE) dialogWidgets();
        else if (shown != null) doorWidgets(shown);
        else simulatorWidgets(x);
    }

    private SkinButton add(Column.Button spec, Rect at) {
        var button = SkinButton.of(spec, at, door == null || dialog != Dialog.NONE ? this::act : this::actDoor, ProcessSimulatorScreen::skin);
        if (spec.key().equals("run") && spec.active()) button.primary(true);
        addRenderableWidget(button);
        return button;
    }

    private void simulatorWidgets(SheetContext x) {
        var slots = SimulatorPage.frameKeys(x, tab, frame, host, editor);
        for (var slot : slots) add(slot.button(), slot.rect());
        themeKey(frame.theme());
        canvasBox = SimulatorFrame.inner(frame.well());
        if (tab == SimulatorFrame.Tab.FLOWSHEET) {
            view.layout(canvasBox);
            if (!placed) {
                // A small window opens on the whole sheet; a wide one at its own size.
                placed = true;
                if (frame.size() != SimulatorFrame.Size.WIDE) view.fit(editor.layout().bounds());
            }
            var selected = gestures.selected();
            if (selected != null && !(selected.unit() ? editor.unit(selected.id()) == null : editor.stream(selected.id()) == null)) panelWidgets(x, selected);
            for (var slot : SimulatorPage.canvasKeys(x, frame, canvasBox, category)) add(slot.button(), slot.rect());
            nameWidget();
        } else if (tab == SimulatorFrame.Tab.RESULTS) pageWidgets(ResultsPage.rows(x, resultsState), Map.of(), SimulatorFrame.RESULT_DOOR);
        else if (tab == SimulatorFrame.Tab.BASIS) pageWidgets(BasisPage.rows(x), BasisPage.values(x), SimulatorFrame.BASIS_DOOR);
    }

    /** The contrast sign; its hover names the panel shown. */
    private void themeKey(Rect at) {
        String theme = lang(ClientConfig.theme() == ClientConfig.Theme.DARK ? "fluid.theme.dark" : "fluid.theme.light");
        var key = new SkinButton(at.x(), at.y(), at.w(), at.h(), Component.literal(theme), b -> {
            ClientConfig.toggleTheme();
            rebuildWidgets();
        }, ProcessSimulatorScreen::skin).icon(Skin.Icon.THEME);
        key.setTooltip(Tooltip.create(Component.literal(theme + "\n").append(Component.translatable("gui.icheme.fluid.theme.hint"))));
        addRenderableWidget(key);
    }

    private void nameWidget() {
        var naming = gestures.naming();
        if (naming == null) return;
        var box = new SkinField(font, (int) view.canvasX(naming.x()), (int) view.canvasY(naming.y()) - 2, 66, 15, Component.literal(naming.text()), ProcessSimulatorScreen::skin);
        box.setValue(naming.text());
        box.setResponder(gestures::typed);
        addRenderableWidget(box);
        setFocused(box);
    }

    private void panelWidgets(SheetContext x, SheetGestures.Selection selected) {
        var panel = SelectionColumn.of(x, selected);
        var r = SimulatorFrame.panel(canvasBox);
        kit.layout(new Rect(r.x() + 7, r.y() + 5, r.w() - 18, r.h() - 10), panel.rows(), GuiCanvas.measure(font));
        keep(panel.values(), (key, text) -> panelEntry(x, selected, key, text), this::actPanel);
    }

    private void pageWidgets(List<Column.Row> rows, Map<String, String> values, int width) {
        kit.layout(new Rect(10, SimulatorFrame.DOOR_TOP, width - 10, height - SimulatorFrame.BOTTOM - SimulatorFrame.DOOR_TOP), rows, GuiCanvas.measure(font));
        keep(values, (key, text) -> BasisPage.entry(context(), key, text), this::act);
    }

    private interface Entry {
        void write(String key, String text);
    }

    private void keep(Map<String, String> values, Entry write, java.util.function.Consumer<String> press) {
        kit.keep((field, slot) -> {
            var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(field.label()), ProcessSimulatorScreen::skin);
            box.setValue(values.getOrDefault(field.key(), ""));
            box.setResponder(text -> write.write(field.key(), text));
            entries.put(field.key(), box);
            return addRenderableWidget(box);
        }, (spec, slot) -> addRenderableWidget(SkinButton.of(spec, slot, press, ProcessSimulatorScreen::skin)));
        kit.place(0, 0);
    }

    private int doorLeft() {
        return (width - doorWidth()) / 2;
    }

    private int doorTop() {
        return (height - doorHeight()) / 2;
    }

    private int doorWidth() {
        return FluidDeviceScreen.FluidGuiLayout.imageWidth(width);
    }

    private int doorHeight() {
        return FluidDeviceScreen.FluidGuiLayout.imageHeight(height);
    }

    private void doorWidgets(Door shown) {
        int w = doorWidth(), h = doorHeight(), left = doorLeft(), top = doorTop();
        themeKey(CalculatorColumn.theme(w).moved(left, top));
        var tabs = DoorFrame.tabs(shown, w);
        for (int i = 0; i < shown.tabs().size(); i++) add(shown.tabs().get(i), tabs.get(i).moved(left, top));
        var m = GuiCanvas.measure(font);
        kit.layout(DoorFrame.areas(shown, w, h), DoorFrame.bar(shown, w), shown.columns(), m);
        keep(shown.values(), (key, text) -> shown.actions().entry(key, text), this::actDoor);
        kit.place(left, top);
        var footer = DoorFrame.footer(shown, w, h, m);
        add(new Column.Button("close_door", lang("fluid.close"), true, null), footer.getFirst().moved(left, top));
        for (int i = 0; i < shown.footer().size(); i++) add(shown.footer().get(i), footer.get(i + 1).moved(left, top));
        if (!shown.calculator()) {
            var soft = SimulatorPage.softKeys(units(), ProcessSimulatorScreen::lang);
            for (int i = 0; i < 4; i++) add(soft.get(i), footer.get(footer.size() - 4 + i).moved(left, top));
        }
        for (var slot : shown.glass().slots(SimulatorFrame.inner(DoorFrame.glass(shown, w, h)))) {
            var at = slot.rect().moved(left, top);
            if (slot.button()) {
                add(new Column.Button(slot.key(), slot.text(), !slot.latched(), null, slot.latched()), at);
                continue;
            }
            var box = new SkinField(font, at.x(), at.y(), at.w(), at.h(), Component.literal(slot.key()), ProcessSimulatorScreen::skin);
            box.setValue(slot.text());
            if (slot.hint() != null) box.setHint(Component.literal(slot.hint()));
            box.setResponder(text -> shown.actions().entry(slot.key(), text));
            addRenderableWidget(box);
        }
    }

    private void dialogWidgets() {
        int w = FileColumns.W, h = FileColumns.H, left = (width - w) / 2, top = (height - h) / 2;
        var x = context();
        if (dialog == Dialog.SAVE_AS) {
            var name = new SkinField(font, left + 12, top + 40, w - 24, 18, Component.literal("name"), ProcessSimulatorScreen::skin);
            name.setHint(Component.literal(lang("flowsheet.file.name")));
            entries.put("file", name);
            addRenderableWidget(name);
            setFocused(name);
            var slots = DialogColumns.footer(w, h, 2);
            add(new Column.Button("dialog/cancel", lang("flowsheet.file.cancel"), true, null), slots.get(0).moved(left, top));
            add(new Column.Button("dialog/save", lang("flowsheet.file.save"), true, null), slots.get(1).moved(left, top));
            return;
        }
        if (dialog == Dialog.CHANGES) {
            var slots = DialogColumns.footer(w, h, 2);
            add(new Column.Button("dialog/cancel", lang("flowsheet.file.cancel"), true, null), slots.get(0).moved(left, top));
            add(new Column.Button("dialog/load", lang("flowsheet.file.open"), true, null), slots.get(1).moved(left, top));
            return;
        }
        var list = SimulatorPage.listing(dialog.name(), x, files, chosen);
        var slots = DialogColumns.footer(w, h, list.keys().length);
        for (int i = 0; i < slots.size(); i++) add(new Column.Button("dialog/" + i, list.keys()[i], true, null), slots.get(i).moved(left, top));
    }

    private String signature(SheetContext x) {
        var shown = shownDoor(x);
        var text = new StringBuilder().append(tab).append(dialog).append(door).append(doorView.tab).append(doorView.connection).append(doorView.index).append(doorView.phase).append(doorView.basis).append(doorView.results).append(doorView.metric);
        text.append(gestures.selected()).append(gestures.naming() == null ? "" : gestures.naming().target()).append(category).append(resultsState.view).append(host.running() == null).append(editor.canUndo()).append(editor.canRedo());
        text.append(x.units());
        if (shown != null) for (var rows : shown.columns()) text.append(rows.size()).append(SimulatorPage.keys(rows));
        if (shown != null) text.append(shown.glass().slots(new Rect(0, 0, 400, 400)).stream().map(Door.Slot::key).toList());
        return text.toString();
    }

    // ---- Keys ----

    private void act(String key) {
        var x = context();
        switch (key) {
            case "close" -> onClose();
            case "run" -> host.run(editor.sheet());
            case "stop" -> host.stop();
            case "undo" -> editor.undo();
            case "redo" -> editor.redo();
            case "zoom/out" -> view.zoom(1 / SheetView.STEP, canvasBox.x() + canvasBox.w() / 2.0, canvasBox.y() + canvasBox.h() / 2.0);
            case "zoom/in" -> view.zoom(SheetView.STEP, canvasBox.x() + canvasBox.w() / 2.0, canvasBox.y() + canvasBox.h() / 2.0);
            case "zoom/fit" -> view.fit(editor.layout().bounds());
            case "file/new" -> newSheet();
            case "file/open" -> openDialog();
            case "file/save" -> host.store(editor.sheet());
            case "file/save_as" -> dialog = Dialog.SAVE_AS;
            case "export" -> export(x);
            default -> {
                if (key.startsWith("tab/")) {
                    tab = SimulatorFrame.Tab.values()[Integer.parseInt(key.substring(4))];
                    if (tab == SimulatorFrame.Tab.RESULTS) ResultsPage.first(x, resultsState);
                }
                else if (key.startsWith("category/")) category = Integer.parseInt(key.substring(9));
                else if (key.equals("category-") || key.equals("category+")) category = Math.floorMod(category + (key.endsWith("+") ? 1 : -1), com.wormzjl.icheme.client.gui.flowsheet.Tiles.CATEGORIES.size());
                else if (key.startsWith("unit/")) unitKey(key.substring(5));
                else if (key.startsWith("dialog/")) dialogKey(key.substring(7));
                else ResultsPage.press(resultsState, key);
            }
        }
        rebuild();
    }

    private void rebuild() {
        rebuildWidgets();
    }

    /** A display key: one client-wide switch, as on every screen. */
    private static void unitKey(String key) {
        switch (key) {
            case "temperature" -> ClientConfig.temperature(ClientConfig.temperature().next());
            case "pressure" -> ClientConfig.pressure(ClientConfig.pressure().next());
            case "time" -> ClientConfig.flowTime(ClientConfig.flowTime().next());
            default -> ClientConfig.basis(ClientConfig.basis() == ClientConfig.Basis.MASS ? ClientConfig.Basis.MOLE : ClientConfig.Basis.MASS);
        }
    }

    private void actDoor(String key) {
        var x = context();
        var shown = shownDoor(x);
        if (shown == null) return;
        if (key.equals("close_door")) door = null;
        else if (key.equals("templates") && !door.unit()) dialog = Dialog.TEMPLATES;
        else if (key.startsWith("unit/")) unitKey(key.substring(5));
        else if (key.equals("temperature") || key.equals("basis")) unitKey(key);
        else shown.actions().button(key);
        rebuild();
    }

    private void actPanel(String key) {
        var selected = gestures.selected();
        if (selected == null) return;
        switch (key) {
            case "open" -> open(selected);
            case "delete" -> gestures.delete();
            case "replace" -> {
                dialog = Dialog.REPLACE;
                chosen = -1;
            }
            default -> {
            }
        }
        rebuild();
    }

    private void panelEntry(SheetContext x, SheetGestures.Selection selected, String key, String text) {
        if (key.equals("name")) {
            editor.rename(selected.id(), text);
            return;
        }
        var feed = StreamDoors.door(x, selected.id(), new Door.View());
        feed.actions().entry(key, text);
    }

    private void newSheet() {
        var sheet = editor.sheet();
        editor.load(new Flowsheet(sheet.basis(), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT));
        gestures.select(null);
        door = null;
    }

    private Path folder() {
        return minecraft.gameDirectory.toPath().resolve(FOLDER);
    }

    private void openDialog() {
        try {
            files = FileColumns.entries(folder(), ProcessSimulatorScreen::lang);
            chosen = -1;
            dialog = Dialog.OPEN;
        } catch (IOException unreadable) {
            files = List.of();
            dialog = Dialog.OPEN;
        }
    }

    private void dialogKey(String key) {
        var x = context();
        if (key.equals("cancel")) {
            dialog = Dialog.NONE;
            return;
        }
        if (key.equals("save")) {
            var name = entries.get("file").getValue().strip();
            if (!FileColumns.nameAllowed(name)) return;
            try {
                Files.createDirectories(folder());
                FlowsheetFile.save(FileColumns.file(folder(), name), editor.sheet());
                dialog = Dialog.NONE;
            } catch (IOException unwritable) {
                dialog = Dialog.NONE;
            }
            return;
        }
        if (key.equals("load")) {
            if (opening != null) load(opening);
            dialog = Dialog.NONE;
            return;
        }
        int index = Integer.parseInt(key);
        var list = SimulatorPage.listing(dialog.name(), x, files, chosen);
        if (index == 0) {
            dialog = Dialog.NONE;
            return;
        }
        if (dialog == Dialog.OPEN && index == 1) {
            Util.getPlatform().openPath(folder());
            return;
        }
        if (index != list.primary() || chosen < 0) return;
        switch (dialog) {
            case OPEN -> {
                var entry = files.get(chosen);
                if (!entry.opens()) return;
                try {
                    var sheet = FlowsheetFile.open(entry.file());
                    if (!FileColumns.changes(sheet, ProcessSimulatorScreen::lang).isEmpty()) {
                        opening = sheet;
                        openingName = entry.name();
                        dialog = Dialog.CHANGES;
                        return;
                    }
                    load(sheet);
                } catch (IOException gone) {
                    return;
                }
            }
            case TEMPLATES -> StreamDoors.template(x, door.id(), FluidPresetCatalog.resolve(MaterialRuntime.active()).get(chosen).moleFractions());
            case REPLACE -> editor.replaceUnit(gestures.selected().id(), SimulatorPage.litTypes().get(chosen));
            default -> {
            }
        }
        dialog = Dialog.NONE;
    }

    private void load(Flowsheet sheet) {
        editor.load(sheet);
        gestures.select(null);
        door = null;
    }

    private void export(SheetContext x) {
        try {
            Files.createDirectories(folder());
            Files.writeString(folder().resolve("results.csv"), ResultsPage.csv(x, resultsState));
        } catch (IOException unwritable) {
            // The status stays as it was: the player's folder refused the file.
        }
    }

    // ---- Events ----

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (dialog != Dialog.NONE) {
            var list = dialog == Dialog.SAVE_AS || dialog == Dialog.CHANGES ? null : SimulatorPage.listing(dialog.name(), context(), files, chosen);
            if (list != null) {
                int row = FileColumns.rowAt(list, FileColumns.W, FileColumns.H, x - (width - FileColumns.W) / 2.0, y - (height - FileColumns.H) / 2.0);
                if (row >= 0) chosen = row;
            }
            return super.mouseClicked(x, y, button);
        }
        if (door != null) {
            var shown = shownDoor(context());
            if (shown != null && !super.mouseClicked(x, y, button)) {
                String key = shown.glass().click(SimulatorFrame.inner(DoorFrame.glass(shown, doorWidth(), doorHeight())), x - doorLeft(), y - doorTop());
                if (key != null) actDoor(key);
                return kit.press(x - doorLeft(), y - doorTop(), button);
            }
            return true;
        }
        if (super.mouseClicked(x, y, button)) return true;
        if (tab == SimulatorFrame.Tab.RESULTS) {
            if (resultsState.view != ResultsPage.View.STREAMS) return false;
            var c = context();
            var column = SimulatorFrame.doorColumn(ResultsPage.rows(c, resultsState), SimulatorFrame.RESULT_DOOR, c.m());
            String id = ResultsPage.tick(c, ResultsPage.list(column, frame.bottom()), x, y);
            if (id != null) ResultsPage.toggle(resultsState, id);
            return id != null;
        }
        if (tab != SimulatorFrame.Tab.FLOWSHEET) return false;
        var tile = frame.tileAt(category, SimulatorFrame.inner(frame.tileWell()), x, y);
        if (tile != null) return gestures.carry(tile);
        if (!canvasBox.contains(x, y) || gestures.selected() != null && SimulatorFrame.panel(canvasBox).contains(x, y)) return false;
        return gestures.press(x, y, button, System.currentTimeMillis());
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (door != null) return kit.drag(y - doorTop()) || super.mouseDragged(x, y, button, dx, dy);
        gestures.drag(x, y);
        return true;
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        kit.release();
        if (door == null && dialog == Dialog.NONE) gestures.release(x, y, button);
        return super.mouseReleased(x, y, button);
    }

    @Override public void mouseMoved(double x, double y) {
        if (door == null) gestures.move(x, y);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (door != null) return kit.wheel(x - doorLeft(), y - doorTop(), dy);
        if (tab == SimulatorFrame.Tab.FLOWSHEET && canvasBox.contains(x, y)) view.wheel(dy, x, y);
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (dialog != Dialog.NONE) {
                dialog = Dialog.NONE;
                rebuild();
                return true;
            }
            if (door != null) {
                door = null;
                rebuild();
                return true;
            }
            return gestures.escape() || super.keyPressed(key, scan, modifiers);
        }
        if (gestures.mode() == SheetGestures.Mode.NAME && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            gestures.commit(gestures.naming().text());
            rebuild();
            return true;
        }
        if (super.keyPressed(key, scan, modifiers)) return true;
        boolean control = hasControlDown();
        if (control && key == GLFW.GLFW_KEY_Z) return editor.undo();
        if (control && key == GLFW.GLFW_KEY_Y) return editor.redo();
        if (key == GLFW.GLFW_KEY_DELETE && door == null) return gestures.delete();
        return false;
    }

    @Override public boolean isPauseScreen() {
        return false;
    }

    // ---- Drawing ----

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        var x = context();
        if (!signature(x).equals(signature)) rebuildWidgets();
        hovered = null;
        var skin = skin();
        var shown = shownDoor(x);
        if (dialog != Dialog.NONE) {
            drawDialog(g, x);
        } else if (shown != null) {
            int left = doorLeft(), top = doorTop();
            var canvas = new GuiCanvas(g, font, left, top);
            g.pose().pushPose();
            g.pose().translate(left, top, 0);
            DoorFrame.back(canvas, skin, shown, doorWidth(), doorHeight());
            kit.placeAll(shown.columns(), x.m());
            kit.place(left, top);
            hovered = kit.draw(canvas, skin, mouseX - left, mouseY - top);
            g.pose().popPose();
        } else {
            var canvas = new GuiCanvas(g, font, 0, 0);
            hovered = SimulatorPage.draw(canvas, skin, x, tab, frame, view, gestures, resultsState, category, editor, kit, title.getString(), host.holder(), mouseX, mouseY);
        }
        super.render(g, mouseX, mouseY, partial);
        KitDoor.hint(g, font, hovered, mouseX, mouseY);
    }

    private void drawDialog(GuiGraphics g, SheetContext x) {
        int w = FileColumns.W, h = FileColumns.H, left = (width - w) / 2, top = (height - h) / 2;
        var canvas = new GuiCanvas(g, font, left, top);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        skin().page(canvas, new Rect(0, 0, w, h));
        if (dialog == Dialog.SAVE_AS) skin().title(canvas, 10, 10, lang("flowsheet.file.save_title"));
        else if (dialog == Dialog.CHANGES && opening != null) FileColumns.drawChanges(canvas, skin(), lang("flowsheet.file.changes_title", openingName), FileColumns.changes(opening, ProcessSimulatorScreen::lang), lang("flowsheet.file.changes_foot"), w, h);
        else FileColumns.draw(canvas, skin(), SimulatorPage.listing(dialog.name(), x, files, chosen), w, h);
        g.pose().popPose();
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // The pages draw their own background.
    }

}
