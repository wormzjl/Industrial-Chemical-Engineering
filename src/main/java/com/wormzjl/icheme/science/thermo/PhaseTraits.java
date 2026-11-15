package com.wormzjl.icheme.science.thermo;

/**
 * The numbers that say what kind of phase one evaluated root of the untranslated PR78 cubic is (I-1, THERMO_CORE_REVIEW
 * X36): the label rules of {@link PhaseIdentification} read them, and a consumer may read them in place of a label (a
 * continuous trait does not flip on a line through a continuous region, as a liquid or vapour label does above the
 * critical point).
 *
 * <p>Each number is the same double the label rules read today: {@link #of(PengRobinsonKernel.Evaluation, double, double)}
 * forms the untranslated volume {@code v = Z R T / P} by the expression of
 * {@link PhaseIdentification#parameter(PengRobinsonKernel.Evaluation, double, double)} and the parameter by
 * {@link PhaseIdentification#parameter(double, double, double, double, double)} on it. No kernel call: the evaluation
 * already holds {@code Z}, {@code a}, {@code b} and {@code da/dT}.</p>
 *
 * @param physicalRootCount physical roots of the cubic at the state and composition: 1, or 3 where both roots exist
 * @param compressibility {@code Z = P v / (R T)} of the evaluated root on the untranslated cubic
 * @param volumeRatio the untranslated molar volume over the mixture co-volume, {@code v / b} (the cubic's critical volume
 *        is {@link PhaseIdentification#VAPOUR_BRANCH_VOLUME_RATIO})
 * @param identificationParameter the phase identification parameter of the root; {@code NaN} where it is undefined
 */
public record PhaseTraits(int physicalRootCount, double compressibility, double volumeRatio, double identificationParameter) {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    /** The traits of a kernel evaluation's root at its temperature and pressure. */
    public static PhaseTraits of(PengRobinsonKernel.Evaluation evaluation, double temperatureKelvin, double pressurePascal) {
        return of(evaluation.physicalRootCount(), evaluation.compressibility(), evaluation.aMix(), evaluation.bMix(),
                evaluation.daMixDt(), temperatureKelvin, pressurePascal);
    }

    /**
     * The traits of a root from the numbers a kernel evaluation holds: its root count, its untranslated compressibility
     * factor and the mixture's {@code a}, {@code b}, {@code da/dT}, at the temperature and pressure it was evaluated at
     * (a phase state keeps these numbers of the evaluation that filled it).
     */
    public static PhaseTraits of(int physicalRootCount, double compressibility, double a, double b, double dadt,
                                 double temperatureKelvin, double pressurePascal) {
        double volume = compressibility * R * temperatureKelvin / pressurePascal;
        return new PhaseTraits(physicalRootCount, compressibility, volume / b,
                PhaseIdentification.parameter(temperatureKelvin, volume, a, b, dadt));
    }
}
