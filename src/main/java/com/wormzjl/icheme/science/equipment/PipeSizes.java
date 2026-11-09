package com.wormzjl.icheme.science.equipment;

/**
 * Drawn proportions of a line and its flanges from its bore, shared by pipes, vessel nozzles and pipeline devices
 * (photo survey 2026-10-16, section 0: ASME B36.10 pipe and B16.5 / B16.47 Class 150 flanges). A pipe is at most 0.8 m
 * across, so that its flange stays within one block (owner decision 2026-10-25).
 */
public final class PipeSizes {
    /** The smallest drawn outside diameter: one texel. */
    public static final double MIN_OUTSIDE = 1 / 16.0;
    /** The largest outside diameter: its flange, 1.25 OD + 85 mm, reaches one block. */
    public static final double MAX_OUTSIDE = .8;
    /**
     * The largest bore, as large as the largest outside (owner decision 2026-10-25): a large line's wall is thin (32"
     * standard: 812.8 mm on a 793.7 mm bore), so a bore from 0.727 m draws at {@link #MAX_OUTSIDE} ({@link #outside}).
     */
    public static final double MAX_BORE = MAX_OUTSIDE;

    private PipeSizes() {
    }

    /**
     * A unit nozzle's drawn outside diameter, m: {@code want} (a line sized from the unit's duty) to the nearest 0.05 m,
     * from {@code least} to {@code most} (the nozzle's room on its face, at most one block).
     */
    public static double od(double want, double least, double most) {
        return Math.max(least, Math.min(most, Math.round(want * 20) / 20.0));
    }

    /**
     * Outside diameter of a pipe of bore {@code bore}: the wall adds about a tenth (2" 52.5/60.3 mm, 12" 303/324 mm), at
     * most {@link #MAX_OUTSIDE}.
     */
    public static double outside(double bore) {
        return Math.max(MIN_OUTSIDE, Math.min(MAX_OUTSIDE, 1.1 * bore));
    }

    /**
     * Flange outside diameter: about twice the pipe at 4", 1.5 times at 12", 1.3 times from 24" (1.25 OD + 85 mm fits the
     * table within a few percent), at most one block.
     */
    public static double flange(double outside) {
        return Math.min(1, 1.25 * outside + .085);
    }

    /** One flange of a pair: a pair is one texel thick up to 24", two above. */
    public static double flangeThickness(double outside) {
        return outside > .6 ? 1 / 16.0 : 1 / 32.0;
    }

    /** Width of the dark joint line between paired flange plates, m; adjoining blocks draw half each. */
    public static final double FLANGE_SEAM = 1 / 128.0;

    /** Length of the straight neck before a flange plate. */
    public static double hub(double outside) {
        return Math.min(.15, .04 + .25 * outside);
    }

    /** A square flange shoulder: the neck keeps the pipe's outside diameter right up to the plate. */
    public static double hubOutside(double outside) {
        return outside;
    }

    /**
     * Class 150 bolting by the pipe's outside diameter, inches: {largest outside, bolts, bolt diameter} (photo survey
     * EQUIPMENT_DETAILS_PHOTO_SURVEY.md, section C: ASME B16.5 to 24", B16.47 series A to 48").
     */
    private static final double[][] BOLTING = {
            {1.9, 4, .5}, {3.5, 4, .625}, {4.5, 8, .625}, {8.625, 8, .75}, {12.75, 12, .875}, {14, 12, 1}, {16, 16, 1}, {18, 16, 1.125},
            {20, 20, 1.125}, {24, 20, 1.25}, {26, 24, 1.25}, {30, 28, 1.25}, {32, 28, 1.5}, {36, 32, 1.5}, {42, 36, 1.5}, {48, 44, 1.5}};
    private static final double INCH = .0254;

    /** Bolts in a flange of a line of outside diameter {@code outside}, m; beyond 48" as many as keep 48"'s pitch, in fours. */
    public static int bolts(double outside) {
        double inches = outside / INCH;
        for (var row : BOLTING) if (inches <= row[0] * 1.02) return (int) row[1];
        return 4 * (int) Math.ceil(44 * inches / 48 / 4);
    }

    /** Pipe and equipment flanges share the pipe's drawn diameter grid and at most twelve visible bolts. */
    public static int drawnBolts(double outside) {
        return Math.min(12, bolts(outsideOf(code(outside))));
    }

    /** Diameter of those bolts, m. */
    public static double boltDiameter(double outside) {
        double inches = outside / INCH;
        for (var row : BOLTING) if (inches <= row[0] * 1.02) return row[2] * INCH;
        return 1.5 * INCH;
    }

    /** Facets around a pipe of this outside diameter: at most about 1/3 m wide, at least eight. */
    public static int sides(double outside) {
        return Math.max(8, 4 * (int) Math.ceil(.75 * Math.PI * outside));
    }

    /** A drawn outside diameter in 1/64 m steps (1 to 64), as cell keys carry it. */
    public static int code(double outside) {
        return (int) Math.max(1, Math.min(64, Math.round(outside * 64)));
    }

    public static double outsideOf(int code) {
        return code / 64.0;
    }

    /** The outside a pipe of bore {@code bore} is drawn at: its {@link #outside}, on the 1/64 m grid. A rack lane's width and height follow it. */
    public static double drawn(double bore) {
        return outsideOf(code(outside(bore)));
    }
}
