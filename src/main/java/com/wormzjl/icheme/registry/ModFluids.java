package com.wormzjl.icheme.registry;

import com.wormzjl.icheme.IChemE;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
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
 * The placeholder crude oil (batch 2026-11-15-fluid-interfaces, W3, decision D9): a Forge fluid {@code icheme:crude_oil}
 * (source) and {@code icheme:flowing_crude_oil}, its liquid block {@code crude_oil} and its bucket {@code crude_oil_bucket},
 * both fluids in the tag {@code c:crude_oil} that the bundled conversion rule {@code icheme:c/crude_oil} names. It is
 * always registered, so a world never loses it when a mod is added; while another mod's fluid is in {@code c:crude_oil}
 * the creative tab and JEI hide the bucket and the fluid ({@link #othersInTag}). No world generation. Its own registers,
 * so the shared block and item lists stay as they were.
 */
public final class ModFluids {
    /** The tag the crude oils of most mods share. */
    public static final TagKey<Fluid> CRUDE_OIL_TAG = TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("c", "crude_oil"));
    /** The bucket's tint over the vanilla water textures: a dark brown-black, opaque. */
    public static final int CRUDE_OIL_TINT = 0xFF1B1611;

    private static final DeferredRegister<FluidType> TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, IChemE.MOD_ID);
    private static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(Registries.FLUID, IChemE.MOD_ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(IChemE.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(IChemE.MOD_ID);

    /** A medium crude at ambient: 870 kg/m³, 5 000 (5 Pa s on Forge's scale, water 1 000), 300 K. */
    public static final DeferredHolder<FluidType, FluidType> CRUDE_OIL_TYPE = TYPES.register("crude_oil", () -> new FluidType(FluidType.Properties.create()
            .descriptionId("fluid_type.icheme.crude_oil").density(870).viscosity(5000).temperature(300)
            .canExtinguish(false).canHydrate(false).canConvertToSource(false).motionScale(0.0023)
            .sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL).sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY)));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> CRUDE_OIL = FLUIDS.register("crude_oil", () -> new BaseFlowingFluid.Source(properties()));
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_CRUDE_OIL = FLUIDS.register("flowing_crude_oil", () -> new BaseFlowingFluid.Flowing(properties()));
    public static final DeferredBlock<LiquidBlock> CRUDE_OIL_BLOCK = BLOCKS.registerBlock("crude_oil", p -> new LiquidBlock(CRUDE_OIL.get(), p),
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).replaceable().noCollission().strength(100f).pushReaction(PushReaction.DESTROY).noLootTable().liquid());
    public static final DeferredItem<BucketItem> CRUDE_OIL_BUCKET = ITEMS.registerItem("crude_oil_bucket", p -> new BucketItem(CRUDE_OIL.get(), p),
            new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));

    private static BaseFlowingFluid.Properties properties() {
        // Slow and short, as a heavy liquid: lava's spread on a 3-block slope search, one level less per block.
        return new BaseFlowingFluid.Properties(CRUDE_OIL_TYPE, CRUDE_OIL, FLOWING_CRUDE_OIL).bucket(CRUDE_OIL_BUCKET).block(CRUDE_OIL_BLOCK)
                .slopeFindDistance(2).levelDecreasePerBlock(2).tickRate(20).explosionResistance(100f);
    }

    private ModFluids() {
    }

    /**
     * Whether a fluid of another mod is in {@code c:crude_oil} (D9): then the placeholder hides from the creative tab and
     * JEI. Reads the bound tags, so it answers after the tags loaded (on the client, after the server sent them).
     */
    public static boolean othersInTag() {
        return BuiltInRegistries.FLUID.getTag(CRUDE_OIL_TAG).map(set -> set.stream().anyMatch(h -> h.unwrapKey()
                .map(k -> !k.location().getNamespace().equals(IChemE.MOD_ID)).orElse(true))).orElse(false);
    }

    /** Registers the fluid type, the two fluids, the liquid block and the bucket. */
    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
    }
}
