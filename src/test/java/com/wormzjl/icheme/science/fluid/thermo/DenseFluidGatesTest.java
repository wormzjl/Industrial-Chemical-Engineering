package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;

/**
 * The dense-fluid stream laws (S2b of batch 2026-11-15-supercritical-fluids, S2_NETWORK_REVIEW.md): pure carbon dioxide
 * on the network package, by {@code flashTP}, along an isobar through its critical temperature and an isotherm above it.
 * A dense fluid is one phase whose density runs from a gas's to a liquid's, so its viscosity, velocity limit and film
 * weight change smoothly along both sweeps and do not jump where the state's slot changes. The one accepted step is the
 * viscosity at {@code Tc} on the isobar: a liquid table below, dilute plus the residual above.
 *
 * <p>Each neighbour bound is 1.5 times the largest neighbour change measured on the sweep when the laws were written
 * (2026-11-15; {@code tmp/supercritical-s2/probe/sweeps.txt} in the batch's worktree), so a new step fails the gate.</p>
 */
class DenseFluidGatesTest {
    private static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidMaterialCatalog.NETWORK_PACKAGE);
    /** Sweep A, 10 MPa, 280 to 400 K by 1 K: largest changes 1.742e-6 Pa s (281 K, the liquid table), 1.854 m/s (318 K), 0.070 (319 K). */
    private static final double A_VISCOSITY=1.5*1.742e-6,A_LIMIT=1.5*1.854,A_WEIGHT=1.5*.0700;
    /** Sweep B, 320 K, 2 to 30 MPa by 0.25 MPa: largest changes 1.809e-6 Pa s (10 MPa), 2.918 m/s (10.25 MPa), 0.0998 (10 MPa). */
    private static final double B_VISCOSITY=1.5*1.809e-6,B_LIMIT=1.5*2.918,B_WEIGHT=1.5*.0998;
    /** The viscosity at Tc on sweep A, from 304 to 305 K: the liquid table's 76.74 uPa s to dilute plus residual, 53.82. */
    private static final double TC_STEP=.701;

    private static double[] pure(String name){double[] n=new double[MODEL.componentCount()];n[MODEL.components().indexOf(name)]=1;return n;}
    /** The state's one fluid: viscosity, velocity limit and film weight of its stream, its slot, and whether it is dense. */
    private record Laws(double viscosity,double limit,double weight,int slot,boolean dense) {
        static Laws of(FluidThermodynamics.State s) {
            int slot=s.oneFluidSlot();
            assertTrue(slot>=0,"one fluid at "+s.temperature()+" K "+s.pressure()+" Pa");
            int label=slot==FluidThermodynamics.State.VAPOR?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
            return new Laws(MODEL.phaseViscosity(s,label,null,null),MODEL.phaseVelocityLimit(s,label),HeatCurve.liquidLikeWeight(s),slot,s.dense());
        }
    }
    private static Laws at(double t,double p,double[] n){return Laws.of(assertDoesNotThrow(()->MODEL.flashTP(t,p,n,()->{}),"no hold at "+t+" K "+p+" Pa"));}

    @Test void anIsobarThroughTcChangesItsLawsSmoothlyButForTheViscosityAtTc() {
        double[] co2=pure("CarbonDioxide");
        Laws previous=null;int steps=0;
        for(int t=280;t<=400;t++) {
            var laws=at(t,1e7,co2);
            if(previous!=null) {
                String where=(t-1)+" to "+t+" K";
                if(previous.dense!=laws.dense){assertEquals(TC_STEP,laws.viscosity/previous.viscosity,.01,"the viscosity step at Tc, "+where);steps++;}
                else assertEquals(previous.viscosity,laws.viscosity,A_VISCOSITY,"viscosity "+where);
                assertEquals(previous.limit,laws.limit,A_LIMIT,"velocity limit "+where);
                assertEquals(previous.weight,laws.weight,A_WEIGHT,"film weight "+where);
            }
            previous=laws;
        }
        assertEquals(1,steps,"one step: at Tc");
    }

    @Test void anIsothermAboveTcChangesItsLawsSmoothlyAndCrossesTheSlotLineContinuously() {
        double[] co2=pure("CarbonDioxide");
        Laws previous=null;int slotChanges=0;
        for(int k=0;k<=112;k++) {
            double p=2e6+.25e6*k;var laws=at(320,p,co2);
            assertTrue(laws.dense,"dense at 320 K "+p+" Pa");
            if(previous!=null) {
                String where=(p-.25e6)+" to "+p+" Pa";
                if(previous.slot!=laws.slot)slotChanges++;
                assertEquals(previous.viscosity,laws.viscosity,B_VISCOSITY,"viscosity "+where);
                assertEquals(previous.limit,laws.limit,B_LIMIT,"velocity limit "+where);
                assertEquals(previous.weight,laws.weight,B_WEIGHT,"film weight "+where);
            }
            previous=laws;
        }
        assertEquals(1,slotChanges,"the isotherm crosses the slot line once");
        // Bisect the slot line to 1e-12 of its pressure: the laws on its two sides agree to 1e-9 relative. The line is the
        // critical density (owner decision O2 A, S2c): the vapour slot below reduced density 1, the liquid slot from it.
        double lo=9e6,hi=1.1e7;int below=at(320,lo,co2).slot;
        assertEquals(FluidThermodynamics.State.VAPOR,below);
        assertNotEquals(below,at(320,hi,co2).slot);
        while(hi-lo>1e-12*lo){double mid=(lo+hi)/2;if(at(320,mid,co2).slot==below)lo=mid;else hi=mid;}
        var a=at(320,lo,co2);var b=at(320,hi,co2);
        assertNotEquals(a.slot,b.slot);
        assertEquals(1,MODEL.flashTP(320,lo,co2,()->{}).reducedDensity(),1e-9,"the slot line is at the critical density");
        assertEquals(1,MODEL.flashTP(320,hi,co2,()->{}).reducedDensity(),1e-9,"the slot line is at the critical density");
        assertEquals(1,b.viscosity/a.viscosity,1e-9,"viscosity across the slot line");
        assertEquals(1,b.limit/a.limit,1e-9,"velocity limit across the slot line");
        assertEquals(a.weight,b.weight,1e-9,"film weight across the slot line");
    }

    /**
     * Nitrogen at 300 K below a quarter of its critical density keeps today's laws to the bit: its stream viscosity is the
     * Wilke value, its film the gas film, its velocity limit the vapour's acoustic law. The residual's onset is at
     * {@code rho_r} 0.25, which nitrogen reaches at 6.95 MPa (6.9 MPa: 0.2481; 7.0 MPa: 0.2516).
     */
    @Test void aGasBelowAQuarterOfItsCriticalDensityKeepsItsLawsToTheBit() {
        double[] n2=pure("Nitrogen");
        var films=HeatCurve.Films.DEFAULT;
        for(int k=1;k<=69;k++) {
            double p=k*1e5;var s=MODEL.flashTP(300,p,n2,()->{});String at="300 K "+p+" Pa";
            assertEquals(FluidThermodynamics.State.VAPOR,s.oneFluidSlot(),at);
            assertEquals(Double.doubleToLongBits(MODEL.viscosity.vapor(300,s.vaporAmountsView())),Double.doubleToLongBits(MODEL.phaseViscosity(s,FluidThermodynamics.GAS,null,null)),"Wilke at "+at);
            double w=HeatCurve.liquidLikeWeight(s);
            assertEquals(0,w,"the gas film's weight at "+at);
            assertEquals(films.gas(),films.at(1-w,w),0,at);
            double compliance=s.vaporVolume()*s.phase(FluidThermodynamics.State.VAPOR).isothermalCompressibility();
            double acoustic=s.vaporVolume()/Math.sqrt(MODEL.vaporMass(s)*compliance);
            assertEquals(Double.doubleToLongBits(Math.min(MODEL.maximumVelocityMetresPerSecond(),acoustic)),Double.doubleToLongBits(MODEL.phaseVelocityLimit(s,FluidThermodynamics.GAS)),at);
        }
    }
}
