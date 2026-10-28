package com.wormzjl.icheme.science.fluid.network;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A fired heater's coil law (batch 2026-10-27-heat-exchanger-design, W6, plan section 4). */
class FiredCoilTest {
    /** Liquid from 300 to 400 K at 4 kJ/kgK, a boiling plateau at 400 K of 2 MJ/kg, vapour on at 2 kJ/kgK. */
    private static final HeatCurve BOILING=HeatCurve.of(List.of(new double[]{0,300,2000},new double[]{4e5,400,2000},new double[]{2.4e6,400,5000},new double[]{2.6e6,500,100}));

    @Test void theSetPointsEnthalpyIsTheLargestOnTheCurve() {
        assertEquals(2e5,BOILING.enthalpyAt(350),1e-6);
        assertEquals(2.4e6,BOILING.enthalpyAt(400),"a plateau's temperature names its vapour end");
        assertEquals(2.5e6,BOILING.enthalpyAt(450),1e-6);
        // Beyond the ends the end lines extend, as temperatureAt does.
        assertEquals(2.8e6,BOILING.enthalpyAt(600),1e-6);assertEquals(-4e4,BOILING.enthalpyAt(290),1e-6);
        for(double t:new double[]{290,350,450,600})assertEquals(t,BOILING.temperatureAt(BOILING.enthalpyAt(t)),1e-9);
        var flat=HeatCurve.of(List.of(new double[]{0,400,5000},new double[]{1e6,400,5000}));
        assertEquals(1e6,flat.enthalpyAt(450),"an end without slope holds its end knot");assertEquals(0,flat.enthalpyAt(350));
    }
    /** The heat is β · |ṁ| · (h_set − h_in): linear in the flow, zero at no flow, the outlet at the set point while β is 1. */
    @Test void aFullyFiringRadiantCoilBringsItsOutletToTheSetPoint() {
        var coil=new FiredCoil(450,1e7,1,0);
        double inlet=BOILING.enthalpyAt(320);
        for(double flow:new double[]{2,-2}) {
            double heat=coil.heat(BOILING,inlet,flow);
            assertEquals(450,BOILING.temperatureAt(inlet+heat/Math.abs(flow)),1e-9,"either direction");
        }
        assertEquals(0,coil.heat(BOILING,inlet,0));
        assertEquals(2*coil.heat(BOILING,inlet,1),coil.heat(BOILING,inlet,2),1e-6);
    }
    /** β for a step from its start: 1 within the duty, the duty over the demand beyond it, 0 above the set point or with the burners out. */
    @Test void theFiringShareIsPlannedFromTheStepsStart() {
        var coil=new FiredCoil(450,1e6,1,0);
        double inlet=BOILING.enthalpyAt(320),demand=BOILING.enthalpyAt(450)-inlet;
        assertEquals(1,coil.planned(BOILING,inlet,.1).firing());
        var short_=coil.planned(BOILING,inlet,2);
        assertEquals(1e6/(2*demand),short_.firing(),1e-12);
        assertEquals(1e6,short_.heat(BOILING,inlet,2),1e-6,"at its maximum, the duty");
        assertEquals(0,coil.planned(BOILING,BOILING.enthalpyAt(460),2).firing(),"a stream above the set point is not fired");
        assertEquals(0,coil.planned(BOILING,BOILING.enthalpyAt(450),2).firing(),"nor one at it");
        assertEquals(0,new FiredCoil(450,0,1,0).planned(BOILING,inlet,2).firing(),"burners out");
        assertEquals(1,coil.planned(BOILING,inlet,0).firing(),"no flow: nothing to limit");
        assertEquals(coil.shape(),short_.shape(),"the share is a slice input, not a shape");
    }
    @Test void aCoilValidatesItsFacts() {
        assertThrows(IllegalArgumentException.class,()->new FiredCoil(0,1,1,0));
        assertThrows(IllegalArgumentException.class,()->new FiredCoil(450,-1,1,0));
        assertThrows(IllegalArgumentException.class,()->new FiredCoil(450,1,1.5,0));
        assertThrows(IllegalArgumentException.class,()->new FiredCoil(450,Double.POSITIVE_INFINITY,1,0));
    }
}
