package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;

import java.util.List;

/**
 * A circulating fluidized-bed boiler (owner decisions D1, D3, D4 of 2026-10-18, NEW_UNITS_PLAN.md;
 * {@code NEW_UNITS_SURVEY_SOLID_FUEL.md} section 1), drawn by {@link FluidizedBedBoilerLayout}. Its main size is the
 * furnace's inside width W, 3 to 26 m in 1 m steps (Chalmers' 12 MW research CFB to Lagisza's 27.6 m): the depth is
 * 0.65 W between 2.5 and 11 m (secondary air must reach the middle, so large units grow in width), the furnace
 * 18 + 1.25 W high between 20 and 45 m (Baima's 35.5 m at W 15), the grid about 3.3 MW of fuel per m² (Lagisza 966 MWth
 * over 27.6 × 10.6 m). The second size is the number of hot cyclones in a row on the rear wall, each 0.85 W / n across
 * (Basu's practical largest 8.7 m, Baima 300 MWe); a count is offered only where the cyclones need no clamp: none wider
 * than 8.7 m or than a quarter of the furnace's height (the loop's standpipe needs the rest of it), and the row of
 * barrels, 0.6 m apart, no wider than the furnace and 2 m. The support is the grid's height, 5 to 8 m (survey): the
 * windbox, the bed-ash coolers and their conveyor stand under it.
 */
final class FluidizedBedBoilerDesign extends UnitDesign {
    /** The survey's grid heat release, MW of fuel per m² of grid, and the most a CFB is drawn for, MW. */
    static final double GRID_RELEASE = 3.3, MAX_INPUT = 950;
    /** Basu's practical largest cyclone, m. */
    static final double MAX_CYCLONE = 8.7, MIN_CYCLONE = 2;

    /** The furnace's inside depth for width {@code w}, m. */
    static double depth(double w) {
        return Math.max(2.5, Math.min(11, .65 * w));
    }

    /** The furnace's height from the grid to the roof for width {@code w}, m. */
    static double height(double w) {
        return Math.max(20, Math.min(45, 18 + 1.25 * w));
    }

    /** A cyclone's inside diameter for width {@code w} and {@code n} cyclones, m. */
    static double cyclone(double w, int n) {
        return Math.max(MIN_CYCLONE, Math.min(MAX_CYCLONE, .85 * w / n));
    }

    /** The fuel input, MW: the grid's heat release over the furnace's plan, at most {@link #MAX_INPUT}. */
    static double input(double w) {
        return Math.min(MAX_INPUT, GRID_RELEASE * w * depth(w));
    }

    FluidizedBedBoilerDesign() {
        // Computed in its overrides: minLength, maxLength.
        super(new Sizes(3, 26, 1,
                Sizes.COMPUTED, Sizes.COMPUTED,
                5, 8,
                Orientation.VERTICAL, "fluidized_bed_boiler.width", "fluidized_bed_boiler.cyclones", "fluidized_bed_boiler.grid", true));
    }

    /** The fewest cyclones none of which is wider than 8.7 m or than a quarter of the furnace's height. */
    @Override
    public int minLength(double size) {
        double largest = Math.min(MAX_CYCLONE, .25 * height(size));
        return Math.max(1, (int) Math.ceil(.85 * size / largest - 1e-9));
    }

    /** The most cyclones, at most four, whose row of barrels (each 0.85 W / n and 1.3 m of lining and gap) fits the furnace's width and 2 m. */
    @Override
    public int maxLength(double size) {
        int most = (int) Math.floor((.15 * size + 2) / 1.3 + 1e-9);
        return Math.max(minLength(size), Math.min(4, most));
    }

    /** A new boiler's cyclones: one per 8 m of furnace width (survey default ceil(W / 8)). */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), (int) Math.ceil(size / 8 - 1e-9)));
    }

    @Override
    public boolean lengthInMetres() {
        return false;
    }

    @Override
    public int defaultSupport(double size, int length) {
        return 6;
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("fluidized_bed_boiler.input", whole(input(shape.diameter())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double w = shape.diameter();
        int n = (int) shape.tangentLength();
        return List.of(new Line("fluidized_bed_boiler.furnace", size(w), number(depth(w)), number(height(w))),
                new Line("fluidized_bed_boiler.cyclones", n, number(cyclone(w, n))),
                new Line("fluidized_bed_boiler.input", whole(input(w))));
    }

    @Override
    void build(ProcessUnit unit) {
        new FluidizedBedBoilerLayout(unit).draw();
    }
}
