package com.wormzjl.icheme.client.fx;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * The mod's own core shaders (CUSTOM_SHADER_SYSTEM_PLAN.md sections 4.1 and 4.4), {@code assets/icheme/shaders/core/}.
 * Used only on the SHADER path, that is with no shader pack in use; Iris does not intercept a mod's own shader.
 * <ul>
 * <li>{@code icheme:fx_flame}: flame ribbons, procedural (no texture).</li>
 * <li>{@code icheme:fx_plume}: smoke and plume puffs, procedural, lit by {@code Daylight} and {@code SunDirection}.</li>
 * <li>{@code icheme:fx_haze}: heat shimmer over a screen copy in {@code Sampler0} (W8 writes its GLSL).</li>
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
}
