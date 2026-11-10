package com.wormzjl.icheme.client.fx;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws every registered {@link FxEmitter} (CUSTOM_SHADER_SYSTEM_PLAN.md sections 4.1, 4.6 and 4.8). Render thread only.
 * <ul>
 * <li><b>Stage.</b> {@code AFTER_WEATHER}: vanilla has drawn the clouds (with their depth) and the rain by then, so clouds
 * no longer paint over the effects in front of them (visual review 2026-11-10, item 3). Under Fabulous graphics (that stage
 * runs inside the weather target) and under a shader pack (whose pipeline was checked at {@code AFTER_PARTICLES}) the
 * batch stays at {@code AFTER_PARTICLES}.</li>
 * <li><b>Order.</b> The haze first, over the frame without effects; then flames, smoke and plumes together, the farthest
 * emitter first, switching shader only where the kind changes, so a far plume never covers a near flame (item 4).</li>
 * <li><b>Path.</b> Chosen once per frame ({@link FxPath}, at {@link RenderFrameEvent.Pre}). OFF draws nothing; nothing
 * is drawn while Iris renders a shadow pass.</li>
 * <li><b>Selection and budget.</b> Emitters within the vanilla render distance and in the frustum, ranked by the pixels
 * their radius covers ({@code RenderLod}), share {@link FxBudget#FRAME_QUADS}; half density under a shader pack.</li>
 * <li><b>SHADER path.</b> Each emitter's quads live in a cached {@link VertexBuffer} ({@link FxPackedSink}), rebuilt only
 * when its {@link FxEmitter#revision()} or its allotment step changes; per frame the batch sets the shader once per kind
 * and per emitter only {@code Origin} and {@code GlideStart}. The vertex shader eases, faces the camera and flickers.</li>
 * <li><b>Heat haze</b> (W8, owner X6 A). SHADER path only and only with {@code heat_haze} on: before the haze sprites draw,
 * one blit copies the frame so far ({@link #copyScreen}), which {@code fx_haze} reads a few pixels off.</li>
 * <li><b>Travelling puffs.</b> Each emitter's {@link FxEmitter#driftPeriod()} is the per-draw uniform {@code DriftPeriod}.</li>
 * <li><b>COMPATIBLE path.</b> Quads are written every frame ({@link FxImmediateSink}) into the vanilla buffer source,
 * one batch per render type.</li>
 * <li><b>Failures.</b> An emitter that throws turns off its type ({@link FxGuard#effect}); a failure in the batch itself
 * turns the batch off for the session. GL state and buffers are released in {@code finally}; no exception leaves the
 * event.</li>
 * <li><b>Counters</b> (FX_BENCH_PLAN.md): {@link FxStats} starts each frame here; the draw adds its quads, emitters,
 * rebuilds, build and draw time and the cached buffers' bytes.</li>
 * </ul>
 * Shader time is seconds of game time since an epoch that moves every {@link #EPOCH_SECONDS}, so a float keeps
 * millisecond steps however old the world.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class FxBatch {
    /** The shader clock's epoch step, s: float time stays finer than 0.5 ms below it. */
    static final double EPOCH_SECONDS = 4096;
    private static final FxGuard BATCH = new FxGuard("effects batch", () -> "IChemE");
    private static final FxGuard PATH = new FxGuard("effects path", () -> "IChemE");
    private static final Set<FxEmitter> EMITTERS = new LinkedHashSet<>();
    private static final Map<FxEmitter, Built> BUILT = new IdentityHashMap<>();
    /** The heat haze's screen copy (W8): made at the first frame with haze, resized with the window. */
    private static @Nullable TextureTarget screenCopy;

    /** A cached buffer; {@code bytes} its vertices' size, for {@link FxStats.Counter#VERTEX_BYTES}. */
    private record Built(long revision, int quads, @Nullable VertexBuffer buffer, long bytes) {
    }

    // ---- The per-frame working space (render thread only), reused so that a frame allocates nothing (bench 2026-11-10,
    // FX_BENCH_REVIEW.md: about 23 KB a frame went to lists, records, boxed pixels, the comparator and streams) ----
    private static FxEmitter[] candidates = new FxEmitter[64], visible = new FxEmitter[64];
    private static double[] pixels = new double[64], distance = new double[64];
    private static int[] wanted = new int[64], allotted = new int[64], order = new int[64], scratch = new int[64], quadsOf = new int[64];
    /** Indices into {@link #visible}: farthest first ({@code sorted}); a run's members ({@code members}). */
    private static int[] sorted = new int[64], members = new int[64];
    /** Each emitter's frustum box, kept while its centre and radius hold. */
    private static final Map<FxEmitter, AABB> BOUNDS = new IdentityHashMap<>();
    private static final List<FxEmitter> DEAD = new ArrayList<>();
    private static @Nullable RenderLevelStageEvent drawing;
    private static final Runnable DRAW = () -> draw(drawing);
    private static @Nullable FxEmitter probing, writing;
    private static final java.util.function.BooleanSupplier ALIVE = () -> probing.alive();
    private static @Nullable FxImmediateSink sink;
    private static int writingQuads;
    private static final Runnable WRITE = () -> writing.write(sink, writingQuads);

    /** Grows the working space to hold {@code n} emitters. */
    private static void room(int n) {
        if (candidates.length >= n) return;
        int size = Math.max(n, 2 * candidates.length);
        candidates = new FxEmitter[size];
        visible = new FxEmitter[size];
        pixels = new double[size];
        distance = new double[size];
        wanted = new int[size];
        allotted = new int[size];
        order = new int[size];
        scratch = new int[size];
        quadsOf = new int[size];
        sorted = new int[size];
        members = new int[size];
    }

    private FxBatch() {
    }

    /** Registers {@code emitter} until it reports {@code alive() == false} or is {@link #remove}d. */
    public static void add(FxEmitter emitter) {
        EMITTERS.add(emitter);
    }

    public static void remove(FxEmitter emitter) {
        EMITTERS.remove(emitter);
        release(emitter);
    }

    /** Emitters registered now. */
    public static int size() {
        return EMITTERS.size();
    }

    /** Drops every emitter and frees every buffer. */
    public static void clear() {
        EMITTERS.clear();
        for (var built : BUILT.values()) if (built.buffer() != null) built.buffer().close();
        BUILT.clear();
        BOUNDS.clear();
        if (screenCopy != null) screenCopy.destroyBuffers();
        screenCopy = null;
        FxStats.set(FxStats.Counter.VERTEX_BYTES, 0);
        FxStats.set(FxStats.Counter.TEXTURE_BYTES, 0);
        FxStats.set(FxStats.Counter.REGISTERED, 0);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            clear();
        } catch (LinkageError | RuntimeException ignored) {
            // Buffers already gone with the GL context.
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Pre event) {
        if (!PATH.run(FxPath::refresh)) FxPath.fallback();
        FxStats.frame(FxPath.current());
    }

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (EMITTERS.isEmpty() || event.getStage() != stage()) return;
        long started = System.nanoTime();
        drawing = event;
        try {
            BATCH.run(DRAW);
        } finally {
            drawing = null;
        }
        FxStats.add(FxStats.Counter.DRAW_NS, System.nanoTime() - started);
        FxStats.set(FxStats.Counter.REGISTERED, EMITTERS.size());
    }

    /** The stage the batch draws at: after the clouds and the rain, except under Fabulous graphics or a shader pack. */
    static RenderLevelStageEvent.Stage stage() {
        return Minecraft.useShaderTransparency() || FxCompat.iris().packInUse() ? RenderLevelStageEvent.Stage.AFTER_PARTICLES
                : RenderLevelStageEvent.Stage.AFTER_WEATHER;
    }

    /** The shader group a kind draws in: flames apart from smoke and plumes, which share {@code fx_plume}; haze alone. */
    static int group(FxKind kind) {
        return switch (kind) {
            case HAZE -> 0;
            case FLAME -> 1;
            case SMOKE, PLUME -> 2;
        };
    }

    /** The key a run shares: a shader group under the SHADER path ({@code byGroup}), or a kind under the COMPATIBLE path. */
    static int runKey(FxKind kind, boolean byGroup) {
        return byGroup ? group(kind) : kind.ordinal();
    }

    private static void draw(RenderLevelStageEvent event) {
        var path = FxPath.current();
        if (path == FxPath.OFF) return;
        var iris = FxCompat.iris();
        if (iris.shadowPass()) return;
        var mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        prune();
        if (EMITTERS.isEmpty()) return;

        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double now = FxGlide.seconds(level.getGameTime(), partial);
        double epoch = Math.floor(now / EPOCH_SECONDS) * EPOCH_SECONDS;
        var camera = event.getCamera().getPosition();
        var frustum = event.getFrustum();
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        var view = ClientConfig.lodView(event.getProjectionMatrix().m11(), mc.getWindow().getHeight());

        room(EMITTERS.size());
        int count = 0, shown = 0;
        try {
            // Heat haze: the SHADER path only, and only with heat_haze on (owner X6 A).
            boolean haze = path == FxPath.SHADER && ClientConfig.heatHaze();
            for (var emitter : EMITTERS) {
                if (FxGuard.effect(emitter.type()).disabled() || emitter.kind() == FxKind.HAZE && !haze) continue;
                double x = emitter.x(), y = emitter.y(), z = emitter.z(), r = Math.max(.1, emitter.radius());
                double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z, distanceSqr = dx * dx + dy * dy + dz * dz;
                if (distanceSqr > (reach + r) * (reach + r)) continue;
                if (!frustum.isVisible(bounds(emitter, x, y, z, r))) continue;
                candidates[count] = emitter;
                pixels[count] = view.pixels(2 * r, distanceSqr);
                wanted[count] = emitter.quads();
                count++;
            }
            if (count == 0) return;
            double density = path == FxPath.COMPATIBLE && iris.packInUse() ? FxBudget.PACK_DENSITY : 1;
            FxBudget.allot(pixels, wanted, count, density, FxBudget.FRAME_QUADS, allotted, order, scratch);
            double cx = camera.x, cy = camera.y, cz = camera.z;
            for (int i = 0; i < count; i++) {
                int quads = path == FxPath.SHADER ? FxBudget.quantize(allotted[i], wanted[i]) : allotted[i];
                if (quads <= 0) continue;
                visible[shown] = candidates[i];
                quadsOf[shown] = quads;
                distance[shown] = distanceSqr(candidates[i], cx, cy, cz);
                sorted[shown] = shown;
                shown++;
            }
            if (shown == 0) return;
            FxStats.add(FxStats.Counter.EMITTERS, shown);
            // The farthest first: translucent effects that write no depth must be drawn back to front.
            farthestFirst(sorted, shown, distance, scratch);

            double daylight = level.getSkyDarken(partial);
            double sunAngle = level.getSunAngle(partial);
            float sunX = (float) -Math.sin(sunAngle), sunY = (float) Math.cos(sunAngle);
            if (path == FxPath.SHADER) {
                int n = 0;
                for (int k = 0; k < shown; k++) if (visible[sorted[k]].kind() == FxKind.HAZE) members[n++] = sorted[k];
                if (n > 0) drawShader(event, FxKind.HAZE, members, 0, n, now, epoch, cx, cy, cz, daylight, sunX, sunY);
                n = 0;
                for (int k = 0; k < shown; k++) if (visible[sorted[k]].kind() != FxKind.HAZE) members[n++] = sorted[k];
                for (int from = 0, to; from < n; from = to) {
                    int key = runKey(visible[members[from]].kind(), true);
                    for (to = from + 1; to < n && runKey(visible[members[to]].kind(), true) == key; to++) ;
                    drawShader(event, visible[members[from]].kind(), members, from, to, now, epoch, cx, cy, cz, daylight, sunX, sunY);
                }
            } else {
                for (int from = 0, to; from < shown; from = to) {
                    int key = runKey(visible[sorted[from]].kind(), false);
                    for (to = from + 1; to < shown && runKey(visible[sorted[to]].kind(), false) == key; to++) ;
                    drawCompatible(event, visible[sorted[from]].kind(), sorted, from, to, now, cx, cy, cz, daylight);
                }
            }
        } finally {
            // Hold no emitter past the frame.
            java.util.Arrays.fill(candidates, 0, count, null);
            java.util.Arrays.fill(visible, 0, shown, null);
        }
    }

    /** The frustum box of {@code emitter} about (x, y, z) with radius {@code r}: the kept one while it still matches. */
    private static AABB bounds(FxEmitter emitter, double x, double y, double z, double r) {
        var box = BOUNDS.get(emitter);
        if (box != null && box.minX == x - r && box.minY == y - r && box.minZ == z - r && box.maxX == x + r && box.maxY == y + r && box.maxZ == z + r)
            return box;
        box = new AABB(x - r, y - r, z - r, x + r, y + r, z + r);
        BOUNDS.put(emitter, box);
        return box;
    }

    /**
     * Sorts {@code index[0, n)} farthest first by {@code distance} (as {@code Double.compare}, reversed); stable, so equal
     * distances keep their order, as the list sort this replaced. Insertion runs of 16, then merges through {@code scratch}.
     */
    static void farthestFirst(int[] index, int n, double[] distance, int[] scratch) {
        final int run = 16;
        for (int from = 0; from < n; from += run) {
            int to = Math.min(n, from + run);
            for (int i = from + 1; i < to; i++) {
                int v = index[i], j = i - 1;
                while (j >= from && Double.compare(distance[index[j]], distance[v]) < 0) {
                    index[j + 1] = index[j];
                    j--;
                }
                index[j + 1] = v;
            }
        }
        int[] a = index, b = scratch;
        for (int width = run; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(n, lo + width), hi = Math.min(n, lo + 2 * width), i = lo, j = mid, k = lo;
                while (i < mid && j < hi) b[k++] = Double.compare(distance[a[i]], distance[a[j]]) < 0 ? a[j++] : a[i++];
                while (i < mid) b[k++] = a[i++];
                while (j < hi) b[k++] = a[j++];
            }
            int[] t = a;
            a = b;
            b = t;
        }
        if (a != index) System.arraycopy(a, 0, index, 0, n);
    }

    private static double distanceSqr(FxEmitter e, double cx, double cy, double cz) {
        double dx = e.x() - cx, dy = e.y() - cy, dz = e.z() - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Draws {@link #visible}{@code [run[from, to)]} on the SHADER path with {@code kind}'s shader. */
    private static void drawShader(RenderLevelStageEvent event, FxKind kind, int[] run, int from, int to, double now, double epoch,
                                   double cx, double cy, double cz, double daylight, float sunX, float sunY) {
        if (from >= to) return;
        var type = FxRenderTypes.shader(kind);
        var window = Minecraft.getInstance().getWindow();
        ShaderInstance shader = null;
        boolean setUp = false;
        try {
            type.setupRenderState();
            setUp = true;
            if (kind == FxKind.HAZE) RenderSystem.setShaderTexture(0, copyScreen(window.getWidth(), window.getHeight()));
            shader = RenderSystem.getShader();
            if (shader == null) return;
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), window);
            uniform(shader, "FxTime", false, (float) (now - epoch));
            uniform(shader, "GlideSeconds", false, (float) FxGlide.SECONDS);
            uniform(shader, "Daylight", false, (float) daylight);
            uniform(shader, "SunDirection", false, sunX, sunY, 0F);
            uniform(shader, "ScreenSize", false, (float) window.getWidth(), (float) window.getHeight());
            shader.apply();
            for (int k = from; k < to; k++) {
                var emitter = visible[run[k]];
                int quads = quadsOf[run[k]];
                var buffer = buffer(emitter, quads);
                if (buffer == null) continue;
                uniform(shader, "Origin", true, (float) (emitter.x() - cx), (float) (emitter.y() - cy), (float) (emitter.z() - cz));
                uniform(shader, "DriftPeriod", true, (float) emitter.driftPeriod());
                uniform(shader, "PuffGrowth", true, (float) emitter.puffGrowth());
                // A glide that never started (negative infinity) is settled: any start far enough back.
                uniform(shader, "GlideStart", true, (float) Math.max(emitter.glideStart() - epoch, -1e6));
                buffer.bind();
                buffer.draw();
                FxStats.add(FxStats.Counter.QUADS, quads);
            }
        } finally {
            if (shader != null) shader.clear();
            VertexBuffer.unbind();
            if (setUp) type.clearRenderState();
        }
    }

    /** Writes {@link #visible}{@code [run[from, to)]} of {@code kind} into the vanilla buffer source (COMPATIBLE path). */
    private static void drawCompatible(RenderLevelStageEvent event, FxKind kind, int[] run, int from, int to, double now,
                                       double cx, double cy, double cz, double daylight) {
        var type = FxRenderTypes.compatible(kind);
        if (type == null) return;
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        var stack = event.getPoseStack() == null ? new PoseStack() : event.getPoseStack();
        var pose = stack.last();
        var camera = event.getCamera();
        boolean wrote = false;
        long started = System.nanoTime();
        try {
            for (int k = from; k < to; k++) {
                var emitter = visible[run[k]];
                int quads = quadsOf[run[k]];
                if (emitter.kind() != kind) continue;
                var consumer = buffers.getBuffer(type);
                wrote = true;
                double ox = emitter.x() - cx, oy = emitter.y() - cy, oz = emitter.z() - cz;
                double ease = FxGlide.ease(emitter.glideStart(), now);
                if (sink == null) sink = new FxImmediateSink(consumer, pose, new double[]{ox, oy, oz}, camera.getLeftVector(), camera.getUpVector(),
                        ease, now, daylight, emitter.driftPeriod(), emitter.puffGrowth(), kind, emitter.light(), quads);
                else sink.reset(consumer, pose, ox, oy, oz, camera.getLeftVector(), camera.getUpVector(),
                        ease, now, daylight, emitter.driftPeriod(), emitter.puffGrowth(), kind, emitter.light(), quads);
                writing = emitter;
                writingQuads = quads;
                try {
                    FxGuard.effect(emitter.type()).run(WRITE);
                } finally {
                    writing = null;
                }
                FxStats.add(FxStats.Counter.QUADS, quads - sink.remaining());
            }
        } finally {
            if (wrote) FxStats.add(FxStats.Counter.BUILD_NS, System.nanoTime() - started);
            if (wrote) buffers.endBatch(type);
        }
    }

    /** The emitter's cached buffer for {@code quads}, rebuilt when its revision or allotment changed; null when empty or broken. */
    private static @Nullable VertexBuffer buffer(FxEmitter emitter, int quads) {
        long revision = emitter.revision();
        var built = BUILT.get(emitter);
        if (built != null && built.revision() == revision && built.quads() == quads) return built.buffer();
        long started = System.nanoTime();
        VertexBuffer fresh = null;
        long size = 0;
        try (var bytes = new ByteBufferBuilder(Math.max(256, quads * 4 * FxPackedSink.FORMAT.getVertexSize()))) {
            var builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, FxPackedSink.FORMAT);
            var sink = new FxPackedSink(builder, quads);
            if (FxGuard.effect(emitter.type()).run(() -> emitter.write(sink, quads))) {
                var mesh = builder.build();
                if (mesh != null) {
                    size = mesh.vertexBuffer().remaining();
                    fresh = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    fresh.bind();
                    fresh.upload(mesh);
                    VertexBuffer.unbind();
                }
            }
        }
        if (built != null && built.buffer() != null) built.buffer().close();
        BUILT.put(emitter, new Built(revision, quads, fresh, size));
        FxStats.add(FxStats.Counter.VERTEX_BYTES, size - (built == null ? 0 : built.bytes()));
        FxStats.add(FxStats.Counter.REBUILDS, 1);
        FxStats.add(FxStats.Counter.BUILD_NS, System.nanoTime() - started);
        return fresh;
    }

    private static void prune() {
        try {
            for (var emitter : EMITTERS) {
                probing = emitter;
                boolean alive = FxGuard.effect(emitter.type()).test(ALIVE, false);
                if (!alive) DEAD.add(emitter);
            }
        } finally {
            probing = null;
        }
        if (DEAD.isEmpty()) return;
        for (var emitter : DEAD) remove(emitter);
        DEAD.clear();
    }

    private static void release(FxEmitter emitter) {
        BOUNDS.remove(emitter);
        var built = BUILT.remove(emitter);
        if (built != null && built.buffer() != null) built.buffer().close();
        if (built != null) FxStats.add(FxStats.Counter.VERTEX_BYTES, -built.bytes());
    }

    /**
     * Copies the frame drawn so far into {@link #screenCopy} for the heat haze (W8): one blit of the bound framebuffer's
     * colour (the main target, or Fabulous' particles target), which is bound again after; returns the copy's texture.
     */
    private static int copyScreen(int width, int height) {
        int bound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (screenCopy == null) screenCopy = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        else if (screenCopy.width != width || screenCopy.height != height) screenCopy.resize(width, height, Minecraft.ON_OSX);
        // RGBA8 colour, no depth.
        FxStats.set(FxStats.Counter.TEXTURE_BYTES, 4L * width * height);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, bound);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, screenCopy.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, bound);
        return screenCopy.getColorTextureId();
    }

    /** Sets a float uniform if the shader has it; after {@code apply()} it must be uploaded at once. No varargs: no array a call. */
    private static void uniform(ShaderInstance shader, String name, boolean upload, float a) {
        var u = shader.getUniform(name);
        if (u == null) return;
        u.set(a);
        if (upload) u.upload();
    }

    private static void uniform(ShaderInstance shader, String name, boolean upload, float a, float b) {
        var u = shader.getUniform(name);
        if (u == null) return;
        u.set(a, b);
        if (upload) u.upload();
    }

    private static void uniform(ShaderInstance shader, String name, boolean upload, float a, float b, float c) {
        var u = shader.getUniform(name);
        if (u == null) return;
        u.set(a, b, c);
        if (upload) u.upload();
    }
}
