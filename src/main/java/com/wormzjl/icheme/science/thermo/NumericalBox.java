package com.wormzjl.icheme.science.thermo;

/**
 * The one numerical box of the thermodynamics, 10 to 6000 K and 1 Pa to 1 GPa (owner decision of 2026-10-16, plan
 * {@code RANGE_EXTRAPOLATION_PLAN} section 4, option A of decision 8). Leaving a property's data range never holds a
 * state: every model continues past its data. The box is where the mathematics stops being trusted at all, far from
 * play (liquid nitrogen at 63 K is six times its floor, the reference spines reach 6000 K, 1 GPa is a hundred times a
 * package envelope's 10 MPa): a trial state outside it is refused with a {@link ThermoRefusal}, so a
 * Newton step or a substep backtracks, and the equilibrium engine's searches stay inside it. A committed state outside
 * it would be a solver defect, a numerical failure, never a data-range hold.
 *
 * <p>The box bounds every temperature and pressure a model reads, a component's partial pressure among them (the
 * hydrocarbon partial pressure a network vapour is evaluated at, an unknown of its own): up to the same 1 GPa, and down
 * to {@link #MINIMUM_PARTIAL_PRESSURE}, the partial pressure's own floor, since a trace in a gas that is nearly all steam
 * stands far below the 1 Pa of a state (1e-6 of a gas at 1 bar: 0.1 Pa).
 */
public final class NumericalBox {
    public static final double MINIMUM_TEMPERATURE = 10.0;
    public static final double MAXIMUM_TEMPERATURE = 6000.0;
    public static final double MINIMUM_PRESSURE = 1.0;
    public static final double MAXIMUM_PRESSURE = 1.0e9;
    /** The floor of a partial pressure (see the class comment); its ceiling is {@link #MAXIMUM_PRESSURE}. */
    public static final double MINIMUM_PARTIAL_PRESSURE = 1.0e-6;

    /** The fixed end of a refused state's message: the box. */
    private static final String OUTSIDE_STATE = " Pa lies outside the numerical box " + MINIMUM_TEMPERATURE + ".." + MAXIMUM_TEMPERATURE
            + " K, " + MINIMUM_PRESSURE + ".." + MAXIMUM_PRESSURE + " Pa";
    /** The fixed end of a refused partial pressure's message. */
    private static final String OUTSIDE_PARTIAL = " Pa lies outside the numerical box " + MINIMUM_PARTIAL_PRESSURE + ".." + MAXIMUM_PRESSURE + " Pa";

    private NumericalBox() {}

    /** Whether {@code (t, p)} lies in the box (a non-finite value does not). */
    public static boolean contains(double temperatureKelvin, double pressurePascal) {
        return temperatureKelvin >= MINIMUM_TEMPERATURE && temperatureKelvin <= MAXIMUM_TEMPERATURE
                && pressurePascal >= MINIMUM_PRESSURE && pressurePascal <= MAXIMUM_PRESSURE;
    }

    /** Refuses a state outside the box: a rejected trial. */
    public static void require(double temperatureKelvin, double pressurePascal) {
        if (!contains(temperatureKelvin, pressurePascal)) {
            throw new ThermoRefusal("State ", temperatureKelvin, " K, ", pressurePascal, OUTSIDE_STATE);
        }
    }

    /** Whether a partial pressure lies in the box, {@link #MINIMUM_PARTIAL_PRESSURE} to {@link #MAXIMUM_PRESSURE} (a non-finite value does not). */
    public static boolean containsPartialPressure(double pressurePascal) {
        return pressurePascal >= MINIMUM_PARTIAL_PRESSURE && pressurePascal <= MAXIMUM_PRESSURE;
    }

    /** Refuses a partial pressure outside the box: a rejected trial, as for a state. */
    public static void requirePartialPressure(double pressurePascal) {
        if (!containsPartialPressure(pressurePascal)) {
            throw new ThermoRefusal("Partial pressure ", pressurePascal, OUTSIDE_PARTIAL);
        }
    }
}
