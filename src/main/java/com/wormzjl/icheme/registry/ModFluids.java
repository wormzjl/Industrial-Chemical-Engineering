package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The mod's one Forge fluid (batch 2026-11-15-fluid-interfaces, W3, decision D9): {@code icheme:crude_oil}, a placeholder
 * crude oil in the common tag {@code c:crude_oil}, so a world without another mod's crude still has one to pour into a
 * converter (the bundled rule {@code #c:crude_oil} turns it into the preset {@code medium_crude}). Always registered; its
 * bucket is hidden from the creative tab and from JEI where another mod's fluid is in the tag ({@link #otherCrude}). No
 * world generation. Its properties are a medium crude's at the surface: 870 kg/m³, a kinematic viscosity about five times
 * water's, 300 K.
 */
public final class ModFluids {
    public static final DeferredRegister<FluidType> FLUID_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, IChemE.MOD_ID);
    public static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, IChemE.MOD_ID);
    /** The common tag the bundled conversion rule names. */
    public static final TagKey<Fluid> CRUDE_OIL_TAG = TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("c", "crude_oil"));

    public static final DeferredHolder<FluidType, FluidType> CRUDE_OIL_TYPE = FLUID_TYPES.register("crude_oil", () -> new FluidType(FluidType.Properties.create()
            .descriptionId("fluid_type.icheme.crude_oil").density(870).viscosity(5000).temperature(300).canSwim(true).canDrown(true)
            .sound(SoundActions.BUCKET_FILL, net.minecraft.sounds.SoundEvents.BUCKET_FILL).sound(SoundActions.BUCKET_EMPTY, net.minecraft.sounds.SoundEvents.BUCKET_EMPTY)));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> CRUDE_OIL = FLUIDS.register("crude_oil", () -> new BaseFlowingFluid.Source(properties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_CRUDE_OIL = FLUIDS.register("flowing_crude_oil", () -> new BaseFlowingFluid.Flowing(properties()));
    public static final DeferredBlock<LiquidBlock> CRUDE_OIL_BLOCK = ModBlocks.BLOCKS.registerBlock("crude_oil", p -> new LiquidBlock(CRUDE_OIL.get(), p),
            BlockBehaviour.Properties.ofFullCopy(Blocks.WATER).mapColor(net.minecraft.world.level.material.MapColor.COLOR_BLACK));
    public static final DeferredItem<BucketItem> CRUDE_OIL_BUCKET = ModItems.ITEMS.registerItem("crude_oil_bucket", p -> new BucketItem(CRUDE_OIL.get(), p),
            new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));

    private ModFluids() {}

    private static BaseFlowingFluid.Properties properties() {
        return new BaseFlowingFluid.Properties(CRUDE_OIL_TYPE, CRUDE_OIL, FLOWING_CRUDE_OIL).bucket(CRUDE_OIL_BUCKET).block(CRUDE_OIL_BLOCK)
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(20);
    }

    public static void register(IEventBus bus) {
        FLUID_TYPES.register(bus);
        FLUIDS.register(bus);
    }

    /** Whether a fluid of another mod is in {@code c:crude_oil}: then that one is the crude, and this one is hidden (D9). */
    public static boolean otherCrude() {
        return BuiltInRegistries.FLUID.getTag(CRUDE_OIL_TAG).map(set -> set.stream().anyMatch(h -> h.unwrapKey()
                .map(key -> !key.location().getNamespace().equals(IChemE.MOD_ID)).orElse(false))).orElse(false);
    }
}
