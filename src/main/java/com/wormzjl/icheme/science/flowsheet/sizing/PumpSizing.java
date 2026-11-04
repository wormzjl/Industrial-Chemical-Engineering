package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.Npsh;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** A pump (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4 and 3.5): the smallest discharge whose rated flow fits the window. */
public final class PumpSizing {
    private static final List<Size> SIZES = sizes();

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

    /**
     * A pump at design flow.
     *
     * @param flow     m³/h
     * @param head     m of what it pumps
     * @param mostHead what the world's 2 MPa of its reference fluid gives, m
     * @param npsh     what it has available (m), null until the height it draws from is known: then neither NPSH row reads
     * @param density  its liquid's, kg/m³, which its shaft power reads; NaN where the caller gives none (no shaft power)
     */
    public record Load(double flow, double head, double mostHead, @Nullable Double npsh, double density) {
        /** A pump with no flow: no size, no lamp. */
        public static final Load NONE = new Load(0, 0, 0, null, Double.NaN);

        /** A pump whose liquid's density is not given: its duty has no shaft power. */
        public Load(double flow, double head, double mostHead, @Nullable Double npsh) {
            this(flow, head, mostHead, npsh, Double.NaN);
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
         */
        public static Load of(LineSizing.Line suction, LineSizing.Size size, FlowStream liquid, double rise, @Nullable Double height, FlowThermo thermo) {
            if (!suction.flows()) return NONE;
            double weight = suction.density() * PassiveStepSolver.GRAVITY, reference = thermo.model().pumpReferenceDensity() * PassiveStepSolver.GRAVITY;
            Double npsh = null;
            if (height != null) npsh = (liquid.pressure() + weight * height - 1e5 * LineSizing.drop(suction, size, suction.length()) - thermo.bubblePressure(liquid.temperature(), liquid.amountsView())) / weight;
            return new Load(suction.volume() * 3600, rise / weight, Limit.PUMP_HEAD.value() / reference, npsh, suction.density());
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

    private static Rating limits(Load load, Size size) {
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.PUMP_FLOW.read(load.flow()));
        readings.add(Limit.PUMP_HEAD.against(load.head(), load.mostHead()));
        var npsh = load.npsh();
        if (npsh != null) {
            double required = requiredNpsh(load.flow());
            readings.add(Limit.PUMP_NPSH_WORLD.against(npsh, Npsh.BREAKDOWN * required));
            readings.add(Limit.PUMP_NPSH.against(npsh, required + Limit.PUMP_NPSH.value()));
        }
        readings.add(Limit.PUMP_RATED.read(100 * load.flow() / ProcessUnit.pumpFlow(size.discharge())));
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

    /** The smallest discharge that meets every limit. No flow, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        return Selection.smallest(SIZES, size -> rate(load, size));
    }
}
