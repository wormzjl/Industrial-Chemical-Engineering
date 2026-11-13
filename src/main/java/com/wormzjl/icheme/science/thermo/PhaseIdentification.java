package com.wormzjl.icheme.science.thermo;

/**
 * The phase identification parameter of Venkatarathnam and Oellrich (2011, Fluid Phase Equilibria 301, 225-233) on
 * the untranslated PR78 cubic: a liquid-like or vapour-like label for one fluid state that needs no saturation
 * property and no mixture critical point. {@code PIP = v [ (d2P/dT dv) / (dP/dT)_v - (d2P/dv2) / (dP/dv)_T ]}, which
 * is {@code d ln[(dP/dT)_v / -(dP/dv)_T] / d ln v} at constant temperature: the ideal gas has {@code PIP = 1}, a dense
 * liquid tends to {@code v/(v - b) > 1}, a dilute real gas is {@code 1 + (B - T dB/dT)/v} with the cubic's second
 * virial coefficient {@code B = b - a/(R T)}, below one while {@code B < T dB/dT}; so {@code PIP > 1} is liquid-like
 * and {@code PIP < 1} vapour-like.
 *
 * <p>The label rule: liquid-like when {@code PIP > max(1, Z)}, {@code Z = P v/(R T)} of the same untranslated root;
 * vapour-like otherwise. The bare {@code PIP > 1} misreads a hot gas: {@code B - T dB/dT} turns positive above the
 * Boyle temperature and a dilute gas then has a parameter just above one (nitrogen above about 620 K: 1.000079 at 700 K
 * and 0.1 MPa), but such a gas has {@code Z = 1 + B/v} above one and above its parameter, since to first order
 * {@code PIP - Z = -T (dB/dT)/v < 0} wherever the alpha function decreases (to 1388 K for the bundled components, above
 * every fluid domain's 1200 K). A liquid-like state has its parameter well above one and above {@code Z}: every liquid
 * and dense supercritical state with {@code Z < 1} (nitrogen at 130 K and 6 MPa: {@code Z} 0.39, {@code PIP} 4.97), and
 * a compressed liquid whose {@code Z} has reached one ({@code crude_pc12} at 293.15 K and 2 MPa: {@code Z} 1.15,
 * {@code PIP} about 92), which a bare {@code Z < 1} guard would call vapour. Measured over the one-root states with
 * {@code PIP > 1} and {@code Z >= 1} of every bundled component inside their fluid domains, those less dense than the
 * cubic's critical volume ({@code v > 3.95 b}) all have {@code PIP < Z} and the denser ones {@code PIP - Z > 91}. The
 * three-root case does not use this rule: there the stability root decides.</p>
 *
 * <p>For {@code P = R T/(v - b) - a/D}, {@code D = v^2 + 2 b v - b^2}, the four derivatives are the ones
 * {@code TranslatedCubic.fill} forms for its volumetric block:</p>
 * <pre>
 *   dP/dv    = -R T/(v - b)^2 + 2 a (v + b)/D^2
 *   dP/dT    =  R/(v - b) - (da/dT)/D
 *   d2P/dv2  =  2 R T/(v - b)^3 + 2 a/D^2 - 8 a (v + b)^2/D^3
 *   d2P/dTdv = -R/(v - b)^2 + 2 (da/dT)(v + b)/D^2
 * </pre>
 * <p>so the parameter needs the mixture's {@code a}, {@code b}, {@code da/dT} and the root's volume only, evaluated on
 * the untranslated volume {@code v = Z R T/P} (the label belongs to the root, not to the density correction). It is
 * undefined where {@code (dP/dT)_v = 0} and at a mechanically unstable root; at a pure-component critical point the
 * ratio has a finite limit but separates nothing, which is why the critical band (the engine's {@code FluidTpEquilibrium}) grades
 * such states research-only whatever their label.</p>
 */
public final class PhaseIdentification {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    private PhaseIdentification() {}

    /**
     * The parameter at molar volume {@code v} of the untranslated cubic with mixture parameters {@code a}, {@code b} and
     * {@code da/dT}; {@code NaN} where it is undefined.
     */
    public static double parameter(double temperatureKelvin, double volume, double a, double b, double dadt) {
        double dpdv = PengRobinsonKernel.pressureVolumeDerivative(temperatureKelvin, volume, a, b);
        double dpdt = PengRobinsonKernel.pressureTemperatureDerivative(volume, b, dadt);
        double dpdvv = PengRobinsonKernel.pressureSecondVolumeDerivative(temperatureKelvin, volume, a, b);
        double dpdtv = PengRobinsonKernel.pressureTemperatureVolumeDerivative(volume, b, dadt);
        if (!(dpdv < 0.0) || dpdt == 0.0) return Double.NaN;
        return volume * (dpdtv / dpdt - dpdvv / dpdv);
    }

    /** The parameter of a kernel evaluation at its temperature and pressure (its root's untranslated volume). */
    public static double parameter(PengRobinsonKernel.Evaluation evaluation, double temperatureKelvin, double pressurePascal) {
        double volume = evaluation.compressibility() * R * temperatureKelvin / pressurePascal;
        return parameter(temperatureKelvin, volume, evaluation.aMix(), evaluation.bMix(), evaluation.daMixDt());
    }

    /**
     * The parameter's own side of one: {@code PIP > 1}. Not a label by itself (a hot dilute gas has a parameter just
     * above one): label a root with {@link #liquidLike(double, double)}. An undefined parameter is not above one.
     */
    public static boolean liquidLike(double parameter) { return parameter > 1.0; }

    /** The parameter's own side of one: {@code PIP <= 1}; see {@link #liquidLike(double)}. Undefined is neither side. */
    public static boolean vapourLike(double parameter) { return parameter <= 1.0; }

    /**
     * The label of one root (class comment, P3 WP7c): liquid-like when its parameter exceeds both one and its
     * compressibility factor {@code Z = P v/(R T)} on the untranslated cubic. An undefined parameter is not liquid-like.
     */
    public static boolean liquidLike(double parameter, double compressibility) {
        return parameter > 1.0 && parameter > compressibility;
    }

    /**
     * Vapour-like: a defined parameter at most {@code max(1, Z)} (the ideal gas, exactly 1, is vapour; a hot gas whose
     * parameter is just above one lies below its {@code Z}). An undefined parameter is not vapour-like.
     */
    public static boolean vapourLike(double parameter, double compressibility) {
        return parameter <= 1.0 || parameter <= compressibility;
    }

    /** {@link #liquidLike(double, double)} of a kernel evaluation's root at its temperature and pressure. */
    public static boolean liquidLike(PengRobinsonKernel.Evaluation evaluation, double temperatureKelvin, double pressurePascal) {
        return liquidLike(parameter(evaluation, temperatureKelvin, pressurePascal), evaluation.compressibility());
    }

    /** {@link #vapourLike(double, double)} of a kernel evaluation's root at its temperature and pressure. */
    public static boolean vapourLike(PengRobinsonKernel.Evaluation evaluation, double temperatureKelvin, double pressurePascal) {
        return vapourLike(parameter(evaluation, temperatureKelvin, pressurePascal), evaluation.compressibility());
    }

    /**
     * The untranslated PR78 cubic's critical molar volume in units of its co-volume, {@code v_c / b = Z_c / Omega_b =
     * 3.9513730355914}: the kernel's own vapour-branch constant on {@code v/b} (the retired network root heuristic
     * {@code a/(R T b) < 5.8773599486044 || v/b > 3.9513730355914}, and the P1 stability probe's single-root branch
     * test). A root less dense than this is on the gas side of the cubic's critical volume.
     */
    public static final double VAPOUR_BRANCH_VOLUME_RATIO = 3.9513730355914;

    /**
     * Whether a root is denser than the cubic's critical volume: {@code v < }{@link #VAPOUR_BRANCH_VOLUME_RATIO}
     * {@code b} with the untranslated {@code v = Z R T / P} and the mixture co-volume {@code b} of the evaluation.
     */
    public static boolean denserThanCriticalVolume(PengRobinsonKernel.Evaluation evaluation, double temperatureKelvin, double pressurePascal) {
        double volume = evaluation.compressibility() * R * temperatureKelvin / pressurePascal;
        return volume < VAPOUR_BRANCH_VOLUME_RATIO * evaluation.bMix();
    }

    /**
     * The test each product of a liquid-liquid split must pass (P3 WP11): liquid-like by the label rule
     * ({@link #liquidLike(PengRobinsonKernel.Evaluation, double, double)}, {@code PIP > max(1, Z)}) <b>and</b> denser
     * than the cubic's critical volume ({@link #denserThanCriticalVolume}). The parameter alone calls the lighter phase
     * of some high-pressure splits liquid-like by a small margin while it is a dense gas less dense than the critical
     * volume (N2/C2H6 at 195 K and 10 MPa: {@code PIP} 1.048, {@code Z} 0.694, {@code v/b} 4.38; CH4/CO2 at 245 K and
     * 8 MPa: {@code PIP} 1.203, {@code v/b} 3.96); with the volume guard such a split is vapour-liquid again, while the
     * liquid-liquid splits of the type III and CO2-in-methane regions (lighter liquid {@code v/b} 1.09 to 1.1) keep
     * their label.
     */
    public static boolean denseLiquidLike(PengRobinsonKernel.Evaluation evaluation, double temperatureKelvin, double pressurePascal) {
        return liquidLike(evaluation, temperatureKelvin, pressurePascal)
                && denserThanCriticalVolume(evaluation, temperatureKelvin, pressurePascal);
    }
}
