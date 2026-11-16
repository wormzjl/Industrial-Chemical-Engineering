package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModFluids;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;

/**
 * The placeholder crude oil on a running server (batch 2026-11-15-fluid-interfaces, W3, D9): the registry holds
 * {@code icheme:crude_oil}, its flowing form, its liquid block and its bucket; the bound tag {@code c:crude_oil} holds both
 * fluids; the rules the server's reload published answer {@code icheme:crude_oil} with the bundled tag rule
 * {@code icheme:c/crude_oil}; no other mod's fluid is in the tag here, so the placeholder shows. Places nothing.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class CrudeOilGameTests {
    private CrudeOilGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 20, batch = "crude-oil")
    public static void thePlaceholderCrudeOilIsRegisteredTaggedAndConverts(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("icheme", "crude_oil");
        check(BuiltInRegistries.FLUID.get(id) == ModFluids.CRUDE_OIL.get(), "the registry's icheme:crude_oil is the placeholder");
        check(BuiltInRegistries.FLUID.containsKey(ResourceLocation.fromNamespaceAndPath("icheme", "flowing_crude_oil")), "its flowing form");
        check(BuiltInRegistries.BLOCK.get(id) == ModFluids.CRUDE_OIL_BLOCK.get(), "its liquid block");
        check(ModFluids.CRUDE_OIL_BUCKET.get().content == ModFluids.CRUDE_OIL.get(), "its bucket");
        check(ModFluids.CRUDE_OIL.get().getFluidType() == ModFluids.CRUDE_OIL_TYPE.get(), "its fluid type");
        check(ModFluids.CRUDE_OIL.get().defaultFluidState().is(ModFluids.CRUDE_OIL_TAG), "the source is in c:crude_oil");
        check(ModFluids.FLOWING_CRUDE_OIL.get().defaultFluidState().is(ModFluids.CRUDE_OIL_TAG), "the flowing form is in c:crude_oil");
        check(!ModFluids.othersInTag(), "no other mod's crude oil in this run");
        var tags = new HashSet<String>();
        Fluid fluid = ModFluids.CRUDE_OIL.get();
        fluid.builtInRegistryHolder().tags().forEach(t -> tags.add(t.location().toString()));
        var rule = ConversionRuntime.active().forward(id.toString(), tags, false);
        check(rule != null && rule.id().equals("icheme:c/crude_oil"), "the bundled tag rule answers: " + (rule == null ? "none" : rule.id()));
        helper.succeed();
    }

    private static void check(boolean holds, String what) {
        if (!holds) throw new GameTestAssertException(what);
    }
}
