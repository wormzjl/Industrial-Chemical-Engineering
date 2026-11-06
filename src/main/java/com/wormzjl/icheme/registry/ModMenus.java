package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.world.inventory.ProcessSimulatorMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, IChemE.MOD_ID);
    public static final DeferredHolder<MenuType<?>,MenuType<com.wormzjl.icheme.world.inventory.FluidDeviceMenu>> FLUID_DEVICE=MENU_TYPES.register("fluid_device",()->IMenuTypeExtension.create(com.wormzjl.icheme.world.inventory.FluidDeviceMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.wormzjl.icheme.world.inventory.PipelineToolMenu>> PIPELINE_TOOL =
            MENU_TYPES.register("pipeline_tool", () -> IMenuTypeExtension.create(com.wormzjl.icheme.world.inventory.PipelineToolMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ProcessSimulatorMenu>> PROCESS_SIMULATOR =
            MENU_TYPES.register(
                    "process_simulator",
                    () -> IMenuTypeExtension.create(ProcessSimulatorMenu::new)
            );

    public static final DeferredHolder<MenuType<?>, MenuType<com.wormzjl.icheme.world.inventory.GaugeMenu>> GAUGE = MENU_TYPES.register("gauge", () -> IMenuTypeExtension.create(com.wormzjl.icheme.world.inventory.GaugeMenu::new));
    public static final DeferredHolder<MenuType<?>, MenuType<com.wormzjl.icheme.world.inventory.AssemblyMenu>> ASSEMBLY = MENU_TYPES.register("assembly", () -> IMenuTypeExtension.create(com.wormzjl.icheme.world.inventory.AssemblyMenu::new));

    /** A machine's own page (batch 2026-11-03-rotating-machinery, W7). */
    public static final DeferredHolder<MenuType<?>, MenuType<com.wormzjl.icheme.world.inventory.MachineMenu>> MACHINE = MENU_TYPES.register("machine", () -> IMenuTypeExtension.create(com.wormzjl.icheme.world.inventory.MachineMenu::new));

    public static final DeferredHolder<MenuType<?>,MenuType<com.wormzjl.icheme.world.inventory.ControlPanelMenu>> CONTROL_PANEL=MENU_TYPES.register("control_panel",()->IMenuTypeExtension.create(com.wormzjl.icheme.world.inventory.ControlPanelMenu::new));
    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENU_TYPES.register(modEventBus);
    }
}
