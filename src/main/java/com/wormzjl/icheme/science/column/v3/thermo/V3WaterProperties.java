package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.WaterRecords;
import com.wormzjl.icheme.science.thermo.water.Iapws97Region1;
import com.wormzjl.icheme.science.thermo.water.SteamIdealGas;
import com.wormzjl.icheme.science.thermo.water.SteamVirial;
import com.wormzjl.icheme.science.thermo.water.WagnerPruss;
import com.wormzjl.icheme.science.thermo.water.WaterConstants;
import com.wormzjl.icheme.science.thermo.water.WaterDatum;

/**
 * The water of the column and of the fluid network: one model (decision E30 of batch 2026-11-04-open-thermo-data).
 *
 * <p>The saturation-pressure relation is the IAPWS Wagner--Pruss auxiliary equation. The steam is the network's
 * second-virial gas: the ideal-gas enthalpy integrates the water record's Shomate-form heat capacity (a fit to the
 * IAPWS-95 ideal-gas Cp), and {@link SteamVirial} adds IF97 Region 2's residual at the steam's partial pressure. The
 * liquid is IF97 Region 1 at the pressure ({@link Iapws97Region1#liquid}), on the water datum: saturated liquid water at 298.15 K
 * sits the record's latent heat there (IAPWS's) below the saturated steam ({@link #liquidEnthalpyOffset}). The datum
 * cancels only where water does not change phase across a boundary; steam in and liquid water out books it (W3b: the
 * Watson latent heat it used before put liquid water 564.5 J/mol under the network's cubic water). Before E30 the column priced steam as an ideal gas and liquid water
 * as the steam less the Watson enthalpy at every temperature, 136 J/mol (533 K, 2.5 bar) and 285 J/mol (332 K) off the
 * network's.</p>
 *
 * <p>Every correlation exists twice: once resolving the water data from the calculation context,
 * which is what V3 and the legacy callers use, and once taking that data explicitly. They compute
 * the same expression from the same numbers; the explicit form is for a caller that resolves the
 * record once and then evaluates in a loop, because the context form costs a {@code ThreadLocal}
 * lookup and two immutable-map probes per coefficient access.</p>
 *
 * <p>Every formula is the water module's ({@code science.thermo.water}, F2 of batch 2026-11-13-codebase-review); this
 * class keeps the column's API, its range checks and its liquid scratch, and delegates.</p>
 */
public final class V3WaterProperties {


    public static String revision() { return data().revision(); }
    public static double molarMass() { return data().molarMass(); }
    public static double triplePoint() { return data().triplePoint(); }
    public static double criticalTemperature() { return data().criticalTemperature(); }
    public static double maximumTemperature() { return data().maximumTemperature(); }
    private V3WaterProperties() {}

    private static com.wormzjl.icheme.science.material.MaterialCatalog.Water data() {
        return com.wormzjl.icheme.science.material.MaterialRuntime.water();
    }


    /**
     * Saturation pressure in Pa at any positive temperature up to the critical point: {@link #wagnerPruss}, continued
     * below the triple point as the network's (F3 of batch 2026-11-13-codebase-review). Above the critical temperature
     * there is no saturation, and it refuses.
     */
    public static double saturationPressurePascal(double temperatureKelvin) {
        return saturationPressurePascal(data(), temperatureKelvin);
    }

    public static double saturationPressurePascal(MaterialCatalog.Water water, double temperatureKelvin) {
        requireSaturationTemperature(water, temperatureKelvin);
        return wagnerPruss(water, temperatureKelvin);
    }

    /**
     * The Wagner--Pruss equation itself at any positive temperature up to the critical point: below the triple point it
     * is the supercooled liquid's saturation pressure (1.10 times the ice sublimation pressure at 263 K, the measured
     * ratio). The fluid network and the column both continue there (owner rule of 2026-10-16; F3).
     */
    public static double wagnerPruss(MaterialCatalog.Water water, double temperatureKelvin) {
        return WagnerPruss.pressure(constants(water), temperatureKelvin);
    }

    /**
     * {@code d ln(P_sat)/dT} in 1/K, differentiating the same Wagner--Pruss auxiliary equation.
     *
     * <p>The log is what the wet-tray saturation row carries, and it is also what stays finite here: the
     * pressure itself spans orders of magnitude over the correlation's range, while its logarithmic slope is
     * an order-one number everywhere including the critical point, where {@code theta} vanishes and only the
     * linear coefficient survives. The value function's exact-critical shortcut needs no counterpart, because
     * the expression below is already the limit that shortcut stands for.</p>
     */
    public static double dLogSaturationPressureDT(double temperatureKelvin) {
        return dLogSaturationPressureDT(data(), temperatureKelvin);
    }

    public static double dLogSaturationPressureDT(MaterialCatalog.Water water, double temperatureKelvin) {
        requireSaturationTemperature(water, temperatureKelvin);
        return WagnerPruss.logSlope(constants(water), temperatureKelvin);
    }

    /** Inverts {@link #saturationPressurePascal(double)} by bounded bisection. */
    public static double saturationTemperatureKelvin(double pressurePascal) {
        return saturationTemperatureKelvin(data(), pressurePascal);
    }

    public static double saturationTemperatureKelvin(MaterialCatalog.Water water, double pressurePascal) {
        return WagnerPruss.temperature(constants(water), pressurePascal);
    }

    /**
     * Ideal-gas water-vapor molar enthalpy in J/mol: the record's Shomate fit inside its range (273.16 K through 900 K),
     * held at its end heat capacity past either end, so value and slope are continuous there (owner rule of 2026-10-16,
     * the network's {@code FluidThermodynamics.vaporWaterEnthalpy}; F3 of batch 2026-11-13-codebase-review).
     */
    public static double vaporMolarEnthalpy(double temperatureKelvin) {
        return vaporMolarEnthalpy(data(), temperatureKelvin);
    }

    public static double vaporMolarEnthalpy(MaterialCatalog.Water water, double temperatureKelvin) {
        return SteamIdealGas.enthalpy(constants(water), temperatureKelvin);
    }

    /** Whether {@code temperatureKelvin} lies past the steam's data, where {@link #vaporMolarEnthalpy} is continued. */
    public static boolean steamContinued(MaterialCatalog.Water water, double temperatureKelvin) {
        return SteamIdealGas.continued(constants(water), temperatureKelvin);
    }

    /**
     * The water-vapour heat capacity in J/(mol K): the Shomate polynomial the enthalpy integrates, held at its end value
     * past the steam's data (F3).
     *
     * <p>Differentiating the integral rather than quoting the Shomate coefficients again keeps one set of
     * numbers in the file, so the capacity cannot drift away from the enthalpy it belongs to.</p>
     */
    public static double dVaporMolarEnthalpyDT(double temperatureKelvin) {
        return dVaporMolarEnthalpyDT(data(), temperatureKelvin);
    }

    public static double dVaporMolarEnthalpyDT(MaterialCatalog.Water water, double temperatureKelvin) {
        return SteamIdealGas.heatCapacity(constants(water), temperatureKelvin);
    }

    public static double vaporizationEnthalpy(double temperatureKelvin) {
        return vaporizationEnthalpy(data(), temperatureKelvin);
    }

    public static double vaporizationEnthalpy(MaterialCatalog.Water water, double temperatureKelvin) {
        requireEnthalpyTemperature(water, temperatureKelvin);
        return WaterDatum.watsonLatentHeat(constants(water), temperatureKelvin);
    }

    /**
     * Steam at its partial pressure {@code p}, J/mol: the ideal-gas steam ({@link #vaporMolarEnthalpy}) plus the
     * second virial's residual {@code p (B - T dB/dT)} ({@link SteamVirial#residualEnthalpy}), the network's steam.
     */
    public static double steamMolarEnthalpy(double temperatureKelvin, double partialPressurePascal) {
        return vaporMolarEnthalpy(temperatureKelvin) + SteamVirial.residualEnthalpy(temperatureKelvin, partialPressurePascal);
    }

    /** {@code dh/dT} of {@link #steamMolarEnthalpy} at constant partial pressure, J/(mol K). */
    public static double dSteamMolarEnthalpyDT(double temperatureKelvin, double partialPressurePascal) {
        return dVaporMolarEnthalpyDT(temperatureKelvin)
                + SteamVirial.residualHeatCapacity(temperatureKelvin, partialPressurePascal);
    }

    /**
     * The enthalpy rate of {@code water} mol/s of steam in a gas with {@code hydrocarbon} mol/s of hydrocarbon vapour at
     * total pressure {@code pressurePascal}, W: the steam at its Dalton partial pressure {@code P W / (V + W)}. Zero
     * without steam.
     */
    public static double steamEnthalpyRate(double temperatureKelvin, double pressurePascal, double water, double hydrocarbon) {
        if (water == 0.0) return 0.0;
        return water * steamMolarEnthalpy(temperatureKelvin, pressurePascal * water / (hydrocarbon + water));
    }

    /** {@code d/dW} of {@link #steamEnthalpyRate} at constant T, P and V: {@code h + W (dh/dp) P V / (V + W)^2}. */
    public static double steamEnthalpyRateByWater(
            double temperatureKelvin, double pressurePascal, double water, double hydrocarbon) {
        double gas = hydrocarbon + water;
        return steamMolarEnthalpy(temperatureKelvin, pressurePascal * water / gas)
                + water * steamPressureSlope(temperatureKelvin) * pressurePascal * hydrocarbon / (gas * gas);
    }

    /** {@code d/dV} of {@link #steamEnthalpyRate} at constant T, P and W: {@code -W (dh/dp) P W / (V + W)^2}. */
    public static double steamEnthalpyRateByHydrocarbon(
            double temperatureKelvin, double pressurePascal, double water, double hydrocarbon) {
        if (water == 0.0) return 0.0;
        double gas = hydrocarbon + water;
        return -water * steamPressureSlope(temperatureKelvin) * pressurePascal * water / (gas * gas);
    }

    /** {@code d/dT} of {@link #steamEnthalpyRate} at constant P, W and V. */
    public static double steamEnthalpyRateByTemperature(
            double temperatureKelvin, double pressurePascal, double water, double hydrocarbon) {
        if (water == 0.0) return 0.0;
        return water * dSteamMolarEnthalpyDT(temperatureKelvin, pressurePascal * water / (hydrocarbon + water));
    }

    /** {@code dh/dp} of the steam at constant T, J/(mol Pa): {@code B - T dB/dT}; the residual is linear in p. */
    private static double steamPressureSlope(double temperatureKelvin) {
        return SteamVirial.residualEnthalpy(temperatureKelvin, 1.0);
    }

    /**
     * Liquid water at {@code (T, p)}, J/mol: the network's, IF97 Region 1 at the pressure and its continuation past
     * its range ({@link Iapws97Region1#liquid}), on the water datum ({@link #liquidEnthalpyOffset}).
     */
    public static double liquidMolarEnthalpy(double temperatureKelvin, double pressurePascal) {
        return liquidMolarEnthalpy(data(), temperatureKelvin, pressurePascal);
    }

    public static double liquidMolarEnthalpy(MaterialCatalog.Water water, double temperatureKelvin, double pressurePascal) {
        LiquidScratch scratch = LIQUID.get();
        Iapws97Region1.liquid(water.triplePoint(), temperatureKelvin, pressurePascal, scratch.region);
        return scratch.region.specificEnthalpy() * water.molarMass() + scratch.offset(water);
    }

    /** {@code dh/dT} of {@link #liquidMolarEnthalpy} at constant pressure, J/(mol K): Region 1's c_p, held past its range. */
    public static double dLiquidMolarEnthalpyDT(double temperatureKelvin, double pressurePascal) {
        MaterialCatalog.Water water = data();
        LiquidScratch scratch = LIQUID.get();
        Iapws97Region1.liquid(water.triplePoint(), temperatureKelvin, pressurePascal, scratch.region);
        return scratch.region.specificHeatCapacity() * water.molarMass();
    }

    /**
     * The water datum, J/mol: what moves Region 1's enthalpy (its own datum) onto the steam's, so that saturated liquid
     * water at 298.15 K sits the record's latent heat ({@code datum_vaporization_enthalpy}, IF97's 2441.706 kJ/kg) below
     * the saturated steam ({@link #steamMolarEnthalpy} at the saturation pressure). The network's utility water
     * ({@code FluidThermodynamics.waterLiquid}) and the column's take this one offset. A record that declares no latent
     * heat keeps the earlier datum: liquid at 298.15 K and 1 atm one Watson vaporization enthalpy below the ideal-gas
     * steam.
     */
    public static double liquidEnthalpyOffset(MaterialCatalog.Water water) {
        return WaterDatum.liquidEnthalpyOffset(constants(water));
    }

    /**
     * Per-thread Region 1 scratch, the last water record's datum and its numbers for the water module, so a residual
     * evaluation allocates nothing.
     */
    private static final ThreadLocal<LiquidScratch> LIQUID = ThreadLocal.withInitial(LiquidScratch::new);

    /** {@code water}'s numbers, held per thread for the last record this thread evaluated (the column's hot path). */
    private static WaterConstants constants(MaterialCatalog.Water water) {
        return LIQUID.get().constants(water);
    }

    private static final class LiquidScratch {
        private final Iapws97Region1.Liquid region = new Iapws97Region1.Liquid();
        private MaterialCatalog.Water water;
        private double offset;
        private MaterialCatalog.Water constantsOf;
        private WaterConstants constants;

        private WaterConstants constants(MaterialCatalog.Water record) {
            if (record != constantsOf) {
                constants = WaterRecords.constants(record);
                constantsOf = record;
            }
            return constants;
        }

        private double offset(MaterialCatalog.Water record) {
            if (record != water) {
                offset = liquidEnthalpyOffset(record);
                water = record;
            }
            return offset;
        }
    }

    /** Positive and at most critical: Wagner-Pruss continues below the triple point, and nothing saturates above Tc. */
    private static void requireSaturationTemperature(MaterialCatalog.Water water, double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || !(temperatureKelvin > 0.0)
                || temperatureKelvin > water.criticalTemperature()) {
            throw new IllegalArgumentException("Water saturation temperature is outside the correlation envelope");
        }
    }

    private static void requireEnthalpyTemperature(MaterialCatalog.Water water, double temperatureKelvin) {
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < water.triplePoint()
                || temperatureKelvin > water.maximumTemperature()) {
            throw new IllegalArgumentException("Water enthalpy temperature is outside the correlation envelope");
        }
    }
}
