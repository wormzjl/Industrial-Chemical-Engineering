package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.unit.ExchangerRating;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * An air cooler (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4 and 3.5): the least face within its drop.
 * The bare surface a duty needs comes from {@link ExchangerRating}: the stream's curve at full fans' films, against air that takes
 * any heat at 25 °C, as every tube row meets fresh air. Its drop reads the stream at its mean temperature, whose viscosity it
 * carries through the bank.
 */
public final class AirCoolerSizing {
    private static final List<Size> SIZES = sizes();

    private AirCoolerSizing() {
    }

    private static List<Size> sizes() {
        var sizes = new ArrayList<Size>();
        for (var bay : ProcessUnit.bays())
            for (int parallel = 1; parallel <= ProcessUnit.Bank.MAX_PARALLEL; parallel++)
                for (int series = 1; series <= ProcessUnit.Bank.MAX_SERIES; series++) sizes.add(new Size(bay[0], (int) bay[1], new ProcessUnit.Bank(parallel, series)));
        sizes.sort(Comparator.comparingDouble(Size::face).thenComparingInt(size -> size.bank().bays()));
        return List.copyOf(sizes);
    }

    /**
     * A bank.
     *
     * @param width  a bay's, m
     * @param length a bay's, m
     */
    public record Size(double width, int length, ProcessUnit.Bank bank) implements UnitSize {
        /** The whole bank's bundle face, m². */
        public double face() {
            return bank.bays() * ProcessUnit.bundleFace(width, length);
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(EquipmentShape.Kind.AIR_COOLER, width, length, bank);
        }
    }

    /**
     * A cooler at design flow.
     *
     * @param mean    the stream at its mean temperature
     * @param needed  the bare surface the duty needs, m², infinite if the air cannot take it there
     * @param outlet  how far its outlet stands above the air, K
     */
    public record Load(LineSizing.Carried mean, double needed, double outlet) {
        /** The cooler between these streams, null if it passes no heat. */
        public static @Nullable Load of(FlowStream in, FlowStream out, DesignBasis basis, FlowThermo thermo, MixtureViscosity.Workspace scratch) {
            double removed = in.enthalpy() - out.enthalpy(), air = HeatSides.AIR_TEMPERATURE;
            if (in.empty() || !(removed > 0)) return null;
            var side = ExchangerRating.side(thermo, in, Math.min(out.temperature(), air), in.temperature());
            var fans = new ExchangerRating.Side(side.curve().withFilms(HeatSides.airFilms(1)), side.massFlow(), side.inlet());
            double flow = basis.designFlow(), needed = ExchangerRating.rate(fans.at(flow), ExchangerRating.Side.sink(air), removed * flow).area();
            var mean = thermo.atTemperature((in.temperature() + out.temperature()) / 2, in.pressure(), in.amountsView(), in.solids());
            return new Load(LineSizing.Carried.of(mean, flow, thermo, scratch), needed, out.temperature() - air);
        }
    }

    /** Every limit of {@code load} read at {@code size}. Null load, nothing to read. */
    public static Rating rate(@Nullable Load load, Size size) {
        if (load == null) return Rating.NONE;
        var readings = new ArrayList<Reading>();
        readings.add(UnitSize.reading(UnitSize.built(size)));
        readings.add(Limit.AIR_COOLER_SURFACE.against(HeatSides.airCoolerSurface(size.width(), size.length(), size.bank()), load.needed()));
        readings.add(Limit.AIR_COOLER_OUTLET.read(load.outlet()));
        readings.add(Limit.AIR_COOLER_DROP.read(load.mean().drop(HeatSides.airCoolerPassage(size.width(), size.length(), size.bank()), 1)));
        return new Rating(readings);
    }

    /** The least face that meets every limit, by its fewest bays. No load, no size. */
    public static Selection.Choice<Size> auto(@Nullable Load load) {
        if (load == null) return new Selection.Choice<>(null, Rating.NONE);
        return Selection.smallest(SIZES, size -> rate(load, size));
    }
}
