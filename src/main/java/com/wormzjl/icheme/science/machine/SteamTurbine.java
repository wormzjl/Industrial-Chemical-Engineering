package com.wormzjl.icheme.science.machine;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A mechanical-drive steam turbine at process level (batch 2026-11-03-rotating-machinery, plan section 3.4): the
 * isentropic drop from the inlet's real {@code P/ρ}, a constant isentropic efficiency at every load (simplification S4,
 * no-load steam ignored), and the steam a shaft power needs. Its governor valve passes {@link #steamFor} the shaft's demand;
 * the exhaust takes {@code h_in − W/ṁ}, and the flash gives its temperature and wetness.
 *
 * @param ratedPower the shaft power at the rated steam conditions, W
 * @param efficiency η_is, the isentropic efficiency
 */
public record SteamTurbine(double ratedPower, double efficiency) {
    /** The overspeed trip, as a share of the rated speed (API 612: 110 % of the maximum continuous speed). */
    public static final double TRIP_SPEED = 1.10;
    /** The efficiencies of a single-stage and a multistage machine: 40 bar and 400 °C to 4 bar is 6.8 kg/kWh ideal, about
     * 15 kg/kWh in a single stage and 9 in a multistage machine (US DOE steam tip sheet 21). */
    static final double SINGLE_STAGE = .45, MULTISTAGE = .76;
    /** The rated powers, W, below which a design is single stage and above which it is multistage; between them the
     * efficiency runs in log power (API 611 builds single stages to about 3 MW). */
    static final double SINGLE_STAGE_UP_TO = 3e5, MULTISTAGE_FROM = 3e6;

    public SteamTurbine {
        if (!(ratedPower > 0) || !Double.isFinite(ratedPower) || !(efficiency > 0 && efficiency <= 1))
            throw new IllegalArgumentException("Invalid turbine rating: " + ratedPower + " W, " + efficiency);
    }

    /** A turbine rated {@code ratedPower} (W) with the {@link #typicalEfficiency} of its size. */
    public static SteamTurbine rated(double ratedPower) {
        return new SteamTurbine(ratedPower, typicalEfficiency(ratedPower));
    }

    /**
     * The isentropic enthalpy drop, J/kg, from {@code inletPressure} (Pa) and {@code inletDensity} (kg/m³) to
     * {@code exhaustPressure}, for heat capacity ratio {@code k}:
     * {@code k/(k − 1) (P_in/ρ_in)(1 − (P_out/P_in)^((k−1)/k))}. 524 kJ/kg from 40 bar and 400 °C to 4 bar at k = 1.3, where
     * the steam tables give 528. None when the exhaust stands at or above the inlet.
     */
    public static double isentropicDrop(double inletPressure, double inletDensity, double exhaustPressure, double k) {
        if (!(k > 1)) throw new IllegalArgumentException("Invalid heat capacity ratio " + k);
        if (!(exhaustPressure < inletPressure)) return 0;
        double exponent = (k - 1) / k;
        return inletPressure / inletDensity / exponent * (1 - DeterministicMath.pow(Math.max(0, exhaustPressure) / inletPressure, exponent));
    }

    /** The shaft power, W, of {@code massFlow} (kg/s) across isentropic drop {@code drop} (J/kg): {@code ṁ η_is Δh_is}. */
    public double work(double massFlow, double drop) {
        return Math.max(0, massFlow) * efficiency * Math.max(0, drop);
    }

    /** The steam flow, kg/s, that gives shaft power {@code power} (W) across {@code drop}; infinite with no drop. */
    public double steamFor(double power, double drop) {
        return drop > 0 ? Math.max(0, power) / (efficiency * drop) : Double.POSITIVE_INFINITY;
    }

    /** The steam rate, kg/kWh, across {@code drop}: 10.6 at 65 % for 40 bar and 400 °C to 4 bar. */
    public double steamRate(double drop) {
        return steamFor(3.6e6, drop);
    }

    /** The isentropic efficiency of a turbine rated {@code ratedPower} (W): {@link #SINGLE_STAGE} up to
     * {@link #SINGLE_STAGE_UP_TO}, {@link #MULTISTAGE} from {@link #MULTISTAGE_FROM}, in log power between (0.61 at 1 MW). */
    public static double typicalEfficiency(double ratedPower) {
        double x = DeterministicMath.log(ratedPower / SINGLE_STAGE_UP_TO) / DeterministicMath.log(MULTISTAGE_FROM / SINGLE_STAGE_UP_TO);
        return SINGLE_STAGE + (MULTISTAGE - SINGLE_STAGE) * Math.min(1, Math.max(0, x));
    }
}
