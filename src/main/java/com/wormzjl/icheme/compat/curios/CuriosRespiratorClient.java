package com.wormzjl.icheme.compat.curios;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wormzjl.icheme.client.RespiratorClient;
import com.wormzjl.icheme.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;
import top.theillusivec4.curios.api.client.ICurioRenderer;

/**
 * The respirator drawn on the head from a Curios slot: the same model and texture as the helmet slot's
 * ({@link RespiratorClient#TEXTURE}), on the outer armour layer's head, following the wearer's head. Client only; reached
 * only after {@code ModList.isLoaded("curios")}.
 */
public final class CuriosRespiratorClient implements ICurioRenderer {
    private HumanoidModel<LivingEntity> model;

    private CuriosRespiratorClient() {
    }

    /** Registers the renderer (client setup). */
    public static void register() {
        CuriosRendererRegistry.register(ModItems.RESPIRATOR.get(), CuriosRespiratorClient::new);
    }

    @Override
    public <T extends LivingEntity, M extends net.minecraft.client.model.EntityModel<T>> void render(ItemStack stack, SlotContext slotContext,
            PoseStack pose, RenderLayerParent<T, M> parent, MultiBufferSource buffers, int light, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch) {
        var entity = slotContext.entity();
        if (!slotContext.visible() || entity.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(ModItems.RESPIRATOR.get())) {
            return;
        }
        if (model == null) {
            model = new HumanoidModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        }
        ICurioRenderer.followHeadRotations(entity, model.head);
        var buffer = buffers.getBuffer(RenderType.armorCutoutNoCull(RespiratorClient.TEXTURE));
        model.head.render(pose, buffer, light, OverlayTexture.NO_OVERLAY);
    }
}
