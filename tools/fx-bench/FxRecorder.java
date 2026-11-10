package com.wormzjl.icheme.mcpcompat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Effects recorder (batch 2026-11-10-custom-shader-system, owner 2026-11-10: "Record these animations"). Measurement tool,
 * attached beside {@code FrameTimeProbe} by {@code tools/fx-bench/record.sh} only, never in {@code src/main}.
 *
 * <p>Active when {@code <game dir>/fps-autopilot/record.txt} exists. It waits until the probe's {@code mode=fx} view window
 * has put the spectator at the view point (the scene, the feed and the drums' instruments are then in place), then flies to
 * each shot of {@code record.txt} ({@code name x y z yaw pitch seconds fps settle}), waits {@code settle} s and captures
 * {@code seconds × fps} frames of the main render target at half size into {@code <game dir>/fx-record/<name>/}, plus the
 * first frame at full size. The GUI is hidden while it records. It logs {@code FX_RECORD} lines and ends before the probe's
 * next window.
 */
@EventBusSubscriber(modid = "icheme_mcp_compat", value = Dist.CLIENT)
public final class FxRecorder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double VIEW_X = .5, VIEW_Y = -40, VIEW_Z = -30;
    private record Shot(String name, double x, double y, double z, float yaw, float pitch, double seconds, int fps, double settle) {}

    private static List<Shot> shots;
    private static boolean done;
    private static int index = -1;
    private static long armedAt, shotAt;
    private static int frame;
    private static ExecutorService writer;

    private FxRecorder() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (done || mc.player == null || mc.level == null) return;
        if (shots == null) {
            var file = mc.gameDirectory.toPath().resolve("fps-autopilot/record.txt");
            if (!Files.exists(file)) { done = true; return; }
            shots = read(file);
            LOGGER.info("FX_RECORD armed shots={}", shots.size());
        }
        long now = System.nanoTime();
        if (index < 0) {
            boolean atView = mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR
                    && Math.abs(mc.player.getX() - VIEW_X) < .6 && Math.abs(mc.player.getY() - VIEW_Y) < .6 && Math.abs(mc.player.getZ() - VIEW_Z) < .6;
            if (!atView) { armedAt = 0; return; }
            if (armedAt == 0) { armedAt = now; return; }
            if (now - armedAt < 5_000_000_000L) return;
            writer = Executors.newFixedThreadPool(4);
            mc.options.hideGui = true;
            next(mc, now);
        }
    }

    private static void next(Minecraft mc, long now) {
        index++;
        frame = 0;
        if (index >= shots.size()) {
            done = true;
            mc.options.hideGui = false;
            writer.shutdown();
            try { writer.awaitTermination(60, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            LOGGER.info("FX_RECORD done shots={}", shots.size());
            return;
        }
        var s = shots.get(index);
        mc.player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", s.x, s.y, s.z, s.yaw, s.pitch));
        shotAt = now;
        LOGGER.info("FX_RECORD shot={} at={} {} {} yaw={} pitch={}", s.name, s.x, s.y, s.z, s.yaw, s.pitch);
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (done || index < 0 || index >= shots.size() || mc.player == null) return;
        var s = shots.get(index);
        long now = System.nanoTime();
        double t = (now - shotAt) / 1e9 - s.settle;
        if (t < 0) return;
        int frames = (int) Math.round(s.seconds * s.fps);
        if (frame >= frames) { next(mc, now); return; }
        if (t < (double) frame / s.fps) return;
        var target = mc.getMainRenderTarget();
        NativeImage full = Screenshot.takeScreenshot(target);
        var dir = mc.gameDirectory.toPath().resolve("fx-record").resolve(s.name);
        int n = frame++;
        double at = t;
        writer.submit(() -> {
            try {
                Files.createDirectories(dir);
                if (n == 0) full.writeToFile(dir.resolve("full.png"));
                var half = new NativeImage(full.getWidth() / 2, full.getHeight() / 2, false);
                full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), half);
                half.writeToFile(dir.resolve(String.format(Locale.ROOT, "f%03d.png", n)));
                half.close();
                Files.writeString(dir.resolve("times.txt"), String.format(Locale.ROOT, "%d %.3f%n", n, at), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                LOGGER.warn("FX_RECORD write failed {}", dir, e);
            } finally {
                full.close();
            }
        });
    }

    private static List<Shot> read(Path file) {
        var out = new ArrayList<Shot>();
        try {
            for (var line : Files.readAllLines(file)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                var p = line.split("\\s+");
                out.add(new Shot(p[0], Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Float.parseFloat(p[4]),
                        Float.parseFloat(p[5]), Double.parseDouble(p[6]), Integer.parseInt(p[7]), Double.parseDouble(p[8])));
            }
        } catch (Exception e) {
            LOGGER.warn("FX_RECORD cannot read {}", file, e);
        }
        return out;
    }
}
