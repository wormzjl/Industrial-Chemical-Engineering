import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.HeldDraws;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.Sprite;
import com.wormzjl.icheme.client.gui.flowsheet.ColumnDoors;
import com.wormzjl.icheme.client.gui.flowsheet.Door;
import com.wormzjl.icheme.client.gui.flowsheet.DoorFrame;
import com.wormzjl.icheme.client.gui.flowsheet.FileColumns;
import com.wormzjl.icheme.client.gui.flowsheet.FlowsheetExamples;
import com.wormzjl.icheme.client.gui.flowsheet.ResultsPage;
import com.wormzjl.icheme.client.gui.flowsheet.SelectionColumn;
import com.wormzjl.icheme.client.gui.flowsheet.SheetContext;
import com.wormzjl.icheme.client.gui.flowsheet.SheetEditor;
import com.wormzjl.icheme.client.gui.flowsheet.SheetGestures;
import com.wormzjl.icheme.client.gui.flowsheet.SheetState;
import com.wormzjl.icheme.client.gui.flowsheet.SheetUnits;
import com.wormzjl.icheme.client.gui.flowsheet.SheetView;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorFrame;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorHost;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorPage;
import com.wormzjl.icheme.client.gui.flowsheet.SimulatorRunner;
import com.wormzjl.icheme.client.gui.flowsheet.SizingPage;
import com.wormzjl.icheme.client.gui.flowsheet.StreamDoors;
import com.wormzjl.icheme.client.gui.flowsheet.StreamProps;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * The process simulator's CPU, allocation and draw-call cost on the bundled crude unit (batch 2026-11-02-process-simulator,
 * PERFORMANCE_REVIEW.md). A plain JVM: the mod's classes and resources, gson, and the merged game jar only so that {@code KitDoor}
 * loads (nothing of the game runs). Each claim is one phase in its own JVM: a warm-up of {@code -Dwarm} seconds, then a measurement
 * of {@code -Dmeasure} seconds (the owner's rule: 60 and 60) with a JFR recording of that window only. See README.md.
 * <pre>java -cp "build/classes/java/main;build/resources/main;gson.jar;merged.jar" tools/simulator-bench/SimulatorBench.java &lt;phase&gt; &lt;out folder&gt;</pre>
 */
public final class SimulatorBench {
    static final double WARM = Double.parseDouble(System.getProperty("warm", "60")), MEASURE = Double.parseDouble(System.getProperty("measure", "60"));
    static final int W = 944, H = 524, DW = 790, DH = 524;
    static final Measure M = Measure.fixed(6);
    static final Skin SKIN = PanelSkin.LIGHT;
    static final SheetUnits UNITS = SheetUnits.DEFAULT;
    static final JsonObject LANG = JsonParser.parseReader(new InputStreamReader(ClassLoader.getSystemResourceAsStream("assets/icheme/lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    /** The game's lookup: a key, then its arguments formatted in. */
    static final HeatControlColumn.Texts T = (key, a) -> {
        var line = LANG.get("gui.icheme." + key);
        String text = line == null ? key : line.getAsString();
        try {
            return a.length == 0 ? text : String.format(Locale.ROOT, text, a);
        } catch (RuntimeException bad) {
            return text;
        }
    };
    static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final com.sun.management.OperatingSystemMXBean OS = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    static MaterialCatalog CATALOG;
    static String NETWORK;
    static StreamProps.Fluids FLUIDS;
    static Path OUT;
    static final StringBuilder REPORT = new StringBuilder();

    static void say(String format, Object... args) {
        String line = String.format(Locale.ROOT, format, args);
        System.out.println(line);
        REPORT.append(line).append('\n');
    }

    // ---- The sheet and what it ran to ----

    static Flowsheet crudeUnit() {
        var example = FlowsheetExamples.read(ClassLoader::getSystemResourceAsStream).stream().filter(e -> e.stem().equals("crude_unit")).findFirst().orElseThrow();
        if (example.sheet() == null) throw new IllegalStateException("crude_unit does not read: " + example.refused());
        return example.sheet();
    }

    /** A copy with nothing shared: the sheet read again from its own file text. */
    static Flowsheet fresh(Flowsheet sheet) {
        try {
            var file = OUT.resolve("copy.json");
            FlowsheetFile.save(file, sheet);
            return FlowsheetFile.read(Files.readAllBytes(file));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Params columnDefaults() {
        var input = CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG);
        return ColumnDoor.from(input, ColumnComponents.Choices.DEFAULT.condenser());
    }

    static FlowThermo thermo() {
        return new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> {});
    }

    /** What a run spends, unit by unit: wall and thread CPU from one calculation's start to the next's, and its checkpoints. */
    static final class Units implements FlowsheetSolver.Progress {
        final Map<String, long[]> spent = new TreeMap<>();
        String unit = "(compile, estimates, thermo)";
        long wall = System.nanoTime(), cpu = THREADS.getCurrentThreadCpuTime();
        int passes, checkpoints;

        @Override public void calculation(int pass, int passes, String unit) {
            close();
            this.unit = unit;
            this.passes = Math.max(this.passes, pass);
        }

        void close() {
            long w = System.nanoTime(), c = THREADS.getCurrentThreadCpuTime();
            var row = spent.computeIfAbsent(unit, k -> new long[4]);
            row[0] += w - wall;
            row[1] += c - cpu;
            row[2]++;
            wall = w;
            cpu = c;
        }

        void checkpoint() {
            checkpoints++;
            spent.computeIfAbsent(unit, k -> new long[4])[3]++;
        }

        void as(String next) {
            close();
            unit = next;
        }
    }

    /** Run, as {@code SimulatorRunner}'s job does it: the engine from {@code last}, then the sheet's items rated where it stood steady. */
    static FlowsheetRun run(Flowsheet sheet, FlowsheetRun last, Units units) {
        var run = FlowsheetEngine.run(sheet, CATALOG, units::checkpoint, last, units);
        units.as("(rate after the run)");
        if (run.status() == FlowsheetRun.Status.STEADY || sheet.sizing().any()) MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(sheet, run, thermo()));
        units.close();
        return run;
    }

    record Solved(Flowsheet sheet, FlowsheetRun run, SimulatorHost.Finished finished, SimulatorRunner.Rated rated) {
    }

    /** The crude unit run, sized by Size all and rated, as a player leaves it before the Sizing tab. */
    static Solved solved() {
        var sheet = crudeUnit();
        var run = run(sheet, null, new Units());
        if (run.status() != FlowsheetRun.Status.STEADY) throw new IllegalStateException("crude unit ran " + run.status());
        var all = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(sheet, run, thermo(), FlowsheetSolver.Progress.NONE));
        var finished = new SimulatorHost.Finished(all.sheet(), all.run(), 4.0);
        var sized = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(all.sheet(), all.run(), thermo()));
        return new Solved(all.sheet(), all.run(), finished, new SimulatorRunner.Rated(all.sheet(), finished, sized, false));
    }

    /** A block's host with a run and a rating in hand, nothing running. */
    record Host(SimulatorHost.Finished done, SimulatorRunner.Rated sizes) implements SimulatorHost {
        @Override public Finished last() {
            return done;
        }

        @Override public SimulatorRunner.Rated rated() {
            return sizes;
        }

        @Override public SheetState.Running running() {
            return null;
        }

        @Override public boolean canRun() {
            return true;
        }

        @Override public boolean stores() {
            return true;
        }
    }

    // ---- The timed loop ----

    interface Op {
        void run() throws Exception;
    }

    static long[] grow(long[] a, int n) {
        return n < a.length ? a : Arrays.copyOf(a, a.length * 2);
    }

    /** CPU of every live Java thread; what the process spends beyond it is the VM's own threads. */
    static long javaThreadsCpu() {
        long sum = 0;
        for (long id : THREADS.getAllThreadIds()) sum += Math.max(0, THREADS.getThreadCpuTime(id));
        return sum;
    }

    static double ms(long ns) {
        return ns / 1e6;
    }

    /**
     * {@code op} again and again: {@link #WARM} seconds, then {@link #MEASURE} seconds recorded by JFR to {@code <name>.jfr}. Prints
     * wall and thread CPU per call (mean, p50, p99, max), process CPU, bytes allocated per call, GC count and time, and the heap's peak.
     */
    static long timed(String name, Op op) throws Exception {
        long end = System.nanoTime() + (long) (WARM * 1e9);
        int warm = 0;
        do {
            op.run();
            warm++;
        } while (System.nanoTime() < end);
        System.gc();
        var pools = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP).toList();
        pools.forEach(MemoryPoolMXBean::resetPeakUsage);
        var gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long gcCount0 = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum(), gcTime0 = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        var recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName(name);
        recording.start();
        long[] walls = new long[1024], cpus = new long[1024];
        int n = 0;
        long java0 = javaThreadsCpu(), alloc0 = THREADS.getCurrentThreadAllocatedBytes(), process0 = OS.getProcessCpuTime(), began = System.nanoTime();
        end = began + (long) (MEASURE * 1e9);
        do {
            long w = System.nanoTime(), c = THREADS.getCurrentThreadCpuTime();
            op.run();
            walls = grow(walls, n);
            cpus = grow(cpus, n);
            cpus[n] = THREADS.getCurrentThreadCpuTime() - c;
            walls[n++] = System.nanoTime() - w;
        } while (System.nanoTime() < end);
        long took = System.nanoTime() - began, alloc = THREADS.getCurrentThreadAllocatedBytes() - alloc0, process = OS.getProcessCpuTime() - process0, javaAll = javaThreadsCpu() - java0;
        recording.stop();
        recording.dump(OUT.resolve(name + ".jfr"));
        recording.close();
        long gcCount = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum() - gcCount0, gcTime = gcs.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum() - gcTime0;
        long peak = pools.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        var w = Arrays.copyOf(walls, n);
        var c = Arrays.copyOf(cpus, n);
        Arrays.sort(w);
        double cpuMean = Arrays.stream(c).average().orElse(0);
        say("%s: %d warm-up calls, %d measured in %.1f s", name, warm, n, took / 1e9);
        say("  wall ms: mean %.4f  p50 %.4f  p99 %.4f  max %.4f", ms(took) / n, ms(w[n / 2]), ms(w[Math.min(n - 1, (int) (n * 0.99))]), ms(w[n - 1]));
        long own = Arrays.stream(c).sum();
        say("  thread CPU ms: mean %.4f; process CPU ms per call %.4f: other Java threads %.4f, VM threads (GC, JIT, JFR) %.4f", cpuMean / 1e6, ms(process) / n, ms(javaAll - own) / n,
                ms(process - javaAll) / n);
        say("  allocated per call: %.1f kB; GC %d collections, %d ms in %.1f s; heap peak %.1f MB (Xmx %.0f MB)", alloc / 1024.0 / n, gcCount, gcTime, took / 1e9, peak / 1048576.0,
                Runtime.getRuntime().maxMemory() / 1048576.0);
        return n;
    }

    // ---- Heap ----

    static long used() throws InterruptedException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(60);
            best = Math.min(best, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        }
        return best;
    }

    /** The heap {@code make}'s result holds that nothing held before: used after a full GC, with and without it. */
    static <T> T retained(String what, Supplier<T> make) throws InterruptedException {
        long before = used();
        T kept = make.get();
        long after = used();
        say("  %-58s %9.1f kB", what, (after - before) / 1024.0);
        return kept;
    }

    // ---- The screen, as ProcessSimulatorScreen.render draws it ----

    /**
     * Counts what a frame sends the GPU, as GuiCanvas and DrawCallTest count it (1.21.1 {@code GuiGraphics}, {@link HeldDraws}): outside a
     * batch every fill, text and sprite is one draw; inside, fills go out in runs while texts and sprites wait until a fill or text would
     * cover them, a clip changes or the batch ends. Quads: a fill, a glyph or a sprite each.
     */
    static final class Counting implements Canvas {
        /** The draw calls as GuiCanvas makes them, by kind: a batch holds its texts and sprites (P5). */
        final HeldDraws held = new HeldDraws();
        long fills, glyphs, sprites, texts;

        @Override public Measure measure() {
            return M;
        }

        @Override public void batch(Runnable body) {
            held.begin(() -> {
            });
            try {
                body.run();
            } finally {
                held.end();
            }
        }

        @Override public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills++;
            held.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        }

        @Override public void text(String text, int x, int y, int argb) {
            text(text, x, y, 1, argb);
        }

        @Override public void text(String text, float x, float y, float scale, int argb) {
            texts++;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) != ' ') glyphs++;
            held.text(x, y, x + M.width(text) * scale, y + M.height() * scale, () -> {
            });
        }

        @Override public void sprite(Sprite sprite, float x0, float y0, float x1, float y1, int argb) {
            sprites++;
            held.sprite(x0, y0, x1, y1, () -> {
            });
        }

        @Override public void clip(Rect area) {
            held.clip();
        }

        @Override public void unclip() {
            held.clip();
        }
    }

    /** A page or a door as a player leaves it open: the state the screen keeps between frames. */
    static final class Screen {
        final SheetEditor editor;
        final Host host;
        final SheetView view = new SheetView();
        final SheetGestures gestures;
        final KitDoor kit = new KitDoor();
        final ResultsPage.State results = new ResultsPage.State();
        final SizingPage.State sizing = new SizingPage.State();
        final SimulatorFrame frame = new SimulatorFrame(W, H);
        SimulatorFrame.Tab tab = SimulatorFrame.Tab.FLOWSHEET;
        SheetGestures.Selection door;
        final Door.View doorView = new Door.View();
        boolean open;
        List<FileColumns.Entry> files = List.of();
        SheetState stated;
        Flowsheet statedSheet;
        SimulatorHost.Finished statedRun;
        int category = 2;
        double mouseX = W / 2.0, mouseY = H / 2.0;

        Screen(Solved solved) {
            editor = new SheetEditor(solved.sheet(), M, SimulatorBench::columnDefaults);
            host = new Host(solved.finished(), solved.rated());
            gestures = new SheetGestures(editor, view, s -> {});
            view.layout(SimulatorFrame.inner(frame.well()));
            view.fit(editor.layout().bounds());
        }

        /** The screen's context(): the state is worked out again only when the sheet or the run changed. */
        SheetContext context() {
            host.lock(editor);
            var last = host.last();
            if (stated == null || statedSheet != editor.sheet() || statedRun != last) {
                statedSheet = editor.sheet();
                statedRun = last;
                stated = SheetState.of(statedSheet, last);
            }
            return new SheetContext(editor, stated.running(host.running()), UNITS, T, M, FLUIDS, host, List.of());
        }

        Door shownDoor(SheetContext x) {
            if (door == null) return null;
            if (door.unit()) return editor.unit(door.id()).type().equals("column") ? ColumnDoors.door(x, door.id(), doorView) : com.wormzjl.icheme.client.gui.flowsheet.UnitDoors.door(x, door.id(), doorView);
            return StreamDoors.door(x, door.id(), doorView);
        }

        /** The screen's signature(x, shown): what it compares each frame to know whether its widgets stand, on the frame's own door (P6). */
        String signature(SheetContext x, Door shown) {
            var text = new StringBuilder().append(tab).append(open).append(door).append(doorView.tab).append(doorView.connection).append(doorView.index).append(doorView.phase).append(doorView.results).append(doorView.metric);
            text.append(gestures.selected()).append(category).append(results.view).append(true).append(editor.canUndo()).append(editor.canRedo()).append(SimulatorPage.runDue(x, host));
            text.append(x.units());
            if (shown != null) for (var rows : shown.columns()) text.append(rows.size()).append(SimulatorPage.keys(rows));
            if (shown != null) text.append(shown.glass().slots(new Rect(0, 0, 400, 400)).stream().map(Door.Slot::key).toList());
            if (tab == SimulatorFrame.Tab.SIZING) {
                text.append(sizing.view).append(sizing.unit).append(sizing.line).append(sizing.path).append(SimulatorPage.keys(SizingPage.rows(x, sizing)));
                text.append(System.identityHashCode(SizingPage.sized(x))).append(SizingPage.open(x)).append(SizingPage.sizes(x));
            }
            return text.toString();
        }

        /** The screen's init for what is shown: the kit laid out once, as rebuildWidgets does. */
        void init() {
            var x = context();
            var shown = shownDoor(x);
            if (open) {
                files = FileColumns.entries(fifty(editor.sheet()), FlowsheetExamples.read(ClassLoader::getSystemResourceAsStream), T);
            } else if (shown != null) {
                kit.layout(DoorFrame.areas(shown, DW, DH), DoorFrame.bar(shown, DW), DoorFrame.columns(shown, DW), M);
            } else if (tab == SimulatorFrame.Tab.FLOWSHEET && gestures.selected() != null) {
                var r = SimulatorFrame.panel(SimulatorFrame.inner(frame.well()));
                kit.layout(new Rect(r.x() + 7, r.y() + 5, r.w() - 18, r.h() - 10), SelectionColumn.of(x, gestures.selected()).rows(), M);
            } else if (tab == SimulatorFrame.Tab.RESULTS) {
                kit.layout(new Rect(10, SimulatorFrame.DOOR_TOP, SimulatorFrame.RESULT_DOOR - 10, H - SimulatorFrame.BOTTOM - SimulatorFrame.DOOR_TOP), ResultsPage.rows(x, results), M);
            } else if (tab == SimulatorFrame.Tab.SIZING) {
                kit.layout(new Rect(10, SimulatorFrame.DOOR_TOP, SimulatorFrame.SIZING_DOOR - 10, H - SimulatorFrame.BOTTOM - SimulatorFrame.DOOR_TOP), SizingPage.rows(x, sizing), M);
            }
        }

        /** One frame: render()'s own work, then the widgets super.render draws, through the same skin calls, in one batch as the screen draws it (P5). */
        void frame(Counting c) {
            c.batch(() -> draw(c));
        }

        private void draw(Counting c) {
            var x = context();
            var shown = shownDoor(x);
            signature(x, shown);
            if (open) {
                FileColumns.draw(c, SKIN, SimulatorPage.listing("OPEN", x, files, List.of(), List.of(), 0), FileColumns.W, FileColumns.H);
                return;
            }
            if (shown != null) {
                var box = DoorFrame.back(c, SKIN, shown, DW, DH);
                kit.placeAll(DoorFrame.columns(shown, DW), x.m());
                kit.draw(c, SKIN, mouseX, mouseY);
                SKIN.iconKey(c, com.wormzjl.icheme.client.gui.common.CalculatorColumn.theme(DW), Skin.Icon.THEME, false);
                var tabs = DoorFrame.tabs(shown, DW);
                for (int i = 0; i < shown.tabs().size(); i++) SKIN.button(c, tabs.get(i), shown.tabs().get(i).text(), SimulatorPage.press(shown.tabs().get(i)), false);
                var footer = DoorFrame.footer(shown, DW, DH, M);
                SKIN.button(c, footer.getFirst(), T.get("fluid.close"), Skin.Press.REST, false);
                for (int i = 0; i < shown.footer().size(); i++) SKIN.button(c, footer.get(i + 1), shown.footer().get(i).text(), SimulatorPage.press(shown.footer().get(i)), false);
                if (!shown.calculator()) {
                    var soft = SimulatorPage.softKeys(UNITS, T);
                    for (int i = 0; i < 4; i++) SKIN.softKey(c, footer.get(footer.size() - 4 + i), soft.get(i).text(), false);
                }
                for (var slot : shown.glass().slots(box)) {
                    if (slot.button()) SKIN.button(c, slot.rect(), slot.text(), slot.latched() ? Skin.Press.CURRENT : Skin.Press.REST, false);
                    else SKIN.field(c, slot.rect(), slot.text(), slot.hint() == null ? "" : slot.hint(), false);
                }
                widgets(c, shown.values());
                return;
            }
            SimulatorPage.draw(c, SKIN, x, tab, frame, view, gestures, results, sizing, category, editor, kit, "Process simulator", null, mouseX, mouseY);
            for (var slot : SimulatorPage.frameKeys(x, tab, frame, host, editor)) SKIN.button(c, slot.rect(), slot.button().text(), SimulatorPage.press(slot.button()), false);
            SKIN.iconKey(c, frame.theme(), Skin.Icon.THEME, false);
            if (tab == SimulatorFrame.Tab.FLOWSHEET) {
                for (var slot : SimulatorPage.canvasKeys(x, frame, SimulatorFrame.inner(frame.well()), category)) SKIN.button(c, slot.rect(), slot.button().text(), SimulatorPage.press(slot.button()), false);
                if (gestures.selected() != null) widgets(c, SelectionColumn.of(x, gestures.selected()).values());
            } else if (tab == SimulatorFrame.Tab.SIZING) widgets(c, SizingPage.values(x, sizing));
            else widgets(c, Map.of());
        }

        /** The kit's fields and buttons in view, as their widgets draw. */
        void widgets(Counting c, Map<String, String> values) {
            var port = kit.view().viewport();
            int offset = kit.view().offset();
            for (var column : kit.columns())
                for (var p : column.rows()) {
                    if (p.rect().y() - offset > port.bottom() || p.rect().bottom() - offset < port.y()) continue;
                    if (p.row() instanceof Column.Field f) SKIN.field(c, p.slots().get(f.key()), values.getOrDefault(f.key(), ""), "", false);
                    else if (p.row() instanceof Column.Wide wide) SKIN.field(c, p.slots().get(wide.key()), values.getOrDefault(wide.key(), ""), "", false);
                    else if (p.row() instanceof Column.Buttons b) for (var spec : b.buttons()) SKIN.button(c, p.slots().get(spec.key()), spec.text(), SimulatorPage.press(spec), false);
                    else if (p.row() instanceof Column.Setting s && s.steps()) for (var spec : List.of(s.minus(), s.plus())) SKIN.button(c, p.slots().get(spec.key()), spec.text(), Skin.Press.REST, false);
                }
        }
    }

    static Screen page(Solved solved, String page) {
        var s = new Screen(solved);
        switch (page) {
            case "idle" -> {
            }
            case "selected" -> s.gestures.select(new SheetGestures.Selection(true, "T-101"));
            case "feed" -> s.door = new SheetGestures.Selection(false, "CRUDE");
            case "column" -> s.door = new SheetGestures.Selection(true, "T-101");
            case "column-results" -> {
                s.door = new SheetGestures.Selection(true, "T-101");
                s.doorView.tab = 2;
            }
            case "results" -> s.tab = SimulatorFrame.Tab.RESULTS;
            case "sizing" -> s.tab = SimulatorFrame.Tab.SIZING;
            case "open" -> s.open = true;
            default -> throw new IllegalArgumentException("page " + page);
        }
        s.init();
        return s;
    }

    // ---- The phases ----

    static void runPhase(boolean warmStart) throws Exception {
        var sheet = crudeUnit();
        var first = new Units();
        long w = System.nanoTime(), c = THREADS.getCurrentThreadCpuTime(), a = THREADS.getCurrentThreadAllocatedBytes();
        var steady = run(sheet, null, first);
        say("first Run in this JVM: %.0f ms wall, %.0f ms CPU, %.1f MB allocated, %s, %d passes", ms(System.nanoTime() - w), ms(THREADS.getCurrentThreadCpuTime() - c),
                (THREADS.getCurrentThreadAllocatedBytes() - a) / 1048576.0, steady.status(), first.passes);
        var units = new Units();
        int[] statuses = new int[FlowsheetRun.Status.values().length];
        timed(warmStart ? "run-warm" : "run", () -> {
            var u = new Units();
            var r = run(sheet, warmStart ? steady : null, u);
            statuses[r.status().ordinal()]++;
            u.spent.forEach((k, v) -> {
                var row = units.spent.computeIfAbsent(k, kk -> new long[4]);
                for (int i = 0; i < 4; i++) row[i] += v[i];
            });
            units.passes = u.passes;
            units.checkpoints += u.checkpoints;
        });
        say("  statuses %s; passes %d", Arrays.toString(statuses), units.passes);
        say("  by unit, per run (warm-up and measurement together, %d runs counted):", Arrays.stream(statuses).sum());
        int runs = Math.max(1, Arrays.stream(statuses).sum());
        long total = units.spent.values().stream().mapToLong(v -> v[0]).sum();
        units.spent.entrySet().stream().sorted((p, q) -> Long.compare(q.getValue()[0], p.getValue()[0])).forEach(e -> say("    %-30s wall %8.1f ms (%4.1f %%)  CPU %8.1f ms  calcs %5.1f  checkpoints %8.0f", e.getKey(),
                ms(e.getValue()[0]) / runs, 100.0 * e.getValue()[0] / total, ms(e.getValue()[1]) / runs, e.getValue()[2] / (double) runs, e.getValue()[3] / (double) runs));
        say("  checkpoints per run: %.0f (each flash and each solver step calls it: the flash-count proxy)", units.checkpoints / (double) runs);
    }

    static void sizePhase() throws Exception {
        var sheet = crudeUnit();
        var run = run(sheet, null, new Units());
        timed("size", () -> MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(sheet, run, thermo(), FlowsheetSolver.Progress.NONE)));
        var solved = solved();
        timed("rate", () -> MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(solved.sheet(), solved.run(), thermo())));
    }

    /** One stage's sums: thread CPU, wall, bytes allocated, checkpoints. */
    static final class Stage {
        long cpu, wall, alloc, checkpoints;
        int n;
    }

    static <T> T stage(Map<String, Stage> stages, String name, long[] checkpoints, Supplier<T> body) {
        long c = THREADS.getCurrentThreadCpuTime(), w = System.nanoTime(), a = THREADS.getCurrentThreadAllocatedBytes(), k = checkpoints[0];
        T out = body.get();
        var s = stages.computeIfAbsent(name, x -> new Stage());
        s.cpu += THREADS.getCurrentThreadCpuTime() - c;
        s.wall += System.nanoTime() - w;
        s.alloc += THREADS.getCurrentThreadAllocatedBytes() - a;
        s.checkpoints += checkpoints[0] - k;
        s.n++;
        return out;
    }

    /**
     * Size all split as {@code SheetSizing.sizeAll} runs it: the proposal pass (a column on Auto tries its diameters), the applied
     * copy, the re-run warm from the run where the sizes move it, and the rating. Counters by stage; -Drounds measured rounds after two.
     */
    static void sizeStagesPhase() throws Exception {
        var sheet = crudeUnit();
        var run = run(sheet, null, new Units());
        var moves = SheetSizing.class.getDeclaredMethod("moves", Flowsheet.class, Flowsheet.class);
        moves.setAccessible(true);
        long[] checkpoints = {0};
        var counted = MaterialRuntime.with(CATALOG, NETWORK, () -> new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> checkpoints[0]++));
        int rounds = Integer.getInteger("rounds", 3);
        var stages = new LinkedHashMap<String, Stage>();
        for (int round = 0; round < rounds + 2; round++) {
            if (round == 2) stages.clear();
            var units = new Units[1];
            int at = round;
            MaterialRuntime.with(CATALOG, NETWORK, () -> {
                var proposal = stage(stages, "proposal pass (column trials)", checkpoints, () -> SheetSizing.pass(sheet, run, counted, SheetSizing.Entries.of(sheet, false)));
                var sized = stage(stages, "applied copy", checkpoints, () -> SheetSizing.applied(sheet, proposal));
                boolean moved;
                try {
                    moved = (boolean) moves.invoke(null, sheet, sized);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                var rerun = !moved ? run : stage(stages, "re-run, warm from the run", checkpoints, () -> {
                    units[0] = new Units();
                    return FlowsheetEngine.run(sized, CATALOG, () -> {
                        checkpoints[0]++;
                        units[0].checkpoint();
                    }, run, units[0]);
                });
                stage(stages, "rating", checkpoints, () -> SheetSizing.rate(sized, rerun, counted));
                if (units[0] != null) {
                    units[0].close();
                    if (at >= 2) say("round %d: re-run %s in %d passes; by unit: %s", at - 1,rerun.status(), units[0].passes, units[0].spent.entrySet().stream().filter(e -> e.getValue()[0] > 5_000_000)
                            .map(e -> String.format(Locale.ROOT, "%s %.0f ms, %d calcs, %d checkpoints", e.getKey(), ms(e.getValue()[0]), e.getValue()[2], e.getValue()[3])).toList());
                }
                return null;
            });
        }
        say("Size all by stage, mean of %d rounds after 2 (CPU under the machine's load, see machine.txt):", rounds);
        long cpu = stages.values().stream().mapToLong(s -> s.cpu).sum();
        stages.forEach((k, s) -> say("  %-32s %d of %d rounds; CPU %8.1f ms (%4.1f %%), wall %8.1f ms, allocated %8.1f MB, checkpoints %8.0f", k, s.n, rounds, ms(s.cpu) / rounds,
                100.0 * s.cpu / cpu, ms(s.wall) / rounds, s.alloc / 1048576.0 / rounds, s.checkpoints / (double) rounds));
    }

    static void framePhase(String page) throws Exception {
        var screen = page(solved(), page);
        var one = new Counting();
        screen.frame(one);
        say("frame %s: %d draw calls (%d fill runs, %d text runs, %d sprites), %d fills, %d texts (%d glyphs), %d sprites; %d quads, %d vertices", page, one.held.draws(), one.held.fillDraws(), one.held.textDraws(),
                one.held.spriteDraws(), one.fills, one.texts, one.glyphs, one.sprites, one.fills + one.glyphs + one.sprites,
                4 * (one.fills + one.glyphs + one.sprites));
        var c = new Counting();
        timed("frame-" + page, () -> {
            screen.frame(c);
        });
    }

    static void editPhase(String op) throws Exception {
        var solved = solved();
        var screen = new Screen(solved);
        var editor = screen.editor;
        var pump = editor.unit("P-101");
        var crude = editor.stream("CRUDE");
        int[] i = {0};
        switch (op) {
            // A drag's step: one coalesced undo step, then the next frame's state and layout.
            case "move" -> timed("edit-move", () -> {
                editor.moveUnit("P-101", pump.x() + (i[0]++ % 2) * 8, pump.y(), "drag");
                screen.context();
                editor.layout();
            });
            // A feed entry committed: a new undo step each time, then the next frame's state and layout.
            case "feed" -> timed("edit-feed", () -> {
                var f = crude.feed();
                editor.setFeed("CRUDE", new Flowsheet.Feed(f.temperature() + (i[0]++ % 2), f.pressure(), f.amounts(), f.solids()), null);
                screen.context();
                editor.layout();
            });
            // Its parts alone, on the sheet as it stands.
            case "state" -> timed("edit-state", () -> SheetState.of(editor.sheet(), solved.finished()));
            case "layout" -> timed("edit-layout", () -> com.wormzjl.icheme.client.gui.flowsheet.SheetLayout.of(editor.sheet(), M));
            // The autosave the screen writes every AUTOSAVE_TICKS after an edit.
            case "save" -> {
                var file = OUT.resolve("autosave.json");
                timed("edit-save", () -> FlowsheetFile.save(file, editor.sheet()));
            }
            default -> throw new IllegalArgumentException("edit " + op);
        }
    }

    static void ramPhase() throws Exception {
        var solved = solved();
        var bytes = Files.readAllBytes(OUT.resolve("copy.json"));
        say("retained heap (used after a full GC, with and without):");
        retained("the catalog, bundled (already held: a second copy)", MaterialCatalog::bundled);
        retained("network thermo, FluidThermodynamics.forNetwork (one a run)", () -> MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK)));
        retained("the crude unit sheet, read from its file", () -> {
            try {
                return FlowsheetFile.read(bytes);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        var editor = retained("editor on its own sheet copy: layout, roles, wiring", () -> {
            var e = new SheetEditor(fresh(solved.sheet()), M, SimulatorBench::columnDefaults);
            e.layout();
            e.roles();
            e.wiring(null);
            return e;
        });
        var run = retained("FlowsheetRun of a cold Run (crude unit)", () -> run(fresh(solved.sheet()), null, new Units()));
        retained("SheetState.of the sized sheet against its run (SheetResults, greyed)", () -> SheetState.of(solved.sheet(), solved.finished()));
        retained("SheetSizing.Sized, a rating of the sized sheet", () -> MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.rate(solved.sheet(), solved.run(), thermo())));
        var screen = page(solved, "results");
        screen.context();
        retained("props caches: a Results frame, then a Flowsheet frame, fill SheetState.props", () -> {
            screen.frame(new Counting());
            screen.tab = SimulatorFrame.Tab.FLOWSHEET;
            screen.frame(new Counting());
            return screen;
        });
        var crude = editor.stream("CRUDE");
        retained("undo history: 100 feed entries, each its own step", () -> {
            for (int i = 0; i < 100; i++) {
                var f = crude.feed();
                editor.setFeed("CRUDE", new Flowsheet.Feed(f.temperature() + i + 1, f.pressure(), f.amounts(), f.solids()), null);
            }
            return editor;
        });
        var pump = editor.unit("P-101");
        retained("undo history: 100 unit moves, each its own step", () -> {
            for (int i = 0; i < 100; i++) editor.moveUnit("P-101", pump.x() + 8 * (i % 2 + 1), pump.y(), null);
            return editor;
        });
        retained("undo history: 100 column door entries (reflux), each its own step", () -> {
            for (int i = 0; i < 100; i++) if (!editor.set("T-101", "reflux", 1.0 + i / 100.0, null)) throw new IllegalStateException("reflux refused");
            return editor;
        });
        say("  kept: %s %s %s", editor.canUndo(), run.status(), screen.tab);
    }

    /** A flowsheets folder holding the crude unit 50 times. */
    static Path fifty(Flowsheet sheet) {
        try {
            var folder = OUT.resolve("open-50");
            Files.createDirectories(folder);
            try (var old = Files.list(folder)) {
                for (var f : old.toList()) Files.delete(f);
            }
            for (int i = 0; i < 50; i++) FlowsheetFile.save(FileColumns.file(folder, String.format(Locale.ROOT, "Crude unit %02d", i)), sheet);
            return folder;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The heap's classes after a full GC: bytes and instances by class name, from the VM's own class histogram. */
    static Map<String, long[]> histogram() throws Exception {
        var text = (String) ManagementFactory.getPlatformMBeanServer().invoke(new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[]{new String[0]}, new String[]{String[].class.getName()});
        var classes = new LinkedHashMap<String, long[]>();
        for (var line : text.split("\n")) {
            var parts = line.trim().split("\\s+");
            if (parts.length < 4 || !parts[0].endsWith(":")) continue;
            classes.put(parts[3], new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
        }
        return classes;
    }

    /** What one cold Run's result holds, by class: the class histogram with and without it. */
    static void histoPhase() throws Exception {
        var sheet = crudeUnit();
        run(fresh(sheet), null, new Units());
        var before = histogram();
        var held = run(fresh(sheet), null, new Units());
        var after = histogram();
        var grown = new ArrayList<Map.Entry<String, long[]>>();
        after.forEach((k, v) -> {
            var was = before.getOrDefault(k, new long[2]);
            grown.add(Map.entry(k, new long[]{v[0] - was[0], v[1] - was[1]}));
        });
        grown.sort((p, q) -> Long.compare(q.getValue()[1], p.getValue()[1]));
        say("a held FlowsheetRun (%s), the classes it adds, by bytes:", held.status());
        grown.stream().limit(25).forEach(e -> say("  %10.1f kB %9d  %s", e.getValue()[1] / 1024.0, e.getValue()[0], e.getKey()));
        say("  reports %d, streams %d, outcomes %d, log %d", held.reports().size(), held.streams().size(), held.outcomes().size(), held.log().size());
        // What stays when nothing is held: Runs on feeds 1 K apart, each result dropped, as a player's edits make new column inputs.
        var crude = sheet.streams().stream().filter(s -> s.id().equals("CRUDE")).findFirst().orElseThrow();
        long base = used();
        var start = histogram();
        int runs = Integer.getInteger("runs", 4);
        for (int i = 1; i <= runs; i++) {
            var editor = new SheetEditor(fresh(sheet), M, SimulatorBench::columnDefaults);
            var f = crude.feed();
            editor.setFeed("CRUDE", new Flowsheet.Feed(f.temperature() + i, f.pressure(), f.amounts(), f.solids()), null);
            // On a thread of its own, as SimulatorRunner starts one a job (-Dsame for the bench's own thread).
            if (Boolean.getBoolean("same")) run(editor.sheet(), null, new Units());
            else {
                var job = new Thread(() -> run(editor.sheet(), null, new Units()), SimulatorRunner.THREAD);
                job.start();
                job.join();
            }
            say("  after %d Runs on new feeds, results dropped: heap +%.1f kB", i, (used() - base) / 1024.0);
        }
        var end = histogram();
        var kept = new ArrayList<Map.Entry<String, long[]>>();
        end.forEach((k, v) -> {
            var was = start.getOrDefault(k, new long[2]);
            kept.add(Map.entry(k, new long[]{v[0] - was[0], v[1] - was[1]}));
        });
        kept.sort((p, q) -> Long.compare(q.getValue()[1], p.getValue()[1]));
        say("the classes %d dropped Runs leave, by bytes:", runs);
        kept.stream().limit(15).forEach(e -> say("  %10.1f kB %9d  %s", e.getValue()[1] / 1024.0, e.getValue()[0], e.getKey()));
    }

    static void openPhase() throws Exception {
        var sheet = crudeUnit();
        var folder = fifty(sheet);
        long size = Files.size(FileColumns.file(folder, "Crude unit 00"));
        say("open: 50 files of %d bytes, the crude unit each", size);
        var bytes = Files.readAllBytes(FileColumns.file(folder, "Crude unit 00"));
        timed("open-parse-one", () -> FlowsheetFile.read(bytes));
        timed("open-list-50", () -> FileColumns.entries(folder, FlowsheetExamples.read(ClassLoader::getSystemResourceAsStream), T));
        retained("Open's 50 rows, as the dialog holds them", () -> FileColumns.entries(folder, FlowsheetExamples.read(ClassLoader::getSystemResourceAsStream), T));
    }

    // ---- Results, exactly ----

    /**
     * Every value a cold Run, a warm Run from it and Size all leave, as exact text, to {@code results.txt}: a change that must keep
     * results bit-identical is checked by this file before and after (diff). No timing.
     */
    static void bitsPhase() throws Exception {
        var out = new StringBuilder();
        var sheet = crudeUnit();
        var units = new Units();
        var cold = run(sheet, null, units);
        out.append("cold checkpoints ").append(units.checkpoints).append('\n');
        dump(out, "cold", cold, 0, new java.util.IdentityHashMap<>());
        units = new Units();
        var warm = run(sheet, cold, units);
        out.append("warm checkpoints ").append(units.checkpoints).append('\n');
        dump(out, "warm", warm, 0, new java.util.IdentityHashMap<>());
        var all = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetSizing.sizeAll(sheet, cold, thermo(), FlowsheetSolver.Progress.NONE));
        out.append("size sheet ").append(new String(FlowsheetFile.write(all.sheet()), StandardCharsets.UTF_8)).append('\n');
        dump(out, "size", all.run(), 0, new java.util.IdentityHashMap<>());
        Files.writeString(OUT.resolve("results.txt"), out);
        say("bits: %d lines, cold %s, warm %s, size %s", out.chars().filter(c -> c == '\n').count(), cold.status(), warm.status(), all.run().status());
    }

    /** {@code value}'s fields, depth first: doubles by their exact text, maps by sorted key, timings left out. */
    static void dump(StringBuilder out, String path, Object value, int depth, java.util.IdentityHashMap<Object, Boolean> seen) throws Exception {
        if (value == null || value instanceof Number || value instanceof CharSequence || value instanceof Boolean || value instanceof Enum<?> || value instanceof Character) {
            out.append(path).append(" = ").append(value).append('\n');
            return;
        }
        if (depth > 12 || seen.put(value, Boolean.TRUE) != null) return;
        var type = value.getClass();
        if (type.isArray()) {
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) dump(out, path + "[" + i + "]", java.lang.reflect.Array.get(value, i), depth + 1, seen);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            var keys = new java.util.ArrayList<String>();
            var byKey = new java.util.HashMap<String, Object>();
            for (var e : map.entrySet()) {
                keys.add(String.valueOf(e.getKey()));
                byKey.put(String.valueOf(e.getKey()), e.getValue());
            }
            java.util.Collections.sort(keys);
            for (var k : keys) dump(out, path + "{" + k + "}", byKey.get(k), depth + 1, seen);
            return;
        }
        if (value instanceof Iterable<?> list) {
            int i = 0;
            for (var item : list) dump(out, path + "[" + i++ + "]", item, depth + 1, seen);
            return;
        }
        if (!type.getName().startsWith("com.wormzjl.")) {
            out.append(path).append(" : ").append(type.getName()).append('\n');
            return;
        }
        for (var t = type; t != null && t != Object.class; t = t.getSuperclass())
            for (var f : t.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getName().equals("seconds")) continue;
                f.setAccessible(true);
                dump(out, path + "." + f.getName(), f.get(value), depth + 1, seen);
            }
    }

    public static void main(String[] args) throws Exception {
        String phase = args[0];
        OUT = Path.of(args[1]);
        Files.createDirectories(OUT);
        long w = System.nanoTime();
        CATALOG = MaterialCatalog.bundled();
        NETWORK = CATALOG.presets().network().packageId();
        var model = MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK));
        FLUIDS = new StreamProps.Fluids(model, CATALOG.requirePackage(NETWORK), id -> CATALOG.name(id).english(), "Crude oil, methane, nitrogen");
        fresh(crudeUnit());
        var version = Class.forName("com.wormzjl.icheme.science.column.v3.NativeColumnSolver").getDeclaredMethod("version");
        version.setAccessible(true);
        say("phase %s, JDK %s, %d cores, Xmx %.0f MB, warm %.0f s, measure %.0f s; catalog and thermo %.0f ms; column library %s", phase, System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() / 1048576.0, WARM, MEASURE, ms(System.nanoTime() - w), version.invoke(null));
        String[] p = phase.split(":", 2);
        switch (p[0]) {
            case "run" -> runPhase(false);
            case "run-warm" -> runPhase(true);
            case "size" -> sizePhase();
            case "size-stages" -> sizeStagesPhase();
            case "frame" -> framePhase(p[1]);
            case "edit" -> editPhase(p[1]);
            case "ram" -> ramPhase();
            case "open" -> openPhase();
            case "histo" -> histoPhase();
            case "bits" -> bitsPhase();
            default -> throw new IllegalArgumentException("phase " + phase);
        }
        Files.writeString(OUT.resolve(phase.replace(':', '-') + ".txt"), REPORT);
    }
}
