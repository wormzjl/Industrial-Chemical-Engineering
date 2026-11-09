package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.BatchStillOptions;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.fluid.network.BatchStill;

import java.util.ArrayList;
import java.util.List;

/**
 * A batch still (batch 2026-11-09-batch-distiller, W7; {@code BATCH_STILL_SIMULATOR.md}): the pot, on the constructor's list of pots,
 * with the options its door runs. Every other size follows the pot as the world's law has it: the heater's rating (25 kW/m² on the
 * heated surface), the condenser's area (the full boil-up and 20 % at 40 K) and the column's diameter (an F-factor of 1.5 Pa^0.5 at the
 * full boil-up). The rating shows them beside the limits.
 *
 * <p>A batch is self-similar in its heat: in pot mode every flow of the column is proportional to the boil-up, and the condenser and
 * its design water grow with the rating, so the batch at another pot is the run's batch, its time scaled by the ratings. The pot's
 * fill and the batch's time at the design flow ({@link DesignBasis#designFlow}: the charge is the inlet over the cycle) are what a size
 * changes; the F-factor and the condenser's share are the same at every pot, and read as the run gave them.</p>
 */
public final class StillSizing {
    private StillSizing() {
    }

    /** The pots the constructor builds, litres, smallest first: its list, which a stepped grid would repeat. */
    public static List<Double> pots() {
        return UnitDesign.of(EquipmentShape.Kind.BATCH_STILL).sizes();
    }

    /** @param pot the pot's working volume, litres: the world's still's main size; {@code options} its heater, condenser and column */
    public record Size(double pot, BatchStillOptions options) implements UnitSize {
        public Size {
            if (!(pot > 0) || !Double.isFinite(pot) || options == null) throw new IllegalArgumentException("Invalid still pot");
        }

        @Override public EquipmentShape shape() {
            var kind = EquipmentShape.Kind.BATCH_STILL;
            return UnitSize.unit(kind, pot, ProcessUnit.defaultLength(kind, pot), ProcessUnit.Bank.ONE).withStill(options);
        }

        /** The law's still of this pot. */
        public BatchStill.Design design() {
            var heater = switch (options.heater()) {
                case SOLID -> BatchStill.Heater.SOLID;
                case LIQUID -> BatchStill.Heater.LIQUID;
                case ELECTRIC -> BatchStill.Heater.ELECTRIC;
            };
            return new BatchStill.Design(pot / 1000, heater, options.condenses(), options.stages());
        }

        /** This pot with {@code litres}, the options kept. */
        public Size withPot(double litres) {
            return new Size(litres, options);
        }

        /** This pot with the door's {@code value}. */
        public Size withOptions(BatchStillOptions value) {
            return new Size(pot, value);
        }
    }

    /** What the rating shows beside the limits, by key: the heater's rating (W), the condenser's area (m²), the column's diameter (m). */
    public static final String RATED = "still_rated", CONDENSER = "still_condenser", COLUMN = "still_column";

    /**
     * A still on its run.
     *
     * @param options   the door's heater, condenser and column
     * @param volume    the charge's volume at the design flow, m³
     * @param heat      the heat its batch took at the design flow, J
     * @param firing    the door's firing, 0 to 1
     * @param cycle     the door's cycle, s
     * @param condensed the least share of the head's vapour its condenser took (1 without a condenser)
     * @param flooded   the share of its batch the column flooded
     * @param fFactor   its column's highest F-factor, Pa^0.5
     */
    public record Load(BatchStillOptions options, double volume, double heat, double firing, double cycle, double condensed, double flooded, double fFactor) {
        /** A still with no charge: no size, no lamp. */
        public static final Load NONE = new Load(BatchStillOptions.DEFAULT, 0, 0, 1, 1, 1, 0, 0);

        /**
         * The still on what its run read ({@code BatchStillUnit}'s readings): the charge's volume and the batch's heat at the run's flow,
         * scaled to the design flow.
         */
        public static Load of(BatchStillOptions options, double volume, double heatRate, double firing, double cycle, double condensed, double flooded, double fFactor, DesignBasis basis) {
            if (!(volume > 0) || !(heatRate > 0) || !(cycle > 0) || !(firing > 0)) return NONE;
            return new Load(options, volume * basis.designFlow(), heatRate * cycle * basis.designFlow(), firing, cycle, condensed, flooded, fFactor);
        }

        boolean charges() {
            return volume > 0;
        }

        /** The batch's time on pot {@code size}, s: its heat at the size's firing. */
        double batch(Size size) {
            return heat / (firing * size.design().rating());
        }

        /** The share of pot {@code size} the charge fills. */
        double fill(Size size) {
            return volume / size.design().volume();
        }
    }

    /** Every limit of {@code load} read at {@code size}, with the heater's rating, the condenser's area and the column's diameter beside them. */
    public static Rating rate(Load load, Size size) {
        if (!load.charges()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        double fill = 100 * load.fill(size), batch = load.batch(size);
        readings.add(Limit.STILL_VOLUME.read(fill));
        readings.add(Limit.STILL_FILL.read(fill));
        readings.add(Limit.STILL_BATCH.against(batch / 3600, load.cycle() / 3600));
        readings.add(Limit.STILL_BATCH_SPARE.read(100 * (load.cycle() / batch - 1)));
        if (size.options().condenses()) readings.add(Limit.STILL_COOLING.read(100 * load.condensed()));
        if (size.options().column()) {
            readings.add(Limit.STILL_FLOOD.read(100 * (1 - load.flooded())));
            readings.add(Limit.STILL_F_FACTOR.read(load.fFactor()));
        }
        var design = size.design();
        return new Rating(readings).with(RATED, design.rating()).with(CONDENSER, design.condenserArea()).with(COLUMN, design.columnDiameter());
    }

    /**
     * The smallest pot that meets every limit, first those that hold the charge at the usual fill and finish the batch with the cycle's
     * {@link Limit#AUTO_SPARE} (the owner's "always have enough excess"). No charge, no size.
     */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.charges()) return Selection.Choice.none();
        var sizes = new ArrayList<Size>();
        for (double pot : pots()) sizes.add(new Size(pot, load.options()));
        // First the pots that hold the charge at the usual fill (its headroom is the pot's own spare) and finish the batch with the spare.
        double held = Selection.spared(1);
        List<Size> order = Selection.spareFirst(sizes, size -> Math.min(held * BatchStill.FILL / load.fill(size), load.cycle() / load.batch(size)), held);
        return Selection.smallest(order, size -> rate(load, size));
    }
}
