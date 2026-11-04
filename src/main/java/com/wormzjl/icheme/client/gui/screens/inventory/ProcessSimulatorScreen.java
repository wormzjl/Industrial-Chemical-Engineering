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
import com.wormzjl.icheme.client.gui.flowsheet.FlowsheetExamples;
import com.wormzjl.icheme.client.gui.flowsheet.ResultsPage;
import com.wormzjl.icheme.client.gui.flowsheet.SelectionColumn;
import com.wormzjl.icheme.client.gui.flowsheet.SheetAutosave;
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
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorRunner;
import com.wormzjl.icheme.client.gui.flowsheet.SizingGlass;
import com.wormzjl.icheme.client.gui.flowsheet.SizingPage;
import com.wormzjl.icheme.client.gui.flowsheet.StreamDoors;
import com.wormzjl.icheme.client.gui.flowsheet.StreamProps;
import com.wormzjl.icheme.client.gui.flowsheet.UnitDoors;
import com.wormzjl.icheme.network.ProcessSimulatorNetwork;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.RevisionNotice;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.inventory.ProcessSimulatorMenu;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * The process simulator (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2): the whole window at {@link WindowScale}'s design height 480, with tabs, the canvas, the palette,
 * the doors. It only forwards events and draws: the sheet, its gestures and every page are Minecraft-free in {@code client.gui.flowsheet}, and {@link SimulatorHost} is the run and the
 * block. A change of what stands on screen rebuilds the widgets; the door's rows are placed again each frame for their texts. It stands on a menu, so a close, a disconnect and the block
 * going all end it, and it is no {@code AbstractContainerScreen}: a typed E is text here, and it has no slots.
 */
public final class ProcessSimulatorScreen extends Screen implements MenuAccess<ProcessSimulatorMenu> {
    private static final int DESIGN_HEIGHT = 480;
    private static final String FOLDER = "IChemE/flowsheets";
    /** An edited sheet goes to its autosave at most this often, in client ticks: a minute at 20 TPS. */
    private static final int AUTOSAVE_TICKS = 1200;
    /** How long a word that is no warning stays on the status line. */
    private static final int SAID_TICKS = 160;
    /** How long a sheet stands still before its sizes are rated again: half a second, so typing asks once. */
    private static final int RATE_TICKS = 10;

    private enum Dialog {
        NONE, OPEN, SAVE_AS, TEMPLATES, COLUMN_TEMPLATES, REPLACE, CHANGES, SAVE_TEMPLATE, SAVE_COLUMN, UNSAVED, DELETE;

        /** Whether it asks for a name. */
        boolean names() {
            return this == SAVE_AS || this == SAVE_TEMPLATE || this == SAVE_COLUMN;
        }

        /** Whether it lists rows to choose from. */
        boolean lists() {
            return this == OPEN || this == TEMPLATES || this == COLUMN_TEMPLATES || this == REPLACE;
        }
    }

    private final ProcessSimulatorMenu menu;
    private final SimulatorBlockHost host;
    private final SheetEditor editor;
    private final SheetView view = new SheetView();
    private final SheetGestures gestures;
    private final StreamProps.Fluids fluids;
    private final KitDoor kit = new KitDoor();
    private final ResultsPage.State resultsState = new ResultsPage.State();
    private final SizingPage.State sizingState = new SizingPage.State();
    /** The sheet Size all started from: its edit lands only on that sheet. */
    private @Nullable Flowsheet sizingFrom;
    private SimulatorRunner.@Nullable Rated applied;
    /** What the last quiet rating was asked for, so one edit asks once. */
    private SimulatorHost.@Nullable Finished rateLast;
    private @Nullable Flowsheet rateSheet, seenSheet;
    private int changedAt;
    private final Map<String, SkinField> entries = new HashMap<>();
    /** An entry's edit ended (a click elsewhere, another entry, Enter): the next frame shows what the sheet took. */
    private boolean committed;
    /** True while the widgets are built again: the game clears the focus then, which ends no edit. */
    private boolean rebuilding;
    /** The context and door the last build placed its widgets on: a frame that rebuilds draws these, not a third door. */
    private @Nullable Built built;

    private record Built(SheetContext context, @Nullable Door door) {
    }
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
    private List<FileColumns.ColumnTemplate> columnTemplates = List.of();
    private List<FileColumns.StreamTemplate> streamTemplates = List.of();
    /** A folder being read away from the frame, and what to do with it once it has been. */
    private @Nullable CompletableFuture<Runnable> reading;
    private @Nullable SheetState stated;
    private @Nullable Flowsheet statedSheet;
    private SimulatorHost.@Nullable Finished statedRun;
    private @Nullable Map<String, String> statedServer;
    private List<String> differing = List.of();
    private final Map<String, String> clientRevisions;
    private @Nullable Flowsheet opening;
    private String openingName = "";
    /** The file name the sheet in {@link #opening} keeps once loaded: none for an example. */
    private @Nullable String openingFile;
    /** Open's row the delete prompt asks about. */
    private FileColumns.@Nullable Entry deleting;
    /** What a prompt holds back until the player answers it. */
    private @Nullable Runnable pending;
    /** The file this sheet last came from or went to, which its autosave stands beside. */
    private @Nullable String fileName;
    /** The sheet last handed to the autosave writer; a sheet whose write failed comes back through {@link #unsaved}. */
    private @Nullable Flowsheet autosaved;
    private final AtomicReference<Flowsheet> unsaved = new AtomicReference<>();
    private int ticks, savedAt;
    private SimulatorHost.@Nullable Said said;
    private int saidAt;
    private boolean saving;

    public ProcessSimulatorScreen(ProcessSimulatorMenu menu, Inventory inventory, Component title) {
        super(title);
        this.menu = menu;
        var opened = menu.opening();
        var block = new SimulatorBlockHost(MaterialRuntime::active, opened.reader(), opened.holder().isEmpty() ? null : opened.holder(), opened.stored(), opened.tokens(), body -> ProcessSimulatorNetwork.sendBody(menu.containerId, body));
        host = block;
        var catalog = MaterialRuntime.active();
        // A sheet flashes on the world's own package, whatever it names: a stream's page reads that thermo.
        String network = catalog.presets().network().packageId();
        var pack = catalog.requirePackage(network);
        String packName = Component.translatableWithFallback("gui.icheme.flowsheet.basis.pack." + MaterialCatalog.Package.label(pack.id()).replace(':', '.'), MaterialCatalog.Package.label(pack.id())).getString();
        fluids = new StreamProps.Fluids(FluidThermodynamics.forNetwork(catalog, network), pack, id -> ProcessUi.materialName(catalog.name(id)), packName);
        var sheet = new Flowsheet(new Flowsheet.Basis(network, pack.revision(), fluids.axis()), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT);
        editor = new SheetEditor(sheet, measure(), ProcessSimulatorScreen::columnDefaults);
        clientRevisions = RevisionNotice.tokens(catalog);
        host.lock(editor);
        gestures = new SheetGestures(editor, view, this::open);
        block.opened(sheet);
        autosaved = sheet;
        if (opened.damaged()) block.say(new SimulatorHost.Said("store.kept", List.of(), true));
        menu.listen(this::replayed, this::answered);
    }

    @Override public ProcessSimulatorMenu getMenu() {
        return menu;
    }

    /** What the block held lands. Its parts follow the opening, so nothing has been edited yet. */
    private void replayed(byte[] packed) {
        var sheet = host.replayed(packed, fluids.model());
        if (sheet != null) {
            load(sheet);
            autosaved = sheet;
        }
        if (minecraft != null) rebuildWidgets();
    }

    /** What the server made of a body or a design. A body it took silently, unless the player asked for it by Save. */
    private void answered(String key, String detail) {
        boolean ok = key.equals(ProcessSimulatorNetwork.SAVED) || key.equals(ProcessSimulatorNetwork.DESIGN_SAVED) || key.equals(ProcessSimulatorNetwork.DESIGNS_SENT);
        if (key.equals(ProcessSimulatorNetwork.SAVED) && !saving) return;
        if (key.equals(ProcessSimulatorNetwork.SAVED)) saving = false;
        if (door != null) doorView.said = lang("flowsheet." + key, detail);
        else host.say(new SimulatorHost.Said(key, List.of(detail), !ok));
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
        return new SheetUnits(ClientConfig.temperature(), ClientConfig.pressure(), ClientConfig.flowTime(), SheetUnits.Basis.valueOf(ClientConfig.basis().name()));
    }

    /** What the frame and every key read: worked out once for a sheet and a run, since a sheet's signature strings are not a frame's work. Only the run's clock is new each time. */
    private SheetContext context() {
        host.lock(editor);
        var rated = host.rated();
        if (rated != null && rated.edit() && rated != applied && host.running() == null) {
            // Size all's sizes land as one step, on the sheet it started from; an edit since, which a run's lock leaves none of, keeps its own.
            applied = rated;
            if (editor.sheet() == sizingFrom) editor.sized(rated.sheet().copy());
            sizingFrom = null;
        }
        var last = host.last();
        if (stated == null || statedSheet != editor.sheet() || statedRun != last) {
            statedSheet = editor.sheet();
            statedRun = last;
            stated = SheetState.of(statedSheet, last);
        }
        var server = host.serverRevisions();
        if (server != statedServer) {
            statedServer = server;
            differing = RevisionNotice.differing(clientRevisions, server);
        }
        return new SheetContext(editor, stated.running(host.running()), units(), ProcessSimulatorScreen::lang, GuiCanvas.measure(font), fluids, host, differing);
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

    /** Esc and Close end here: the block takes what is new first, for the close that follows must find it stored. */
    @Override public void onClose() {
        host.settle(editor.sheet());
        minecraft.player.closeContainer();
    }

    @Override public void removed() {
        super.removed();
        autosave();
        host.close();
        WindowScale.restore(minecraft);
    }

    @Override public void tick() {
        ticks++;
        if (minecraft.player == null || !minecraft.player.isAlive()) {
            minecraft.player.closeContainer();
            return;
        }
        host.ranOut(editor.sheet());
        if (ticks - savedAt >= AUTOSAVE_TICKS) {
            savedAt = ticks;
            autosave();
        }
        var failed = unsaved.getAndSet(null);
        if (failed != null) {
            // The next autosave tries again, unless the sheet moved on and that write is already asked for.
            if (failed == autosaved) autosaved = null;
            host.say(new SimulatorHost.Said("store.autosave_failed", List.of(), true));
        }
        var now = host.said();
        if (now != said) {
            said = now;
            saidAt = ticks;
        }
        if (now != null && !now.warn() && ticks - saidAt >= SAID_TICKS) host.say(null);
        rateWhenDue();
    }

    /**
     * The Sizing tab's lamps hold for the run on show at the sheet's sizes (plan section 3.1 item 5). A block's stored run comes
     * without them, and an edit of a size moves them: a quiet rating rebuilds them once the sheet has stood still a moment.
     */
    private void rateWhenDue() {
        var sheet = editor.sheet();
        if (sheet != seenSheet) {
            seenSheet = sheet;
            changedAt = ticks;
        }
        var last = host.last();
        if (tab != SimulatorFrame.Tab.SIZING || last == null || host.running() != null || host.rating() || ticks - changedAt < RATE_TICKS) return;
        if (rateLast == last && rateSheet == sheet) return;
        var rated = host.rated();
        rateLast = last;
        rateSheet = sheet;
        if (rated != null && rated.run() == last && SheetState.same(rated.sheet(), sheet)) return;
        host.rate(sheet);
    }

    /**
     * The editor's own copy, beside its file: what stays when the block goes. It takes the sheet as it stands, and a reader has nothing
     * of its own to keep. The client's one writer writes it off the render thread (P3); a failed write reaches the status line next tick.
     */
    private void autosave() {
        var sheet = editor.sheet();
        if (host.reader() || sheet == autosaved) return;
        autosaved = sheet;
        SheetAutosave.CLIENT.save(FileColumns.autosave(folder(), fileName, menu.opening().sheet().toString()), sheet, unsaved::set);
    }

    @Override protected void init() {
        WindowScale.apply(minecraft, this, DESIGN_HEIGHT, (w, h) -> h >= SimulatorFrame.LEAST_H && w >= SimulatorFrame.LEAST_W);
        frame = new SimulatorFrame(width, height, font.width(title));
        entries.clear();
        var x = context();
        if (tab == SimulatorFrame.Tab.SIZING && !SizingPage.open(x)) tab = SimulatorFrame.Tab.FLOWSHEET;
        var shown = shownDoor(x);
        built = new Built(x, shown);
        signature = signature(x, shown);
        if (dialog != Dialog.NONE) dialogWidgets();
        else if (shown != null) doorWidgets(shown);
        else simulatorWidgets(x);
    }

    private SkinButton add(Column.Button spec, Rect at) {
        var button = SkinButton.of(spec, at, door == null || dialog != Dialog.NONE ? this::act : this::actDoor, ProcessSimulatorScreen::skin);
        if ((spec.key().equals("run") || spec.key().equals("size_all")) && spec.active()) button.primary(true);
        // Run: its ▶, lit amber while the results are out of date (the owner, 2026-11-04, option C).
        if (spec.key().equals("run")) button.sign(Skin.Icon.RUN).due(spec.active() && SimulatorPage.runDue(context(), host));
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
                // A window opens on the whole sheet, at its own size where it can: fit stops at zoom 1.
                placed = true;
                view.fit(editor.layout().bounds());
            }
            var selected = gestures.selected();
            if (selected != null && !(selected.unit() ? editor.unit(selected.id()) == null : editor.stream(selected.id()) == null)) panelWidgets(x, selected);
            for (var slot : SimulatorPage.canvasKeys(x, frame, canvasBox, category)) add(slot.button(), slot.rect());
            nameWidget();
        } else if (tab == SimulatorFrame.Tab.RESULTS) pageWidgets(ResultsPage.rows(x, resultsState), Map.of(), SimulatorFrame.RESULT_DOOR, (key, text) -> {
        });
        else if (tab == SimulatorFrame.Tab.BASIS) pageWidgets(BasisPage.rows(x), BasisPage.values(x), SimulatorFrame.BASIS_DOOR, (key, text) -> BasisPage.entry(context(), key, text));
        else if (tab == SimulatorFrame.Tab.SIZING) pageWidgets(SizingPage.rows(x, sizingState), SizingPage.values(x, sizingState), SimulatorFrame.SIZING_DOOR, (key, text) -> SizingPage.entry(context(), sizingState, key, text));
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

    private void pageWidgets(List<Column.Row> rows, Map<String, String> values, int width, Entry write) {
        kit.layout(new Rect(10, SimulatorFrame.DOOR_TOP, width - 10, height - SimulatorFrame.BOTTOM - SimulatorFrame.DOOR_TOP), rows, GuiCanvas.measure(font));
        keep(values, write, this::act);
    }

    private interface Entry {
        void write(String key, String text);
    }

    private void keep(Map<String, String> values, Entry write, java.util.function.Consumer<String> press) {
        kit.keep((field, slot) -> {
            var box = new SkinField(font, slot.x(), slot.y(), slot.w(), slot.h(), Component.literal(field.label()), ProcessSimulatorScreen::skin);
            box.setValue(values.getOrDefault(field.key(), ""));
            box.setEditable(editor.editable());
            box.setResponder(text -> write.write(field.key(), text));
            box.onCommit(this::commit);
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
        kit.layout(DoorFrame.areas(shown, w, h), DoorFrame.bar(shown, w), DoorFrame.columns(shown, w), m);
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
            box.setEditable(editor.editable());
            if (slot.hint() != null) box.setHint(Component.literal(slot.hint()));
            box.setResponder(text -> shown.actions().entry(slot.key(), text));
            box.onCommit(this::commit);
            entries.put(slot.key(), box);
            addRenderableWidget(box);
        }
    }

    private void dialogWidgets() {
        int w = FileColumns.W, h = FileColumns.H, left = (width - w) / 2, top = (height - h) / 2;
        var x = context();
        if (dialog.names()) {
            var name = new SkinField(font, left + 12, top + 40, w - 24, 18, Component.literal("name"), ProcessSimulatorScreen::skin);
            name.setHint(Component.literal(lang("flowsheet.file.name")));
            entries.put("file", name);
            addRenderableWidget(name);
            setFocused(name);
            if (dialog == Dialog.SAVE_AS) keys(left, top, "cancel", lang("flowsheet.file.cancel"), "to_clipboard", lang("flowsheet.file.to_clipboard"), "save", lang("flowsheet.file.save"));
            else keys(left, top, "cancel", lang("flowsheet.file.cancel"), "save", lang("flowsheet.file.save"));
            return;
        }
        switch (dialog) {
            case UNSAVED -> keys(left, top, "cancel", lang("flowsheet.file.cancel"), "discard", lang("flowsheet.file.discard"), "store", lang("flowsheet.file.save"));
            case CHANGES -> keys(left, top, "cancel", lang("flowsheet.file.cancel"), "load", lang("flowsheet.file.open"));
            case DELETE -> keys(left, top, "cancel", lang("flowsheet.file.cancel"), "delete", lang("flowsheet.file.delete"));
            default -> {
                var list = listing(x);
                var slots = DialogColumns.footer(FileColumns.W, FileColumns.H, list.keys().length);
                // Each dialog's confirming key is primary, as the renders draw it (gui-creation rule 6): Open's Open, the templates' Load.
                for (int i = 0; i < slots.size(); i++) add(new Column.Button("dialog/" + i, list.keys()[i], list.off()[i] == null, list.off()[i]), slots.get(i).moved(left, top)).primary(i == list.primary());
            }
        }
    }

    /** A prompt's push buttons: each key {@code dialog/<key>} with its text, in pairs; the last confirms, primary. */
    private void keys(int left, int top, String... pairs) {
        var slots = DialogColumns.footer(FileColumns.W, FileColumns.H, pairs.length / 2);
        for (int i = 0; i < pairs.length; i += 2)
            add(new Column.Button("dialog/" + pairs[i], pairs[i + 1], true, null), slots.get(i / 2).moved(left, top)).primary(i == pairs.length - 2);
    }

    /**
     * An entry's edit ended. Each keystroke already wrote the sheet; this ends the coalescing step, so the next edit is an
     * undo step of its own, and asks the next frame to show the values the sheet took (and, on the Sizing tab, the new rating).
     */
    private void commit() {
        if (rebuilding) return;
        editor.endStep();
        committed = true;
    }

    /** The widgets built again, the entry that has the focus (one clicked into as the last edit ended) kept focused at its cursor. */
    private void rebuildKeepingFocus() {
        String key = entries.entrySet().stream().filter(e -> e.getValue() == getFocused() && e.getValue().isFocused()).map(Map.Entry::getKey).findFirst().orElse(null);
        int cursor = key == null ? 0 : entries.get(key).getCursorPosition();
        rebuildWidgets();
        var box = key == null ? null : entries.get(key);
        if (box == null) return;
        setFocused(box);
        box.setCursorPosition(cursor);
    }

    @Override protected void rebuildWidgets() {
        rebuilding = true;
        try {
            super.rebuildWidgets();
        } finally {
            rebuilding = false;
        }
    }

    /** What decides whether the widgets stand, for the context and door this frame draws: a door is built once a frame. */
    private String signature(SheetContext x, @Nullable Door shown) {
        var text = new StringBuilder().append(tab).append(dialog).append(door).append(doorView.tab).append(doorView.connection).append(doorView.index).append(doorView.phase).append(doorView.results).append(doorView.metric);
        text.append(gestures.selected()).append(gestures.naming() == null ? "" : gestures.naming().target()).append(category).append(resultsState.view).append(host.running() == null).append(editor.canUndo()).append(editor.canRedo()).append(SimulatorPage.runDue(x, host));
        text.append(x.units());
        if (shown != null) for (var rows : shown.columns()) text.append(rows.size()).append(SimulatorPage.keys(rows));
        if (shown != null) text.append(shown.glass().slots(new Rect(0, 0, 400, 400)).stream().map(Door.Slot::key).toList());
        if (tab == SimulatorFrame.Tab.SIZING) {
            text.append(sizingState.view).append(sizingState.unit).append(sizingState.line).append(sizingState.path).append(SimulatorPage.keys(SizingPage.rows(x, sizingState)));
            // A new rating's sizes fill the entries, but not under the player's hands: the next rebuild after typing takes them.
            if (!(getFocused() instanceof SkinField)) text.append(System.identityHashCode(SizingPage.sized(x))).append(SizingPage.open(x)).append(SizingPage.sizes(x));
        }
        return text.toString();
    }

    // ---- Keys ----

    private void act(String key) {
        var x = context();
        if (host.said() != null && !key.startsWith("dialog/")) host.say(null);
        switch (key) {
            case "close" -> onClose();
            case "run" -> host.run(editor.sheet());
            case "size_all" -> {
                sizingFrom = editor.sheet();
                host.size(editor.sheet());
            }
            case "stop" -> host.stop();
            case "send" -> {
                // The key acts only while SizingPage.unsent has nothing against it; the server reads the designs again.
                if (SizingPage.unsent(x) == null) ProcessSimulatorNetwork.sendDesigns(menu.containerId, SizingPage.designs(x));
            }
            case "undo" -> editor.undo();
            case "redo" -> editor.redo();
            case "zoom/out" -> view.zoom(1 / SheetView.STEP, canvasBox.x() + canvasBox.w() / 2.0, canvasBox.y() + canvasBox.h() / 2.0);
            case "zoom/in" -> view.zoom(SheetView.STEP, canvasBox.x() + canvasBox.w() / 2.0, canvasBox.y() + canvasBox.h() / 2.0);
            case "zoom/fit" -> view.fit(editor.layout().bounds());
            case "file/new" -> newSheet();
            case "file/open" -> openDialog();
            case "file/save" -> {
                saving = true;
                host.store(editor.sheet());
            }
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
                else if (!SizingPage.press(x, sizingState, key)) ResultsPage.press(resultsState, key);
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
            // Mole, Mass, Volume and back: a stream's flow on that basis.
            default -> ClientConfig.basis(ClientConfig.Basis.valueOf(SheetUnits.Basis.valueOf(ClientConfig.basis().name()).next().name()));
        }
    }

    private void actDoor(String key) {
        var x = context();
        var shown = shownDoor(x);
        if (shown == null) return;
        if (key.equals("close_door")) door = null;
        else if (key.equals("templates") && !door.unit()) {
            chosen = -1;
            streamTemplates = List.of();
            dialog = Dialog.TEMPLATES;
            read(() -> {
                var listed = ColumnTemplates.streams(minecraft, MaterialRuntime.active(), fluids);
                return () -> streamTemplates = listed;
            });
        } else if (key.equals("templates")) {
            readColumnTemplates();
            dialog = Dialog.COLUMN_TEMPLATES;
        } else if (key.equals("save") && !door.unit()) dialog = Dialog.SAVE_TEMPLATE;
        else if (key.equals("save_design")) saveDesign(x);
        else if (key.equals("solve")) host.alone(editor.sheet(), door.id());
        else if (key.startsWith("unit/")) unitKey(key.substring(5));
        else if (key.equals("temperature") || key.equals("basis")) unitKey(key);
        else shown.actions().button(key);
        rebuild();
    }

    /** Sends the column as its door stands; the server checks it again ({@code ColumnDesign.of}) and stores it, and its answer comes back to this door. */
    private void saveDesign(SheetContext x) {
        var input = ColumnDoors.input(x, door.id());
        if (input != null) ProcessSimulatorNetwork.sendDesign(menu.containerId, input, ColumnDoors.choices(editor.unit(door.id())));
    }

    private void readColumnTemplates() {
        chosen = -1;
        columnTemplates = List.of();
        read(() -> {
            var listed = ColumnTemplates.listed(minecraft);
            return () -> columnTemplates = listed;
        });
    }

    private void actPanel(String key) {
        var selected = gestures.selected();
        if (selected == null) return;
        switch (key) {
            case "open" -> open(selected);
            case "copy" -> {
                var stream = editor.stream(selected.id());
                var words = stream == null ? null : StreamDoors.copy(context(), stream);
                if (words != null) host.say(new SimulatorHost.Said("panel.copied", List.of(words), false));
            }
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
        fileName = null;
        replaceWith(new Flowsheet(sheet.basis(), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT));
    }

    /** Puts {@code sheet} in place of this one, once the player has said what becomes of an edit the block has not taken. */
    private void replaceWith(Flowsheet sheet) {
        boolean blank = editor.sheet().units().isEmpty() && editor.sheet().streams().isEmpty();
        if (!blank && host.unsaved(editor.sheet())) {
            pending = () -> load(sheet);
            dialog = Dialog.UNSAVED;
            return;
        }
        load(sheet);
        dialog = Dialog.NONE;
    }

    private Path folder() {
        return minecraft.gameDirectory.toPath().resolve(FOLDER);
    }

    private void openDialog() {
        files = List.of();
        chosen = -1;
        dialog = Dialog.OPEN;
        read(() -> {
            var listed = FileColumns.entries(folder(), FlowsheetExamples.read(ColumnTemplates.resources(minecraft)), ProcessSimulatorScreen::lang);
            return () -> files = listed;
        });
    }

    /** Reads a folder away from the frame. The dialog stands empty until the read lands, which the next frame sees. */
    private void read(Supplier<Runnable> read) {
        reading = CompletableFuture.supplyAsync(read);
    }

    private FileColumns.Listing listing(SheetContext x) {
        return SimulatorPage.listing(dialog.name(), x, files, streamTemplates, columnTemplates, chosen);
    }

    /** The sheet a player keeps as text, for another player: the file's own format. */
    private void fromClipboard() {
        byte[] text = minecraft.keyboardHandler.getClipboard().getBytes(StandardCharsets.UTF_8);
        try {
            if (text.length > SheetBody.MAX_UNPACKED) throw new FlowsheetFailure("file.damaged", "text");
            fileName = null;
            replaceWith(FlowsheetFile.read(text));
        } catch (FlowsheetFailure refused) {
            dialog = Dialog.NONE;
            host.say(new SimulatorHost.Said("file.clipboard_refused", List.of(), true));
        }
    }

    /** A dialog's name given: where it goes depends on what asked. */
    private void saveNamed(SheetContext x) {
        var name = entries.get("file").getValue().strip();
        if (!FileColumns.nameAllowed(name)) return;
        try {
            switch (dialog) {
                case SAVE_TEMPLATE -> {
                    var stream = editor.stream(door.id());
                    ColumnTemplates.saveStream(minecraft, name, stream.feed());
                    doorView.said = lang("flowsheet.file.template_saved", name);
                    dialog = Dialog.NONE;
                }
                case SAVE_COLUMN -> {
                    var input = ColumnDoors.input(x, door.id());
                    if (input == null) doorView.said = lang("flowsheet.file.column_no_feed");
                    else {
                        ColumnTemplates.saveColumn(minecraft, name, input);
                        doorView.said = lang("flowsheet.file.template_saved", name);
                    }
                    dialog = Dialog.NONE;
                }
                default -> {
                    Files.createDirectories(folder());
                    FlowsheetFile.save(FileColumns.file(folder(), name), editor.sheet());
                    fileName = name;
                    dialog = Dialog.NONE;
                }
            }
        } catch (IOException | IllegalArgumentException unwritable) {
            dialog = Dialog.NONE;
            host.say(new SimulatorHost.Said("file.save_failed", List.of(), true));
        }
    }

    private void dialogKey(String key) {
        var x = context();
        if (key.equals("cancel")) {
            // The delete prompt's Cancel goes back to Open, its row still chosen.
            dialog = dialog == Dialog.DELETE ? Dialog.OPEN : Dialog.NONE;
            deleting = null;
            return;
        }
        if (key.equals("delete")) {
            var entry = deleting;
            deleting = null;
            if (entry != null) {
                try {
                    FileColumns.delete(folder(), entry);
                    fileName = FileColumns.nameAfterDelete(folder(), fileName, entry);
                } catch (IOException refused) {
                    host.say(new SimulatorHost.Said("file.delete_failed", List.of(), true));
                }
            }
            openDialog();
            return;
        }
        if (key.equals("save")) {
            saveNamed(x);
            return;
        }
        if (key.equals("to_clipboard")) {
            minecraft.keyboardHandler.setClipboard(new String(FlowsheetFile.write(editor.sheet()), StandardCharsets.UTF_8));
            dialog = Dialog.NONE;
            host.say(new SimulatorHost.Said("file.on_clipboard", List.of(), false));
            return;
        }
        if (key.equals("load")) {
            dialog = Dialog.NONE;
            if (opening != null) {
                fileName = openingFile;
                replaceWith(opening);
            }
            return;
        }
        if (key.equals("discard") || key.equals("store")) {
            if (key.equals("store")) host.store(editor.sheet());
            if (pending != null) pending.run();
            pending = null;
            dialog = Dialog.NONE;
            return;
        }
        int index = Integer.parseInt(key);
        var list = listing(x);
        if (index == 0) {
            dialog = Dialog.NONE;
            return;
        }
        if (dialog == Dialog.OPEN && index == FileColumns.OPEN_FOLDER) {
            Util.getPlatform().openPath(folder());
            return;
        }
        if (dialog == Dialog.OPEN && index == FileColumns.OPEN_CLIPBOARD) {
            fromClipboard();
            return;
        }
        if (dialog == Dialog.OPEN && index == FileColumns.OPEN_DELETE) {
            if (chosen >= 0 && chosen < files.size() && FileColumns.deletable(files.get(chosen))) {
                deleting = files.get(chosen);
                dialog = Dialog.DELETE;
            }
            return;
        }
        if (dialog == Dialog.COLUMN_TEMPLATES && index == 2) {
            dialog = Dialog.SAVE_COLUMN;
            return;
        }
        if (dialog == Dialog.COLUMN_TEMPLATES && index == 1) {
            if (chosen >= 0 && !columnTemplates.get(chosen).bundled()) {
                try {
                    ColumnTemplates.deleteColumn(minecraft, columnTemplates.get(chosen).file());
                    readColumnTemplates();
                } catch (IOException gone) {
                    host.say(new SimulatorHost.Said("file.save_failed", List.of(), true));
                }
            }
            return;
        }
        if (index != list.primary() || chosen < 0) return;
        switch (dialog) {
            case OPEN -> {
                var entry = files.get(chosen);
                if (!entry.opens()) return;
                try {
                    var opened = FileColumns.opened(entry);
                    if (!FileColumns.changes(opened.sheet(), fluids, ProcessSimulatorScreen::columnDefaults, ProcessSimulatorScreen::lang).isEmpty()) {
                        opening = opened.sheet();
                        openingName = entry.name();
                        openingFile = opened.name();
                        dialog = Dialog.CHANGES;
                        return;
                    }
                    fileName = opened.name();
                    replaceWith(opened.sheet());
                    return;
                } catch (IOException gone) {
                    return;
                }
            }
            case TEMPLATES -> StreamDoors.template(x, door.id(), streamTemplates.get(chosen));
            case COLUMN_TEMPLATES -> ColumnDoors.template(x, door.id(), columnTemplates.get(chosen).template());
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
        SkinField.clickOutside(this, x, y);
        if (dialog != Dialog.NONE) {
            var list = dialog.lists() ? listing(context()) : null;
            if (list != null) {
                int row = FileColumns.rowAt(list, FileColumns.W, FileColumns.H, x - (width - FileColumns.W) / 2.0, y - (height - FileColumns.H) / 2.0);
                if (row >= 0 && row != chosen) {
                    chosen = row;
                    rebuildWidgets();
                }
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
        if (tab == SimulatorFrame.Tab.SIZING) {
            var c = context();
            if (!SizingGlass.pick(sizingState, SizingGlass.click(c, sizingState, c.m(), SimulatorFrame.inner(SimulatorPage.glass(frame, width)), x, y))) return false;
            rebuildWidgets();
            return true;
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
        if (tab == SimulatorFrame.Tab.SIZING && SimulatorPage.glass(frame, width).contains(x, y)) SizingGlass.scroll(sizingState, dy);
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
        if (reading != null && reading.isDone()) {
            reading.join().run();
            reading = null;
            rebuildWidgets();
        }
        if (committed) {
            committed = false;
            rebuildKeepingFocus();
        }
        var x = context();
        var shown = shownDoor(x);
        if (!signature(x, shown).equals(signature)) {
            // The rebuild built the frame's door again, on a context it read anew: draw that one.
            rebuildWidgets();
            x = built.context();
            shown = built.door();
        }
        hovered = null;
        var context = x;
        var drawn = shown;
        // The whole frame, widgets too, in one batch: its fills go out in runs that its texts and sprites no longer break (P5).
        new GuiCanvas(g, font, 0, 0).batch(() -> {
            draw(g, context, drawn, mouseX, mouseY);
            super.render(g, mouseX, mouseY, partial);
        });
        KitDoor.hint(g, font, hovered, mouseX, mouseY);
    }

    /** The page, the door or the dialog under the widgets. */
    private void draw(GuiGraphics g, SheetContext x, @Nullable Door shown, int mouseX, int mouseY) {
        var skin = skin();
        if (dialog != Dialog.NONE) {
            drawDialog(g, x);
        } else if (shown != null) {
            int left = doorLeft(), top = doorTop();
            var canvas = new GuiCanvas(g, font, left, top);
            g.pose().pushPose();
            g.pose().translate(left, top, 0);
            DoorFrame.back(canvas, skin, shown, doorWidth(), doorHeight());
            kit.placeAll(DoorFrame.columns(shown, doorWidth()), x.m());
            kit.place(left, top);
            hovered = kit.draw(canvas, skin, mouseX - left, mouseY - top);
            g.pose().popPose();
        } else {
            var canvas = new GuiCanvas(g, font, 0, 0);
            hovered = SimulatorPage.draw(canvas, skin, x, tab, frame, view, gestures, resultsState, sizingState, category, editor, kit, title.getString(), host.holder(), mouseX, mouseY);
        }
    }

    private void drawDialog(GuiGraphics g, SheetContext x) {
        int w = FileColumns.W, h = FileColumns.H, left = (width - w) / 2, top = (height - h) / 2;
        var canvas = new GuiCanvas(g, font, left, top);
        g.pose().pushPose();
        g.pose().translate(left, top, 0);
        skin().page(canvas, new Rect(0, 0, w, h));
        if (dialog.names()) skin().title(canvas, 10, 10, lang(dialog == Dialog.SAVE_AS ? "flowsheet.file.save_title" : dialog == Dialog.SAVE_TEMPLATE ? "flowsheet.file.template_title" : "flowsheet.file.column_title"));
        else if (dialog == Dialog.UNSAVED) FileColumns.drawChanges(canvas, skin(), lang("flowsheet.file.unsaved_title"), FileColumns.unsaved(ProcessSimulatorScreen::lang), lang("flowsheet.file.unsaved_foot"), w, h);
        else if (dialog == Dialog.DELETE && deleting != null) FileColumns.drawChanges(canvas, skin(), lang("flowsheet.file.delete_title", deleting.name()), FileColumns.deleting(ProcessSimulatorScreen::lang), "", w, h);
        else if (dialog == Dialog.CHANGES && opening != null) FileColumns.drawChanges(canvas, skin(), lang("flowsheet.file.changes_title", openingName), FileColumns.changes(opening, fluids, ProcessSimulatorScreen::columnDefaults, ProcessSimulatorScreen::lang), lang("flowsheet.file.changes_foot"), w, h);
        else FileColumns.draw(canvas, skin(), listing(x), w, h);
        g.pose().popPose();
    }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // The pages draw their own background.
    }

}
