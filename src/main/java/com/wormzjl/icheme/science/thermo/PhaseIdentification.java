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
 * <p>With {@code Z < 1} the label and the cubic's critical volume part on a supercritical isotherm: the label calls a
 * root liquid-like at reduced densities {@code v_c/v} from about 0.6 to 1, where {@code PIP} is just above one (1.000 to
 * 1.28). Measured (batch 2026-11-15-supercritical-fluids, S2_NETWORK_REVIEW.md) over pure nitrogen, methane and carbon
 * dioxide at {@code Tr} 1.0 to 3.0 and {@code Pr} 0.1 to 9: 2144 of 11070 states, from {@code Tr} 1.15 and {@code Pr}
 * 1.8 up; inside the data ranges 11, all nitrogen at 145 to 177 K and 6.1 to 9.8 MPa. At ambient temperature the label
 * first says liquid at 16.4 MPa for nitrogen ({@code v_c/v} 0.61), 15.1 MPa for methane and 9.79 MPa for carbon dioxide
 * at 319 K, the critical volume at 28.9, 18.4 and 9.85 MPa. So the fluid network does not place one dense fluid by this
 * label: a one-fluid answer on a supercritical isotherm goes to the liquid slot iff {@code v_c/v >= 1} (owner decision
 * O2 A, S2c; {@code FluidThermodynamics.placed}). The engine's own label of such a root, and the column's and the
 * simulator's, stay this rule.</p>
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
     * The kernel's {@code Omega_a / Omega_b = 0.45724 / 0.07780} ({@link PengRobinsonKernel}'s critical attraction and
     * co-volume constants), the value of {@code a / (b R T)} of a pure component at its critical temperature, where its
     * alpha function is one: below it ({@link PhaseTraits#supercriticalIsotherm()}) a pure component is above its
     * {@code Tc}. The exact PR constants give {@code 0.457235528921 / 0.0777960739039 = 5.8773599486044}, the ratio at
     * which the cubic's isotherm first has three roots at some pressure; the kernel's rounded constants give
     * 5.8771208226, 4.1e-5 lower, so every isotherm below this one has one root at every pressure, and a pure
     * component's {@code T > Tc} holds exactly.
     */
    public static final double SUPERCRITICAL_ATTRACTION_RATIO = 0.45724 / 0.07780;

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

    // The label rules on a phase's traits (I-1). The overloads above stay what the engine's label sites call: they take
    // the evaluation in hand and allocate nothing per iterate.

    /** A label the rules give one fluid phase; the constants are named as the engine's fluid phase kinds. */
    public enum Label { VAPOR, LIQUID, FLUID }

    /**
     * {@link #liquidLike(double, double)} of a root's traits: the same comparison on the same two doubles as
     * {@link #liquidLike(PengRobinsonKernel.Evaluation, double, double)} of the evaluation the traits were formed from.
     */
    public static boolean liquidLike(PhaseTraits traits) {
        return liquidLike(traits.identificationParameter(), traits.compressibility());
    }

    /**
     * {@link #denseLiquidLike(PengRobinsonKernel.Evaluation, double, double)} on a root's traits: liquid-like and
     * {@code v/b <} {@link #VAPOUR_BRANCH_VOLUME_RATIO}. The evaluation's form compares {@code v} with
     * {@code VAPOUR_BRANCH_VOLUME_RATIO * b}; the two roundings can part only where {@code v/b} is within an ulp or two of
     * the constant.
     */
    public static boolean denseLiquidLike(PhaseTraits traits) {
        return liquidLike(traits) && traits.volumeRatio() < VAPOUR_BRANCH_VOLUME_RATIO;
    }

    /**
     * The label of a single fluid phase (the engine's single-phase rule): on three physical roots the requested root's
     * ({@code VAPOR} for the vapour root, {@code LIQUID} otherwise); {@code FLUID} for a pure component above its own
     * critical temperature and pressure; otherwise by the parameter, {@code LIQUID} where {@link #liquidLike(PhaseTraits)},
     * {@code VAPOR} where not, {@code FLUID} where the parameter is undefined.
     *
     * @param root the root the phase was evaluated on
     * @param pureSupercritical whether the phase is one component above its critical temperature and pressure (the
     *        caller reads the critical constants)
     */
    public static Label singleLabel(PhaseTraits traits, PengRobinsonKernel.Root root, boolean pureSupercritical) {
        if (traits.physicalRootCount() > 1) return root == PengRobinsonKernel.Root.VAPOR ? Label.VAPOR : Label.LIQUID;
        if (pureSupercritical) return Label.FLUID;
        double parameter = traits.identificationParameter();
        return Double.isNaN(parameter) ? Label.FLUID : liquidLike(parameter, traits.compressibility()) ? Label.LIQUID : Label.VAPOR;
    }
}
