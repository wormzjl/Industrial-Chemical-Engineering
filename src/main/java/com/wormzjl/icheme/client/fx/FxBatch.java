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
 * Draws every registered {@link FxEmitter} at {@code AFTER_PARTICLES} (CUSTOM_SHADER_SYSTEM_PLAN.md sections 4.1, 4.6
 * and 4.8). Render thread only.
 * <ul>
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
    /** The haze first, over a copy of the scene without our effects; then flames, then smoke and plumes. */
    private static final FxKind[][] SHADER_GROUPS = {{FxKind.HAZE}, {FxKind.FLAME}, {FxKind.SMOKE, FxKind.PLUME}};
    /** The heat haze's screen copy (W8): made at the first frame with haze, resized with the window. */
    private static @Nullable TextureTarget screenCopy;

    /** A cached buffer; {@code bytes} its vertices' size, for {@link FxStats.Counter#VERTEX_BYTES}. */
    private record Built(long revision, int quads, @Nullable VertexBuffer buffer, long bytes) {
    }

    private record Visible(FxEmitter emitter, int quads) {
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || EMITTERS.isEmpty()) return;
        long started = System.nanoTime();
        BATCH.run(() -> draw(event));
        FxStats.add(FxStats.Counter.DRAW_NS, System.nanoTime() - started);
        FxStats.set(FxStats.Counter.REGISTERED, EMITTERS.size());
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

        var candidates = new ArrayList<FxEmitter>();
        var pixels = new ArrayList<Double>();
        // Heat haze: the SHADER path only, and only with heat_haze on (owner X6 A).
        boolean haze = path == FxPath.SHADER && ClientConfig.heatHaze();
        for (var emitter : EMITTERS) {
            if (FxGuard.effect(emitter.type()).disabled() || emitter.kind() == FxKind.HAZE && !haze) continue;
            double x = emitter.x(), y = emitter.y(), z = emitter.z(), r = Math.max(.1, emitter.radius());
            double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z, distanceSqr = dx * dx + dy * dy + dz * dz;
            if (distanceSqr > (reach + r) * (reach + r)) continue;
            if (!frustum.isVisible(new AABB(x - r, y - r, z - r, x + r, y + r, z + r))) continue;
            candidates.add(emitter);
            pixels.add(view.pixels(2 * r, distanceSqr));
        }
        if (candidates.isEmpty()) return;
        double[] px = new double[candidates.size()];
        int[] wanted = new int[candidates.size()];
        for (int i = 0; i < px.length; i++) {
            px[i] = pixels.get(i);
            wanted[i] = candidates.get(i).quads();
        }
        double density = path == FxPath.COMPATIBLE && iris.packInUse() ? FxBudget.PACK_DENSITY : 1;
        int[] allotted = FxBudget.allot(px, wanted, density, FxBudget.FRAME_QUADS);
        var visible = new ArrayList<Visible>();
        for (int i = 0; i < allotted.length; i++) {
            int quads = path == FxPath.SHADER ? FxBudget.quantize(allotted[i], wanted[i]) : allotted[i];
            if (quads > 0) visible.add(new Visible(candidates.get(i), quads));
        }
        if (visible.isEmpty()) return;
        FxStats.add(FxStats.Counter.EMITTERS, visible.size());

        double daylight = level.getSkyDarken(partial);
        double sunAngle = level.getSunAngle(partial);
        float sunX = (float) -Math.sin(sunAngle), sunY = (float) Math.cos(sunAngle);
        if (path == FxPath.SHADER) {
            for (var group : SHADER_GROUPS) drawShader(event, group, visible, now, epoch, camera.x, camera.y, camera.z, daylight, sunX, sunY);
        } else {
            for (var kind : FxKind.values()) drawCompatible(event, kind, visible, now, camera.x, camera.y, camera.z, daylight);
        }
    }

    private static void drawShader(RenderLevelStageEvent event, FxKind[] kinds, List<Visible> visible, double now, double epoch,
                                   double cx, double cy, double cz, double daylight, float sunX, float sunY) {
        List<Visible> group = new ArrayList<>();
        for (var v : visible) for (var kind : kinds) if (v.emitter().kind() == kind) group.add(v);
        if (group.isEmpty()) return;
        var type = FxRenderTypes.shader(kinds[0]);
        var window = Minecraft.getInstance().getWindow();
        ShaderInstance shader = null;
        boolean setUp = false;
        try {
            type.setupRenderState();
            setUp = true;
            if (kinds[0] == FxKind.HAZE) RenderSystem.setShaderTexture(0, copyScreen(window.getWidth(), window.getHeight()));
            shader = RenderSystem.getShader();
            if (shader == null) return;
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, event.getModelViewMatrix(), event.getProjectionMatrix(), window);
            uniform(shader, "FxTime", false, (float) (now - epoch));
            uniform(shader, "GlideSeconds", false, (float) FxGlide.SECONDS);
            uniform(shader, "Daylight", false, (float) daylight);
            uniform(shader, "SunDirection", false, sunX, sunY, 0F);
            uniform(shader, "ScreenSize", false, (float) window.getWidth(), (float) window.getHeight());
            shader.apply();
            for (var v : group) {
                var emitter = v.emitter();
                var buffer = buffer(emitter, v.quads());
                if (buffer == null) continue;
                uniform(shader, "Origin", true, (float) (emitter.x() - cx), (float) (emitter.y() - cy), (float) (emitter.z() - cz));
                uniform(shader, "DriftPeriod", true, (float) emitter.driftPeriod());
                // A glide that never started (negative infinity) is settled: any start far enough back.
                uniform(shader, "GlideStart", true, (float) Math.max(emitter.glideStart() - epoch, -1e6));
                buffer.bind();
                buffer.draw();
                FxStats.add(FxStats.Counter.QUADS, v.quads());
            }
        } finally {
            if (shader != null) shader.clear();
            VertexBuffer.unbind();
            if (setUp) type.clearRenderState();
        }
    }

    private static void drawCompatible(RenderLevelStageEvent event, FxKind kind, List<Visible> visible, double now,
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
            for (var v : visible) {
                var emitter = v.emitter();
                if (emitter.kind() != kind) continue;
                var consumer = buffers.getBuffer(type);
                wrote = true;
                double[] origin = {emitter.x() - cx, emitter.y() - cy, emitter.z() - cz};
                var sink = new FxImmediateSink(consumer, pose, origin, camera.getLeftVector(), camera.getUpVector(),
                        FxGlide.ease(emitter.glideStart(), now), now, daylight, emitter.driftPeriod(), kind, emitter.light(), v.quads());
                FxGuard.effect(emitter.type()).run(() -> emitter.write(sink, v.quads()));
                FxStats.add(FxStats.Counter.QUADS, v.quads() - sink.remaining());
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
        var dead = new ArrayList<FxEmitter>();
        for (var emitter : EMITTERS) {
            boolean alive = FxGuard.effect(emitter.type()).test(emitter::alive, false);
            if (!alive) dead.add(emitter);
        }
        for (var emitter : dead) remove(emitter);
    }

    private static void release(FxEmitter emitter) {
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

    /** Sets a float uniform if the shader has it; after {@code apply()} it must be uploaded at once. */
    private static void uniform(ShaderInstance shader, String name, boolean upload, float... values) {
        var u = shader.getUniform(name);
        if (u == null) return;
        if (values.length == 1) u.set(values[0]);
        else if (values.length == 2) u.set(values[0], values[1]);
        else u.set(values[0], values[1], values[2]);
        if (upload) u.upload();
    }
}
