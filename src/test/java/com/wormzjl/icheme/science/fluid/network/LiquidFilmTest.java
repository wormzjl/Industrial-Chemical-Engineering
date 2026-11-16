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
 * The liquid film (owner decisions D13 and D15 of batch 2026-11-16-fouling-corrosion): a liquid's film is the reference
 * film, water at 20 °C, times φ = min(1, (k/0.6)^0.6 (cp/4180)^0.4 (1 mPa·s/μ)^0.4), never below the gas film, a resistance
 * in series with the film evaluated at every sampled point of the heat curve: μ from the viscosity model, k from the water
 * or petroleum rule, cp from the curve's own enthalpy.
 */
class LiquidFilmTest {
    private static final FluidThermodynamics MODEL=FluidTestSupport.networkModel();
    private static final double REFERENCE=HeatCurve.Films.DEFAULT.liquid();

    private static double film(double mu,double k,double cp){return 1/(1/REFERENCE+HeatCurve.liquidResistance(mu,k,cp));}
    /** The heat capacity a knot's resistance implies, given its viscosity and conductivity; NaN where the resistance is 0 or
     * at its most (the factor is clamped there). */
    private static double impliedCapacity(double resistance,double mu,double k) {
        if(!(resistance>0&&resistance<HeatCurve.LIQUID_RESISTANCE_MOST))return Double.NaN;
        double phi=1/(1+REFERENCE*resistance);
        double rest=Math.pow((Double.isFinite(k)&&k>0?k:.6)/.6,.6)*Math.pow(1e-3/(Double.isFinite(mu)&&mu>0?mu:1e-3),.4);
        return 4180*Math.pow(phi/rest,1/.4);
    }

    @Test void theLawMatchesItsTable() {
        assertEquals(5000,REFERENCE);
        double[][] table={{1e-3,.6,4180,5000},{3e-4,.68,4200,5000},{1e-3,.126,2000,1459},{3e-4,.091,2900,2255},{.05,.110,2300,298},{50,.12,2000,100}};
        for(double[] row:table)assertEquals(row[3],film(row[0],row[1],row[2]),.01*row[3],"mu "+row[0]+" k "+row[1]+" cp "+row[2]);
        assertEquals(0,HeatCurve.liquidResistance(1e-3,.6,4180),"the reference");
        assertEquals(0,HeatCurve.liquidResistance(3e-4,.68,4200),"a thin water is capped at the reference");
        assertEquals(HeatCurve.LIQUID_RESISTANCE_MOST,HeatCurve.liquidResistance(50,.12,2000),"the gas film is the floor");
        assertEquals(1/100.-1/5000.,HeatCurve.LIQUID_RESISTANCE_MOST,1e-18);
    }

    /** A refused or broken input makes its own factor 1, the reference's (D13's refusal guard, kept for each property). */
    @Test void aRefusedPropertyTakesTheReferenceFactor() {
        double k=.126,cp=2000,mu=3e-3;
        for(double bad:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertEquals(HeatCurve.liquidResistance(1e-3,k,cp),HeatCurve.liquidResistance(bad,k,cp),"viscosity "+bad);
            assertEquals(HeatCurve.liquidResistance(mu,.6,cp),HeatCurve.liquidResistance(mu,bad,cp),"conductivity "+bad);
            assertEquals(HeatCurve.liquidResistance(mu,k,4180),HeatCurve.liquidResistance(mu,k,bad),"heat capacity "+bad);
        }
        assertEquals(0,HeatCurve.liquidResistance(Double.NaN,Double.NaN,Double.NaN),"all refused: the reference film");
    }

    /** The two conductivity rules: water within 3 % of saturated liquid water (NIST WebBook, IAPWS 2011), continuous ends;
     * the petroleum rule's own values and floor. */
    @Test void theConductivityRulesHoldTheirData() {
        double[][] water={{0.01,.55560},{25.01,.60648},{50.01,.64059},{75.01,.66353},{100.01,.67721},{125.01,.68274},{150.01,.68101},
                {175.01,.67351},{200.01,.66001},{225.01,.64104},{250.01,.61688},{275.01,.58753},{300.01,.55263}};
        for(double[] row:water)assertEquals(row[1],HeatCurve.waterConductivity(273.15+row[0]),.03*row[1],"water at "+row[0]+" °C");
        assertEquals(HeatCurve.waterConductivity(273.15),HeatCurve.waterConductivity(250),"held below 0 °C");
        assertEquals(HeatCurve.waterConductivity(573.15),HeatCurve.waterConductivity(700),"held above 300 °C");
        assertEquals(.126,HeatCurve.oilConductivity(298.15),5e-4);assertEquals(.091,HeatCurve.oilConductivity(573.15),5e-4);
        assertEquals(HeatCurve.OIL_CONDUCTIVITY_FLOOR,HeatCurve.oilConductivity(1200));
        double edge=(.164-HeatCurve.OIL_CONDUCTIVITY_FLOOR)/1.277e-4;
        assertEquals(HeatCurve.OIL_CONDUCTIVITY_FLOOR,HeatCurve.oilConductivity(edge),1e-15,"continuous at the floor");
    }

    private static double[] water(){double[] n=new double[MODEL.componentCount()];n[n.length-1]=1;return n;}

    /** Warm water is at the reference: 5000 W/m²K at every liquid knot from 20 to 80 °C (20 °C within 1 %). */
    @Test void warmWaterKeepsTheReferenceFilm() {
        var curve=HeatCurve.sample(MODEL,water(),101_325,293.15,353.15,HeatCurve.Films.DEFAULT,()->{});
        for(int i=0;i<curve.knots();i++) {
            assertTrue(curve.film(i)<=REFERENCE&&curve.film(i)>=.99*REFERENCE,"knot "+i+" at "+curve.temperature(i)+" K: "+curve.film(i));
            if(curve.temperature(i)>=300)assertEquals(REFERENCE,curve.film(i),"knot "+i);
        }
    }

    /** Water near freezing is viscous: its film falls below the reference. */
    @Test void coldWaterFallsBelowTheReference() {
        var curve=HeatCurve.sample(MODEL,water(),101_325,274.15,285.15,HeatCurve.Films.DEFAULT,()->{});
        assertTrue(curve.film(0)<.9*REFERENCE,"cold water's film "+curve.film(0));
        assertTrue(curve.film(curve.knots()-1)>curve.film(0),"it rises as the water warms");
    }

    /** A thin liquid (water from 300 K), its boiling plateau and its steam keep the reference films exactly. */
    @Test void aThinLiquidKeepsItsFilmsBitForBit() {
        var amounts=water();
        var curve=HeatCurve.sample(MODEL,amounts,101_325,300,390,HeatCurve.Films.DEFAULT,()->{});
        var fouled=HeatCurve.Films.DEFAULT.fouled(3.5e-4);var refilmed=curve.withFilms(fouled);
        for(int i=0;i<curve.knots();i++) {
            assertEquals(0,curve.liquidResistance(i),"warm water has no film resistance at knot "+i);
            var at=state(curve,i,amounts,101_325);
            if(at!=null) {
                assertEquals(HeatCurve.Films.DEFAULT.at(at[0],at[1]),curve.film(i),"knot "+i);
                assertEquals(HeatCurve.Films.DEFAULT.at(at[0],at[1],0),curve.film(i),"at(x, w, 0) is at(x, w), knot "+i);
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

    /**
     * An oil's film follows its viscosity, its conductivity by the petroleum rule and its heat capacity from the curve's
     * own chords (centred inside, one-sided at the ends) at every knot, and rises as it warms; the heat capacity lies
     * between 1500 and 3500 J/kgK; the curve makes one flash per knot, no more.
     */
    @Test void anOilsFilmFollowsItsPropertiesAtEveryKnot() {
        var amounts=residue();
        boolean enabled=SolverDiagnostics.ENABLED;SolverDiagnostics.ENABLED=true;
        long flashes=SolverDiagnostics.flashCalls.sum(),again;
        HeatCurve curve;
        try {
            curve=HeatCurve.sample(MODEL,amounts,RESIDUE_PRESSURE,320,450,HeatCurve.Films.DEFAULT,()->{});
            flashes=SolverDiagnostics.flashCalls.sum()-flashes;
            again=SolverDiagnostics.flashCalls.sum();
            for(int i=0;i<curve.knots();i++)MODEL.flashTP(curve.temperature(i),RESIDUE_PRESSURE,amounts,()->{});
            again=SolverDiagnostics.flashCalls.sum()-again;
        } finally{SolverDiagnostics.ENABLED=enabled;}
        int n=curve.knots();
        // The sampling flashes each kept point and, per final segment, one midpoint it keeps off (the chord test); the film
        // reads no state of its own.
        assertEquals(0,again%n,"each knot flashes alike");
        assertTrue(flashes<=again/n*(2L*n-1),"no flash beyond the sampling's: "+flashes+" for "+n+" knots");
        for(int i=0;i<n;i++) {
            var s=MODEL.flashTP(curve.temperature(i),RESIDUE_PRESSURE,amounts,()->{});
            assertEquals(s.enthalpy()/s.mass(),curve.enthalpy(i),"an all-liquid curve has no inserted knot");
            if(i>0)assertTrue(curve.temperature(i)-curve.temperature(i-1)>=HeatCurve.CHORD_KELVIN,"knots wider than the shortest chord");
            int a=Math.max(0,i-1),b=Math.min(n-1,i+1);
            double cp=(curve.enthalpy(b)-curve.enthalpy(a))/(curve.temperature(b)-curve.temperature(a));
            assertTrue(cp>1500&&cp<3500,"knot "+i+" heat capacity "+cp);
            double mu=MODEL.slotViscosity(s,s.oneFluidSlot(),null,null),k=HeatCurve.oilConductivity(curve.temperature(i));
            double expected=Math.max(100,film(mu,k,cp));
            assertEquals(expected,curve.film(i),1e-9*expected,"knot "+i+" at "+curve.temperature(i)+" K, mu "+mu+" k "+k+" cp "+cp);
            if(i>0)assertTrue(curve.film(i)>=curve.film(i-1),"the film rises as the oil warms: knot "+i);
        }
        assertTrue(curve.film(0)<REFERENCE/4,"cool residue is viscous: "+curve.film(0));
        System.out.printf(Locale.ROOT,"D15 cost: %d knots, %d flashes; films %.1f to %.1f W/m2K%n",n,flashes,curve.film(0),curve.film(n-1));
    }

    private static double[] mixture(){double[] n=residue();n[MODEL.components().indexOf("N-pentane")]=1;return n;}
    /** The viscosity of a sampled point's liquid the way the curve reads it: the oil and aqueous slots by volume. */
    private static double liquidViscosity(FluidThermodynamics.State s) {
        double value=0,volume=0;
        for(int slot:new int[]{FluidThermodynamics.State.LIQUID,FluidThermodynamics.State.AQUEOUS}){double v=s.slotVolume(slot);if(v>0){value+=v*MODEL.slotViscosity(s,slot,null,null);volume+=v;}}
        return value/volume;
    }

    /**
     * A boiling mixture: its plateau and gas keep their films; a point just past the bubble point (0 &lt; x &lt; 0.05,
     * whose chords carry latent heat) takes the heat capacity of the nearest all-liquid point.
     */
    @Test void aBoilingPointTakesItsNeighboursHeatCapacity() {
        var n=mixture();double p=300_000;
        var curve=HeatCurve.sample(MODEL,n,p,300,700,HeatCurve.Films.DEFAULT,()->{});
        boolean other=false;int boiling=0;
        for(int i=0;i<curve.knots();i++) {
            var s=MODEL.flashTP(curve.temperature(i),p,n,()->{});
            if(s.enthalpy()/s.mass()!=curve.enthalpy(i))continue;
            double w=HeatCurve.liquidLikeWeight(s),x=Double.isNaN(w)?MODEL.vaporMass(s)/s.mass():1-w;
            boolean weighted=Double.isNaN(w)?x<HeatCurve.LIQUID_UNTIL:w>0;
            if(!weighted){other=true;assertEquals(0,curve.liquidResistance(i));assertEquals(HeatCurve.Films.DEFAULT.at(x,w),curve.film(i),"knot "+i);continue;}
            if(!(Double.isNaN(w)&&x>0))continue;
            // The nearest all-liquid sampled knot below: the bubble point's liquid side.
            int j=i-1;
            while(j>=0){var below=MODEL.flashTP(curve.temperature(j),p,n,()->{});if(below.enthalpy()/below.mass()==curve.enthalpy(j)&&MODEL.vaporMass(below)==0)break;j--;}
            assertTrue(j>=0,"an all-liquid knot below knot "+i);
            var below=MODEL.flashTP(curve.temperature(j),p,n,()->{});
            double mine=impliedCapacity(curve.liquidResistance(i),liquidViscosity(s),HeatCurve.oilConductivity(curve.temperature(i)));
            double theirs=impliedCapacity(curve.liquidResistance(j),liquidViscosity(below),HeatCurve.oilConductivity(curve.temperature(j)));
            assertEquals(theirs,mine,1e-6*theirs,"knot "+i+" at x "+x+" takes knot "+j+"'s heat capacity");
            boiling++;
        }
        assertTrue(other,"the curve reaches the plateau");
        assertTrue(boiling>0,"the curve has a boiling liquid point");
    }

    /** A curve whose every liquid point is boiling has no liquid chord: the reference heat capacity. */
    @Test void aCurveThatStartsBoilingTakesTheReferenceHeatCapacity() {
        var n=mixture();double p=300_000;
        var whole=HeatCurve.sample(MODEL,n,p,300,700,HeatCurve.Films.DEFAULT,()->{});
        double bubble=Double.NaN;
        for(int i=0;i<whole.knots()&&Double.isNaN(bubble);i++){var s=MODEL.flashTP(whole.temperature(i),p,n,()->{});if(MODEL.vaporMass(s)>0)bubble=whole.temperature(i);}
        var curve=HeatCurve.sample(MODEL,n,p,bubble+.5,bubble+150,HeatCurve.Films.DEFAULT,()->{});
        int checked=0;
        for(int i=0;i<curve.knots();i++) {
            var s=MODEL.flashTP(curve.temperature(i),p,n,()->{});
            if(s.enthalpy()/s.mass()!=curve.enthalpy(i))continue;
            double x=MODEL.vaporMass(s)/s.mass();
            assertTrue(x>0,"every point boils: knot "+i);
            if(x>=HeatCurve.LIQUID_UNTIL)continue;
            double expected=HeatCurve.liquidResistance(liquidViscosity(s),HeatCurve.oilConductivity(curve.temperature(i)),HeatCurve.HEAT_CAPACITY_REFERENCE);
            assertEquals(expected,curve.liquidResistance(i),1e-12*expected+1e-18,"knot "+i);
            checked++;
        }
        assertTrue(checked>0,"the curve has a boiling liquid point");
    }

    /** A deposit and a liquid film are resistances in series: 1/film = 1/5000 + rf + R. */
    @Test void aFouledLiquidFilmAddsBothResistances() {
        var curve=HeatCurve.sample(MODEL,residue(),RESIDUE_PRESSURE,320,450,HeatCurve.Films.DEFAULT,()->{});
        double rf=5.3e-4;var fouled=curve.withFilms(HeatCurve.Films.DEFAULT.fouled(rf));
        for(int i=0;i<curve.knots();i++) {
            assertTrue(curve.liquidResistance(i)>0);
            double inverse=1/REFERENCE+rf+curve.liquidResistance(i);
            assertEquals(inverse,1/fouled.film(i),1e-12*inverse,"knot "+i);
        }
    }

    /** The effective film of an air cooler, its arithmetic before D13 ({@code AirCooled.Air.effective}). */
    private static double effective(double process,double barePerFace,double air,double rate) {
        double conductance=1/(1/(barePerFace*process)+1/air);
        return -rate*DeterministicMath.expm1(-conductance/rate)/barePerFace;
    }
    /** An air cooler's tubes take the liquid film's resistance inside, before the air; warm water's films are the reference's. */
    @Test void anAirCoolerTakesTheResistanceInsideItsTubes() {
        var thin=HeatCurve.sample(MODEL,water(),101_325,300,390,HeatCurve.Films.DEFAULT,()->{});
        var oil=HeatCurve.sample(MODEL,residue(),RESIDUE_PRESSURE,320,450,HeatCurve.Films.DEFAULT,()->{});
        for(double rf:new double[]{0,3.5e-4})for(double fans:new double[]{0,.5,1}) {
            var air=HeatSides.AIR.fouled(rf);double draught=air.draught(fans);
            var films=air.films(draught);var outer=films.outer();assertNotNull(outer);
            double share=Math.max(draught,1e-12),a=air.film()*share,rate=air.capacity()*share,bare=air.barePerFace();
            var p=air.process();
            var old=new HeatCurve.Films(effective(p.liquid(),bare,a,rate),effective(p.twoPhase(),bare,a,rate),effective(p.gas(),bare,a,rate));
            assertEquals(old.liquid(),films.wallLiquid());assertEquals(old.twoPhase(),films.wallTwoPhase());assertEquals(old.gas(),films.wallGas());
            var now=thin.withFilms(films);var before=thin.withFilms(old);
            for(int i=0;i<thin.knots();i++)assertEquals(before.film(i),now.film(i),"thin knot "+i+" rf "+rf+" fans "+fans);
            var hot=oil.withFilms(films);
            for(int i=0;i<oil.knots();i++) {
                double value=effective(1/(1/p.liquid()+oil.liquidResistance(i)),bare,a,rate);
                assertEquals(value,hot.film(i),1e-12*value,"oil knot "+i);
                assertTrue(hot.film(i)<before.film(0)||oil.liquidResistance(i)==0);
            }
        }
        assertEquals(HeatSides.airFilms(1),HeatSides.airFilms(1),"films with an outer side compare by value");
    }
}
