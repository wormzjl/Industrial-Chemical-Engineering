package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A pump drawing one dense fluid (S2c of batch 2026-11-15-supercritical-fluids, S2C_NETWORK_REVIEW.md): carbon dioxide
 * above its critical temperature has no vapour pressure ({@link ReleasePressure#leaveOutGases} leaves it out), so the pump
 * has no cavitation margin and no vapour-pressure floor; its suction limit is the clean zone's edge E alone, the lowest
 * pressure at which the stream at its own enthalpy keeps its vapour share within the clean share. Below the critical
 * pressure E is a real flash (the isenthalp enters the two-phase dome from the dense side); just above the critical
 * density it is the slot line (reduced density 1) crossed at constant enthalpy, the pump-against-compressor convention of
 * owner decision O2 A. Either way E lies below the suction's pressure, so the pump is not vapour-locked.
 */
class DenseFluidSuctionTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PassiveStepSolver solver=new PassiveStepSolver(model);
    /** Carbon dioxide's critical pressure in the network package (chemsep_carbon_dioxide), Pa. */
    private static final double CO2_CRITICAL_PRESSURE=7.383e6;

    private double[] co2(){double[] n=new double[model.componentCount()];n[model.components().indexOf("CarbonDioxide")]=1;return n;}
    private double edge(FluidThermodynamics.State s) {
        double[] drawn=new double[3];drawn[FluidThermodynamics.OIL]=FluidThermodynamics.phaseVolume(s,FluidThermodynamics.OIL);
        return solver.suctions.zoneEdge(InletPhase.of(model,s,drawn),()->{});
    }
    private FluidThermodynamics.State along(FluidThermodynamics.State s,double p) {
        return model.equilibriumPH(s.temperature(),p,s.fluidEnthalpy(),s.totals(),()->{});
    }

    @Test void aPumpOnDenseCarbonDioxideHasNoVapourPressureAndItsLimitIsTheFlashBelowItsCriticalPressure() {
        var s=model.flashTP(320,15e6,co2(),()->{});
        assertEquals(FluidThermodynamics.State.LIQUID,s.oneFluidSlot(),"dense carbon dioxide from its critical density sits in the liquid slot");
        assertTrue(s.dense());
        assertEquals(Double.POSITIVE_INFINITY,solver.suctions.release(s,()->{}),"no vapour pressure above Tc: no cavitation margin, no floor");
        double e=edge(s);
        // Measured 2026-11-15 (tmp/supercritical-s2/probe/zone-edge.txt in the batch's worktree): 6.5198 MPa.
        assertEquals(6.5198e6,e,6.5198e6*2e-4,"the clean zone's edge");
        assertTrue(e<CO2_CRITICAL_PRESSURE,"below the critical pressure: a flash");
        var below=along(s,e*(1-1e-3));
        assertTrue(FluidThermodynamics.holdsPhase(below,FluidThermodynamics.GAS)&&FluidThermodynamics.holdsPhase(below,FluidThermodynamics.OIL),"just below E the stream is two phases");
        assertEquals(e,ReleasePressure.limit(solver.suctions.release(s,()->{}),e,0),0,"the suction limit is E alone");
        assertTrue(s.pressure()-e>8e6,"E stands 8.5 MPa under the suction: no vapour lock");
    }

    @Test void justAboveItsCriticalDensityTheLimitIsTheSlotLineAtConstantEnthalpy() {
        double lo=8e6,hi=9e6;
        for(int i=0;i<100;i++){double mid=(lo+hi)/2;if(model.flashTP(310,mid,co2(),()->{}).reducedDensity()<1.0005)lo=mid;else hi=mid;}
        var s=model.flashTP(310,hi,co2(),()->{});
        assertEquals(FluidThermodynamics.State.LIQUID,s.oneFluidSlot());
        double e=edge(s);
        assertTrue(e>CO2_CRITICAL_PRESSURE&&e<s.pressure(),"above the critical pressure and below the suction: "+e);
        var above=along(s,e*(1+1e-6));var below=along(s,e*(1-1e-3));
        assertEquals(FluidThermodynamics.State.LIQUID,above.oneFluidSlot(),"at E the stream is still one fluid in the liquid slot");
        assertEquals(FluidThermodynamics.State.VAPOR,below.oneFluidSlot(),"just below E it is one fluid in the vapour slot: no flash");
        assertEquals(1,above.reducedDensity(),1e-3,"E is the critical density on the isenthalp");
    }
}
