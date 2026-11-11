package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W3B_NETWORK_PLAN.md section 17, form G: the clean zone's edge E is read on the stream the suction line delivers
 * ({@link InletPhase#drawn}, the stream the head share reads). Through a BULK end (a junction's mixture) it is the whole
 * fluid's, and where that stream already holds vapour past the clean zone's edge at the supplier's pressure, E is that
 * pressure: the lock, max(E, p_v), then stands at it and the pump closes (cold random 181, bundled random 6). Through a
 * vessel's liquid port it is the liquid's alone, so a tank of water under a nitrogen blanket keeps section 17's E below the
 * tank's pressure. A carried crystal moves neither: E reads the fluid's enthalpy. The limit is the larger of the edges
 * that exist.
 */
class SuctionZoneEdgeTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PassiveStepSolver solver=new PassiveStepSolver(model);

    private double[] feed(Object... pairs) {
        double[] n=new double[model.componentCount()];
        for(int k=0;k<pairs.length;k+=2)n[model.components().indexOf((String)pairs[k])]=((Number)pairs[k+1]).doubleValue();
        return n;
    }

    /** LNG with 0.4 % nitrogen at 127.5 K and 312 kPa, flashed to a few per cent vapour by volume, as cold random 181's
     * suction junction stood: its vapour share is past the clean zone's edge at its pressure (3.2 %), so the mixture's E is
     * the junction's own pressure; its liquid alone has E below it. */
    @Test void aTwoPhaseJunctionsMixturePastTheEdgeHasItsEdgeAtItsOwnPressure() {
        double[] lng=feed("Methane",90.0,"Ethane",7.0,"Propane",3.0,"Nitrogen",.4);
        double t=127.5,bubble=bubble(t,lng);
        FluidThermodynamics.State s=null;
        for(double p=bubble*.999;p>.5*bubble;p*=.995){s=model.flashTP(t,p,lng.clone(),()->{});if(share(s)>GasEntrainment.cleanShare(p))break;}
        assertNotNull(s);double p=s.pressure();
        assertTrue(share(s)>GasEntrainment.cleanShare(p),"the junction's vapour is past the edge: "+share(s)+" at "+p+" Pa");
        assertEquals(p,solver.zoneEdge(InletPhase.of(model,s,null),()->{}),0,"the mixture's edge is the junction's own pressure");
        double liquidOnly=solver.zoneEdge(InletPhase.of(model,s,liquid(s,FluidThermodynamics.OIL)),()->{});
        assertTrue(liquidOnly<p,"its liquid alone has its edge below the junction's pressure: "+liquidOnly);
    }

    /** Water under a nitrogen blanket at 298.15 K and 1 atm, drawn alone through the tank's liquid port: section 17's E,
     * 69.9 kPa, below the tank's pressure. The tank's whole contents (mostly nitrogen) put E at the tank's pressure. */
    @Test void aLiquidDrawnFromUnderItsGasKeepsItsEdgeBelowTheVesselsPressure() {
        var s=model.flashTP(298.15,101325,feed("Water",55.3,"Nitrogen",40.0),()->{});
        assertTrue(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)&&FluidThermodynamics.holdsLiquid(s));
        double e=solver.zoneEdge(InletPhase.of(model,s,liquid(s,FluidThermodynamics.AQUEOUS)),()->{});
        assertEquals(69.9e3,e,.5e3,"section 17's E for water under nitrogen");
        assertTrue(e<s.pressure());
        assertEquals(s.pressure(),solver.zoneEdge(InletPhase.of(model,s,null),()->{}),0,"the whole blanketed contents are past the edge");
    }

    /** A carried crystal (2 kg of a dry-ice-like solid) in the same water under nitrogen, and in air-free water drawn whole:
     * E is the fluid's, so the crystal's enthalpy moves it by nothing beyond the bisection's width. */
    @Test void aCarriedCrystalLeavesTheEdgeUnchanged() {
        var crystal=new SolidInventory.Moments(2.0,2.0/1560,2.0*850);
        var blanketed=model.flashTP(298.15,101325,feed("Water",55.3,"Nitrogen",40.0),()->{});
        var withCrystal=blanketed.withSolidState(blanketed.solids(),crystal);
        double plain=solver.zoneEdge(InletPhase.of(model,blanketed,liquid(blanketed,FluidThermodynamics.AQUEOUS)),()->{});
        assertEquals(plain,solver.zoneEdge(InletPhase.of(model,withCrystal,liquid(withCrystal,FluidThermodynamics.AQUEOUS)),()->{}),0,"through the liquid port");
        var water=model.flashTP(298.15,101325,feed("Water",55.3),()->{});
        double bulk=solver.zoneEdge(InletPhase.of(model,water,null),()->{});
        assertTrue(bulk<water.pressure(),"air-free water drawn whole has its edge below its pressure: "+bulk);
        double carried=solver.zoneEdge(InletPhase.of(model,water.withSolidState(water.solids(),crystal),null),()->{});
        assertEquals(bulk,carried,2e-6*bulk,"through a BULK end");
    }

    /** The limit is the larger of the edges that exist, the breakdown NPSH on p_v only; none only where neither exists. */
    @Test void theLimitIsTheLargerOfTheEdgesThatExist() {
        double none=Double.POSITIVE_INFINITY;
        assertEquals(50e3,PassiveStepSolver.limit(none,50e3,1e3),0,"no p_v (a dead oil under a blanket): E alone");
        assertEquals(4e3,PassiveStepSolver.limit(3e3,none,1e3),0,"no E (its top trial refused): p_v and the breakdown");
        assertEquals(50e3,PassiveStepSolver.limit(3e3,50e3,1e3),0,"both: the larger");
        assertEquals(60e3,PassiveStepSolver.limit(55e3,50e3,5e3),0,"both: p_v and the breakdown past E");
        assertEquals(none,PassiveStepSolver.limit(none,none,1e3),0,"neither: no limit");
    }

    /** The whole of one liquid phase of {@code s}, as a liquid port at a unit-free target draws it. */
    private static double[] liquid(FluidThermodynamics.State s,int phase) {
        double[] drawn=new double[3];drawn[phase]=FluidThermodynamics.phaseVolume(s,phase);return drawn;
    }
    private double share(FluidThermodynamics.State s) {
        double v=s.vaporVolume(),total=v+s.condensedVolume();return total>0?v/total:0;
    }
    private double bubble(double t,double[] x) {
        double lo=1e3,hi=1e8;
        for(int k=0;k<200&&hi>lo*(1+1e-9);k++){double mid=Math.sqrt(lo*hi);if(FluidThermodynamics.holdsPhase(model.flashTP(t,mid,x.clone(),()->{}),FluidThermodynamics.GAS))lo=mid;else hi=mid;}
        return hi;
    }
}
