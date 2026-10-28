package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IChemE.MOD_ID);
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity>> FLUID_DEVICE=BLOCK_ENTITY_TYPES.register("fluid_device",()->BlockEntityType.Builder.of(com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity::new,
            ModBlocks.FLUID_RESERVOIR.get(),ModBlocks.FLUID_PIPE.get(),ModBlocks.FLUID_GENERATOR.get(),ModBlocks.FLUID_VOID.get(),ModBlocks.INLINE_FILTER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ColumnCalculatorV3BlockEntity>>
            COLUMN_CALCULATOR_V3 = BLOCK_ENTITY_TYPES.register(
                    "column_calculator_v3",
                    () -> BlockEntityType.Builder.of(
                            ColumnCalculatorV3BlockEntity::new,
                            ModBlocks.COLUMN_CALCULATOR_V3.get()
                    ).build(null)
            );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity>>
            EQUIPMENT_SHELL = BLOCK_ENTITY_TYPES.register(
                    "equipment_shell",
                    () -> BlockEntityType.Builder.of(
                            com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity::new,
                            ModBlocks.EQUIPMENT_SHELL.get()
                    ).build(null)
            );
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity>>
            EQUIPMENT_CONSTRUCTOR = BLOCK_ENTITY_TYPES.register(
                    "equipment_constructor",
                    () -> BlockEntityType.Builder.of(
                            com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity::new,
                            ModBlocks.EQUIPMENT_CONSTRUCTOR.get()
                    ).build(null)
            );

    private ModBlockEntities() {
    }

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity>>
            PIPE_RACK = BLOCK_ENTITY_TYPES.register("pipe_rack",
                    () -> BlockEntityType.Builder.of(com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity::new, ModBlocks.PIPE_RACK.get()).build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITY_TYPES.register(modEventBus);
    }
}
