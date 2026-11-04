package com.wormzjl.icheme.compat.create;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModCreativeTabs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Create compatibility module (batch 2026-11-03-rotating-machinery, W6b form A, owner decision D40; plan section 8).
 * {@link IChemE} calls {@link #register} only when Create is loaded: this package is the only one that names a Create,
 * Ponder, Flywheel or Catnip type, so a pack without Create never loads one. It holds one block, the Create coupling,
 * with its own registers on the mod bus.
 */
public final class CreateCompat {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(IChemE.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(IChemE.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, IChemE.MOD_ID);

    /** The Create coupling: Create's rotation in on its facing side, an industrial shaft end out of the opposite side. */
    public static final DeferredBlock<CreateCouplingBlock> COUPLING = BLOCKS.registerBlock("create_coupling", CreateCouplingBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(3.5F).sound(SoundType.METAL).noOcclusion());
    public static final DeferredItem<BlockItem> COUPLING_ITEM = ITEMS.registerSimpleBlockItem("create_coupling", COUPLING);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CreateCouplingBlockEntity>> COUPLING_ENTITY = BLOCK_ENTITIES.register("create_coupling",
            () -> BlockEntityType.Builder.of((pos, state) -> new CreateCouplingBlockEntity(CreateCompat.COUPLING_ENTITY.get(), pos, state), COUPLING.get()).build(null));

    private CreateCompat() {
    }

    /** Registers the coupling on {@code modBus}; the client's renderer and Flywheel visual on a client. Called with Create loaded only. */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(CreateCompat::tab);
        if (FMLEnvironment.dist.isClient()) CreateCompatClient.register(modBus);
    }

    /** The coupling joins the mod's creative tab, after the creative motor. */
    private static void tab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == ModCreativeTabs.MAIN.getKey()) event.accept(COUPLING_ITEM.get());
    }
}
