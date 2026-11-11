package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import org.junit.jupiter.api.Test;

class HydrocarbonModelTest {
    /**
     * The direct liquid path (P3 WP4): a hydrocarbon liquid is the translated PR78 at the state pressure, so its volume,
     * {@code ln phi} and compressibility are the equation of state's own there, with no reference pressure and no global
     * compressibility; the column's data is untouched.
     */
    @Test void aLiquidIsTheEquationOfStateAtTheStatePressureWithItsOwnCompressibility() {
        var catalog=MaterialCatalog.bundled();var original=catalog.requirePackage("icheme:tjl20_methane");
        var model=new HydrocarbonModel(catalog,"icheme:tjl20_methane");
        var eos=model.translated();
        double[] liquid=new double[model.componentCount()];liquid[6]=1;
        double[][] states={{300,1e6},{300,101325},{350,1e6},{350,2e6}};
        double[] pureKappa=new double[states.length];
        for(int s=0;s<states.length;s++) {
            double t=states[s][0],p=states[s][1];
            var phase=model.phase(t,p,TestPhases.conserved(model,liquid),PengRobinsonKernel.Root.LIQUID);
            var direct=eos.evaluate(t,p,liquid,PengRobinsonKernel.Root.LIQUID,eos.prepare(t));
            assertEquals(Double.doubleToLongBits(direct.molarVolume()),Double.doubleToLongBits(phase.molarVolume()),t+" K "+p+" Pa");
            assertEquals(Double.doubleToLongBits(direct.molarEnthalpy()),Double.doubleToLongBits(phase.molarEnthalpy()));
            assertArrayEquals(direct.logFugacityCoefficients(),java.util.Arrays.copyOf(phase.logFugacity(),model.componentCount()),0,"the water slot of a dry list is NaN");
            assertEquals(direct.isothermalCompressibility(),phase.isothermalCompressibility(),1e-24);
            pureKappa[s]=phase.isothermalCompressibility();
            assertDoesNotThrow(()->model.phase(t,p,TestPhases.conserved(model,liquid),PengRobinsonKernel.Root.LIQUID,model.prepare(t),true),"a liquid root beside a vapour");
        }
        // The compressibility is a property of the state: it rises with temperature, and is not one global number.
        assertTrue(pureKappa[2]>pureKappa[0],"kappa(350 K) > kappa(300 K)");
        assertNotEquals(1e-9,pureKappa[0]);
        liquid[6]=.05;liquid[12]=.45;liquid[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE-1]=.5;
        var crude=model.phase(350,1e6,TestPhases.conserved(model,liquid),PengRobinsonKernel.Root.LIQUID);
        assertTrue(crude.molarVolume()>0&&crude.isothermalCompressibility()>0);
        assertSame(original,catalog.requirePackage("icheme:tjl20_methane"));
        assertTrue(model.revision().endsWith(":"+HydrocarbonModel.FORMULATION),model.revision());
        assertFalse(model.revision().contains("k="),"no compressibility in the revision");
    }
    @Test void liquidPressureCorrectionSatisfiesTheChemicalPotentialVolumeIdentity() {
        var model=new HydrocarbonModel(MaterialCatalog.bundled(),"icheme:tjl20_methane");
        double[] n=TestPhases.conserved(model,new double[model.componentCount()]);n[6]=1;
        double p=1e6,dtp=10,t=300;
        double gl=model.phase(t,p-dtp,n,PengRobinsonKernel.Root.LIQUID).logFugacity()[6]+Math.log(p-dtp);
        double gh=model.phase(t,p+dtp,n,PengRobinsonKernel.Root.LIQUID).logFugacity()[6]+Math.log(p+dtp);
        double volume=(gh-gl)/(2*dtp)*8.31446261815324*t;
        assertEquals(model.phase(t,p,n,PengRobinsonKernel.Root.LIQUID).molarVolume(),volume,1e-10);
    }

    // ---------------- from HybridDerivativeTest ----------------
    /** W3b: liquid water is the cubic's aqueous liquid at the state pressure (Region 1's free water is retired): its own
     * dv/dP (not a global compressibility; the state's {@code waterCompressibility}) and the Maxwell identity
     * dh/dP = v - T dv/dT at and above saturation. Below it the water share's liquid gap is held on the saturation line
     * (its value at p_sat, no P slope), so the identity is left there: 9 % at 350 K and 10 kPa (W3B_STEP35_REPORT.md). */
    @Test void stateWaterPreservesHeatAndPressureIdentities() {
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        double[] none=new double[model.componentCount()-1];
        for(double t:new double[]{298.15,350,375})for(double p:new double[]{1e4,101325,1e6,2e6}) {
            if(p<1.05*model.saturationPressure(t))continue;
            double dt=.001,dp=10;
            var s=TestPhases.state(model,t,p,none,none,1,0);
            double vp=(TestPhases.state(model,t,p+dp,none,none,1,0).volume()-TestPhases.state(model,t,p-dp,none,none,1,0).volume())/(2*dp);
            double vt=(TestPhases.state(model,t+dt,p,none,none,1,0).volume()-TestPhases.state(model,t-dt,p,none,none,1,0).volume())/(2*dt);
            double hp=(TestPhases.state(model,t,p+dp,none,none,1,0).enthalpy()-TestPhases.state(model,t,p-dp,none,none,1,0).enthalpy())/(2*dp);
            double kappa=TestPhases.waterCompressibility(s);
            assertEquals(-kappa*s.volume(),vp,1e-6*kappa*s.volume());
            assertTrue(kappa>0&&kappa!=1e-9,"water's own compressibility at "+t+" K: "+kappa);
            assertEquals(s.volume()-t*vt,hp,1e-6*s.volume());
        }
    }
    @Test void liquidMixtureChemicalPotentialPressureDerivativesSumToPhysicalVolume() {
        var model=new HydrocarbonModel(MaterialCatalog.bundled(),"icheme:tjl20_methane");
        double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE+1];n[0]=.01;n[6]=.09;n[12]=.4;n[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE-1]=.5;
        double t=350,p=1e6,dp=10;
        var mid=model.phase(t,p,n,PengRobinsonKernel.Root.LIQUID);
        var a=model.phase(t,p-dp,n,PengRobinsonKernel.Root.LIQUID).logFugacity();var b=model.phase(t,p+dp,n,PengRobinsonKernel.Root.LIQUID).logFugacity();
        double v=0;for(int i=0;i<model.componentCount();i++)v+=n[i]*(b[i]-a[i]+Math.log((p+dp)/(p-dp)))/(2*dp)*FluidThermodynamics.R*t;
        assertEquals(mid.molarVolume(),v,1e-9*mid.molarVolume()+1e-12);
    }
    /**
     * The numerical guard "no equation-of-state root" (owner rule of 2026-10-16) refuses a trial; it never escapes. The PR
     * cubic has a physical root above {@code B} at every finite state, but floating point loses it far outside the
     * numerical box: a nitrogen trace in steam at 6.6e25 Pa ({@code B = 2.7e17}: the root's distance of about one above
     * {@code B} is below one ulp of it) and at 1.5e292 Pa ({@code A B} overflows), the Newton trials of bundled fuzz seeds
     * 164 and 145 before the box bounded the partial pressure (the next test). The kernel's {@link IllegalStateException}
     * escaped the network's solvers, which refuse a trial on an {@link IllegalArgumentException}; a phase whose cubic loses
     * its root is refused with the typed {@link HydrocarbonModel.RootAbsent}, the guard for a root lost inside the box.
     */
    @Test void aTrialWhoseCubicLosesItsRootIsARefusedTrial() {
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
        double[] nitrogen=new double[model.hydrocarbon.componentCount()];
        nitrogen[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=0.5192913739164029;
        // {T, the hydrocarbon partial pressure} of the trials of seeds 164 and 145.
        double[][] trials={{695.9367657695016,6.556000279082378E25},{350.2058835382443,1.5164818447497963E292}};
        for(double[] trial:trials) {
            double t=trial[0],pc=trial[1];String at=t+" K, "+pc+" Pa";
            var lost=assertThrows(HydrocarbonModel.RootAbsent.class,()->model.hydrocarbon.phase(t,pc,TestPhases.conserved(model.hydrocarbon,nitrogen),PengRobinsonKernel.Root.VAPOR),at);
            assertInstanceOf(PengRobinsonKernel.NoPhysicalRoot.class,lost.getCause(),at);
            assertInstanceOf(IllegalArgumentException.class,lost,"the type the network's solvers refuse a trial on");
            // The same vapour at a partial pressure a gas can stand at is answered.
            assertTrue(model.hydrocarbon.phase(t,1e4,TestPhases.conserved(model.hydrocarbon,nitrogen),PengRobinsonKernel.Root.VAPOR).molarVolume()>0,at);
        }
    }
    // W3b: aPartialPressureOutsideTheBoxIsRefusedBeforeTheKernel removed. The vapour's hydrocarbon partial pressure was an
    // unknown of its own beside the free steam; with water in the cubic the vapour is one phase at the state pressure, which
    // the numerical box bounds (DataRangeContinuationTest), and a root lost inside the box is the test above.
}
