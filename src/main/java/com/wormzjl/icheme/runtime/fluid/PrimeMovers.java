package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.Burners;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.ExhaustCoil;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.ShaftPlan;
import com.wormzjl.icheme.science.machine.PrimeMover;

import java.util.*;

/**
 * The fuelled prime movers' fuel and exhaust (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md sections 3 to 10): the
 * coupling between a combustion engine's or gas turbine's shaft train, its fuel's island and its steam coil's island.
 * Arithmetic on the server thread only, as the burners' is ({@link FiredHeaters}); {@link ShaftTrains} drives it:
 * <ul>
 * <li>at each commit or replay of a fuel island, the heat the set fired, W: the mass flow into its fuel void over the span
 * times the fuel's lower heating value ({@link Burners#perKilogram}), the supply's pressure and the fuel's phase;</li>
 * <li>at the train's step, the net shaft power that fuel gives ({@link PrimeMover#net}), the train's {@code available};
 * then from the governor's feed-forward the fuel target: the fuel for that power on the Willans line with
 * {@link Burners#MARGIN}, at most what the supply's pressure passes ({@link PrimeMover#supplyShare}, owner P5 A), 0 stopped
 * or on the wrong phase (owner P6 B: an engine takes liquid, a gas turbine gas);</li>
 * <li>at each dispatch, the fuel valve's target ({@link ShaftPlan.Kind#FUEL}, keyed by the fuel void) and a gas turbine's
 * steam coil's gas ({@link ShaftPlan.Kind#EXHAUST}: its capacity rate and the model's exhaust temperature);</li>
 * <li>its status word when short: {@code NO FUEL}, {@code WRONG FUEL}, {@code FUEL NOT COMBUSTIBLE}, {@code LOW FUEL PRESSURE},
 * {@code LOW FUEL}.</li>
 * </ul>
 * All of it is state: a load starts every set unfired, and its first slices light it again (owner P10 A: self-starting).
 */
public final class PrimeMovers {
    /** A set as its page shows it: fired heat, fuel flow, its heat, supply pressure, net shaft power, exhaust flow, its temperature and heat, radiator heat, the coil's heat and the stack. */
    public record View(String model, double fired, double fuelFlow, double heatingValue, double supply, double required, double net, double exhaustFlow, double exhaust,
                       double exhaustHeat, double cooling, double coilHeat, double stack, Burners.Fuel fuel) {}

    private static final class Fired {
        double fired, flow, perKilogram, supply = Double.NaN, coilHeat;
        boolean seen, wrongPhase;
        Burners.Fuel fuel;
    }

    private final double[] heatingValues, molarMasses;
    private final Map<Long, Fired> sets = new HashMap<>();
    /** Each fuel void's set (its shaft unit), each coil's fuel void: rebuilt by {@link #sync}. */
    private final Map<Long, Long> unitOfFuel = new HashMap<>(), fuelOfCoil = new HashMap<>();
    private final Map<Long, PrimeMover> laws = new HashMap<>();

    /**
     * @param heatingValues the network's lower heating values, J/mol, in its conserved order
     * @param molarMasses   its molar masses, kg/mol
     */
    public PrimeMovers(double[] heatingValues, double[] molarMasses) {
        if (heatingValues.length != molarMasses.length) throw new IllegalArgumentException("Heating values and molar masses disagree");
        this.heatingValues = heatingValues.clone();
        this.molarMasses = molarMasses.clone();
    }

    /** The world's sets: each prime mover unit with its fuel void and law, and its coil's registration ({@code coil}, 0 for none). */
    void sync(Collection<ShaftUnit> units, java.util.function.Function<ShaftUnit, Long> coil, java.util.function.Function<ShaftUnit, PrimeMover> law) {
        unitOfFuel.clear();
        fuelOfCoil.clear();
        laws.clear();
        for (var u : units) {
            if (!u.kind().primeMover() || u.fluid() == 0) continue;
            var l = law.apply(u);
            if (l == null) continue;
            unitOfFuel.put(u.fluid(), u.id());
            laws.put(u.id(), l);
            long c = coil.apply(u);
            if (c > 0) fuelOfCoil.put(c, u.fluid());
        }
        sets.keySet().retainAll(laws.keySet());
    }

    private Fired set(long unit) {
        return sets.computeIfAbsent(unit, k -> new Fired());
    }

    /** The set's law, or null for a unit that is not a prime mover. */
    PrimeMover law(long unit) {
        return laws.get(unit);
    }

    /** The net shaft power the set's last fired fuel gives, W. */
    double available(long unit) {
        var l = laws.get(unit);
        var s = sets.get(unit);
        return l == null || s == null ? 0 : l.net(s.fired);
    }

    /** Whether fuel arrives: the set's idle fuel is fired at least. */
    boolean firing(long unit) {
        var l = laws.get(unit);
        var s = sets.get(unit);
        return l != null && s != null && s.fired >= .99 * l.model().family().idle() * l.model().ratedFuel();
    }

    /** The fuel target, kg/s, for {@code governorPower} W of net shaft power, 0 for a set that does not run or burns the wrong phase. */
    double target(long unit, double governorPower, boolean running) {
        var l = laws.get(unit);
        if (l == null || !running) return 0;
        var s = set(unit);
        if (s.wrongPhase) return 0;
        double heat = s.perKilogram > 0 ? s.perKilogram : l.defaultHeatingValue();
        double fuel = Math.min(l.model().ratedFuel(), l.fuel(l.grossFor(governorPower)) * Burners.MARGIN);
        // The supply's pressure caps the fuel (owner P5 A); before any reading, the rated fuel.
        double share = Double.isFinite(s.supply) ? l.supplyShare(s.supply) : 1;
        return Math.min(fuel, share * l.model().ratedFuel()) / heat;
    }

    /** Why the set is short of fuel, its status word in place of {@code LOW FUEL}. */
    String shortWord(long unit) {
        var l = laws.get(unit);
        var s = sets.get(unit);
        if (l == null || s == null || !s.seen) return "NO FUEL";
        if (s.wrongPhase) return "WRONG FUEL";
        if (s.flow > 0 && s.perKilogram == 0) return "FUEL NOT COMBUSTIBLE";
        if (!(s.fired > 0)) return Double.isFinite(s.supply) && l.supplyShare(s.supply) < 1 ? "LOW FUEL PRESSURE" : "NO FUEL";
        return Double.isFinite(s.supply) && s.supply < l.model().required() ? "LOW FUEL PRESSURE" : "LOW FUEL";
    }

    /** The plans at dispatch (reads only): each fuel valve's target from {@code targets} (by fuel void), each coil's gas. */
    void plans(PassiveNetwork graph, Map<Long, Double> targets, Map<Long, ShaftPlan> out) {
        for (var pipe : graph.pipes()) {
            if (pipe.control() instanceof FlowControl.ControlValve valve && valve.source() == FlowControl.ValveSource.ENGINE) {
                long fuel = graph.reservoirs().get(pipe.second()).id();
                if (unitOfFuel.containsKey(fuel)) out.put(fuel, new ShaftPlan(ShaftPlan.Kind.FUEL, targets.getOrDefault(fuel, 0.0)));
            } else if (pipe.heat() instanceof ExhaustCoil) {
                long coil = graph.reservoirs().get(pipe.first()).id();
                var gas = gas(coil);
                if (gas != null) out.put(coil, gas);
            }
        }
    }

    /** Coil {@code coil}'s gas plan: its set's exhaust capacity rate and temperature, none while it is not fired; null for no set. */
    ShaftPlan gas(long coil) {
        Long fuel = fuelOfCoil.get(coil);
        Long unit = fuel == null ? null : unitOfFuel.get(fuel);
        var l = unit == null ? null : laws.get(unit);
        if (l == null) return null;
        var s = sets.get(unit);
        double fired = s == null ? 0 : s.fired, flue = l.flue(l.exhaustHeat(fired, l.gross(fired)));
        return flue > 0 ? new ShaftPlan(ShaftPlan.Kind.EXHAUST, flue, l.model().exhaust()) : new ShaftPlan(ShaftPlan.Kind.EXHAUST, 0, 0);
    }

    /** The coils whose set's fuel is booked: {@code fuel} void to coil. */
    Map<Long, Long> coils() {
        var out = new HashMap<Long, Long>();
        for (var e : fuelOfCoil.entrySet()) out.put(e.getValue(), e.getKey());
        return out;
    }

    /** A committed or replayed span: each fuel void's fired heat, supply and phase, each coil's heat. Returns the units whose fuel was booked. */
    List<Long> booked(long start, long end, PassiveIntervalSolver.Result result) {
        if (end <= start) return List.of();
        var solved = result.graph();
        var average = result.averageMassFlows();
        double seconds = (end - start) / 20.0;
        Map<Long, Double> heat = null;
        var out = new ArrayList<Long>();
        for (int e = 0; e < solved.pipes().size(); e++) {
            var pipe = solved.pipes().get(e);
            if (pipe.control() instanceof FlowControl.ControlValve valve && valve.source() == FlowControl.ValveSource.ENGINE) {
                Long unit = unitOfFuel.get(solved.reservoirs().get(pipe.second()).id());
                if (unit == null) continue;
                var l = laws.get(unit);
                var donor = solved.reservoirs().get(pipe.first());
                var s = set(unit);
                var phase = FiredHeaters.phase(donor);
                // The valve stays shut on the wrong phase (owner P6 B): an engine burns liquid, a gas turbine gas.
                s.wrongPhase = donor.state().mass() > 0 && (l.model().family() == PrimeMover.Family.ENGINE ? phase != Burners.Fuel.LIQUID : phase != Burners.Fuel.GAS);
                double flow = Math.max(0, average[e]), perKilogram = Burners.perKilogram(donor.inventory().moles(), molarMasses, heatingValues);
                s.flow = flow;
                if (perKilogram > 0 || flow > 0) s.perKilogram = perKilogram;
                s.fired = flow * perKilogram;
                s.supply = donor.state().pressure();
                s.fuel = phase;
                s.seen = true;
                out.add(unit);
            } else if (pipe.heat() instanceof ExhaustCoil) {
                long coil = solved.reservoirs().get(pipe.first()).id();
                Long fuel = fuelOfCoil.get(coil);
                Long unit = fuel == null ? null : unitOfFuel.get(fuel);
                if (unit == null) continue;
                if (heat == null) {
                    heat = new HashMap<>();
                    for (var b : result.boundaries())
                        if (b.kind() == ConservativeTransport.BoundaryTransfer.Kind.HEAT) heat.merge(b.nodeId(), b.totalEnergyJoule(), Double::sum);
                }
                set(unit).coilHeat = Math.max(0, heat.getOrDefault(pipe.id(), 0.) / seconds);
            }
        }
        return out;
    }

    /** Set {@code unit} as its page shows it, or null. */
    public View view(long unit) {
        var l = laws.get(unit);
        if (l == null) return null;
        var s = sets.get(unit);
        double fired = s == null ? 0 : s.fired, gross = l.gross(fired), exhaust = l.exhaustHeat(fired, gross), flue = l.flue(exhaust), coil = s == null ? 0 : s.coilHeat;
        return new View(l.model().name(), fired, s == null ? 0 : s.flow, s == null ? 0 : s.perKilogram, s == null ? Double.NaN : s.supply, l.model().required(), l.net(fired),
                l.exhaustFlow(exhaust), fired > 0 ? l.model().exhaust() : PrimeMover.AIR, exhaust, l.cooling(fired, gross), coil,
                fired > 0 ? ExhaustCoil.stack(l.model().exhaust(), flue, coil) : PrimeMover.AIR,
                s == null || s.fuel == null ? l.model().family() == PrimeMover.Family.ENGINE ? Burners.Fuel.LIQUID : Burners.Fuel.GAS : s.fuel);
    }

    /**
     * The water the fuel forms, kg per joule fired: methane 2.25 kg per kg at 50 MJ/kg, diesel (CH1.85) 1.24 kg per kg at
     * 42.8 MJ/kg. The stack's plume reads it; the set's own balance does not.
     */
    static final double GAS_WATER = 2.25 / PrimeMover.METHANE_LHV, LIQUID_WATER = 1.24 / PrimeMover.DIESEL_LHV;

    /**
     * A set's stack as the flue smoke draws it ({@code FluidNetwork.HeaterFlue}): its firing over its rated fuel, its stack's
     * temperature, its exhaust flow, kg/s, the exhaust's water per kg of the rest, and its fuel's phase; null where {@code fuel}
     * is no set's fuel void.
     */
    public record Flue(double firing, double stack, double flow, double water, Burners.Fuel fuel) {}

    /** The stack of the set whose fuel void is {@code fuel} (a read), or null. */
    public Flue flue(long fuel) {
        Long unit = unitOfFuel.get(fuel);
        var l = unit == null ? null : laws.get(unit);
        var v = l == null ? null : view(unit);
        if (v == null) return null;
        double formed = v.fired() * (v.fuel() == Burners.Fuel.GAS ? GAS_WATER : LIQUID_WATER), rest = v.exhaustFlow() - formed;
        return new Flue(Math.clamp(v.fired() / l.model().ratedFuel(), 0, 1), v.stack(), v.exhaustFlow(), rest > 0 ? formed / rest : 0, v.fuel());
    }
}
