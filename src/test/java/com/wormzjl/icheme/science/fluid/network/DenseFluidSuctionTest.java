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
 * pressure at which the stream at its own enthalpy keeps its vapour share within the clean share. One dense fluid's
 * vapour share is {@code 1 - w} on its liquid-like weight (S4): below the critical pressure E is a real flash (the
 * isenthalp enters the two-phase dome from the dense side); above it E is where the fluid thins to the clean share, a
 * density, not the slot line; and a fluid already that gas-like at its suction has its E at its own pressure.
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

    private FluidThermodynamics.State atDensity(double t,double target) {
        double lo=5e6,hi=2e7;
        for(int i=0;i<100;i++){double mid=(lo+hi)/2;if(model.flashTP(t,mid,co2(),()->{}).reducedDensity()<target)lo=mid;else hi=mid;}
        return model.flashTP(t,hi,co2(),()->{});
    }
    private double share(FluidThermodynamics.State s){return 1-FluidThermodynamics.liquidLikeWeight(s);}

    @Test void justAboveItsCriticalDensityTheFluidIsGasLikeEnoughThatEIsItsOwnPressure() {
        var s=atDensity(310,1.0005);
        assertEquals(FluidThermodynamics.State.LIQUID,s.oneFluidSlot());
        // Its vapour share 1 - w = 0.499 is past the clean share at 8.33 MPa (0.322): the pump holds its suction at the
        // supplier's pressure, as on any stream that gassy (before S4, the slot line on the isenthalp, 8.3178 MPa).
        assertTrue(share(s)>=GasEntrainment.cleanShare(s.pressure()));
        assertEquals(s.pressure(),edge(s),0,"E is the suction's own pressure");
    }

    @Test void aboveTheCriticalPressureEIsWhereTheFluidThinsToTheCleanShare() {
        var s=atDensity(320,1.25);
        double e=edge(s);
        // Measured 2026-11-16 (tmp/supercritical-s4/probe/seam.txt in the batch's worktree): 8.3612 MPa; 7.3093 MPa before S4,
        // where the slot line was crossed.
        assertEquals(8.3612e6,e,8.3612e6*2e-4,"the clean zone's edge");
        assertTrue(e>CO2_CRITICAL_PRESSURE&&e<s.pressure(),"above the critical pressure and below the suction");
        var above=along(s,e*(1+1e-6));var below=along(s,e*(1-1e-3));
        assertTrue(above.dense()&&below.dense(),"one dense fluid on both sides of E: no flash");
        assertTrue(share(above)<=GasEntrainment.cleanShare(above.pressure()),"at E it holds the clean share");
        assertTrue(share(below)>GasEntrainment.cleanShare(below.pressure()),"just below E it is past it");
    }
}
