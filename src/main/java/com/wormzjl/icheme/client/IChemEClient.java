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
        event.register(ModMenus.CONTROL_PANEL.get(),com.wormzjl.icheme.client.gui.screens.inventory.ControlPanelScreen::new);
        event.register(ModMenus.GAUGE.get(), com.wormzjl.icheme.client.gui.screens.inventory.GaugeScreen::new);
        event.register(ModMenus.ASSEMBLY.get(), com.wormzjl.icheme.client.gui.screens.inventory.AssemblyScreen::new);
        event.register(ModMenus.MACHINE.get(), com.wormzjl.icheme.client.gui.screens.inventory.MachineScreen::new);
        event.register(ModMenus.PIPELINE_TOOL.get(), com.wormzjl.icheme.client.gui.screens.inventory.PipelineToolScreen::new);
    }

    /** The P0 film command (batch 2026-11-12-tutorial-system); the field guide replaces it in P1. */
    @SubscribeEvent
    public static void registerCommands(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        com.wormzjl.icheme.client.gui.tutorial.FilmCommand.register(event);
    }

    /** The field guide's key (batch 2026-11-12-tutorial-system, P1): default H. */
    @SubscribeEvent
    public static void registerKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(com.wormzjl.icheme.client.gui.tutorial.GuideClient.KEY);
    }

    /** The field guide's one-line tracker, at the top left. */
    @SubscribeEvent
    public static void registerLayers(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "guide_hud"), com.wormzjl.icheme.client.gui.tutorial.GuideHud::render);
    }

    /** The guide key opens the field guide while no screen is open. */
    @SubscribeEvent
    public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event) {
        com.wormzjl.icheme.client.gui.tutorial.GuideClient.key(event);
    }

    /** The field guide asks the server for this player's tutorial progress (batch 2026-11-12-tutorial-system, P1). */
    @SubscribeEvent
    public static void loggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        com.wormzjl.icheme.client.gui.tutorial.GuideClient.hello();
    }

    /** Fluid device identities belong to a world: the remembered last-delivered views go with it. */
    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        com.wormzjl.icheme.network.FluidNetwork.forgetDelivered();
        TankRenderer.clear();
        EquipmentBodyRenderer.release();
        SightGlassRenderer.clear();
        MoverSounds.clear();
        DriverSounds.clear();
        com.wormzjl.icheme.client.fx.sound.FxSounds.clear();
        com.wormzjl.icheme.client.fx.state.ProcessStates.clear();
    }

}
