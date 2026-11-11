package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A blocked-in drum relieving to a flare stack (batch 2026-11-11-gas-blanketing, PRESSURE_SAFETY_VALVE_PLAN.md WP5): a
 * horizontal drum fed methane at 5 bar through its side inlet, every other nozzle open-ended and closed, its pressure safety
 * valve on the second top flange at the default set pressure, its outlet piped to a flare stack's gas inlet. Expected: the
 * valve holds the drum at its set pressure and the feed leaves through the flare, piped (not to the atmosphere).
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ReliefFlareGameTests {
    private ReliefFlareGameTests() {}

    static final double FEED = 500_000, DISCHARGE = .15, TIP = .3;

    @GameTest(template = "empty", timeoutTicks = 8000, batch = "psv-flare")
    public static void aBlockedInDrumRelievesToAFlareStack(GameTestHelper helper) {
        run(helper);
    }

    static void run(GameTestHelper helper) {
        var plant = new AssemblyGameTests.Plant(helper, helper.absolutePos(new BlockPos(8, 4, 8)), new BlockPos(-8, -3, -8), new BlockPos(24, 40, 16));
        var level = plant.level;
        var world = plant.world;
        long[] ids = new long[3];
        // The relief at the last two publications, and the last one's committed tick.
        double[] last = {-1, -1, -1};
        String[] failure = {null};
        Runnable cleanup = () -> {
            plant.remove();
            plant.release();
        };
        try {
            plant.hold();
            var drum = plant.place(EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 2), 0, 6, 0);
            var place = InstrumentHosts.ReliefValve.of(level, drum.origin(), drum.shape());
            if (place == null) throw new IllegalStateException("The drum has no flange for a pressure safety valve");
            // The feed: methane at 5 bar (a flare burns fuel gas; on nitrogen its tip passes only what keeps a stable flame,
            // 8e-4 kg/s through a 0.1 m tip, and the drum rises over its set pressure) out along the drum's side inlet.
            var feed = LevelLoopGameTests.line(plant, drum, Role.SIDE, 4, 0);
            level.setBlock(feed.getLast(), ModBlocks.FLUID_GENERATOR.get().defaultBlockState(), Block.UPDATE_ALL);
            plant.devices.add(feed.getLast());
            for (int k = feed.size() - 2; k >= 0; k--) plant.pipe(feed.get(k));
            // The flare beside the drum, its gas inlet routed from the valve's outlet cell.
            var flare = plant.place(tip(TIP), 12, 0, 0);
            var inlet = AssemblyGameTests.Plant.front(flare, Role.FLARE_GAS);
            var cell = place.port().cell();
            var outlet = new BlockPos(cell.x(), cell.y(), cell.z()).relative(Direction.valueOf(place.port().face().name()));
            var discharge = plant.route(outlet, inlet);
            ShapeRasterizer.Port gas = AssemblyGameTests.Plant.port(flare, Role.FLARE_GAS);
            long generator = LevelLoopGameTests.id(plant, feed.getLast());
            TankCharge.run(world, generator, new FluidDeviceSpec(1, 300, FEED, methane()));
            // A 0.15 m discharge line: the pipe tool's default bore carries about 0.5 kg/s of this gas at the 100 m/s cap, and the drum
            // rises toward its feed's pressure (built-up back pressure; measured 4.92 bar on the first run).
            world.resizeLine(LevelLoopGameTests.id(plant, discharge.get(1)), DISCHARGE, x -> com.wormzjl.icheme.science.equipment.PipeSizes.MAX_BORE);
            ids[1] = LevelLoopGameTests.id(plant, flare.origin().offset(gas.cell().x(), gas.cell().y(), gas.cell().z()));
            helper.startSequence()
                    .thenWaitUntil(() -> {
                        var host = InstrumentHosts.at(level, drum.controller());
                        helper.assertTrue(host != null && world.view(host.identity()).tank() != null, "waiting for the drum");
                    })
                    .thenExecute(() -> {
                        ids[0] = InstrumentHosts.at(level, drum.controller()).identity();
                        if (!world.reliefValve(ids[0], place.port())) throw new IllegalStateException("The pressure safety valve was not fitted");
                    })
                    .thenWaitUntil(() -> {
                        var view = world.view(ids[0]);var flareView = world.view(ids[1]).flare();
                        double set = world.registrations().get(ids[0]).spec().relief();
                        double p = view.state() == null ? 0 : view.state().pressure();
                        // Settled: the valve carries the feed (more than 0.1 kg/s), the same within 5 % on two publications.
                        double q = view.relief() == null ? 0 : view.relief().massFlow();
                        if (view.committedTick() != last[2]) {last[2] = view.committedTick();last[0] = last[1];last[1] = q;}
                        boolean settled = q > .1 && last[0] > 0 && Math.abs(last[1] - last[0]) < .05 * last[1] && flareView != null && flareView.massFlow() > 0;
                        helper.assertTrue(settled && Math.abs(p - set) < .03 * set, "waiting for the relief to settle: P=" + p + " set=" + set + " relief=" + view.relief() + " flare=" + (flareView == null ? null : flareView.massFlow()));
                    })
                    .thenExecute(() -> {
                        var view = world.view(ids[0]);var flareView = world.view(ids[1]).flare();
                        double set = world.registrations().get(ids[0]).spec().relief();
                        IChemE.LOGGER.info("psv_flare result P={} set={} relief={} kg/s open={} flare={} kg/s status={}", view.state().pressure(), set, view.relief().massFlow(),
                                view.relief().open(), flareView.massFlow(), view.status());
                        if (view.relief().open()) failure[0] = "the valve relieves to the atmosphere, not to the flare";
                        else if (Math.abs(view.relief().massFlow() - flareView.massFlow()) > .1 * flareView.massFlow()) failure[0] = "the flare burns " + flareView.massFlow() + " kg/s of the valve's " + view.relief().massFlow();
                        world.reliefValve(ids[0], null);
                        cleanup.run();
                    })
                    .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "waiting for the plant's removal"))
                    .thenExecute(() -> {if (failure[0] != null) throw new GameTestAssertException(failure[0]);})
                    .thenSucceed();
        } catch (RuntimeException e) {
            IChemE.LOGGER.error("psv_flare setup failed", e);
            cleanup.run();
            throw new GameTestAssertException("psv flare setup: " + e.getMessage());
        }
    }

    /** One mole of methane on the network's list. */
    static double[] methane() {
        var catalog = MaterialRuntime.current();
        var ids = new java.util.ArrayList<>(catalog.requirePackage(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog)).components());
        ids.add("Water");
        var n = new double[ids.size()];
        n[new com.wormzjl.icheme.science.material.MaterialAxis(ids).requireIndex("Methane")] = 1;
        return n;
    }

    /** A flare stack with tip {@code tip}, m, as the constructor builds one. */
    static EquipmentShape tip(double tip) {
        var d = com.wormzjl.icheme.science.equipment.UnitDesign.of(EquipmentShape.Kind.FLARE_STACK);
        int length = d.defaultLength(tip);
        return EquipmentShape.designed(EquipmentShape.Kind.FLARE_STACK, tip, length, EquipmentShape.Heading.NORTH, d.defaultSupport(tip, length), com.wormzjl.icheme.science.equipment.ProcessUnit.Bank.ONE);
    }
}
