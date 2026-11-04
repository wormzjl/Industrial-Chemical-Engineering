package com.wormzjl.icheme.science.fluid.thermo;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Leaving a data range never holds a state (owner rule of 2026-10-16, RANGE_EXTRAPOLATION_PLAN.md sections 2 to 4; it
 * replaces the thermo-domain error of F4/T1/T2): a component below its record's data, a pressure below the package
 * envelope and a component range beyond its viscosity data are evaluated on the models' continuations. Only the numerical
 * box refuses a state, as a rejected trial (a plain {@link IllegalArgumentException}, never a {@link ThermoHold}). Free
 * water below its melting line is ice (WP4), carried by a step as its stand-in, the supercooled liquid held at its
 * 273.16 K values.
 */
class DataRangeContinuationTest {
    private static final String NETWORK="icheme:tjl20_methane_nitrogen";
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(catalog,NETWORK);
    private int index(String component){return model.components().indexOf(component);}
    private double[] overall(Object... pairs){double[] n=new double[model.componentCount()];for(int i=0;i<pairs.length;i+=2)n[index((String)pairs[i])]=(Double)pairs[i+1];return n;}

    /** A crude fraction's caloric fit and viscosity tables end at 293.15 K; below, its ideal-gas cp is held and its
     * liquid viscosity rises along its last segment (Andrade): the state is carried, as the same fraction in a real gas is. */
    @Test void aCrudeFractionBelowItsDataIsCarriedNotRefused() {
        var gas=assertDoesNotThrow(()->model.flashTP(200,101325,overall("Nitrogen",.99,"crude_pc03",.01),()->{}));
        assertEquals(200,gas.temperature());assertTrue(Double.isFinite(gas.enthalpy())&&gas.mass()>0);
        // The same rule on a direct state, the path every Newton trial takes.
        double[] vapor=new double[model.hydrocarbon.componentCount()];vapor[index("Nitrogen")]=.99;vapor[index("crude_pc03")]=.01;
        var direct=assertDoesNotThrow(()->model.state(200,101325,new double[vapor.length],vapor,0,0,101325));
        assertTrue(Double.isFinite(direct.enthalpy()));
        // Two fractions below their data as a liquid: smooth, with a viscosity from the continued tables.
        var oil=assertDoesNotThrow(()->model.flashTP(250,101325,overall("crude_pc03",.4,"crude_pc05",.6),()->{}));
        assertTrue(oil.liquidVolume()>0,"a crude liquid at 250 K");
        double mu=model.phaseViscosity(oil,FluidThermodynamics.OIL,new com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace(),null);
        assertTrue(mu>0&&Double.isFinite(mu),"continued liquid viscosity "+mu);
    }

    /** Below the package's 100 Pa a gas is still a gas; the numerical box (1 Pa..1 GPa, 10..6000 K) is the one bound, a
     * rejected trial and not a hold. */
    @Test void aPressureBelowTheEnvelopeIsCarriedAndOnlyTheNumericalBoxRefusesAState() {
        double[] none=new double[model.hydrocarbon.componentCount()];double[] nitrogen=none.clone();nitrogen[index("Nitrogen")]=1;
        var thin=assertDoesNotThrow(()->model.state(298.15,50,none,nitrogen,0,0,50));
        assertEquals(50,thin.pressure());
        for(double[] outside:new double[][]{{298.15,.5},{5,101325},{298.15,2e9},{7000,101325}}) {
            var refused=assertThrows(IllegalArgumentException.class,()->model.state(outside[0],outside[1],none,nitrogen,0,0,outside[1]));
            assertFalse(refused instanceof ThermoHold,"the box is a rejected trial, never a hold");
            assertTrue(refused.getMessage().contains("numerical box"),refused.getMessage());
            assertFalse(NumericalBox.contains(outside[0],outside[1]));
        }
        var malformed=assertThrows(IllegalArgumentException.class,()->model.state(298.15,101325,new double[2],new double[2],0,0,101325));
        assertFalse(malformed instanceof ThermoHold,"a wrong basis is malformed input");
        var nan=assertThrows(IllegalArgumentException.class,()->model.state(Double.NaN,101325,none,nitrogen,0,0,101325));
        assertFalse(nan instanceof ThermoHold,"a non-finite temperature is malformed input");
        assertDoesNotThrow(()->model.flashTP(700,101325,overall("Nitrogen",1.0),()->{}));
    }

    /** Liquid water below its melting line freezes (WP4): a TP answer states its ice, and the network carries it as its
     * stand-in, free liquid water, until a vessel's refresh freezes it; no hold. Its vapour keeps the ice's sublimation
     * pressure (260 Pa at 263.15 K, against the supercooled liquid's 287 Pa), and a humid gas below it is a gas. */
    @Test void freeLiquidWaterBelowItsMeltingLineIsTheIcesStandInAndItsVapourKeepsTheIcesPressure() {
        double[] none=new double[model.hydrocarbon.componentCount()];double[] nitrogen=none.clone();nitrogen[index("Nitrogen")]=1;
        var standIn=assertDoesNotThrow(()->model.state(270,101325,none,nitrogen,1,0,101325));
        assertEquals(1,standIn.waterLiquid());
        var pure=assertDoesNotThrow(()->model.flashTP(270,101325,overall("Water",1.0),()->{}));
        assertEquals(1,pure.waterLiquid(),"pure water at 270 K and 1 atm is ice, carried as its stand-in");
        var frost=assertDoesNotThrow(()->model.flashTP(263.15,101325,overall("Nitrogen",.995,"Water",.005),()->{}));
        assertTrue(frost.waterLiquid()>0,"the water above the ice's pressure is ice: "+frost.waterLiquid());
        assertEquals(model.freeWaterIce().fugacity(263.15,101325),frost.waterPartialPressure(),1e-6*frost.waterPartialPressure(),"the steam over ice");
        assertEquals(259.89,frost.waterPartialPressure(),.02,"R14-08 at 263.15 K");
        // The saturation pressure continues as the supercooled liquid's (Wagner-Pruss: 287 Pa at 263.15 K, 1.10 times the
        // ice's 260 Pa), and liquid water from its 273.16 K values: cp held (76.0 J/mol K), the volume along dv/dT (negative
        // there, water being densest at 277 K: 3.16 K colder the held liquid is 2.1e-4 larger).
        double supercooled=model.saturationPressure(263.15);
        assertTrue(supercooled>280&&supercooled<295,"supercooled saturation pressure "+supercooled);
        var held=model.waterLiquid(270,101325);var triple=model.waterLiquid(273.16,101325);
        assertEquals(76.0,(triple.molarEnthalpy()-held.molarEnthalpy())/3.16,.1,"cp held at its 273.16 K value");
        double slope=(model.waterLiquid(273.17,101325).molarVolume()-triple.molarVolume())/.01;
        assertTrue(slope<0,"water contracts as it warms at 273.16 K");
        assertEquals(triple.molarVolume()+slope*(270-273.16),held.molarVolume(),1e-6*triple.molarVolume(),"the volume along dv/dT");
        var humid=assertDoesNotThrow(()->model.flashTP(263,101325,overall("Nitrogen",.999,"Water",.001),()->{}));
        assertEquals(0,humid.waterLiquid());assertEquals(.001,humid.waterVapor(),1e-15);
        // Liquid water below its saturation pressure leaves Region 1's range: a notice, not a refusal.
        var stretched=WaterRegion1.evaluate(catalog.requirePackage(NETWORK).water(),350,1000);
        assertTrue(stretched.specificVolume()>0);
    }

    /** The envelope is the package's data range and the input box of device settings: the hull of its compounds' dataset
     * ranges, so a package with a compound whose dataset declares none cannot be built; no range has to lie inside another
     * any more (a component beyond its package envelope or its own vapour viscosity data loads, and is continued where it
     * leaves them). */
    @Test void theCatalogReadsTheEnvelopeAndNoLongerChecksRangesAgainstEachOther() {
        var resources=new HashMap<>(catalog.resources());
        // The pilot's ethane dataset is in no other package, so the network package keeps its envelope.
        String dataset="data/icheme/materials/properties/pilot_ethane.json",id="icheme:pilot_cryogenic";
        var o=JsonParser.parseString(resources.get(dataset)).getAsJsonObject();o.remove("fluid_domain");resources.put(dataset,o.toString());
        var withoutEnvelope=MaterialCatalog.parse(resources);
        assertTrue(withoutEnvelope.fluidValidity(id).isEmpty());assertTrue(withoutEnvelope.fluidValidity(NETWORK).isPresent());
        var missing=assertThrows(IllegalArgumentException.class,()->com.wormzjl.icheme.fluid.support.FluidTestSupport.model(withoutEnvelope,id));
        assertTrue(missing.getMessage().contains("declares no fluid_domain"),missing.getMessage());

        var outside=new HashMap<>(catalog.resources());String nitrogen="data/icheme/materials/properties/nitrogen.json";
        var n=JsonParser.parseString(outside.get(nitrogen)).getAsJsonObject();n.getAsJsonObject("fluid_domain").addProperty("pressure_max_pascal",2e7);outside.put(nitrogen,n.toString());
        assertDoesNotThrow(()->MaterialCatalog.parse(outside));
        var unsupported=new HashMap<>(catalog.resources());String methane="data/icheme/materials/properties/tjl20_methane.json";
        var m=JsonParser.parseString(unsupported.get(methane)).getAsJsonObject();m.getAsJsonObject("fluid_domain").addProperty("temperature_min_kelvin",150);unsupported.put(methane,m.toString());
        assertDoesNotThrow(()->MaterialCatalog.parse(unsupported));
    }
}
