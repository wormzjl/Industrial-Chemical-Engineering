package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.math.DeterministicMath;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A compressor (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4, 3.5 and 3.10): the type by its inlet flow,
 * then the smallest frame. The world's law is one isothermal step for both, so stages live here alone: a turbo's are its impellers,
 * and a piston machine's opposed pairs are shared out among as many stages as its ratio asks, its first stage then taking only its
 * own pairs' displacement. The discharge is the adiabatic estimate of one stage, cooled back to the suction between stages.
 */
public final class CompressorSizing {
    private static final String HYDROGEN = "Hydrogen";
    private static final double GAS_CONSTANT = FluidThermodynamics.R;
    /** The step of the heat capacity's difference, K. */
    private static final double STEP = 1;
    private static final List<Size> PISTON = sizes(UnitType.PISTON), TURBO = sizes(UnitType.TURBO);

    private CompressorSizing() {
    }

    private static List<Size> sizes(UnitType type) {
        var design = UnitDesign.of(type.kind());
        var sizes = new ArrayList<Size>();
        for (double size : UnitSize.grid(type.kind()))
            for (int length = design.minLength(size); length <= design.maxLength(size); length += design.lengthStep()) sizes.add(new Size(type, size, length));
        sizes.sort(Comparator.comparingDouble(Size::frame).thenComparingInt(Size::length));
        return List.copyOf(sizes);
    }

    /**
     * A frame.
     *
     * @param size   a piston's bore, a turbo's impeller, m
     * @param length a piston's throws, a turbo's impellers
     */
    public record Size(UnitType type, double size, int length) implements UnitSize {
        public Size {
            if (type != UnitType.PISTON && type != UnitType.TURBO) throw new IllegalArgumentException("Not a compressor: " + type);
            if (!(size > 0) || !Double.isFinite(size) || length < 1) throw new IllegalArgumentException("Invalid compressor size");
        }

        /** What the whole frame takes at its inlet, m³/h. */
        double frame() {
            return type == UnitType.PISTON ? ProcessUnit.displacement(size, length) : ProcessUnit.turboFlow(size);
        }

        /** The stages it runs {@code ratio} in. */
        int stages(double ratio) {
            if (type == UnitType.TURBO) return length;
            int pairs = length / 2, asked = (int) Math.ceil(DeterministicMath.log(Math.max(ratio, 1)) / DeterministicMath.log(Limit.COMPRESSOR_STAGE_PISTON.value()) - 1e-9);
            return Math.max(1, Math.min(pairs, asked));
        }

        /** What it takes at its inlet in {@code stages} stages, m³/h: a piston's first stage has only its share of the pairs. */
        double displacement(int stages) {
            return type == UnitType.PISTON ? ProcessUnit.displacement(size, 2 * (length / 2 / stages)) : frame();
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(type.kind(), size, length, ProcessUnit.Bank.ONE);
        }
    }

    /**
     * A compressor at design flow.
     *
     * @param flow     at its inlet, m³/h
     * @param ratio    outlet pressure over inlet
     * @param liquid   the suction's condensed share by mass, %
     * @param suction  K
     * @param exponent (γ − 1) / γ of the gas
     * @param hydrogen its hydrogen's mole share, %
     */
    public record Load(double flow, double ratio, double liquid, double suction, double exponent, double hydrogen) {
        /** A compressor with no flow: no size, no lamp. */
        public static final Load NONE = new Load(0, 1, 0, 0, 0, 0);

        public static Load of(FlowStream in, FlowStream out, DesignBasis basis, FlowThermo thermo, MixtureViscosity.Workspace scratch) {
            if (in.empty()) return NONE;
            var warmer = thermo.atTemperature(in.temperature() + STEP, in.pressure(), in.amountsView(), in.solids());
            double capacity = (warmer.enthalpy() - in.enthalpy()) / STEP / in.total();
            int hydrogen = thermo.place(HYDROGEN);
            double share = hydrogen < 0 ? 0 : 100 * in.amountsView()[hydrogen] / in.total();
            double liquid = 100 - Service.vapourShare(in.state(), thermo.model());
            return new Load(LineSizing.Carried.of(in, basis.designFlow(), thermo, scratch).volume() * 3600, out.pressure() / in.pressure(), liquid, in.temperature(), GAS_CONSTANT / capacity, share);
        }

        boolean flows() {
            return flow > 0;
        }
    }

    /** The type for {@code load}: a piston, for under the smallest frame no turbo exists either. */
    public static UnitType type(Load load) {
        return load.flow() <= Limit.TYPE_PISTON.value() ? UnitType.PISTON : UnitType.TURBO;
    }

    /** Every limit of {@code load} read at {@code size}, either type. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        int stages = size.stages(load.ratio());
        double stage = DeterministicMath.pow(load.ratio(), 1. / stages), discharge = load.suction() * DeterministicMath.pow(stage, load.exponent()) - 273.15;
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.COMPRESSOR_RATIO.read(load.ratio()));
        readings.add(Limit.COMPRESSOR_LIQUID.read(load.liquid()));
        readings.add(size.type() == UnitType.PISTON ? Limit.TYPE_PISTON.read(load.flow()) : Limit.TYPE_TURBO.read(load.flow()));
        readings.add(Limit.COMPRESSOR_DISPLACEMENT.against(size.displacement(stages), load.flow()));
        readings.add((size.type() == UnitType.PISTON ? Limit.COMPRESSOR_STAGE_PISTON : Limit.COMPRESSOR_STAGE_TURBO).read(stage));
        readings.add((load.hydrogen() > Limit.COMPRESSOR_HYDROGEN.value() ? Limit.COMPRESSOR_DISCHARGE_HYDROGEN : Limit.COMPRESSOR_DISCHARGE).read(discharge));
        return new Rating(readings);
    }

    /**
     * {@code type}'s smallest frame: the player's type, on Auto's size, or the proposed type where it is null. Frames whose
     * displacement holds the flow with {@link Limit#AUTO_SPARE} come first (S4c); the type stays on the design flow, as its rows are
     * ranges, not capacities. No flow, no size.
     */
    public static Selection.Choice<Size> auto(Load load, @Nullable UnitType type) {
        if (!load.flows()) return Selection.Choice.none();
        if (type == null) type = type(load);
        double held = Selection.spared(load.flow());
        return Selection.smallest(Selection.spareFirst(type == UnitType.PISTON ? PISTON : TURBO, size -> size.displacement(size.stages(load.ratio())), held), size -> rate(load, size));
    }
}
