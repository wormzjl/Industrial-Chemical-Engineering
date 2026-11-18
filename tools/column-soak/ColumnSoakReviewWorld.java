package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.testworld.ColumnTestWorld;
import com.wormzjl.icheme.testworld.CrudeUnitPlant;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Soak of the default column test world (tools/column-soak): the plant built and started as
 * {@link ColumnTestWorldReviewWorld} does (paced start), then held to 20 ticks a second of wall time (a GameTest server
 * otherwise ticks as fast as it can, far ahead of the solves), as a game runs, for {@link #SOAK_TICKS}. Every {@link #EVERY} ticks it writes each island's status and solver counters and
 * every non-pipe device's status, pressure, temperature, mass, flow, level and heat to CSV files in the game directory
 * ({@code soak-islands.csv}, {@code soak-devices.csv}). Reads only; changes nothing. Not a gate.
 *
 * <p>Run: copy into {@code src/fluidGameTest/java/com/wormzjl/icheme/fluid/gametest/}, then
 * {@code ./gradlew runReviewWorldServer -PreviewBatch=review-column-soak --max-workers=4}; analyse with
 * {@code tools/column-soak/drift.py run/review-world}; remove the copy.
 */
@GameTestHolder("icheme_review")
@PrefixGameTestTemplate(false)
public final class ColumnSoakReviewWorld {
    private ColumnSoakReviewWorld() {}

    /** The tick the column first showed a condition in the feed batch, or -1. */
    static int tripAt = -1;

    static final String BATCH = "review-column-soak", FEED_BATCH = "review-column-soak-feed", BURNER_BATCH = "review-column-soak-burners", SAVE_BATCH = "review-column-soak-save";
    /** The save batch: the world in run/review-world/world as it was saved (a copy of a client's save), nothing built, 5 minutes. */
    static final int SAVE_TICKS = 6_000;
    private static final java.util.regex.Pattern MEMBER = java.util.regex.Pattern.compile("part of the column at (-?\\d+), (-?\\d+), (-?\\d+)");
    /** The burner batch: 1500 ticks, each heater's burners, radiant coils and fuel valves every second ({@code soak-burners.csv}). */
    static final int BURNER_TICKS = 1_500;
    /** The feed batch: past the first feed trip (about tick 1960), the crude island's movers and valves and the column's
     * phase and conditions every second ({@code soak-feed.csv}, {@code soak-column.csv}). */
    static final int FEED_TICKS = 2_600, FEED_EVERY = 20;
    /** 20 minutes of game time after the start, sampled every 5 s. */
    static final int SOAK_TICKS = 24_000, EVERY = 100;

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = BATCH)
    public static void theColumnTestWorldRunsSteady(GameTestHelper helper) {
        run(helper, BATCH, SOAK_TICKS);
    }

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = SAVE_BATCH)
    public static void theSavedWorldRunsSteady(GameTestHelper helper) {
        AssemblyGameTests.clearStartControls(helper);
        if (!SAVE_BATCH.equals(System.getProperty(ReviewBatch.PROPERTY, ""))) {
            helper.succeed();
            return;
        }
        var world = com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority.find(helper.getLevel().getServer()).orElseThrow();
        var masters = new java.util.TreeMap<String, com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position>();
        int[] ticks = {0};
        long[] wall = {0};
        PrintWriter[] out = new PrintWriter[5];
        helper.startSequence()
                .thenExecute(() -> {
                    try {
                        var dir = Path.of("");
                        String[] names = {"soak-islands.csv", "soak-devices.csv", "soak-feed.csv", "soak-column.csv", "soak-burners.csv"};
                        for (int i = 0; i < names.length; i++) out[i] = new PrintWriter(Files.newBufferedWriter(dir.resolve(names[i])));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    out[0].println("tick,island,status,online,committed,retry_at,cadence,accepted,rejected,advanced_s,rejections");
                    out[1].println("tick,device,kind,pos,island,status,p_pa,t_k,mass_kg,flow_kg_s,level_m,heat_out_k,heat_w");
                    out[2].println("tick,island,committed,pipe,id,control,mode,avg_kg_s,end_kg_s,head_pa,valve_drop_pa,drive");
                    out[3].println("tick,online,phase,alpha,resolving,engine,conditions");
                    out[4].println("tick,what,heater,id,a,b,c,d,e,f,g");
                    // A player standing at the plant: every chunk with a device (and one around) ticks, so block entities
                    // such as the creative power sources push FE as they do in a client.
                    var chunks = new java.util.TreeSet<Long>();
                    for (var r : world.registrations().values()) {
                        var p = r.device().position();
                        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                            chunks.add(net.minecraft.world.level.ChunkPos.asLong((p.x() >> 4) + dx, (p.z() >> 4) + dz));
                    }
                    for (long c : chunks) helper.getLevel().setChunkForced(net.minecraft.world.level.ChunkPos.getX(c), net.minecraft.world.level.ChunkPos.getZ(c), true);
                    IChemE.LOGGER.info("column_soak save started ticks={} devices={} forced_chunks={}", SAVE_TICKS, world.registrations().size(), chunks.size());
                    wall[0] = System.nanoTime();
                })
                .thenWaitUntil(() -> {
                    int t = ++ticks[0];
                    long due = wall[0] + t * 50_000_000L, now = System.nanoTime();
                    if (due > now) try {
                        Thread.sleep((due - now) / 1_000_000L, (int) ((due - now) % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (t % EVERY == 0) {
                        sample(world, t, out);
                        // The columns, by the master their members' status names ("part of the column at x, y, z").
                        for (var e : world.registrations().entrySet()) {
                            var v = world.view(e.getKey());
                            var m = v == null ? null : MEMBER.matcher(v.status());
                            if (m != null && m.find()) {
                                var at = new net.minecraft.core.BlockPos(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                                masters.computeIfAbsent(at.toShortString(), k -> com.wormzjl.icheme.world.level.block.Positions.of(helper.getLevel(), at));
                            }
                        }
                    }
                    if (t % FEED_EVERY == 0) {
                        feed(world, t, out[2]);
                        for (var master : masters.values()) columnAt(world, master, t, out[3]);
                        coils(world, t, out[4]);
                    }
                    if (t % 1200 == 0) IChemE.LOGGER.info("column_soak save tick={} columns={}", t, masters.keySet());
                    helper.assertTrue(t >= SAVE_TICKS, "Soaking the save: tick " + t + " of " + SAVE_TICKS);
                })
                .thenExecute(() -> {
                    for (var w : out) w.close();
                    IChemE.LOGGER.info("column_soak save done ticks={}", ticks[0]);
                })
                .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = BURNER_BATCH)
    public static void theBurnersFireToTheirDemand(GameTestHelper helper) {
        run(helper, BURNER_BATCH, BURNER_TICKS);
    }

    @GameTest(template = "empty", timeoutTicks = Integer.MAX_VALUE, batch = FEED_BATCH)
    public static void theFeedLoopRunsSteady(GameTestHelper helper) {
        run(helper, FEED_BATCH, FEED_TICKS);
    }

    private static void run(GameTestHelper helper, String batch, int soakTicks) {
        AssemblyGameTests.clearStartControls(helper);
        if (!batch.equals(System.getProperty(ReviewBatch.PROPERTY, ""))) {
            helper.succeed();
            return;
        }
        var options = CrudeUnitPlant.review(com.wormzjl.icheme.science.material.MaterialRuntime.active());
        var base = ColumnOperationReviewWorld.rig(helper, options).plant.base;
        var rig = ColumnTestWorld.build(helper.getLevel(), base, options);
        boolean[] ended = {false, false};
        var start = new ColumnTestWorld.Start(rig, ok -> {
            ended[0] = true;
            ended[1] = ok;
        }, true);
        ColumnTestWorld.begin(start);
        int[] ticks = {0};
        long[] wall = {0};
        PrintWriter[] out = new PrintWriter[6];
        var lag = new JunctionLagTrace();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(ended[0], "Waiting for the column test world's start: " + start.state()))
                .thenExecute(() -> {
                    if (!ended[1]) helper.fail("The column test world's start stopped at " + start.state());
                    try {
                        var dir = Path.of("");
                        out[0] = new PrintWriter(Files.newBufferedWriter(dir.resolve("soak-islands.csv")));
                        out[1] = new PrintWriter(Files.newBufferedWriter(dir.resolve("soak-devices.csv")));
                        out[2] = new PrintWriter(Files.newBufferedWriter(dir.resolve("soak-feed.csv")));
                        out[3] = new PrintWriter(Files.newBufferedWriter(dir.resolve("soak-column.csv")));
                        out[4] = new PrintWriter(Files.newBufferedWriter(dir.resolve("soak-burners.csv")));
                        if (batch.equals(FEED_BATCH)) out[5] = new PrintWriter(Files.newBufferedWriter(dir.resolve("junction-lag.csv")));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    if (out[5] != null) out[5].println("start_tick,end_tick,endpoint_full_firing,accepted,rejected,coil_kg,feed_kg,coil_j_per_kg,feed_j_per_kg,coil_at_feed_pressure_K,feed_K");
                    out[0].println("tick,island,status,online,committed,retry_at,cadence,accepted,rejected,advanced_s,rejections");
                    out[1].println("tick,device,kind,pos,island,status,p_pa,t_k,mass_kg,flow_kg_s,level_m,heat_out_k,heat_w");
                    out[2].println("tick,island,committed,pipe,id,control,mode,avg_kg_s,end_kg_s,head_pa,valve_drop_pa,drive");
                    out[3].println("tick,online,phase,alpha,resolving,engine,conditions");
                    out[4].println("tick,what,heater,id,a,b,c,d,e,f,g");
                    if (out[5] != null) rig.plant.world.observe((changed, metrics) -> lag.sample(rig, changed, out[5]));
                    IChemE.LOGGER.info("column_soak started ticks={} every={}", soakTicks, EVERY);
                    wall[0] = System.nanoTime();
                })
                .thenWaitUntil(() -> {
                    int t = ++ticks[0];
                    // 50 ms a tick of wall time, as a game runs: sleep to the tick's due time.
                    long due = wall[0] + t * 50_000_000L, now = System.nanoTime();
                    if (due > now) try {
                        Thread.sleep((due - now) / 1_000_000L, (int) ((due - now) % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (t % EVERY == 0) sample(rig.plant.world, t, out);
                    if (t % FEED_EVERY == 0) {
                        feed(rig.plant.world, t, out[2]);
                        column(rig, t, out[3]);
                        burners(rig, t, out[4]);
                    }
                    // The feed batch ends 10 s after the column first shows a condition.
                    boolean done = t >= soakTicks || batch.equals(FEED_BATCH) && tripAt >= 0 && t >= tripAt + 200;
                    helper.assertTrue(done, "Soaking: tick " + t + " of " + soakTicks);
                })
                .thenExecute(() -> {
                    out[0].close();
                    out[1].close();
                    out[2].close();
                    out[3].close();
                    out[4].close();
                    if (out[5] != null) {
                        rig.plant.world.observe(null);
                        out[5].close();
                    }
                    IChemE.LOGGER.info("column_soak done ticks={}", ticks[0]);
                    rig.plant.release();
                })
                .thenSucceed();
    }

    /**
     * The islands that hold a pump: each mover's and control valve's connection in the island's last result (control, mode,
     * average and end flow, the rise, the valve's own drop at the slice's end: the trim's inputs, ShaftTrains.seriesValveDrop),
     * and the pump's drive as its view shows it (speed, share, status).
     */
    private static void feed(com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority world, int tick, PrintWriter out) {
        for (var s : world.diagnosticSnapshots()) {
            var last = s.lastResult();
            if (last.isEmpty()) continue;
            var r = last.get();
            var g = r.graph();
            boolean pump = g.pipes().stream().anyMatch(p -> p.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Machine);
            if (!pump) continue;
            var drops = new java.util.HashMap<Long, Double>();
            for (var o : r.observations()) drops.put(o.pipe(), o.valveDrop());
            for (int e = 0; e < g.pipes().size(); e++) {
                var p = g.pipes().get(e);
                var c = p.control();
                boolean machine = c instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Machine;
                if (!machine && !(c instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve)) continue;
                String drive = "";
                if (machine) {
                    var v = world.view(p.id());
                    var d = v == null ? null : v.drive();
                    if (d != null) drive = String.format(Locale.ROOT, "%s speed=%.4f share=%.4f %s", d.driver(), d.speed(), d.share(), d.status());
                }
                String control = machine ? String.format(Locale.ROOT, "pump speed=%.4f set_m3_s=%.5f", ((com.wormzjl.icheme.science.fluid.network.FlowControl.Machine) c).speed(),
                        ((com.wormzjl.icheme.science.fluid.network.FlowControl.Machine) c).setFlow()) : c.toString();
                out.printf(Locale.ROOT, "%d,%d,%d,%d,%d,\"%s\",%s,%.4f,%.4f,%.1f,%.1f,\"%s\"%n", tick, s.id(), s.clock().committedTick(), e, p.id(), control.replace('"', '\''),
                        r.endpointModes().get(e), r.averageMassFlows()[e], r.endpointFlows()[e], r.endpointHeads()[e], drops.getOrDefault(p.id(), Double.NaN), drive.replace('"', '\''));
            }
        }
        out.flush();
    }

    /**
     * Each heater's sides (status, outlet K, heat W) and burners (demand, asked, fired W, fuel kg/s, heating value J/kg,
     * alarms); every radiant coil in every island's last result (set point K, duty W, firing, limit, flow kg/s, committed
     * tick); every burner fuel valve (its control, mode, average flow, rise and its own drop).
     */
    private static void burners(CrudeUnitPlant.Rig rig, int tick, PrintWriter out) {
        var plant = rig.plant;
        for (int k = 0; k < rig.heaters.size(); k++)
            for (long id : com.wormzjl.icheme.world.level.block.EquipmentStructure.sideIdentities(plant.level, rig.heaters.get(k))) {
                var v = plant.world.view(id);
                var heat = v == null ? null : v.heat();
                out.printf(Locale.ROOT, "%d,side,%d,%d,\"%s\",%.2f,%.4g,,,,%n", tick, k, id, v == null ? "-" : v.status().replace('"', '\''),
                        heat == null ? Double.NaN : heat.outletTemperature(), heat == null ? Double.NaN : heat.heatWatt());
                long b = plant.world.burners().burnersOf(id);
                if (b == 0) continue;
                var bv = plant.world.burners().view(b);
                if (bv != null) out.printf(Locale.ROOT, "%d,burners,%d,%d,%.5g,%.5g,%.5g,%.4f,%.4g,\"%s\",%n", tick, k, b, bv.demand(), bv.asked(), bv.fired(), bv.fuelFlow(), bv.heatingValue(), bv.alarms());
            }
        coils(plant.world, tick, out);
        out.flush();
    }

    /** Every radiant coil (set point, duty, firing, limit, flow, its ends) and burner fuel valve in every island's last result. */
    private static void coils(com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority world, int tick, PrintWriter out) {
        for (var s : world.diagnosticSnapshots()) {
            var last = s.lastResult();
            if (last.isEmpty()) continue;
            var r = last.get();
            var g = r.graph();
            var drops = new java.util.HashMap<Long, Double>();
            for (var o : r.observations()) drops.put(o.pipe(), o.valveDrop());
            for (int e = 0; e < g.pipes().size(); e++) {
                var p = g.pipes().get(e);
                if (p.heat() instanceof com.wormzjl.icheme.science.fluid.network.FiredCoil c)
                {
                    var in = g.reservoirs().get(p.first()).state();
                    var outlet = g.reservoirs().get(p.second()).state();
                    out.printf(Locale.ROOT, "%d,coil,%d,%d,%.2f,%.5g,%.5f,%.5f,%.4f,%d,\"in %.0f Pa %.2f K %s / out %.0f Pa %.2f K %s\"%n", tick, s.id(), p.id(), c.setTemperature(), c.duty(), c.firing(),
                            c.limit(), r.averageMassFlows()[e], s.clock().committedTick(), in.pressure(), in.temperature(), g.reservoirs().get(p.first()).kind(),
                            outlet.pressure(), outlet.temperature(), g.reservoirs().get(p.second()).kind());
                }
                else if (p.control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.ControlValve cv && cv.source() == com.wormzjl.icheme.science.fluid.network.FlowControl.ValveSource.BURNER)
                    out.printf(Locale.ROOT, "%d,fuel,%d,%d,\"%s\",%s,%.5f,%.1f,%.1f,%d,%n", tick, s.id(), p.id(), cv.toString().replace('"', '\''), r.endpointModes().get(e),
                            r.averageMassFlows()[e], r.endpointHeads()[e], drops.getOrDefault(p.id(), Double.NaN), s.clock().committedTick());
            }
        }
        out.flush();
    }

    /** The column's Operation view: phase, the cycle's share of its set feed, the solve in flight and every condition. */
    private static void column(CrudeUnitPlant.Rig rig, int tick, PrintWriter out) {
        columnAt(rig.plant.world, rig.plant.position(rig.plant.column.origin()), tick, out);
    }

    private static void columnAt(com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority world, com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position master, int tick, PrintWriter out) {
        var v = world.columnView(master, id -> Double.NaN);
        if (v == null) return;
        var c = new StringBuilder();
        for (var a : v.conditions()) c.append(a.condition()).append('[').append(String.format(Locale.ROOT, "%.4g", a.value())).append("] ").append(a.text()).append(" | ");
        out.printf(Locale.ROOT, "%d,%d,%s,%.4f,%s,\"%s\",\"%s\"%n", tick, world.diagnosticSnapshots().stream().mapToLong(s -> s.clock().onlineTick()).max().orElse(-1),
                v.phase(), v.alpha(), v.resolving(), v.engine().replace('"', '\''), c.toString().replace('"', '\''));
        if (tripAt < 0 && v.conditions().stream().anyMatch(a -> a.condition().rank == com.wormzjl.icheme.science.plant.column.Condition.Rank.TRIP)) {
            tripAt = tick;
            IChemE.LOGGER.info("column_soak conditions at tick={}: {}", tick, c);
        }
        out.flush();
    }

    private static void sample(com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority world, int tick, PrintWriter[] out) {
        int held = 0;
        for (var s : world.diagnosticSnapshots()) {
            var r = s.lastResult();
            var c = s.clock();
            if (s.status().startsWith("HELD")) held++;
            out[0].printf(Locale.ROOT, "%d,%d,\"%s\",%d,%d,%d,%d,%d,%d,%.3f,\"%s\"%n", tick, s.id(), s.status().replace('"', '\''), c.onlineTick(), c.committedTick(), c.retryAtTick(),
                    c.cadenceTicks(), r.map(x -> x.acceptedSubsteps()).orElse(-1), r.map(x -> x.rejectedSubsteps()).orElse(-1), r.map(x -> x.advancedSeconds()).orElse(Double.NaN),
                    r.map(x -> x.rejectionReasons().toString()).orElse(""));
        }
        for (var e : world.registrations().entrySet()) {
            var device = e.getValue().device();
            String kind = String.valueOf(device.kind());
            if (kind.contains("PIPE")) continue;
            FluidView v = world.view(e.getKey());
            if (v == null) continue;
            var st = v.state();
            out[1].printf(Locale.ROOT, "%d,%d,%s,\"%s\",%s,\"%s\",%.6g,%.6g,%.6g,%.6g,%.6g,%.6g,%.6g%n", tick, e.getKey(), kind, String.valueOf(device.position()), "",
                    v.status().replace('"', '\''), st == null ? Double.NaN : st.pressure(), st == null ? Double.NaN : st.temperature(), st == null ? Double.NaN : st.mass(),
                    v.massFlow(), v.tank() == null ? Double.NaN : v.tank().levelHeight(), v.heat() == null ? Double.NaN : v.heat().outletTemperature(),
                    v.heat() == null ? Double.NaN : v.heat().heatWatt());
        }
        out[0].flush();
        out[1].flush();
        if (tick % 1200 == 0) IChemE.LOGGER.info("column_soak tick={} held_islands={}", tick, held);
    }
}
