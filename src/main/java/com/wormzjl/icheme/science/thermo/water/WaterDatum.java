package com.wormzjl.icheme.science.thermo.water;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The water datum shared by the column, the network's utility water and the duty ledger, and Watson's latent heat it
 * falls back on (moved from {@code V3WaterProperties}, operation for operation).
 */
public final class WaterDatum {
    private WaterDatum() {}

    /**
     * Watson's latent heat, J/mol: {@code dH_b ((1 - T/T_c)/(1 - T_b/T_c))^n}, zero at and above the critical
     * temperature. No range check below it: the caller has made one.
     */
    public static double watsonLatentHeat(WaterConstants w, double temperatureKelvin) {
        if (temperatureKelvin >= w.criticalTemperature()) return 0.0;
        double reduced = (1.0 - temperatureKelvin / w.criticalTemperature())
                / (1.0 - w.boilingTemperature() / w.criticalTemperature());
        return w.vaporizationEnthalpy() * DeterministicMath.pow(Math.max(0.0, reduced), w.watsonExponent());
    }

    /**
     * The offset, J/mol, that moves Region 1's enthalpy (its own datum, per kg times the molar mass) onto the steam's:
     * saturated liquid water at 298.15 K sits the record's latent heat ({@code datum_vaporization_enthalpy}, IF97's
     * 2441.706 kJ/kg) below the saturated steam (ideal gas plus the second virial's residual at the saturation pressure).
     * A record that declares no latent heat keeps the earlier datum: liquid at 298.15 K and 1 atm one Watson
     * vaporization enthalpy below the ideal-gas steam.
     */
    public static double liquidEnthalpyOffset(WaterConstants w) {
        var region = new Iapws97Region1.Liquid();
        if (Double.isNaN(w.datumVaporizationEnthalpy())) {
            Iapws97Region1.liquid(w.triplePoint(), 298.15, 101325, region);
            return SteamIdealGas.enthalpy(w, 298.15) - watsonLatentHeat(w, 298.15)
                    - region.specificEnthalpy() * w.molarMass();
        }
        double saturation = WagnerPruss.pressure(w, 298.15);
        Iapws97Region1.liquid(w.triplePoint(), 298.15, saturation, region);
        return SteamIdealGas.enthalpy(w, 298.15) + SteamVirial.residualEnthalpy(298.15, saturation) - w.datumVaporizationEnthalpy()
                - region.specificEnthalpy() * w.molarMass();
    }
}
