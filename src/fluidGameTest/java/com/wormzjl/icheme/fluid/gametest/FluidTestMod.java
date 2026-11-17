package com.wormzjl.icheme.fluid.gametest;

import net.neoforged.fml.common.Mod;

/** Loaded only in the explicit fluid GameTest run, never in the production artifact. */
@Mod("icheme_fluid_test")
public final class FluidTestMod {
    public FluidTestMod() {
        // The dev-only world preset "IChemE column test" (runReviewClient): a no-op in any other world.
        ColumnTestWorld.register(net.neoforged.neoforge.common.NeoForge.EVENT_BUS);
        if(Boolean.getBoolean("icheme.fluid.benchmark")) {
            var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
            bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,FluidServerBenchmark::installFixture);
            bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,FluidServerBenchmark::beforeTick);
            bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,FluidServerBenchmark::afterTick);
        }
    }
}
