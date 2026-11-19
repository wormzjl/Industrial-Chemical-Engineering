package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.compat.curios.CuriosRespiratorClient;
import com.wormzjl.icheme.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * The respirator's worn look (batch 2026-11-18-respirator): {@link RespiratorModel} with {@link #TEXTURE}. In the helmet
 * slot vanilla's armour layer draws it, through {@link IClientItemExtensions#getHumanoidArmorModel} (the layer copies the
 * wearer's pose and the head's visibility onto it); in a Curios slot (Curios installed)
 * {@code compat.curios.CuriosRespiratorClient} draws the same model's head.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class RespiratorClient {
    /** The worn texture: the armour material {@code icheme:respirator}'s outer layer, and the model's texture. */
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "textures/models/armor/respirator_layer_1.png");

    private static RespiratorModel model;

    private RespiratorClient() {
    }

    /** The baked model: baked again at each resource reload (the renderers' layers are rebuilt then). */
    public static RespiratorModel model() {
        var baked = model;
        if (baked == null) {
            baked = new RespiratorModel(Minecraft.getInstance().getEntityModels().bakeLayer(RespiratorModel.LAYER));
            model = baked;
        }
        return baked;
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(RespiratorModel.LAYER, RespiratorModel::createLayer);
    }

    @SubscribeEvent
    public static void rebake(EntityRenderersEvent.AddLayers event) {
        model = new RespiratorModel(event.getEntityModels().bakeLayer(RespiratorModel.LAYER));
    }

    @SubscribeEvent
    public static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel<?> original) {
                return model();
            }
        }, ModItems.RESPIRATOR.get());
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("curios")) {
            event.enqueueWork(() -> CuriosRespiratorClient.register(() -> model().head, TEXTURE));
        }
    }
}
