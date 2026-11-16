package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A pump drawing one dense fluid (S2c of batch 2026-11-15-supercritical-fluids, S2C_NETWORK_REVIEW.md): carbon dioxide
 * above its critical temperature has no vapour pressure ({@link ReleasePressure#leaveOutGases} leaves it out), so the pump
 * has no cavitation margin and no vapour-pressure floor; its suction limit is the clean zone's edge E alone, the lowest
 * pressure at which the stream at its own enthalpy keeps its vapour share within the clean share. One dense fluid has no
 * second phase (S4b): its E stands only where its isenthalp really flashes, from the bubble side of the critical point
 * (below the critical pressure); an isenthalp that passes the critical point on the gas side enters the dome from the dew
 * side, and the stream has no edge. So one seam is left, on the isenthalp through the critical point, where E goes from
 * about the critical pressure to none. As first implemented (S4) a dense trial read {@code 1 - w}, and E was a density
 * above the critical pressure, or the suction's own pressure below reduced density about 1.1: a pump drawing such a fluid
 * through a line was vapour-locked.
 */
class DenseFluidSuctionTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final PassiveStepSolver solver=new PassiveStepSolver(model);
    /** Carbon dioxide's critical pressure in the network package (chemsep_carbon_dioxide), Pa. */
    private static final double CO2_CRITICAL_PRESSURE=7.383e6;

    private double[] co2(){double[] n=new double[model.componentCount()];n[model.components().indexOf("CarbonDioxide")]=1;return n;}
    /** E of the whole fluid of one-fluid state {@code s}, drawn from the phase of its slot. */
    private double edge(FluidThermodynamics.State s) {
        int label=s.oneFluidSlot()==FluidThermodynamics.State.VAPOR?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
        double[] drawn=new double[3];drawn[label]=FluidThermodynamics.phaseVolume(s,label);
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

    private FluidThermodynamics.State atDensity(double t,double target) {
        double lo=5e6,hi=2e7;
        for(int i=0;i<100;i++){double mid=(lo+hi)/2;if(model.flashTP(t,mid,co2(),()->{}).reducedDensity()<target)lo=mid;else hi=mid;}
        return model.flashTP(t,hi,co2(),()->{});
    }

    /** Gas side: carbon dioxide at 310 K at reduced density 1.0005 (the liquid slot), 1.03 and 1.06, and at 0.6 (the vapour
     * slot): its isenthalp stays dense down to the critical pressure and enters the dome from the dew side, at a vapour
     * share near 0.9, past the clean share at once; the last passing trial is one dense fluid, so no edge and no limit.
     * The seam at reduced density 0.5 (none to the suction's own pressure, S4) is gone. */
    @Test void onTheGasSideOfTheCriticalPointADenseFluidHasNoEdge() {
        for(double delta:new double[]{.49,.51,.6,1.0005,1.03,1.06}) {
            var s=atDensity(310,delta);
            assertTrue(s.dense());
            assertEquals(Double.POSITIVE_INFINITY,edge(s),0,"no edge at reduced density "+delta);
            assertEquals(Double.POSITIVE_INFINITY,ReleasePressure.limit(solver.suctions.release(s,()->{}),edge(s),0),0,"no suction limit at "+delta);
        }
        var s=atDensity(310,1.03);var below=along(s,7.375e6);
        assertTrue(FluidThermodynamics.holdsPhase(below,FluidThermodynamics.GAS)&&FluidThermodynamics.holdsPhase(below,FluidThermodynamics.OIL),"it does split below the critical pressure");
        assertTrue(ReleasePressure.vapourVolumeShare(below)>.8,"from the dew side: "+ReleasePressure.vapourVolumeShare(below));
    }

    /** Bubble side: carbon dioxide at 320 K, reduced density 1.25 (11.08 MPa), flashes below the critical pressure at
     * 7.3093 MPa, where its vapour share rises from 0 at the bubble pressure (8.3612 MPa as first implemented, a density). */
    @Test void onTheBubbleSideTheEdgeIsTheFlashBelowTheCriticalPressure() {
        var s=atDensity(320,1.25);
        double e=edge(s);
        // Measured 2026-11-16 (tmp/supercritical-s4b/after/S4SeamProbe.txt in the batch's worktree): 7.3093 MPa.
        assertEquals(7.3093e6,e,7.3093e6*2e-4,"the clean zone's edge");
        assertTrue(e<CO2_CRITICAL_PRESSURE,"below the critical pressure");
        var above=along(s,e*(1+1e-6));var below=along(s,e*(1-1e-3));
        assertTrue(ReleasePressure.vapourVolumeShare(above)>0&&ReleasePressure.vapourVolumeShare(above)<=GasEntrainment.cleanShare(above.pressure()),"at E it holds a vapour, at most the clean share");
        assertTrue(ReleasePressure.vapourVolumeShare(below)>GasEntrainment.cleanShare(below.pressure()),"just below E it is past it");
    }

    /** The seam at 310 K: on the isenthalp through the critical point, between reduced densities 1.06 and 1.075, E goes
     * from none to about the critical pressure; from 1.20 on it is the flash S4 measured, to the bisection's width. */
    @Test void theSeamSitsOnTheIsenthalpThroughTheCriticalPoint() {
        assertEquals(Double.POSITIVE_INFINITY,edge(atDensity(310,1.06)),0);
        double near=edge(atDensity(310,1.075));
        assertEquals(7.3772e6,near,7.3772e6*2e-4,"just past the seam, about the critical pressure");
        assertTrue(near<CO2_CRITICAL_PRESSURE);
        // tmp/supercritical-s4/probe/seam.txt (S4): 7.174059 MPa at 1.20, 6.524773 MPa at 1.475.
        assertEquals(7.174059e6,edge(atDensity(310,1.20)),7.174059e6*2e-5);
        assertEquals(6.524773e6,edge(atDensity(310,1.475)),6.524773e6*2e-5);
    }
}
