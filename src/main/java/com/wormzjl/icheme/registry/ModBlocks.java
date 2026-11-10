package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.world.level.block.ProcessSimulatorBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(IChemE.MOD_ID);
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.FluidDeviceBlock> FLUID_RESERVOIR=fluid("fluid_reservoir",com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.RESERVOIR);
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.FluidDeviceBlock> FLUID_PIPE=fluid("fluid_pipe",com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE);
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.FluidDeviceBlock> FLUID_GENERATOR=fluid("fluid_generator",com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.GENERATOR);
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.FluidDeviceBlock> FLUID_VOID=fluid("fluid_void",com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.VOID);
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.FluidDeviceBlock> INLINE_FILTER=fluid("inline_filter",com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.FILTER);
    /** A three-way diverter valve (batch 2026-11-09-batch-distiller). */
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.FluidDeviceBlock> DIVERTER_VALVE=fluid("diverter_valve",com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.DIVERTER_VALVE);
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.LinePieceBlock> LINE_PIECE=BLOCKS.registerBlock("line_piece",
            com.wormzjl.icheme.world.level.block.LinePieceBlock::new,BlockBehaviour.Properties.of().strength(.5F).sound(SoundType.METAL).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion().dynamicShape().noLootTable());
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ConduitBlock> DATA_CABLE=BLOCKS.registerBlock("data_cable",
            com.wormzjl.icheme.world.level.block.ConduitBlock::new,BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(.5F).sound(SoundType.METAL).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion().dynamicShape());
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ConduitBlock> POWER_CABLE=BLOCKS.registerBlock("power_cable",
            p->new com.wormzjl.icheme.world.level.block.ConduitBlock(p,com.wormzjl.icheme.science.equipment.CableMedium.POWER),BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(.5F).sound(SoundType.METAL).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion().dynamicShape());
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ControlPanelBlock> LOCAL_CONTROLLER=controlPanel("local_controller");
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.DcsBlock> DCS=BLOCKS.registerBlock("dcs",
            com.wormzjl.icheme.world.level.block.DcsBlock::new,BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3).sound(SoundType.METAL)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion());
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.CreativePowerBlock> CREATIVE_POWER=BLOCKS.registerBlock("creative_power",
            com.wormzjl.icheme.world.level.block.CreativePowerBlock::new,BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(-1,3600000).sound(SoundType.METAL));
    private static DeferredBlock<com.wormzjl.icheme.world.level.block.ControlPanelBlock> controlPanel(String name){return BLOCKS.registerBlock(name,
            com.wormzjl.icheme.world.level.block.ControlPanelBlock::new,BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3).sound(SoundType.METAL).noOcclusion());}
    // A pipe rack (batch 2026-10-26-pipe-laying-system): a thin frame, so light and the faces behind it show through; its shape follows its lanes.
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.PipeRackBlock> PIPE_RACK=BLOCKS.registerBlock("pipe_rack",com.wormzjl.icheme.world.level.block.PipeRackBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F).sound(SoundType.METAL).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion().dynamicShape());
    private static DeferredBlock<com.wormzjl.icheme.world.level.block.FluidDeviceBlock> fluid(String name,com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind kind) {
        var properties=BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F).sound(SoundType.METAL).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK);
        // Every device model (the inline filter too, a Y-strainer since 2026-10-16) leaves part of its block space open:
        // none occludes the neighbouring block's faces in the chunk renderer, and light reaches inside each.
        properties.noOcclusion();
        return BLOCKS.registerBlock(name,p->com.wormzjl.icheme.world.level.block.FluidDeviceBlock.create(kind,p),properties);
    }

    public static final DeferredBlock<ProcessSimulatorBlock> PROCESS_SIMULATOR = BLOCKS.registerBlock(
            "process_simulator",
            ProcessSimulatorBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(3.5F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
    );

    // Parametric vessels (batch 2026-10-15-custom-equipment-rendering): placed whole by EquipmentStructure, no items, no drops.
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.EquipmentShellBlock> EQUIPMENT_SHELL = BLOCKS.registerBlock(
            "equipment_shell",
            com.wormzjl.icheme.world.level.block.EquipmentShellBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1.0F, 3600000.0F).sound(SoundType.METAL)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion().dynamicShape().noLootTable()
    );
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.EquipmentInteriorBlock> EQUIPMENT_INTERIOR = BLOCKS.registerBlock(
            "equipment_interior",
            com.wormzjl.icheme.world.level.block.EquipmentInteriorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1.0F, 3600000.0F).sound(SoundType.METAL)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noLootTable()
                    // An open tank's cells collide up to its floating roof, which moves (FloatingRoofs); nothing spawns on it.
                    // A dynamic shape leaves the state unsolid, which lets fluids wash it away: forced solid.
                    .dynamicShape().forceSolidOn().isValidSpawn((state, level, position, type) -> false)
    );
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.EquipmentConstructorBlock> EQUIPMENT_CONSTRUCTOR = BLOCKS.registerBlock(
            "equipment_constructor",
            com.wormzjl.icheme.world.level.block.EquipmentConstructorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2.5F).sound(SoundType.METAL).noOcclusion()
    );

    // Rotating machinery (batch 2026-11-03-rotating-machinery): a shaft on one axis and the creative motor; their trains are the engine's.
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ShaftBlock> SHAFT = BLOCKS.registerBlock(
            "shaft",
            properties->net.neoforged.fml.ModList.get().isLoaded("create")?new com.wormzjl.icheme.compat.create.CreateShaftBlock(properties):new com.wormzjl.icheme.world.level.block.ShaftBlock(properties),
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F).sound(SoundType.METAL).noOcclusion()
    );
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.CreativeMotorBlock> CREATIVE_MOTOR = BLOCKS.registerBlock(
            "creative_motor",
            com.wormzjl.icheme.world.level.block.CreativeMotorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(3.5F).sound(SoundType.METAL).noOcclusion()
    );

    /** Original positional machine loops of the constructed pumps and compressors; ordinary Blocks sound settings apply. */
    private static final DeferredRegister<SoundEvent> SOUNDS=DeferredRegister.create(Registries.SOUND_EVENT,IChemE.MOD_ID);
    public static final DeferredHolder<SoundEvent,SoundEvent> PUMP_RUNNING=loop("pump_running");
    public static final DeferredHolder<SoundEvent,SoundEvent> COMPRESSOR_RUNNING=loop("compressor_running");
    /** A pump's cavitation crackle and gas churn (W10c, decision D35), played over its casing loop. */
    public static final DeferredHolder<SoundEvent,SoundEvent> PUMP_CAVITATION=loop("pump_cavitation");
    public static final DeferredHolder<SoundEvent,SoundEvent> PUMP_GAS=loop("pump_gas");
    /** The drivers' own loops, apart from their machine's casing loop (decision D26); the generator plays the motor's sound. */
    public static final DeferredHolder<SoundEvent,SoundEvent> MOTOR_RUNNING=loop("motor_running");
    public static final DeferredHolder<SoundEvent,SoundEvent> GENERATOR_RUNNING=loop("generator_running");
    public static final DeferredHolder<SoundEvent,SoundEvent> TURBINE_RUNNING=loop("turbine_running");
    /** Process loops (tools/process-sounds): an elevated flare's roar and pilot hiss, a still's burner, firebox and boiling pot. */
    public static final DeferredHolder<SoundEvent,SoundEvent> FLARE_ROAR=loop("flare_roar");
    public static final DeferredHolder<SoundEvent,SoundEvent> FLARE_PILOT=loop("flare_pilot");
    public static final DeferredHolder<SoundEvent,SoundEvent> BURNER_ROAR=loop("burner_roar");
    public static final DeferredHolder<SoundEvent,SoundEvent> FIRE_DRAUGHT=loop("fire_draught");
    public static final DeferredHolder<SoundEvent,SoundEvent> POT_BOIL=loop("pot_boil");
    /**
     * The plant's other sources (batch 2026-11-10-custom-shader-system, PROCESS_SOUNDS_REVIEW.md table 1): a gas or steam
     * valve's and a vent's hiss, a liquid valve's rush, its cavitation (the pump's crackle) and its
     * rattling hiss when it flashes, a fan's whoosh, falling water, a
     * gearbox's whine, and a liquid line's water-hammer knock (a one-shot).
     */
    public static final DeferredHolder<SoundEvent,SoundEvent> VALVE_GAS=loop("valve_gas");
    public static final DeferredHolder<SoundEvent,SoundEvent> VALVE_LIQUID=loop("valve_liquid");
    /** A cavitating liquid valve (the pump's cavitation loop) and a flashing one (hiss and plug rattle), owner 2026-11-10. */
    public static final DeferredHolder<SoundEvent,SoundEvent> VALVE_CAVITATING=loop("valve_cavitating");
    public static final DeferredHolder<SoundEvent,SoundEvent> VALVE_FLASHING=loop("valve_flashing");
    public static final DeferredHolder<SoundEvent,SoundEvent> FAN_WHOOSH=loop("fan_whoosh");
    public static final DeferredHolder<SoundEvent,SoundEvent> WATER_FALL=loop("water_fall");
    public static final DeferredHolder<SoundEvent,SoundEvent> GEAR_WHINE=loop("gear_whine");
    public static final DeferredHolder<SoundEvent,SoundEvent> PIPE_KNOCK=loop("pipe_knock");
    private static DeferredHolder<SoundEvent,SoundEvent> loop(String name){return SOUNDS.register(name,()->SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,name)));}

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        // The column calculator's old id: a placed one becomes a simulator (plan section 3.6).
        BLOCKS.addAlias(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "column_calculator_v3"), PROCESS_SIMULATOR.getId());
        BLOCKS.register(modEventBus);
        SOUNDS.register(modEventBus);
    }
}
