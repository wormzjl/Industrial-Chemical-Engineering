package com.wormzjl.icheme.fluid.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@link LevelLoopGameTests}'s drum level loop at the review server's own time factor (batch 2026-11-11-minecraft-time-delta,
 * W6): the review server filters by batch ({@link ReviewBatch}), so this runs alone. Attached by level-loop.init.gradle;
 * not a gate. run.sh sets {@code fluid.timeScale} in the review server's config for the run and restores it. The rig's
 * switches are read here: {@code icheme.levelLoop.cycles} (control cycles the drum holds, default 2),
 * {@code icheme.levelLoop.blanket} (a blanketed drum), {@code icheme.levelLoop.direct} (from empty on the design feed).
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class LevelLoopFactorRun {
    private LevelLoopFactorRun() {}

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "level-loop")
    public static void theDrumLevelLoopAtTheServersFactor(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "level-loop")) return;
        LevelLoopGameTests.run(helper, Integer.getInteger("icheme.levelLoop.cycles", LevelLoopGameTests.CYCLES),
                Boolean.getBoolean("icheme.levelLoop.blanket"), Boolean.getBoolean("icheme.levelLoop.direct"));
    }
}
