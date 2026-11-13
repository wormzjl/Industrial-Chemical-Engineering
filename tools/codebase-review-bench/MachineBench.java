import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.runtime.fluid.CertificatePolicy;
import com.wormzjl.icheme.runtime.fluid.ElectricStores;
import com.wormzjl.icheme.runtime.fluid.IslandClock;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.management.ObjectName;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * B1 machines scenario (batch 2026-11-13-codebase-review, PROFILE_REVIEW.md): {@code -Dtrains} (default 20) shaft trains, each an
 * electric motor (11 kW, 2 poles), a shaft block, a gearbox on Auto, a shaft block and a pump casing, on its own island: a water
 * supply at 1 atm, the casing's machine pipe (CentrifugalPump .01 m3/s, 50 m, 0.7) and a void at the rise of its 0.8 speed line.
 * The rig is the JUnit rig of {@code ShaftCouplingTest} and {@code PumpHealthTest}: a real {@link IslandCoordinator}, {@link ShaftTrains}
 * and {@link ElectricStores} (Settings needPower false, so the motors run without a supply), the island solves run synchronously on
 * the bench thread at completion. Each tick does what {@code FluidWorldAuthority.tick} does for these parts: the electric and shaft
 * cadences every 100 ticks, the coordinator's tick, the completions, the queued shaft events. Ticks run back to back, not paced.
 * {@code -Dwarm} s of warm-up, then {@code -Dmeasure} s with a JFR "profile" recording; heap histograms (after a full GC) before
 * the rig is built and after the window give the retained heap per train. Writes {@code machines.txt}, {@code machines.jfr},
 * {@code histo-before.txt}, {@code histo-after.txt}.
 */
public final class MachineBench {
    static final double WARM = Double.parseDouble(System.getProperty("warm", "60")), MEASURE = Double.parseDouble(System.getProperty("measure", "60"));
    static final int TRAINS = Integer.getInteger("trains", 20);
    /** {@code -Dcertificates=false}: no rest or steady certificates, so every island solves every cadence (100 ticks). */
    static final boolean CERTIFICATES = Boolean.parseBoolean(System.getProperty("certificates", "true"));
    static final String DIM = "minecraft:overworld";
    static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    static final PipeResistance.Geometry FAT = new PipeResistance.Geometry(1, .1, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
    static final CentrifugalPump PUMP = new CentrifugalPump(.01, 50, .7);
    static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    final FluidThermodynamics model = FluidTestSupport.networkModel();
    final long[] epoch = {0};
    final Map<Long, IslandCoordinator.Attempt> attempts = new LinkedHashMap<>();
    final Map<Long, ProcessSolveJobs.FluidIslandCommand> commands = new HashMap<>();
    final SimulationTime time = SimulationTime.of(4);
    IslandCoordinator coordinator;
    ShaftTrains trains;
    ElectricStores stores;
    long requests, solves, solveNanos;

    FluidThermodynamics.State water(double pressure) {
        var n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        return model.flashTP(298.15, pressure, n, () -> {});
    }

    static PhysicalFluidTopology.Position cell(int x, int z) {
        return new PhysicalFluidTopology.Position(DIM, x, 64, z);
    }

    void build() {
        coordinator = new IslandCoordinator(new IslandCoordinator.Dispatcher() {
            public int availableWorkers() { return 64 - attempts.size(); }
            public long nextRequestId() { return ++requests; }
            public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveJobs.FluidIslandCommand command) {
                attempts.put(attempt.slice().requestId(), attempt);
                commands.put(attempt.slice().requestId(), command);
                return true;
            }
            public void cancel(long request) { throw new AssertionError("no deadlines here"); }
        }, changed -> {}, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CERTIFICATES ? CertificatePolicy.defaults() : CertificatePolicy.disabled()).withTime(time), () -> epoch[0]);
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(CATALOG));
        // Island k owns devices 1000k+1 (the supply, which is the casing's fluid device) and 1000k+2 (the void).
        trains = new ShaftTrains(ledger, device -> device % 1000 == 1 && device / 1000 >= 1 && device / 1000 <= TRAINS ? device / 1000 : null, coordinator);
        stores = new ElectricStores(ledger, device -> null, coordinator, () -> new ElectricStores.Settings(ElectricStores.Settings.DEFAULT.joulesPerFE(), false));
        trains.electric(stores);
        trains.host(ShaftTrains.Host.NONE);
        coordinator.shafts(trains);
        var supply = water(101325);
        double rho = supply.mass() / supply.volume(), rise = PUMP.rise(.008, .8, rho, supply.pressure(), 1.4);
        var none = ShaftTrain.Trip.NONE;
        var fixed = MachineDuty.Drive.FIXED;
        for (int k = 1; k <= TRAINS; k++) {
            long base = 1000L * k;
            var line = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(base + 1, 0, water(101325), PassiveNetwork.NodeKind.GENERATOR),
                    new PassiveNetwork.Reservoir(base + 2, 0, water(101325 + rise), PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(base + 10, 0, 1, FAT, new FlowControl.Machine(PUMP, 0, true))));
            coordinator.register(new IslandCoordinator.Snapshot(k, 0, line, new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model.withTime(time));
            int z = 4 * k;
            trains.queue(new ShaftTrains.Put(cell(0, z), ShaftNetwork.Kind.MOTOR, id -> new ShaftUnit(id, ShaftNetwork.Kind.MOTOR, cell(0, z), 0,
                    List.of(new ShaftUnit.End(cell(0, z), 1, 0, 0, true)), 0, 11e3, 2950, 2, fixed, ShaftUnit.Settings.DEFAULT, none, null)));
            trains.queue(new ShaftTrains.Put(cell(1, z), ShaftNetwork.Kind.SHAFT, id -> ShaftUnit.shaft(id, cell(1, z), 0)));
            trains.queue(new ShaftTrains.Put(cell(2, z), ShaftNetwork.Kind.GEARBOX, id -> new ShaftUnit(id, ShaftNetwork.Kind.GEARBOX, cell(2, z), 0,
                    List.of(new ShaftUnit.End(cell(2, z), -1, 0, 0, false), new ShaftUnit.End(cell(2, z), 1, 0, 0, true)),
                    0, 0, 0, 0, fixed, ShaftUnit.Settings.DEFAULT, none, null)));
            trains.queue(new ShaftTrains.Put(cell(3, z), ShaftNetwork.Kind.SHAFT, id -> ShaftUnit.shaft(id, cell(3, z), 0)));
            trains.queue(new ShaftTrains.Put(cell(4, z), ShaftNetwork.Kind.CASING, id -> new ShaftUnit(id, ShaftNetwork.Kind.CASING, cell(4, z), 0,
                    List.of(new ShaftUnit.End(cell(4, z), -1, 0, 0, false)), base + 1, 7e3, 2950, 0, fixed, ShaftUnit.Settings.DEFAULT, none, null)));
        }
        trains.apply();
    }

    void drain() {
        while (!attempts.isEmpty()) {
            for (long request : List.copyOf(attempts.keySet())) {
                var attempt = attempts.remove(request);
                var command = commands.remove(request);
                long t0 = System.nanoTime();
                var result = (ProcessSolveJobs.FluidIslandSolveResult) command.solve(FluidTestSupport.NEVER);
                solveNanos += System.nanoTime() - t0;
                solves++;
                coordinator.completed(attempt, Optional.of(result));
            }
            coordinator.pump();
        }
    }

    void tick() {
        epoch[0]++;
        if (epoch[0] % ElectricStores.CADENCE == 0) {
            stores.cadence();
            trains.cadence();
        }
        coordinator.tick();
        drain();
        if (trains.hasEvents()) {
            trains.apply();
            drain();
        }
    }

    static String histogram() throws Exception {
        var server = ManagementFactory.getPlatformMBeanServer();
        return (String) server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[] {new String[0]}, new String[] {String[].class.getName()});
    }

    static long allocated() {
        long sum = 0;
        for (long id : THREADS.getAllThreadIds()) {
            long b = THREADS.getThreadAllocatedBytes(id);
            if (b > 0) sum += b;
        }
        return sum;
    }

    static long[] gc() {
        long count = 0, millis = 0;
        for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            millis += Math.max(0, bean.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    String states() {
        var counts = new java.util.TreeMap<String, Integer>();
        for (var u : trains.units()) {
            if (u.kind() != ShaftNetwork.Kind.MOTOR && u.kind() != ShaftNetwork.Kind.CASING) continue;
            var v = trains.view(u.id());
            String key = u.kind() + " " + (v == null ? "null" : v.status() + String.format(Locale.ROOT, " %.0f rpm", v.speed()));
            counts.merge(key, 1, Integer::sum);
        }
        var cert = 0;
        for (int k = 1; k <= TRAINS; k++) if (coordinator.observe(k).certificate().isPresent()) cert++;
        return counts + " certified islands " + cert + "/" + TRAINS;
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        var lines = new ArrayList<String>();
        var bench = new MachineBench();
        bench.water(101325); // the model's classes load before the baseline histogram
        Files.writeString(out.resolve("histo-before.txt"), histogram());
        bench.build();
        long end = System.nanoTime() + (long) (WARM * 1e9);
        long ticks = 0;
        while (System.nanoTime() < end) { bench.tick(); ticks++; }
        lines.add("trains " + TRAINS + ", certificates " + CERTIFICATES + ", time factor 4, warm-up ticks " + ticks + " solves " + bench.solves);
        lines.add("after warm-up: " + bench.states());
        var recording = new Recording(Configuration.getConfiguration("profile"));
        recording.setName("machines");
        recording.start();
        long solves0 = bench.solves, solveNanos0 = bench.solveNanos, b0 = allocated();
        long[] g0 = gc();
        long cpu0 = THREADS.getCurrentThreadCpuTime();
        long start = System.nanoTime();
        end = start + (long) (MEASURE * 1e9);
        long measured = 0;
        while (System.nanoTime() < end) { bench.tick(); measured++; }
        double window = (System.nanoTime() - start) / 1e9;
        long cpu = THREADS.getCurrentThreadCpuTime() - cpu0, bytes = allocated() - b0;
        long[] g1 = gc();
        recording.stop();
        recording.dump(out.resolve("machines.jfr"));
        recording.close();
        long solves = bench.solves - solves0;
        double solveSeconds = (bench.solveNanos - solveNanos0) / 1e9;
        lines.add(String.format(Locale.ROOT, "window %.2f s, ticks %d (%.1f us a tick, %.0f x real time at 20 TPS), CPU %.2f s", window, measured,
                1e6 * window / measured, measured / 20.0 / window, cpu / 1e9));
        lines.add(String.format(Locale.ROOT, "island solves %d (%.2f per tick); solve wall %.3f s (%.1f %% of the window), %.1f us a solve",
                solves, (double) solves / measured, solveSeconds, 100 * solveSeconds / window, solves == 0 ? 0 : 1e6 * solveSeconds / solves));
        lines.add(String.format(Locale.ROOT, "outside the solves: %.1f us a tick (trains, stores, coordinator, bookkeeping)", 1e6 * (window - solveSeconds) / measured));
        lines.add(String.format(Locale.ROOT, "per 100-tick cadence (5 s of play): %.1f us wall, of which %.1f us outside the solves", 100e6 * window / measured, 100e6 * (window - solveSeconds) / measured));
        lines.add(String.format(Locale.ROOT, "allocated %.1f MB (%.0f B a tick, %.1f kB a solve if all were the solves')", bytes / 1e6, (double) bytes / measured,
                solves == 0 ? 0 : bytes / 1e3 / solves));
        lines.add(String.format(Locale.ROOT, "GC %d collections, %d ms (%.2f %% of the window)", g1[0] - g0[0], g1[1] - g0[1], 100.0 * (g1[1] - g0[1]) / 1000 / window));
        lines.add("after window: " + bench.states());
        Files.writeString(out.resolve("histo-after.txt"), histogram());
        lines.forEach(System.out::println);
        Files.write(out.resolve("machines.txt"), lines);
        System.exit(0);
    }
}
