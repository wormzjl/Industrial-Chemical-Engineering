package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The drivers' speed laws (batch 2026-11-03-rotating-machinery, plan section 4.1). A driver sets its speed once per slice
 * from what the last slices measured, never inside a Newton: the heat units' controller pattern. Speeds are shares of the
 * rated speed.
 */
public final class SpeedControl {
    /** The largest change of speed in one slice. */
    public static final double LARGEST_STEP = .2;

    private SpeedControl() {
    }

    /** A positive-displacement machine's speed for {@code setFlow}: the set flow over the displacement (m³/s each), exact and
     * without feedback, within the drive's range. */
    public static double displacementSpeed(double setFlow, double displacement, double lowest, double highest) {
        return clamp(setFlow / displacement, lowest, highest);
    }

    /**
     * A centrifugal machine's next speed, regulating a measure that rises with speed (its flow, or its pressure rise) to
     * {@code target}. With a previous slice whose measure moved the right way, the secant through the two; otherwise the
     * affinity guess {@code s (target/measured)^(1/exponent)} (exponent 1 for a flow, 2 for a rise). Each step is at most
     * {@link #LARGEST_STEP}, within the drive's range. It settles in two to four slices.
     *
     * @param previousSpeed    the speed of the slice before, NaN for none
     * @param previousMeasured the measure of the slice before, NaN for none
     */
    public static double regulate(double target, double speed, double measured, double previousSpeed, double previousMeasured,
                                  double exponent, double lowest, double highest) {
        double ds = speed - previousSpeed, dm = measured - previousMeasured, next;
        if (ds != 0 && dm / ds > 0) next = speed + (target - measured) * ds / dm;
        else if (measured > 0 && target > 0) next = speed * DeterministicMath.pow(target / measured, 1 / exponent);
        else next = target > measured ? speed + LARGEST_STEP : speed - LARGEST_STEP;
        return clamp(clamp(next, speed - LARGEST_STEP, speed + LARGEST_STEP), lowest, highest);
    }

    /**
     * A driver short of power (a turbine short of steam, a drive at its current limit, a Create network at its capacity):
     * the next speed {@code s (available/demand)^(1/6)}, at most {@code setSpeed}. For a load whose power goes as {@code s^m},
     * each slice multiplies the speed error by {@code 1 − m/6}: it halves it for the cube law and converges for any m below
     * 12. The plain cube-root step diverges from m = 6, which a static head can give.
     */
    public static double powerLimited(double speed, double available, double demand, double setSpeed) {
        if (!(demand > 0)) return setSpeed;
        return Math.min(setSpeed, speed * DeterministicMath.cbrt(Math.sqrt(Math.max(0, available) / demand)));
    }

    /**
     * A driver that cannot cut its power (an ungoverned turbine, batch 2026-11-03-rotating-machinery reading A22): the same
     * damped step {@code s (available/demand)^(1/6)} as {@link #powerLimited}, without the cap at the set speed, so a surplus
     * accelerates the shaft and the overspeed trip can act. With no demand, any power is a surplus without bound (+∞); with
     * neither, the speed holds.
     */
    public static double balance(double speed, double available, double demand) {
        if (!(demand > 0)) return available > 0 ? Double.POSITIVE_INFINITY : speed;
        return speed * DeterministicMath.cbrt(Math.sqrt(Math.max(0, available) / demand));
    }

    private static double clamp(double value, double lowest, double highest) {
        return Math.min(highest, Math.max(lowest, value));
    }
}
