package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.math.DeterministicMath;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The viscous liquid film (owner decision D13 of batch 2026-11-16-fouling-corrosion): a liquid's film is the reference
 * film times φ = min(1, (1 mPa·s / μ)^0.4), never below the gas film, a resistance in series with the film evaluated at
 * every sampled point of the heat curve; a thin liquid keeps its film bit for bit.
 */
class ViscousFilmTest {
    private static final FluidThermodynamics MODEL=FluidTestSupport.networkModel();
    private static final double REFERENCE=HeatCurve.Films.DEFAULT.liquid();

    /** The film of a liquid of viscosity {@code mu} by the statement itself, with the gas film as its floor. */
    private static double expected(double mu){return Math.max(HeatCurve.Films.DEFAULT.gas(),REFERENCE*Math.min(1,Math.pow(1e-3/mu,.4)));}
    private static double film(double mu){return 1/(1/REFERENCE+HeatCurve.viscous(mu));}

    @Test void theResistanceFollowsTheViscosityAndStopsAtTheGasFilm() {
        for(double mu:new double[]{1e-3,5e-4,1e-6,0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertEquals(0,HeatCurve.viscous(mu),"at "+mu);
        assertEquals(1289,film(3e-3),1);assertEquals(796,film(10e-3),1);assertEquals(513,film(30e-3),1);
        assertEquals(.644,film(3e-3)/REFERENCE,5e-4);assertEquals(.398,film(10e-3)/REFERENCE,5e-4);assertEquals(.257,film(30e-3)/REFERENCE,5e-4);
        for(double mu:new double[]{2e-3,3e-3,10e-3,30e-3,.3,1})assertEquals(expected(mu),film(mu),1e-9*expected(mu),"at "+mu);
        assertEquals(HeatCurve.VISCOUS_MOST,HeatCurve.viscous(1.8));assertEquals(100,film(1.8),1e-9);assertEquals(100,film(50),1e-9);
        assertTrue(HeatCurve.viscous(1.78)<HeatCurve.VISCOUS_MOST,"the floor starts at 1.79 Pa·s");
        assertEquals(1/100.-1/2000.,HeatCurve.VISCOUS_MOST,1e-18);
    }

    /** A thin liquid (water from 20 °C, a light hydrocarbon), its boiling plateau and its gas keep today's films exactly. */
    @Test void aThinLiquidKeepsItsFilmsBitForBit() {
        double[] water=new double[MODEL.componentCount()];water[water.length-1]=1;
        var boiling=HeatCurve.sample(MODEL,water,101_325,300,390,HeatCurve.Films.DEFAULT,()->{});
        assertThin(boiling,water,101_325);
        double[] pentane=new double[MODEL.componentCount()];pentane[MODEL.components().indexOf("N-pentane")]=1;
        assertThin(HeatCurve.sample(MODEL,pentane,300_000,280,380,HeatCurve.Films.DEFAULT,()->{}),pentane,300_000);
    }
    private static void assertThin(HeatCurve curve,double[] amounts,double pressure) {
        var fouled=HeatCurve.Films.DEFAULT.fouled(3.5e-4);var refilmed=curve.withFilms(fouled);
        for(int i=0;i<curve.knots();i++) {
            assertEquals(0,curve.viscous(i),"a thin liquid has no viscous resistance at knot "+i);
            var at=state(curve,i,amounts,pressure);
            if(at!=null) {
                assertEquals(HeatCurve.Films.DEFAULT.at(at[0],at[1]),curve.film(i),"knot "+i);
                assertEquals(fouled.at(at[0],at[1]),refilmed.film(i),"fouled knot "+i);
            }
        }
        var again=curve.withFilms(HeatCurve.Films.DEFAULT);
        for(int i=0;i<curve.knots();i++)assertEquals(curve.film(i),again.film(i),"refilmed knot "+i);
    }
    /** The vapour coordinate and liquid-like weight of a sampled (not inserted) knot by its own flash, or null for an
     * inserted knot (its temperature differs from the flash's by the interpolation). */
    private static double[] state(HeatCurve curve,int knot,double[] amounts,double pressure) {
        var s=MODEL.flashTP(curve.temperature(knot),pressure,amounts,()->{});
        if(s.enthalpy()/s.mass()!=curve.enthalpy(knot))return null;
        double w=HeatCurve.liquidLikeWeight(s);
        return new double[]{Double.isNaN(w)?MODEL.vaporMass(s)/s.mass():1-w,w};
    }

    /** The heaviest crude cut in the network package: a residue, viscous when cool. */
    private static double[] residue() {
        double[] n=new double[MODEL.componentCount()];
        int heavy=-1;for(int c=0;c<MODEL.components().size();c++)if(MODEL.components().get(c).toLowerCase(Locale.ROOT).contains("pc10"))heavy=c;
        assertTrue(heavy>=0,"a crude cut pc10 in "+MODEL.components());
        n[heavy]=1;return n;
    }
    private static final double RESIDUE_PRESSURE=1_000_000;

    /** A viscous liquid's film is 2000 φ of its own viscosity at every knot, and rises as it warms. */
    @Test void aViscousLiquidsFilmFollowsItsViscosityAtEveryKnot() {
        var amounts=residue();
        boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.ENABLED=true;
        long flashes=SolverDiagnostics.flashCalls.sum();
        HeatCurve curve;
        try{curve=HeatCurve.sample(MODEL,amounts,RESIDUE_PRESSURE,320,450,HeatCurve.Films.DEFAULT,()->{});}
        finally{SolverDiagnostics.ENABLED=enabled;}
        flashes=SolverDiagnostics.flashCalls.sum()-flashes;
        int viscous=0;
        for(int i=0;i<curve.knots();i++) {
            var s=MODEL.flashTP(curve.temperature(i),RESIDUE_PRESSURE,amounts,()->{});
            assertEquals(s.enthalpy()/s.mass(),curve.enthalpy(i),"an all-liquid curve has no inserted knot");
            double mu=MODEL.slotViscosity(s,s.oneFluidSlot(),null,null);
            assertEquals(expected(mu),curve.film(i),1e-9*expected(mu),"knot "+i+" at "+curve.temperature(i)+" K, mu "+mu);
            if(curve.viscous(i)>0)viscous++;
            if(i>0)assertTrue(curve.film(i)>=curve.film(i-1),"the film rises as the oil warms: knot "+i);
        }
        assertTrue(curve.film(curve.knots()-1)>curve.film(0),"warmer oil, thinner, better film");
        assertTrue(curve.film(0)<REFERENCE/2,"cool residue is viscous: "+curve.film(0));
        // The cost: one viscosity evaluation per flashed point whose liquid film has weight, here every point.
        System.out.printf(Locale.ROOT,"D13 cost: %d knots, %d with a viscous film, %d flashes; films %.1f to %.1f W/m2K%n",
                curve.knots(),viscous,flashes,curve.film(0),curve.film(curve.knots()-1));
    }

    /** A boiling mixture: its plateau and gas keep their films, only the liquid end is viscous. */
    @Test void aTwoPhaseOrGasKnotKeepsItsFilm() {
        double[] n=residue();n[MODEL.components().indexOf("N-pentane")]=1;
        var curve=HeatCurve.sample(MODEL,n,300_000,300,700,HeatCurve.Films.DEFAULT,()->{});
        boolean liquid=false,other=false;
        for(int i=0;i<curve.knots();i++) {
            var s=MODEL.flashTP(curve.temperature(i),300_000,n,()->{});
            if(s.enthalpy()/s.mass()!=curve.enthalpy(i))continue;
            double w=HeatCurve.liquidLikeWeight(s),x=Double.isNaN(w)?MODEL.vaporMass(s)/s.mass():1-w;
            boolean weighted=Double.isNaN(w)?x<HeatCurve.LIQUID_UNTIL:w>0;
            if(!weighted){other=true;assertEquals(0,curve.viscous(i));assertEquals(HeatCurve.Films.DEFAULT.at(x,w),curve.film(i),"knot "+i);}
            else if(curve.viscous(i)>0)liquid=true;
        }
        assertTrue(other,"the curve reaches the plateau");
    }

    /** A deposit and a viscous liquid are resistances in series: 1/film = 1/2000 + rf + viscous. */
    @Test void aFouledViscousFilmAddsBothResistances() {
        var curve=HeatCurve.sample(MODEL,residue(),RESIDUE_PRESSURE,320,450,HeatCurve.Films.DEFAULT,()->{});
        double rf=5.3e-4;var fouled=curve.withFilms(HeatCurve.Films.DEFAULT.fouled(rf));
        for(int i=0;i<curve.knots();i++) {
            assertTrue(curve.viscous(i)>0);
            double inverse=1/REFERENCE+rf+curve.viscous(i);
            assertEquals(inverse,1/fouled.film(i),1e-12*inverse,"knot "+i);
        }
    }

    /** The old effective film of an air cooler, its arithmetic before D13 ({@code AirCooled.Air.effective}). */
    private static double effective(double process,double barePerFace,double air,double rate) {
        double conductance=1/(1/(barePerFace*process)+1/air);
        return -rate*DeterministicMath.expm1(-conductance/rate)/barePerFace;
    }
    /** An air cooler's tubes take the viscous resistance inside, before the air; a thin liquid's films are today's. */
    @Test void anAirCoolerTakesTheResistanceInsideItsTubes() {
        double[] water=new double[MODEL.componentCount()];water[water.length-1]=1;
        var thin=HeatCurve.sample(MODEL,water,101_325,300,390,HeatCurve.Films.DEFAULT,()->{});
        var viscous=HeatCurve.sample(MODEL,residue(),RESIDUE_PRESSURE,320,450,HeatCurve.Films.DEFAULT,()->{});
        for(double rf:new double[]{0,3.5e-4})for(double fans:new double[]{0,.5,1}) {
            var air=HeatSides.AIR.fouled(rf);double draught=air.draught(fans);
            var films=air.films(draught);var outer=films.outer();assertNotNull(outer);
            double share=Math.max(draught,1e-12),a=air.film()*share,rate=air.capacity()*share,bare=air.barePerFace();
            var p=air.process();
            var old=new HeatCurve.Films(effective(p.liquid(),bare,a,rate),effective(p.twoPhase(),bare,a,rate),effective(p.gas(),bare,a,rate));
            assertEquals(old.liquid(),films.wallLiquid());assertEquals(old.twoPhase(),films.wallTwoPhase());assertEquals(old.gas(),films.wallGas());
            var now=thin.withFilms(films);var before=thin.withFilms(old);
            for(int i=0;i<thin.knots();i++)assertEquals(before.film(i),now.film(i),"thin knot "+i+" rf "+rf+" fans "+fans);
            var hot=viscous.withFilms(films);
            for(int i=0;i<viscous.knots();i++) {
                double value=effective(1/(1/p.liquid()+viscous.viscous(i)),bare,a,rate);
                assertEquals(value,hot.film(i),1e-12*value,"viscous knot "+i);
                assertTrue(hot.film(i)<before.film(0)||viscous.viscous(i)==0);
            }
        }
        assertEquals(HeatSides.airFilms(1),HeatSides.airFilms(1),"films with an outer side compare by value");
    }
}
