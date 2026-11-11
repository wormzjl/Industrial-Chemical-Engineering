package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;

/**
 * Test bridge to the network equation of state's own evaluation of one hydrocarbon phase
 * ({@link TranslatedPengRobinson#evaluateValues}, the values {@link HydrocarbonModel#phase} states, with no network domain
 * check), for tests outside this package. Each call evaluates on a fresh workspace, so the buffer it returns belongs to
 * the caller and is not refilled by the next call.
 */
public final class HydrocarbonValues {
    private HydrocarbonValues() {}

    public static TranslatedPengRobinson.Values of(HydrocarbonModel model, double temperature, double pressure, double[] amounts,
                                                   PengRobinsonKernel.Root root) {
        // W3b: the conserved basis (compounds, then water; compounds alone pass with no water) to the kernel's.
        return model.translated().evaluateValues(temperature, pressure, model.kernelAmounts(TestPhases.conserved(model, amounts)), root,
                model.prepare(temperature));
    }
}
