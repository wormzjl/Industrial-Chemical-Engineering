package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.network.ReleasePressure;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.Npsh;
import com.wormzjl.icheme.science.machine.PlungerPump;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A pump (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4 and 3.5): the smallest discharge whose rated flow fits
 * the window. Since S8 of batch 2026-11-15-supercritical-fluids the Pump tile is built as a centrifugal pump ({@link Size}) or a
 * triplex plunger pump ({@link Plunger}): the player's type, or Auto's ({@link #type}).
 */
public final class PumpSizing {
    private static final List<Size> SIZES = sizes();
    private static final List<Plunger> PLUNGERS = plungers();

    private PumpSizing() {
    }

    private static List<Size> sizes() {
        var sizes = new ArrayList<Size>();
        for (double discharge : UnitSize.grid(EquipmentShape.Kind.PUMP)) sizes.add(new Size(discharge));
        return List.copyOf(sizes);
    }

    /** @param discharge m */
    public record Size(double discharge) implements UnitSize {
        @Override public EquipmentShape shape() {
            return UnitSize.unit(EquipmentShape.Kind.PUMP, discharge, 1, ProcessUnit.Bank.ONE);
        }
    }

    private static List<Plunger> plungers() {
        var out = new ArrayList<Plunger>();
        for (int model = 1; model <= PlungerPump.MODELS.size(); model++) out.add(new Plunger(model));
        return List.copyOf(out);
    }

    /** A plunger pump (S8). @param model its model's number, 1 to 4 ({@link PlungerPump#MODELS}) */
    public record Plunger(int model) implements UnitSize {
        public Plunger {
            if (model < 1 || model > PlungerPump.MODELS.size()) throw new IllegalArgumentException("No plunger pump model " + model);
        }

        /** Its displacement at its design speed, m³/h. */
        public double capacity() {
            return PlungerPump.model(model).capacity();
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(EquipmentShape.Kind.PLUNGER_PUMP, model, 1, ProcessUnit.Bank.ONE);
        }
    }

    /**
     * A pump at design flow.
     *
     * @param flow     m³/h
     * @param head     m of what it pumps
     * @param mostHead what the world's 2 MPa of its reference fluid gives, m
     * @param npsh     what it has available (m), null until the height it draws from is known: then neither NPSH row reads.
     *                 Its suction pressure over its liquid's p_v ({@link FlowThermo#vapourPressure}), or over zero pressure
     *                 where the liquid has none ({@code boils} false)
     * @param density  its liquid's, kg/m³, which its shaft power reads; NaN where the caller gives none (no shaft power)
     * @param edge     the clean zone's edge E of its liquid ({@link FlowThermo#zoneEdge}) over the pressure {@code npsh} counts
     *                 from, m; positive infinity where it has none
     * @param boils    whether its liquid has a p_v
     * @param reducedDensity its suction's reduced density {@code v_c / v} where it is one dense fluid ({@code State.dense()},
     *                 S5 of batch 2026-11-15-supercritical-fluids), which {@link Limit#PUMP_DENSITY} reads; NaN for any other
     *                 suction, which reads no such row
     * @param pressure its suction's pressure, Pa, which a plunger pump's clearance loss and work read (batch
     *                 2026-11-16-supercritical-followups, P1); NaN where the caller gives none: then a plunger pump's work is
     *                 {@code Q ΔP} and its displacement loses no clearance
     * @param exponent its suction's isentropic exponent ({@code FluidThermodynamics#isentropicExponent}); NaN with no pressure
     */
    public record Load(double flow, double head, double mostHead, @Nullable Double npsh, double density, double edge, boolean boils, double reducedDensity,
                       double pressure, double exponent) {
        /** A pump with no flow: no size, no lamp. */
        public static final Load NONE = new Load(0, 0, 0, null, Double.NaN);

        /** A pump whose suction's pressure and exponent are not given. */
        public Load(double flow, double head, double mostHead, @Nullable Double npsh, double density, double edge, boolean boils, double reducedDensity) {
            this(flow, head, mostHead, npsh, density, edge, boils, reducedDensity, Double.NaN, Double.NaN);
        }

        /** A pump whose liquid's density is not given: its duty has no shaft power. */
        public Load(double flow, double head, double mostHead, @Nullable Double npsh) {
            this(flow, head, mostHead, npsh, Double.NaN);
        }

        /** A pump on a liquid that holds no gas: its NPSH counts from its vapour pressure and it has no edge above that. */
        public Load(double flow, double head, double mostHead, @Nullable Double npsh, double density) {
            this(flow, head, mostHead, npsh, density, Double.POSITIVE_INFINITY, true);
        }

        /** A pump on a suction that is no dense fluid. */
        public Load(double flow, double head, double mostHead, @Nullable Double npsh, double density, double edge, boolean boils) {
            this(flow, head, mostHead, npsh, density, edge, boils, Double.NaN);
        }

        /**
         * The pump on {@code suction}, which stands at {@code size}.
         *
         * @param height the static head from the nozzle it draws from to grade (m), null if that is not known yet
         */
        public static Load of(LineSizing.Line suction, LineSizing.Size size, FlowStream in, FlowStream out, @Nullable Double height, FlowThermo thermo) {
            return of(suction, size, in, out.pressure() - in.pressure(), height, thermo);
        }

        /**
         * A pump with no sheet streams of its own, a block's member: it draws {@code liquid} at its source's pressure and adds
         * {@code rise} (Pa), which the caller adds up from the loop it serves.
         *
         * <p>Its NPSH available is its suction pressure over its liquid's p_v, as the world counts it (W3B_NETWORK_PLAN.md
         * sections 17 and 44): a liquid saturated with a permanent gas stands at its bubble pressure in its drum and still
         * has the head over its vapour pressure. What the gas costs is the clean zone's edge E, which the world's row reads.
         * Two searches of the sheet's flash, 16 flashes each.</p>
         */
        public static Load of(LineSizing.Line suction, LineSizing.Size size, FlowStream liquid, double rise, @Nullable Double height, FlowThermo thermo) {
            if (!suction.flows()) return NONE;
            double weight = suction.density() * PassiveStepSolver.GRAVITY, reference = thermo.model().pumpReferenceDensity() * PassiveStepSolver.GRAVITY;
            double dense = liquid.state() != null && liquid.state().dense() ? liquid.state().reducedDensity() : Double.NaN;
            // A plunger pump's clearance and work read the suction's pressure and exponent (P1); the world's row reads the same.
            double exponent = liquid.state() != null ? thermo.model().isentropicExponent(liquid.state()) : Double.NaN;
            double pressure = Double.isNaN(exponent) ? Double.NaN : liquid.pressure();
            if (height == null) return new Load(suction.volume() * 3600, rise / weight, Limit.PUMP_HEAD.value() / reference, null, suction.density(), Double.POSITIVE_INFINITY, true, dense,
                    pressure, exponent);
            double vapour = thermo.vapourPressure(liquid.temperature(), liquid.pressure(), liquid.amountsView()), edge = thermo.zoneEdge(liquid.temperature(), liquid.pressure(), liquid.amountsView());
            boolean boils = vapour < Double.POSITIVE_INFINITY;
            double from = boils ? vapour : 0, at = liquid.pressure() + weight * height - 1e5 * LineSizing.drop(suction, size, suction.length());
            return new Load(suction.volume() * 3600, rise / weight, Limit.PUMP_HEAD.value() / reference, (at - from) / weight, suction.density(),
                    edge < Double.POSITIVE_INFINITY ? (edge - from) / weight : Double.POSITIVE_INFINITY, boils, dense, pressure, exponent);
        }

        boolean flows() {
            return flow > 0;
        }
    }

    /**
     * A pump's duty as a column's door reads it (batch 2026-11-03-rotating-machinery, W8, owner decision D43), at design flow.
     *
     * @param flow  m³/h at its suction
     * @param head  m of its liquid
     * @param shaft its shaft power at the design efficiency of a pump rated for the duty ({@link CentrifugalPump#bestEfficiency}), W;
     *              NaN where the load has no density
     * @param npsh  its NPSH3 at that flow ({@link #requiredNpsh}), m
     * @param motor the motor a design proposes ({@link Motor#ratingFor}: the next standard rating above 1.2 times the shaft power), W;
     *              NaN with no shaft power
     */
    public record Duty(double flow, double head, double shaft, double npsh, double motor) {
        /** The rating values that carry it, flow first: {@link Rating#values} keys, each in this record's unit. */
        public static final String FLOW = "pump_flow", HEAD = "pump_head", SHAFT = "pump_shaft", NPSH = "pump_npsh3", MOTOR = "pump_motor";

        /** The duty a rating carries, null where it carries none (no flow, or a rating of another unit). */
        public static @Nullable Duty of(Rating rating) {
            var v = rating.values();
            if (!v.containsKey(FLOW)) return null;
            return new Duty(v.get(FLOW), v.get(HEAD), v.getOrDefault(SHAFT, Double.NaN), v.get(NPSH), v.getOrDefault(MOTOR, Double.NaN));
        }

        Rating on(Rating rating) {
            var out = rating.with(FLOW, flow).with(HEAD, head).with(NPSH, npsh);
            return Double.isNaN(shaft) ? out : out.with(SHAFT, shaft).with(MOTOR, motor);
        }
    }

    /** {@code load}'s duty: its shaft power {@code ρ g Q H / η}, η Corripio's for a pump rated at its flow (W1, decision A5). */
    public static Duty duty(Load load) {
        double volume = load.flow() / 3600, shaft = load.density() * PassiveStepSolver.GRAVITY * volume * load.head() / CentrifugalPump.bestEfficiency(volume);
        return new Duty(load.flow(), load.head(), shaft, requiredNpsh(load.flow()), Double.isNaN(shaft) ? Double.NaN : Motor.ratingFor(shaft));
    }

    /** Every limit of {@code load} read at {@code size}, with its {@link Duty} beside them. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        return duty(load).on(limits(load, size));
    }

    /** {@code load}'s rise, Pa: its head in metres of what it pumps, on that liquid's weight; NaN where it has no density. */
    static double rise(Load load) {
        return load.head() * load.density() * PassiveStepSolver.GRAVITY;
    }

    /**
     * The isentropic work per suction volume of {@code load}'s rise from its suction ({@link PlungerPump#isentropicWork}), J/m³:
     * the rise itself where the load gives no suction pressure.
     */
    static double plungerWork(Load load) {
        double rise = rise(load);
        return Double.isNaN(load.pressure()) ? rise : PlungerPump.isentropicWork(rise, load.pressure(), load.exponent());
    }

    /** The share of a plunger's displacement its clearance leaves at {@code load}'s rise ({@link PlungerPump#volumetricEfficiency}):
     * 1 where the load gives no suction pressure. */
    static double plungerVolumetricEfficiency(Load load) {
        double rise = rise(load);
        return Double.isNaN(load.pressure()) || !Double.isFinite(rise) ? 1 : PlungerPump.volumetricEfficiency(rise, load.pressure(), load.exponent());
    }

    /**
     * A plunger pump's duty on {@code load} (S8): its shaft power the flow times the isentropic work of the rise over
     * {@link PlungerPump#MECHANICAL_EFFICIENCY}, as the world books the plungers' work (P1 of batch 2026-11-16-supercritical-followups;
     * {@code Q ΔP} on a liquid); no NPSH3 of its own.
     */
    public static Duty plungerDuty(Load load) {
        double shaft = load.flow() / 3600 * plungerWork(load) / PlungerPump.MECHANICAL_EFFICIENCY;
        return new Duty(load.flow(), load.head(), shaft, Double.NaN, Double.isNaN(shaft) ? Double.NaN : Motor.ratingFor(shaft));
    }

    /**
     * Every limit of {@code load} read at plunger pump {@code size} (S8): the world's flow, the flow with {@link Limit#AUTO_SPARE}
     * against what the model delivers, its displacement times the clearance's share at the load's rise ({@link Limit#PUMP_RATED}:
     * above 120 % the flow is more than it delivers; P1), and the rise against its relief rise. No head, suction density or NPSH
     * row: it displaces its volume at any density, and its suction limit is the world's.
     */
    public static Rating rate(Load load, Plunger size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.PUMP_FLOW.read(load.flow()));
        readings.add(Limit.PUMP_RATED.read(100 * Selection.spared(load.flow()) / (size.capacity() * plungerVolumetricEfficiency(load))));
        double rise = rise(load);
        if (Double.isFinite(rise)) readings.add(Limit.PLUNGER_RISE.read(rise / 1e6));
        return plungerDuty(load).on(new Rating(readings));
    }

    /**
     * The type Auto proposes for {@code load} (S8): a plunger pump where the flow is within its models (at most 100 m³/h) and either
     * its head is more than a centrifugal casing gives ({@link Load#mostHead}) or its suction is one dense fluid lighter than
     * {@link Limit#PUMP_DENSITY}, where a centrifugal pump's suction density row would light; else a centrifugal pump, as every
     * pump was before.
     */
    public static UnitType type(Load load) {
        boolean small = load.flows() && load.flow() <= PlungerPump.MODELS.getLast().capacity();
        boolean high = load.head() > load.mostHead(), light = Double.isFinite(load.reducedDensity()) && load.reducedDensity() < Limit.PUMP_DENSITY.value();
        return small && (high || light) ? UnitType.PLUNGER : UnitType.CENTRIFUGAL_PUMP;
    }

    /**
     * {@code size}'s shape as it is built for {@code duty} (owner, 2026-11-17: a sized pump is built with its duty): the pump
     * design rated at its bore's flow ({@link UnitRatings#pumpFlow}), at the rated head for which its own curve ({@link
     * CentrifugalPump}, the law the world builds from it) gives the design head at the design flow, at the poles the pump design
     * gives the bore. The bore-only shape where there is no duty, or where that design point is outside the pump design's
     * ranges (the PUMP_HEAD and PUMP_FLOW rows say why). Null where the constructor refuses the bore itself.
     */
    public static @Nullable EquipmentShape built(Size size, @Nullable Duty duty) {
        var shape = UnitSize.built(size);
        if (shape == null || duty == null || !(duty.flow() > 0) || !(duty.head() > 0)) return shape;
        double rated = UnitRatings.pumpFlow(size.discharge()) / 3600, design = duty.flow() / 3600;
        var unit = new CentrifugalPump(rated, 1, CentrifugalPump.bestEfficiency(rated), 1);
        double share = unit.head(design, 1);
        if (!(share > 0)) return shape;
        try {
            return shape.withDuty(MachineDuty.pump(UnitRatings.pumpFlow(size.discharge()), duty.head() / share, shape.duty().poles()));
        } catch (IllegalArgumentException outside) {
            return shape;
        }
    }

    /** {@code unit}'s built shape ({@link #built(Size, Duty)}) at the duty its rating carries; null for another size or a refused one. */
    public static @Nullable EquipmentShape built(SheetSizing.SizedUnit unit) {
        return unit.size() instanceof Size size ? built(size, Duty.of(unit.rating())) : null;
    }

    private static Rating limits(Load load, Size size) {
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.PUMP_FLOW.read(load.flow()));
        readings.add(Limit.PUMP_HEAD.against(load.head(), load.mostHead()));
        var npsh = load.npsh();
        if (npsh != null) {
            double required = requiredNpsh(load.flow());
            // The world's suction limit as heads over the pressure the NPSH counts from: p_v stands at 0 there, or nowhere.
            double floor = ReleasePressure.limit(load.boils() ? 0 : Double.POSITIVE_INFINITY, load.edge(), Npsh.BREAKDOWN * required);
            if (floor < Double.POSITIVE_INFINITY) readings.add(Limit.PUMP_NPSH_WORLD.against(npsh, floor));
            readings.add(Limit.PUMP_NPSH.against(npsh, required + Limit.PUMP_NPSH.value()));
        }
        readings.add(Limit.PUMP_RATED.read(100 * load.flow() / UnitRatings.pumpFlow(size.discharge())));
        if (Double.isFinite(load.reducedDensity())) readings.add(Limit.PUMP_DENSITY.read(load.reducedDensity()));
        return new Rating(readings);
    }

    /**
     * The NPSH3 (m) of the world's in-line pump set to {@code flow} (m³/h), at that flow: its duty design
     * ({@link FlowControl.Pump#npsh}, {@link Npsh#forDuty}; owner decision D21), 1.7 m at 36 m³/h and 3.3 m from 100 m³/h up.
     */
    public static double requiredNpsh(double flow) {
        double volumeFlow = flow / 3600;
        return new FlowControl.Pump(volumeFlow, 1).npsh().required(volumeFlow);
    }

    /** The smallest discharge that meets every limit, those whose rated flow holds the design flow with {@link Limit#AUTO_SPARE} first. No flow, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        // The smallest bore whose flow holds the design flow with Limit.AUTO_SPARE first, as a plunger pump's (owner, 2026-11-17).
        return Selection.smallest(Selection.spareFirst(SIZES, size -> UnitRatings.pumpFlow(size.discharge()), Selection.spared(load.flow())), size -> rate(load, size));
    }

    /** The smallest plunger pump that meets every limit, those whose displacement holds the flow with its spare first (S8). No flow, no size. */
    public static Selection.Choice<Plunger> autoPlunger(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        return Selection.smallest(Selection.spareFirst(PLUNGERS, Plunger::capacity, Selection.spared(load.flow())), size -> rate(load, size));
    }
}
