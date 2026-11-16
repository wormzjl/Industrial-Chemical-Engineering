package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.ShaftPlan;
import com.wormzjl.icheme.science.machine.Wear;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * The machines' health (batch 2026-11-03-rotating-machinery W10b, build plan revision 2, owner decision D34; one class for
 * every machine since code base review M-12): every machine with a fault ({@link #faults}), a constructed casing, an
 * in-line pump or compressor, a dry screw or a steam turbine, keyed by its device identity (the node its connection leaves).
 * Health is a fact, 1 (new) to 0 (failed); arithmetic on the server thread only:
 * <ul>
 * <li>at each commit and replay its wear rate is read from its faults' intensities at the slice's committed suction (its
 * {@link PassiveStepSolver.PipeObservation#npshRatio}, {@link PassiveStepSolver.PipeObservation#dry}, a dry screw's liquid
 * share, a centrifugal compressor's wet suction and a steam turbine's own exhaust moisture, both
 * {@link PassiveStepSolver.PipeObservation#condensedShare}), by the rule of {@link Wear#rate(double, double, Wear.Settings)}, and booked over
 * the slice's process time, its online ticks times the time factor ({@link #booked});</li>
 * <li>on the presentation cadence a pump not booked for a cadence (a resting island) books its last rate to now, one that
 * reached 0 fails, and the facts go to the ledger ({@link #cadence});</li>
 * <li>a failed pump is closed at dispatch by a {@link ShaftPlan.Kind#FAILED} plan, its settings kept ({@link #plans});</li>
 * <li>a repair kit is a queued event ({@link #queue}), applied at the tick drain ({@link #apply}).</li>
 * </ul>
 * A failure and a repair wake the pump's island; wear above 0 wakes nothing and changes nothing the solver reads.
 * {@code machines.wear.enabled = false} books nothing and closes nothing.
 */
public final class MachineHealth {
    /** The presentation cadence, online ticks (as the electric stores'). */
    public static final int CADENCE = ElectricStores.CADENCE;

    /**
     * What a repair kit came to: the item is consumed only for {@code REPAIRED}. {@code NOT_IDLE} (batch
     * 2026-11-16-fouling-corrosion, WP2): flow passed the device over its island's last committed slice, so the kit is kept.
     * A breached vessel is repaired with the Equipment Constructor ({@link Integrity#queueRepair}), no longer with a kit.
     */
    public enum Outcome { REPAIRED, FULL, NOT_A_PUMP, NO_KIT, NOT_IDLE }

    /**
     * A repair kit used on a pump: {@code take} consumes the kit at acceptance (false when it can no longer, the player
     * gone or the kit spent; true without consuming in creative mode), {@code reply} tells the player the outcome.
     */
    public record Repair(long device, BooleanSupplier take, Consumer<Outcome> reply) {
        public Repair {
            Objects.requireNonNull(take);
            Objects.requireNonNull(reply);
        }
    }

    /**
     * A pump's suction as its last committed slice read it (W10d, owner decision D37), for its page: NPSH available and NPSH3
     * at the accepted flow (m), the vapour volume share α (0 to 1), σ = NPSHA/NPSH3 and the gas ratio r = α/α_T; each NaN
     * where the slice made no read (a stopped pump, no vapour-pressure floor, a set-flow pump's r).
     */
    public record Suction(double available, double required, double gas, double sigma, double ratio) {
        public static final Suction NONE = new Suction(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    private static final class Pump {
        double health = 1, rate;
        /** The sound bytes of its last committed suction (W10c): the crackle {@code 255 I_c(σ)} and the churn {@code 255 v(r)}. */
        int crackle, churn;
        Suction suction = Suction.NONE;
        /** A dry screw's suction liquid volume share at its last committed slice (owner V6 B); 0 for a pump. */
        double liquid;
        /** The fault that set the larger class term of its last booking, and its intensity (M-12): state, never saved; null for none. */
        Wear.Fault fault;
        double intensity;
        /** Its low-flow intensity at its last committed slice (N1): its {@code LOW FLOW} word while above 0. */
        double lowFlow;
        /** A centrifugal compressor's wet gas intensity at its last committed slice (N2): its {@code WET GAS} word while above 0. */
        double wetGas;
        /** A steam turbine's wet exhaust intensity at its last committed slice (N4): its {@code WET EXHAUST} word while above 0. */
        double wetExhaust;
        long bookedTick;
        boolean failed;
    }

    private final WorldTopologyLedger topology;
    private final LongFunction<Long> owner;
    private final IslandCoordinator coordinator;
    private final LongPredicate isPump;
    private final LongFunction<FlowControl> registered;
    private final Supplier<Wear.Settings> settings;
    /** The erosion switch and the corrosion year a valve trim's wear is booked on (batch 2026-11-16-fouling-corrosion). */
    private final Supplier<Integrity.Settings> integrity;
    private final Map<Long, Pump> pumps = new TreeMap<>();
    private final ArrayDeque<Repair> events = new ArrayDeque<>();
    /** The machines a blast breached since the last drain (WP6b). */
    private final ArrayDeque<Long> breaches = new ArrayDeque<>();
    private final Set<Long> wake = new TreeSet<>();

    /**
     * @param owner  a fluid device's island, or null ({@link PhysicalRegistry#owner})
     * @param isPump     whether a device identity is a pump that stands: a registration whose control is a liquid mover
     * @param registered a device's registered control (null for none): a repaired in-line pump switches back on only where
     *                   its registration is switched on
     */
    public MachineHealth(WorldTopologyLedger topology, LongFunction<Long> owner, IslandCoordinator coordinator, LongPredicate isPump, LongFunction<FlowControl> registered,
                      Supplier<Wear.Settings> settings) {
        this(topology, owner, coordinator, isPump, registered, settings, () -> Integrity.Settings.DEFAULT);
    }

    /** @param integrity the erosion switch and the corrosion year of the valves' trims (config {@code erosion.*}, {@code corrosion.*}) */
    public MachineHealth(WorldTopologyLedger topology, LongFunction<Long> owner, IslandCoordinator coordinator, LongPredicate isPump, LongFunction<FlowControl> registered,
                      Supplier<Wear.Settings> settings, Supplier<Integrity.Settings> integrity) {
        this.integrity = Objects.requireNonNull(integrity);
        this.topology = Objects.requireNonNull(topology);
        this.owner = Objects.requireNonNull(owner);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.isPump = Objects.requireNonNull(isPump);
        this.registered = Objects.requireNonNull(registered);
        this.settings = Objects.requireNonNull(settings);
        long now = coordinator.now();
        // The saved health; a pump saved at 0 stands failed from its first dispatch.
        topology.health().forEach((id, value) -> {
            var p = new Pump();
            p.health = value;
            p.failed = value <= 0;
            p.bookedTick = now;
            pumps.put(id, p);
        });
    }

    private static final List<Wear.Fault> LIQUID_MOVER = List.of(Wear.Fault.CAVITATION, Wear.Fault.DRY, Wear.Fault.SOLIDS), SCREW = List.of(Wear.Fault.LIQUID),
            CENTRIFUGAL = List.of(Wear.Fault.CAVITATION, Wear.Fault.DRY, Wear.Fault.LOW_FLOW, Wear.Fault.SOLIDS), COMPRESSOR = List.of(Wear.Fault.WET_GAS),
            TURBINE = List.of(Wear.Fault.WET_EXHAUST), RING = List.of(Wear.Fault.SOLIDS);

    /**
     * The faults {@code control} reads (M-12): a liquid mover cavitation, running dry and solids in its stream (batch
     * 2026-11-16-fouling-corrosion: {@link Wear.Fault#SOLIDS}, the erosion class), and a centrifugal pump on its curve also
     * low flow (N1; a set-flow pump has no curve); a dry screw vacuum pump liquid at its suction (owner V6 B); a liquid ring
     * vacuum pump solids in its stream; a centrifugal compressor on its curve wet gas (N2: a constructed casing and an
     * in-line compressor; a reciprocating frame is a set-flow compressor and has none); a steam turbine, a control and not a
     * mover, wet exhaust (N4); none for another control.
     */
    static List<Wear.Fault> faults(FlowControl control) {
        if (control instanceof FlowControl.Turbine) return TURBINE;
        if (!(control instanceof FlowControl.Mover mover)) return List.of();
        if (mover.liquid()) return mover instanceof FlowControl.Machine m && m.law() instanceof com.wormzjl.icheme.science.machine.CentrifugalPump ? CENTRIFUGAL : LIQUID_MOVER;
        if (mover instanceof FlowControl.Machine m && com.wormzjl.icheme.science.machine.VacuumPump.dryScrew(m.law())) return SCREW;
        if (mover instanceof FlowControl.Machine m && m.law() instanceof com.wormzjl.icheme.science.machine.VacuumPump) return RING;
        if (mover instanceof FlowControl.Machine m && m.law() instanceof com.wormzjl.icheme.science.machine.CentrifugalCompressor) return COMPRESSOR;
        return List.of();
    }

    /**
     * Whether registration {@code r} is a control valve whose trim wears by particles (batch 2026-11-16-fouling-corrosion,
     * plan section 8): a pipe fitting (no ports) whose control is a {@link FlowControl.ControlValve} that throttles: not a
     * valve built into a sink (a fired heater's, a flare's, a prime mover's), not a gas blanketing valve, not an automated
     * shutoff valve. Its health record is created at its first wear above 0.
     */
    public static boolean trim(WorldTopologyLedger.Registration r) {
        return r != null && r.device().ports().isEmpty() && r.device().control() instanceof FlowControl.ControlValve v && trims(v);
    }

    /** Whether control valve {@code v}'s trim wears ({@link #trim}). */
    static boolean trims(FlowControl.ControlValve v) {
        return !v.source().sink() && !v.onOffValve() && v.source() != FlowControl.ValveSource.BLANKET;
    }

    /** Whether registration {@code r} has a health record: a machine that wears ({@link #pump}) or a valve trim ({@link #trim}). */
    public static boolean tracked(WorldTopologyLedger.Registration r) {
        return pump(r) || trim(r);
    }

    /**
     * The inert solid mass share of what connection transfer {@code t} carried, 0 to 1: its solids, crystals left out
     * (they melt), over its mass, both directions; 0 for none.
     */
    static double solidShare(com.wormzjl.icheme.science.fluid.network.PipeTransfer t) {
        if (t == null) return 0;
        double solids = t.forward().solids().withoutCrystals().massKg() + t.reverse().solids().withoutCrystals().massKg();
        double mass = t.forward().massKg() + t.reverse().massKg();
        if (!(solids > 0) || !(mass > 0)) return 0;
        double share = solids / mass;
        return Double.isFinite(share) ? Math.min(share, 1) : 0;
    }

    /** Whether {@code control} wears: it has a fault ({@link #faults}). */
    static boolean worn(FlowControl control) {
        return !faults(control).isEmpty();
    }

    /**
     * Whether registration {@code r} is a machine that wears, "a pump" in this class's words: its control a liquid mover (a
     * constructed casing or an in-line pump), a dry screw, a centrifugal compressor or a steam turbine. The cadence keeps the
     * entries of these devices only.
     */
    public static boolean pump(WorldTopologyLedger.Registration r) {
        return r != null && worn(r.device().control());
    }

    private Wear.Settings settings() {
        return settings.get();
    }

    private Pump pump(long id) {
        var p = pumps.get(id);
        if (p == null && isPump.test(id)) {
            p = new Pump();
            p.bookedTick = coordinator.now();
            pumps.put(id, p);
        }
        return p;
    }

    /** Books {@code p} at {@code rate} (per process second) from its last booking or {@code from}, whichever is later, to {@code to}. */
    private void book(Pump p, double rate, long from, long to) {
        long a = Math.max(from, p.bookedTick);
        if (to > a && rate > 0) p.health = Math.max(0, p.health - coordinator.time().seconds(rate * (to - a)));
        p.rate = rate;
        p.bookedTick = Math.max(p.bookedTick, to);
    }

    // ---------------- the engine ----------------

    /**
     * A commit or replay of {@code island} over online ticks {@code start} to {@code end}: each pump books the wear its
     * committed suction gives. A pump reaching 0 fails at the next cadence (a replay may be a view's read, which wakes
     * nothing).
     */
    void booked(long island, long start, long end, PassiveIntervalSolver.Result result) {
        if (end <= start) return;
        var solved = result.graph();
        Map<Long, PassiveStepSolver.PipeObservation> rows = null;
        var s = settings();
        var flows = result.endpointFlows();
        Map<Long, com.wormzjl.icheme.science.fluid.network.PipeTransfer> moved = null;
        boolean solids = false;
        for (var t : result.pipeTransfers()) if (!t.forward().solids().empty() || !t.reverse().solids().empty()) { solids = true; break; }
        for (int e = 0; e < solved.pipes().size(); e++) {
            var pipe = solved.pipes().get(e);
            var control = pipe.control();
            // A control valve's trim (batch 2026-11-16-fouling-corrosion, plan section 8): particles wear it at its own drop's speed.
            if (control instanceof FlowControl.ControlValve valve && trims(valve)) {
                long id = solved.reservoirs().get(pipe.first()).id();
                double rate = 0;
                var is = integrity.get();
                if (solids && s.enabled() && is.erosion()) {
                    if (moved == null) {
                        moved = new HashMap<>();
                        for (var t : result.pipeTransfers()) moved.put(t.pipeId(), t);
                    }
                    double share = solidShare(moved.get(pipe.id()));
                    if (share > 0) {
                        if (rows == null) {
                            rows = new HashMap<>();
                            for (var row : result.observations()) rows.put(row.pipe(), row);
                        }
                        var row = rows.get(pipe.id());
                        if (row != null) rate = com.wormzjl.icheme.science.integrity.ErosionLaw.trim(share,
                                com.wormzjl.icheme.science.integrity.ErosionLaw.trimSpeed(row.valveDrop(), row.density()), is.yearProcessHours());
                    }
                }
                // Its record is made at its first wear above 0 (a valve that never wears keeps none).
                var p = rate > 0 ? pump(id) : pumps.get(id);
                if (p != null) {
                    book(p, rate, start, end);
                    p.fault = rate > 0 ? Wear.Fault.SOLIDS : null;
                    p.intensity = rate > 0 ? 1 : 0;
                }
                continue;
            }
            if (!worn(control)) continue;
            long id = solved.reservoirs().get(pipe.first()).id();
            var p = pump(id);
            if (p == null) continue;
            if (rows == null) {
                rows = new HashMap<>();
                for (var row : result.observations()) rows.put(row.pipe(), row);
            }
            var row = rows.get(pipe.id());
            // The solid mass share the mover's connection carried (batch 2026-11-16-fouling-corrosion): its SOLIDS fault's
            // driving quantity; 0 with no solids anywhere on the island's last slice.
            double carried = 0;
            if (solids && row != null && integrity.get().erosion()) {
                if (moved == null) {
                    moved = new HashMap<>();
                    for (var t : result.pipeTransfers()) moved.put(t.pipeId(), t);
                }
                carried = solidShare(moved.get(pipe.id()));
            }
            // A mover reads while it runs (a set or curve flow above 0); a steam turbine while steam passes forward through it.
            var m = control instanceof FlowControl.Mover mover ? mover : null;
            boolean read = row != null && (m != null ? m.targetVolumeFlow() > 0 : flows[e] > 0);
            // A dry screw's liquid share (owner V6 B): its LIQUID fault's driving quantity, and its liquid noise.
            double liquid = m != null && !m.liquid() && read && Double.isFinite(row.vapourShare()) ? 1 - row.vapourShare() : 0;
            // A centrifugal pump's flow over its rated flow at its speed (N1): its suction volume flow, the committed endpoint
            // flow over the suction node's density (as the trains book its shaft power; the row's density is the drawn stream's).
            double share = Double.NaN;
            if (read && m instanceof FlowControl.Machine machine && machine.law() instanceof com.wormzjl.icheme.science.machine.CentrifugalPump law && machine.speed() > 0) {
                var suction = solved.reservoirs().get(pipe.first()).state();
                double density = suction.volume() > 0 ? suction.mass() / suction.volume() : 0;
                if (density > 0) share = Math.max(0, flows[e]) / density / (law.ratedFlow() * machine.speed());
            }
            p.lowFlow = Wear.lowFlow(share);
            // A centrifugal compressor's suction condensed mass share (N2): the number its wet efficiency read in the solve,
            // carried by its observation row (NaN on every other row).
            double wet = read && m != null ? row.condensedShare() : Double.NaN;
            p.wetGas = Wear.wetGas(wet);
            // A steam turbine's own exhaust moisture (N4): its stream at the exhaust pressure and at its inlet enthalpy less its
            // shaft work, flashed by the solve and carried by its observation row. What the exhaust node holds does not enter.
            // Read against the onset at its exhaust pressure, the row's second end (W5, Wear.wetOnset): less the onset's rise.
            double exhaust = read && control instanceof FlowControl.Turbine ? Wear.wetExcess(row.condensedShare(), row.secondPressure()) : Double.NaN;
            p.wetExhaust = Wear.wetExhaust(exhaust);
            // The largest intensity of each class, and the fault that set it.
            double erosion = 0, seizure = 0;
            Wear.Fault eroding = null, seizing = null;
            if (read) for (var fault : faults(control)) {
                double q = switch (fault) {
                    case CAVITATION -> row.npshRatio();
                    case DRY -> row.dry() ? 1 : 0;
                    case LIQUID -> liquid;
                    case LOW_FLOW -> share;
                    case WET_GAS -> wet;
                    case WET_EXHAUST -> exhaust;
                    // The inert solid mass share of the stream (batch 2026-11-16-fouling-corrosion, WP2).
                    case SOLIDS -> carried;
                };
                double i = fault.intensity(q);
                if (fault.type == Wear.FaultClass.EROSION) {
                    if (i > erosion) {
                        erosion = i;
                        eroding = fault;
                    }
                } else if (i > seizure) {
                    seizure = i;
                    seizing = fault;
                }
            }
            book(p, Wear.rate(erosion, seizure, s), start, end);
            boolean seized = seizure / s.dryMinutes() / 60 > erosion / s.cavitationHours() / 3600;
            p.fault = seized ? seizing : eroding;
            p.intensity = seized ? seizure : erosion;
            if (m == null || !m.liquid()) {
                p.liquid = liquid;
                p.crackle = com.wormzjl.icheme.science.machine.VacuumPump.liquidNoise(liquid);
                p.churn = 0;
                p.suction = Suction.NONE;
                continue;
            }
            p.crackle = read ? (int) Math.round(255 * Wear.intensity(row.npshRatio())) : 0;
            p.churn = read ? (int) Math.round(255 * com.wormzjl.icheme.science.machine.GasEntrainment.churn(row.gasRatio())) : 0;
            p.suction = read ? new Suction(row.npshRatio() * row.npshRequired(), row.npshRequired(), row.vapourShare(), row.npshRatio(), row.gasRatio()) : Suction.NONE;
        }
    }

    /**
     * The plans of {@code graph}'s failed machines: each closed for the slice ({@link ShaftPlan.Kind#FAILED} 0: a machine at
     * no speed, a set-flow mover switched off, a steam turbine's admission shut). An in-line pump
     * that stands closed as a failure closed it (switched off with its registration on, the same settings) and is no longer
     * failed is switched back on (1): an island keeps the controls its last slice solved with.
     */
    void plans(PassiveNetwork graph, Map<Long, ShaftPlan> out) {
        boolean enabled = settings().enabled();
        for (var pipe : graph.pipes()) {
            // A failed valve trim fails open, its settings kept (owner D5): the valve stands as an unconfigured one does. One
            // repaired since takes its registered control back (an island keeps the controls its last slice solved with).
            if (pipe.control() instanceof FlowControl.ControlValve valve && trims(valve)) {
                long id = graph.reservoirs().get(pipe.first()).id();
                var p = pumps.get(id);
                if (p == null) continue;
                if (enabled && p.failed) out.put(id, ShaftPlan.trim(null));
                else if (valve.equals(FlowControl.ControlValve.unconfigured()) && registered.apply(id) instanceof FlowControl.ControlValve own && !own.equals(valve))
                    out.put(id, ShaftPlan.trim(own));
                continue;
            }
            if (!worn(pipe.control())) continue;
            long id = graph.reservoirs().get(pipe.first()).id();
            var p = pumps.get(id);
            if (enabled && p != null && p.failed) out.put(id, new ShaftPlan(ShaftPlan.Kind.FAILED, 0));
            else if (pipe.control() instanceof FlowControl.Pump pump && !pump.enabled() && registered.apply(id) instanceof FlowControl.Pump on && on.enabled()
                    && on.requestedVolumeFlow() == pump.requestedVolumeFlow() && on.maximumAddedPressure() == pump.maximumAddedPressure())
                out.put(id, new ShaftPlan(ShaftPlan.Kind.FAILED, 1));
        }
    }

    /** The presentation cadence: pumps follow the registrations, a resting pump books its last rate, one at 0 fails and wakes its island; the facts go to the ledger. */
    public void cadence() {
        long now = coordinator.now();
        pumps.keySet().removeIf(id -> !isPump.test(id));
        boolean enabled = settings().enabled();
        for (var e : pumps.entrySet()) {
            var p = e.getValue();
            if (now - p.bookedTick >= CADENCE) book(p, enabled ? p.rate : 0, p.bookedTick, now);
            if (enabled && !p.failed && p.health <= 0) {
                p.failed = true;
                wake(e.getKey());
            }
        }
        flushWakes();
        flush();
    }

    private void wake(long id) {
        Long island = owner.apply(id);
        if (island != null) wake.add(island);
    }

    private void flushWakes() {
        if (wake.isEmpty()) return;
        coordinator.shaftsMoved(List.copyOf(wake));
        wake.clear();
    }

    // ---------------- repair ----------------

    /** Queues a repair; it applies at the next tick drain. */
    public void queue(Repair repair) {
        events.add(Objects.requireNonNull(repair));
    }

    /**
     * Queues a blast's breach of machine {@code id}'s casing (batch 2026-11-15-pollution-system, WP6b): at the next tick drain,
     * before the repairs, its health falls to 0 and it fails as a worn-out machine does (closed by its {@link ShaftPlan.Kind#FAILED}
     * plan, its island woken); a repair kit makes it new again. Nothing for a device with no health record, nor while
     * {@code machines.wear.enabled} is false. The health is the fact; no other field is saved.
     */
    public void breach(long id) {
        breaches.add(id);
    }

    public boolean hasEvents() {
        return !events.isEmpty() || !breaches.isEmpty();
    }

    /** Applies the queued breaches, then the queued repairs: a worn pump takes its kit and is new again, and a failed one wakes its island. */
    public void apply() {
        long now = coordinator.now();
        boolean enabled = settings().enabled();
        while (!breaches.isEmpty()) {
            long id = breaches.poll();
            // machines.wear.enabled = false: no machine fails, so a blast leaves it running.
            var p = enabled ? pump(id) : null;
            if (p == null) continue;
            book(p, p.rate, p.bookedTick, now);
            p.health = 0;
            if (!p.failed) {
                p.failed = true;
                wake(id);
            }
        }
        while (!events.isEmpty()) {
            var r = events.poll();
            var p = pump(r.device());
            Outcome outcome;
            if (p == null) outcome = Outcome.NOT_A_PUMP;
            else if (p.health >= 1) outcome = Outcome.FULL;
            // A kit works on a machine or a trim no flow passes (batch 2026-11-16-fouling-corrosion, plan section 10).
            else if (!idle(r.device())) outcome = Outcome.NOT_IDLE;
            else if (!r.take().getAsBoolean()) outcome = Outcome.NO_KIT;
            else {
                book(p, p.rate, p.bookedTick, now);
                p.health = 1;
                if (p.failed) {
                    p.failed = false;
                    wake(r.device());
                }
                outcome = Outcome.REPAIRED;
            }
            r.reply().accept(outcome);
        }
        flushWakes();
        flush();
    }

    /**
     * Makes machine or trim {@code id} new (the Equipment Constructor's structure repair, {@link Integrity#queueRepair}): its
     * health 1, a failed one standing again and its island woken. Nothing for a device with no health record. Called at the
     * tick drain; the facts go to the ledger at once.
     */
    public void renew(long id) {
        var p = pumps.get(id);
        if (p == null) return;
        book(p, p.rate, p.bookedTick, coordinator.now());
        p.health = 1;
        if (p.failed) {
            p.failed = false;
            wake(id);
        }
        flushWakes();
        flush();
    }

    /**
     * Whether no flow passes machine or valve {@code id}: no connection at its node moved more than
     * {@link Integrity#IDLE_FLOW} over its island's last committed slice and no mover of it runs; a device in no island is
     * idle. A read of the stored island: nothing solves.
     */
    boolean idle(long id) {
        Long island = owner.apply(id);
        if (island == null) return true;
        var result = coordinator.observe(island).lastResult().orElse(null);
        if (result == null) return true;
        var g = result.graph();
        int node = -1;
        for (int n = 0; n < g.reservoirs().size(); n++) if (g.reservoirs().get(n).id() == id) { node = n; break; }
        if (node < 0) return true;
        var flows = result.averageMassFlows();
        for (int e = 0; e < g.pipes().size(); e++) {
            var pipe = g.pipes().get(e);
            if (pipe.first() != node && pipe.second() != node) continue;
            if (Math.abs(flows[e]) > Integrity.IDLE_FLOW) return false;
            if (pipe.first() == node && pipe.control() instanceof FlowControl.Mover m && m.targetVolumeFlow() > 0) return false;
        }
        return true;
    }

    // ---------------- facts and reads ----------------

    /** Hands the health facts to the ledger: at the cadence, after a repair and before a save. */
    public void flush() {
        topology.health(facts());
    }

    /** The health facts, by device: only pumps below 1. */
    public Map<Long, Double> facts() {
        var out = new HashMap<Long, Double>();
        pumps.forEach((id, p) -> {
            if (p.health < 1) out.put(id, p.health);
        });
        return out;
    }

    /** Pump {@code id}'s health, 0 to 1, or null when it is no pump. */
    public Double health(long id) {
        var p = pumps.get(id);
        if (p != null) return p.health;
        return isPump.test(id) ? 1.0 : null;
    }

    /** Whether pump {@code id} stands failed. */
    public boolean failed(long id) {
        var p = pumps.get(id);
        return p != null && p.failed && settings().enabled();
    }

    /** Pump {@code id}'s cavitation crackle, 0 to 255, from its last committed suction (W10c); 0 for no pump. */
    public int crackle(long id) {
        var p = pumps.get(id);
        return p == null ? 0 : p.crackle;
    }

    /** Pump {@code id}'s gas churn, 0 to 255, from its last committed suction (W10c); 0 for no pump. */
    public int churn(long id) {
        var p = pumps.get(id);
        return p == null ? 0 : p.churn;
    }

    /** Pump {@code id}'s wear rate at its last booking, health per second. */
    public double rate(long id) {
        var p = pumps.get(id);
        return p == null ? 0 : p.rate;
    }

    /** Machine {@code id}'s standing fault: the one that set the larger class term of its last booking (M-12); null for none. */
    public Wear.Fault fault(long id) {
        var p = pumps.get(id);
        return p == null ? null : p.fault;
    }

    /** The intensity, 0 to 1, of machine {@code id}'s standing fault at its last booking; 0 for none. */
    public double intensity(long id) {
        var p = pumps.get(id);
        return p == null ? 0 : p.intensity;
    }

    /** Pump {@code id}'s suction as its last committed slice read it (W10d); {@link Suction#NONE} for no read and no pump. */
    public Suction suction(long id) {
        var p = pumps.get(id);
        return p == null ? Suction.NONE : p.suction;
    }

    /**
     * Pump {@code id}'s status word on its page's message display, said once (W10d, owner decision D37): {@code FAILED} at
     * health 0, which takes precedence; else {@code LIQUID} while a dry screw takes liquid; else {@code LOW FLOW} while a
     * centrifugal pump runs below its minimum flow (N1, {@link Wear#lowFlow} above 0); else {@code CAVITATION} while σ is below inception ({@link Wear#INCEPTION}: while it
     * crackles); else {@code GAS} while its gas ratio is above half the Turpin onset (while it churns); else {@code WET GAS}
     * while a centrifugal compressor takes a wet suction (N2, {@link Wear#wetGas} above 0); else {@code WET EXHAUST} while a
     * steam turbine exhausts wet (N4, {@link Wear#wetExhaust} above 0); else empty. Its health has its meter. The words after
     * {@code GAS} belong to machines that read none of the words before them, so the order of today's words is kept.
     */
    public String status(long id) {
        if (trim(topology.active().get(id))) return trimStatus(id);
        if (failed(id)) return "FAILED";
        var screw = pumps.get(id);
        if (screw != null && screw.liquid > com.wormzjl.icheme.science.machine.VacuumPump.LIQUID_TRACE) return "LIQUID";
        if (screw != null && screw.lowFlow > 0) return "LOW FLOW";
        var s = suction(id);
        if (s.sigma() < Wear.INCEPTION) return "CAVITATION";
        if (s.ratio() > GAS_RATIO) return "GAS";
        if (screw != null && screw.wetGas > 0) return "WET GAS";
        if (screw != null && screw.wetExhaust > 0) return "WET EXHAUST";
        return "";
    }

    /** A valve trim's health at and below which its status line says {@code ERODED TRIM} (plan section 8). */
    public static final double ERODED_TRIM = .25;

    /**
     * Control valve {@code id}'s trim word (batch 2026-11-16-fouling-corrosion): {@code TRIM FAILED} at health 0 while wear
     * is on (it stands open), {@code ERODED TRIM} from {@link #ERODED_TRIM}, else empty.
     */
    public String trimStatus(long id) {
        var p = pumps.get(id);
        if (p == null) return "";
        if (p.failed && settings().enabled()) return "TRIM FAILED";
        return p.health <= ERODED_TRIM ? "ERODED TRIM" : "";
    }

    /** The gas ratio r = α/α_T above which a pump churns ({@link com.wormzjl.icheme.science.machine.GasEntrainment#churn}) and its page says {@code GAS}. */
    public static final double GAS_RATIO = com.wormzjl.icheme.science.machine.GasEntrainment.CLEAN_ONSETS;
}
