package com.wormzjl.icheme.science.thermo.water;

import java.util.Arrays;

/**
 * The numbers of one water record, as plain doubles (the module imports no material type): what the water module's
 * functions read. Built once per record by the material side ({@code science.material.WaterRecords}); immutable, the
 * coefficient arrays copied in and read through the indexed accessors.
 *
 * @param molarMass kg/mol
 * @param triplePoint K
 * @param criticalTemperature K
 * @param criticalPressure Pa
 * @param steamDataMaximum the upper end of the steam's ideal-gas data, K
 * @param boilingTemperature the Watson latent heat's reference temperature, K
 * @param vaporizationEnthalpy the Watson latent heat at {@code boilingTemperature}, J/mol
 * @param watsonExponent the Watson exponent
 * @param datumVaporizationEnthalpy the liquid datum's latent heat at 298.15 K, J/mol; NaN when the record declares none
 */
public final class WaterConstants {
    private final double molarMass, triplePoint, criticalTemperature, criticalPressure, steamDataMaximum,
            boilingTemperature, vaporizationEnthalpy, watsonExponent, datumVaporizationEnthalpy;
    private final double[] saturation, shomate;

    /**
     * @param saturation the six Wagner-Pruss coefficients {@code a1 .. a6}
     * @param shomate the seven Shomate constants {@code A .. E, F, H} (kJ/mol, {@code t = T/1000})
     */
    public WaterConstants(double molarMass, double triplePoint, double criticalTemperature, double criticalPressure,
                          double steamDataMaximum, double boilingTemperature, double vaporizationEnthalpy,
                          double watsonExponent, double[] saturation, double[] shomate, double datumVaporizationEnthalpy) {
        if (saturation.length != 6 || shomate.length != 7) throw new IllegalArgumentException("Water needs 6 saturation and 7 Shomate coefficients");
        this.molarMass = molarMass;
        this.triplePoint = triplePoint;
        this.criticalTemperature = criticalTemperature;
        this.criticalPressure = criticalPressure;
        this.steamDataMaximum = steamDataMaximum;
        this.boilingTemperature = boilingTemperature;
        this.vaporizationEnthalpy = vaporizationEnthalpy;
        this.watsonExponent = watsonExponent;
        this.saturation = saturation.clone();
        this.shomate = shomate.clone();
        this.datumVaporizationEnthalpy = datumVaporizationEnthalpy;
    }

    public double molarMass() { return molarMass; }
    public double triplePoint() { return triplePoint; }
    public double criticalTemperature() { return criticalTemperature; }
    public double criticalPressure() { return criticalPressure; }
    public double steamDataMaximum() { return steamDataMaximum; }
    public double boilingTemperature() { return boilingTemperature; }
    public double vaporizationEnthalpy() { return vaporizationEnthalpy; }
    public double watsonExponent() { return watsonExponent; }
    public double datumVaporizationEnthalpy() { return datumVaporizationEnthalpy; }
    /** Wagner-Pruss coefficient {@code a_(i+1)}. */
    public double saturation(int i) { return saturation[i]; }
    /** Shomate constant {@code i} (0 to 6: A, B, C, D, E, F, H). */
    public double shomate(int i) { return shomate[i]; }

    @Override public String toString() {
        return "WaterConstants[Tc=" + criticalTemperature + ", Pc=" + criticalPressure + ", saturation=" + Arrays.toString(saturation) + "]";
    }
}
