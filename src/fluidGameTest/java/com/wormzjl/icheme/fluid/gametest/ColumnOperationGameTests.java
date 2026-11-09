package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.network.FluidNetwork;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.fluid.ColumnUnit;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.science.column.operation.ColumnCycle;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A column's life in the world (batch 2026-10-28-column-operation, gates of plan C3, C4 and C6b): on the plant of
 * {@link ColumnOperationReviewWorld}, Start, the column running at its design feed with its ports splitting the islands,
 * its cooling water warmed by Q/(m c_p), a blocked product cutting the feed, Stop keeping the members' contents, and
 * dismantling booking the accounts. One plant takes one column's start, so the stages run in order on it. The test removes
 * what it placed, its result held until the cleanup is done.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ColumnOperationGameTests {
    private ColumnOperationGameTests() {}

    /** A menu open reads what is stored: a resting island's clock stays where it stands, where a live read advances it (merge review, finding 8). */
    @GameTest(template = "empty", timeoutTicks = 10000, batch = "column-read")
    public static void aMenuReadAdvancesNoIsland(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var pos = helper.absolutePos(new BlockPos(0, 1, 0));
        level.setBlock(pos, ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), 3);
        long device = ((FluidDeviceBlockEntity) level.getBlockEntity(pos)).fluidIdentity();
        java.util.function.LongSupplier committed = () -> world.diagnosticSnapshots().stream().filter(s -> s.graph().reservoirs().stream().anyMatch(n -> n.id() == device)).findFirst().orElseThrow().clock().committedTick();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(world.diagnosticSnapshots().stream().anyMatch(s -> s.certificate().isPresent() && s.graph().reservoirs().stream().anyMatch(n -> n.id() == device)), "Waiting for the tank to certify"))
                .thenIdle(60)
                .thenExecute(() -> {
                    long before = committed.getAsLong();
                    FluidNetwork.opening(world, device);
                    helper.assertTrue(committed.getAsLong() == before, "The read advanced the island past " + before);
                    world.view(device);
                    helper.assertTrue(committed.getAsLong() > before, "A live read should advance what the stored read left");
                    level.removeBlock(pos, false);
                })
                .thenWaitUntil(() -> helper.assertTrue(!world.capture().world().active().containsKey(device), "Waiting for topology cleanup"))
                .thenSucceed();
    }

    /** Liquid water near 40 C, J/(kg K): the heat balance of the condenser's tubes. */
    private static final double WATER_HEAT_CAPACITY = 4180;
    /** The heat balance's tolerance: the water's specific heat over this range, and a duty that moves a little between cycles. */
    private static final double HEAT_TOLERANCE = .05;
    /** The ticks before the water's balance is read: the fence the heat waits behind, the first cycles' duty washed out, the first short slice. */
    private static final int SETTLE_TICKS = 1100;
    /**
     * The ticks the water's outlet is averaged over, by slice length. A slice takes the 5 s cycles' heat that came due before it,
     * up to what the approach to the condenser allows, and the slices are shorter than the cycles: every fourth to tenth slice
     * takes what is left, some 10 K cooler. The mean closes the balance over those beats; one slice's weight stays small.
     */
    private static final int MEAN_TICKS = 1200;

    private static final class Life {
        final ColumnOperationReviewWorld.Rig rig;
        final String[] failure = {null};
        Map<Long, double[]> before = Map.of();
        Set<Long> members = Set.of();
        double[] accounts;
        double[] destroyed;

        Life(ColumnOperationReviewWorld.Rig rig) {
            this.rig = rig;
        }

        OperationView view() {
            return rig.plant.world.columnView(rig.plant.position(rig.plant.column.origin()), id -> Double.NaN);
        }

        ColumnUnit unit() {
            return rig.plant.world.column(rig.plant.position(rig.plant.column.origin()));
        }

        double feedRate(OperationView view) {
            return view.streams().stream().filter(s -> s.port() == Port.FEED).mapToDouble(OperationView.Stream::rate).sum();
        }

        double design() {
            return Arrays.stream(rig.input.feedComponentMolarFlowsMolPerSecond()).sum();
        }

        boolean failed() {
            return failure[0] != null;
        }

        /** Every node's inventory, by device, as the islands hold it now. */
        Map<Long, double[]> contents() {
            // On the world list by compound id: each island runs on its own list, which a Stop may change.
            var result = new HashMap<Long, double[]>();
            var world = rig.plant.world;
            for (var island : world.diagnosticSnapshots()) {
                var own = world.diagnosticModel(island.id());
                for (var node : island.graph().reservoirs())
                    result.put(node.id(), com.wormzjl.icheme.runtime.fluid.IslandSets.amounts(node.inventory().moles(), own, world.model()));
            }
            return result;
        }

        /** Runs {@code step} unless a stage failed, a step's exception its failure. */
        void stage(Runnable step) {
            if (failed()) return;
            try {
                step.run();
            } catch (RuntimeException e) {
                failure[0] = e.toString();
                IChemE.LOGGER.error("column_gametest stage failed", e);
            }
        }

        void check(boolean holds, String what) {
            if (!holds && failure[0] == null) failure[0] = what;
        }
    }

    @GameTest(template = "empty", timeoutTicks = 20000, batch = "column-operation")
    public static void aColumnRunsInItsPlantStopsAndLeaves(GameTestHelper helper) {
        var rig = ColumnOperationReviewWorld.rig(helper);
        var life = new Life(rig);
        var plant = rig.plant;
        try {
            rig.build();
        } catch (RuntimeException e) {
            life.failure[0] = "build: " + e.getMessage();
            IChemE.LOGGER.error("column_gametest build failed", e);
        }
        double[] warmed = new double[4];
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(life.failed() || plant.valvesInstalled(), "Waiting for the valves"))
                .thenIdle(20)
                .thenExecute(() -> life.stage(() -> {
                    life.before = life.contents();
                    rig.start(life.failure);
                }))
                .thenWaitUntil(() -> helper.assertTrue(life.failed() || rig.paced() && atDesign(life), "Waiting for the column at its design feed"))
                .thenExecute(() -> life.stage(() -> running(life)))
                .thenWaitUntil(() -> helper.assertTrue(life.failed() || rig.paced() && warmedOver(life, warmed), "Waiting for the cooling water"))
                .thenExecute(() -> life.stage(() -> {
                    warms(life, warmed[1] / warmed[3]);
                    // Nothing downstream of the smallest product's valve: its buffer fills and cuts the feed.
                    plant.level.removeBlock(rig.sinks.get(2), false);
                }))
                .thenWaitUntil(() -> helper.assertTrue(life.failed() || rig.paced() && limited(life), "Waiting for the full buffer"))
                .thenExecute(() -> life.stage(() -> {
                    cut(life);
                    AssemblyService.stop(plant.level, plant.column.origin());
                }))
                .thenWaitUntil(() -> helper.assertTrue(life.failed() || stopped(life), "Waiting for the column to stop"))
                .thenExecute(() -> life.stage(() -> {
                    kept(life);
                    var unit = life.unit();
                    life.accounts = new double[unit.stations().getFirst().account().moles().length];
                    for (var s : unit.stations()) {
                        double[] n = s.account().moles();
                        for (int c = 0; c < n.length; c++) life.accounts[c] += n[c];
                    }
                    life.destroyed = plant.world.capture().world().destroyed().moles();
                }))
                .thenExecute(() -> {
                    var unit = life.failed() ? null : life.unit();
                    plant.remove();
                    if (unit != null) life.stage(() -> life.check(life.unit() == null, "A dismantled column leaves its unit"));
                })
                .thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the plant to leave"))
                .thenExecute(() -> {
                    life.stage(() -> booked(life));
                    plant.release();
                    if (life.failed()) helper.fail(life.failure[0]);
                })
                .thenSucceed();
    }

    /** The column past its start, taking its design feed unlimited; a column that cannot get there fails the test. */
    private static boolean atDesign(Life life) {
        var view = life.view();
        if (view == null) return false;
        if (view.phase() == OperationView.Phase.NOT_READY || view.phase() == OperationView.Phase.UPSET) life.failure[0] = "column " + view.phase() + ": " + view.engine();
        return view.phase() == OperationView.Phase.RUNNING && view.limit() == ColumnCycle.Limit.NONE && view.alpha() == 1 && life.feedRate(view) >= .99 * life.design();
    }

    /** Plan C4: a running column at its design; C3: the ports split the islands, and no flow reaches a member. */
    private static void running(Life life) {
        var view = life.view();
        var unit = life.unit();
        var world = life.rig.plant.world;
        life.check(unit != null && unit.running(), "The column runs");
        life.members = new HashSet<>();
        for (var r : world.registrations().values()) if (r.device().suspendedBy() == unit.id()) life.members.add(r.device().id());
        life.check(life.members.size() > 40, "The column holds its members and the lines between them: " + life.members.size());
        double feed = life.feedRate(view);
        log("feed_mol_s={} design_mol_s={} members={}", feed, life.design(), life.members.size());
        life.check(Math.abs(feed - life.design()) <= .01 * life.design(), "The feed runs at the design rate: " + feed + " of " + life.design());
        for (var s : view.streams()) life.check(s.rate() > 0, "Every stream runs: " + s.port() + s.index());

        var ports = new HashSet<Long>();
        for (var s : unit.stations()) ports.add(s.device());
        ports.remove(0L);
        life.check(ports.size() == unit.stations().size(), "Every station stands in the world");
        for (var island : world.diagnosticSnapshots()) {
            int held = 0;
            for (var node : island.graph().reservoirs()) {
                if (ports.contains(node.id())) held++;
                // The condenser's water side stays in the network, the column unit its partner (C6b).
                // A held vessel stands alone with its contents; nothing flows through a member while its column runs.
                if (life.members.contains(node.id()) && node.id() != unit.condenser())
                    life.check(island.graph().pipes().isEmpty(), "A member connects to nothing: " + node.id() + " in island " + island.id() + " with " + island.graph().pipes().size() + " pipes, condenser " + unit.condenser());
            }
            life.check(held <= 1, "Each port has its own island: " + held + " in island " + island.id());
            life.check(!island.status().startsWith("HELD"), "No island holds: " + island.status());
        }
        double taken = unit.totals().taken().total(), produced = unit.totals().produced().total();
        log("taken_mol={} produced_mol={}", taken, produced);
        life.check(taken > 0 && Math.abs(taken - produced) <= 1e-6 * taken, "What the column took it gave: " + taken + " and " + produced);
    }

    /** Counts the settling ticks ({@code warmed[0]}), then sums the tubes' outlet temperature over the slices the water island commits, weighted by their ticks: {@code warmed[1]} the sum, {@code warmed[2]} the last slice's end, {@code warmed[3]} the ticks. */
    private static boolean warmedOver(Life life, double[] warmed) {
        if (++warmed[0] <= SETTLE_TICKS) return false;
        for (var island : life.rig.plant.world.diagnosticSnapshots()) {
            var tubes = outlet(life, island.graph());
            long end = island.clock().committedTick();
            if (tubes == null || end == warmed[2]) continue;
            if (warmed[2] > 0) {
                warmed[1] += tubes.state().temperature() * (end - warmed[2]);
                warmed[3] += end - warmed[2];
            }
            warmed[2] = end;
        }
        return warmed[3] >= MEAN_TICKS;
    }

    /** The node the condenser's heat side discharges into: the water leaves the tubes at its temperature. */
    private static PassiveNetwork.Reservoir outlet(Life life, PassiveNetwork graph) {
        for (var pipe : graph.pipes())
            if (pipe.heat() != null && graph.reservoirs().get(pipe.first()).id() == life.unit().condenser()) return graph.reservoirs().get(pipe.second());
        return null;
    }

    /** The settled column's duties as solved; plan C6b: the water through the condenser's tubes warms by the duty over its flow and heat capacity. */
    private static void warms(Life life, double outletTemperature) {
        var rig = life.rig;
        var world = rig.plant.world;
        for (var d : life.view().duties()) life.check(Math.abs(d.booked() - d.solved()) <= .01 * Math.abs(d.solved()), "A settled duty is booked as solved: " + d.kind() + d.index() + " " + d.booked() + " of " + d.solved());
        double duty = life.view().duties().stream().filter(d -> d.kind().name().equals("CONDENSER")).mapToDouble(OperationView.Duty::booked).sum();
        double flow = Math.abs(world.view(rig.id(rig.water)).massFlow());
        double warm = outletTemperature - ColumnOperationReviewWorld.WATER_TEMPERATURE, expected = Math.abs(duty) / (flow * WATER_HEAT_CAPACITY);
        log("condenser_W={} condenser_K={} water_kg_s={} warm_K={} expected_K={}", duty, life.unit().setpoints().condenserTemperature(), flow, warm, expected);
        life.check(duty < 0 && flow > 0, "The condenser rejects heat to running water: " + duty + " W, " + flow + " kg/s");
        life.check(Math.abs(warm - expected) <= HEAT_TOLERANCE * expected, "The water warms by Q/(m c_p): " + warm + " K, " + expected + " K expected");
    }

    /** The column at its outlet's limit with the feed all but cut; a column that stops instead fails the test. */
    private static boolean limited(Life life) {
        var view = life.view();
        if (view == null) return false;
        if (!view.running()) life.failure[0] = "A blocked product does not stop the column: " + view.phase() + " " + view.engine();
        return view.limit() == ColumnCycle.Limit.OUTLET && view.alpha() < .05;
    }

    /** Plan C3: the blocked product's buffer is full and the feed stands, the column still running. */
    private static void cut(Life life) {
        var view = life.view();
        double held = view.streams().stream().filter(s -> s.port() == Port.DRAW && s.index() == 2).mapToDouble(OperationView.Stream::held).sum();
        log("alpha={} held={} feed_mol_s={}", view.alpha(), held, life.feedRate(view));
        life.check(view.phase() == OperationView.Phase.LIMITED, "The column says it is limited: " + view.phase());
        life.check(held >= .99, "The blocked product's buffer is full: " + held);
    }

    /** The unit stopped and its members back in the islands. */
    private static boolean stopped(Life life) {
        var unit = life.unit();
        if (unit == null || unit.running()) return false;
        var contents = life.contents();
        for (var island : life.rig.plant.world.diagnosticSnapshots()) if (island.status().startsWith("WAITING")) return false;
        return contents.size() > 0;
    }

    /** Plan C3: Start and Stop keep every member's contents. */
    private static void kept(Life life) {
        var after = life.contents();
        double worst = 0;
        int compared = 0;
        for (long member : life.members) {
            double[] was = life.before.get(member), now = after.get(member);
            if (was == null) continue;
            life.check(now != null, "A member keeps its node: " + member);
            if (now == null) continue;
            compared++;
            double total = Arrays.stream(was).sum();
            for (int c = 0; c < was.length; c++) worst = Math.max(worst, Math.abs(was[c] - now[c]) / Math.max(total, 1e-12));
        }
        log("members_compared={} of={} worst={}", compared, life.members.size(), worst);
        life.check(compared > 0, "The members' contents were read");
        life.check(worst <= 1e-9, "Start and Stop keep the members' contents: worst " + worst);
    }

    /** Plan C3: what the accounts held is booked as destroyed, on top of what the plant's removal destroyed. */
    private static void booked(Life life) {
        double[] now = life.rig.plant.world.capture().world().destroyed().moles();
        double held = 0, worst = 0;
        for (int c = 0; c < now.length; c++) {
            held += life.accounts[c];
            worst = Math.max(worst, life.accounts[c] - (now[c] - life.destroyed[c]));
        }
        log("accounts_mol={} short_mol={}", held, worst);
        life.check(held > 0, "The stopped column's accounts held its products");
        life.check(worst <= 1e-9 * held, "Dismantling books every account as destroyed: " + worst + " mol short of " + held);
    }

    private static void log(String format, Object... values) {
        IChemE.LOGGER.info("column_gametest " + format, Arrays.stream(values).map(v -> v instanceof Double d ? (Object) String.format(Locale.ROOT, "%.6g", d) : v).toArray());
    }
}
