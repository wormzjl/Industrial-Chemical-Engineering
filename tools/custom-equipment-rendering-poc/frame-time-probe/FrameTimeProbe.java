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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
 * {@code view.txt} and {@code away.txt}, and quits.
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
    // GPU ring (render thread only).
    private static int[] queries;
    private static final boolean[] PENDING = new boolean[RING], SCREEN = new boolean[RING], WINDOW = new boolean[RING];
    private static int slot;
    private static boolean screenInFrame;
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
        GL33.glQueryCounter(queries[slot * 4 + index], GL33.GL_TIMESTAMP);
    }

    @SubscribeEvent
    public static void framePre(RenderFrameEvent.Pre event) {
        if (queries == null) {
            queries = new int[RING * 4];
            GL15.glGenQueries(queries);
            LOGGER.info("FPS_PROBE gpu renderer={} version={}", GL11.glGetString(GL11.GL_RENDERER), GL11.glGetString(GL11.GL_VERSION));
        }
        if (PENDING[slot]) {
            int base = slot * 4;
            if (GL15.glGetQueryObjecti(queries[base + 3], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
                if (WINDOW[slot]) {
                    long t0 = GL33.glGetQueryObjecti64(queries[base], GL15.GL_QUERY_RESULT), t3 = GL33.glGetQueryObjecti64(queries[base + 3], GL15.GL_QUERY_RESULT);
                    if (gpuFrames < GPU_FRAME.length) GPU_FRAME[gpuFrames++] = t3 - t0;
                    if (SCREEN[slot] && gpuScreens < GPU_SCREEN.length)
                        GPU_SCREEN[gpuScreens++] = GL33.glGetQueryObjecti64(queries[base + 2], GL15.GL_QUERY_RESULT) - GL33.glGetQueryObjecti64(queries[base + 1], GL15.GL_QUERY_RESULT);
                }
            } else if (WINDOW[slot]) gpuMissed++;
            PENDING[slot] = false;
        }
        WINDOW[slot] = measuring(System.nanoTime());
        SCREEN[slot] = false;
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

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        if (queries != null) {
            counter(3);
            PENDING[slot] = true;
            slot = (slot + 1) % RING;
        }
        if (!active) return;
        long now = System.nanoTime();
        if (now >= warmupEnds && loadBefore == null) loadBefore = CompletableFuture.supplyAsync(FrameTimeProbe::cpuTimes);
        if (now >= warmupEnds && last >= warmupEnds && count < SAMPLES.length) SAMPLES[count++] = now - last;
        last = now;
        if (now < measureEnds) return;
        active = false;
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
            boolean lod = "lod".equals(pilot.getProperty("mode"));
            if (!lod && !"simulator".equals(pilot.getProperty("mode"))) {
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
            stage = lod ? 40 : 20;
            var settings = new LevelSettings(save, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(save, settings, new WorldOptions(20261104L, false, false),
                    registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
            return;
        }
        if (stage < 0) return;
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
