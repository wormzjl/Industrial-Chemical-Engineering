package com.wormzjl.icheme.fluid.gametest;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.WorldPresetTags;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The column test world's preset path without a client (COLUMN_TEST_WORLD.md): the preset is loaded from the test mod's
 * data and listed in the {@code minecraft:normal} tag (the Create World screen's list), its overworld is recognised and the
 * GameTest world's own flat world is not; then the plant is built through the preset's entry ({@link ColumnTestWorld#build})
 * on clear ground of this world and started by the preset's tick-driven start ({@link ColumnTestWorld.Start}), which must
 * reach its end with the column past its first solve.
 *
 * <p>Runs only when named: {@code ./gradlew runReviewWorldServer -PreviewBatch=review-column-test-world}.
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ColumnTestWorldReviewWorld {
    private ColumnTestWorldReviewWorld() {}

    static final String BATCH = "review-column-test-world";

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = BATCH)
    public static void theColumnTestWorldStartsItsPlant(GameTestHelper helper) {
        AssemblyGameTests.clearStartControls(helper);
        if (!BATCH.equals(System.getProperty(ReviewBatch.PROPERTY, ""))) {
            helper.succeed();
            return;
        }
        var server = helper.getLevel().getServer();
        var registry = server.registryAccess().registryOrThrow(Registries.WORLD_PRESET);
        var preset = registry.getHolder(ColumnTestWorld.PRESET).orElse(null);
        if (preset == null) throw new IllegalStateException("The preset " + ColumnTestWorld.PRESET.location() + " is not loaded");
        if (!preset.is(WorldPresetTags.NORMAL)) throw new IllegalStateException("The preset is not in the minecraft:normal tag: the Create World screen does not list it");
        var settings = ColumnTestWorld.presetSettings(server);
        if (settings == null) throw new IllegalStateException("The preset's overworld is not a flat generator");
        if (!settings.structureOverrides().map(s -> s.size() == 0).orElse(false)) throw new IllegalStateException("The preset carries structures: " + settings.structureOverrides());
        if (!ColumnTestWorld.isPreset(server, new FlatLevelSource(settings))) throw new IllegalStateException("The preset's own overworld is not recognised");
        if (ColumnTestWorld.isPreset(server, helper.getLevel().getChunkSource().getGenerator()))
            throw new IllegalStateException("The GameTest world's flat world is taken for the preset's");
        com.wormzjl.icheme.IChemE.LOGGER.info("column_test_world review preset={} layers={} biome={} recognised=true gametest_world=false", ColumnTestWorld.PRESET.location(),
                settings.getLayersInfo(), settings.getBiome().unwrapKey().map(k -> k.location().toString()).orElse("?"));

        // The plant through the preset's entry, at clear ground of the test grid (the rig's own search).
        var options = ColumnOperationReviewWorld.review(com.wormzjl.icheme.science.material.MaterialRuntime.active());
        var base = ColumnOperationReviewWorld.rig(helper, options).plant.base;
        var rig = ColumnTestWorld.build(helper.getLevel(), base, options);
        if (!rig.plant.base.equals(base)) throw new IllegalStateException("The preset's build stands at " + rig.plant.base.toShortString() + ", not on the ground at " + base.toShortString());
        boolean[] ended = {false, false};
        var start = new ColumnTestWorld.Start(rig, ok -> {
            ended[0] = true;
            ended[1] = ok;
        }, true);
        ColumnTestWorld.begin(start);
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ended[0], "Waiting for the column test world's start: " + start.state()))
                .thenExecute(() -> {
                    rig.plant.release();
                    if (!ended[1]) helper.fail("The column test world's start stopped at " + start.state());
                })
                .thenSucceed();
    }
}
