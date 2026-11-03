package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
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
     */
    public record Load(double flow, double head, double mostHead, @Nullable Double npsh) {
        /** A pump with no flow: no size, no lamp. */
        public static final Load NONE = new Load(0, 0, 0, null);

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
            return new Load(suction.volume() * 3600, rise / weight, Limit.PUMP_HEAD.value() / reference, npsh);
        }

        boolean flows() {
            return flow > 0;
        }
    }

    /** Every limit of {@code load} read at {@code size}. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.PUMP_FLOW.read(load.flow()));
        readings.add(Limit.PUMP_HEAD.against(load.head(), load.mostHead()));
        var npsh = load.npsh();
        if (npsh != null) {
            readings.add(Limit.PUMP_NPSH_WORLD.read(npsh));
            readings.add(Limit.PUMP_NPSH.read(npsh));
        }
        readings.add(Limit.PUMP_RATED.read(100 * load.flow() / ProcessUnit.pumpFlow(size.discharge())));
        return new Rating(readings);
    }

    /** The smallest discharge that meets every limit. No flow, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        return Selection.smallest(SIZES, size -> rate(load, size));
    }
}
