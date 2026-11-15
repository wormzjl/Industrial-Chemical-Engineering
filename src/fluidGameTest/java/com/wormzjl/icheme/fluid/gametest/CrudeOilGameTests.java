package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModFluids;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.stream.Collectors;

/**
 * The placeholder crude oil (batch 2026-11-15-fluid-interfaces, W3): the fluid is registered, it is in {@code c:crude_oil},
 * and the bundled conversion rule {@code #c:crude_oil} answers it in the forward direction.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class CrudeOilGameTests {
    private CrudeOilGameTests() {}

    @GameTest(template = "empty", batch = "crude-oil")
    public static void thePlaceholderCrudeIsTaggedAndConverts(GameTestHelper helper) {
        var id = ResourceLocation.fromNamespaceAndPath("icheme", "crude_oil");
        helper.assertTrue(BuiltInRegistries.FLUID.containsKey(id), "The crude oil fluid is registered");
        var holder = BuiltInRegistries.FLUID.getHolder(id).orElseThrow();
        helper.assertTrue(holder.is(ModFluids.CRUDE_OIL_TAG), "The crude oil is in c:crude_oil");
        helper.assertTrue(ModFluids.CRUDE_OIL_BUCKET.get().content == ModFluids.CRUDE_OIL.get(), "Its bucket holds it");
        var tags = holder.tags().map(t -> t.location().toString()).collect(Collectors.toUnmodifiableSet());
        var rule = ConversionRuntime.active().forward(id.toString(), tags, false);
        helper.assertTrue(rule != null && "#c:crude_oil".equals(rule.fluid()), "The bundled #c:crude_oil rule converts it: " + rule);
        helper.succeed();
    }
}
