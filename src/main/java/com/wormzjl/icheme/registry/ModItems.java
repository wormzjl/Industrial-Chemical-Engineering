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
    /** The pipe laying tool's settings (batch 2026-10-26-pipe-laying-system): the bore it lays, as the sizing tool's. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PipelineToolItem.Settings>> PIPE_LAYING_SETTINGS =
            COMPONENTS.register("pipe_laying_settings", () -> DataComponentType.<PipelineToolItem.Settings>builder()
                    .persistent(PipelineToolItem.Settings.CODEC).networkSynchronized(PipelineToolItem.Settings.STREAM_CODEC).build());
    /** The primary paint the laying tool's lines take (their pipe), 0xRRGGBB; absent: bare steel ({@link com.wormzjl.icheme.science.equipment.Paint#BARE}). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> PIPE_LAYING_PAINT = linePaint("pipe_laying_paint");
    /** The secondary paint (their flanges), 0xRRGGBB or bare; absent: the primary paint, as the flanges took before it existed. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> PIPE_LAYING_SECONDARY = linePaint("pipe_laying_secondary");

    private static DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> linePaint(String name) {
        return COMPONENTS.register(name, () -> DataComponentType.<Integer>builder()
                .persistent(com.mojang.serialization.Codec.INT.validate(rgb -> com.wormzjl.icheme.science.equipment.Paint.line(rgb) ? com.mojang.serialization.DataResult.success(rgb)
                        : com.mojang.serialization.DataResult.error(() -> "A line's paint is 0xRRGGBB or -1 (bare), not " + rgb)))
                .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.INT).build());
    }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(IChemE.MOD_ID);
    public static final DeferredItem<BlockItem> FLUID_RESERVOIR=ITEMS.registerSimpleBlockItem("fluid_reservoir",ModBlocks.FLUID_RESERVOIR);
    public static final DeferredItem<BlockItem> FLUID_PIPE=ITEMS.registerSimpleBlockItem("fluid_pipe",ModBlocks.FLUID_PIPE);
    public static final DeferredItem<PipelineFittingItem> FLUID_PUMP=fitting("fluid_pump",Instrument.Type.PUMP);
    public static final DeferredItem<PipelineFittingItem> FLUID_COMPRESSOR=fitting("fluid_compressor",Instrument.Type.COMPRESSOR);
    public static final DeferredItem<PipelineFittingItem> PRESSURE_CONTROL_VALVE=fitting("pressure_control_valve",Instrument.Type.PRESSURE_VALVE);
    public static final DeferredItem<BlockItem> FLUID_GENERATOR=ITEMS.registerSimpleBlockItem("fluid_generator",ModBlocks.FLUID_GENERATOR);
    public static final DeferredItem<BlockItem> FLUID_VOID=ITEMS.registerSimpleBlockItem("fluid_void",ModBlocks.FLUID_VOID);
    public static final DeferredItem<PipelineFittingItem> INLINE_FILTER=fitting("inline_filter",Instrument.Type.FILTER);
    public static final DeferredItem<BlockItem> PIPE_RACK=ITEMS.registerSimpleBlockItem("pipe_rack",ModBlocks.PIPE_RACK);
    public static final DeferredItem<com.wormzjl.icheme.world.item.RecoveredSolidsItem> RECOVERED_SOLIDS=ITEMS.registerItem("recovered_solids",com.wormzjl.icheme.world.item.RecoveredSolidsItem::new);
    public static final DeferredItem<BlockItem> EQUIPMENT_CONSTRUCTOR=ITEMS.registerSimpleBlockItem("equipment_constructor",ModBlocks.EQUIPMENT_CONSTRUCTOR);
    public static final DeferredItem<net.minecraft.world.item.Item> FLUID_DEBUGGER=ITEMS.registerSimpleItem("fluid_debugger",new net.minecraft.world.item.Item.Properties().stacksTo(1));
    public static final DeferredItem<PipelineToolItem> PIPELINE_TOOL =
            ITEMS.registerItem("pipeline_tool", PipelineToolItem::new);
    public static final DeferredItem<com.wormzjl.icheme.world.item.PipeLayingToolItem> PIPE_LAYING_TOOL =
            ITEMS.registerItem("pipe_laying_tool", com.wormzjl.icheme.world.item.PipeLayingToolItem::new);
    public static final DeferredItem<PipelineFittingItem> PIPELINE_SIGHT_GLASS=fitting("pipeline_sight_glass",Instrument.Type.SIGHT_GLASS);
    public static final DeferredItem<PipelineFittingItem> PIPELINE_SHUTOFF_VALVE=fitting("pipeline_shutoff_valve",Instrument.Type.SHUTOFF);
    public static final DeferredItem<PipelineFittingItem> PIPELINE_CHECK_VALVE=fitting("pipeline_check_valve",Instrument.Type.CHECK);

    public static final DeferredItem<BlockItem> PROCESS_SIMULATOR = ITEMS.registerSimpleBlockItem(
            "process_simulator",
            ModBlocks.PROCESS_SIMULATOR
    );

    public static final DeferredItem<com.wormzjl.icheme.world.item.GaugeItem> PRESSURE_GAUGE = ITEMS.registerItem("pressure_gauge", p -> new com.wormzjl.icheme.world.item.GaugeItem(p, GaugeSettings.PRESSURE));
    public static final DeferredItem<com.wormzjl.icheme.world.item.GaugeItem> TEMPERATURE_GAUGE = ITEMS.registerItem("temperature_gauge", p -> new com.wormzjl.icheme.world.item.GaugeItem(p, GaugeSettings.TEMPERATURE));
    public static final DeferredItem<com.wormzjl.icheme.world.item.AccessoryWrenchItem> ACCESSORY_WRENCH = ITEMS.registerItem("accessory_wrench", com.wormzjl.icheme.world.item.AccessoryWrenchItem::new);

    public static final DeferredItem<com.wormzjl.icheme.world.item.SensorItem> PRESSURE_PROBE=sensor("pressure_probe",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE);
    public static final DeferredItem<com.wormzjl.icheme.world.item.SensorItem> TEMPERATURE_PROBE=sensor("temperature_probe",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.TEMPERATURE);
    public static final DeferredItem<com.wormzjl.icheme.world.item.SensorItem> FLOWMETER=sensor("flowmeter",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.FLOW);
    public static final DeferredItem<com.wormzjl.icheme.world.item.SensorItem> DIGITAL_LEVEL_METER=sensor("digital_level_meter",com.wormzjl.icheme.runtime.instrument.Sensor.Kind.LEVEL);
    private static DeferredItem<com.wormzjl.icheme.world.item.SensorItem> sensor(String name,com.wormzjl.icheme.runtime.instrument.Sensor.Kind kind){return ITEMS.registerItem(name,p->new com.wormzjl.icheme.world.item.SensorItem(p,kind));}

    private ModItems() {
    }

    private static DeferredItem<PipelineFittingItem> fitting(String name,Instrument.Type type){return ITEMS.registerItem(name,p->new PipelineFittingItem(p,type));}

    /** Registers the item settings components and the items. */
    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
        ITEMS.addAlias(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "column_calculator_v3"), PROCESS_SIMULATOR.getId());
        ITEMS.register(modEventBus);
    }
}
