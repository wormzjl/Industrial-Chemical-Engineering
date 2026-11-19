package com.wormzjl.icheme.science.fluid.solver;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

class PhaseLayoutTest {
    @Test void sparseTwoPhaseEncodingKeepsPhasePropertySnapshotsIsolated() {
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:tjl20_methane");
        double[] liquid=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE],vapor=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE];liquid[6]=1;vapor[0]=1;
        var state=TestPhases.state(model,350,101325,liquid,vapor,0,0);var layout=new PhaseLayout(model,state);
        var expected=layout.encode(state);
        TestPhases.liquidProperties(state).logFugacity()[0]=Double.NaN;TestPhases.vaporProperties(state).logFugacity()[0]=Double.NaN;
        assertArrayEquals(expected,layout.encode(state),0);
        assertTrue(Arrays.stream(expected).allMatch(Double::isFinite));
    }
    @Test void coupledVolumeEnergyAndPhaseEquationsRecoverIndependentTpTargets() {
        // W3b: on the network model, whose cubic holds the water (a crude package without it holds none).
        String id="icheme:tjl20_methane";var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        var assay=com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo(id);
        var feed=assay.crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions();
        int size=model.componentCount();double[] crude=new double[size];
        for(int c=0;c<feed.length;c++)crude[model.components().indexOf(assay.componentBasis().componentId(c))]+=feed[c];
        crude[size-1]=.2;
        double[] water=new double[size];water[size-1]=1;double[] methane=new double[size];methane[model.components().indexOf("Methane")]=1;
        for(double[] n:new double[][]{crude,water,methane}) {
            var seed=model.flashTP(350,101325,n,()->{});var target=model.flashTP(351,105000,n,()->{});
            var layout=new PhaseLayout(model,seed);
            var equations=layout.wallClosure(n,target.internalEnergy(),target.volume(),Double.NaN);var initial=layout.encode(seed);var initialResidual=equations.residual(initial);
            var result=SparseNewton.solve(equations,initial,new SparseNewton.Settings(30,1e-10,1e-6,24),()->{});
            var actual=layout.decode(result.variables(),0);
            assertArrayEquals(initialResidual,equations.residual(initial),0,"Exact-temperature coefficient cache must not retain a perturbed Newton temperature");
            assertEquals(target.temperature(),actual.temperature(),1e-5);assertEquals(target.pressure(),actual.pressure(),.1);
            assertArrayEquals(n,PhaseLayout.totalAmounts(actual),1e-10);
        }
    }
    /** Decision A423: the wall row and the bag volume stated on a given branch, as a Jacobian sweep reads them. */
    @Test void theWallRowAndTheBagVolumeAreStatedOnTheBranchASweepIsBuiltOn() {
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:tjl20_methane_nitrogen");
        var air=model.initialNitrogenCharge(1,298.15,101325,()->{});double volume=air.volume();
        var taut=new PhaseLayout.Wall(true,true);var slack=new PhaseLayout.Wall(false,false);
        // The charge in a vessel 1e-8 larger: a slack bag at the ambient. Its own branch reads the slack row, zero; the taut
        // branch reads the volume short of the capacity, and the bag at the capacity.
        double larger=volume*(1+1e-8);
        assertEquals(slack,PhaseLayout.wall(air,larger));
        assertEquals(0,PhaseLayout.wallRow(air,larger),0);assertEquals(PhaseLayout.wallRow(air,larger),PhaseLayout.wallRow(air,larger,slack),0);
        assertEquals((volume-larger)/larger,PhaseLayout.wallRow(air,larger,taut),0);
        assertEquals(volume,PhaseLayout.bagVolume(air,larger),0);assertEquals(larger,PhaseLayout.bagVolume(air,larger,taut),0);
        // The charge in a vessel 1e-8 smaller: the wall carries it. Its own branch reads the taut row; the slack branch
        // reads zero at the ambient, and the bag at the contents' volume.
        double smaller=volume*(1-1e-8);
        assertEquals(taut,PhaseLayout.wall(air,smaller));
        assertEquals((volume-smaller)/smaller,PhaseLayout.wallRow(air,smaller),0);assertEquals(PhaseLayout.wallRow(air,smaller),PhaseLayout.wallRow(air,smaller,taut),0);
        assertEquals(0,PhaseLayout.wallRow(air,smaller,slack),0);
        assertEquals(smaller,PhaseLayout.bagVolume(air,smaller),0);assertEquals(volume,PhaseLayout.bagVolume(air,smaller,slack),0);
        // Within 1e-12 of the capacity the bag is the capacity on the slack branch too (the kink's own band).
        double kink=volume*(1+1e-13);
        assertEquals(new PhaseLayout.Wall(false,true),PhaseLayout.wall(air,kink));assertEquals(kink,PhaseLayout.bagVolume(air,kink,new PhaseLayout.Wall(false,true)),0);
    }
}
