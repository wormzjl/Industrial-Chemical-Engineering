package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.ViscosityCorrelation;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Gate of the owner rule of 2026-10-16 (RANGE_EXTRAPOLATION_PLAN section 3): every property model continues past its
 * data range, with value and slope continuous at each end where the plan's C1 column says so and the value alone where a
 * property is held; a continued viscosity is capped at {@link ViscosityCorrelation#MAXIMUM_VISCOSITY}. The crystal
 * segment records are checked in {@code CrystalReferenceTest}. Continuity is read from the one-sided difference quotients
 * at the end, which a jump in value or slope separates.
 */
class RangeContinuationGateTest {
    private static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    private final MaterialCatalog catalog = MaterialCatalog.bundled();

    /** Ideal gases: the spines, every {@code shifted_polynomial_5} fit of the network and the steam hold the end's c_p. */
    @Test
    void everyIdealGasHoldsItsEndHeatCapacityWithEnthalpyAndEntropyContinuous() {
        int checked = 0;
        for (var entry : catalog.packages().entrySet()) {
            String id = entry.getKey();
            var propertyPackage = entry.getValue();
            for (int i = 0; i < propertyPackage.components().size(); i++) {
                String component = propertyPackage.components().get(i);
                IdealGasFunction gas;
                if (!propertyPackage.spine().isEmpty()) gas = catalog.requireSpine(id, component);
                else {
                    var property = propertyPackage.properties().get(i);
                    if (property.cp().isEmpty()) continue;
                    double[] fit = HydrocarbonModel.idealGasFitRange(catalog, id, property);
                    gas = new NetworkPhaseEngine.CoefficientIdealGas(property, fit[0], fit[1]);
                }
                ends(gas, id + " " + component);
                checked++;
            }
        }
        var model = FluidThermodynamics.forNetwork(catalog, NETWORK);
        ends(model.steam(), "steam");
        assertTrue(checked > 50, "ideal-gas functions checked: " + checked);
    }

    /** The network's translated equation of state takes the same held c_p on its coefficient path as the engine's fit. */
    @Test
    void theTranslatedCoefficientPathContinuesAsTheEnginesIdealGas() {
        var model = FluidThermodynamics.forNetwork(catalog, NETWORK);
        var eos = model.hydrocarbon.translated();
        var propertyPackage = catalog.requirePackage(NETWORK);
        int n = propertyPackage.components().size();
        for (int i = 0; i < n; i++) {
            var property = propertyPackage.properties().get(i);
            double[] fit = HydrocarbonModel.idealGasFitRange(catalog, NETWORK, property);
            double minimum = fit[0], maximum = fit[1];
            var gas = new NetworkPhaseEngine.CoefficientIdealGas(property, minimum, maximum);
            double[] pure = new double[n];
            pure[i] = 1;
            DoubleUnaryOperator cp = t -> eos.evaluateValues(t, 1000, pure, PengRobinsonKernel.Root.VAPOR, eos.prepare(t)).idealGasHeatCapacity();
            DoubleUnaryOperator h = t -> eos.evaluateValues(t, 1000, pure, PengRobinsonKernel.Root.VAPOR, eos.prepare(t)).molarEnthalpy();
            for (double t : new double[] {0.5 * minimum, minimum, maximum, 1.5 * maximum})
                assertEquals(gas.heatCapacity(t), cp.applyAsDouble(t), 1e-9 * gas.heatCapacity(t), property.component() + " c_p at " + t + " K");
            valueAndSlope(h, minimum, property.component() + " translated enthalpy");
            valueAndSlope(h, maximum, property.component() + " translated enthalpy");
        }
    }

    /** Water: IF97 Region 1's h and v (held at 273.16 and 623.15 K), SR6 (value), Wagner-Pruss, the steam's viscosity. */
    @Test
    void theWaterModelsContinueAtTheirEnds() {
        var model = FluidThermodynamics.forNetwork(catalog, NETWORK);
        var water = catalog.requirePackage(NETWORK).water();
        for (double[] end : new double[][] {{water.triplePoint(), 1e5}, {WaterRegion1.REGION_1_MAXIMUM_TEMPERATURE, 20e6}}) {
            valueAndSlope(t -> WaterRegion1.evaluate(water, t, end[1]).specificEnthalpy(), end[0], "IF97 enthalpy");
            valueAndSlope(t -> WaterRegion1.evaluate(water, t, end[1]).specificVolume(), end[0], "IF97 volume");
        }
        var viscosity = new MixtureViscosity(catalog, NETWORK);
        for (double e : new double[] {water.triplePoint(), WaterRegion1.REGION_1_MAXIMUM_TEMPERATURE})
            value(t -> Math.log(viscosity.waterLiquid(t)), e, "SR6 ln viscosity");
        assertEquals(viscosity.waterLiquid(water.triplePoint()), viscosity.waterLiquid(200), 0.0, "SR6 held at the triple point");
        valueAndSlope(t -> Math.log(model.saturationPressure(t)), water.triplePoint(), "Wagner-Pruss ln p_sat");
        valueAndSlope(t -> Math.log(WaterRegion1.diluteVaporViscosity(t)), WaterRegion1.VISCOSITY_LOW, "steam ln viscosity");
    }

    /** Viscosity tables: a liquid continues below its table with value and slope and holds its value above; a vapour
     * continues with value and slope at both ends. */
    @Test
    void everyViscosityTableContinuesAtItsEnds() {
        int checked = 0;
        for (var entry : catalog.packages().entrySet()) {
            for (String component : entry.getValue().components()) {
                for (var phase : ViscosityCorrelation.Phase.values()) {
                    var data = catalog.viscosity(entry.getKey(), component, phase).orElse(null);
                    if (data == null || data.model() != ViscosityCorrelation.Model.LOG_TABLE) continue;
                    DoubleUnaryOperator log = t -> Math.log(data.dynamicViscosityPascalSeconds(t, phase));
                    String what = entry.getKey() + " " + component + " " + phase + " ln viscosity";
                    valueAndSlope(log, data.minimumTemperatureKelvin(), what);
                    if (phase == ViscosityCorrelation.Phase.VAPOR) valueAndSlope(log, data.maximumTemperatureKelvin(), what);
                    else {
                        value(log, data.maximumTemperatureKelvin(), what);
                        assertEquals(log.applyAsDouble(data.maximumTemperatureKelvin()), log.applyAsDouble(2 * data.maximumTemperatureKelvin()), 0.0);
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "viscosity tables checked: " + checked);
    }

    /** At the numerical box's 10 K every liquid viscosity is finite and at most 1e12 Pa s, which the heaviest cut reaches. */
    @Test
    void aContinuedViscosityIsCappedAtTheGlassValue() {
        double highest = 0;
        for (var entry : catalog.packages().entrySet()) {
            for (String component : entry.getValue().components()) {
                var data = catalog.viscosity(entry.getKey(), component, ViscosityCorrelation.Phase.LIQUID).orElse(null);
                if (data == null) continue;
                double mu = data.dynamicViscosityPascalSeconds(NumericalBox.MINIMUM_TEMPERATURE, ViscosityCorrelation.Phase.LIQUID);
                assertTrue(mu > 0 && mu <= ViscosityCorrelation.MAXIMUM_VISCOSITY, entry.getKey() + " " + component + ": " + mu);
                highest = Math.max(highest, mu);
            }
        }
        assertEquals(ViscosityCorrelation.MAXIMUM_VISCOSITY, highest, 0.0, "the cap acts on the heaviest cut");
    }

    /** CO2-I (Jaeger and Span): g, s and v continue with value and slope past 80 and 300 K and past 500 MPa. */
    @Test
    void theJaegerSpanSolidContinuesWithItsDerivatives() {
        var function = catalog.crystals().values().stream().filter(c -> c.gibbsFunction() != null).findFirst().orElseThrow().gibbsFunction();
        for (double e : new double[] {function.minimumTemperature(), function.maximumTemperature()}) {
            valueAndSlope(t -> function.evaluate(t, 1e5, 0, 0).gibbs(), e, "CO2-I g(T)");
            valueAndSlope(t -> function.evaluate(t, 1e5, 0, 0).entropy(), e, "CO2-I s(T)");
            valueAndSlope(t -> function.evaluate(t, 1e5, 0, 0).volume(), e, "CO2-I v(T)");
        }
        valueAndSlope(p -> function.evaluate(200, p, 0, 0).gibbs(), function.maximumPressure(), "CO2-I g(P)");
        valueAndSlope(p -> function.evaluate(200, p, 0, 0).volume(), function.maximumPressure(), "CO2-I v(P)");
    }

    /** An ideal gas at both ends of its data: c_p held past them, h and s continuous with their slopes. */
    private static void ends(IdealGasFunction gas, String what) {
        for (double e : new double[] {gas.minimumTemperature(), gas.maximumTemperature()}) {
            value(gas::heatCapacity, e, what + " c_p");
            valueAndSlope(gas::enthalpy, e, what + " enthalpy");
            valueAndSlope(t -> gas.entropy(t, 1e5), e, what + " entropy");
        }
        assertEquals(gas.heatCapacity(gas.minimumTemperature()), gas.heatCapacity(0.5 * gas.minimumTemperature()), 0.0, what + " c_p held below");
        assertEquals(gas.heatCapacity(gas.maximumTemperature()), gas.heatCapacity(1.5 * gas.maximumTemperature()), 0.0, what + " c_p held above");
    }

    /** {@code f} continues with value and slope at {@code e}: its one-sided difference quotients there agree. */
    static void valueAndSlope(DoubleUnaryOperator f, double e, String what) {
        double d = 1e-6 * e, at = f.applyAsDouble(e);
        double below = (at - f.applyAsDouble(e - d)) / d, above = (f.applyAsDouble(e + d) - at) / d;
        double tolerance = 1e-3 * Math.max(Math.abs(below), Math.abs(above)) + 64 * Math.ulp(at) / d;
        assertEquals(below, above, tolerance, what + " at " + e + ": slope " + below + " below, " + above + " above");
    }

    /** {@code f} continues with its value at {@code e} (a held property, whose slope may jump there). */
    static void value(DoubleUnaryOperator f, double e, String what) {
        double d = 1e-12 * e, at = f.applyAsDouble(e);
        for (double side : new double[] {e - d, e + d})
            assertEquals(at, f.applyAsDouble(side), 1e-6 * Math.max(1, Math.abs(at)), what + " at " + e);
    }
}
