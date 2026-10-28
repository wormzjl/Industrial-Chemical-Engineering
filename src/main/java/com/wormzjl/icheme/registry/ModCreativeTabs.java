package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's creative tab. A holder of its own, as the blocks and items have, so that {@link IChemE}'s static state (its
 * logger, read by unit tests on repair paths) touches no registry: a registry needs Minecraft bootstrapped.
 */
public final class ModCreativeTabs {
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IChemE.MOD_ID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.icheme"))
            .icon(() -> ModItems.PRESSURE_CONTROL_VALVE.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(ModItems.COLUMN_CALCULATOR_V3.get());
                output.accept(ModItems.PRESSURE_PROBE.get());
                output.accept(ModItems.TEMPERATURE_PROBE.get());
                output.accept(ModItems.FLOWMETER.get());
                output.accept(ModItems.DIGITAL_LEVEL_METER.get());
                output.accept(ModItems.PRESSURE_GAUGE.get());
                output.accept(ModItems.TEMPERATURE_GAUGE.get());
                output.accept(ModItems.ACCESSORY_WRENCH.get());
                output.accept(ModItems.PIPELINE_TOOL.get());
                output.accept(ModItems.PIPE_LAYING_TOOL.get());
                output.accept(ModItems.PIPE_RACK.get());
                output.accept(ModItems.PIPELINE_SIGHT_GLASS.get());
                output.accept(ModItems.PIPELINE_SHUTOFF_VALVE.get());
                output.accept(ModItems.PIPELINE_CHECK_VALVE.get());
                output.accept(ModItems.FLUID_RESERVOIR.get());
                output.accept(ModItems.FLUID_PIPE.get());
                output.accept(ModItems.FLUID_PUMP.get());
                output.accept(ModItems.FLUID_COMPRESSOR.get());
                output.accept(ModItems.PRESSURE_CONTROL_VALVE.get());
                output.accept(ModItems.FLUID_GENERATOR.get());
                output.accept(ModItems.FLUID_VOID.get());
                output.accept(ModItems.INLINE_FILTER.get());
                output.accept(ModItems.RECOVERED_SOLIDS.get());
                output.accept(ModItems.FLUID_DEBUGGER.get());
                output.accept(ModItems.EQUIPMENT_CONSTRUCTOR.get());
            })
            .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        CREATIVE_TABS.register(modEventBus);
    }
}
