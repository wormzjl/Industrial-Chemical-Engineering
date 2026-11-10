package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.AirCooled;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.CoolingTower;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.ShaftPlan;
import com.wormzjl.icheme.science.machine.ElectricStore;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import java.util.*;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * The electric stores of a world (batch 2026-11-03-rotating-machinery W5, build plan sections 2 to 4, owner decision
 * D30): one per powered unit, keyed by its identity (reading A53): a motor's or generator's shaft unit, an air cooler's
 * process side, an induced-draft tower's basin, a fired heater's burners (their combustion-air fans, batch
 * 2026-11-09-furnace-fuel-power: booked by {@link FiredHeaters} at the fuel's commits, a store run empty puts the fire out). Arithmetic on the server thread only:
 * <ul>
 * <li>a port's FE adds to (or a generator's port takes from) its store at once: the FE protocol, no process work, nothing
 * wakes ({@link #receive}, {@link #extract});</li>
 * <li>fans book their draw at their island's commit, over the slice's online ticks at the committed fan power (reading
 * A59, {@link #booked}); a motor and a generator book at their train's step ({@link ShaftTrains});</li>
 * <li>on the presentation cadence a unit not booked for a cadence (at rest) books its last power, a stopped unit whose
 * store holds one slice of rated draw restarts, and the stores' facts go to the ledger ({@link #cadence}, readings A60, A62);</li>
 * <li>a store run empty marks its unit unpowered: state, not a fact, read at dispatch as a {@link ShaftPlan.Kind#FANS}
 * plan (reading A61), so the player's settings stay as they were.</li>
 * </ul>
 * A store from a save before W5 is unmetered (owner decision A73 option A): its unit runs free, as before, until its port
 * first receives FE. {@code machinesNeedPower = false} books nothing and stops nothing.
 */
public final class ElectricStores {
    /** The presentation cadence, online ticks: about 5 s (AGENTS.md, engine schedule). */
    public static final int CADENCE = 100;

    /** The config's two keys (reading A66). */
    public record Settings(double joulesPerFE, boolean needPower) {
        public static final Settings DEFAULT = new Settings(2.5, true);

        public Settings {
            if (!(joulesPerFE > 0) || !Double.isFinite(joulesPerFE)) throw new IllegalArgumentException("Invalid joules per FE " + joulesPerFE);
        }
    }

    /** What a store powers. */
    public enum Kind {
        MOTOR, GENERATOR, FANS, TOWER, INLINE, BURNERS, STILL;

        public boolean generator() {
            return this == GENERATOR;
        }
    }

    /**
     * A store as a page shows it (W7; read only): its content and capacity, J; its unit's electric power, W (drawn, or a
     * generator's output); FE per tick in and out over the last cadence; whether metered, whether its unit stands for want
     * of power; its status word ({@code NO POWER}, {@code UNMETERED}, or empty).
     */
    public record View(Kind kind, double joules, double capacity, double power, double inPerTick, double outPerTick, boolean metered, boolean unpowered, String status) {
    }

    /** One store: its facts (content, metered) and its engine state. */
    private static final class Store {
        final Kind kind;
        double joules, ratedDraw, power, inJoules, outJoules, inRate, outRate, tickJoules;
        boolean metered, unpowered;
        /** A commit or replay ran the store short: the unit stops at the next cadence (a replay may be a view's read, which wakes nothing). */
        boolean shortfall;
        long bookedTick, rateTick, tick = Long.MIN_VALUE;
        long offeredTick = Long.MIN_VALUE;

        Store(Kind kind, ElectricStore.Facts facts, long now) {
            this.kind = kind;
            joules = facts.joules();
            metered = facts.metered();
            bookedTick = rateTick = now;
        }

        double capacity() {
            return kind == Kind.MOTOR ? ElectricStore.MOTOR_BUFFER_SECONDS * ratedDraw : ElectricStore.capacity(ratedDraw);
        }
    }

    private final WorldTopologyLedger topology;
    private final LongFunction<Long> owner;
    private final IslandCoordinator coordinator;
    private final Supplier<Settings> settings;
    private LongPredicate supply=id->false;
    /** Told when a unit lost or regained its power: a fired heater's burners wake their radiant coil's island too. */
    private java.util.function.LongConsumer moved=id->{};
    private final Map<Long, Store> stores = new TreeMap<>();
    /** Islands to wake at the next decision point: a fan unit lost or regained its power. */
    private final Set<Long> wake = new TreeSet<>();

    /**
     * @param owner a fluid device's island, or null ({@link PhysicalRegistry#owner})
     */
    public ElectricStores(WorldTopologyLedger topology, LongFunction<Long> owner, IslandCoordinator coordinator, Supplier<Settings> settings) {
        this.topology = Objects.requireNonNull(topology);
        this.owner = Objects.requireNonNull(owner);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.settings = Objects.requireNonNull(settings);
        long now = coordinator.now();
        // The saved stores; a unit stands unpowered at load only where its metered store is empty (reading A79).
        topology.stores().forEach((id, facts) -> {
            var kind = kind(id);
            if (kind == null) return;
            var s = new Store(kind, facts, now);
            stores.put(id, s);
        });
        // Existing inline movers retain their saved custom duty and former unmetered behaviour until wired.
        for(var r:topology.active().values()) if(r.device().kind()==com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE&&r.device().control() instanceof FlowControl.Mover&&!(r.device().control() instanceof FlowControl.Machine)&&!stores.containsKey(r.device().id()))
            stores.put(r.device().id(),new Store(Kind.INLINE,ElectricStore.Facts.UNMETERED,now));
        sync();
        for (var s : stores.values()) s.unpowered = !kindGenerator(s) && s.metered && (s.kind == Kind.MOTOR || s.joules <= 0) && needPower();
    }

    private static boolean kindGenerator(Store s) {
        return s.kind.generator();
    }

    public Settings settings() {
        return settings.get();
    }

    public double joulesPerFE() {
        return settings.get().joulesPerFE();
    }

    /** Installs the listener told when a unit lost or regained its power. */
    public void moved(java.util.function.LongConsumer value){moved=Objects.requireNonNull(value);}

    /** Installs the server-thread, read-only FE source lookup used at the motor decision cadence. */
    public void supply(LongPredicate value){supply=Objects.requireNonNull(value);}

    private boolean needPower() {
        return settings.get().needPower();
    }

    // ---------------- which units have stores ----------------

    /** Whether registration {@code r} is a powered unit: an air cooler's process side, an induced-draft tower's basin. */
    public static boolean powered(WorldTopologyLedger.Registration r) {
        if(r.device().kind()==com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE&&r.device().control() instanceof FlowControl.Mover)return true;
        var heat = r.device().heat();
        if (heat != null && heat.fans()) return true;
        if (r.device().burner()) return true;
        var tower = r.spec().tower();
        return tower != null && tower.draft() == CoolingTower.Draft.INDUCED;
    }

    /** Whether batch still {@code u} is a powered unit: an electric one (batch 2026-11-09-batch-distiller, W4). */
    public static boolean powered(StillUnit u) {
        return u.heater() == com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.ELECTRIC;
    }

    /** Whether shaft unit {@code u} is a powered unit: a motor or a generator. */
    public static boolean powered(ShaftUnit u) {
        return u.kind() == ShaftNetwork.Kind.MOTOR || u.kind() == ShaftNetwork.Kind.GENERATOR;
    }

    private Kind kind(long id) {
        var still = topology.still(id);
        if (still != null) return powered(still) ? Kind.STILL : null;
        var u = topology.shaft(id);
        if (u != null) return u.kind() == ShaftNetwork.Kind.MOTOR ? Kind.MOTOR : u.kind() == ShaftNetwork.Kind.GENERATOR ? Kind.GENERATOR : null;
        var r = topology.active().get(id);
        if (r == null || !powered(r)) return null;
        return r.device().control() instanceof FlowControl.Mover ? Kind.INLINE : r.device().burner() ? Kind.BURNERS : r.device().heat() != null ? Kind.FANS : Kind.TOWER;
    }

    /** A unit's rated electric draw, W (a generator's rated output): the store's capacity and rates follow it (plan 7). */
    private double ratedDraw(long id, Kind kind) {
        return switch (kind) {
            case MOTOR -> {
                var u = topology.shaft(id);
                yield u == null ? 0 : ElectricStore.motorDraw(u.ratedPower(), u.poles(), true);
            }
            case GENERATOR -> {
                var u = topology.shaft(id);
                yield u == null ? 0 : u.ratedPower();
            }
            case INLINE -> {
                var r=topology.active().get(id);
                yield r==null?0:com.wormzjl.icheme.science.machine.InlineMachines.ratedDraw((FlowControl.Mover)r.device().control());
            }
            case FANS -> {
                var r = topology.active().get(id);
                yield r == null ? 0 : fanDraw(r.device().heat(), 1);
            }
            case TOWER -> {
                var r = topology.active().get(id);
                yield r == null ? 0 : towerDraw(r.spec().tower(), 1);
            }
            case BURNERS -> burnerDraw(topology.active().get(id), FiredHeaters.designFired(topology.active().get(id)));
            case STILL -> {
                var u = topology.still(id);
                yield u == null ? 0 : com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.ELECTRIC.drawn(u.design().rating());
            }
        };
    }

    /**
     * A fired heater's combustion-air fans' electric draw, W, at {@code fired} W of fuel ({@link com.wormzjl.icheme.science.fluid.network.Burners#fanPower}):
     * their shaft power over one 4-pole motor's efficiency at the design firing; 0 for no burners.
     */
    public static double burnerDraw(WorldTopologyLedger.Registration burners, double fired) {
        double design = FiredHeaters.designFired(burners);
        if (!(design > 0)) return 0;
        var full = com.wormzjl.icheme.science.fluid.network.Burners.fanPower(design);
        return com.wormzjl.icheme.science.fluid.network.Burners.fanPower(fired) / ElectricStore.fanEfficiency(full, 1);
    }

    /** Books a fired heater's fans' draw {@code power} W over a committed or replayed span (a read wakes nothing: a shortfall stops them at the cadence). */
    void bookedBurners(long id, double power, long start, long end) {
        var s = stores.get(id);
        if (s == null || s.kind != Kind.BURNERS) return;
        draw(s, power, start, end, false);
    }

    /** An air cooler's electric draw, W, with its fans at {@code fans} of full speed: fan power over its fan motors' efficiency (reading A70). */
    public static double fanDraw(HeatUnit heat, double fans) {
        double full = HeatSides.fanPower(heat.area(), 1);
        return HeatSides.fanPower(heat.area(), fans) / ElectricStore.fanEfficiency(full, heat.fanCount());
    }

    /** An induced-draft tower's electric draw, W, with its fans at {@code speed}: one fan motor per cell (reading A71: one store a structure). */
    public static double towerDraw(CoolingTower tower, double speed) {
        if (tower == null || tower.draft() != CoolingTower.Draft.INDUCED) return 0;
        return tower.fanPower(speed) / ElectricStore.fanEfficiency(tower.fanPower(1), tower.cells());
    }

    /**
     * Brings the stores to the units that stand: a new powered unit takes a metered, empty store (a store built from W5
     * on), a gone one's store leaves, and each takes its rating. On the cadence and at load.
     */
    private void sync() {
        var seen = new HashSet<Long>();
        for (var r : topology.active().values()) if (powered(r)) seen.add(r.device().id());
        for (var u : topology.shafts().values()) if (powered(u)) seen.add(u.id());
        // An electric batch still (batch 2026-11-09-batch-distiller, W4): its store is keyed by its pot.
        for (var u : topology.stills().values()) if (powered(u)) seen.add(u.id());
        stores.keySet().retainAll(seen);
        long now = coordinator.now();
        for (long id : seen) {
            var kind = kind(id);
            var s = stores.get(id);
            if (s == null || s.kind != kind) {
                s = new Store(kind, ElectricStore.Facts.EMPTY, now);
                stores.put(id, s);
                s.ratedDraw = ratedDraw(id, kind);
                // A new store is empty: its unit waits for one slice of power before it first runs.
                s.unpowered = !kind.generator() && needPower();
                continue;
            }
            s.ratedDraw = ratedDraw(id, kind);
        }
    }

    private Store store(long id) {
        var s = stores.get(id);
        if (s == null && kind(id) != null) {
            sync();
            s = stores.get(id);
        }
        return s;
    }

    // ---------------- the port (the FE protocol) ----------------

    /** The generators whose stores hold energy, by unit (for the push to neighbours, reading A65): a read. */
    public List<Long> charged() {
        List<Long> out = null;
        for (var e : stores.entrySet())
            if (e.getValue().kind.generator() && e.getValue().joules >= joulesPerFE()) {
                if (out == null) out = new ArrayList<>();
                out.add(e.getKey());
            }
        return out == null ? List.of() : out;
    }

    /** Whether unit {@code id}'s port gives FE (a generator) rather than taking it; null for no store. */
    public Boolean generator(long id) {
        var s = store(id);
        return s == null ? null : s.kind.generator();
    }

    /** The joules a port may move this tick, of the per-tick rate (twice the rated draw). */
    private double room(Store s) {
        long now = coordinator.now();
        if (s.tick != now) {
            s.tick = now;
            s.tickJoules = 0;
        }
        return Math.max(0, ElectricStore.perTick(s.ratedDraw) - s.tickJoules);
    }

    /**
     * FE offered to unit {@code id}'s port (receive-only for a consumer, reading A57): the store takes what its capacity and
     * per-tick rate allow, in whole FE, and the first FE it takes meters it (A73). Nothing wakes, nothing solves.
     */
    public int receive(long id, int fe, boolean simulate) {
        var s = store(id);
        if (s == null || s.kind.generator() || fe <= 0) return 0;
        // Push-only FE sources also prove their live feed when a full buffer accepts nothing. Preflight stays read-only.
        if(!simulate)s.offeredTick = coordinator.now();
        double jpf = joulesPerFE();
        double taken = Math.min(ElectricStore.accepted(s.joules, s.capacity(), fe * jpf), room(s));
        int whole = (int) Math.min(fe, Math.floor(taken / jpf + 1e-9));
        if (!simulate && whole > 0) {
            double joules = whole * jpf;
            s.joules = Math.min(s.capacity(), s.joules + joules);
            s.tickJoules += joules;
            s.inJoules += joules;
            s.metered = true;
        }
        return whole;
    }

    /** FE taken from unit {@code id}'s port (extract-only for a generator): its content within its per-tick rate, in whole FE. */
    public int extract(long id, int fe, boolean simulate) {
        var s = store(id);
        if (s == null || !s.kind.generator() || fe <= 0) return 0;
        double jpf = joulesPerFE();
        int whole = (int) Math.min(fe, Math.floor(Math.min(s.joules, room(s)) / jpf + 1e-9));
        if (!simulate && whole > 0) {
            double joules = whole * jpf;
            s.joules = Math.max(0, s.joules - joules);
            s.tickJoules += joules;
            s.outJoules += joules;
        }
        return whole;
    }

    /** Unit {@code id}'s content in whole FE, at most {@link Integer#MAX_VALUE}. */
    public int stored(long id) {
        var s = stores.get(id);
        return s == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.floor(s.joules / joulesPerFE()));
    }

    /** Unit {@code id}'s capacity in whole FE, at most {@link Integer#MAX_VALUE}. */
    public int capacity(long id) {
        var s = stores.get(id);
        return s == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.floor(s.capacity() / joulesPerFE()));
    }

    // ---------------- booking ----------------

    /** A new consumer waits for its store; existing unmetered stores retain their saved free-running behaviour. */
    public boolean powered(long id) {
        var s = stores.get(id);
        return !needPower() || s != null && !s.unpowered;
    }

    /** Whether unit {@code id} runs free: no store, an unmetered one, or a world whose machines need no power. */
    boolean free(Store s) {
        return s == null || !s.metered || !needPower();
    }

    /**
     * Books {@code power} W drawn by consumer {@code id} from its last booking to {@code to} (an online tick): what is left
     * when the store runs short, which then marks the unit unpowered. True when it ran short.
     */
    public boolean draw(long id, double power, long to) {
        var s = stores.get(id);
        if (s == null || s.kind.generator()) return false;
        return draw(s, power, s.bookedTick, to, true);
    }

    private boolean draw(Store s, double power, long from, long to, boolean mark) {
        // The last committed power stands for a cadence's booking at rest, also when the cadence already booked this span.
        s.power = power;
        if (to <= s.bookedTick) return false;
        from = Math.max(from, s.bookedTick);
        s.bookedTick = to;
        if (free(s)) return false;
        var d = ElectricStore.draw(s.joules, power, (to - from) * ElectricStore.TICK_SECONDS);
        s.joules = d.content();
        s.outJoules += d.booked();
        if (d.shortfall() && !s.unpowered) {
            if (!mark) {
                s.shortfall = true;
                return false;
            }
            s.unpowered = true;
            return true;
        }
        return false;
    }

    /**
     * A running column's held member {@code id} (W5 of batch 2026-11-10-column-failure-modes): a fan side or an in-line pump
     * books {@code power} W from its last booking to {@code to}, as its island's commit would; then whether it has power. A
     * store run short stops it here (the column's cycle is a decision point, not a read). No store: it runs free.
     */
    public boolean member(long id, double power, long to) {
        var s = store(id);
        if (s == null || s.kind.generator()) return true;
        draw(s, power, s.bookedTick, to, true);
        return powered(id);
    }

    /** Books {@code power} W given by generator {@code id} from its last booking to {@code to}: its store takes up to its capacity. */
    public void give(long id, double power, long to) {
        var s = stores.get(id);
        if (s == null || !s.kind.generator() || to <= s.bookedTick) return;
        double joules = Math.max(0, power) * (to - s.bookedTick) * ElectricStore.TICK_SECONDS;
        s.bookedTick = to;
        s.power = power;
        double taken = ElectricStore.accepted(s.joules, s.capacity(), joules);
        s.joules += taken;
        s.inJoules += taken;
    }

    /** Generator {@code id}'s electric load for the next {@code seconds}, W (reading A63): its rating, but no more than its store's room. */
    public double load(long id, double seconds) {
        var s = stores.get(id);
        return s == null ? 0 : ElectricStore.generatorLoad(s.joules, s.capacity(), s.ratedDraw, seconds);
    }

    /** Whether stopped unit {@code id}'s store holds one slice of its rated draw again (reading A62); clears its flag when so. */
    public boolean restart(long id) {
        var s = stores.get(id);
        if (s == null || !s.unpowered) return false;
        double threshold = s.kind == Kind.MOTOR ? ElectricStore.MOTOR_RESTART_SECONDS * s.ratedDraw : ElectricStore.restart(s.ratedDraw);
        if (!free(s) && (s.joules < threshold || s.kind == Kind.MOTOR && !supplied(id,s))) return false;
        s.unpowered = false;
        return true;
    }

    private boolean supplied(long id,Store s) {
        // FE producers can tick before or after the engine. One tick of ordering tolerance, no process work per tick.
        return s.offeredTick != Long.MIN_VALUE && coordinator.now() - s.offeredTick <= 1 || supply.test(id);
    }

    /** Sample the motor's live FE feed at an engine decision point, retaining all saved joules. */
    public boolean disconnect(long id) {
        var s = stores.get(id);
        if (s == null || s.kind != Kind.MOTOR || free(s) || s.unpowered || supplied(id,s)) return false;
        s.unpowered = true;
        return true;
    }

    /** Whether unit {@code id} was booked within the last cadence. */
    public boolean bookedWithin(long id, long now) {
        var s = stores.get(id);
        return s != null && now - s.bookedTick < CADENCE;
    }

    /** Each fan unit's slice input at dispatch: its fans' limit, 0 unpowered, 1 otherwise (reading A61). Reads only. */
    void plans(PassiveNetwork graph, Map<Long, ShaftPlan> out) {
        for (var pipe : graph.pipes()) {
            long moverId=graph.reservoirs().get(pipe.first()).id();var moverStore=pipe.control() instanceof FlowControl.Mover||pipe.heat() instanceof AirCooled?store(moverId):stores.get(moverId);
            if(moverStore!=null&&moverStore.kind==Kind.INLINE){
                var registration=topology.active().get(moverId);var design=registration==null?null:registration.device().control();
                boolean running=powered(moverId)&&design instanceof FlowControl.Mover mover&&mover.enabled();
                out.put(moverId,new ShaftPlan(design instanceof FlowControl.Machine?ShaftPlan.Kind.SPEED:ShaftPlan.Kind.FAILED,running?1:0));continue;
            }
            if (!(pipe.heat() instanceof AirCooled)) continue;
            long id = graph.reservoirs().get(pipe.first()).id();
            var s = stores.get(id);
            if (s != null && s.kind == Kind.FANS) out.put(id, new ShaftPlan(ShaftPlan.Kind.FANS, powered(id) ? 1 : 0));
        }
        for (var node : graph.reservoirs()) {
            var s = node.inventory().tower() == null ? null : store(node.id());
            if (s != null && s.kind == Kind.TOWER) out.put(node.id(), new ShaftPlan(ShaftPlan.Kind.FANS, powered(node.id()) ? 1 : 0));
        }
    }

    /**
     * A committed or replayed span of {@code island}: each fan unit in it books its fans' draw over the span (reading A59).
     * One that runs its store short stops at the next cadence: a replay may be a view's read, and a read wakes nothing.
     */
    void booked(long island, long start, long end, PassiveIntervalSolver.Result result) {
        if (stores.isEmpty() || end <= start) return;
        var solved = result.graph();
        var average=result.averageMassFlows();var heads=result.endpointHeads();
        for(int edge=0;edge<solved.pipes().size();edge++) {
            var pipe=solved.pipes().get(edge);var node=solved.reservoirs().get(pipe.first());var supply=node.state();
            var store=stores.get(node.id());
            if(store!=null&&store.kind==Kind.INLINE&&pipe.control() instanceof FlowControl.Mover mover) {
                double rho=supply.volume()>0?supply.mass()/supply.volume():0;
                double q=rho>0?Math.max(0,average[edge])/rho:0,shaft=0;
                if(rho>0&&mover.enabled())shaft=mover instanceof FlowControl.Machine m?m.law().shaftPower(q,m.speed(),rho,supply.pressure(),1.4):mover.power(Math.max(0,average[edge]),heads[edge],rho,supply.pressure(),1.4)/.65;
                draw(store,com.wormzjl.icheme.science.machine.InlineMachines.electricPower(shaft),start,end,false);
                continue;
            }
            if (!(pipe.heat() instanceof AirCooled cooled)) continue;
            long id = solved.reservoirs().get(pipe.first()).id();
            var s = stores.get(id);
            var r = topology.active().get(id);
            if (s == null || s.kind != Kind.FANS || r == null || r.device().heat() == null) continue;
            draw(s, fanDraw(r.device().heat(), cooled.fans()), start, end, false);
        }
        for (var node : solved.reservoirs()) {
            var tower = node.inventory().tower();
            var s = tower == null ? null : stores.get(node.id());
            if (s == null || s.kind != Kind.TOWER) continue;
            var rates = result.towers().get(node.id());
            double speed = rates == null ? s.power > 0 ? 1 : 0 : rates.fill().speed();
            draw(s, towerDraw(tower, speed), start, end, false);
        }
    }

    private void flushWakes() {
        if (wake.isEmpty()) return;
        coordinator.shaftsMoved(List.copyOf(wake));
        wake.clear();
    }

    /**
     * The presentation cadence (readings A60, A62): the stores follow the units; a fan unit not booked for a cadence (at
     * rest) books its last power to now, one that ran short stops, one whose store refilled restarts, and each wakes its
     * island; the FE rates are taken over the cadence; the facts go to the ledger. Trains book their own at their step.
     */
    public void cadence() {
        long now = coordinator.now();
        sync();
        for (var e : stores.entrySet()) {
            var s = e.getValue();
            long id = e.getKey();
            if (s.kind == Kind.FANS || s.kind == Kind.TOWER || s.kind == Kind.INLINE || s.kind == Kind.BURNERS || s.kind == Kind.STILL) {
                boolean changed = now - s.bookedTick >= CADENCE && draw(s, s.power, s.bookedTick, now, true);
                if (s.shortfall) {
                    s.shortfall = false;
                    if (!s.unpowered && needPower()) {
                        s.unpowered = true;
                        changed = true;
                    }
                }
                changed |= restart(id);
                if (!needPower() && s.unpowered) {
                    s.unpowered = false;
                    changed = true;
                }
                if (changed) {
                    Long island = owner.apply(id);
                    if (island != null) wake.add(island);
                    moved.accept(id);
                }
            }
            long span = now - s.rateTick;
            if (span > 0) {
                s.inRate = s.inJoules / span;
                s.outRate = s.outJoules / span;
                s.inJoules = s.outJoules = 0;
                s.rateTick = now;
            }
        }
        flushWakes();
        flush();
    }

    /** Hands the stores' facts to the ledger: at the cadence and before a save. */
    public void flush() {
        topology.stores(facts());
    }

    /** The stores' facts, by unit. */
    public Map<Long, ElectricStore.Facts> facts() {
        var out = new HashMap<Long, ElectricStore.Facts>();
        stores.forEach((id, s) -> out.put(id, new ElectricStore.Facts(s.joules, s.metered)));
        return out;
    }

    /** Unit {@code id}'s store as a page shows it, or null for none. */
    public View view(long id) {
        var s = stores.get(id);
        if (s == null) return null;
        double jpf = joulesPerFE();
        boolean stopped = s.unpowered && needPower();
        String status = stopped ? "NO POWER" : !s.metered && !s.kind.generator() ? "UNMETERED" : "";
        return new View(s.kind, s.joules, s.capacity(), s.power, s.inRate / jpf, s.outRate / jpf, s.metered, stopped, status);
    }

    /**
     * A fan unit's power as its page shows it (reading A68; W7, reading A86): its draw, FE per tick and store for the Power
     * group, and {@code NO POWER} or the notice {@code UNMETERED} for the display; null for none.
     */
    public FluidView.Power power(long id) {
        var v = view(id);
        if (v == null || v.kind() == Kind.MOTOR || v.kind() == Kind.GENERATOR) return null;
        String status = !v.metered() && needPower() ? "UNMETERED" : v.unpowered() ? "NO POWER" : "";
        double draw = Math.max(0, v.power());
        return new FluidView.Power(draw, draw / joulesPerFE() / 20, v.capacity() > 0 ? Math.clamp(v.joules() / v.capacity(), 0, 1) : null, status);
    }
}
