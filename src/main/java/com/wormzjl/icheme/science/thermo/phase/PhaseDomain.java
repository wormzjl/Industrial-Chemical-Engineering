package com.wormzjl.icheme.science.thermo.phase;

import java.util.List;
import java.util.Objects;

/**
 * The envelope of a package for the equilibrium engine: the package's data range as a whole, its {@code fluid_domain}
 * (the catalog's {@code fluidValidity}, the network's {@code FluidDomain.envelope()}). Leaving it never refuses a state
 * (owner rule of 2026-10-16): the engine searches the
 * numerical box ({@link com.wormzjl.icheme.science.thermo.NumericalBox}) and grades a converged answer outside the
 * envelope {@link EquilibriumResult.CoverageGrade#EXTRAPOLATED}. An open domain (for research and tests on a sub-basis,
 * like the open-domain kernels of the stability test) covers every state and must be declared as such.
 */
public final class PhaseDomain {
    private final String packageId;
    private final List<String> components;
    private final boolean open;
    private final double minimumT, maximumT, minimumP, maximumP;

    private PhaseDomain(String packageId, List<String> components, boolean open, double[] envelope) {
        this.packageId = Objects.requireNonNull(packageId, "packageId");
        this.components = List.copyOf(components);
        this.open = open;
        if (envelope.length != 4 || !(envelope[0] > 0.0) || !(envelope[1] > envelope[0]) || !(envelope[2] > 0.0)
                || !(envelope[3] > envelope[2]) || !Double.isFinite(envelope[1]) || !Double.isFinite(envelope[3])) {
            throw new IllegalArgumentException("An envelope is [Tmin, Tmax, Pmin, Pmax] with 0 < min < max, finite");
        }
        minimumT = envelope[0];
        maximumT = envelope[1];
        minimumP = envelope[2];
        maximumP = envelope[3];
    }

    /**
     * The envelope of package {@code packageId} over {@code components}, the network basis (the EOS components, water):
     * its {@code fluid_domain} hull, K and Pa, built by the caller that reads it from the catalog.
     */
    public static PhaseDomain of(String packageId, List<String> components, double minimumTemperature,
                                 double maximumTemperature, double minimumPressure, double maximumPressure) {
        return new PhaseDomain(packageId, components, false, new double[] {minimumTemperature, maximumTemperature,
                minimumPressure, maximumPressure});
    }

    /** A declared open domain: every positive finite state is covered. For research and test contracts only. */
    public static PhaseDomain open(String packageId, List<String> components) {
        return new PhaseDomain(packageId, components, true, new double[] {Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE,
                Double.MAX_VALUE});
    }

    public List<String> components() { return components; }

    /** The envelope {@code [Tmin, Tmax, Pmin, Pmax]}, a copy. */
    public double[] envelope() { return new double[] {minimumT, maximumT, minimumP, maximumP}; }

    /** Whether {@code (t, p)} lies in the envelope: outside it a converged answer is graded extrapolated. */
    public boolean covers(double temperature, double pressure) {
        return temperature >= minimumT && temperature <= maximumT && pressure >= minimumP && pressure <= maximumP;
    }
}
