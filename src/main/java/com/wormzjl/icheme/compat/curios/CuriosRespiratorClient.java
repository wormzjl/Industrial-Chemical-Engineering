package com.wormzjl.icheme.compat.curios;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wormzjl.icheme.registry.ModItems;
import java.util.function.Supplier;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * Draws the shared respirator model from a Curios slot, posed as the wearer's head. The client registers it only
 * when Curios is installed.
 */
public final class CuriosRespiratorClient implements ICurioRenderer {
    private final Supplier<ModelPart> bakedHead;
    private final ResourceLocation texture;

    private CuriosRespiratorClient(Supplier<ModelPart> bakedHead, ResourceLocation texture) {
        this.bakedHead = bakedHead;
        this.texture = texture;
    }

    /** Registers the renderer with the shared texture and a supplier for the current baked head after any reload. */
    public static void register(Supplier<ModelPart> bakedHead, ResourceLocation texture) {
        CuriosRendererRegistry.register(ModItems.RESPIRATOR.get(), () -> new CuriosRespiratorClient(bakedHead, texture));
    }

    @Override
    public <T extends LivingEntity, M extends net.minecraft.client.model.EntityModel<T>> void render(ItemStack stack, SlotContext slotContext,
            PoseStack pose, RenderLayerParent<T, M> parent, MultiBufferSource buffers, int light, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        var entity = slotContext.entity();
        if (!slotContext.visible() || entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(ModItems.RESPIRATOR.get())) {
            return;
        }
        var head = bakedHead.get();
        ICurioRenderer.followHeadRotations(entity, head);
        var buffer = buffers.getBuffer(RenderType.armorCutoutNoCull(texture));
        head.render(pose, buffer, light, OverlayTexture.NO_OVERLAY);
    }
}
