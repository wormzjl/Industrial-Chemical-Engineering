package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.world.item.GaugeSettings;
import com.wormzjl.icheme.world.item.PipelineFittingItem;
import com.wormzjl.icheme.world.item.PipelineToolItem;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    /** Persistent item settings, synchronized through the ordinary inventory protocol. */
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, IChemE.MOD_ID);
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PipelineToolItem.Settings>> PIPELINE_TOOL_SETTINGS =
            COMPONENTS.register("pipeline_tool_settings", () -> DataComponentType.<PipelineToolItem.Settings>builder()
                    .persistent(PipelineToolItem.Settings.CODEC).networkSynchronized(PipelineToolItem.Settings.STREAM_CODEC).build());
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GaugeSettings>> GAUGE_SETTINGS = COMPONENTS.register("gauge_settings", () -> DataComponentType.<GaugeSettings>builder().persistent(GaugeSettings.CODEC).networkSynchronized(GaugeSettings.STREAM_CODEC).build());

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(IChemE.MOD_ID);
    public static final DeferredItem<BlockItem> FLUID_RESERVOIR=ITEMS.registerSimpleBlockItem("fluid_reservoir",ModBlocks.FLUID_RESERVOIR);
    public static final DeferredItem<BlockItem> FLUID_PIPE=ITEMS.registerSimpleBlockItem("fluid_pipe",ModBlocks.FLUID_PIPE);
    public static final DeferredItem<PipelineFittingItem> FLUID_PUMP=fitting("fluid_pump",Instrument.Type.PUMP);
    public static final DeferredItem<PipelineFittingItem> FLUID_COMPRESSOR=fitting("fluid_compressor",Instrument.Type.COMPRESSOR);
    public static final DeferredItem<PipelineFittingItem> PRESSURE_CONTROL_VALVE=fitting("pressure_control_valve",Instrument.Type.PRESSURE_VALVE);
    public static final DeferredItem<BlockItem> FLUID_GENERATOR=ITEMS.registerSimpleBlockItem("fluid_generator",ModBlocks.FLUID_GENERATOR);
    public static final DeferredItem<BlockItem> FLUID_VOID=ITEMS.registerSimpleBlockItem("fluid_void",ModBlocks.FLUID_VOID);
    public static final DeferredItem<PipelineFittingItem> INLINE_FILTER=fitting("inline_filter",Instrument.Type.FILTER);
    public static final DeferredItem<com.wormzjl.icheme.world.item.RecoveredSolidsItem> RECOVERED_SOLIDS=ITEMS.registerItem("recovered_solids",com.wormzjl.icheme.world.item.RecoveredSolidsItem::new);
    public static final DeferredItem<BlockItem> EQUIPMENT_CONSTRUCTOR=ITEMS.registerSimpleBlockItem("equipment_constructor",ModBlocks.EQUIPMENT_CONSTRUCTOR);
    public static final DeferredItem<net.minecraft.world.item.Item> FLUID_DEBUGGER=ITEMS.registerSimpleItem("fluid_debugger",new net.minecraft.world.item.Item.Properties().stacksTo(1));
    public static final DeferredItem<PipelineToolItem> PIPELINE_TOOL =
            ITEMS.registerItem("pipeline_tool", PipelineToolItem::new);
    public static final DeferredItem<PipelineFittingItem> PIPELINE_SIGHT_GLASS=fitting("pipeline_sight_glass",Instrument.Type.SIGHT_GLASS);
    public static final DeferredItem<PipelineFittingItem> PIPELINE_SHUTOFF_VALVE=fitting("pipeline_shutoff_valve",Instrument.Type.SHUTOFF);
    public static final DeferredItem<PipelineFittingItem> PIPELINE_CHECK_VALVE=fitting("pipeline_check_valve",Instrument.Type.CHECK);

    public static final DeferredItem<BlockItem> COLUMN_CALCULATOR_V3 = ITEMS.registerSimpleBlockItem(
            "column_calculator_v3",
            ModBlocks.COLUMN_CALCULATOR_V3
    );

    public static final DeferredItem<com.wormzjl.icheme.world.item.GaugeItem> PRESSURE_GAUGE = ITEMS.registerItem("pressure_gauge", p -> new com.wormzjl.icheme.world.item.GaugeItem(p, GaugeSettings.PRESSURE));
    public static final DeferredItem<com.wormzjl.icheme.world.item.GaugeItem> TEMPERATURE_GAUGE = ITEMS.registerItem("temperature_gauge", p -> new com.wormzjl.icheme.world.item.GaugeItem(p, GaugeSettings.TEMPERATURE));
    public static final DeferredItem<com.wormzjl.icheme.world.item.AccessoryWrenchItem> ACCESSORY_WRENCH = ITEMS.registerItem("accessory_wrench", com.wormzjl.icheme.world.item.AccessoryWrenchItem::new);
    public static final DeferredItem<com.wormzjl.icheme.world.item.LiquidLevelGaugeItem> LIQUID_LEVEL_GAUGE = ITEMS.registerItem("liquid_level_gauge", com.wormzjl.icheme.world.item.LiquidLevelGaugeItem::new);

    private ModItems() {
    }

    private static DeferredItem<PipelineFittingItem> fitting(String name,Instrument.Type type){return ITEMS.registerItem(name,p->new PipelineFittingItem(p,type));}

    /** Registers the item settings components and the items. */
    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
