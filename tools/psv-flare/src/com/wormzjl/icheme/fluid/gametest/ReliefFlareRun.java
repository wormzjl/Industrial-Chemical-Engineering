package com.wormzjl.icheme.fluid.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link ReliefFlareGameTests}' blocked-in drum relieving to a flare, alone on the review server (batch
 * 2026-11-11-gas-blanketing): the review server filters by batch ({@link ReviewBatch}). Attached by psv-flare.init.gradle;
 * not a gate (the gate is the fluid GameTest itself, in the final lane).
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ReliefFlareRun {
    private ReliefFlareRun() {}

    @GameTest(template = "empty", timeoutTicks = 8000, batch = "psv-flare")
    public static void aBlockedInDrumRelievesToAFlareStackAlone(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "psv-flare")) return;
        ReliefFlareGameTests.aBlockedInDrumRelievesToAFlareStack(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 8000, batch = "psv-flare-small")
    public static void aSmallFlareAlone(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "psv-flare")) return;
        ReliefFlareGameTests.aDrumRelievesToASmallFlareWithoutAHold(helper);
    }

    @GameTest(template = "empty", timeoutTicks = 8000, batch = "psv-flare-inert")
    public static void nitrogenToAFlareAlone(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "psv-flare")) return;
        ReliefFlareGameTests.aDrumRelievesNitrogenToASmallFlareWithoutAHold(helper);
    }
}
