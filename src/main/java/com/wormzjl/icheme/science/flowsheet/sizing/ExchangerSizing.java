package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.unit.ExchangerRating;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An exchanger or a kettle (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4, 3.5 and 3.11). The surface a
 * duty needs comes from {@link ExchangerRating} on the world's two curves, and each side's drop from its {@link HeatSides} passage
 * at its inlet's flow, as the world's law prices it. Auto takes the fewest shells, then the least surface, that holds it under
 * twice the need and within both drops. A kettle's shell is a pool, so it has no shell drop and reads its flux.
 */
public final class ExchangerSizing {
    /** The most shells Auto tries: as many as a column member step takes in parallel (S3b). */
    private static final int MOST_SHELLS = ColumnComponents.MAX_UNITS;
    private static final List<Size> EXCHANGERS = sizes(EquipmentShape.Kind.EXCHANGER), KETTLES = sizes(EquipmentShape.Kind.KETTLE);

    private ExchangerSizing() {
    }

    private static List<Size> sizes(EquipmentShape.Kind kind) {
        var design = UnitDesign.of(kind);
        var sizes = new ArrayList<Size>();
        for (int shells = 1; shells <= MOST_SHELLS; shells++)
            for (double shell : UnitSize.grid(kind))
                for (int tubes = design.minLength(shell); tubes <= design.maxLength(shell); tubes++) sizes.add(new Size(kind == EquipmentShape.Kind.KETTLE, shells, shell, tubes));
        sizes.sort(Comparator.comparingInt(Size::shells).thenComparingDouble(Size::surface));
        return List.copyOf(sizes);
    }

    /**
     * A size of exchanger.
     *
     * @param kettle what it is, by its tile
     * @param shells in parallel
     * @param shell  m, a kettle's bundle
     * @param tubes  m
     */
    public record Size(boolean kettle, int shells, double shell, int tubes) implements UnitSize {
        public Size {
            if (shells < 1 || !(shell > 0) || !Double.isFinite(shell) || tubes < 1) throw new IllegalArgumentException("Invalid exchanger size");
        }

        /** All its shells' tube surface, m². */
        public double surface() {
            return shells * ProcessUnit.tubeSurface(shell, tubes);
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(kettle ? EquipmentShape.Kind.KETTLE : EquipmentShape.Kind.EXCHANGER, shell, tubes, ProcessUnit.Bank.ONE);
        }

        @Override public int count() {
            return shells;
        }

        public Size withShells(int next) {
            return new Size(kettle, next, shell, tubes);
        }
    }

    /**
     * An exchanger at design flow.
     *
     * @param tube      its tube side
     * @param shell     its shell side, null on a kettle
     * @param tubeDrop  the row its tube side's drop reads
     * @param shellDrop the row its shell side's reads, null on a kettle
     * @param needed    the surface the duty needs, m², infinite if the curves touch
     * @param duty      W
     * @param hotEnd    K
     * @param coldEnd   K
     * @param approach  the least difference along it, K
     * @param waterOut  cooling water's outlet, °C, null if its cold side is no water
     */
    public record Load(boolean kettle, LineSizing.Carried tube, LineSizing.@Nullable Carried shell, Limit tubeDrop, @Nullable Limit shellDrop, double needed, double duty, double hotEnd, double coldEnd, double approach, @Nullable Double waterOut) {
        /**
         * The unit between these streams, null if it passes no heat. A free side's outlet leaves no surface to read, so an exchanger
         * with a utility on one side has no load either.
         *
         * @param shellOut on a kettle, what leaves it
         */
        public static @Nullable Load of(boolean kettle, FlowStream tubeIn, FlowStream tubeOut, FlowStream shellIn, FlowStream shellOut, DesignBasis basis, FlowThermo thermo, MixtureViscosity.Workspace scratch) {
            double heat = tubeOut.enthalpy() - tubeIn.enthalpy();
            if (tubeIn.empty() || shellIn.empty() || heat == 0) return null;
            boolean tubeHot = heat < 0;
            var hotIn = tubeHot ? tubeIn : shellIn;
            var hotOut = tubeHot ? tubeOut : shellOut;
            var coldIn = tubeHot ? shellIn : tubeIn;
            var coldOut = tubeHot ? shellOut : tubeOut;
            double flow = basis.designFlow(), duty = Math.abs(heat) * flow, hotEnd = hotIn.temperature() - coldOut.temperature(), coldEnd = hotOut.temperature() - coldIn.temperature();
            double needed = Double.POSITIVE_INFINITY, approach = Math.min(hotIn.temperature() - coldIn.temperature(), Math.min(hotEnd, coldEnd));
            if (hotIn.temperature() > coldIn.temperature()) {
                var hot = ExchangerRating.side(thermo, hotIn, coldIn.temperature(), hotIn.temperature()).at(flow);
                var cold = ExchangerRating.side(thermo, coldIn, coldIn.temperature(), hotIn.temperature()).at(flow);
                var rated = ExchangerRating.rate(hot, cold, duty);
                needed = rated.area();
                approach = rated.approach();
            }
            var waterOut = Service.water(coldIn.state()) ? (Double) (coldOut.temperature() - 273.15) : null;
            var tube = LineSizing.Carried.of(tubeIn, flow, thermo, scratch);
            var shell = kettle ? null : LineSizing.Carried.of(shellIn, flow, thermo, scratch);
            return new Load(kettle, tube, shell, drop(tubeIn, tubeOut, !tubeHot, thermo), kettle ? null : drop(shellIn, shellOut, tubeHot, thermo), needed, duty, hotEnd, coldEnd, approach, waterOut);
        }

        /** The row a side answers to, by the world's film along it: what boils, what comes in as a gas or to condense, or a liquid. */
        private static Limit drop(FlowStream in, FlowStream out, boolean heated, FlowThermo thermo) {
            double inlet = Service.vapourShare(in.state(), thermo.model()), outlet = Service.vapourShare(out.state(), thermo.model()), wet = Limit.EXCHANGER_SIDE.value();
            if (heated && outlet > wet && inlet <= Limit.SERVICE_VAPOUR.value()) return Limit.EXCHANGER_DROP_BOILING;
            return inlet > wet ? Limit.EXCHANGER_DROP_GAS : Limit.EXCHANGER_DROP_LIQUID;
        }
    }

    /** Every limit of {@code load} read at {@code size}, any size. Null load, nothing to read. */
    public static Rating rate(@Nullable Load load, Size size) {
        if (load == null) return Rating.NONE;
        double surface = size.surface();
        var readings = new ArrayList<Reading>();
        readings.add(UnitSize.reading(UnitSize.built(size)));
        readings.add(Limit.EXCHANGER_SURFACE.against(surface, load.needed()));
        readings.add(Limit.EXCHANGER_HOT_END.read(load.hotEnd()));
        readings.add(Limit.EXCHANGER_COLD_END.read(load.coldEnd()));
        readings.add(Limit.EXCHANGER_APPROACH.read(load.approach()));
        readings.add(Limit.EXCHANGER_OVER_NEED.read(100 * (surface / load.needed() - 1)));
        readings.add(load.tubeDrop().read(load.tube().drop(HeatSides.tubePassage(size.shell(), size.tubes()), size.shells())));
        var row = load.shellDrop();
        if (load.shell() != null && row != null) readings.add(row.read(shellDrop(load, size)));
        if (load.kettle()) readings.add(Limit.KETTLE_FLUX.read(load.duty() / 1e3 / surface));
        var water = load.waterOut();
        if (water != null) readings.add(Limit.EXCHANGER_WATER_OUT.read(water));
        return new Rating(readings);
    }

    /** What {@code load}'s shell side loses at {@code size} at design flow, bar: 0 on a kettle, whose shell boils in a pool. */
    public static double shellDrop(Load load, Size size) {
        var shell = load.shell();
        return shell == null ? 0 : shell.drop(HeatSides.shellPassage(size.shell(), size.tubes()), size.shells());
    }

    /** The fewest shells, then the least surface, that meets every limit. No load, no size. */
    public static Selection.Choice<Size> auto(@Nullable Load load) {
        if (load == null) return new Selection.Choice<>(null, Rating.NONE);
        return Selection.smallest(load.kettle() ? KETTLES : EXCHANGERS, size -> rate(load, size));
    }
}
