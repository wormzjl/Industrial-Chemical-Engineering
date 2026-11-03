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
     * @param needed  the bare surface the duty needs at full fans, m², infinite if the air cannot take it there
     * @param outlet  how far its outlet stands above the air, K
     * @param stream  the stream's side at design flow, which the fans' films are put on; null on a load given whole
     * @param duty    W at design flow
     */
    public record Load(LineSizing.Carried mean, double needed, double outlet, ExchangerRating.@Nullable Side stream, double duty) {
        /** A load given whole, whose fans this stage cannot read. */
        public Load(LineSizing.Carried mean, double needed, double outlet) {
            this(mean, needed, outlet, null, 0);
        }

        /** The cooler between these streams, null if it passes no heat. */
        public static @Nullable Load of(FlowStream in, FlowStream out, DesignBasis basis, FlowThermo thermo, MixtureViscosity.Workspace scratch) {
            double removed = in.enthalpy() - out.enthalpy(), air = HeatSides.AIR_TEMPERATURE;
            if (in.empty() || !(removed > 0)) return null;
            double flow = basis.designFlow();
            var side = ExchangerRating.side(thermo, in, Math.min(out.temperature(), air), in.temperature()).at(flow);
            var mean = thermo.atTemperature((in.temperature() + out.temperature()) / 2, in.pressure(), in.amountsView(), in.solids());
            return new Load(LineSizing.Carried.of(mean, flow, thermo, scratch), needed(side, removed * flow, 1), out.temperature() - air, side, removed * flow);
        }

        /** The bare surface {@code duty} W needs from {@code side} with the fans at {@code fans} of full speed, m². */
        private static double needed(ExchangerRating.Side side, double duty, double fans) {
            var filmed = new ExchangerRating.Side(side.curve().withFilms(HeatSides.airFilms(fans)), side.massFlow(), side.inlet());
            return ExchangerRating.rate(filmed, ExchangerRating.Side.sink(HeatSides.AIR_TEMPERATURE), duty).area();
        }
    }

    /**
     * An air cooler's fans at design flow ({@code COOLER_MODEL_PLAN.md} section 8): the share of full speed, from 0, natural draught
     * through open louvers, to 1, and the power they draw, W.
     */
    public record Fans(double speed, double power) {
    }

    /** Bisections of the fans' speed: a millionth of full speed. */
    private static final int FAN_STEPS = 20;

    /**
     * The fans of {@code size} that remove {@code load}'s duty against 25 °C air: the least speed whose films leave the face's bare
     * surface enough, its power by the fan laws ({@link HeatSides#fanPower}). Natural draught that holds the duty stills them, the
     * louvers throttling; a face short at full speed runs full, and its surface row says so. Null where the load has no stream.
     */
    public static @Nullable Fans fans(@Nullable Load load, Size size) {
        var stream = load == null ? null : load.stream();
        if (stream == null) return null;
        double surface = size.surface();
        if (Load.needed(stream, load.duty(), 0) <= surface) return new Fans(0, 0);
        if (!(load.needed() <= surface)) return new Fans(1, HeatSides.fanPower(surface, 1));
        double slow = 0, fast = 1;
        for (int step = 0; step < FAN_STEPS; step++) {
            double middle = (slow + fast) / 2;
            if (Load.needed(stream, load.duty(), middle) <= surface) fast = middle;
            else slow = middle;
        }
        return new Fans(fast, HeatSides.fanPower(surface, fast));
    }

    /** The smallest air cooler Auto knows, the size it gives a member with no need. */
    public static Size smallest() {
        return SIZES.getFirst();
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
