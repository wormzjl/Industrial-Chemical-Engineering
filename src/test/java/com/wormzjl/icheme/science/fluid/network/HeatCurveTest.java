package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The heat curve and side kernel (batch 2026-10-27-heat-exchanger-design, work package W1). */
class HeatCurveTest {
    /** A film of 1 W/m2K makes the area the conductance UA, as the 2026-09-24 prototype's kernel read it. */
    private static HeatCurve sensible(){return HeatCurve.of(List.of(new double[]{0,300,1},new double[]{400_000,400,1}));}

    @Test void agreesWithTheConstantCpExponentialAndItsEnergyBalance() {
        double out=sensible().march(400_000,2,8000,300);
        assertEquals(400_000/Math.E,out,1e-9);
        assertEquals(300+100/Math.E,sensible().temperatureAt(out),1e-12);
    }
    @Test void latentHeatLeavesAtConstantTemperatureBeforeTheLiquidCools() {
        var curve=HeatCurve.of(List.of(new double[]{0,300,1},new double[]{400_000,400,1},new double[]{2_400_000,400,1},new double[]{2_600_000,500,1}));
        assertEquals(1_400_000,curve.march(2_400_000,1,10_000,300),1e-8);
        double full=curve.march(2_400_000,1,24_000,300);
        assertEquals(400_000/Math.E,full,1e-8);
        assertEquals(300+100/Math.E,curve.temperatureAt(full),1e-10);
    }
    @Test void heatingAndEitherFlowSignGiveTheSameMarch() {
        assertEquals(sensible().march(0,2,8000,400),sensible().march(0,-2,8000,400));
        assertEquals(400-100/Math.E,sensible().temperatureAt(sensible().march(0,2,8000,400)),1e-12);
    }
    @Test void subdivisionAndTheEnthalpyReferenceDoNotChangeTheAnswer() {
        var curve=HeatCurve.of(List.of(new double[]{0,300,1},new double[]{200_000,350,1},new double[]{300_000,350,1},new double[]{700_000,500,1}));
        double whole=curve.march(700_000,.4,600,300);
        assertEquals(whole,curve.march(curve.march(700_000,.4,200,300),.4,400,300),1e-8);
        var shifted=HeatCurve.of(List.of(new double[]{-1e6,300,1},new double[]{-800_000,350,1},new double[]{-700_000,350,1},new double[]{-300_000,500,1}));
        assertEquals(700_000-whole,-300_000-shifted.march(-300_000,.4,600,300),1e-8);
    }
    @Test void zeroFlowZeroAreaAndHugeExposureHaveTheirLimits() {
        assertEquals(400_000,sensible().march(400_000,0,1000,300));
        assertEquals(400_000,sensible().march(400_000,2,0,300));
        assertEquals(300,sensible().temperatureAt(sensible().march(400_000,1e-100,1000,300)),1e-9);
    }
    @Test void malformedCurvesAreRefusedAndInputsAreOwned() {
        assertThrows(IllegalArgumentException.class,()->new HeatCurve(new double[]{0,0},new double[]{300,400},new double[]{1,1}));
        assertThrows(IllegalArgumentException.class,()->new HeatCurve(new double[]{0,1},new double[]{400,300},new double[]{1,1}));
        assertThrows(IllegalArgumentException.class,()->new HeatCurve(new double[]{0,1},new double[]{300,400},new double[]{1,0}));
        assertThrows(IllegalArgumentException.class,()->sensible().march(0,1,-1,300));
        double[] h={0,400_000},t={300,400},f={1,1};
        var curve=new HeatCurve(h,t,f);h[1]=0;t[1]=0;
        assertEquals(400,curve.temperatureAt(400_000));
    }
    /** AGENTS.md "Thermo validity": beyond its knots the curve extends its end line, value and slope continuous; a donor
     * or a wall outside the span never throws. */
    @Test void beyondItsKnotsTheCurveExtendsItsEndLines() {
        var curve=sensible();
        assertEquals(425,curve.temperatureAt(500_000),1e-12);
        assertEquals(275,curve.temperatureAt(-100_000),1e-12);
        assertTrue(curve.extrapolated(500_000)&&!curve.extrapolated(200_000));
        // One line through the extension: the exponential from 425 K, and heating towards a wall above the top knot.
        assertEquals(300+125/Math.E,curve.temperatureAt(curve.march(500_000,2,8000,300)),1e-10);
        assertEquals(450-150/Math.E,curve.temperatureAt(curve.march(0,2,8000,450)),1e-10);
        assertEquals(250+25/Math.E,curve.temperatureAt(curve.march(-100_000,2,8000,250)),1e-10);
    }
    /** A film linear in h on each segment, against a fine fourth-order integration of dh/dA = -film (T - wall) / m. */
    @Test void aLinearFilmIntegratesExactlyAcrossSegments() {
        var curve=HeatCurve.of(List.of(new double[]{0,300,2000},new double[]{100_000,320,500},new double[]{200_000,330,100}));
        for(double[] c:new double[][]{{200_000,.5,3,290},{0,.5,2,340},{150_000,.2,.5,310}})
            assertEquals(reference(curve,c[0],c[1],c[2],c[3]),curve.march(c[0],c[1],c[2],c[3]),1e-6*Math.abs(c[0]-curve.march(c[0],c[1],c[2],c[3]))+1e-6);
    }
    /** The film and the temperature difference with a common root (their partial fractions' denominator is zero). */
    @Test void aFilmProportionalToTheDifferenceHasNoSingularity() {
        var curve=HeatCurve.of(List.of(new double[]{0,300,100},new double[]{100_000,400,200}));
        assertEquals(reference(curve,90_000,.3,40,200),curve.march(90_000,.3,40,200),1e-4);
        assertEquals(reference(curve,90_000,.3,40,200.000001),curve.march(90_000,.3,40,200.000001),1e-4);
    }
    /** Owner answer Q2, plan section 4: water's plateau at 1 bar takes the condensing film inside it, from the knots at the
     * vapour fractions 0.05 and 0.95, not the mean of the liquid and gas films. */
    @Test void aPureComponentsPlateauCarriesTheTwoPhaseFilm() {
        var model=FluidTestSupport.networkModel();
        double[] water=new double[model.componentCount()];water[water.length-1]=1;
        var curve=HeatCurve.sample(model,water,101_325,355,390,HeatCurve.Films.DEFAULT,()->{});
        double middle=Double.NaN,width=0;
        for(int k=0;k<curve.knots()-1;k++)if(curve.film(k)==5000&&curve.film(k+1)==5000){middle=(curve.enthalpy(k)+curve.enthalpy(k+1))/2;width=curve.enthalpy(k+1)-curve.enthalpy(k);}
        assertFalse(Double.isNaN(middle),"Two knots inside the plateau carry 5000 W/m2K");
        // Between the vapour fractions 0.05 and 0.95: nine tenths of water's latent heat at 1 atm (2.26 MJ/kg).
        assertEquals(.9*2.257e6,width,.03*2.257e6);
        assertEquals(5000,curve.filmAt(middle),1e-9);
        double saturation=curve.temperatureAt(middle);
        assertEquals(373.12,saturation,.5);
        // Condensing on 0.01 m2 of wall 10 K below saturation at 1 kg/s: 5000 W/m2K x 10 K x 0.01 m2.
        assertEquals(-500,curve.march(middle,1,.01,saturation-10)-middle,1e-6);
        // In series with a liquid film of 2000 W/m2K the overall coefficient is 1/(1/5000 + 1/2000) = 1428.6 W/m2K.
        double u=1/(1/curve.filmAt(middle)+1/HeatCurve.Films.DEFAULT.liquid());
        assertEquals(1428.57,u,.01);
    }
    @Test void aSideMarchesItsCellsInFlowOrderWithItsBypassAndGiveBack() {
        var curve=sensible();
        var uniform=new HeatSide(8000,HeatCurve.Films.DEFAULT,new double[]{300,300,300,300,300,300,300,300},0,0);
        // A uniform wall: eight cells are the one march over the whole surface.
        assertEquals(2*(curve.march(400_000,2,8000,300)-400_000),uniform.heat(curve,400_000,2),1e-6);
        assertEquals(0,uniform.heat(curve,400_000,0));
        double[] profile={300,310,320,330,340,350,360,370},reversed={370,360,350,340,330,320,310,300};
        var side=uniform.withInputs(profile,0,0);
        assertEquals(side.heat(curve,400_000,2),uniform.withInputs(reversed,0,0).heat(curve,400_000,-2),1e-9);
        assertNotEquals(side.heat(curve,400_000,2),side.heat(curve,400_000,-2));
        // Half bypassed: half the flow through the cells; the give-back is an enthalpy shift on the whole flow.
        assertEquals(1*(curve.march(400_000,1,8000,300)-400_000),uniform.withInputs(uniform.wall(),.5,0).heat(curve,400_000,2),1e-6);
        assertEquals(2*500,uniform.withInputs(uniform.wall(),1,500).heat(curve,400_000,2),1e-12);
        assertEquals(0,uniform.withInputs(uniform.wall(),.3,500).heat(curve,400_000,0));
    }
    /** RK4 in area with 200 000 steps. */
    private static double reference(HeatCurve curve,double inlet,double mass,double area,double wall) {
        int steps=200_000;double h=inlet,da=area/steps;
        for(int i=0;i<steps;i++) {
            double k1=rate(curve,h,mass,wall),k2=rate(curve,h+da*k1/2,mass,wall),k3=rate(curve,h+da*k2/2,mass,wall),k4=rate(curve,h+da*k3,mass,wall);
            h+=da*(k1+2*k2+2*k3+k4)/6;
        }
        return h;
    }
    private static double rate(HeatCurve curve,double h,double mass,double wall){return -curve.filmAt(h)*(curve.temperatureAt(h)-wall)/mass;}
}
