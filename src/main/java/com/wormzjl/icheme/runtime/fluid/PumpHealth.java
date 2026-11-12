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
 * The pumps' health (batch 2026-11-03-rotating-machinery W10b, build plan revision 2, owner decision D34): every pump,
 * a constructed casing or an in-line pump, keyed by its device identity (the node its mover's connection leaves). Health
 * is a fact, 1 (new) to 0 (failed); arithmetic on the server thread only:
 * <ul>
 * <li>at each commit and replay its wear rate ({@link Wear#rate}) is read from the slice's committed suction (its
 * {@link PassiveStepSolver.PipeObservation#npshRatio} and {@link PassiveStepSolver.PipeObservation#dry}) and booked over
 * the slice's online ticks ({@link #booked});</li>
 * <li>on the presentation cadence a pump not booked for a cadence (a resting island) books its last rate to now, one that
 * reached 0 fails, and the facts go to the ledger ({@link #cadence});</li>
 * <li>a failed pump is closed at dispatch by a {@link ShaftPlan.Kind#FAILED} plan, its settings kept ({@link #plans});</li>
 * <li>a repair kit is a queued event ({@link #queue}), applied at the tick drain ({@link #apply}).</li>
 * </ul>
 * A failure and a repair wake the pump's island; wear above 0 wakes nothing and changes nothing the solver reads.
 * {@code machines.wear.enabled = false} books nothing and closes nothing.
 */
public final class PumpHealth {
    /** The presentation cadence, online ticks (as the electric stores'). */
    public static final int CADENCE = ElectricStores.CADENCE;

    /** What a repair came to: the item is consumed only for {@code REPAIRED}. */
    public enum Outcome { REPAIRED, FULL, NOT_A_PUMP, NO_KIT }

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
        long bookedTick;
        boolean failed;
    }

    private final WorldTopologyLedger topology;
    private final LongFunction<Long> owner;
    private final IslandCoordinator coordinator;
    private final LongPredicate isPump;
    private final LongFunction<FlowControl> registered;
    private final Supplier<Wear.Settings> settings;
    private final Map<Long, Pump> pumps = new TreeMap<>();
    private final ArrayDeque<Repair> events = new ArrayDeque<>();
    private final Set<Long> wake = new TreeSet<>();

    /**
     * @param owner  a fluid device's island, or null ({@link PhysicalRegistry#owner})
     * @param isPump     whether a device identity is a pump that stands: a registration whose control is a liquid mover
     * @param registered a device's registered control (null for none): a repaired in-line pump switches back on only where
     *                   its registration is switched on
     */
    public PumpHealth(WorldTopologyLedger topology, LongFunction<Long> owner, IslandCoordinator coordinator, LongPredicate isPump, LongFunction<FlowControl> registered,
                      Supplier<Wear.Settings> settings) {
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

    /** Whether {@code mover} wears: a liquid mover, or a dry screw vacuum pump, which liquid wears (owner V6 B). */
    static boolean worn(FlowControl.Mover mover) {
        return mover.liquid() || mover instanceof FlowControl.Machine m && com.wormzjl.icheme.science.machine.VacuumPump.dryScrew(m.law());
    }

    /** Whether registration {@code r} is a pump: its control a liquid mover (a constructed casing or an in-line pump) or a dry screw. */
    public static boolean pump(WorldTopologyLedger.Registration r) {
        return r != null && r.device().control() instanceof FlowControl.Mover m && worn(m);
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
        for (var pipe : solved.pipes()) {
            if (!(pipe.control() instanceof FlowControl.Mover m) || !worn(m)) continue;
            long id = solved.reservoirs().get(pipe.first()).id();
            var p = pump(id);
            if (p == null) continue;
            if (rows == null) {
                rows = new HashMap<>();
                for (var row : result.observations()) rows.put(row.pipe(), row);
            }
            var row = rows.get(pipe.id());
            boolean read = row != null && m.targetVolumeFlow() > 0;
            if (!m.liquid()) {
                // A dry screw (owner V6 B): liquid above the trace wears it as running dry does, and makes its liquid noise.
                double liquid = read && Double.isFinite(row.vapourShare()) ? 1 - row.vapourShare() : 0;
                p.liquid = liquid;
                book(p, Wear.rate(Double.NaN, liquid > com.wormzjl.icheme.science.machine.VacuumPump.LIQUID_TRACE, s), start, end);
                p.crackle = com.wormzjl.icheme.science.machine.VacuumPump.liquidNoise(liquid);
                p.churn = 0;
                p.suction = Suction.NONE;
                continue;
            }
            book(p, read ? Wear.rate(row.npshRatio(), row.dry(), s) : 0, start, end);
            p.crackle = read ? (int) Math.round(255 * Wear.intensity(row.npshRatio())) : 0;
            p.churn = read ? (int) Math.round(255 * com.wormzjl.icheme.science.machine.GasEntrainment.churn(row.gasRatio())) : 0;
            p.suction = read ? new Suction(row.npshRatio() * row.npshRequired(), row.npshRequired(), row.vapourShare(), row.npshRatio(), row.gasRatio()) : Suction.NONE;
        }
    }

    /**
     * The plans of {@code graph}'s failed pumps: each closed for the slice ({@link ShaftPlan.Kind#FAILED} 0). An in-line pump
     * that stands closed as a failure closed it (switched off with its registration on, the same settings) and is no longer
     * failed is switched back on (1): an island keeps the controls its last slice solved with.
     */
    void plans(PassiveNetwork graph, Map<Long, ShaftPlan> out) {
        boolean enabled = settings().enabled();
        for (var pipe : graph.pipes()) {
            if (!(pipe.control() instanceof FlowControl.Mover m) || !worn(m)) continue;
            long id = graph.reservoirs().get(pipe.first()).id();
            var p = pumps.get(id);
            if (enabled && p != null && p.failed) out.put(id, new ShaftPlan(ShaftPlan.Kind.FAILED, 0));
            else if (m instanceof FlowControl.Pump pump && !pump.enabled() && registered.apply(id) instanceof FlowControl.Pump on && on.enabled()
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

    public boolean hasEvents() {
        return !events.isEmpty();
    }

    /** Applies the queued repairs: a worn pump takes its kit and is new again, and a failed one wakes its island. */
    public void apply() {
        long now = coordinator.now();
        while (!events.isEmpty()) {
            var r = events.poll();
            var p = pump(r.device());
            Outcome outcome;
            if (p == null) outcome = Outcome.NOT_A_PUMP;
            else if (p.health >= 1) outcome = Outcome.FULL;
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

    /** Pump {@code id}'s suction as its last committed slice read it (W10d); {@link Suction#NONE} for no read and no pump. */
    public Suction suction(long id) {
        var p = pumps.get(id);
        return p == null ? Suction.NONE : p.suction;
    }

    /**
     * Pump {@code id}'s status word on its page's message display, said once (W10d, owner decision D37): {@code FAILED} at
     * health 0, which takes precedence; else {@code CAVITATION} while σ is below inception ({@link Wear#INCEPTION}: while it
     * crackles); else {@code GAS} while its gas ratio is above half the Turpin onset (while it churns); else empty. Its
     * health has its meter.
     */
    public String status(long id) {
        if (failed(id)) return "FAILED";
        var screw = pumps.get(id);
        if (screw != null && screw.liquid > com.wormzjl.icheme.science.machine.VacuumPump.LIQUID_TRACE) return "LIQUID";
        var s = suction(id);
        if (s.sigma() < Wear.INCEPTION) return "CAVITATION";
        if (s.ratio() > GAS_RATIO) return "GAS";
        return "";
    }

    /** The gas ratio r = α/α_T above which a pump churns ({@link com.wormzjl.icheme.science.machine.GasEntrainment#churn}) and its page says {@code GAS}. */
    public static final double GAS_RATIO = com.wormzjl.icheme.science.machine.GasEntrainment.CLEAN_ONSETS;
}
