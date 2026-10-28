package com.wormzjl.icheme.science.fluid.network;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A fired heater's convection coil against the flue gas (batch 2026-10-27-heat-exchanger-design, the owner's test of 2026-10-28, F1). */
class ConvectionCoilTest {
    /** An oil at 2.8 kJ/kgK from 400 to 1200 K. */
    private static final HeatCurve OIL=HeatCurve.of(List.of(new double[]{0,400,2000},new double[]{2.24e6,1200,2000}));
    /** Water at 10 bar, roughly: liquid at 4.2 kJ/kgK from 290 K, boiling at 453 K (2 MJ/kg), steam at 2.2 kJ/kgK to 1200 K. */
    private static final HeatCurve WATER=HeatCurve.of(List.of(new double[]{0,290,2000},new double[]{6.846e5,453,2000},new double[]{2.6846e6,453,5000},new double[]{4.3280e6,1200,100}));

    /**
     * The design point (OWNER_TEST_REVIEW.md section 1): a 10 MW heater's radiant coil takes 7 MW, so its flue gas carries
     * 7 kW/K; crude at 250 °C through the 200 m² convection coil at 54.9 W/m²K takes 3 MW, 0.429 of the radiant heat (the
     * coils' 70/30 split), and leaves at 283 °C.
     */
    @Test void theDesignSplitIsThirtySeventy() {
        var coil=new ConvectionCoil(54.9*200,1,ConvectionCoil.flueOf(7e6));
        assertEquals(7e3,coil.flue(),1e-9);
        double flow=1e7/(2800*110),inlet=OIL.enthalpyAt(523.15),heat=coil.heat(OIL,inlet,flow);
        assertEquals(3e6,heat,.01e6);
        assertEquals(556.15,OIL.temperatureAt(inlet+heat/flow),.5);
    }
    @Test void itMovesNothingWithoutFlowOrFlueGas() {
        double inlet=OIL.enthalpyAt(523.15);var coil=new ConvectionCoil(1e4,1,7e3);
        assertEquals(0,coil.withFlue(0).heat(OIL,inlet,10),"the burners out");
        assertEquals(0,coil.heat(OIL,inlet,0),"no flow");
        assertEquals(coil.heat(OIL,inlet,10),coil.heat(OIL,inlet,-10),"either direction");
        assertEquals(0,ConvectionCoil.flueOf(-5),"a radiant coil that cools gives no flue gas");
        assertEquals(coil.shape(),coil.withFlue(1).shape(),"the flue gas is a slice input, not a shape");
    }
    /** A starved coil nears the bridgewall and never passes it; a strong stream takes at most what the gas holds. */
    @Test void itIsBoundedByTheBridgewallAndTheGas() {
        var coil=new ConvectionCoil(1e4,1,7e3);double inlet=OIL.enthalpyAt(523.15),gas=7e3*(ConvectionCoil.BRIDGEWALL-523.15);
        for(double flow:new double[]{1e-3,.01,.1,1,2.5,10,100,1e4}) {
            double heat=coil.heat(OIL,inlet,flow);
            assertTrue(heat>0&&heat<gas,flow+" kg/s: "+heat);
            assertTrue(OIL.temperatureAt(inlet+heat/flow)<=ConvectionCoil.BRIDGEWALL+1e-9,"never past the bridgewall at "+flow+" kg/s");
        }
        assertEquals(ConvectionCoil.BRIDGEWALL,OIL.temperatureAt(inlet+coil.heat(OIL,inlet,1e-3)/1e-3),1,"a starved coil reaches it");
        assertEquals(gas,new ConvectionCoil(1e6,1,7e3).heat(OIL,inlet,1e5),.001*gas,"a large coil on a strong stream takes all the gas holds");
        // Water from 25 °C: the plateau's latent heat is in the stream's mean capacity; still below the bridgewall.
        double water=WATER.enthalpyAt(298.15),heat=coil.heat(WATER,water,.1);
        assertTrue(heat>0&&WATER.temperatureAt(water+heat/.1)<=ConvectionCoil.BRIDGEWALL+1e-9);
    }
    /** Where the stream's capacity rate meets the gas's the law is smooth: balanced counterflow, UA ΔT / (1 + UA / C). */
    @Test void itIsSmoothWhereTheCapacityRatesMeet() {
        var coil=new ConvectionCoil(1e4,1,7e3);double inlet=OIL.enthalpyAt(523.15),balanced=7e3/2800;
        double expected=(ConvectionCoil.BRIDGEWALL-523.15)/(1/1e4+1/7e3);
        for(double share:new double[]{1-1e-9,1,1+1e-9})assertEquals(expected,coil.heat(OIL,inlet,balanced*share),1e-6*expected);
        assertEquals(coil.heat(OIL,inlet,balanced*.999),coil.heat(OIL,inlet,balanced*1.001),.002*expected,"continuous across");
    }
    @Test void aCoilValidatesItsFacts() {
        assertThrows(IllegalArgumentException.class,()->new ConvectionCoil(0,1,1));
        assertThrows(IllegalArgumentException.class,()->new ConvectionCoil(1,1,-1));
        assertThrows(IllegalArgumentException.class,()->new ConvectionCoil(Double.NaN,1,1));
        assertThrows(IllegalArgumentException.class,()->new ConvectionCoil(1,1,Double.POSITIVE_INFINITY));
    }
}
