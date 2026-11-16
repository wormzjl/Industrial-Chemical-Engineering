package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** The conversion rules the server sent belong to its world (batch 2026-11-15-fluid-interfaces, W5): they go when the client leaves it. */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class ConversionClient {
    private ConversionClient() {
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ConversionRuntime.resetClient();
    }
}
