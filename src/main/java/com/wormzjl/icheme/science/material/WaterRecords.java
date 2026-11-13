package com.wormzjl.icheme.science.material;

import com.wormzjl.icheme.science.thermo.water.WaterConstants;

/**
 * A catalog water record as the water module's {@link WaterConstants} (the module imports no material type): the one
 * mapping. A caller on a hot path holds the result rather than mapping per evaluation.
 */
public final class WaterRecords {
    private WaterRecords() {}

    /** {@code water}'s numbers. */
    public static WaterConstants constants(MaterialCatalog.Water water) {
        return new WaterConstants(water.molarMass(), water.triplePoint(), water.criticalTemperature(),
                water.criticalPressure(), water.maximumTemperature(), water.boilingTemperature(), water.vaporizationEnthalpy(),
                water.watsonExponent(), doubles(water.saturation()), doubles(water.shomate()), water.datumVaporizationEnthalpy());
    }

    private static double[] doubles(java.util.List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }
}
