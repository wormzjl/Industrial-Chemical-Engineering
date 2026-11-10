package com.wormzjl.icheme.client.fx;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wormzjl.icheme.IChemE;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * The mod's own core shaders (CUSTOM_SHADER_SYSTEM_PLAN.md sections 4.1 and 4.4), {@code assets/icheme/shaders/core/}.
 * Used only on the SHADER path, that is with no shader pack in use; Iris does not intercept a mod's own shader.
 * <ul>
 * <li>{@code icheme:fx_flame}: flame ribbons, procedural (no texture).</li>
 * <li>{@code icheme:fx_plume}: smoke and plume puffs, procedural, lit by {@code Daylight} and {@code SunDirection}.</li>
 * <li>{@code icheme:fx_haze}: heat shimmer over a screen copy in {@code Sampler0}, which {@link FxBatch} takes before the haze draws (W8).</li>
 * <li>{@code icheme:rendertype_wiring}: the instrument wiring, the entity cutout shader plus {@code NormalMap} and
 * {@code SpecularMap} (W2 writes its lighting).</li>
 * </ul>
 * The three effect shaders take {@link FxPackedSink}'s packed {@code NEW_ENTITY} layout; the wiring takes plain
 * {@code NEW_ENTITY}. Reloaded with the resources: each reload clears all four and registers them again. A shader that
 * fails to compile or link is logged and leaves {@link #failed()} true, so every effect takes the COMPATIBLE path.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class FxShaders {
    private static final Logger LOGGER = LoggerFactory.getLogger("icheme/fx");
    public static final ResourceLocation FLAME = id("fx_flame"), PLUME = id("fx_plume"), HAZE = id("fx_haze"), WIRING = id("rendertype_wiring");

    private static volatile @Nullable ShaderInstance flame, plume, haze, wiring;

    private FxShaders() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, path);
    }

    @SubscribeEvent
    public static void register(RegisterShadersEvent event) {
        flame = plume = haze = wiring = null;
        register(event, FLAME, FxPackedSink.FORMAT, s -> flame = s);
        register(event, PLUME, FxPackedSink.FORMAT, s -> plume = s);
        register(event, HAZE, FxPackedSink.FORMAT, s -> haze = s);
        register(event, WIRING, DefaultVertexFormat.NEW_ENTITY, s -> wiring = s);
    }

    private static void register(RegisterShadersEvent event, ResourceLocation name, VertexFormat format, Consumer<ShaderInstance> loaded) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), name, format), loaded);
        } catch (Exception | LinkageError e) {
            LOGGER.warn("IChemE effects: shader {} did not load; effects use vanilla render types", name, e);
        }
    }

    /** True until every shader has loaded, and after any of them failed: the effects then take the COMPATIBLE path. */
    public static boolean failed() {
        return flame == null || plume == null || haze == null || wiring == null;
    }

    public static @Nullable ShaderInstance flame() {
        return flame;
    }

    public static @Nullable ShaderInstance plume() {
        return plume;
    }

    public static @Nullable ShaderInstance haze() {
        return haze;
    }

    public static @Nullable ShaderInstance wiring() {
        return wiring;
    }

    private static final String[] SAMPLERS = new String[12];
    /** Texture ids boxed once: {@code setSampler} keeps an Object, and {@code Integer.valueOf} boxes ids above 127 a call. */
    private static Integer[] boxed = new Integer[1024];

    static {
        for (int i = 0; i < SAMPLERS.length; i++) SAMPLERS[i] = "Sampler" + i;
    }

    /**
     * As {@code ShaderInstance.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, window)} (NeoForge 21.1,
     * Minecraft 1.21.1): the same samplers and uniforms from the same sources in the same order, without its twelve
     * {@code "Sampler" + i} concatenations a call (about 600 B; FX_BENCH_REVIEW.md, {@code FxBatch.drawShader}). No line
     * width: that is for lines only. Render thread only.
     */
    public static void defaultUniforms(ShaderInstance shader, Matrix4f modelView, Matrix4f projection, Window window) {
        for (int i = 0; i < SAMPLERS.length; i++) shader.setSampler(SAMPLERS[i], box(RenderSystem.getShaderTexture(i)));
        if (shader.MODEL_VIEW_MATRIX != null) shader.MODEL_VIEW_MATRIX.set(modelView);
        if (shader.PROJECTION_MATRIX != null) shader.PROJECTION_MATRIX.set(projection);
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (shader.GLINT_ALPHA != null) shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        if (shader.FOG_START != null) shader.FOG_START.set(RenderSystem.getShaderFogStart());
        if (shader.FOG_END != null) shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (shader.FOG_COLOR != null) shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (shader.FOG_SHAPE != null) shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if (shader.TEXTURE_MATRIX != null) shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (shader.GAME_TIME != null) shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if (shader.SCREEN_SIZE != null) shader.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
        RenderSystem.setupShaderLights(shader);
    }

    private static Integer box(int id) {
        if (id < 0) return id;
        if (id >= boxed.length) {
            if (id >= 1 << 20) return id;
            boxed = java.util.Arrays.copyOf(boxed, Math.max(id + 1, 2 * boxed.length));
        }
        var b = boxed[id];
        if (b == null) b = boxed[id] = id;
        return b;
    }
}
