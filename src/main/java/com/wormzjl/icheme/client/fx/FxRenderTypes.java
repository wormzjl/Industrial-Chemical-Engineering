package com.wormzjl.icheme.client.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.wormzjl.icheme.IChemE;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The effects' render types (CUSTOM_SHADER_SYSTEM_PLAN.md sections 4.1 and 4.4) and the textures the COMPATIBLE path
 * samples (authored in phase 2, {@code assets/icheme/textures/fx/}):
 * <ul>
 * <li>{@link #FLAME_FLIPBOOK}: {@link #FLAME_FRAMES} frames stacked top to bottom, the flame's tip at each frame's
 * top, with a LabPBR {@code _s} beside it whose alpha carries emission.</li>
 * <li>{@link #SMOKE}, {@link #PLUME}: four soft puffs in a 2 × 2 grid, white albedo (the vertex colour tints).</li>
 * </ul>
 * SHADER path: our own shaders, premultiplied alpha, depth test without depth write, no culling, no lightmap (the flame
 * is full bright, the plume shader lights itself). COMPATIBLE path: {@code entityTranslucentEmissive} for flames and
 * {@code entityTranslucentCull} for smoke and plumes, which draw correctly at {@code AFTER_PARTICLES} with or without
 * a shader pack ({@code EquipmentGhost}).
 */
public final class FxRenderTypes {
    public static final ResourceLocation FLAME_FLIPBOOK = texture("flame_flipbook"), SMOKE = texture("smoke"), PLUME = texture("plume");
    public static final int FLAME_FRAMES = 8;

    /** One-minus-source-alpha over a colour already multiplied by its alpha: glowing cores add, soot covers. */
    private static final RenderStateShard.TransparencyStateShard PREMULTIPLIED = new RenderStateShard.TransparencyStateShard("icheme_premultiplied",
            () -> {
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            },
            () -> {
                RenderSystem.disableBlend();
                RenderSystem.defaultBlendFunc();
            });

    private static final RenderType SHADER_FLAME = effect("icheme_fx_flame", new RenderStateShard.ShaderStateShard(FxShaders::flame));
    private static final RenderType SHADER_PLUME = effect("icheme_fx_plume", new RenderStateShard.ShaderStateShard(FxShaders::plume));
    private static final Map<ResourceLocation, RenderType> WIRING = new ConcurrentHashMap<>();

    private FxRenderTypes() {
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "textures/fx/" + name + ".png");
    }

    private static RenderType effect(String name, RenderStateShard.ShaderStateShard shader) {
        return RenderType.create(name, FxPackedSink.FORMAT, VertexFormat.Mode.QUADS, 1536, false, false,
                RenderType.CompositeState.builder()
                        .setShaderState(shader)
                        .setTextureState(RenderStateShard.NO_TEXTURE)
                        .setTransparencyState(PREMULTIPLIED)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

    /** The SHADER path's type for {@code kind}: its shader state is set up before a cached buffer draws. */
    public static RenderType shader(FxKind kind) {
        return kind == FxKind.FLAME ? SHADER_FLAME : SHADER_PLUME;
    }

    /** The COMPATIBLE path's type for {@code kind}. */
    public static RenderType compatible(FxKind kind) {
        return switch (kind) {
            case FLAME -> RenderType.entityTranslucentEmissive(FLAME_FLIPBOOK);
            case SMOKE -> RenderType.entityTranslucentCull(SMOKE);
            case PLUME -> RenderType.entityTranslucentCull(PLUME);
        };
    }

    /**
     * The SHADER path's wiring type on {@code texture} (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.4): the entity cutout state
     * (no blend, depth write, no culling, lightmap) on {@code icheme:rendertype_wiring}, which W2 also binds
     * {@code NormalMap} and {@code SpecularMap} for. Under a pack W2 uses {@code RenderType.entityCutoutNoCull(texture)}.
     */
    public static RenderType wiring(ResourceLocation texture) {
        return WIRING.computeIfAbsent(texture, t -> RenderType.create("icheme_wiring", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1536,
                true, false, RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(FxShaders::wiring))
                        .setTextureState(new RenderStateShard.TextureStateShard(t, false, false))
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .createCompositeState(true)));
    }
}
