package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/** Shared translucent liquid drawing for tank gauges and open basins, called only on the render thread. */
final class LiquidRenderer {
    static final RenderType TYPE = RenderType.entityTranslucentCull(InventoryMenu.BLOCK_ATLAS);
    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("block/white_concrete");

    private LiquidRenderer() {}

    static TextureAtlasSprite sprite() {
        return Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(WHITE);
    }

    /** Draw the supplied material opacity without imposing a minimum on clear liquid surfaces. */
    static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, int argb,
                       float u, float v, int light, float nx, float ny, float nz) {
        consumer.addVertex(pose, x, y, z).setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }
}
