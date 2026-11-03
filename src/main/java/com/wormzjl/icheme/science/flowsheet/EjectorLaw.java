package com.wormzjl.icheme.science.flowsheet;

/**
 * The steam ejector's law (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.12, D21): the steam one kilogram
 * of load takes, from El-Dessouky's correlation [WT-EJ] with the square root of the molar mass that vendors use to turn a gas into
 * a dry-air load [EJ-VEND]. It reads no game state, so column operation's stage C reads it as the simulator does.
 *
 * <p>{@code Ra = 0.296 P_d^1.19 / P_s^1.04 (P_m / P_s)^0.015 PCF / TCF √(18.015 / M)}, pressures in kPa, the load's temperature in
 * °C. PCF's polynomial has its least at 1500 kPa and turns up above it, doubling the steam at 3500 kPa, which no ejector does: it
 * stays at its least there, with the polynomial's own zero slope at the edge. Outside the fitted range the law runs on as written,
 * and {@link #fitted} says so for a notice; it never holds.
 */
public final class EjectorLaw {
    /** Water's molar mass, g/mol: the correlation's load and motive. */
    public static final double WATER = 18.015;
    /** The correlation's fitted range [WT-EJ]: its own steam per kg below this, on water. */
    public static final double FITTED_RATIO = 4;
    /** ... a compression above this ... */
    public static final double FITTED_COMPRESSION = 1.89;
    /** ... and a motive pressure within these, Pa. */
    public static final double FITTED_MOTIVE_LOW = 100e3, FITTED_MOTIVE_HIGH = 3500e3;
    /** Where PCF's polynomial has its least, kPa: 9e-4 / (2 × 3e-7). */
    static final double PCF_EDGE = 1500;

    private EjectorLaw() {
    }

    /**
     * Kilograms of motive steam one kilogram of load takes.
     *
     * @param motive      the motive steam's pressure, Pa
     * @param suction     the load's, Pa
     * @param discharge   the discharge's, Pa
     * @param temperature the load's, K
     * @param molarMass   the load's, g/mol
     */
    public static double ratio(double motive, double suction, double discharge, double temperature, double molarMass) {
        return water(motive, suction, discharge, temperature) * Math.sqrt(WATER / molarMass);
    }

    /** The correlation's own steam per kg, for a load of water vapour: what its fitted range reads. */
    static double water(double motive, double suction, double discharge, double temperature) {
        double m = motive / 1e3, s = suction / 1e3, d = discharge / 1e3, t = temperature - 273.15;
        double edge = Math.min(m, PCF_EDGE);
        double pcf = 3e-7 * edge * edge - 9e-4 * edge + 1.6101;
        double tcf = 2e-8 * t * t - 6e-4 * t + 1.0047;
        return .296 * Math.pow(d, 1.19) / Math.pow(s, 1.04) * Math.pow(m / s, .015) * pcf / tcf;
    }

    /** Whether these pressures and this temperature lie inside the correlation's fitted range. */
    public static boolean fitted(double motive, double suction, double discharge, double temperature) {
        return water(motive, suction, discharge, temperature) < FITTED_RATIO && discharge / suction > FITTED_COMPRESSION && motive >= FITTED_MOTIVE_LOW && motive <= FITTED_MOTIVE_HIGH;
    }
}
