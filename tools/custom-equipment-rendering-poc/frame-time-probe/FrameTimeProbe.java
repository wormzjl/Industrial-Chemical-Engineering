package com.wormzjl.icheme.mcpcompat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.wormzjl.icheme.client.fx.FxStats;
import com.wormzjl.icheme.network.FluidNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Measurement only (batch 2026-10-15-custom-equipment-rendering, extended for batch 2026-11-02-process-simulator); detach to
 * tools/custom-equipment-rendering-poc/frame-time-probe/ before a batch merges.
 *
 * <p>{@code /fpsprobe <warm-up s> <measurement s> <label>} (client command) waits the warm-up, records every frame of the
 * measurement window and logs one {@code FPS_PROBE} line: frames, mean FPS, frame-interval percentiles, GPU time of the frame
 * and of the open screen, the screen's CPU time, and the machine's load (system CPU, the top CPU users of any process).
 *
 * <p>GPU time: a ring of {@link #RING} frames, each with four {@code GL_TIMESTAMP} counters (frame start, screen start, screen
 * end, frame end; {@code GL_TIME_ELAPSED} queries cannot nest), read {@link #RING} frames later, never waiting on the GPU. The
 * screen's end is taken after a flush of its {@code GuiGraphics}, so its batched fills are inside.
 *
 * <p>Autopilot: when {@code <game dir>/fps-autopilot/autopilot.properties} exists. {@code mode=columns} is the 2026-10-15 lane
 * (open {@code save}, {@code camera.txt}, {@code columns.txt}, {@code empty.txt}). {@code mode=simulator} creates a fresh flat
 * world named {@code save} (refused if it exists), places a process simulator, measures the world with no screen, opens the
 * block, loads the bundled crude unit, runs it to steady, then measures idle, panning, T-101's door and the Results tab, and quits.
 * {@code mode=lod} (batch 2026-11-09-lod-rendering) creates a fresh flat world the same way, runs {@code scene.txt}, puts a
 * pressure gauge on each pipe of the {@code grid.*} grid through the server's own install request, then measures
 * {@code view.txt} and {@code away.txt}, and quits. {@code mode=fx} (batch 2026-11-10-custom-shader-system,
 * FX_BENCH_PLAN.md; lane in tools/fx-bench/) creates a fresh flat world, runs {@code scene.txt} (the effects scene in a
 * snowy biome), puts every gauge and a level meter on the {@code drums.*} grid's drums, feeds the effects' client stores
 * synthetic full-load states ({@link #feed}), measures each window of {@code windows} (60 s + 60 s each), times the server's
 * new presentation publications ({@link #serverTiming}), logs the effects' memory, and quits.
 *
 * <p>Every window also logs one {@code FX_PROBE} line: the effects' render-thread time per frame from {@code FxStats}, the
 * CPU and GPU time of the stages the effects and the wiring draw at, {@code AFTER_PARTICLES} and {@code AFTER_WEATHER}
 * (the effects since 0d562f7b0, {@code FxBatch.stage()}), each timed apart and summed, so vanilla's clouds and weather
 * between them stay out (GL_TIMESTAMP counters from this probe's own first and last handlers of each stage, so
 * {@code src/main} has no timer code), quads, emitters, rebuilds, the sound tick and its loops; with
 * {@code jfr=true}, a JFR recording of the window and one {@code FX_JFR} line (allocation and render-thread CPU samples
 * whose stack holds {@code client.fx} or the instrument renderer).
 */
@EventBusSubscriber(modid = "icheme_mcp_compat", value = Dist.CLIENT)
public final class FrameTimeProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SIM = "com.wormzjl.icheme.client.gui.screens.inventory.ProcessSimulatorScreen";
    private static final String FLOWSHEET = "com.wormzjl.icheme.client.gui.flowsheet.";
    private static final BlockPos BLOCK = new BlockPos(3, -60, 0);
    private static final int RING = 8;
    private static final long[] SAMPLES = new long[1 << 21], GPU_FRAME = new long[1 << 21], GPU_SCREEN = new long[1 << 21], CPU_SCREEN = new long[1 << 21];
    private static boolean active;
    private static long warmupEnds, measureEnds, last, screenStarted;
    private static int count, gpuFrames, gpuScreens, cpuScreens, gpuMissed;
    private static String label = "";
    // GPU ring (render thread only): per slot frame start, screen start, screen end, frame end, then each timed stage's start
    // and end (AFTER_PARTICLES, AFTER_WEATHER).
    private static final RenderLevelStageEvent.Stage[] TIMED = {RenderLevelStageEvent.Stage.AFTER_PARTICLES, RenderLevelStageEvent.Stage.AFTER_WEATHER};
    private static final int COUNTERS = 4 + 2 * TIMED.length;
    private static int[] queries;
    private static final boolean[] PENDING = new boolean[RING], SCREEN = new boolean[RING], WINDOW = new boolean[RING];
    /** Per timed stage and slot: the stage ran in that slot's frame. */
    private static final boolean[][] STAGE = new boolean[TIMED.length][RING];
    private static int slot;
    private static boolean screenInFrame;
    private static final boolean[] stageInFrame = new boolean[TIMED.length];
    private static final long[] stageOpened = new long[TIMED.length];
    /** This frame's CPU time in the timed stages, summed. */
    private static long stageCpuFrame;
    // The effects per frame of the window (batch 2026-11-10-custom-shader-system): FxStats, the timed stages, the sound tick.
    private static final int FX_SAMPLES = 1 << 18;
    private static final long[] FX_DRAW = new long[FX_SAMPLES], FX_BUILD = new long[FX_SAMPLES], FX_WIRING = new long[FX_SAMPLES], FX_CPU = new long[FX_SAMPLES],
            STAGE_CPU = new long[FX_SAMPLES], GPU_STAGE = new long[FX_SAMPLES], SOUND = new long[FX_SAMPLES];
    private static int fxFrames, stageFrames, gpuStages, soundTicks;
    private static long quadSum, quadMax, emitterSum, loopSum, loopMax, soundSourceSum, soundGroupSum;
    private static long rebuildsBefore, wiringBuildsBefore, framesBefore;
    // The machine's load over the window.
    private static CompletableFuture<Map<Long, Object[]>> loadBefore;
    private static double cpuLoadSum;
    private static int cpuLoadSamples;
    // Autopilot state (render thread only).
    private static Properties pilot;
    private static int stage;
    private static long stageStarted, waitUntil;
    private static boolean shot, panning;
    private static int panFrame;
    private static final ArrayDeque<String> COMMANDS = new ArrayDeque<>();

    private FrameTimeProbe() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("fpsprobe")
                .then(Commands.argument("warmup", IntegerArgumentType.integer(0, 600))
                        .then(Commands.argument("measure", IntegerArgumentType.integer(1, 600))
                                .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                                    start(StringArgumentType.getString(context, "label"), IntegerArgumentType.getInteger(context, "warmup"),
                                            IntegerArgumentType.getInteger(context, "measure"));
                                    context.getSource().sendSystemMessage(Component.literal("fpsprobe started: " + label));
                                    return 1;
                                })))));
    }

    private static void start(String name, int warmupSeconds, int measureSeconds) {
        long now = System.nanoTime();
        warmupEnds = now + warmupSeconds * 1_000_000_000L;
        measureEnds = warmupEnds + measureSeconds * 1_000_000_000L;
        label = name;
        count = gpuFrames = gpuScreens = cpuScreens = gpuMissed = 0;
        fxFrames = stageFrames = gpuStages = soundTicks = 0;
        quadSum = quadMax = emitterSum = loopSum = loopMax = soundSourceSum = soundGroupSum = 0;
        rebuildsBefore = wiringBuildsBefore = framesBefore = -1;
        cpuLoadSum = 0;
        cpuLoadSamples = 0;
        loadBefore = null;
        last = 0;
        active = true;
        LOGGER.info("FPS_PROBE start label={}", label);
    }

    private static boolean measuring(long now) {
        return active && now >= warmupEnds && now < measureEnds;
    }

    // ---- GPU timestamps ----

    private static void counter(int index) {
        GL33.glQueryCounter(queries[slot * COUNTERS + index], GL33.GL_TIMESTAMP);
    }

    @SubscribeEvent
    public static void framePre(RenderFrameEvent.Pre event) {
        if (queries == null) {
            queries = new int[RING * COUNTERS];
            GL15.glGenQueries(queries);
            LOGGER.info("FPS_PROBE gpu renderer={} version={}", GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION));
        }
        if (PENDING[slot]) {
            int base = slot * COUNTERS;
            if (GL15.glGetQueryObjecti(queries[base + 3], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                if (WINDOW[slot]) {
                    long t0 = GL33.glGetQueryObjecti64(queries[base], GL15.GL_QUERY_RESULT), t3 = GL33.glGetQueryObjecti64(queries[base + 3], GL15.GL_QUERY_RESULT);
                    if (gpuFrames < GPU_FRAME.length) GPU_FRAME[gpuFrames++] = t3 - t0;
                    if (SCREEN[slot] && gpuScreens < GPU_SCREEN.length)
                        GPU_SCREEN[gpuScreens++] = GL33.glGetQueryObjecti64(queries[base + 2], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(queries[base + 1], GL15.GL_QUERY_RESULT);
                    long stages = 0;
                    boolean any = false;
                    for (int k = 0; k < TIMED.length; k++) {
                        if (!STAGE[k][slot]) continue;
                        any = true;
                        stages += GL33.glGetQueryObjecti64(queries[base + 5 + 2 * k], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(queries[base + 4 + 2 * k], GL15.GL_QUERY_RESULT);
                    }
                    if (any && gpuStages < GPU_STAGE.length) GPU_STAGE[gpuStages++] = stages;
                }
            } else if (WINDOW[slot]) gpuMissed++;
            PENDING[slot] = false;
        }
        WINDOW[slot] = measuring(System.nanoTime());
        SCREEN[slot] = false;
        for (int k = 0; k < TIMED.length; k++) {
            STAGE[k][slot] = false;
            stageInFrame[k] = false;
        }
        stageCpuFrame = 0;
        screenInFrame = false;
        counter(0);
        if (panning) pan(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void screenPre(ScreenEvent.Render.Pre event) {
        if (queries == null || screenInFrame) return;
        screenStarted = System.nanoTime();
        counter(1);
    }

    @SubscribeEvent
    public static void screenPost(ScreenEvent.Render.Post event) {
        if (queries == null || screenInFrame) return;
        event.getGuiGraphics().flush();
        counter(2);
        screenInFrame = true;
        SCREEN[slot] = true;
        long now = System.nanoTime();
        if (measuring(now) && cpuScreens < CPU_SCREEN.length) CPU_SCREEN[cpuScreens++] = now - screenStarted;
    }

    /** A timed stage ({@link #TIMED}): its index, or -1. */
    private static int timed(RenderLevelStageEvent.Stage stage) {
        for (int k = 0; k < TIMED.length; k++) if (TIMED[k] == stage) return k;
        return -1;
    }

    /**
     * A timed stage opens: before every other handler. {@code AFTER_PARTICLES} holds the instrument wiring, and the effects
     * under Fabulous or a shader pack; {@code AFTER_WEATHER} holds the effects otherwise ({@code FxBatch.stage()}).
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void stageFirst(RenderLevelStageEvent event) {
        int k = queries == null ? -1 : timed(event.getStage());
        if (k < 0 || stageInFrame[k]) return;
        stageOpened[k] = System.nanoTime();
        counter(4 + 2 * k);
    }

    /** The stage closes: after every other handler. Its batches are ended by then, so their draws are inside. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stageLast(RenderLevelStageEvent event) {
        int k = queries == null ? -1 : timed(event.getStage());
        if (k < 0 || stageInFrame[k]) return;
        counter(5 + 2 * k);
        stageInFrame[k] = true;
        STAGE[k][slot] = true;
        stageCpuFrame += System.nanoTime() - stageOpened[k];
    }

    /** This frame's effects counters (FxStats), read before the next frame starts them again. */
    private static void sampleFx() {
        if (fxFrames >= FX_SAMPLES) return;
        long draw = FxStats.now(FxStats.Counter.DRAW_NS), wiring = FxStats.now(FxStats.Counter.WIRING_NS);
        FX_DRAW[fxFrames] = draw;
        FX_BUILD[fxFrames] = FxStats.now(FxStats.Counter.BUILD_NS);
        FX_WIRING[fxFrames] = wiring;
        FX_CPU[fxFrames++] = draw + wiring;
        long quads = FxStats.now(FxStats.Counter.QUADS);
        quadSum += quads;
        quadMax = Math.max(quadMax, quads);
        emitterSum += FxStats.now(FxStats.Counter.EMITTERS);
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        boolean staged = false;
        for (boolean k : stageInFrame) staged |= k;
        if (staged && measuring(System.nanoTime()) && stageFrames < STAGE_CPU.length) STAGE_CPU[stageFrames++] = stageCpuFrame;
        if (queries != null) {
            counter(3);
            PENDING[slot] = true;
            slot = (slot + 1) % RING;
        }
        if (!active) return;
        long now = System.nanoTime();
        if (now >= warmupEnds && loadBefore == null) {
            loadBefore = CompletableFuture.supplyAsync(FrameTimeProbe::cpuTimes);
            rebuildsBefore = FxStats.total(FxStats.Counter.REBUILDS);
            wiringBuildsBefore = FxStats.total(FxStats.Counter.WIRING_BUILDS);
            framesBefore = FxStats.frames();
            if (pilot != null && "true".equals(pilot.getProperty("jfr"))) jfrStart();
        }
        if (now >= warmupEnds && last >= warmupEnds && count < SAMPLES.length) {
            SAMPLES[count++] = now - last;
            sampleFx();
        }
        last = now;
        if (now < measureEnds) return;
        active = false;
        fxLine();
        jfrStop(label, count);
        var sorted = Arrays.copyOf(SAMPLES, count);
        Arrays.sort(sorted);
        long total = 0;
        for (long sample : sorted) total += sample;
        double seconds = total / 1e9;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        LOGGER.info("FPS_PROBE label={} frames={} seconds={} meanFps={} p50ms={} p95ms={} p99ms={} maxMs={} gpuFrame[p50/p95ms]={}/{} gpuScreen[p50/p95ms]={}/{} cpuScreen[p50/p95ms]={}/{} gpuFrames={} gpuMissed={} camera={}",
                label, count, String.format("%.2f", seconds), String.format("%.1f", count / seconds),
                ms(sorted, .50), ms(sorted, .95), ms(sorted, .99), String.format("%.2f", count == 0 ? 0 : sorted[count - 1] / 1e6),
                ms(GPU_FRAME, gpuFrames, .50), ms(GPU_FRAME, gpuFrames, .95), ms(GPU_SCREEN, gpuScreens, .50), ms(GPU_SCREEN, gpuScreens, .95),
                ms(CPU_SCREEN, cpuScreens, .50), ms(CPU_SCREEN, cpuScreens, .95), gpuFrames, gpuMissed, camera);
        var before = loadBefore;
        String name = label;
        double cpuLoad = cpuLoadSamples == 0 ? Double.NaN : cpuLoadSum / cpuLoadSamples;
        CompletableFuture.runAsync(() -> LOGGER.info("FPS_LOAD label={} systemCpu={}% top={}", name, String.format("%.0f", cpuLoad * 100), top(before.join(), cpuTimes(), seconds)));
    }

    // ---- The machine's load ----

    /** Every process's CPU time, ns, and its command's file name: read away from the render thread. */
    private static Map<Long, Object[]> cpuTimes() {
        var times = new HashMap<Long, Object[]>();
        ProcessHandle.allProcesses().forEach(p -> {
            var info = p.info();
            info.totalCpuDuration().ifPresent(d -> times.put(p.pid(), new Object[]{d.toNanos(), info.command().map(c -> Path.of(c).getFileName().toString()).orElse("?")}));
        });
        return times;
    }

    /** The five processes that took the most cores over the window, at least 0.05 each. */
    private static String top(Map<Long, Object[]> before, Map<Long, Object[]> after, double seconds) {
        var rows = new ArrayList<Object[]>();
        for (var e : after.entrySet()) {
            var was = before.get(e.getKey());
            if (was == null) continue;
            double cores = ((long) e.getValue()[0] - (long) was[0]) / 1e9 / seconds;
            if (cores >= .05) rows.add(new Object[]{e.getValue()[1] + " " + e.getKey(), cores});
        }
        rows.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
        var out = new StringBuilder();
        for (int i = 0; i < Math.min(5, rows.size()); i++) out.append(i == 0 ? "" : ", ").append(rows.get(i)[0]).append(' ').append(String.format("%.2f", (double) rows.get(i)[1]));
        return out.toString();
    }

    // ---- Autopilot ----

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (active && measuring(System.nanoTime()) && ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os && mc.level != null && mc.level.getGameTime() % 20 == 0) {
            double load = os.getCpuLoad();
            if (load >= 0) {
                cpuLoadSum += load;
                cpuLoadSamples++;
            }
        }
        if (stage == 0) {
            var file = mc.gameDirectory.toPath().resolve("fps-autopilot/autopilot.properties");
            if (!(mc.screen instanceof TitleScreen) || !Files.isRegularFile(file)) return;
            pilot = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) {
                pilot.load(reader);
            } catch (IOException e) {
                LOGGER.error("FPS_AUTOPILOT cannot read {}", file, e);
                stage = -1;
                return;
            }
            String save = pilot.getProperty("save");
            boolean lod = "lod".equals(pilot.getProperty("mode")), fx = "fx".equals(pilot.getProperty("mode"));
            if (!lod && !fx && !"simulator".equals(pilot.getProperty("mode"))) {
                LOGGER.info("FPS_AUTOPILOT opening save {}", save);
                stage = 1;
                mc.createWorldOpenFlows().openWorld(save, () -> LOGGER.error("FPS_AUTOPILOT could not open the save"));
                return;
            }
            if (mc.getLevelSource().levelExists(save)) {
                LOGGER.error("FPS_AUTOPILOT the save {} exists: this lane measures a fresh world only", save);
                stage = -1;
                return;
            }
            LOGGER.info("FPS_AUTOPILOT creating the flat world {}", save);
            stage = fx ? 60 : lod ? 40 : 20;
            var settings = new LevelSettings(save, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(save, settings, new WorldOptions(20261104L, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
            return;
        }
        if (stage < 0) return;
        if (stage >= 60) {
            fx(mc);
            return;
        }
        if (stage >= 40) {
            lod(mc);
            return;
        }
        if (stage >= 20) {
            simulator(mc);
            return;
        }
        columns(mc);
    }

    /** The 2026-10-15 lane: columns, then empty. */
    private static void columns(Minecraft mc) {
        if (stage > 6) return;
        long now = System.nanoTime();
        if (!COMMANDS.isEmpty()) {
            if (mc.player != null) mc.player.connection.sendCommand(COMMANDS.poll());
            return;
        }
        int warmup = Integer.parseInt(pilot.getProperty("warmup", "60")), measure = Integer.parseInt(pilot.getProperty("measure", "60"));
        String name = pilot.getProperty("label", "lane");
        switch (stage) {
            case 1 -> { // in the world: wait 10 s for the first chunks, then camera and columns
                if (mc.player == null || mc.level == null || mc.screen != null) return;
                if (stageStarted == 0) stageStarted = now;
                if (now - stageStarted < 10_000_000_000L) return;
                queue(mc, "camera.txt");
                queue(mc, "columns.txt");
                stage = 2;
            }
            case 2 -> {
                start(name + "-columns", warmup, measure);
                shot = false;
                stage = 3;
            }
            case 3 -> {
                if (!shot && now > warmupEnds - 5_000_000_000L) {
                    Screenshot.grab(mc.gameDirectory, "fps-" + name + "-columns.png", mc.getMainRenderTarget(), message -> {});
                    shot = true;
                }
                if (active) return;
                queue(mc, "empty.txt");
                stage = 4;
            }
            case 4 -> {
                start(name + "-empty", warmup, measure);
                stage = 5;
            }
            case 5 -> {
                if (active) return;
                quit(mc);
                stage = 6;
            }
            case 6 -> {
                if (mc.level != null) return;
                stage = 7;
                mc.stop();
            }
            default -> {
            }
        }
    }

    /**
     * The LOD lane (batch 2026-11-09-lod-rendering), stages 40 on: 10 s in the new world, {@code scene.txt}, 30 s for the
     * pipes' fluid identities and the chunk meshes, the gauges, 10 s for them to reach the client, then the {@code view.txt}
     * and {@code away.txt} windows ({@code warmup} s, then {@code measure} s each).
     */
    private static void lod(Minecraft mc) {
        long now = System.nanoTime();
        if (!COMMANDS.isEmpty()) {
            if (mc.player != null) mc.player.connection.sendCommand(COMMANDS.poll());
            return;
        }
        int warmup = Integer.parseInt(pilot.getProperty("warmup", "60")), measure = Integer.parseInt(pilot.getProperty("measure", "60"));
        String name = pilot.getProperty("label", "lod");
        switch (stage) {
            case 40 -> {
                if (mc.player == null || mc.level == null || mc.screen != null) return;
                if (stageStarted == 0) stageStarted = now;
                if (now - stageStarted < 10_000_000_000L) return;
                queue(mc, "scene.txt");
                stageStarted = 0;
                stage = 41;
            }
            case 41 -> {
                if (stageStarted == 0) stageStarted = now;
                if (now - stageStarted < 30_000_000_000L) return;
                installGauges(mc);
                stageStarted = 0;
                stage = 42;
            }
            case 42 -> {
                if (stageStarted == 0) stageStarted = now;
                if (now - stageStarted < 10_000_000_000L) return;
                queue(mc, "view.txt");
                stageStarted = 0;
                stage = 43;
            }
            case 43 -> {
                start(name + "-view", warmup, measure);
                shot = false;
                stage = 44;
            }
            case 44 -> {
                if (!shot && now > warmupEnds - 5_000_000_000L) {
                    Screenshot.grab(mc.gameDirectory, "fps-" + name + "-view.png", mc.getMainRenderTarget(), message -> {});
                    shot = true;
                }
                if (active) return;
                queue(mc, "away.txt");
                stage = 45;
            }
            case 45 -> {
                start(name + "-away", warmup, measure);
                stage = 46;
            }
            case 46 -> {
                if (active) return;
                quit(mc);
                stage = 47;
            }
            case 47 -> {
                if (mc.level != null) return;
                stage = 48;
                mc.stop();
            }
            default -> {
            }
        }
    }

    /**
     * Measurement only: a pressure gauge on each pipe of the grid {@code grid.x}, {@code grid.y}, {@code grid.z},
     * {@code grid.nx}, {@code grid.nz}, {@code grid.step}, through the server's own install request (the item's use), the
     * player put 1.8 m from the pipe on each side in turn until one mount takes it.
     */
    private static void installGauges(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.error("FPS_AUTOPILOT the gauges need the integrated server");
            return;
        }
        int x0 = Integer.parseInt(pilot.getProperty("grid.x", "0")), y = Integer.parseInt(pilot.getProperty("grid.y", "-58"));
        int z0 = Integer.parseInt(pilot.getProperty("grid.z", "0")), nx = Integer.parseInt(pilot.getProperty("grid.nx", "10"));
        int nz = Integer.parseInt(pilot.getProperty("grid.nz", "10")), step = Integer.parseInt(pilot.getProperty("grid.step", "3"));
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            var world = com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.find(server).orElse(null);
            if (players.isEmpty() || world == null) {
                LOGGER.error("FPS_AUTOPILOT no player or no fluid world for the gauges");
                return;
            }
            var player = players.get(0);
            var level = player.serverLevel();
            var stack = new net.minecraft.world.item.ItemStack(com.wormzjl.icheme.registry.ModItems.PRESSURE_GAUGE.get());
            stack.set(com.wormzjl.icheme.registry.ModItems.GAUGE_SETTINGS.get(), com.wormzjl.icheme.world.item.GaugeSettings.PRESSURE);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            int done = 0, refused = 0, unbound = 0;
            for (int i = 0; i < nx; i++)
                for (int k = 0; k < nz; k++) {
                    var pos = new BlockPos(x0 + i * step, y, z0 + k * step);
                    if (!(level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe) || pipe.fluidIdentity() <= 0) {
                        unbound++;
                        continue;
                    }
                    boolean ok = false;
                    for (var side : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                        var centre = pos.getCenter();
                        var eye = centre.add(side.getStepX() * 1.8, side.getStepY() * 1.8, side.getStepZ() * 1.8);
                        var look = centre.subtract(eye).normalize();
                        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z)), pitch = (float) Math.toDegrees(-Math.asin(look.y));
                        player.teleportTo(level, eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
                        world.instruments().request(player, InteractionHand.MAIN_HAND, false);
                        if (world.instruments().occupied(pipe.fluidIdentity())) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) done++;
                    else refused++;
                }
            LOGGER.info("FPS_AUTOPILOT gauges installed {} refused {} without identity {}", done, refused, unbound);
        });
    }

    // ---- The effects lane (batch 2026-11-10-custom-shader-system, FX_BENCH_PLAN.md; tools/fx-bench/) ----

    private static final String[] WATCHES = {"FLARES", "STILLS", "TOWERS", "HEATERS"};
    private static int fxWindow;
    private static CompletableFuture<Void> serverRun;
    // The synthetic feed (client thread only).
    private static boolean feeding, noiseFeed;
    private static Object[] watches;
    private static Field watchedField, noiseField;
    private static final Map<FluidNetwork.Target, Object> SYNTHETIC = new HashMap<>();
    private static List<com.wormzjl.icheme.runtime.fluid.Noise.Source> noiseSources = List.of();
    private static Object noiseSeen;
    private static jdk.jfr.Recording recording;

    /**
     * The effects lane, stages 60 on: 10 s in the new world, {@code scene.txt}, 30 s for the structures' identities and the
     * chunks, the drums' instruments ({@code install}), 10 s, the synthetic states (and with {@code noise=true} the noise
     * sources) for 20 s, then each window of {@code windows} ({@code <name>.txt}, then {@code warmup} s and {@code measure} s),
     * the server's publications ({@code server=true}), the memory line, and quit.
     */
    private static void fx(Minecraft mc) {
        long now = System.nanoTime();
        if (!COMMANDS.isEmpty()) {
            if (mc.player != null) mc.player.connection.sendCommand(COMMANDS.poll());
            return;
        }
        if (now < waitUntil) return;
        int warmup = Integer.parseInt(pilot.getProperty("warmup", "60")), measure = Integer.parseInt(pilot.getProperty("measure", "60"));
        String name = pilot.getProperty("label", "fx");
        String[] windows = pilot.getProperty("windows", "view,away").split(",");
        try {
            switch (stage) {
                case 60 -> { // in the fresh world: 10 s for the first chunks, then the scene, one command a tick
                    if (mc.player == null || mc.level == null || mc.screen != null) return;
                    waitUntil = now + 10_000_000_000L;
                    stage = 61;
                }
                case 61 -> {
                    queue(mc, "scene.txt");
                    if (stage >= 0) stage = 62;
                }
                case 62 -> { // the scene placed: 30 s for the identities and the chunk meshes
                    waitUntil = now + 30_000_000_000L;
                    stage = 63;
                }
                case 63 -> {
                    if (!"false".equals(pilot.getProperty("install"))) installInstruments(mc);
                    waitUntil = now + 10_000_000_000L;
                    stage = 64;
                }
                case 64 -> { // the feed: from here to the quit
                    noiseSources = noiseSources();
                    solo = "true".equals(pilot.getProperty("solo"));
                    feeding = solo || !"false".equals(pilot.getProperty("feed"));
                    noiseFeed = "true".equals(pilot.getProperty("noise"));
                    waitUntil = now + 20_000_000_000L;
                    stage = 65;
                }
                case 65 -> {
                    logFeed("before");
                    fxWindow = 0;
                    if (solo) {
                        soloSteps = pilot.getProperty("solo.steps", SOLO_STEPS).split(",");
                        soloStep = 0;
                        stage = 80;
                    } else stage = 66;
                }
                case 80 -> { // solo: one sound alone for solo.seconds, then solo.gap of silence (longer than a republication)
                    if (soloStep >= soloSteps.length) {
                        soloEnd();
                        LOGGER.info("FX_SOLO done");
                        feeding = false;
                        quit(mc);
                        stage = 71;
                        return;
                    }
                    soloBegin(mc, soloSteps[soloStep].trim());
                    waitUntil = now + Long.parseLong(pilot.getProperty("solo.seconds", "15")) * 1_000_000_000L;
                    stage = 81;
                }
                case 81 -> {
                    soloEnd();
                    soloStep++;
                    waitUntil = now + Long.parseLong(pilot.getProperty("solo.gap", "5")) * 1_000_000_000L;
                    stage = 80;
                }
                case 66 -> {
                    queue(mc, windows[fxWindow].trim() + ".txt");
                    if (stage >= 0) stage = 67;
                }
                case 67 -> {
                    window(mc, name + "-" + windows[fxWindow].trim(), warmup, measure);
                    stage = 68;
                }
                case 68 -> {
                    if (active) return;
                    logFeed(windows[fxWindow].trim());
                    stage = ++fxWindow < windows.length ? 66 : 69;
                }
                case 69 -> {
                    if ("true".equals(pilot.getProperty("server"))) serverRun = serverTiming(mc);
                    stage = 70;
                }
                case 70 -> {
                    if (serverRun != null && !serverRun.isDone()) return;
                    memory(mc);
                    feeding = false;
                    quit(mc);
                    stage = 71;
                }
                case 71 -> {
                    if (mc.level != null) return;
                    stage = 72;
                    mc.stop();
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("FPS_AUTOPILOT stage {} failed", stage, e);
            stage = -1;
        }
    }

    /**
     * Measurement only: every gauge mount of each drum of the grid {@code drums.x}, {@code drums.y}, {@code drums.z},
     * {@code drums.nx}, {@code drums.nz}, {@code drums.step} (a pressure gauge where the mount takes one, else a
     * temperature gauge) and one digital level meter, through the server's own install requests, the player put 1.8 m out
     * from each mount in turn, so the structures' instrument wiring is full.
     */
    private static void installInstruments(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.error("FPS_AUTOPILOT the instruments need the integrated server");
            return;
        }
        int x0 = Integer.parseInt(pilot.getProperty("drums.x", "-27")), y = Integer.parseInt(pilot.getProperty("drums.y", "-60"));
        int z0 = Integer.parseInt(pilot.getProperty("drums.z", "10")), nx = Integer.parseInt(pilot.getProperty("drums.nx", "10"));
        int nz = Integer.parseInt(pilot.getProperty("drums.nz", "5")), step = Integer.parseInt(pilot.getProperty("drums.step", "6"));
        server.execute(() -> {
            var players = server.getPlayerList().getPlayers();
            var world = com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.find(server).orElse(null);
            if (players.isEmpty() || world == null) {
                LOGGER.error("FPS_AUTOPILOT no player or no fluid world for the instruments");
                return;
            }
            var player = players.get(0);
            var level = player.serverLevel();
            int hosts = 0, gauges = 0, sensors = 0, missing = 0;
            for (int i = 0; i < nx; i++)
                for (int k = 0; k < nz; k++) {
                    var host = drumHost(level, new BlockPos(x0 + i * step, y, z0 + k * step));
                    if (host == null) {
                        missing++;
                        continue;
                    }
                    hosts++;
                    var at = host.entity().getBlockPos();
                    for (var mount : host.mounts()) {
                        var now = com.wormzjl.icheme.runtime.instrument.InstrumentHosts.at(level, at);
                        if (now == null || mount.kinds().isEmpty() || now.occupied(mount)) continue;
                        boolean pressure = mount.kinds().contains(com.wormzjl.icheme.world.item.GaugeSettings.Kind.PRESSURE);
                        var stack = new net.minecraft.world.item.ItemStack(pressure ? com.wormzjl.icheme.registry.ModItems.PRESSURE_GAUGE.get() : com.wormzjl.icheme.registry.ModItems.TEMPERATURE_GAUGE.get());
                        stack.set(com.wormzjl.icheme.registry.ModItems.GAUGE_SETTINGS.get(), pressure ? com.wormzjl.icheme.world.item.GaugeSettings.PRESSURE : com.wormzjl.icheme.world.item.GaugeSettings.TEMPERATURE);
                        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                        face(player, level, mount.centre(), mount.normal());
                        world.instruments().request(player, InteractionHand.MAIN_HAND, false);
                    }
                    for (var mount : com.wormzjl.icheme.runtime.instrument.Sensor.mounts(host, com.wormzjl.icheme.runtime.instrument.Sensor.Kind.LEVEL)) {
                        player.setItemInHand(InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(com.wormzjl.icheme.registry.ModItems.DIGITAL_LEVEL_METER.get()));
                        face(player, level, mount.centre(), mount.normal());
                        world.instruments().requestSensor(player, InteractionHand.MAIN_HAND, com.wormzjl.icheme.runtime.instrument.Sensor.Kind.LEVEL);
                        break;
                    }
                    var after = com.wormzjl.icheme.runtime.instrument.InstrumentHosts.at(level, at);
                    if (after != null) {
                        gauges += after.metadata().installed().size();
                        sensors += after.metadata().sensors().size();
                    }
                }
            // A creative install keeps the stack: an instrument left in the hand puts InstrumentRenderer in its placement
            // mode (every host gathered, mounts and ghosts each frame), which is not the case the bench measures.
            player.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            LOGGER.info("FPS_AUTOPILOT drum instruments: hosts {} gauges {} sensors {} without a host {}", hosts, gauges, sensors, missing);
        });
    }

    /** The instrument host of the drum standing at {@code base}: the first of its cells up to 12 m above that hosts instruments. */
    private static com.wormzjl.icheme.runtime.instrument.InstrumentHosts.Host drumHost(net.minecraft.server.level.ServerLevel level, BlockPos base) {
        for (int dy = 0; dy <= 12; dy++) {
            var host = com.wormzjl.icheme.runtime.instrument.InstrumentHosts.at(level, base.above(dy));
            if (host != null && !host.pipe() && !host.mounts().isEmpty()) return host;
        }
        return null;
    }

    /** The player 1.8 m out from {@code centre} along {@code normal}, looking at it. */
    private static void face(net.minecraft.server.level.ServerPlayer player, net.minecraft.server.level.ServerLevel level, Vec3 centre, Vec3 normal) {
        var eye = centre.add(normal.scale(1.8));
        var look = centre.subtract(eye).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z)), pitch = (float) Math.toDegrees(-Math.asin(look.y));
        player.teleportTo(level, eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
    }

    /**
     * Measurement only, client thread, every tick from stage 64: the plan's full-load states for every structure the
     * effects' watches hold ({@code ProcessStates}' stores, by reflection), stored through the stores' own {@code accept}
     * again whenever the server's republication (the structures' real, idle states, every 5 s) replaced them. Ticks start
     * after the packets of the tick are handled, so the effects and the sounds, which read the stores at the tick's end, see
     * only these. With {@code noise=true} the same for the noise watch's answer ({@code FxSounds.accept}). Nothing is sent
     * to the server.
     */
    @SubscribeEvent
    public static void feed(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (!feeding || mc.level == null || mc.player == null) return;
        try {
            if (watches == null) {
                watches = new Object[WATCHES.length];
                for (int k = 0; k < WATCHES.length; k++) {
                    var f = com.wormzjl.icheme.client.fx.state.ProcessStates.class.getDeclaredField(WATCHES[k]);
                    f.setAccessible(true);
                    watches[k] = f.get(null);
                }
                watchedField = com.wormzjl.icheme.client.fx.state.CellWatch.class.getDeclaredField("watched");
                watchedField.setAccessible(true);
                noiseField = com.wormzjl.icheme.client.fx.sound.FxSounds.class.getDeclaredField("noise");
                noiseField.setAccessible(true);
            }
            for (int k = 0; k < watches.length; k++) {
                if (solo && k != soloFeedKind) continue;
                var watch = (com.wormzjl.icheme.client.fx.state.CellWatch<?>) watches[k];
                FluidNetwork.Target nearest = null;
                if (solo) { // the solo feeds the structure nearest the listener only
                    double best = Double.MAX_VALUE;
                    for (Object o : (Set<?>) watchedField.get(watch)) {
                        double d = ((FluidNetwork.Target) o).position().distSqr(mc.player.blockPosition());
                        if (d < best) {
                            best = d;
                            nearest = (FluidNetwork.Target) o;
                        }
                    }
                }
                for (Object o : (Set<?>) watchedField.get(watch)) {
                    var target = (FluidNetwork.Target) o;
                    if (solo && target != nearest) continue;
                    Object s = SYNTHETIC.get(target);
                    if (s == null) {
                        s = synthetic(k, target, mc.level);
                        if (s == null) continue;
                        SYNTHETIC.put(target, s);
                    }
                    if (watch.state(target) != s) accept(s);
                }
            }
            if (solo) {
                if (soloSource != null && (noiseField.get(null) != noiseSeen || soloSource.kind().oneShot())) {
                    com.wormzjl.icheme.client.fx.sound.FxSounds.accept(List.of(soloSource));
                    noiseSeen = noiseField.get(null);
                }
            } else if (noiseFeed && noiseField.get(null) != noiseSeen) {
                com.wormzjl.icheme.client.fx.sound.FxSounds.accept(noiseSources);
                noiseSeen = noiseField.get(null);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("FX_FEED failed; the feed stops", e);
            feeding = false;
        }
    }

    private static void accept(Object state) {
        if (state instanceof FluidNetwork.FlareState s) com.wormzjl.icheme.client.fx.state.ProcessStates.accept(s);
        else if (state instanceof FluidNetwork.StillState s) com.wormzjl.icheme.client.fx.state.ProcessStates.accept(s);
        else if (state instanceof FluidNetwork.TowerPlume s) com.wormzjl.icheme.client.fx.state.ProcessStates.accept(s);
        else if (state instanceof FluidNetwork.HeaterFlue s) com.wormzjl.icheme.client.fx.state.ProcessStates.accept(s);
        else throw new IllegalStateException("No store for " + state);
    }

    /**
     * The full-load state of watch {@code kind} ({@link #WATCHES}) for {@code target}, from its structure's shape, or null
     * while its cell is not loaded: a flare flaring at its rating with soot (flame and smoke), a still distilling at full fire
     * (fire, flue smoke, drip), a tower's exhaust at 36 °C saturated through its exits at 9 m/s (induced) or 4 m/s (natural),
     * a heater fired at its design with liquid fuel (white plume and grey smoke) and its stack at 620 K, 8 m/s.
     */
    private static Object synthetic(int kind, FluidNetwork.Target target, net.minecraft.client.multiplayer.ClientLevel level) {
        if (!(level.getBlockEntity(target.position()) instanceof com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity shell) || shell.shape() == null) return null;
        var shape = shell.shape();
        var marks = com.wormzjl.icheme.science.equipment.ShapeRasterizer.marks(shape);
        return switch (kind) {
            case 0 -> new FluidNetwork.FlareState(target, com.wormzjl.icheme.runtime.fluid.Flares.Status.FLARING.ordinal(), 1f, 0f, .7f, 80f);
            case 1 -> new FluidNetwork.StillState(target, shape.still().heater().ordinal(), com.wormzjl.icheme.science.fluid.network.BatchStill.Status.DISTILLING.ordinal(), 1f, true, true);
            case 2 -> {
                boolean natural = shape.kind() == com.wormzjl.icheme.science.equipment.EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER;
                float w = .036f, air = (float) (1.1 * (natural ? 4 : 9) * area(marks, natural ? "throat" : "fan_exhaust_") / (1 + w));
                yield new FluidNetwork.TowerPlume(target, air, 309f, w, .012f * air);
            }
            case 3 -> new FluidNetwork.HeaterFlue(target, 1f, 620f, (float) (.55 * 8 * area(marks, "stack_top_")), .12f, com.wormzjl.icheme.science.fluid.network.Burners.Fuel.LIQUID.ordinal());
            default -> null;
        };
    }

    /** The flow area of the marks named {@code prefix}..., m², each a circle across its x width; at least 1 m². */
    private static double area(Map<String, double[]> marks, String prefix) {
        double a = 0;
        for (var e : marks.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            double d = e.getValue()[3] - e.getValue()[0];
            a += Math.PI * d * d / 4;
        }
        return Math.max(a, 1);
    }

    /**
     * The sound lane's noise (plan lane 5) around {@code noise.x}, {@code noise.y}, {@code noise.z} (the view point): 100
     * valves at a 1 MPa drop on a 2 m grid, gas and liquid in turn, 1 m below; 20 tower fans at full speed on a ring of 12 m.
     */
    /** The sound lane's valve kinds, in turn on the grid: gas, plain liquid, cavitating, flashing. */
    private static final com.wormzjl.icheme.runtime.fluid.Noise.Kind[] VALVES = {com.wormzjl.icheme.runtime.fluid.Noise.Kind.VALVE_GAS,
            com.wormzjl.icheme.runtime.fluid.Noise.Kind.VALVE_LIQUID, com.wormzjl.icheme.runtime.fluid.Noise.Kind.VALVE_CAVITATING,
            com.wormzjl.icheme.runtime.fluid.Noise.Kind.VALVE_FLASHING};

    private static List<com.wormzjl.icheme.runtime.fluid.Noise.Source> noiseSources() {
        double x = Double.parseDouble(pilot.getProperty("noise.x", ".5")), y = Double.parseDouble(pilot.getProperty("noise.y", "-40"));
        double z = Double.parseDouble(pilot.getProperty("noise.z", "-30"));
        var out = new ArrayList<com.wormzjl.icheme.runtime.fluid.Noise.Source>();
        float valve = (float) com.wormzjl.icheme.runtime.fluid.Noise.valve(1e6);
        float fan = (float) com.wormzjl.icheme.runtime.fluid.Noise.fans(1, com.wormzjl.icheme.runtime.fluid.Noise.TOWER_FAN, 1);
        for (int i = 0; i < 10; i++)
            for (int k = 0; k < 10; k++)
                out.add(new com.wormzjl.icheme.runtime.fluid.Noise.Source(BlockPos.containing(x - 9 + 2 * i, y - 1, z - 9 + 2 * k),
                        VALVES[(i + k) % VALVES.length], valve, 1));
        for (int j = 0; j < 20; j++) {
            double a = 2 * Math.PI * j / 20;
            out.add(new com.wormzjl.icheme.runtime.fluid.Noise.Source(BlockPos.containing(x + 12 * Math.cos(a), y, z + 12 * Math.sin(a)), com.wormzjl.icheme.runtime.fluid.Noise.Kind.FAN, fan, 1));
        }
        return List.copyOf(out);
    }

    // The sound solo (lane.sh 6-solo, a listening run, not a timing run): each sound alone, its name on screen.
    private static final String SOLO_STEPS = "valve_gas,valve_liquid,valve_cavitating,valve_flashing,vent,fan,water_fall,burner,gear,kettle,pipe_knock,ejector,flare,still";
    private static boolean solo;
    private static String[] soloSteps;
    private static int soloStep, soloFeedKind = -1;
    private static com.wormzjl.icheme.runtime.fluid.Noise.Source soloSource;

    /**
     * One solo step: a noise kind is one source {@code solo.distance} m (default 8) ahead of the listener at the view point,
     * at the level its model gives at a usual duty; {@code flare} and {@code still} feed only the structure of that kind
     * nearest the listener, who stands 30 m (flare) or 8 m (still) from it.
     */
    private static void soloBegin(Minecraft mc, String step) {
        double x = Double.parseDouble(pilot.getProperty("noise.x", ".5")), y = Double.parseDouble(pilot.getProperty("noise.y", "-40"));
        double z = Double.parseDouble(pilot.getProperty("noise.z", "-30")), d = Double.parseDouble(pilot.getProperty("solo.distance", "8"));
        soloSource = null;
        soloFeedKind = -1;
        String what;
        COMMANDS.add("gamemode spectator");
        switch (step) {
            case "flare" -> {
                COMMANDS.add("tp @s -3.5 -55 16 0 -15");
                soloFeedKind = 0;
                what = "flare stack at its rating with soot, 30 m";
            }
            case "still" -> {
                COMMANDS.add("tp @s -1.5 -57 -8 0 20");
                soloFeedKind = 1;
                what = "batch still distilling at full fire, 8 m";
            }
            default -> {
                var kind = com.wormzjl.icheme.runtime.fluid.Noise.Kind.valueOf(step.toUpperCase(java.util.Locale.ROOT));
                double level = switch (kind) {
                    case VALVE_GAS, VALVE_LIQUID, VALVE_CAVITATING, VALVE_FLASHING -> com.wormzjl.icheme.runtime.fluid.Noise.valve(1e6);
                    case VENT -> com.wormzjl.icheme.runtime.fluid.Noise.vent(1, com.wormzjl.icheme.runtime.fluid.Noise.ventDensity(.029, 300), .01);
                    case FAN -> com.wormzjl.icheme.runtime.fluid.Noise.fans(1, com.wormzjl.icheme.runtime.fluid.Noise.TOWER_FAN, 1);
                    case WATER_FALL -> com.wormzjl.icheme.runtime.fluid.Noise.water(100);
                    case BURNER -> com.wormzjl.icheme.runtime.fluid.Noise.burner(5e6);
                    case GEAR -> com.wormzjl.icheme.runtime.fluid.Noise.gear(5e5, 1);
                    case KETTLE -> com.wormzjl.icheme.runtime.fluid.Noise.kettle(2e6);
                    case PIPE_KNOCK -> com.wormzjl.icheme.runtime.fluid.Noise.knock(1000, 2);
                    case EJECTOR -> com.wormzjl.icheme.runtime.fluid.Noise.ejector(.218, 5.1, 1e6, 1.2e5);
                };
                if (!Double.isFinite(level)) level = 90;
                COMMANDS.add("tp @s " + x + " " + y + " " + z + " 0 0");
                soloSource = new com.wormzjl.icheme.runtime.fluid.Noise.Source(BlockPos.containing(x, y, z + d), kind, (float) level, 1);
                noiseSeen = null;
                what = String.format(java.util.Locale.ROOT, "%.0f dB sound power, %.0f m ahead, volume %.2f", level, d,
                        com.wormzjl.icheme.client.fx.sound.SoundBudget.volume(level));
            }
        }
        String text = "[sound solo " + (soloStep + 1) + "/" + soloSteps.length + "] " + step + ": " + what;
        LOGGER.info("FX_SOLO {}", text);
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
        mc.gui.setOverlayMessage(Component.literal(step), false);
    }

    /** The step's end: its source and feed stop; a fed structure's real (idle) state returns with the next republication. */
    private static void soloEnd() {
        soloSource = null;
        soloFeedKind = -1;
        if (Minecraft.getInstance().level != null) com.wormzjl.icheme.client.fx.sound.FxSounds.accept(List.of());
        noiseSeen = null;
    }

    /** One {@code FX_FEED} line: per watch, the structures holding a synthetic state over those watched; the effects' sources. */
    private static void logFeed(String when) {
        var out = new StringBuilder();
        if (watches != null) {
            try {
                for (int k = 0; k < watches.length; k++) {
                    var watch = (com.wormzjl.icheme.client.fx.state.CellWatch<?>) watches[k];
                    var watched = (Set<?>) watchedField.get(watch);
                    int fed = 0;
                    for (Object o : watched) {
                        var s = SYNTHETIC.get((FluidNetwork.Target) o);
                        if (s != null && watch.state((FluidNetwork.Target) o) == s) fed++;
                    }
                    out.append(' ').append(WATCHES[k].toLowerCase(java.util.Locale.ROOT)).append('=').append(fed).append('/').append(watched.size());
                }
            } catch (ReflectiveOperationException e) {
                out.append(" unreadable");
            }
        } else out.append(" off");
        LOGGER.info("FX_FEED when={}{} noise={} sources={} registered={}", when, out, FxStats.now(FxStats.Counter.NOISE), FxStats.now(FxStats.Counter.SOURCES),
                FxStats.now(FxStats.Counter.REGISTERED));
    }

    /** The sound tick of the window, sampled at each tick's start: the last tick's time, loops, sources and groups. */
    @SubscribeEvent
    public static void soundSample(ClientTickEvent.Pre event) {
        if (!measuring(System.nanoTime()) || soundTicks >= SOUND.length) return;
        SOUND[soundTicks++] = FxStats.now(FxStats.Counter.SOUND_NS);
        long loops = FxStats.now(FxStats.Counter.LOOPS);
        loopSum += loops;
        loopMax = Math.max(loopMax, loops);
        soundSourceSum += FxStats.now(FxStats.Counter.SOUND_SOURCES);
        soundGroupSum += FxStats.now(FxStats.Counter.SOUND_GROUPS);
    }

    /** The window's {@code FX_PROBE} line (every mode). */
    private static void fxLine() {
        if (framesBefore < 0) return;
        long sound = 0;
        for (int i = 0; i < soundTicks; i++) sound += SOUND[i];
        LOGGER.info("FX_PROBE label={} path={} frames={} fxCpuMs[p50/p95]={}/{} drawMs[p50/p95]={}/{} buildMs[p50/p95]={}/{} wiringMs[p50/p95]={}/{} "
                        + "stageCpuMs[p50/p95]={}/{} gpuStageMs[p50/p95]={}/{} gpuStages={} quads[mean/max]={}/{} emitters[mean]={} rebuilds={} wiringBuilds={} fxFrames={} "
                        + "soundMs[mean/p50/p95/max]={}/{}/{}/{} ticks={} loops[mean/max]={}/{} soundSources[mean]={} soundGroups[mean]={}",
                label, FxStats.path(), fxFrames, fmt(FX_CPU, fxFrames, .5), fmt(FX_CPU, fxFrames, .95), fmt(FX_DRAW, fxFrames, .5), fmt(FX_DRAW, fxFrames, .95),
                fmt(FX_BUILD, fxFrames, .5), fmt(FX_BUILD, fxFrames, .95), fmt(FX_WIRING, fxFrames, .5), fmt(FX_WIRING, fxFrames, .95),
                fmt(STAGE_CPU, stageFrames, .5), fmt(STAGE_CPU, stageFrames, .95), fmt(GPU_STAGE, gpuStages, .5), fmt(GPU_STAGE, gpuStages, .95), gpuStages,
                fxFrames == 0 ? "-" : String.format("%.0f", (double) quadSum / fxFrames), quadMax, fxFrames == 0 ? "-" : String.format("%.1f", (double) emitterSum / fxFrames),
                FxStats.total(FxStats.Counter.REBUILDS) - rebuildsBefore, FxStats.total(FxStats.Counter.WIRING_BUILDS) - wiringBuildsBefore, FxStats.frames() - framesBefore,
                soundTicks == 0 ? "-" : String.format("%.4f", sound / 1e6 / soundTicks), fmt(SOUND, soundTicks, .5), fmt(SOUND, soundTicks, .95), fmt(SOUND, soundTicks, 1),
                soundTicks, soundTicks == 0 ? "-" : String.format("%.1f", (double) loopSum / soundTicks), loopMax,
                soundTicks == 0 ? "-" : String.format("%.1f", (double) soundSourceSum / soundTicks), soundTicks == 0 ? "-" : String.format("%.1f", (double) soundGroupSum / soundTicks));
    }

    /** The {@code quantile} of the first {@code n} samples (ns), ms with four decimals; sorts them. */
    private static String fmt(long[] samples, int n, double quantile) {
        if (n == 0) return "-";
        Arrays.sort(samples, 0, n);
        return String.format("%.4f", samples[Math.min(n - 1, (int) (quantile * n))] / 1e6);
    }

    /**
     * Measurement only: the window's JFR recording (started by {@link #frame} when the window's measurement starts, with
     * {@code jfr=true}): allocation samples ({@code jfr.throttle}, 1000/s by default) and execution samples every 10 ms.
     */
    private static void jfrStart() {
        try {
            var r = new jdk.jfr.Recording();
            r.enable("jdk.ObjectAllocationSample").with("throttle", pilot.getProperty("jfr.throttle", "1000/s")).withStackTrace();
            r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
            r.setToDisk(true);
            r.start();
            recording = r;
        } catch (Throwable e) { // JFR missing from the runtime: the window still measures
            LOGGER.error("FX_JFR could not start", e);
        }
    }

    /** Stops the window's recording, keeps it as {@code fps-<name>.jfr} and logs its {@code FX_JFR} line off the render thread. */
    private static void jfrStop(String name, int frames) {
        var r = recording;
        recording = null;
        if (r == null) return;
        var out = Minecraft.getInstance().gameDirectory.toPath().resolve("fps-" + name + ".jfr");
        try {
            r.stop();
            r.dump(out);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("FX_JFR could not keep {}", out, e);
            return;
        } finally {
            r.close();
        }
        CompletableFuture.runAsync(() -> jfrSummary(out, name, frames));
    }

    private static boolean fxClass(String name) {
        return name.startsWith("com.wormzjl.icheme.client.fx.") || name.startsWith("com.wormzjl.icheme.client.InstrumentRenderer");
    }

    /** The innermost frame of {@code stack} in the effects' code ({@link #fxClass}), as {@code Class.method}; null for none. */
    private static String fxFrame(jdk.jfr.consumer.RecordedStackTrace stack) {
        if (stack == null) return null;
        for (var f : stack.getFrames()) {
            if (f.getMethod() == null || f.getMethod().getType() == null) continue;
            String type = f.getMethod().getType().getName();
            if (fxClass(type)) return type.substring(type.lastIndexOf('.') + 1) + "." + f.getMethod().getName();
        }
        return null;
    }

    /**
     * {@code FX_JFR}: the allocation the samples weigh to the effects' code (all threads), per frame of the window, its top
     * methods and classes; the render thread's execution samples, the share whose stack holds the effects' code, its top
     * methods (innermost effects frame).
     */
    private static void jfrSummary(Path file, String name, int frames) {
        long all = 0, fx = 0, renderSamples = 0, fxSamples = 0;
        var allocAt = new HashMap<String, Long>();
        var allocOf = new HashMap<String, Long>();
        var cpuAt = new HashMap<String, Long>();
        try (var events = new jdk.jfr.consumer.RecordingFile(file)) {
            while (events.hasMoreEvents()) {
                var e = events.readEvent();
                String type = e.getEventType().getName();
                if (type.equals("jdk.ObjectAllocationSample")) {
                    long weight = e.getLong("weight");
                    all += weight;
                    String at = fxFrame(e.getStackTrace());
                    if (at == null) continue;
                    fx += weight;
                    allocAt.merge(at, weight, Long::sum);
                    var c = e.getClass("objectClass");
                    allocOf.merge(c == null ? "?" : c.getName(), weight, Long::sum);
                } else if (type.equals("jdk.ExecutionSample")) {
                    var thread = e.getThread("sampledThread");
                    if (thread == null || !"Render thread".equals(thread.getJavaName())) continue;
                    renderSamples++;
                    String at = fxFrame(e.getStackTrace());
                    if (at == null) continue;
                    fxSamples++;
                    cpuAt.merge(at, 1L, Long::sum);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("FX_JFR cannot read {}", file, e);
            return;
        }
        LOGGER.info("FX_JFR label={} frames={} allocFxBytes={} allocFxPerFrame={} allocAllBytes={} allocTop=[{}] allocClasses=[{}] renderSamples={} fxSamples={} fxShare={}% cpuTop=[{}] file={}",
                name, frames, fx, frames == 0 ? "-" : String.format("%.0f", (double) fx / frames), all, top(allocAt), top(allocOf), renderSamples, fxSamples,
                renderSamples == 0 ? "-" : String.format("%.2f", 100.0 * fxSamples / renderSamples), top(cpuAt), file.getFileName());
    }

    /** The six largest entries, "key value" joined by commas. */
    private static String top(Map<String, Long> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(6)
                .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Measurement only, on the server thread: the new presentation publications of FluidWorldAuthority ({@code publishTowers},
     * {@code publishHeaters}, {@code publishNoise}, {@code publishSilhouettes}; private, by reflection) at the scene's size,
     * {@code server.calls} times each after 20 unmeasured, with what each served (watched devices, islands, subscribers).
     * Noise and silhouettes also as their worst case: every island's levels computed again ({@code Noise.Store.clear} first) and
     * every subscriber ranked again ({@code Silhouettes.changed} first). Each call sends what it publishes, as the cadence does.
     */
    private static CompletableFuture<Void> serverTiming(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.error("FX_SERVER needs the integrated server");
            return null;
        }
        int calls = Integer.parseInt(pilot.getProperty("server.calls", "200"));
        return server.submit(() -> {
            try {
                Object world = com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.find(server).orElseThrow();
                Object noise = get(world, "noise"), silhouettes = get(world, "silhouettes");
                publish(world, "publishTowers", null, calls, "towers", "devices=" + ((Map<?, ?>) get(get(world, "towerWatchers"), "byDevice")).size());
                publish(world, "publishHeaters", null, calls, "heaters", "devices=" + ((Map<?, ?>) get(get(world, "heaterWatchers"), "byDevice")).size());
                publish(world, "publishNoise", null, calls, "noise", "islands=" + ((Map<?, ?>) get(noise, "islands")).size());
                publish(world, "publishNoise", () -> call(noise, "clear"), calls, "noise-recomputed", "islands=" + ((Map<?, ?>) get(noise, "islands")).size());
                publish(world, "publishSilhouettes", null, calls, "silhouettes", "subscribers=" + ((Map<?, ?>) get(silhouettes, "subscriptions")).size());
                publish(world, "publishSilhouettes", () -> call(silhouettes, "changed", "minecraft:overworld"), calls, "silhouettes-ranked",
                        "subscribers=" + ((Map<?, ?>) get(silhouettes, "subscriptions")).size());
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.error("FX_SERVER failed", e);
            }
        });
    }

    private interface Step {
        Object run() throws ReflectiveOperationException;
    }

    private static void publish(Object world, String method, Step before, int calls, String name, String served) throws ReflectiveOperationException {
        var samples = new long[calls];
        for (int i = 0; i < calls + 20; i++) {
            if (before != null) before.run();
            long started = System.nanoTime();
            call(world, method);
            long took = System.nanoTime() - started;
            if (i >= 20) samples[i - 20] = took;
        }
        long sum = 0;
        for (long s : samples) sum += s;
        Arrays.sort(samples);
        LOGGER.info("FX_SERVER publication={} calls={} p50Us={} p95Us={} meanUs={} {}", name, calls, String.format("%.1f", samples[calls / 2] / 1e3),
                String.format("%.1f", samples[Math.min(calls - 1, (int) (.95 * calls))] / 1e3), String.format("%.1f", sum / 1e3 / calls), served);
    }

    /**
     * {@code FX_MEMORY}: the effects' resident bytes from FxStats (cached vertex buffers, wiring uploads, the haze's target),
     * the effects' and wiring's textures loaded now (level 0, RGBA8, read from GL), silhouette boxes and the stores' sizes.
     */
    private static void memory(Minecraft mc) {
        long textures = 0;
        int loaded = 0;
        for (String folder : new String[]{"textures/fx", "textures/wiring"})
            for (var location : mc.getResourceManager().listResources(folder, p -> p.getPath().endsWith(".png")).keySet()) {
                if (!location.getNamespace().equals("icheme")) continue;
                var texture = mc.getTextureManager().getTexture(location, null);
                if (texture == null) continue;
                com.mojang.blaze3d.platform.GlStateManager._bindTexture(texture.getId());
                textures += 4L * GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH) * GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                loaded++;
            }
        com.mojang.blaze3d.platform.GlStateManager._bindTexture(0);
        LOGGER.info("FX_MEMORY vertexBytes={} wiringBytes={} targetBytes={} textureBytes={} textures={} silhouetteBoxes={} silhouettes={} states={} sources={} registered={} noise={} loops={}",
                FxStats.now(FxStats.Counter.VERTEX_BYTES), FxStats.now(FxStats.Counter.WIRING_BYTES), FxStats.now(FxStats.Counter.TEXTURE_BYTES), textures, loaded,
                FxStats.now(FxStats.Counter.SILHOUETTE_BOXES), FxStats.now(FxStats.Counter.SILHOUETTES), FxStats.now(FxStats.Counter.STATES), FxStats.now(FxStats.Counter.SOURCES),
                FxStats.now(FxStats.Counter.REGISTERED), FxStats.now(FxStats.Counter.NOISE), FxStats.now(FxStats.Counter.LOOPS));
    }

    /**
     * The simulator lane, stages 20 on: the world with no screen, then the screen's cases on the bundled crude unit, run to steady
     * before any window, so no run's CPU falls in one. Each case: {@code warmup} s, then {@code measure} s.
     */
    private static void simulator(Minecraft mc) {
        long now = System.nanoTime();
        if (!COMMANDS.isEmpty()) {
            if (mc.player != null) mc.player.connection.sendCommand(COMMANDS.poll());
            return;
        }
        if (now < waitUntil) return;
        int warmup = Integer.parseInt(pilot.getProperty("warmup", "60")), measure = Integer.parseInt(pilot.getProperty("measure", "60"));
        String name = pilot.getProperty("label", "sim");
        try {
            switch (stage) {
                case 20 -> { // in the fresh world: a still noon, the camera, the block; then let the chunks settle
                    if (mc.player == null || mc.level == null || mc.screen != null) return;
                    COMMANDS.addAll(List.of("gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "gamerule doMobSpawning false", "time set 6000", "weather clear",
                            "tp @s 0.5 -60 0.5 -90 15", "setblock " + BLOCK.getX() + " " + BLOCK.getY() + " " + BLOCK.getZ() + " icheme:process_simulator"));
                    waitUntil = now + 10_000_000_000L;
                    stage = 21;
                }
                case 21 -> {
                    window(mc, name + "-none", warmup, measure);
                    stage = 22;
                }
                case 22 -> { // the world with no screen measured: open the block
                    if (active) return;
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(BLOCK).add(-.5, 0, 0), Direction.WEST, BLOCK, false));
                    stageStarted = now;
                    stage = 23;
                }
                case 23 -> { // the screen open: the crude unit, fitted, then Run
                    var screen = mc.screen;
                    if (screen == null || !screen.getClass().getName().equals(SIM)) {
                        if (now - stageStarted > 15_000_000_000L) throw new IllegalStateException("the simulator screen did not open");
                        return;
                    }
                    call(screen, "replaceWith", crude(mc));
                    set(screen, "placed", false);
                    rebuild(screen);
                    call(screen, "act", "run");
                    stageStarted = now;
                    stage = 24;
                    LOGGER.info("FPS_AUTOPILOT the crude unit loaded, Run started");
                }
                case 24 -> { // until the run stands steady, then 5 s for its results to land
                    var screen = simulatorScreen(mc);
                    // Through the public types: the host may be a private class.
                    Object host = get(screen, "host");
                    boolean running = Class.forName(FLOWSHEET + "SimulatorHost").getMethod("running").invoke(host) != null;
                    Object state = Class.forName(FLOWSHEET + "SheetContext").getMethod("state").invoke(call(screen, "context"));
                    if (running || !(boolean) Class.forName(FLOWSHEET + "SheetState").getMethod("steady").invoke(state)) {
                        if (now - stageStarted > 600_000_000_000L) throw new IllegalStateException("the run did not stand steady in 10 minutes");
                        return;
                    }
                    LOGGER.info("FPS_AUTOPILOT the run is steady after {} s", (now - stageStarted) / 1_000_000_000L);
                    waitUntil = now + 5_000_000_000L;
                    stage = 25;
                }
                case 25 -> {
                    window(mc, name + "-idle", warmup, measure);
                    stage = 26;
                }
                case 26 -> {
                    if (active) return;
                    panning = true;
                    panFrame = 0;
                    window(mc, name + "-pan", warmup, measure);
                    stage = 27;
                }
                case 27 -> { // panned: back to the fitted view, then T-101's door
                    if (active) return;
                    panning = false;
                    var screen = simulatorScreen(mc);
                    set(screen, "placed", false);
                    rebuild(screen);
                    var selection = Class.forName(FLOWSHEET + "SheetGestures$Selection").getConstructor(boolean.class, String.class).newInstance(true, "T-101");
                    call(screen, "open", selection);
                    waitUntil = now + 2_000_000_000L;
                    stage = 28;
                }
                case 28 -> {
                    window(mc, name + "-door", warmup, measure);
                    stage = 29;
                }
                case 29 -> { // the door closed, the Results tab
                    if (active) return;
                    var screen = simulatorScreen(mc);
                    set(screen, "door", null);
                    rebuild(screen);
                    call(screen, "act", "tab/1");
                    waitUntil = now + 2_000_000_000L;
                    stage = 30;
                }
                case 30 -> {
                    window(mc, name + "-results", warmup, measure);
                    stage = 31;
                }
                case 31 -> {
                    if (active) return;
                    mc.setScreen(null);
                    quit(mc);
                    stage = 32;
                }
                case 32 -> {
                    if (mc.level != null) return;
                    stage = 33;
                    mc.stop();
                }
                default -> {
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("FPS_AUTOPILOT stage {} failed", stage, e);
            stage = -1;
        }
    }

    /** A measured window, with a screenshot 5 s before it starts. */
    private static void window(Minecraft mc, String name, int warmup, int measure) {
        start(name, warmup, measure);
        shot = false;
        // The shot is taken by the tick that passes shotAt; see shoot().
        shotAt = warmupEnds - 5_000_000_000L;
        shotName = "fps-" + name + ".png";
    }

    private static long shotAt;
    private static String shotName;

    @SubscribeEvent
    public static void shoot(ClientTickEvent.Pre event) {
        if (!shot && active && shotName != null && System.nanoTime() > shotAt) {
            var mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, shotName, mc.getMainRenderTarget(), message -> {});
            shot = true;
        }
    }

    private static void pan(Minecraft mc) {
        try {
            var screen = simulatorScreen(mc);
            Object view = get(screen, "view");
            // Back and forth: 2 px a frame, turning every 120 frames, so the sheet stays in the window.
            double dx = (panFrame++ / 120) % 2 == 0 ? 2 : -2;
            view.getClass().getMethod("pan", double.class, double.class).invoke(view, dx, 0.0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("FPS_AUTOPILOT pan failed", e);
            panning = false;
        }
    }

    private static void quit(Minecraft mc) {
        LOGGER.info("FPS_AUTOPILOT done, saving and quitting");
        if (mc.level != null) mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.literal("Saving")));
    }

    /** The bundled crude unit, the first example with "crude" in its stem. */
    private static Object crude(Minecraft mc) throws ReflectiveOperationException {
        Method resources = Class.forName("com.wormzjl.icheme.client.gui.screens.inventory.ColumnTemplates").getDeclaredMethod("resources", Minecraft.class);
        resources.setAccessible(true);
        @SuppressWarnings("unchecked") var source = (Function<String, InputStream>) resources.invoke(null, mc);
        var examples = (List<?>) Class.forName(FLOWSHEET + "FlowsheetExamples").getMethod("read", Function.class).invoke(null, source);
        for (Object example : examples) {
            String stem = (String) example.getClass().getMethod("stem").invoke(example);
            Object sheet = example.getClass().getMethod("sheet").invoke(example);
            if (stem.contains("crude") && sheet != null) return sheet;
        }
        throw new IllegalStateException("no bundled crude unit among " + examples.size() + " examples");
    }

    private static Screen simulatorScreen(Minecraft mc) {
        var screen = mc.screen;
        if (screen == null || !screen.getClass().getName().equals(SIM)) throw new IllegalStateException("the simulator screen closed: " + screen);
        return screen;
    }

    private static void rebuild(Screen screen) throws ReflectiveOperationException {
        Method rebuild = Screen.class.getDeclaredMethod("rebuildWidgets");
        rebuild.setAccessible(true);
        rebuild.invoke(screen);
    }

    /** Calls the screen's own method {@code name} with {@code args}, by name and count. */
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            return m.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
    }

    private static Object get(Object target, String field) throws ReflectiveOperationException {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        var f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void queue(Minecraft mc, String file) {
        try {
            for (var line : Files.readAllLines(mc.gameDirectory.toPath().resolve("fps-autopilot").resolve(file))) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) COMMANDS.add(line.startsWith("/") ? line.substring(1) : line);
            }
        } catch (IOException e) {
            LOGGER.error("FPS_AUTOPILOT cannot read {}", file, e);
            stage = -1;
        }
    }

    private static String ms(long[] sorted, double quantile) {
        return ms(sorted, sorted.length, quantile);
    }

    /** The {@code quantile} of the first {@code n} samples, ms; sorts them. */
    private static String ms(long[] samples, int n, double quantile) {
        if (n == 0) return "-";
        Arrays.sort(samples, 0, n);
        return String.format("%.2f", samples[Math.min(n - 1, (int) (quantile * n))] / 1e6);
    }
}
