package com.wormzjl.icheme.fluid.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link ConverterGameTests} alone on the review server (batch 2026-11-12-fluid-conversion, W3): the review server filters by
 * batch ({@link ReviewBatch}). Attached by converter.init.gradle; not a gate (the gate is the fluid GameTest itself, in the
 * final lane).
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ConverterRun {
    private ConverterRun() {}

    @GameTest(template = "empty", timeoutTicks = 16000, batch = "converter-liquid")
    public static void aLiquidConverterAlone(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "converter")) return;
        ConverterGameTests.aLiquidConverterConvertsBothWaysThroughItsForgePort(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 16000, batch = "converter-gas")
    public static void aGasConverterAlone(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "converter")) return;
        ConverterGameTests.aGasConverterRegistersClosedAndTakesItsValves(helper);
    }
}
