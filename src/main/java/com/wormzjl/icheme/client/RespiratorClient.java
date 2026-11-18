package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The respirator's worn look (batch 2026-11-18-respirator). In the helmet slot vanilla's armour layer draws it from the
 * armour material's texture; in a Curios slot (Curios installed) {@code compat.curios.CuriosRespiratorClient} draws the
 * same texture on the same head. The placeholder look is a texture on the outer head; the owner picks the final look from
 * the option sheets.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class RespiratorClient {
    /** The worn texture: the armour material {@code icheme:respirator}'s outer layer. */
    public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "textures/models/armor/respirator_layer_1.png");

    private RespiratorClient() {
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("curios")) {
            event.enqueueWork(com.wormzjl.icheme.compat.curios.CuriosRespiratorClient::register);
        }
    }
}
