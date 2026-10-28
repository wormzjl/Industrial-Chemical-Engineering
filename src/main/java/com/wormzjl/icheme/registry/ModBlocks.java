package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.world.level.block.ColumnCalculatorV3Block;
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

    public static final DeferredBlock<ColumnCalculatorV3Block> COLUMN_CALCULATOR_V3 = BLOCKS.registerBlock(
            "column_calculator_v3",
            ColumnCalculatorV3Block::new,
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
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F).sound(SoundType.METAL)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noOcclusion().dynamicShape().noLootTable()
    );
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.EquipmentInteriorBlock> EQUIPMENT_INTERIOR = BLOCKS.registerBlock(
            "equipment_interior",
            com.wormzjl.icheme.world.level.block.EquipmentInteriorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F).sound(SoundType.METAL)
                    .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK).noLootTable()
                    // An open tank's cells collide up to its floating roof, which moves (FloatingRoofs); nothing spawns on it.
                    // A dynamic shape leaves the state unsolid, which lets fluids wash it away: forced solid.
                    .dynamicShape().forceSolidOn().isValidSpawn((state, level, position, type) -> false)
    );
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.EquipmentConstructorBlock> EQUIPMENT_CONSTRUCTOR = BLOCKS.registerBlock(
            "equipment_constructor",
            com.wormzjl.icheme.world.level.block.EquipmentConstructorBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD)
    );

    /** Original positional machine loops of the constructed pumps and compressors; ordinary Blocks sound settings apply. */
    private static final DeferredRegister<SoundEvent> SOUNDS=DeferredRegister.create(Registries.SOUND_EVENT,IChemE.MOD_ID);
    public static final DeferredHolder<SoundEvent,SoundEvent> PUMP_RUNNING=loop("pump_running");
    public static final DeferredHolder<SoundEvent,SoundEvent> COMPRESSOR_RUNNING=loop("compressor_running");
    private static DeferredHolder<SoundEvent,SoundEvent> loop(String name){return SOUNDS.register(name,()->SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,name)));}

    private ModBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        SOUNDS.register(modEventBus);
    }
}
