package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.pollution.Blasts;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Ignition and blasts of flammable cubes (batch 2026-11-15-pollution-system, WP6a): a torch ignites a cube at combustible
 * 1.2 and not one at 0.9, and the flammable face neighbours of a burning cube follow it.
 *
 * <p>Each test fills whole 16 m cubes of a dimension field the world shares, so each runs in a batch of its own, clears the
 * field at its start and its end, and its blasts break no block and light no fire ({@link Blasts#keepBlocks}): the suite
 * reuses one world, and other tests' structures may share these cubes. A cube's air share is read by a pass first (a trace
 * of toxic gas), so the combustible index added is exact; the cubes around the torch's are filled too, so the field's
 * exchange leaves the torch's cube near its index after the pass that installs it (one empty face takes 12 % a pass).</p>
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class PollutionBlastGameTests {
    private PollutionBlastGameTests() {}

    /** The torch stands on a stone in the air above the structure, in a cube above the ground's. */
    private static final BlockPos TORCH = new BlockPos(2, 24, 2);
    /** Ticks from the pass that installs the cloud to its blast: the ignition pass runs every 10. */
    private static final int IGNITES_WITHIN = 40;
    /** Ticks from a cube's blast to its neighbours': the chain is 3 ticks a step. */
    private static final int CHAIN_WITHIN = 20;

    /** The torch's cube and the cubes of {@code reach} (1: its faces; 2: its 3 x 3 x 3 block) around it. */
    private static List<Long> cubes(long centre, int reach) {
        var out = new ArrayList<Long>();
        out.add(centre);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int faces = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (faces == 0 || reach == 1 && faces != 1) {
                        continue;
                    }
                    out.add(CubeKey.offset(centre, dx, dy, dz));
                }
            }
        }
        return out;
    }

    private record Setup(PollutionService service, ResourceKey<Level> dimension, BlockPos torch, long cube, List<Long> cubes) {
        double index(long cube) {
            return service.index(dimension, cube, Channel.COMBUSTIBLE);
        }

        double burnt() {
            return service.ledger(dimension).get(Channel.COMBUSTIBLE, PollutionLedger.Total.BURNT);
        }

        void clean(GameTestHelper helper) {
            service.clear(dimension);
            var level = helper.getLevel();
            level.setBlock(torch, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(torch.below(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            Blasts.keepBlocks(false);
        }
    }

    /** Clears the field, places the torch, and marks the cubes with a trace of toxic gas so the next pass reads their air. */
    private static Setup setUp(GameTestHelper helper, int reach) {
        var level = helper.getLevel();
        var dimension = level.dimension();
        var service = PollutionService.find(level.getServer()).orElseThrow();
        helper.assertTrue(service.enabled(), "Pollution runs in the GameTest server");
        service.clear(dimension);
        Blasts.keepBlocks(true);
        var torch = helper.absolutePos(TORCH);
        level.setBlock(torch.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        long cube = PollutionService.cube(torch);
        var setup = new Setup(service, dimension, torch, cube, cubes(cube, reach));
        for (long c : setup.cubes()) {
            service.add(dimension, c, Channel.TOXIC, 0.01);
        }
        return setup;
    }

    private static void waitForAir(GameTestHelper helper, Setup s) {
        helper.assertTrue(s.service().cell(s.dimension(), s.cube()).loaded(), "Waiting for a pass to read the torch cube's air");
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "pollution_ignite")
    public static void aTorchIgnitesACubeAtTheFlammableLimit(GameTestHelper helper) {
        var s = setUp(helper, 1);
        double[] burnt0 = new double[1];
        long[] installed = new long[1];
        double[] installedIndex = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> waitForAir(helper, s))
                .thenExecute(() -> {
                    burnt0[0] = s.burnt();
                    for (long c : s.cubes()) {
                        s.service().add(s.dimension(), c, Channel.COMBUSTIBLE, 1.2);
                    }
                })
                // The pass that installs the cloud: the cube holds combustible, or has burnt it already.
                .thenWaitUntil(() -> {
                    boolean in = s.service().field(s.dimension()).amount(s.cube(), Channel.COMBUSTIBLE) > 0 || s.burnt() > burnt0[0];
                    helper.assertTrue(in, "Waiting for the pass to install the cloud");
                    installed[0] = helper.getTick();
                    installedIndex[0] = s.burnt() > burnt0[0] ? Double.NaN : s.index(s.cube());
                })
                .thenWaitUntil(() -> helper.assertTrue(s.burnt() > burnt0[0] && s.index(s.cube()) < 0.1,
                        "Waiting for the torch to ignite the cube; index " + s.index(s.cube())))
                .thenExecute(() -> {
                    long took = helper.getTick() - installed[0];
                    double after = s.index(s.cube());
                    double burnt = s.burnt() - burnt0[0];
                    s.clean(helper);
                    helper.assertTrue(Double.isNaN(installedIndex[0]) || installedIndex[0] >= 1.0,
                            "The installed cloud is flammable: index " + installedIndex[0]);
                    helper.assertTrue(took <= IGNITES_WITHIN, "Ignited " + took + " ticks after the pass, at most " + IGNITES_WITHIN);
                    helper.assertTrue(after < 0.1, "The cube burnt: index " + after);
                    helper.assertTrue(burnt > 0, "The ledger books the burnt combustible: " + burnt);
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "pollution_no_ignite")
    public static void aTorchDoesNotIgniteACubeBelowTheLimit(GameTestHelper helper) {
        var s = setUp(helper, 1);
        double[] burnt0 = new double[1];
        double[] highest = new double[1];
        helper.startSequence()
                .thenWaitUntil(() -> waitForAir(helper, s))
                .thenExecute(() -> {
                    burnt0[0] = s.burnt();
                    for (long c : s.cubes()) {
                        s.service().add(s.dimension(), c, Channel.COMBUSTIBLE, 0.9);
                    }
                })
                .thenWaitUntil(() -> helper.assertTrue(s.service().field(s.dimension()).amount(s.cube(), Channel.COMBUSTIBLE) > 0,
                        "Waiting for the pass to install the cloud"))
                // Six ignition passes.
                .thenExecuteFor(60, () -> highest[0] = Math.max(highest[0], s.index(s.cube())))
                .thenExecute(() -> {
                    double burnt = s.burnt() - burnt0[0];
                    double index = s.index(s.cube());
                    s.clean(helper);
                    helper.assertTrue(highest[0] < 1.0 && highest[0] > 0.5, "The installed cloud stays below the limit: index " + highest[0]);
                    helper.assertTrue(burnt == 0, "Nothing burnt: " + burnt);
                    helper.assertTrue(index > 0.5, "The cloud is still there: index " + index);
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "pollution_chain")
    public static void flammableNeighboursFollowTheBlast(GameTestHelper helper) {
        var s = setUp(helper, 2);
        long below = CubeKey.offset(s.cube(), 0, -1, 0);
        long above = CubeKey.offset(s.cube(), 0, 1, 0);
        double[] burnt0 = new double[1];
        long[] first = new long[1];
        helper.startSequence()
                .thenWaitUntil(() -> waitForAir(helper, s))
                .thenExecute(() -> {
                    burnt0[0] = s.burnt();
                    for (long c : s.cubes()) {
                        s.service().add(s.dimension(), c, Channel.COMBUSTIBLE, 1.5);
                    }
                })
                // The torch's cube burns first; the faces of the 3 x 3 x 3 block (one empty face each) stay near 1.26 after the
                // pass, the one below near 1.09 (it drifts up into the centre and takes nothing from below).
                .thenWaitUntil(() -> {
                    helper.assertTrue(s.burnt() > burnt0[0] && s.index(s.cube()) < 0.1, "Waiting for the torch's cube to burn");
                    first[0] = helper.getTick();
                })
                .thenWaitUntil(() -> helper.assertTrue(s.index(below) < 0.1 && s.index(above) < 0.1,
                        "Waiting for the neighbours below and above to burn; " + s.index(below) + " and " + s.index(above)))
                .thenExecute(() -> {
                    long took = helper.getTick() - first[0];
                    s.clean(helper);
                    helper.assertTrue(took <= CHAIN_WITHIN, "The neighbours followed " + took + " ticks after, at most " + CHAIN_WITHIN);
                })
                .thenSucceed();
    }
}
