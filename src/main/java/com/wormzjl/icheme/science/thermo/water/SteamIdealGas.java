package com.wormzjl.icheme.science.thermo.water;

import com.wormzjl.icheme.science.thermo.ThermoRefusal;

/**
 * Water vapour as an ideal gas on the record's datum: the record's Shomate form (a fit to the IAPWS-95 ideal-gas
 * {@code c_p} over 273.16 to 900 K, {@code water-iapws95-shomate-r3}), held at its end heat capacity past either end of
 * the steam's data, so value and slope are continuous there (owner rule of 2026-10-16; F3 of batch
 * 2026-11-13-codebase-review). The one copy for the column, the network's utility steam and the trained-physics check.
 */
public final class SteamIdealGas {
    private SteamIdealGas() {}

    /** The Shomate integral, J/mol, at {@code temperatureKelvin} itself (no hold). */
    public static double shomateEnthalpy(WaterConstants w, double temperatureKelvin) {
        double t = temperatureKelvin / 1_000.0;
        double kiloJoulesPerMol = w.shomate(0) * t + w.shomate(1) * t * t / 2.0 + w.shomate(2) * t * t * t / 3.0
                + w.shomate(3) * t * t * t * t / 4.0 - w.shomate(4) / t + w.shomate(5) + w.shomate(6);
        return 1_000.0 * kiloJoulesPerMol;
    }

    /** The Shomate heat capacity, J/(mol K), at {@code temperatureKelvin} itself (no hold). */
    public static double shomateHeatCapacity(WaterConstants w, double temperatureKelvin) {
        double t = temperatureKelvin / 1_000.0;
        return w.shomate(0) + w.shomate(1) * t + w.shomate(2) * t * t + w.shomate(3) * t * t * t + w.shomate(4) / (t * t);
    }

    /** {@code temperatureKelvin} held inside the steam's data range; it refuses a non-finite temperature. */
    public static double dataTemperature(WaterConstants w, double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin))
            throw new ThermoRefusal("Water enthalpy temperature is not finite");
        return Math.max(w.triplePoint(), Math.min(w.steamDataMaximum(), temperatureKelvin));
    }

    /** Whether {@code temperatureKelvin} lies past the steam's data, where {@link #enthalpy} is continued. */
    public static boolean continued(WaterConstants w, double temperatureKelvin) {
        return temperatureKelvin < w.triplePoint() || temperatureKelvin > w.steamDataMaximum();
    }

    /** The molar enthalpy, J/mol: the Shomate integral inside the data, continued with the held heat capacity past it. */
    public static double enthalpy(WaterConstants w, double temperatureKelvin) {
        double at = dataTemperature(w, temperatureKelvin);
        double h = shomateEnthalpy(w, at);
        return at == temperatureKelvin ? h : h + shomateHeatCapacity(w, at) * (temperatureKelvin - at);
    }

    /** The molar heat capacity, J/(mol K): the Shomate polynomial, held at its end value past the data. */
    public static double heatCapacity(WaterConstants w, double temperatureKelvin) {
        return shomateHeatCapacity(w, dataTemperature(w, temperatureKelvin));
    }
}
