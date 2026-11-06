package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/** VFD motor ratings, efficiency and continuous torque/power envelope. Legacy pole counts remain readable. */
public final class Motor {
    /** The rated outputs a design offers, kW: IEC 60072-1 from 0.75 kW to 1 MW, then the R20 series to 20 MW. */
    private static final double[] RATINGS_KW = {.75, 1.1, 1.5, 2.2, 3, 4, 5.5, 7.5, 11, 15, 18.5, 22, 30, 37, 45, 55, 75, 90, 110,
            132, 160, 200, 250, 315, 355, 400, 450, 500, 560, 630, 710, 800, 900, 1000, 1120, 1250, 1400, 1600, 1800, 2000, 2240,
            2500, 2800, 3150, 3550, 4000, 4500, 5000, 5600, 6300, 7100, 8000, 9000, 10000, 11200, 12500, 14000, 16000, 18000, 20000};
    /** The ratings of the IE3 table, kW: its last row holds from 200 kW to 1 MW, where the classes end, and on above. */
    private static final double[] IE3_KW = {.75, 1.1, 1.5, 2.2, 3, 4, 5.5, 7.5, 11, 15, 18.5, 22, 30, 37, 45, 55, 75, 90, 110, 132, 160, 200};
    /** IE3 minimum full-load efficiency at 50 Hz, %, for 2, 4, 6 and 8 poles at each of {@link #IE3_KW} (IEC 60034-30-1; EU
     * 2019/1781 Annex I, table 2). */
    private static final double[][] IE3 = {
            {80.7, 82.7, 84.2, 85.9, 87.1, 88.1, 89.2, 90.1, 91.2, 91.9, 92.4, 92.7, 93.3, 93.7, 94.0, 94.3, 94.7, 95.0, 95.2, 95.4, 95.6, 95.8},
            {82.5, 84.1, 85.3, 86.7, 87.7, 88.6, 89.6, 90.4, 91.4, 92.1, 92.6, 93.0, 93.6, 93.9, 94.2, 94.6, 95.0, 95.2, 95.4, 95.6, 95.8, 96.0},
            {78.9, 81.0, 82.5, 84.3, 85.6, 86.8, 88.0, 89.1, 90.3, 91.2, 91.7, 92.2, 92.9, 93.3, 93.7, 94.1, 94.6, 94.9, 95.1, 95.4, 95.6, 95.8},
            {75.0, 77.7, 79.7, 81.9, 83.5, 84.8, 86.2, 87.3, 88.6, 89.6, 90.1, 90.6, 91.3, 91.8, 92.2, 92.5, 93.1, 93.4, 93.7, 94.0, 94.3, 94.6}};
    /** The spare a proposed motor carries over the shaft power it drives (owner rule: "always have enough excess"). */
    public static final double SPARE = 1.2;
    /** A variable-speed drive's efficiency, multiplying the motor's (95 to 97 % at full load). */
    public static final double DRIVE_EFFICIENCY = .97;
    /** The lowest speed of a variable-speed drive, share of rated. */
    public static final double LOWEST_SPEED = .1;

    private Motor() {
    }

    /** The smallest standard rating, W, at or above {@code power} (W); NaN above the largest, 20 MW. */
    public static double standardRating(double power) {
        for (double kw : RATINGS_KW) if (power <= kw * 1000 * (1 + 1e-12)) return kw * 1000;
        return Double.NaN;
    }

    /** The rating, W, a design proposes for {@code shaftPower} (W): the next standard one above it with the {@link #SPARE}. */
    public static double ratingFor(double shaftPower) {
        return standardRating(SPARE * shaftPower);
    }

    /** The speed of a {@code poles}-pole motor at 50 Hz, rev/min, its rated slip included. */
    public static double ratedSpeed(int poles) {
        return switch (poles) {
            case 2 -> 2950;
            case 4 -> 1475;
            case 6 -> 985;
            case 8 -> 740;
            default -> throw new IllegalArgumentException("A motor has 2, 4, 6 or 8 poles, not " + poles);
        };
    }

    /** The full-load efficiency of a {@code poles}-pole motor rated {@code ratedPower} (W): IE3, the table's value at a
     * standard rating, in log power between two. */
    public static double efficiency(double ratedPower, int poles) {
        double[] column = IE3[poleColumn(poles)];
        double kw = ratedPower / 1000;
        if (!(kw > IE3_KW[0])) return column[0] / 100;
        for (int i = 1; i < IE3_KW.length; i++) {
            if (kw < IE3_KW[i]) {
                double x = DeterministicMath.log(kw / IE3_KW[i - 1]) / DeterministicMath.log(IE3_KW[i] / IE3_KW[i - 1]);
                return (column[i - 1] + (column[i] - column[i - 1]) * x) / 100;
            }
        }
        return column[column.length - 1] / 100;
    }

    /** The electric power, W, a {@code poles}-pole motor rated {@code ratedPower} draws for {@code shaftPower} (W), through a
     * variable-speed drive when {@code variableSpeed}. */
    public static double electricPower(double shaftPower, double ratedPower, int poles, boolean variableSpeed) {
        return Math.max(0, shaftPower) / (efficiency(ratedPower, poles) * (variableSpeed ? DRIVE_EFFICIENCY : 1));
    }

    /** VFD continuous envelope: constant torque below base speed, constant power above it. */
    public static double availablePower(double ratedPower,double baseRpm,double rpm) {
        if (!(ratedPower>=0)||!(baseRpm>0)||!(rpm>=0)||!Double.isFinite(ratedPower+baseRpm+rpm)) throw new IllegalArgumentException("Invalid motor envelope");
        return ratedPower*Math.min(1,rpm/baseRpm);
    }
    public static double torque(double power,double rpm) { return rpm>0?power*60/(2*Math.PI*rpm):0; }
    public static final double HIGHEST_SPEED=1.5;

    private static int poleColumn(int poles) {
        return switch (poles) {
            case 2 -> 0;
            case 4 -> 1;
            case 6 -> 2;
            case 8 -> 3;
            default -> throw new IllegalArgumentException("A motor has 2, 4, 6 or 8 poles, not " + poles);
        };
    }
}
