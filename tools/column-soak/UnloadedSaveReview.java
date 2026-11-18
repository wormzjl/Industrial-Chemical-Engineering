package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.plant.column.Setpoints;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.concurrent.locks.LockSupport;

/** Opt-in headless review of a disposable copy of the two-heater save. */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
@net.neoforged.fml.common.EventBusSubscriber(modid = "icheme_fluid_test")
public final class UnloadedSaveReview {
    private UnloadedSaveReview() {}

    /** GameTestServer creates fresh level settings and applies --spawnPos only AFTER preparing the initial region. */
    @net.neoforged.bus.api.SubscribeEvent
    public static void beforeLevels(net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        if (!"unloaded-save".equals(System.getProperty(ReviewBatch.PROPERTY))) return;
        var data = event.getServer().getWorldData().overworldData();
        data.setSpawn(new BlockPos(16000,60,16000), 0);
        data.setInitialized(true);
    }

    @GameTest(template = "empty", timeoutTicks = 6300, batch = "unloaded-save")
    public static void savedPlant(GameTestHelper helper) {
        if (ReviewBatch.skipped(helper, "unloaded-save")) return;
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var columns = world.powerTopology().columns().values().stream().filter(u -> u.running()).toList();
        helper.assertTrue(columns.size() == 1, "Expected the saved running column");
        var column = columns.getFirst();
        var master = column.master();
        var shell = new BlockPos(master.x(), master.y(), master.z());
        var burners = world.registrations().values().stream().filter(r -> r.device().burner()).toList();
        helper.assertTrue(burners.size() == 2, "Expected both heaters from the save");
        var cells = new HashSet<BlockPos>();
        cells.add(shell);
        cells.add(new BlockPos(48,-59,44));
        cells.add(new BlockPos(89,-59,44));
        for (var r : burners) {
            var p = r.device().position();
            cells.add(new BlockPos(p.x(), p.y(), p.z()));
        }
        var refilled = new HashSet<Long>();
        int[] elapsed = {0};
        boolean[] solved = {false};
        long[] editTick = {Long.MAX_VALUE};
        long start = System.nanoTime();
        helper.onEachTick(() -> {
            int tick = ++elapsed[0];
            LockSupport.parkNanos(Math.max(0, start + tick * 50_000_000L - System.nanoTime()));
            for (var pos : cells)
                helper.assertTrue(!level.hasChunkAt(pos), "Plant chunk loaded during review at " + pos + " tick " + tick);
            for (var r : burners) {
                long id = r.device().id();
                var store = world.electric().view(id);
                helper.assertTrue(store != null && !store.unpowered(), "Burner " + id + " lost power at tick " + tick);
                if (store.inPerTick() > 0) refilled.add(id);
            }
            var view = world.columnView(master, id -> Double.NaN);
            if (tick == 300) {
                editTick[0] = world.onlineTick();
                var s = column.setpoints();
                var edited = new Setpoints(s.designFeedRate(), s.feedRate(), s.condenserTemperature() + .01,
                        s.refluxRatio(), s.reboilerPerFeed(), s.steamPerFeed(), s.drawsPerFeed(), s.pumparoundsPerFeed());
                helper.assertTrue(world.editColumn(master, edited) == null, "Column rejected its set point");
            }
            if (tick > 300 && view != null && view.solve() != null && view.solve().trigger() != null
                    && view.solve().age() <= com.wormzjl.icheme.science.fluid.SimulationTime.playSeconds(world.onlineTick() - editTick[0])
                    && view.accepted() == null && view.setpoints().condenserTemperature() == column.setpoints().condenserTemperature() + .01)
                solved[0] = true;
            if (tick % 1200 == 0)
                IChemE.LOGGER.info("unloaded_save tick={} chunks_absent={} refilled={} fresh_table={} column={}",
                        tick, cells.size(), refilled, solved[0], view == null ? "absent" : view.phase());
            if (tick == 6000) {
                helper.assertTrue(refilled.size() == burners.size(), "Both burners must receive FE while unloaded");
                helper.assertTrue(solved[0] && view != null && view.solve() != null, "No fresh accepted table in unloaded column");
                helper.succeed();
            }
        });
    }
}
