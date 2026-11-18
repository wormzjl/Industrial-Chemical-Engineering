package com.wormzjl.icheme.fluid.gametest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/** Loaded only in the explicit fluid GameTest run, never in the production artifact. */
@Mod("icheme_fluid_test")
public final class FluidTestMod {
    public FluidTestMod(IEventBus modBus) {
        modBus.addListener(ElectricGameTests::registerForeignEnergy);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                net.neoforged.bus.api.EventPriority.LOWEST, ExchangerRestartReviewWorld::beforeFirstDecision);
        if(Boolean.getBoolean("icheme.fluid.benchmark")) {
            var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
            bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,FluidServerBenchmark::installFixture);
            bus.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,FluidServerBenchmark::beforeTick);
            bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,FluidServerBenchmark::afterTick);
        }
    }
}
