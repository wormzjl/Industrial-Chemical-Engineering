package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.integrity.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * The walls of constructed equipment (batch 2026-11-16-fouling-corrosion, WP2; plan sections 4, 6.5, 7, 8, 9, 10, 11 and
 * 12): corrosion, erosion and fouling booked over process time, the failure of a wall that is through, blast damage, and
 * the structure repair and cleaning events. One record per device, keyed by its device identity: its wall loss (mm), its
 * fouling deposit (m²K/W) and {@code wrecked} are facts ({@link WorldTopologyLedger.IntegrityFacts}); its rate, governing
 * mechanism and fouling asymptote are state. Server thread only, beside {@link MachineHealth}.
 * <ul>
 * <li>Which devices ({@link #kind}): a device a structure registers (with ports; one-block devices, pipes and port lanes are
 * not) that is a closed vessel (a kettle's shell is one; not built open, not a cooling tower's basin, not a still's pot), a
 * heat side, or a machine with a health record ({@link MachineHealth#pump}: casings and steam turbines).</li>
 * <li>At a commit or replay ({@link #booked}) only the island's last result is kept: no arithmetic per slice.</li>
 * <li>At the cadence ({@link #cadence}): every record books its rate over the process time since its last booking
 * ({@code rate × Δt / year}) and a flowing heat side its deposit ({@link FoulingLaw#step}); then at most {@link #EVALUATIONS}
 * records whose island booked a span since their last evaluation, round-robin, read their fluid's digest from that result
 * (a vessel its own node, a heat side and a machine the hotter end of its connection) and set their rate: the inside law, the
 * corrosive air at the device ({@link CorrosionLaw#outside}) and a heat side's particle erosion
 * ({@link ErosionLaw#wall}, read only on an island whose last slice moved solids). A wall through
 * ({@link WallState.Stage#THROUGH}) fails once by the failure its kind has today: a vessel stands breached, a machine
 * fails ({@link MachineHealth#breach}), a heat side's passage closes and its holdup is released once. A wall lost to an
 * oxygen fire is one of the plant's flames for the next pollution pass ({@link #fires}).</li>
 * <li>A blast wrecks a structure's devices ({@link #wreck}): final, no repair.</li>
 * <li>The repair and the cleaning ({@link #queueRepair}, {@link #queueClean}) are queued engine events applied at the tick
 * drain ({@link #apply}), accepted only while every listed device is idle and every vessel among them empty.</li>
 * </ul>
 * Nothing here wakes an island but a failure, a repair or a cleaning: wear above 0 changes nothing the solver reads.
 */
public final class Integrity {
    /** The cadence, online ticks (the machines' health's). */
    public static final int CADENCE = MachineHealth.CADENCE;
    /** Records evaluated per cadence at most. */
    public static final int EVALUATIONS = 256;
    /** Registrations the discovery sweep reads per cadence at most. */
    public static final int SWEEP = 4096;
    /** The mass flow, kg/s, at and below which a connection carries nothing: the step solver's own {@code flowing} test. */
    public static final double IDLE_FLOW = 1e-10;
    /** An empty vessel: less liquid than this share of its volume, and within {@link #EMPTY_PRESSURE} Pa of the ambient. */
    public static final double EMPTY_SHARE = .01, EMPTY_PRESSURE = 2e4;
    /** The corrosive air cubes' read and the pollution's ignition list are bounded: at most this many fires wait. */
    static final int FIRES = 256;

    /** The switches and the time scales (config {@code corrosion.*}, {@code erosion.*}, {@code fouling.*}). */
    public record Settings(boolean corrosion, double yearProcessHours, boolean external, boolean erosion, boolean fouling, double asymptoteProcessHours) {
        public static final Settings DEFAULT = new Settings(true, 20, true, true, true, 10);

        public Settings {
            if (!(yearProcessHours > 0) || !Double.isFinite(yearProcessHours) || !(asymptoteProcessHours > 0) || !Double.isFinite(asymptoteProcessHours))
                throw new IllegalArgumentException("Invalid integrity time scales " + yearProcessHours + " h, " + asymptoteProcessHours + " h");
        }
    }

    /** What a record stands for. */
    public enum Kind { VESSEL, HEAT_SIDE, MACHINE }

    /** What a structure repair or a cleaning came to. */
    public enum Outcome { REPAIRED, CLEANED, NOT_IDLE, NOT_EMPTY, WRECKED, NOTHING_TO_REPAIR }

    /**
     * A wall as its page reads it (WP4): its loss and allowance (mm), its stage, its rate (mm/y) and governing mechanism at
     * its last evaluation (null for none), and its life left in process seconds (infinite at a rate of 0).
     */
    public record Wall(double loss, double allowance, WallState.Stage stage, double rate, Mechanism mechanism, double life) {
    }

    /** What the walls do to the world: the authority's queued edits and reads. */
    public interface Host {
        /** Queues the device's spec {@code breached} (a vessel stands open, a heat side's passage closes); false when nothing was queued. */
        boolean close(long id);

        /** Queues the device's spec whole again; false when it was not breached. */
        boolean open(long id);

        /** A heat side's holdup let into the air once, as a burst line block's is ({@link FluidWorldAuthority#releaseContents}). */
        void release(long id);

        /** The corrosive air index of the cube at {@code at} (0 while pollution is off). */
        double corrosiveAir(PhysicalFluidTopology.Position at);

        /** The corrosion standard of compound list {@code compounds} (an island's own list), or null when it cannot be built. */
        CorrosionStandard standard(List<String> compounds);

        /**
         * Device {@code id}'s wall weeps ({@link WallState.Stage#WEEPING}) or no longer does (batch 2026-11-16-fouling-corrosion,
         * WP4): its structure's drips and weep loop. Called at every cadence for each weeping wall and once when one stops; the
         * host sends only a change. No fluid leaves.
         */
        default void weeping(long id, boolean weeping) {
        }
    }

    /** A queued repair or cleaning. */
    private record Request(List<Long> devices, boolean clean, Consumer<Outcome> reply) {
    }

    /** An island's last booked span: its result and the online tick it ended at. */
    private record Booked(PassiveIntervalSolver.Result result, long end) {
    }

    private static final class Record {
        double loss, fouling;
        boolean wrecked;
        Kind kind;
        /** The corrosion rate (inside and air) and the erosion rate, mm/y, kept apart so that a switch turned off stops its part at once. */
        double corrosion, erosion, asymptote;
        /** What governs the corrosion part, or null. */
        Mechanism corroding;
        /** Its wall through has failed since it was last below through: a failure is applied once, whatever its edit's timing. */
        boolean flowing, failed;
        long bookedTick, evaluated = Long.MIN_VALUE;

        Record(long now) {
            bookedTick = now;
        }

        boolean isNew() {
            return loss == 0 && fouling == 0 && !wrecked;
        }

        /** The wall's rate under {@code s}, mm/y: each part only while its switch is on. */
        double rate(Settings s) {
            double r = (s.corrosion() ? corrosion : 0) + (s.erosion() ? erosion : 0);
            return Math.min(r, CorrosionLaw.MAX_RATE);
        }

        /** The governing mechanism under {@code s}: the larger part's, null at a rate of 0. */
        Mechanism mechanism(Settings s) {
            double c = s.corrosion() ? corrosion : 0, e = s.erosion() ? erosion : 0;
            if (e > c) return Mechanism.EROSION;
            return c > 0 ? corroding : null;
        }
    }

    private final WorldTopologyLedger topology;
    private final LongFunction<Long> owner;
    private final IslandCoordinator coordinator;
    private final MachineHealth health;
    private final Supplier<Settings> settings;
    private final Host host;
    private final TreeMap<Long, Record> walls = new TreeMap<>();
    private final Map<Long, Booked> islands = new HashMap<>();
    private final Map<List<String>, CorrosionStandard> standards = new HashMap<>();
    private final ArrayDeque<Request> events = new ArrayDeque<>();
    private final ArrayDeque<PhysicalFluidTopology.Position> fires = new ArrayDeque<>();
    private List<Long> sweep = List.of();
    private int sweepAt;
    private long cursor = Long.MIN_VALUE;
    private long evaluations;

    public Integrity(WorldTopologyLedger topology, LongFunction<Long> owner, IslandCoordinator coordinator, MachineHealth health, Supplier<Settings> settings, Host host) {
        this.topology = Objects.requireNonNull(topology);
        this.owner = Objects.requireNonNull(owner);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.health = Objects.requireNonNull(health);
        this.settings = Objects.requireNonNull(settings);
        this.host = Objects.requireNonNull(host);
        long now = coordinator.now();
        topology.integrity().forEach((id, facts) -> {
            var w = new Record(now);
            w.loss = facts.wallLoss();
            w.fouling = facts.fouling();
            w.wrecked = facts.wrecked();
            walls.put(id, w);
        });
        // One whole discovery at the start; later ones sweep SWEEP registrations per cadence.
        discover(Integer.MAX_VALUE, now);
    }

    // ---------------- which devices ----------------

    /**
     * What registration {@code r} is to the walls, or null: a device a structure registers (with ports; never a one-block
     * device, a pipe or a port lane) that is a closed vessel (kind {@code RESERVOIR}, not built open, no cooling tower's basin,
     * no still's pot; a kettle's shell is a vessel), a heat side, or a machine that wears ({@link MachineHealth#pump}).
     */
    public static Kind kind(WorldTopologyLedger.Registration r) {
        if (r == null) return null;
        var d = r.device();
        if (!constructed(r)) return null;
        if (d.kind() == TopologyCompiler.Kind.RESERVOIR) return r.spec().open() || r.spec().tower() != null || d.stillPot() ? null : Kind.VESSEL;
        if (d.kind() == TopologyCompiler.Kind.HEAT_SIDE && d.heat() != null) return Kind.HEAT_SIDE;
        return MachineHealth.pump(r) ? Kind.MACHINE : null;
    }

    /**
     * Whether registration {@code r} is a device of constructed equipment: one a structure registers at its node with its
     * nozzle ports ({@code EquipmentStructure.heldAt}'s test), not a one-block device, a pipe or a port lane, which register
     * without ports.
     */
    public static boolean constructed(WorldTopologyLedger.Registration r) {
        return r != null && !r.device().ports().isEmpty() && !r.device().port();
    }

    /** Sweeps up to {@code limit} registrations: new walls join, records of devices no longer registered leave. */
    private void discover(int limit, long now) {
        if (sweepAt >= sweep.size()) {
            sweep = new ArrayList<>(topology.active().keySet());
            sweepAt = 0;
            // A record whose device is gone (neither registered nor queued) leaves with it.
            var latest = topology.latest();
            walls.keySet().removeIf(id -> !latest.containsKey(id));
            islands.keySet().removeIf(island -> !live(island));
        }
        var active = topology.active();
        for (int k = 0; k < limit && sweepAt < sweep.size(); k++) {
            long id = sweep.get(sweepAt++);
            var r = active.get(id);
            if (r == null) continue;
            var kind = kind(r);
            var w = walls.get(id);
            if (kind == null) {
                if (w != null) {
                    if (w.isNew()) walls.remove(id);
                    else w.kind = null;
                }
                continue;
            }
            if (w == null) walls.put(id, w = new Record(now));
            w.kind = kind;
        }
    }

    private boolean live(long island) {
        for (var id : walls.keySet()) {
            Long o = owner.apply(id);
            if (o != null && o == island) return true;
        }
        return false;
    }

    // ---------------- the engine ----------------

    /** A commit or replay of {@code island} over online ticks {@code start} to {@code end}: its result is kept, nothing else. */
    void booked(long island, long start, long end, PassiveIntervalSolver.Result result) {
        if (end <= start || result == null) return;
        islands.put(island, new Booked(result, end));
    }

    /** One island's lookups for one cadence's evaluations, built at its first record's. */
    private static final class View {
        final PassiveIntervalSolver.Result result;
        final List<String> compounds;
        final Map<Long, Integer> nodes = new HashMap<>(), pipes = new HashMap<>(), movers = new HashMap<>();
        final double[] flows;
        final boolean solids;
        Map<Long, PassiveStepSolver.PipeObservation> rows;
        Map<Long, PipeTransfer> transfers;

        View(PassiveIntervalSolver.Result result, List<String> compounds) {
            this.result = result;
            this.compounds = compounds;
            var g = result.graph();
            for (int n = 0; n < g.reservoirs().size(); n++) nodes.put(g.reservoirs().get(n).id(), n);
            for (int e = 0; e < g.pipes().size(); e++) {
                var p = g.pipes().get(e);
                pipes.put(p.id(), e);
                if (MachineHealth.worn(p.control())) movers.putIfAbsent(g.reservoirs().get(p.first()).id(), e);
            }
            flows = result.averageMassFlows();
            boolean any = false;
            for (var t : result.pipeTransfers()) if (!t.forward().solids().empty() || !t.reverse().solids().empty()) { any = true; break; }
            solids = any;
        }

        PassiveStepSolver.PipeObservation row(long pipe) {
            if (rows == null) {
                rows = new HashMap<>();
                for (var r : result.observations()) rows.put(r.pipe(), r);
            }
            return rows.get(pipe);
        }

        /** The inert solid mass share of what connection {@code pipe} carried over the slice, 0 to 1. */
        double solidShare(long pipe) {
            if (!solids) return 0;
            if (transfers == null) {
                transfers = new HashMap<>();
                for (var t : result.pipeTransfers()) transfers.put(t.pipeId(), t);
            }
            return MachineHealth.solidShare(transfers.get(pipe));
        }
    }

    /**
     * The cadence: discovery, then every record books to now, then at most {@link #EVALUATIONS} records whose island booked a
     * span since their last evaluation set their rates, round-robin; a wall through fails; the facts go to the ledger.
     */
    public void cadence() {
        long now = coordinator.now();
        var s = settings.get();
        discover(SWEEP, now);
        for (var w : walls.values()) book(w, now, s);
        var views = new HashMap<Long, View>();
        int done = 0;
        long last = cursor;
        var order = new ArrayList<Map.Entry<Long, Record>>(walls.tailMap(cursor, false).entrySet());
        order.addAll(walls.headMap(cursor, true).entrySet());
        for (var e : order) {
            if (done >= EVALUATIONS) break;
            var w = e.getValue();
            if (w.kind == null) continue;
            long id = e.getKey();
            Long island = owner.apply(id);
            var b = island == null ? null : islands.get(island);
            if (b == null || b.end <= w.evaluated) continue;
            var view = views.get(island);
            if (view == null || view.result != b.result) {
                view = new View(b.result, coordinator.model(island).components());
                views.put(island, view);
            }
            evaluate(id, w, view, s);
            w.evaluated = b.end;
            last = id;
            done++;
        }
        cursor = last;
        evaluations += done;
        // With both switches off nothing fails from a wall (plan section 12); a wall already through stays so, its failure waits.
        if (s.corrosion() || s.erosion()) for (var e : walls.entrySet()) {
            var w = e.getValue();
            if (w.kind == null) continue;
            var r = topology.active().get(e.getKey());
            if (r == null) continue;
            if (WallState.stage(w.loss, r.spec().metallurgy().allowance()) != WallState.Stage.THROUGH) w.failed = false;
            else if (!w.failed) {
                fail(e.getKey(), w, r, s);
                w.failed = true;
            }
        }
        weeps();
        flush();
    }

    /** The walls that weep, as last told to the host (state, not saved: the first cadence after a load tells them again). */
    private final java.util.Set<Long> weeping = new java.util.HashSet<>();

    /** Tells the host which walls weep (WP4): each weeping wall at every cadence, a wall that stopped once. */
    private void weeps() {
        var active = topology.active();
        var now = new java.util.HashSet<Long>();
        for (var e : walls.entrySet()) {
            var w = e.getValue();
            if (w.kind == null || w.wrecked) continue;
            var r = active.get(e.getKey());
            if (r != null && WallState.stage(w.loss, r.spec().metallurgy().allowance()) == WallState.Stage.WEEPING) now.add(e.getKey());
        }
        for (long id : weeping) if (!now.contains(id)) host.weeping(id, false);
        for (long id : now) host.weeping(id, true);
        weeping.clear();
        weeping.addAll(now);
    }

    /** Whether device {@code id}'s wall weeps, as the last cadence or repair told the host. */
    public boolean weeping(long id) {
        return weeping.contains(id);
    }

    /** Books {@code w}'s rate and, on a flowing heat side, its deposit, from its last booking to {@code now}. */
    private void book(Record w, long now, Settings s) {
        long ticks = now - w.bookedTick;
        if (ticks <= 0) return;
        double dt = coordinator.time().seconds(ticks);
        double rate = w.rate(s);
        if (rate > 0 && dt > 0) w.loss = Math.min(WorldTopologyLedger.IntegrityFacts.MAX_LOSS, w.loss + rate * dt / (s.yearProcessHours() * 3600));
        if (s.fouling() && w.kind == Kind.HEAT_SIDE && w.flowing) w.fouling = FoulingLaw.step(w.fouling, w.asymptote, dt, s.asymptoteProcessHours());
        w.bookedTick = now;
    }

    /** {@code w}'s rate, mechanism and fouling state from its island's last result. */
    private void evaluate(long id, Record w, View view, Settings s) {
        var r = topology.active().get(id);
        if (r == null) return;
        var metal = r.spec().metallurgy();
        var g = view.result.graph();
        FluidThermodynamics.State state = null;
        long pipe = 0;
        int edge = -1;
        switch (w.kind) {
            case VESSEL -> {
                Integer n = view.nodes.get(id);
                if (n != null && !g.reservoirs().get(n).empty()) state = g.reservoirs().get(n).state();
            }
            case HEAT_SIDE -> {
                pipe = PhysicalFluidTopology.sideIdentity(id);
                Integer e = view.pipes.get(pipe);
                if (e != null) {
                    edge = e;
                    state = hotter(g, g.pipes().get(e));
                }
            }
            case MACHINE -> {
                Integer e = view.movers.get(id);
                if (e != null) {
                    edge = e;
                    pipe = g.pipes().get(e).id();
                    state = hotter(g, g.pipes().get(e));
                }
            }
        }
        var digest = CorrosionDigest.EMPTY;
        if (state != null) {
            var standard = standard(view.compounds);
            if (standard != null && standard.size() == state.componentCount()) {
                var v = state.vaporAmountsView();
                var l = state.liquidAmountsView();
                var a = state.aqueousAmountsView();
                var total = new double[v.length];
                for (int c = 0; c < total.length; c++) total[c] = v[c] + l[c] + a[c];
                digest = standard.digest(total, state.temperature(), state.pressure(), l, a);
            }
        }
        double share = edge >= 0 && w.kind == Kind.HEAT_SIDE ? view.solidShare(pipe) : 0;
        // Both parts are set whatever the switches; the booking and the reads take each only while its switch is on.
        var in = CorrosionLaw.inside(metal, digest);
        double inside = in.rate(), outside = 0, erosion = 0;
        if (s.external()) outside = CorrosionLaw.outside(metal, host.corrosiveAir(r.device().position()));
        if (w.kind == Kind.HEAT_SIDE && edge >= 0 && share > 0) {
            var row = view.row(pipe);
            var sections = g.pipes().get(edge).sections();
            double area = sections.isEmpty() ? 0 : sections.getFirst().area();
            double density = row == null ? 0 : row.density();
            double speed = row != null && density > 0 && area > 0 ? Math.abs(row.massFlow()) / density / area : 0;
            erosion = ErosionLaw.wall(metal, share, speed);
        }
        double corrosion = inside + outside;
        w.corrosion = Double.isFinite(corrosion) && corrosion > 0 ? Math.min(corrosion, CorrosionLaw.MAX_RATE) : 0;
        w.erosion = Double.isFinite(erosion) && erosion > 0 ? Math.min(erosion, CorrosionLaw.MAX_RATE) : 0;
        w.corroding = w.corrosion <= 0 ? null : outside > inside ? Mechanism.CORROSIVE_AIR : in.mechanism();
        if (w.kind == Kind.HEAT_SIDE) {
            w.flowing = edge >= 0 && Math.abs(view.flows[edge]) > IDLE_FLOW;
            w.asymptote = FoulingLaw.asymptote(digest, share);
        }
    }


    /** The hotter of {@code pipe}'s two end nodes' states, null when both are empty. */
    private static FluidThermodynamics.State hotter(PassiveNetwork g, PassiveNetwork.Pipe pipe) {
        var a = g.reservoirs().get(pipe.first());
        var b = g.reservoirs().get(pipe.second());
        boolean ea = a.empty(), eb = b.empty();
        if (ea && eb) return null;
        if (ea) return b.state();
        if (eb) return a.state();
        return b.state().temperature() > a.state().temperature() ? b.state() : a.state();
    }

    private CorrosionStandard standard(List<String> compounds) {
        var cached = standards.get(compounds);
        if (cached != null || standards.containsKey(compounds)) return cached;
        if (standards.size() >= 64) standards.clear();
        CorrosionStandard built;
        try {
            built = host.standard(compounds);
        } catch (RuntimeException failure) {
            built = null;
        }
        standards.put(List.copyOf(compounds), built);
        return built;
    }

    /** A wall through fails once by its kind's failure; a repeat finds it failed and does nothing. */
    private void fail(long id, Record w, WorldTopologyLedger.Registration r, Settings s) {
        boolean changed = switch (w.kind) {
            case VESSEL -> !r.spec().breached() && host.close(id);
            case HEAT_SIDE -> {
                boolean closed = !r.spec().breached() && host.close(id);
                if (closed) host.release(id);
                yield closed;
            }
            case MACHINE -> {
                // The breach makes the machine's health record if it has none yet; nothing while machines.wear.enabled is false.
                if (health.failed(id)) yield false;
                health.breach(id);
                yield true;
            }
        };
        if (changed && w.mechanism(s) == Mechanism.OXYGEN_FIRE && fires.size() < FIRES) fires.add(r.device().position());
    }

    // ---------------- blast, repair and cleaning ----------------

    /**
     * Marks device {@code id} wrecked (a blast, plan section 9): final; its structure is deconstructed and rebuilt, never
     * repaired. Any device of constructed equipment takes it. Server thread; the fact goes to the ledger at once.
     */
    public void wreck(long id) {
        var r = topology.active().get(id);
        if (!constructed(r)) return;
        var w = walls.get(id);
        if (w == null) {
            w = new Record(coordinator.now());
            w.kind = kind(r);
            walls.put(id, w);
        }
        if (w.wrecked) return;
        w.wrecked = true;
        flush();
    }

    /**
     * Queues the Equipment Constructor's repair of a structure's devices {@code devices}; applied at the next tick drain
     * ({@link #apply}): {@link Outcome#WRECKED} when one is wrecked, {@link Outcome#NOTHING_TO_REPAIR} when none has wall
     * loss, a breach or worn health, {@link Outcome#NOT_IDLE} while one carries flow or runs, {@link Outcome#NOT_EMPTY}
     * while a vessel holds liquid or pressure; else every wall is new, every machine's health 1, every breach closed and
     * every closed heat side open again ({@link Outcome#REPAIRED}). No item is consumed.
     */
    public void queueRepair(List<Long> devices, Consumer<Outcome> reply) {
        events.add(new Request(List.copyOf(devices), false, Objects.requireNonNull(reply)));
    }

    /** Queues the cleaning of heat sides {@code sides} under the same rule: every deposit to 0 ({@link Outcome#CLEANED}). */
    public void queueClean(List<Long> sides, Consumer<Outcome> reply) {
        events.add(new Request(List.copyOf(sides), true, Objects.requireNonNull(reply)));
    }

    public boolean hasEvents() {
        return !events.isEmpty();
    }

    /** Applies the queued repairs and cleanings. */
    public void apply() {
        long now = coordinator.now();
        var s = settings.get();
        while (!events.isEmpty()) {
            var q = events.poll();
            Outcome outcome;
            try {
                outcome = q.clean() ? clean(q.devices(), now, s) : repair(q.devices(), now, s);
            } catch (RuntimeException failure) {
                com.wormzjl.icheme.IChemE.LOGGER.warn("integrity status=REPAIR_FAILED devices={} detail={}", q.devices(), String.valueOf(failure));
                outcome = Outcome.NOTHING_TO_REPAIR;
            }
            q.reply().accept(outcome);
        }
        // A repaired wall stops weeping at once (WP4).
        if (!weeping.isEmpty()) weeps();
        flush();
    }

    private Outcome repair(List<Long> devices, long now, Settings s) {
        var active = topology.active();
        boolean needed = false;
        for (long id : devices) {
            var w = walls.get(id);
            if (w != null && w.wrecked) return Outcome.WRECKED;
            var r = active.get(id);
            if (r == null) continue;
            Double h = health.health(id);
            if (w != null && w.loss > 0 || r.spec().breached() || h != null && h < 1) needed = true;
        }
        if (!needed) return Outcome.NOTHING_TO_REPAIR;
        var refusal = idleAndEmpty(devices);
        if (refusal != null) return refusal;
        for (long id : devices) {
            var r = active.get(id);
            if (r == null) continue;
            var w = walls.get(id);
            if (w != null) {
                book(w, now, s);
                w.loss = 0;
                w.failed = false;
            }
            if (r.spec().breached()) host.open(id);
            health.renew(id);
        }
        return Outcome.REPAIRED;
    }

    private Outcome clean(List<Long> sides, long now, Settings s) {
        boolean needed = false;
        for (long id : sides) {
            var w = walls.get(id);
            if (w != null && w.wrecked) return Outcome.WRECKED;
            if (w != null && w.kind == Kind.HEAT_SIDE && w.fouling > 0) needed = true;
        }
        if (!needed) return Outcome.NOTHING_TO_REPAIR;
        var refusal = idleAndEmpty(sides);
        if (refusal != null) return refusal;
        for (long id : sides) {
            var w = walls.get(id);
            if (w == null || w.kind != Kind.HEAT_SIDE) continue;
            book(w, now, s);
            w.fouling = 0;
        }
        return Outcome.CLEANED;
    }

    /** Null when every device is idle and every vessel among them empty; else what is missing. */
    private Outcome idleAndEmpty(List<Long> devices) {
        var active = topology.active();
        for (long id : devices) if (!idle(id, active.get(id))) return Outcome.NOT_IDLE;
        for (long id : devices) {
            var r = active.get(id);
            if (r != null && r.device().kind() == TopologyCompiler.Kind.RESERVOIR && !empty(id)) return Outcome.NOT_EMPTY;
        }
        return null;
    }

    /**
     * Whether device {@code id} carries nothing: no connection at its node moved more than {@link #IDLE_FLOW} over its
     * island's last committed slice and no mover of it runs ({@link MachineHealth#idle}); a heat side: its passage moved nothing. A device held by a running column is not idle; one in no
     * island is.
     */
    boolean idle(long id, WorldTopologyLedger.Registration r) {
        if (r == null) return true;
        if (r.device().suspendedBy() != 0) return false;
        if (r.device().kind() != TopologyCompiler.Kind.HEAT_SIDE) return health.idle(id);
        // A heat side is its passage, a connection of its own: idle when the passage moved nothing.
        Long island = owner.apply(id);
        var result = island == null ? null : coordinator.observe(island).lastResult().orElse(null);
        if (result == null) return true;
        long passage = PhysicalFluidTopology.sideIdentity(id);
        var pipes = result.graph().pipes();
        for (int e = 0; e < pipes.size(); e++) if (pipes.get(e).id() == passage) return Math.abs(result.averageMassFlows()[e]) <= IDLE_FLOW;
        return true;
    }

    /** Whether vessel {@code id} is empty: less than {@link #EMPTY_SHARE} of its volume liquid, within {@link #EMPTY_PRESSURE} of the ambient. */
    boolean empty(long id) {
        Long island = owner.apply(id);
        if (island == null) return true;
        var g = coordinator.observe(island).graph();
        for (var n : g.reservoirs()) {
            if (n.id() != id) continue;
            if (n.kind() != PassiveNetwork.NodeKind.RESERVOIR || n.empty()) return true;
            var st = n.state();
            return st.condensedVolume() < EMPTY_SHARE * n.inventory().volume()
                    && Math.abs(st.pressure() - com.wormzjl.icheme.science.fluid.solver.PhaseLayout.AMBIENT_PRESSURE) <= EMPTY_PRESSURE;
        }
        return true;
    }

    // ---------------- facts and reads ----------------

    /** Hands the walls' facts to the ledger: at the cadence, after an event and before a save. */
    public void flush() {
        topology.integrity(facts());
    }

    /** The walls' facts, by device: only those away from new and clean. */
    public Map<Long, WorldTopologyLedger.IntegrityFacts> facts() {
        var out = new HashMap<Long, WorldTopologyLedger.IntegrityFacts>();
        walls.forEach((id, w) -> {
            if (!w.isNew()) out.put(id, new WorldTopologyLedger.IntegrityFacts(Math.min(w.loss, WorldTopologyLedger.IntegrityFacts.MAX_LOSS),
                    Math.min(Math.max(w.fouling, 0), FoulingLaw.CAP), w.wrecked));
        });
        return out;
    }

    /** Device {@code id}'s wall (WP4's Integrity group), or null for a device whose wall is not booked. */
    public Wall wall(long id) {
        var w = walls.get(id);
        var r = topology.active().get(id);
        if (w == null || w.kind == null || r == null) return null;
        double allowance = r.spec().metallurgy().allowance();
        var s = settings.get();
        double rate = w.rate(s);
        return new Wall(w.loss, allowance, WallState.stage(w.loss, allowance), rate, w.mechanism(s),
                WallState.lifeProcessSeconds(w.loss, allowance, rate, s.yearProcessHours()));
    }

    /** Heat side {@code id}'s fouling deposit, m²K/W; 0 for none. */
    public double fouling(long id) {
        var w = walls.get(id);
        return w == null ? 0 : w.fouling;
    }

    /** Heat side {@code id}'s deposit in whole steps of {@link FoulingLaw#STEP}, 0 to 20: what the films read (WP5). */
    public int foulingLevel(long id) {
        return FoulingLaw.level(fouling(id));
    }

    /** Whether a blast wrecked device {@code id}. */
    public boolean wrecked(long id) {
        var w = walls.get(id);
        return w != null && w.wrecked;
    }

    /** Device {@code id}'s kind as last discovered, or null. */
    public Kind kindOf(long id) {
        var w = walls.get(id);
        return w == null ? null : w.kind;
    }

    /** Records evaluated since the start (a counter for the tests and the diagnostics). */
    public long evaluations() {
        return evaluations;
    }

    /**
     * The plant's fires from walls an oxygen fire burnt through, at {@code dimension}'s chunk {@code (chunkX, chunkZ)}: each is
     * read once, by the next pollution pass that asks for its chunk.
     */
    public List<PhysicalFluidTopology.Position> fires(String dimension, int chunkX, int chunkZ) {
        if (fires.isEmpty()) return List.of();
        var out = new ArrayList<PhysicalFluidTopology.Position>();
        for (var it = fires.iterator(); it.hasNext(); ) {
            var p = it.next();
            if (p.dimension().equals(dimension) && p.x() >> 4 == chunkX && p.z() >> 4 == chunkZ) {
                out.add(p);
                it.remove();
            }
        }
        return out;
    }

    /**
     * Device {@code id}'s wall words for its status line, said once: {@code WRECKED: deconstruct and rebuild} first; then
     * from the used allowance {@code THIN WALL} (0.5), {@code WEEPING} (0.9) and {@code LEAKING} (through; a heat side adds
     * {@code tube leak}; a machine through says nothing here, its {@code FAILED} stands); else empty.
     */
    public String status(long id) {
        var w = walls.get(id);
        if (w == null) return "";
        if (w.wrecked) return "WRECKED: deconstruct and rebuild";
        var r = topology.active().get(id);
        if (w.kind == null || r == null) return "";
        return switch (WallState.stage(w.loss, r.spec().metallurgy().allowance())) {
            case SOUND -> "";
            case THIN -> "THIN WALL";
            case WEEPING -> "WEEPING";
            case THROUGH -> w.kind == Kind.MACHINE ? "" : w.kind == Kind.HEAT_SIDE ? "LEAKING: tube leak" : "LEAKING";
        };
    }

    /** Sets device {@code id}'s wall facts (the {@code /icheme integrity} command and the tests); a device the walls do not book takes only {@code wrecked}. */
    public boolean set(long id, double loss, double fouling, boolean wrecked) {
        var r = topology.active().get(id);
        if (!constructed(r)) return false;
        var facts = new WorldTopologyLedger.IntegrityFacts(loss, fouling, wrecked);
        var w = walls.get(id);
        if (w == null) {
            w = new Record(coordinator.now());
            w.kind = kind(r);
            walls.put(id, w);
        }
        w.loss = facts.wallLoss();
        w.fouling = facts.fouling();
        w.wrecked = facts.wrecked();
        flush();
        return true;
    }

    /** Sets record {@code id}'s rate (mm/y) and mechanism directly: a test's hook, as an evaluation would. */
    void rate(long id, double rate, Mechanism mechanism) {
        var w = walls.get(id);
        if (w == null) return;
        if (mechanism == Mechanism.EROSION) w.erosion = rate;
        else {
            w.corrosion = rate;
            w.corroding = mechanism;
        }
    }

    /** The compound lists' standards, from per-compound columns on the world list (a host's helper). */
    static Function<List<String>, CorrosionStandard> standards(List<String> world, double[] molarMasses, double[][] formulas, double[] sulfur, double[] acid, double[] fouling) {
        return compounds -> {
            int n = compounds.size();
            var m = new double[n];
            var f = new double[n][];
            var s = new double[n];
            var a = new double[n];
            var o = new double[n];
            for (int c = 0; c < n; c++) {
                int i = world.indexOf(compounds.get(c));
                if (i < 0) return null;
                m[c] = molarMasses[i];
                f[c] = formulas[i];
                s[c] = sulfur[i];
                a[c] = acid[i];
                o[c] = fouling[i];
            }
            return new CorrosionStandard(m, f, s, a, o);
        };
    }
}
