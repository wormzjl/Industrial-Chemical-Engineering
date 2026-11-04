package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.ShaftPlan;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import com.wormzjl.icheme.science.machine.SteamTurbine;
import java.util.*;

/**
 * The shaft trains of a world (batch 2026-11-03-rotating-machinery, W3; build plan sections 1, 2 and 5). It owns the
 * compiled trains and their engine state, and is the coordinator's {@link IslandCoordinator.Shafts} hook:
 * <ul>
 * <li>at an island's dispatch, each driven machine's speed share, a frame's displacement and a governor valve's target
 * ({@link #plans}: reads of what the last step decided, no solve);</li>
 * <li>at each commit and replay, the shaft power each machine took and a turbine gave ({@link #booked}: records only);</li>
 * <li>at the coordinator's decision points, one driver step per train per tick for the trains that booked
 * ({@link #decide}); a speed change past {@link ShaftTrain#SPEED_DEADBAND}, a trip, a start or a reset wakes the
 * driven islands ({@link IslandCoordinator#shaftsMoved}).</li>
 * </ul>
 * Parts change only by queued {@link Event}s (block hooks, structures, the debug command, a speed sensor), applied at the
 * tick drain ({@link #apply}); only then do trains recompile. A train is state: its key is its lowest part's identity.
 */
public final class ShaftTrains implements IslandCoordinator.Shafts {
    /** The most queued shaft events; a full queue drops the newest (a command placing a whole field of shafts). */
    public static final int MAXIMUM_EVENTS = 65536;
    /** The heat capacity ratio of steam for a governor's first target, before the turbine has booked (build plan case 2). */
    static final double STEAM_K = 1.3;
    /** How many pipes a governor binding walks upstream of a turbine's inlet at most. */
    static final int GOVERNOR_WALK = 64;

    /** A queued change to the parts. */
    public sealed interface Event permits Put, Remove, Configure, Reset, Governor, CreateInput {
    }

    /**
     * What a Create coupling at {@code cell} reads of its Create network (W6b, D40): its |rpm| (0 at rest or overstressed)
     * and the stress it may take, su: the network's spare stress plus what the coupling draws already. Queued by the
     * coupling's block entity when Create's speed or spare moves past {@link com.wormzjl.icheme.science.machine.CreateCoupling#moved};
     * engine state, never saved (reading A147).
     */
    public record CreateInput(PhysicalFluidTopology.Position cell, double rpm, double stress) implements Event {
        public CreateInput {
            Objects.requireNonNull(cell);
            if (!(rpm >= 0) || !Double.isFinite(rpm) || !Double.isFinite(stress)) throw new IllegalArgumentException("Invalid Create input");
        }
    }

    /** A part at {@code position}, built by {@code make} from the identity it keeps (a part of the same kind already there) or takes. */
    public record Put(PhysicalFluidTopology.Position position, ShaftNetwork.Kind kind, java.util.function.LongFunction<ShaftUnit> make) implements Event {
        public Put {
            Objects.requireNonNull(position);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(make);
        }
    }

    /** The part at {@code position} leaves. */
    public record Remove(PhysicalFluidTopology.Position position) implements Event {
        public Remove {
            Objects.requireNonNull(position);
        }
    }

    /** New settings for unit {@code unit}: start, stop, mode, set point, power limit, a gearbox's ratio. */
    public record Configure(long unit, ShaftUnit.Settings settings) implements Event {
        public Configure {
            Objects.requireNonNull(settings);
        }
    }

    /** The player resets unit {@code unit}'s latched trip. */
    public record Reset(long unit) implements Event {
    }

    /** A speed sensor installed on ({@code sensor}) or removed from ({@code null}) the turbine registered as {@code turbine}. */
    public record Governor(long turbine, UUID sensor) implements Event {
    }

    /** What the trains need of the world: a registry edit of a valve's control, a turbine's speed sensor, a shaft block's drawn size. */
    public interface Host {
        void edit(long device, FlowControl.ControlValve control);

        UUID speedSensor(long turbine);

        void resize(PhysicalFluidTopology.Position shaft, int pixels);

        /** The Create coupling at {@code coupling} draws {@code impact} su per rpm on its Create network from now (W6b). */
        default void stress(PhysicalFluidTopology.Position coupling, double impact) {
        }

        Host NONE = new Host() {
            public void edit(long device, FlowControl.ControlValve control) {
            }

            public UUID speedSensor(long turbine) {
                return null;
            }

            public void resize(PhysicalFluidTopology.Position shaft, int pixels) {
            }
        };
    }

    /**
     * A part as a page shows it (W7; read only): its status word, its train's driver speed (rpm) and its own (rpm), its speed
     * share, the train's demand (W), the shaft power it gives or takes (W), a motor's electric draw or a generator's output
     * (W) and FE per tick, its settings and trip ({@code NO_POWER} too, which is never saved), and a motor's or generator's
     * electric store (W5, reading A67: content, capacity, FE per tick in and out, {@code NO POWER}, {@code UNMETERED}), or null.
     */
    public record View(long unit, ShaftNetwork.Kind kind, String status, double driverSpeed, double speed, double share, double demand, double shaftPower,
                       double electricPower, double energyPerTick, ShaftUnit.Settings settings, ShaftTrain.Trip trip, ElectricStores.View electric) {
    }

    /** One train's engine state. */
    private static final class Run {
        final ShaftNetwork.Train train;
        /** Each driven member's shaft power at its last booking, W. */
        final Map<Long, Double> power = new HashMap<>();
        double available, steamFlow, inlet = Double.NaN, exhaust = Double.NaN, specificWork = Double.NaN, drop = Double.NaN, efficiency = Double.NaN, measured = Double.NaN, target = Double.NaN;
        boolean fresh = true, booked;
        long stepped = Long.MIN_VALUE;
        String status = "RUNNING";
        double electric, governorPower;
        /** The last step's trip, {@code NO_POWER} included (it is not saved on the unit). */
        ShaftTrain.Trip trip = ShaftTrain.Trip.NONE;
        /** Each generator's electric output decided at the last step, W, and whether it stood outside its speed window. */
        final Map<Long, Double> output = new HashMap<>();
        final Set<Long> offSpeed = new HashSet<>();

        Run(ShaftNetwork.Train train) {
            this.train = train;
        }
    }

    private final WorldTopologyLedger topology;
    private final java.util.function.LongFunction<Long> owner;
    private final IslandCoordinator coordinator;
    private Host host = Host.NONE;
    /** The electric stores (W5): a motor's draw and a generator's output are booked at the train's step. */
    private ElectricStores electric;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final Map<Long, Run> runs = new TreeMap<>();
    /** Unit identity to its train's key. */
    private final Map<Long, Long> trainOf = new HashMap<>();
    /** A casing's or turbine's fluid device to its unit. */
    private final Map<Long, Long> unitOfDevice = new HashMap<>();
    /** A governor valve's device to its turbine's unit, and back. */
    private final Map<Long, Long> turbineOfValve = new HashMap<>(), valveOfTurbine = new HashMap<>();
    /** The speed share each driven device was last sent, and each governor valve's mass target, kg/s. */
    private final Map<Long, Double> shares = new HashMap<>(), governorTargets = new HashMap<>(), woken = new HashMap<>();
    /** A reciprocating frame's displacement at its design speed, m³/s, from its registration. */
    private final Map<Long, Double> displacements = new HashMap<>();
    /** Each shaft block's drawn size as last set. */
    private final Map<Long, Integer> sizes = new HashMap<>();
    /** Statuses a governor binding left on a turbine ({@code NO GOVERNOR VALVE}). */
    private final Map<Long, String> bindings = new HashMap<>();
    /** Each Create coupling's last input by its cell (W6b): engine state, rebuilt from the block entity's next report. */
    private final Map<PhysicalFluidTopology.Position, CreateInput> create = new HashMap<>();
    /** The stress impact last sent to each coupling unit, su per rpm. */
    private final Map<Long, Double> impacts = new HashMap<>();
    /** Stress units per FE per tick (config {@code create.stressPerFE}). */
    private java.util.function.DoubleSupplier stressPerFE = () -> com.wormzjl.icheme.science.machine.CreateCoupling.STRESS_PER_FE;

    /**
     * @param owner a fluid device's island, or null ({@link PhysicalRegistry#owner})
     */
    public ShaftTrains(WorldTopologyLedger topology, java.util.function.LongFunction<Long> owner, IslandCoordinator coordinator) {
        this.topology = Objects.requireNonNull(topology);
        this.owner = Objects.requireNonNull(owner);
        this.coordinator = Objects.requireNonNull(coordinator);
    }

    /** Installs the electric stores (W5). */
    public void electric(ElectricStores value) {
        electric = Objects.requireNonNull(value);
    }

    /** The electric stores, or null before they are installed. */
    public ElectricStores electric() {
        return electric;
    }

    /** The pumps' health (W10b): books at the islands' commits, closes a failed pump at dispatch. */
    private PumpHealth health;

    /** Installs the pumps' health (W10b). */
    public void health(PumpHealth value) {
        health = Objects.requireNonNull(value);
    }

    /** Installs the Create stress rate (W6b): stress units one FE per tick trades for. */
    public void stressPerFE(java.util.function.DoubleSupplier value) {
        stressPerFE = Objects.requireNonNull(value);
    }

    /** Watts per stress unit: {@code 20 × J per FE / su per FE} (D7). */
    public double wattsPerStress() {
        double jpf = electric == null ? ElectricStores.Settings.DEFAULT.joulesPerFE() : electric.joulesPerFE();
        return com.wormzjl.icheme.science.machine.CreateCoupling.wattsPerStress(jpf, stressPerFE.getAsDouble());
    }

    /**
     * Queues the removal of every part of {@code kind} (W6b): a world opened without Create lost its couplings' blocks,
     * and a coupling left as a part would stand its train as a second driver (reading A148).
     */
    public void forget(ShaftNetwork.Kind kind) {
        for (var u : topology.shafts().values()) if (u.kind() == kind) queue(new Remove(u.position()));
    }

    /** Installs the world's side, then compiles the saved parts. */
    public void host(Host value) {
        host = Objects.requireNonNull(value);
        compile();
    }

    /** Queues {@code event}; it applies at the next tick drain. */
    public void queue(Event event) {
        Objects.requireNonNull(event);
        if (events.size() < MAXIMUM_EVENTS) events.addLast(event);
    }

    public boolean hasEvents() {
        return !events.isEmpty();
    }

    /** Applies the queued events, then recompiles and wakes what moved. Called at the tick drain, after the topology's own events. */
    public void apply() {
        if (events.isEmpty()) return;
        boolean recompile = false, resize = false;
        var governors = new ArrayList<Governor>();
        // The parts by cell, kept while the batch applies (a command placing a field of shafts stays linear).
        var index = new HashMap<PhysicalFluidTopology.Position, ShaftUnit>();
        for (var u : topology.shafts().values()) index.put(u.position(), u);
        while (!events.isEmpty()) {
            var event = events.pollFirst();
            switch (event) {
                case Put put -> recompile |= put(put, index);
                case Remove remove -> {
                    create.remove(remove.position());
                    var old = index.remove(remove.position());
                    if (old != null) {
                        topology.removeShaft(old.id());
                        recompile = true;
                    }
                }
                case Configure configure -> {
                    var u = topology.shaft(configure.unit());
                    if (u == null || u.settings().equals(configure.settings())) continue;
                    var next = u.withSettings(configure.settings());
                    // A start turns the driver to its set speed at once (quasi-steady, no run-up); a stop stands it.
                    if (u.kind().driver() && u.settings().running() != configure.settings().running())
                        next = next.withState(new ShaftUnit.State(configure.settings().running() && u.trip() == ShaftTrain.Trip.NONE ? driver(next, false).setSpeed() : 0, Double.NaN, Double.NaN));
                    topology.shaft(next);
                    recompile |= u.kind() == ShaftNetwork.Kind.GEARBOX;
                    fresh(u.id());
                }
                case Reset reset -> {
                    var u = topology.shaft(reset.unit());
                    if (u == null || u.trip() == ShaftTrain.Trip.NONE) continue;
                    var cleared = u.withTrip(ShaftTrain.Trip.NONE);
                    topology.shaft(cleared.withState(new ShaftUnit.State(cleared.settings().running() ? driver(cleared, false).setSpeed() : 0, Double.NaN, Double.NaN)));
                    fresh(u.id());
                }
                case Governor governor -> governors.add(governor);
                case CreateInput input -> {
                    // Create's speed or spare moved (W6b): the coupling's train steps at the next decision point. Its
                    // impact is sent again after it, in case the block entity reloaded since the last one (reading A147).
                    create.put(input.cell(), input);
                    var u = index.get(input.cell());
                    if (u == null || u.kind() != ShaftNetwork.Kind.CREATE_COUPLING) continue;
                    impacts.remove(u.id());
                    fresh(u.id());
                    resize = true;
                }
            }
        }
        if (recompile) compile();
        else if (resize) resize();
        for (var g : governors) bind(g);
        if (!governors.isEmpty()) findGovernors();
        publish();
    }

    private boolean put(Put put, Map<PhysicalFluidTopology.Position, ShaftUnit> index) {
        var old = index.get(put.position());
        long id;
        if (old != null && old.kind() == put.kind()) id = old.id();
        else {
            if (old != null) topology.removeShaft(old.id());
            id = topology.reserve(1);
        }
        var unit = put.make().apply(id);
        if (unit.id() != id || !unit.position().equals(put.position())) throw new IllegalStateException("A shaft part built at another identity or cell");
        if (old != null && old.kind() == put.kind()) unit = unit.withSettings(old.settings()).withTrip(old.trip()).withState(old.state());
        if (unit.equals(old)) return false;
        topology.shaft(unit);
        index.put(unit.position(), unit);
        return true;
    }

    private ShaftUnit at(PhysicalFluidTopology.Position position) {
        for (var u : topology.shafts().values()) if (u.position().equals(position)) return u;
        return null;
    }

    private void fresh(long unit) {
        var key = trainOf.get(unit);
        var run = key == null ? null : runs.get(key);
        if (run != null) run.fresh = true;
    }

    /** Compiles every part into trains; a train with the same key and parts keeps its state. */
    private void compile() {
        var units = topology.shafts();
        var compiled = ShaftNetwork.compile(units.values().stream().map(ShaftUnit::part).toList());
        var before = new HashMap<>(runs);
        runs.clear();
        trainOf.clear();
        unitOfDevice.clear();
        displacements.clear();
        for (var train : compiled) {
            var old = before.get(train.key());
            var run = old != null && old.train.equals(train) ? old : new Run(train);
            runs.put(train.key(), run);
            for (long id : train.parts()) trainOf.put(id, train.key());
        }
        for (var u : units.values()) if (u.fluid() != 0) unitOfDevice.put(u.fluid(), u.id());
        impacts.keySet().retainAll(units.keySet());
        // A driver that has no saved speed starts at its set speed.
        for (var run : runs.values()) {
            var d = run.train.driver() == 0 ? null : units.get(run.train.driver());
            if (d != null && d.state() == null)
                topology.shaft(d.withState(new ShaftUnit.State(d.settings().running() && d.trip() == ShaftTrain.Trip.NONE ? driver(d, false).setSpeed() : 0, Double.NaN, Double.NaN)));
        }
        findGovernors();
        resize();
    }

    /** The governor valves: each turbine's {@code SHAFT_SPEED} valve that its speed sensor works. */
    private void findGovernors() {
        turbineOfValve.clear();
        valveOfTurbine.clear();
        var bySensor = new HashMap<UUID, Long>();
        for (var u : topology.shafts().values())
            if (u.kind() == ShaftNetwork.Kind.STEAM_TURBINE) {
                var sensor = host.speedSensor(u.fluid());
                if (sensor != null) bySensor.put(sensor, u.id());
            }
        if (bySensor.isEmpty()) return;
        for (var r : topology.latest().values())
            if (r.device().control() instanceof FlowControl.ControlValve v && v.source() == FlowControl.ValveSource.SHAFT_SPEED && v.sensor() != null) {
                var turbine = bySensor.get(v.sensor());
                if (turbine != null) {
                    turbineOfValve.put(r.device().id(), turbine);
                    valveOfTurbine.put(turbine, r.device().id());
                }
            }
    }

    /**
     * A speed sensor installed or removed (build plan section 3, reading A25): installed, the nearest control valve upstream
     * of the turbine's inlet, through unbranched pipes in its island, takes source {@code SHAFT_SPEED} and the sensor; a
     * valve holding its own sensor refuses ({@code NO GOVERNOR VALVE}). Removed, the bound valve returns to source
     * {@code NONE} and keeps its last target.
     */
    private void bind(Governor g) {
        Long unit = unitOfDevice.get(g.turbine());
        // Unbind whatever valve this turbine's old sensor worked.
        Long previous = unit == null ? null : valveOfTurbine.get(unit);
        if (previous != null) {
            var r = topology.latest().get(previous);
            if (r != null && r.device().control() instanceof FlowControl.ControlValve v && v.source() == FlowControl.ValveSource.SHAFT_SPEED
                    && (g.sensor() == null || !g.sensor().equals(v.sensor())))
                host.edit(previous, new FlowControl.ControlValve(FlowControl.ValveSource.NONE, v.targetPressure(), v.massTarget(), v.volumeTarget(), null, Math.addExact(v.revision(), 1)));
        }
        if (unit != null) bindings.remove(unit);
        if (g.sensor() == null || unit == null) return;
        long valve = governorValve(g.turbine());
        var r = valve == 0 ? null : topology.latest().get(valve);
        if (r == null || !(r.device().control() instanceof FlowControl.ControlValve v) || v.sensor() != null && !(v.source() == FlowControl.ValveSource.SHAFT_SPEED && g.sensor().equals(v.sensor()))) {
            bindings.put(unit, "NO GOVERNOR VALVE");
            return;
        }
        if (v.source() == FlowControl.ValveSource.SHAFT_SPEED && g.sensor().equals(v.sensor())) return;
        host.edit(valve, new FlowControl.ControlValve(FlowControl.ValveSource.SHAFT_SPEED, v.targetPressure(), v.massTarget(), v.volumeTarget(), g.sensor(), Math.addExact(v.revision(), 1)));
    }

    /** The nearest valve connection upstream of turbine {@code turbine}'s inlet in its island, through unbranched pipes; 0 for none. */
    private long governorValve(long turbine) {
        Long island = owner.apply(turbine);
        if (island == null) return 0;
        var graph = coordinator.presentation(island).graph();
        return upstreamValve(graph, turbine);
    }

    /** {@link #governorValve} on {@code graph}: the walk, Minecraft-free (tested by ShaftTrainsTest). */
    static long upstreamValve(PassiveNetwork graph, long turbine) {
        int node = -1;
        PassiveNetwork.Pipe from = null;
        for (var p : graph.pipes())
            if (p.control() instanceof FlowControl.Turbine && graph.reservoirs().get(p.first()).id() == turbine) {
                node = p.first();
                from = p;
            }
        if (from == null) return 0;
        for (int step = 0; step < GOVERNOR_WALK; step++) {
            PassiveNetwork.Pipe next = null;
            int count = 0;
            for (var p : graph.pipes())
                if (p != from && (p.first() == node || p.second() == node)) {
                    next = p;
                    count++;
                }
            if (count != 1) return 0;
            if (next.control() instanceof FlowControl.ControlValve) return graph.reservoirs().get(next.first()).id();
            node = next.first() == node ? next.second() : next.first();
            from = next;
        }
        return 0;
    }

    /** Sets each shaft block's drawn size from its train's rated torque there (reading A35): the driver's rating at the shaft's speed. */
    private void resize() {
        var units = topology.shafts();
        sizes.keySet().retainAll(units.keySet());
        for (var run : runs.values()) {
            var d = run.train.driver() == 0 ? null : units.get(run.train.driver());
            double rated = d == null ? 0 : driver(d, false).limit(), rpm = d == null ? 0 : d.designSpeed();
            if (d != null && d.kind() == ShaftNetwork.Kind.CREATIVE_MOTOR || d != null && d.kind() == ShaftNetwork.Kind.CREATE_COUPLING) rpm = driver(d, false).setSpeed();
            for (var e : run.train.shafts().entrySet()) {
                double speed = rpm * e.getValue();
                int pixels = d == null || speed <= 0 ? com.wormzjl.icheme.world.level.block.ShaftBlock.DEFAULT_SIZE
                        : com.wormzjl.icheme.science.equipment.ProcessUnit.shaftPixels(com.wormzjl.icheme.science.equipment.ProcessUnit.torque(rated / 1000, speed));
                var u = units.get(e.getKey());
                if (u != null && !Objects.equals(sizes.put(u.id(), pixels), pixels)) host.resize(u.position(), pixels);
            }
        }
    }

    // ---------------- the coordinator's hook ----------------

    @Override
    public Map<Long, ShaftPlan> plans(long island, PassiveNetwork graph) {
        Map<Long, ShaftPlan> plans = null;
        // The fan units' power (W5): a fan limit for every air cooler and induced tower with a store.
        if (electric != null) {
            var fans = new HashMap<Long, ShaftPlan>();
            electric.plans(graph, fans);
            if (!fans.isEmpty()) plans = fans;
        }
        if (shares.isEmpty() && governorTargets.isEmpty()) return failures(plans, graph);
        for (var pipe : graph.pipes()) {
            long id = graph.reservoirs().get(pipe.first()).id();
            ShaftPlan plan = null;
            Double share = shares.get(id);
            if (share != null) plan = switch (pipe.control()) {
                case FlowControl.Machine m -> new ShaftPlan(ShaftPlan.Kind.SPEED, share);
                case FlowControl.Compressor c -> new ShaftPlan(ShaftPlan.Kind.DISPLACEMENT, displacement(id, c) * share);
                default -> null;
            };
            Double target = governorTargets.get(id);
            if (target != null && pipe.control() instanceof FlowControl.ControlValve v && v.source() == FlowControl.ValveSource.SHAFT_SPEED)
                plan = new ShaftPlan(ShaftPlan.Kind.GOVERNOR, target);
            if (plan == null) continue;
            if (plans == null) plans = new HashMap<>();
            plans.put(id, plan);
        }
        return failures(plans, graph);
    }

    /** {@code plans} with each failed pump of {@code graph} closed (W10b): its {@code FAILED} plan stands in for its speed. */
    private Map<Long, ShaftPlan> failures(Map<Long, ShaftPlan> plans, PassiveNetwork graph) {
        if (health != null) {
            var failed = new HashMap<Long, ShaftPlan>();
            health.plans(graph, failed);
            if (!failed.isEmpty()) {
                if (plans == null) plans = new HashMap<>();
                plans.putAll(failed);
            }
        }
        return plans == null ? Map.of() : plans;
    }

    /** A frame's displacement at its design speed: its registered control's flow (a dispatch's graph carries the planned one). */
    private double displacement(long device, FlowControl.Compressor dispatched) {
        return displacements.computeIfAbsent(device, d -> {
            var r = topology.latest().get(d);
            return r != null && r.device().control() instanceof FlowControl.Compressor c ? c.requestedVolumeFlow() : dispatched.requestedVolumeFlow();
        });
    }

    @Override
    public void booked(long island, PassiveNetwork graph, long start, long end, PassiveIntervalSolver.Result result) {
        if (result.advancedSeconds() <= 0) return;
        // The fans' draw over the span (W5, reading A59).
        if (electric != null) electric.booked(island, start, end, result);
        // The pumps' wear over the span (W10b).
        if (health != null) health.booked(island, start, end, result);
        if (unitOfDevice.isEmpty()) return;
        var solved = result.graph();
        var flows = result.endpointFlows();
        var heads = result.endpointHeads();
        var average = result.averageMassFlows();
        for (int e = 0; e < solved.pipes().size(); e++) {
            var pipe = solved.pipes().get(e);
            var node = solved.reservoirs().get(pipe.first());
            Long unit = unitOfDevice.get(node.id());
            if (unit == null) continue;
            Long key = trainOf.get(unit);
            var run = key == null ? null : runs.get(key);
            if (run == null) continue;
            var suction = node.state();
            double density = suction.volume() > 0 ? suction.mass() / suction.volume() : 0;
            switch (pipe.control()) {
                case FlowControl.Machine m -> {
                    double q = density > 0 ? Math.max(0, flows[e]) / density : 0;
                    run.power.put(unit, m.speed() > 0 && density > 0 ? m.law().shaftPower(q, m.speed(), density, suction.pressure(), 1.4) : 0);
                    if (first(run, unit)) {
                        run.measured = q;
                        run.target = m.setFlow() > 0 ? m.setFlow() : m.setPressure() > 0 ? m.setPressure() : Double.NaN;
                        if (m.setFlow() <= 0 && m.setPressure() > 0) run.measured = solved.reservoirs().get(pipe.second()).state().pressure();
                    }
                }
                case FlowControl.Compressor c -> run.power.put(unit, density > 0 ? c.power(Math.max(0, flows[e]), heads[e], density, suction.pressure(), 1.4) : 0);
                case FlowControl.Turbine t -> {
                    double work = 0;
                    for (var b : result.boundaries())
                        if (b.kind() == ConservativeTransport.BoundaryTransfer.Kind.SHAFT && b.nodeId() == pipe.id()) work += b.totalEnergyJoule();
                    run.available = Math.max(0, -work / result.advancedSeconds());
                    run.steamFlow = Math.max(0, average[e]);
                    run.inlet = suction.pressure();
                    run.exhaust = solved.reservoirs().get(pipe.second()).state().pressure();
                    run.specificWork = run.steamFlow > 0 ? run.available / run.steamFlow : Double.NaN;
                    run.efficiency = t.design().efficiency();
                    run.drop = density > 0 ? SteamTurbine.isentropicDrop(suction.pressure(), density, solved.reservoirs().get(pipe.second()).state().pressure(), STEAM_K) : Double.NaN;
                }
                default -> {
                    continue;
                }
            }
            run.fresh = true;
            run.booked = true;
        }
    }

    /** Whether {@code unit} is its train's first driven member: the one a regulating driver reads (reading A31). */
    private static boolean first(Run run, long unit) {
        return !run.train.driven().isEmpty() && run.train.driven().getFirst().id() == unit;
    }

    @Override
    public void decide() {
        long now = coordinator.now();
        boolean moved = false;
        for (var run : runs.values()) {
            if (!run.fresh || run.stepped == now) continue;
            run.fresh = false;
            run.stepped = now;
            moved |= step(run);
        }
        if (moved) publish();
    }

    /** One driver step; true when the train's speed or trip changed. */
    private boolean step(Run run) {
        var train = run.train;
        if (train.fault() != ShaftNetwork.Fault.NONE) {
            run.status = train.fault().name().replace('_', ' ');
            // A coupling on a standing train draws nothing from its Create network (W6b).
            for (long id : train.parts()) {
                var u = topology.shaft(id);
                if (u != null && u.kind() == ShaftNetwork.Kind.CREATE_COUPLING) impact(u, 0);
            }
            return false;
        }
        var d = topology.shaft(train.driver());
        if (d == null) return false;
        long now = coordinator.now();
        var state = d.state() == null ? new ShaftUnit.State(0, Double.NaN, Double.NaN) : d.state();
        // A generator (W5) books its output since its last step and takes the load its store has room for.
        generators(run, state.speed(), now);
        double demand = 0;
        for (var m : train.driven()) demand += run.power.getOrDefault(m.id(), 0.0) / m.efficiency();
        Long valve = valveOfTurbine.get(d.id());
        double sent = valve == null ? 0 : governorTargets.getOrDefault(valve, 0.0);
        boolean steamShort = valve != null && sent > 0 && run.steamFlow < .99 * sent;
        var measure = new ShaftTrain.Measure(demand, run.available, steamShort, run.measured, run.target, state.previousSpeed(), state.previousMeasured());
        // A motor runs while its store has energy (W5): its draw is booked at this step, from its last booking to now, at the
        // power this step decided (reading A59); one that runs short stops here (NO POWER) and restarts on the cadence.
        boolean motor = d.kind() == ShaftNetwork.Kind.MOTOR && electric != null;
        var driver = driver(d, valve != null, !motor || electric.powered(d.id()));
        var step = ShaftTrain.step(driver, state.speed(), measure);
        if (motor && electric.draw(d.id(), step.electricPower(), now)) step = ShaftTrain.step(driver(d, valve != null, false), state.speed(), measure);
        run.trip = step.trip();
        run.status = step.status();
        if (d.kind() == ShaftNetwork.Kind.STEAM_TURBINE && bindings.containsKey(d.id()) && step.trip() == ShaftTrain.Trip.NONE) run.status = run.status + " / " + bindings.get(d.id());
        run.electric = step.electricPower();
        run.governorPower = step.governorPower();
        // A coupling draws the stress of the power its train takes, at most what its network spares (W6b, D40).
        if (driver.drive() == ShaftTrain.Drive.CREATE)
            impact(d, step.speed() > 0 ? com.wormzjl.icheme.science.machine.CreateCoupling.impact(demand, driver.limit(), driver.setSpeed(), wattsPerStress()) : 0);
        if (valve != null) {
            double w = run.specificWork > 0 ? run.specificWork : run.efficiency > 0 && run.drop > 0 ? run.efficiency * run.drop : Double.NaN;
            double target = w > 0 ? step.governorPower() / w : 0;
            if (Double.isFinite(target)) governorTargets.put(valve, Math.min(target, FlowControl.Mover.MAXIMUM_VOLUME_FLOW * 1000));
        }
        // Only a protection trip is the unit's (it waits for the reset); NO POWER clears itself and is never saved.
        var trip = step.trip().latched() ? step.trip() : ShaftTrain.Trip.NONE;
        boolean changed = Math.abs(step.speed() - state.speed()) > ShaftTrain.SPEED_DEADBAND * Math.max(d.designSpeed(), 1) || trip != d.trip();
        var next = d.withTrip(trip).withState(new ShaftUnit.State(step.speed(), state.speed(), run.measured));
        if (changed) topology.shaft(next);
        return changed || valve != null;
    }

    /**
     * The train's generators (W5, readings A63, A64): each books the output its last step decided, from its last booking to
     * {@code now}, then, within 5 % of its rated speed, takes as its load what fills its store's room over the next cadence
     * (its rating at most), else no load ({@code OFF SPEED}); its shaft power is that over its efficiency.
     */
    private void generators(Run run, double driverSpeed, long now) {
        for (var m : run.train.driven()) {
            var g = topology.shaft(m.id());
            if (g == null || g.kind() != ShaftNetwork.Kind.GENERATOR) continue;
            if (electric != null) electric.give(g.id(), run.output.getOrDefault(g.id(), 0.0), now);
            boolean on = com.wormzjl.icheme.science.machine.ElectricStore.onSpeed(driverSpeed * m.ratio(), g.designSpeed());
            // W7: a generator switched off takes no load; its output limit (0: none) caps the load its store has room for.
            var s = g.settings();
            double load = on && s.running() && electric != null ? electric.load(g.id(), ElectricStores.CADENCE * com.wormzjl.icheme.science.machine.ElectricStore.TICK_SECONDS) : 0;
            if (s.powerLimit() > 0) load = Math.min(load, s.powerLimit());
            if (on) run.offSpeed.remove(g.id());
            else run.offSpeed.add(g.id());
            run.output.put(g.id(), load);
            run.power.put(g.id(), load / com.wormzjl.icheme.science.machine.ElectricStore.generatorEfficiency(g.ratedPower(), g.poles()));
        }
    }

    /**
     * The presentation cadence (W5, readings A60, A62): a stopped motor whose store holds one slice of its rated draw
     * restarts; a motor not stepped for a cadence (its islands at rest) books its last draw to now, and stops when that runs
     * its store short; a train with a generator steps again, for its generators' output and load. Each marks its train for
     * a step at the next decision point; nothing here solves.
     */
    public void cadence() {
        if (electric == null) return;
        long now = coordinator.now();
        for (var run : runs.values()) {
            if (run.train.fault() != ShaftNetwork.Fault.NONE) continue;
            var d = topology.shaft(run.train.driver());
            if (d == null) continue;
            if (d.kind() == ShaftNetwork.Kind.MOTOR) {
                if (electric.restart(d.id())) run.fresh = true;
                else if (!electric.bookedWithin(d.id(), now) && electric.draw(d.id(), run.electric, now)) run.fresh = true;
            }
            for (var m : run.train.driven()) {
                var g = topology.shaft(m.id());
                if (g != null && g.kind() == ShaftNetwork.Kind.GENERATOR && !electric.bookedWithin(g.id(), now)) run.fresh = true;
            }
        }
    }

    /** The step's driver for unit {@code d}. */
    private ShaftTrain.Driver driver(ShaftUnit d, boolean governed) {
        return driver(d, governed, true);
    }

    /** The step's driver for unit {@code d}, with power ({@code powered}) or without. */
    private ShaftTrain.Driver driver(ShaftUnit d, boolean governed, boolean powered) {
        var drive = d.drive(d.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE);
        if (drive == null) drive = ShaftTrain.Drive.CREATIVE;
        double design = d.designSpeed() > 0 ? d.designSpeed() : ShaftTrain.CREATIVE_SPEED;
        var s = d.settings();
        if (drive == ShaftTrain.Drive.CREATE) {
            // A Create coupling (W6b, D40): Create's |rpm| and the power of the stress it may take; no settings of its own.
            var in = create.get(d.position());
            double rpm = in == null ? 0 : in.rpm(), limit = in == null ? 0 : Math.max(0, in.stress()) * wattsPerStress();
            return new ShaftTrain.Driver(drive, 0, d.designSpeed() > 0 ? d.designSpeed() : com.wormzjl.icheme.science.machine.CreateCoupling.DESIGN_SPEED, 0, true,
                    ShaftTrain.Mode.SPEED, rpm, limit, ShaftTrain.Trip.NONE, false, true);
        }
        return new ShaftTrain.Driver(drive, d.ratedPower(), design, d.poles(), s.running(), s.mode(), s.setPoint(), s.powerLimit(), d.trip(), governed, powered);
    }

    /**
     * Sends coupling {@code u} its stress impact, su per rpm, when it moved past {@link com.wormzjl.icheme.science.machine.CreateCoupling#impactMoved}
     * (W6b): at the train's step, the engine's decision point, never on Create's tick.
     */
    private void impact(ShaftUnit u, double impact) {
        Double sent = impacts.get(u.id());
        if (sent != null && !com.wormzjl.icheme.science.machine.CreateCoupling.impactMoved(sent, impact)) return;
        impacts.put(u.id(), impact);
        host.stress(u.position(), impact);
    }

    /** Sends each driven device its new share, and wakes the islands whose share or governor target moved. */
    private void publish() {
        var units = topology.shafts();
        var next = new HashMap<Long, Double>();
        for (var u : units.values()) {
            if (u.kind() != ShaftNetwork.Kind.CASING || u.fluid() == 0) continue;
            next.put(u.fluid(), share(u, units));
        }
        var wake = new TreeSet<Long>();
        for (var e : next.entrySet()) {
            Double old = shares.get(e.getKey());
            if (old == null || Math.abs(old - e.getValue()) > ShaftTrain.SPEED_DEADBAND || (old == 0) != (e.getValue() == 0)) owners(e.getKey(), wake);
        }
        for (long gone : shares.keySet()) if (!next.containsKey(gone)) owners(gone, wake);
        shares.clear();
        shares.putAll(next);
        governorTargets.keySet().retainAll(turbineOfValve.keySet());
        woken.keySet().retainAll(governorTargets.keySet());
        for (var e : governorTargets.entrySet()) {
            // A governor target outside the valve's deadband of the one its island was last woken for wakes it (reading A26).
            Double old = woken.get(e.getKey());
            if (old != null && !outsideDeadband(old, e.getValue())) continue;
            woken.put(e.getKey(), e.getValue());
            owners(e.getKey(), wake);
        }
        if (!wake.isEmpty()) coordinator.shaftsMoved(wake);
    }

    private void owners(long device, Set<Long> wake) {
        Long island = owner.apply(device);
        if (island != null) wake.add(island);
    }

    /** Casing {@code u}'s speed share: its train's driver speed times its ratio over its design speed; 0 without a working train. */
    private double share(ShaftUnit u, Map<Long, ShaftUnit> units) {
        Long key = trainOf.get(u.id());
        var run = key == null ? null : runs.get(key);
        if (run == null || run.train.fault() != ShaftNetwork.Fault.NONE || u.designSpeed() <= 0) return 0;
        var d = units.get(run.train.driver());
        if (d == null || d.state() == null) return 0;
        for (var m : run.train.driven()) if (m.id() == u.id()) return d.state().speed() * m.ratio() / u.designSpeed();
        return 0;
    }

    // ---------------- presentation (reads only) ----------------

    /** The fluid device {@code device}'s shaft line for its status: its driver's status word, rpm and speed share; null for none. */
    public String deviceLine(long device) {
        Long unit = unitOfDevice.get(device);
        if (unit == null) return null;
        var v = view(unit);
        if (v == null) return null;
        return String.format(Locale.ROOT, "SHAFT: %s, %.0f rpm, %.0f %%", v.status(), v.speed(), 100 * v.share());
    }

    /** Whether fluid device {@code device} is a casing or turbine of a shaft unit. */
    public boolean unitOf(long device) {
        return unitOfDevice.containsKey(device);
    }

    /** The speed share sent to driven device {@code device}, or 0. */
    public double share(long device) {
        return shares.getOrDefault(device, 0.0);
    }

    /** The shaft speed at turbine {@code device}'s coupling, rpm (its speed sensor's reading), or NaN for none. */
    public double speed(long device) {
        Long unit = unitOfDevice.get(device);
        var v = unit == null ? null : view(unit);
        return v == null ? Double.NaN : v.speed();
    }

    /** The unit at {@code position}, or null. */
    public ShaftUnit unitAt(PhysicalFluidTopology.Position position) {
        return at(position);
    }

    /** Part {@code unit} as a page shows it, or null. */
    public View view(long unit) {
        var u = topology.shaft(unit);
        if (u == null) return null;
        Long key = trainOf.get(unit);
        var run = key == null ? null : runs.get(key);
        var store = electric == null ? null : electric.view(unit);
        if (run == null) return new View(unit, u.kind(), u.kind() == ShaftNetwork.Kind.SHAFT ? "LOOSE" : "NO DRIVER", 0, 0, 0, 0, 0, 0, 0, u.settings(), u.trip(), store);
        var train = run.train;
        var d = train.driver() == 0 ? null : topology.shaft(train.driver());
        double driverSpeed = train.fault() == ShaftNetwork.Fault.NONE && d != null && d.state() != null ? d.state().speed() : 0;
        double factor = 1, demand = 0;
        for (var m : train.driven()) demand += run.power.getOrDefault(m.id(), 0.0) / m.efficiency();
        for (var m : train.driven()) if (m.id() == unit) factor = m.ratio();
        if (train.shafts().containsKey(unit)) factor = train.shafts().get(unit);
        String status = train.fault() != ShaftNetwork.Fault.NONE ? train.fault().name().replace('_', ' ')
                : d != null && d.trip() != ShaftTrain.Trip.NONE ? u.kind().driver() ? ShaftTrain.tripped(d.trip()) : "DRIVER TRIPPED" : run.status;
        // A generator outside its speed window takes no load (W5, reading A64).
        if (u.kind() == ShaftNetwork.Kind.GENERATOR && train.fault() == ShaftNetwork.Fault.NONE && run.offSpeed.contains(unit)) status = "OFF SPEED";
        // A generator switched off on its page (W7) takes no load.
        if (u.kind() == ShaftNetwork.Kind.GENERATOR && train.fault() == ShaftNetwork.Fault.NONE && !u.settings().running()) status = "STOPPED";
        // A motor's or generator's store from a save before W5 runs free until wired (A73): the notice, non-blocking.
        if (store != null && "UNMETERED".equals(store.status()) && u.kind() == ShaftNetwork.Kind.MOTOR) status = status + " / UNMETERED";
        double speed = driverSpeed * factor, share = u.designSpeed() > 0 ? speed / u.designSpeed() : 0;
        double shaft = u.kind().driver() ? demand : run.power.getOrDefault(unit, 0.0);
        double power = u.kind() == ShaftNetwork.Kind.MOTOR ? run.electric : u.kind() == ShaftNetwork.Kind.GENERATOR ? run.output.getOrDefault(unit, 0.0) : 0;
        double jpf = electric == null ? ElectricStores.Settings.DEFAULT.joulesPerFE() : electric.joulesPerFE();
        var trip = u.trip() != ShaftTrain.Trip.NONE || !u.kind().driver() ? u.trip() : run.trip;
        return new View(unit, u.kind(), status, driverSpeed, speed, share, demand, shaft, power, power / jpf / 20, u.settings(), trip, store);
    }

    /** Every shaft unit as the ledger holds it (a read). */
    public List<ShaftUnit> units() {
        return List.copyOf(topology.shafts().values());
    }

    /** Unit {@code unit} as the ledger holds it, or null (a read; W7's pages). */
    public ShaftUnit unit(long unit) {
        return topology.shaft(unit);
    }

    /** Gearbox {@code unit}'s ratio as its train compiled it (its own, or the auto ratio, reading A20); 0 for another part or none. */
    public double ratio(long unit) {
        Long key = trainOf.get(unit);
        var run = key == null ? null : runs.get(key);
        return run == null ? 0 : run.train.ratios().getOrDefault(unit, 0.0);
    }

    /** The driver of the train that drives fluid device {@code device} (a casing), or null for none (W7: the casing's Drive group). */
    public ShaftUnit driverOf(long device) {
        Long unit = unitOfDevice.get(device);
        Long key = unit == null ? null : trainOf.get(unit);
        var run = key == null ? null : runs.get(key);
        if (run == null || run.train.driver() == 0) return null;
        return topology.shaft(run.train.driver());
    }

    /** The shaft unit of fluid device {@code device} (a casing or a turbine), or null. */
    public ShaftUnit unitOfDevice(long device) {
        Long unit = unitOfDevice.get(device);
        return unit == null ? null : topology.shaft(unit);
    }

    /** A turbine's steam at its last booking: flow, kg/s; inlet and exhaust pressure, Pa. */
    public record Steam(double flow, double inlet, double exhaust) {
    }

    /** Turbine unit {@code unit}'s steam at its train's last booking, or null for another part or before its first (W7's page). */
    public Steam steam(long unit) {
        var u = topology.shaft(unit);
        Long key = trainOf.get(unit);
        var run = key == null ? null : runs.get(key);
        if (u == null || u.kind() != ShaftNetwork.Kind.STEAM_TURBINE || run == null || run.train.driver() != unit || !run.booked) return null;
        return new Steam(run.steamFlow, run.inlet, run.exhaust);
    }

    /** A Create coupling as the engine holds it (W6b): Create's |rpm|, the stress it may take (su), the impact last sent (su per rpm, NaN for none). */
    public record Coupling(double rpm, double stress, double impact) {
    }

    /** Coupling unit {@code unit}'s Create input and the impact last sent, or null for another part or none (a read). */
    public Coupling coupling(long unit) {
        var u = topology.shaft(unit);
        if (u == null || u.kind() != ShaftNetwork.Kind.CREATE_COUPLING) return null;
        var in = create.get(u.position());
        return new Coupling(in == null ? 0 : in.rpm(), in == null ? 0 : in.stress(), impacts.getOrDefault(unit, Double.NaN));
    }

    /** The governor valve's fluid device that turbine unit {@code unit}'s speed sensor works, or 0 for none. */
    public long governorOf(long unit) {
        return valveOfTurbine.getOrDefault(unit, 0L);
    }

    /** Every unit, for the debug command's list. */
    public List<View> views() {
        var out = new ArrayList<View>();
        for (long id : new TreeSet<>(topology.shafts().keySet())) out.add(view(id));
        return out;
    }

    /** The governor valve's mass target sent for valve {@code device}, kg/s, or NaN. */
    public double governorTarget(long device) {
        return governorTargets.getOrDefault(device, Double.NaN);
    }

    /** Whether the governor target moved by more than the valve deadband; used by the tests through the plan path. */
    static boolean outsideDeadband(double a, double b) {
        return Math.abs(a - b) > PassiveStepSolver.VALVE_DEADBAND * Math.max(a, b) + 1e-9;
    }
}
