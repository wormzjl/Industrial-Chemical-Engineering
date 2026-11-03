package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.ColumnComponents;
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
        for (int banks = 1; banks <= ColumnComponents.MAX_UNITS; banks++)
            for (var bay : ProcessUnit.bays())
                for (int parallel = 1; parallel <= ProcessUnit.Bank.MAX_PARALLEL; parallel++)
                    for (int series = 1; series <= ProcessUnit.Bank.MAX_SERIES; series++) sizes.add(new Size(bay[0], (int) bay[1], new ProcessUnit.Bank(parallel, series), banks));
        sizes.sort(Comparator.comparingInt(Size::banks).thenComparingDouble(Size::face).thenComparingInt(size -> size.bank().bays()));
        return List.copyOf(sizes);
    }

    /**
     * A bank, or identical banks in parallel (S3b).
     *
     * @param width  a bay's, m
     * @param length a bay's, m
     * @param banks  identical banks in parallel, each taking its share of the stream, 1 to {@link ColumnComponents#MAX_UNITS}
     */
    public record Size(double width, int length, ProcessUnit.Bank bank, int banks) implements UnitSize {
        public Size {
            if (banks < 1 || banks > ColumnComponents.MAX_UNITS) throw new IllegalArgumentException("Invalid air cooler banks " + banks);
        }

        /** One bank. */
        public Size(double width, int length, ProcessUnit.Bank bank) {
            this(width, length, bank, 1);
        }

        /** One bank's bundle face, m². */
        public double face() {
            return bank.bays() * ProcessUnit.bundleFace(width, length);
        }

        /** All its banks' bare surface, m². */
        public double surface() {
            return banks * HeatSides.airCoolerSurface(width, length, bank);
        }

        public Size withBanks(int next) {
            return new Size(width, length, bank, next);
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(EquipmentShape.Kind.AIR_COOLER, width, length, bank);
        }

        @Override public int count() {
            return banks;
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
        readings.add(Limit.AIR_COOLER_SURFACE.against(size.surface(), load.needed()));
        readings.add(Limit.AIR_COOLER_OUTLET.read(load.outlet()));
        readings.add(Limit.AIR_COOLER_DROP.read(drop(load, size)));
        return new Rating(readings);
    }

    /** What {@code load}'s tubes lose at {@code size} at design flow, bar, its banks in parallel. */
    public static double drop(Load load, Size size) {
        return load.mean().drop(HeatSides.airCoolerPassage(size.width(), size.length(), size.bank()), size.banks());
    }

    /**
     * The fewest banks, then the least face, that meets every limit, by its fewest bays, first among those whose surface holds the
     * need with {@link Limit#AUTO_SPARE}: one more bank where fewer cannot hold the spare. No load, no size.
     */
    public static Selection.Choice<Size> auto(@Nullable Load load) {
        if (load == null) return new Selection.Choice<>(null, Rating.NONE);
        double held = load.needed() * (1 + Limit.AUTO_SPARE.value() / 100);
        return Selection.smallest(Selection.spareFirst(SIZES, Size::surface, held), size -> rate(load, size));
    }
}
