package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The vacuum set's steam law, S1 (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.12, W10b): one ejector stands
 * for a whole set, its stages and the intercondensers between them. The steam it takes, kg/s:
 *
 * <p>{@code m = K c(P_m) [G ln(P_d / P_s) + W ln(P_c / P_s)⁺]}
 *
 * <p>{@code G} is the load's gas (everything but water) as air, each kilogram counting √(29 / M), the vendors' rule [EJ-VEND];
 * {@code W} is its water, each kilogram counting √(29 / 18.015) = 1.269. The gas is lifted to the discharge; the water only to
 * {@code P_c}, twice its vapour pressure at the load's temperature, where the set's first intercondenser takes it. The log is the
 * limit of many stages with an intercondenser after each. It reads no game state, so column operation's stage C reads it as the
 * simulator does; it holds nowhere, and needs no fitted range.
 */
public final class EjectorLaw {
    /**
     * Kilograms of steam per kilogram of air per e-fold of compression: El-Dessouky's correlation [WT-EJ] on air at its best stage,
     * 30 to 120 kPa on 10 bar steam, takes 2.21 kg/kg, and 2.21 / ln 4 = 1.594.
     */
    public static final double K = 1.6;
    /** Air's molar mass and water's, g/mol: what a kilogram of load counts as air. */
    public static final double AIR = 29, WATER = 18.015;
    /** The pressure at which the set's first intercondenser condenses the load's water, over its vapour pressure. */
    public static final double CONDENSING = 2;
    /** Where PCF's polynomial has its least, kPa: 9e-4 / (2 × 3e-7). Above it the factor stays flat, with zero slope at the edge. */
    static final double PCF_EDGE = 1500;
    /** The motive pressure the law's {@link #K} is read at, kPa. */
    private static final double REFERENCE_MOTIVE = 1000;

    private EjectorLaw() {
    }

    /** {@code mass} kg of a gas of {@code molarMass} g/mol, as kilograms of air. */
    public static double asAir(double mass, double molarMass) {
        return mass * Math.sqrt(AIR / molarMass);
    }

    /**
     * A suction's gas as air, kg where the amounts are mol (per second where they are per second): every compound of
     * {@code vapour} before the index {@code water} (water vapour is carried apart, {@link #steam}), each as
     * {@code vapour[i] * weights[i] / per * times} kilograms ({@link #asAir}). Liquid carried with the suction is not gas:
     * the caller passes the vapour's amounts. The sheet passes rates with 1 and 1; the world a state's vapour amounts, its
     * vapour volume and the set's capacity (P3-03 of the 2026-11-13 simplifications: one sum for both).
     */
    public static double gasAsAir(double[] vapour, double[] weights, int water, double per, double times) {
        double gas = 0;
        for (int i = 0; i < water; i++) gas += asAir(vapour[i] * weights[i] / per * times, 1e3 * weights[i]);
        return gas;
    }

    /**
     * Kilograms of motive steam the set takes, per second where the load is per second.
     *
     * @param gas        the load's gas as air ({@link #asAir}), kg
     * @param water      the load's water, kg
     * @param motive     the motive steam's pressure, Pa
     * @param suction    the load's, Pa
     * @param discharge  the discharge's, Pa
     * @param condensing where the set condenses the water, Pa: {@link #CONDENSING} times its vapour pressure at the load's temperature
     */
    public static double steam(double gas, double water, double motive, double suction, double discharge, double condensing) {
        return K * motiveFactor(motive) * (gas * DeterministicMath.log(discharge / suction) + asAir(water, WATER) * Math.max(0, DeterministicMath.log(condensing / suction)));
    }

    /** El-Dessouky's PCF at {@code motive} Pa over its value at 10 bar, flat above 15 bar: 1.35 at 3 bar, 0.93 at 15 and above. */
    public static double motiveFactor(double motive) {
        return pcf(Math.min(motive / 1e3, PCF_EDGE)) / pcf(REFERENCE_MOTIVE);
    }

    private static double pcf(double kilopascal) {
        return 3e-7 * kilopascal * kilopascal - 9e-4 * kilopascal + 1.6101;
    }
}
