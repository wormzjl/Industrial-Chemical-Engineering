package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.plant.port.PortAccounts;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.HazardStandard;
import java.util.*;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * What the fluid network lets into the open air, as pollution releases (batch 2026-11-15-pollution-system, WP4; plan
 * sections 2, 3 and 9): read-only arithmetic on each committed or replayed span of an island, on the server thread on the
 * shaft trains' booking hook, after the flares and the chimneys. A span's boundary transfers are its own totals (a replay
 * scales its interval's by the span's share, {@code IslandCertificate.replay}); each is scaled by the span's process seconds
 * over the result's, which is 1 on a commit and on a replay. What left the network at a node is, per compound, what its
 * transfers of one kind took out net ({@code max(0, -sum)}: a boundary transfer is signed, into the island positive).
 * <ul>
 * <li>An open vessel's {@code VENT}: its gas, at its roof. Not a closed vessel's (its gas blanketing valve's vent, owner:
 * not counted for now) and not a batch still's pot (its vent and spill go to the still's head).</li>
 * <li>An open vessel's {@code SPILL}: the volatile share of its liquid ({@link HazardStandard#volatileMoles}), at its foot.
 * The rest of the liquid and its solids stay on the ground; this package books them nowhere.</li>
 * <li>A chimney's tip (its void's {@code EXCHANGE}): its gas and, as particulate, the solids it carries, above its top cell.</li>
 * <li>A flare's tip: while its flame holds ({@link Flares}), the combustion products only: one SO2 per sulphur atom (corrosive,
 * at SO2's reference) and soot ({@link #SOOT_YIELD} of the carbon burnt, by the flame's soot index); while it is out, its gas
 * unburnt. Above the tip's cell: the registration carries no stack height.</li>
 * <li>A fired heater's burners and a prime mover's fuel void: the fuel burns there and its flue or exhaust is no stream of
 * the network ({@code Burners#flueFlow} and {@code ShaftPlan.Kind#EXHAUST} are presentation and a coil's heat), so its SO2,
 * at the unit's cell.</li>
 * <li>A one-time {@code FLUSH} of a node's trace components: its gas, at the node. A certificate is not issued on an interval
 * that flushed ({@code IslandCertificate}), so a replay never carries one and a flush is booked once.</li>
 * <li>A cooling tower's {@code ATMOSPHERE}: what its plume carries, at its top.</li>
 * <li>A waste outfall's void ({@code EXCHANGE}, batch 2026-11-18-water-intake-outfall): the spill law on all it discharged
 * ({@link #outfall}): its vapour to the air at its own cube; the liquid it leaves on the ground (toxic and corrosive) and the
 * solids it carries (particulate: a liquid sets them down, unlike a chimney's gas) to the ledger's {@code GROUND} there,
 * never the field.</li>
 * </ul>
 * Each amount is in air-mole equivalents of its channel. Nothing is saved; no fluid fact, state or reference changes. A
 * fault here is caught and logged ({@link #booked}): it never stops the bookings after it.
 */
public final class Releases {
    /** Where a release goes: the pollution service in the world, a recorder in a test. */
    @FunctionalInterface
    public interface Sink {
        void release(PhysicalFluidTopology.Position position, Channel channel, double amount);

        /**
         * {@code amount} of {@code channel} left on the ground at {@code position}: the pollution ledger's {@code GROUND},
         * never the air (a waste outfall's rest, batch 2026-11-18-water-intake-outfall). None by default.
         */
        default void ground(PhysicalFluidTopology.Position position, Channel channel, double amount) {}
    }

    /** A flare tip's flame over the span just booked: whether it holds, and its soot index, 0 to 1 ({@code Flare#soot}). */
    record Flame(boolean burns, double soot) {
        static final Flame OUT = new Flame(false, 0);
    }

    /** The flame of the flare whose tip is {@code id}, as its last booked span left it. */
    @FunctionalInterface
    interface Flames {
        Flame flame(long id);
    }

    /** The share of a flame's carbon that leaves as soot at a soot index of 1 (plan section 9). */
    public static final double SOOT_YIELD = 0.01;
    /** kg/mol of carbon. */
    public static final double CARBON_MOLAR_MASS = 0.012011;
    /** SO2's reference where the world's package has no SO2 compound: NIOSH IDLH 100 ppm, as a mole fraction. */
    public static final double SO2_REFERENCE = 100e-6;
    /** The formula columns ({@code MaterialCatalog.FORMULA_ELEMENTS}: C, H, N, O, S). */
    static final int CARBON = 0, OXYGEN = 3, SULFUR = 4;
    /** A failure is logged at its first occurrence and then once per this many. */
    static final int LOG_EVERY = 1000;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("icheme/pollution");

    private final WorldTopologyLedger topology;
    private final List<String> axis;
    private final HazardStandard standard;
    private final double[][] formulas;
    private final double so2Reference;
    private final Flames flames;
    private final LongFunction<List<String>> islandAxis;
    private final Supplier<SimulationTime> time;
    private final Sink sink;
    private long failures;

    /**
     * @param axis         the world's conserved components (the network model's, water last): the order of {@code standard}
     *                     and {@code formulas}
     * @param formulas     atoms of C, H, N, O, S per molecule on {@code axis}
     * @param so2Reference SO2's corrosive reference, a mole fraction ({@link #so2Reference(double[], double[][])})
     * @param islandAxis   island {@code id}'s own compound list (its model's components), on which its transfers are stated
     * @param time         process time against online ticks (the coordinator's)
     */
    public Releases(WorldTopologyLedger topology, List<String> axis, HazardStandard standard, double[][] formulas, double so2Reference,
                    Flares flares, LongFunction<List<String>> islandAxis, Supplier<SimulationTime> time, Sink sink) {
        this(topology, axis, standard, formulas, so2Reference, flames(flares), islandAxis, time, sink);
    }

    Releases(WorldTopologyLedger topology, List<String> axis, HazardStandard standard, double[][] formulas, double so2Reference,
             Flames flames, LongFunction<List<String>> islandAxis, Supplier<SimulationTime> time, Sink sink) {
        this.topology = Objects.requireNonNull(topology);
        this.axis = List.copyOf(axis);
        this.standard = Objects.requireNonNull(standard);
        if (standard.size() != this.axis.size() || formulas.length != this.axis.size())
            throw new IllegalArgumentException("Releases: the hazard standard, the formulas and the components disagree");
        this.formulas = formulas.clone();
        if (!(so2Reference > 0 && so2Reference <= 1)) throw new IllegalArgumentException("Releases: SO2's reference must be in (0, 1]");
        this.so2Reference = so2Reference;
        this.flames = Objects.requireNonNull(flames);
        this.islandAxis = Objects.requireNonNull(islandAxis);
        this.time = Objects.requireNonNull(time);
        this.sink = Objects.requireNonNull(sink);
    }

    /** A flare's flame from {@link Flares}: it holds while its last booked span released heat. */
    private static Flames flames(Flares flares) {
        Objects.requireNonNull(flares);
        return id -> {
            var view = flares.view(id);
            if (view == null || !(view.heat() > 0)) return Flame.OUT;
            var flame = flares.flame(id);
            return new Flame(true, flame == null ? 0 : flame.soot());
        };
    }

    /** How many spans failed since the start. */
    long failures() {
        return failures;
    }

    /**
     * A committed or replayed span {@code [start, end)} of island {@code island}: what it let into the air, to the sink. Never
     * throws: a fault is logged at its first occurrence and then once per {@link #LOG_EVERY}.
     */
    public void booked(long island, long start, long end, PassiveIntervalSolver.Result result) {
        try {
            book(island, start, end, result);
        } catch (RuntimeException failure) {
            if (failures++ % LOG_EVERY == 0)
                LOGGER.error("pollution status=RELEASE_BOOKING_FAILED island={} failures={} detail={}", island, failures, String.valueOf(failure));
        }
    }

    /** What one node let out over the span by one kind of transfer: per compound net (island axis, signed), solids out (kg). */
    private static final class Out {
        final double[] moles;
        double solids;
        Out(int components) {
            moles = new double[components];
        }
    }

    /** A release source: one node and one kind of transfer, in a fixed order (node, then kind). */
    private record Key(long node, ConservativeTransport.BoundaryTransfer.Kind kind) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int c = Long.compare(node, other.node);
            return c != 0 ? c : kind.compareTo(other.kind);
        }
    }

    private void book(long island, long start, long end, PassiveIntervalSolver.Result result) {
        if (end <= start || result == null || !(result.advancedSeconds() > 0) || result.boundaries().isEmpty()) return;
        var registrations = topology.active();
        TreeMap<Key, Out> outs = null;
        Map<Long, PassiveNetwork.Reservoir> nodes = null;
        for (var b : result.boundaries()) {
            var kind = b.kind();
            var r = registrations.get(b.nodeId());
            if (r == null) continue;
            switch (kind) {
                case VENT, SPILL -> {
                    // An open vessel's only: a closed vessel's vent is its gas blanketing valve's; a still's pot's go to its head.
                    if (r.device().stillPot()) continue;
                    if (nodes == null) nodes = nodes(result.graph());
                    var node = nodes.get(b.nodeId());
                    if (node == null || node.kind() != PassiveNetwork.NodeKind.RESERVOIR || !node.inventory().open()) continue;
                }
                case FLUSH, ATMOSPHERE -> {
                }
                case EXCHANGE -> {
                    var d = r.device();
                    if (!(d.chimney() || d.flare() || d.burner() || d.engineFuel() || FluidWorldAuthority.outfall(r))) continue;
                }
                default -> {
                    continue;
                }
            }
            if (outs == null) outs = new TreeMap<>();
            var moles = b.moles();
            var out = outs.computeIfAbsent(new Key(b.nodeId(), kind), k -> new Out(moles.length));
            if (out.moles.length != moles.length) throw new IllegalStateException("Releases: transfers of " + moles.length + " and " + out.moles.length + " compounds at node " + b.nodeId());
            for (int c = 0; c < moles.length; c++) out.moles[c] += moles[c];
            if (!b.solids().empty()) out.solids -= b.solidDirection() * b.solids().massKg();
        }
        if (outs == null) return;
        double scale = scale(time.get().seconds(end - start), result.advancedSeconds());
        if (!(scale > 0)) return;
        var own = islandAxis.apply(island);
        for (var entry : outs.entrySet()) {
            var key = entry.getKey();
            var r = registrations.get(key.node());
            var out = entry.getValue();
            var gone = leaving(out.moles, scale);
            double solids = Math.max(0, out.solids) * scale;
            if (!any(gone) && !(solids > 0)) continue;
            var world = PortAccounts.onWorld(island, own, axis, gone);
            var at = r.device().position();
            switch (key.kind()) {
                case VENT -> {
                    if (nodes == null) nodes = nodes(result.graph());
                    var node = nodes.get(key.node());
                    gas(above(at, node == null ? 0 : node.inventory().levelHeight()), world);
                }
                case SPILL -> gas(above(at, 0), standard.volatileMoles(world));
                case FLUSH -> gas(above(at, 0), world);
                case ATMOSPHERE -> {
                    var tower = r.spec().tower();
                    gas(above(at, tower == null ? 0 : tower.height()), world);
                }
                case EXCHANGE -> {
                    var d = r.device();
                    if (d.chimney()) {
                        // The tip is the stack's top cell (FluidWorldAuthority.placeChimney): the plume leaves above it.
                        var top = above(at, 1);
                        gas(top, world);
                        emit(top, Channel.PARTICULATE, HazardStandard.particulate(solids));
                    } else if (d.flare()) {
                        var tip = above(at, 1);
                        var flame = flames.flame(key.node());
                        if (flame != null && flame.burns()) {
                            var products = flareProducts(world, formulas, flame.soot(), so2Reference);
                            emit(tip, Channel.CORROSIVE, products[0]);
                            emit(tip, Channel.PARTICULATE, products[1]);
                        } else gas(tip, world);
                    } else if (FluidWorldAuthority.outfall(r)) {
                        // A waste outfall (batch 2026-11-18-water-intake-outfall, owner: "Ground only"): what evaporates to
                        // the air at its own cube; the rest of the liquid and the solids it carries to the ground there.
                        var cube = above(at, 0);
                        var split = outfall(standard, world);
                        gas(cube, split[0]);
                        grounded(cube, Channel.TOXIC, standard.toxic(split[1]));
                        grounded(cube, Channel.CORROSIVE, standard.corrosive(split[1]));
                        grounded(cube, Channel.PARTICULATE, HazardStandard.particulate(solids));
                    } else emit(above(at, 0), Channel.CORROSIVE, sulfur(world, formulas) / so2Reference);
                }
                default -> {
                }
            }
        }
    }

    private static Map<Long, PassiveNetwork.Reservoir> nodes(PassiveNetwork graph) {
        var out = new HashMap<Long, PassiveNetwork.Reservoir>();
        for (var node : graph.reservoirs()) out.put(node.id(), node);
        return out;
    }

    /** What a destroyed device held (WP6a): its gas and its liquid, kg; a dense fluid's liquid-like part that flashes to the air counts as gas (P2, {@code FluidWorldAuthority.releaseContents}). */
    public record Spilled(double gasKilograms, double liquidKilograms) {
        public static final Spilled NONE = new Spilled(0, 0);
    }

    /**
     * A device a blast destroyed (batch 2026-11-15-pollution-system, WP6a, S5): its gas {@code gasMoles} whole and the
     * volatile share of its liquid {@code liquidMoles} ({@link HazardStandard#volatileMoles}, as a spill), both on the world
     * axis, at {@code at}. The rest of the liquid stays on the ground, as a spill's does.
     */
    public void contents(PhysicalFluidTopology.Position at, double[] gasMoles, double[] liquidMoles) {
        if (gasMoles.length != axis.size() || liquidMoles.length != axis.size())
            throw new IllegalArgumentException("Releases: contents of " + gasMoles.length + " and " + liquidMoles.length + " compounds on an axis of " + axis.size());
        gas(at, gasMoles);
        gas(at, standard.volatileMoles(liquidMoles));
    }

    /** What liquid {@code liquidMoles} (world axis) gives the air as a spill ({@link HazardStandard#volatileMoles}): a new array. */
    public double[] volatileMoles(double[] liquidMoles) {
        return standard.volatileMoles(liquidMoles);
    }

    /** The gas {@code moles} (world axis) through the standard's three gas channels, at {@code at}. */
    private void gas(PhysicalFluidTopology.Position at, double[] moles) {
        emit(at, Channel.TOXIC, standard.toxic(moles));
        emit(at, Channel.COMBUSTIBLE, standard.combustible(moles));
        emit(at, Channel.CORROSIVE, standard.corrosive(moles));
    }

    private void emit(PhysicalFluidTopology.Position at, Channel channel, double amount) {
        if (amount > 0 && Double.isFinite(amount)) sink.release(at, channel, amount);
    }

    // ---------------- the arithmetic ----------------

    /**
     * The factor that turns a result's transfers into the span's: the span's process seconds over the seconds the result
     * advanced (1 on a commit and on a replay, whose transfers are the span's own); 0 for an empty span or result.
     */
    static double scale(double spanSeconds, double advancedSeconds) {
        return spanSeconds > 0 && advancedSeconds > 0 && Double.isFinite(spanSeconds) && Double.isFinite(advancedSeconds) ? spanSeconds / advancedSeconds : 0;
    }

    /** What left, per compound, from the net signed transfers {@code net} (into the island positive), times {@code scale}. */
    static double[] leaving(double[] net, double scale) {
        var out = new double[net.length];
        for (int c = 0; c < net.length; c++) out[c] = net[c] < 0 ? -net[c] * scale : 0;
        return out;
    }

    private static boolean any(double[] moles) {
        for (double n : moles) if (n > 0) return true;
        return false;
    }

    /** Sulphur atoms in {@code moles}, mol: one SO2 each when they burn. */
    static double sulfur(double[] moles, double[][] formulas) {
        double s = 0;
        for (int c = 0; c < moles.length; c++) s += Math.abs(moles[c]) * formulas[c][SULFUR];
        return s;
    }

    /** The carbon in {@code moles}, kg. */
    static double carbonMass(double[] moles, double[][] formulas) {
        double carbon = 0;
        for (int c = 0; c < moles.length; c++) carbon += Math.abs(moles[c]) * formulas[c][CARBON];
        return carbon * CARBON_MOLAR_MASS;
    }

    /**
     * A burning flare's products from the gas {@code moles} it burnt: {corrosive, particulate} air-mole equivalents. Its SO2
     * (one per sulphur atom) at {@code so2Reference}; its soot, {@code soot * SOOT_YIELD} of the carbon's mass, as particulate.
     */
    static double[] flareProducts(double[] moles, double[][] formulas, double soot, double so2Reference) {
        double share = Math.clamp(Double.isFinite(soot) ? soot : 0, 0, 1);
        return new double[]{sulfur(moles, formulas) / so2Reference, HazardStandard.particulate(share * SOOT_YIELD * carbonMass(moles, formulas))};
    }

    /**
     * SO2's corrosive reference on an axis: that of the compound whose formula is S1 O2 (and nothing else) with a corrosive
     * reference, else {@link #SO2_REFERENCE}.
     */
    static double so2Reference(double[] corrosiveReferences, double[][] formulas) {
        for (int c = 0; c < formulas.length && c < corrosiveReferences.length; c++) {
            var f = formulas[c];
            if (f[SULFUR] == 1 && f[OXYGEN] == 2 && f[CARBON] == 0 && f[1] == 0 && f[2] == 0 && corrosiveReferences[c] > 0) return corrosiveReferences[c];
        }
        return SO2_REFERENCE;
    }

    /**
     * A waste outfall's discharge {@code moles} (world axis, all phases: a boundary transfer carries no phase) split by the
     * spill law on the whole stream ({@link HazardStandard#volatileMoles}; batch 2026-11-18-water-intake-outfall, owner:
     * "Ground only"): {vapour, rest}. The vapour goes to the air as a vent's gas: a compound volatile at 25 C (a gas, share 1)
     * whole, of a liquid its volatile share; the rest is the liquid left on the ground, which the ledger books {@code GROUND}
     * (toxic and corrosive) and the air never sees. Clean water books nothing anywhere: water has no reference.
     */
    static double[][] outfall(HazardStandard standard, double[] moles) {
        var vapour = standard.volatileMoles(moles);
        var rest = new double[moles.length];
        for (int c = 0; c < moles.length; c++) rest[c] = Math.max(0, Math.abs(moles[c]) - Math.abs(vapour[c]));
        return new double[][]{vapour, rest};
    }

    private void grounded(PhysicalFluidTopology.Position at, Channel channel, double amount) {
        if (amount > 0 && Double.isFinite(amount)) sink.ground(at, channel, amount);
    }

    /** {@code at} raised by {@code height} blocks, rounded up (a block column), on the block lane. */
    static PhysicalFluidTopology.Position above(PhysicalFluidTopology.Position at, double height) {
        int dy = height > 0 && Double.isFinite(height) ? (int) Math.min(4096, Math.ceil(height)) : 0;
        return new PhysicalFluidTopology.Position(at.dimension(), at.x(), at.y() + dy, at.z());
    }
}
