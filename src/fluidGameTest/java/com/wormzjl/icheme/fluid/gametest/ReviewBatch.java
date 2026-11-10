package com.wormzjl.icheme.fluid.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The review server's batch filter ({@code ./gradlew runReviewWorldServer -PreviewBatch=<batch>}): that batch alone runs
 * and its plants alone stand in the world; unset, every review batch runs as before. NeoForge's GameTest server filters by
 * namespace only ({@code neoforge.enabledGameTestNamespaces}), so each review test asks here first.
 */
final class ReviewBatch {
    static final String PROPERTY = "icheme.review.batch";

    private ReviewBatch() {}

    /** Whether {@code batch} is filtered out of this run; its test then succeeds at once and places nothing. */
    static boolean skipped(GameTestHelper helper, String batch) {
        var only = System.getProperty(PROPERTY, "");
        if (only.isBlank() || only.equals(batch)) return false;
        helper.succeed();
        return true;
    }
}
