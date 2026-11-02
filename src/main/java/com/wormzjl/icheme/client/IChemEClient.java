package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.gui.screens.inventory.ProcessSimulatorScreen;
import com.wormzjl.icheme.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class IChemEClient {
    private IChemEClient() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.PROCESS_SIMULATOR.get(), ProcessSimulatorScreen::new);
        event.register(ModMenus.FLUID_DEVICE.get(),com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen::new);
        event.register(ModMenus.GAUGE.get(), com.wormzjl.icheme.client.gui.screens.inventory.GaugeScreen::new);
        event.register(ModMenus.ASSEMBLY.get(), com.wormzjl.icheme.client.gui.screens.inventory.AssemblyScreen::new);
        event.register(ModMenus.PIPELINE_TOOL.get(), com.wormzjl.icheme.client.gui.screens.inventory.PipelineToolScreen::new);
    }

    /** Fluid device identities belong to a world: the remembered last-delivered views go with it. */
    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        com.wormzjl.icheme.network.FluidNetwork.forgetDelivered();
        TankRenderer.clear();
        SightGlassRenderer.clear();
        MoverSounds.clear();
    }

}
