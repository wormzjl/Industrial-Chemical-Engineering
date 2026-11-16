package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModFluids;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.model.DynamicFluidContainerModel;

/**
 * The placeholder crude oil on the client (batch 2026-11-15-fluid-interfaces, W3): the vanilla water still, flow and
 * overlay textures under the dark tint {@link ModFluids#CRUDE_OIL_TINT}, and the bucket's fluid layer in the same tint
 * (NeoForge's fluid container model). No texture of its own.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class CrudeOilClient {
    private static final ResourceLocation STILL = ResourceLocation.withDefaultNamespace("block/water_still");
    private static final ResourceLocation FLOW = ResourceLocation.withDefaultNamespace("block/water_flow");
    private static final ResourceLocation OVERLAY = ResourceLocation.withDefaultNamespace("block/water_overlay");

    private CrudeOilClient() {
    }

    @SubscribeEvent
    public static void registerExtensions(RegisterClientExtensionsEvent event) {
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture() { return STILL; }
            @Override public ResourceLocation getFlowingTexture() { return FLOW; }
            @Override public ResourceLocation getOverlayTexture() { return OVERLAY; }
            @Override public int getTintColor() { return ModFluids.CRUDE_OIL_TINT; }
        }, ModFluids.CRUDE_OIL_TYPE.get());
    }

    @SubscribeEvent
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(new DynamicFluidContainerModel.Colors(), ModFluids.CRUDE_OIL_BUCKET.get());
    }
}
