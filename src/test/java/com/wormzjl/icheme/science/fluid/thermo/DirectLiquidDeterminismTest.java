package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import org.junit.jupiter.api.Test;

/**
 * Every new evaluation path of the direct liquid path (P3 WP4) gives the same bits on a reused caller-owned workspace
 * as on fresh ones, in any order: the Region 1 workspace (temperature terms kept, pressure terms per call), the node
 * state through a prepared temperature (the Peng-Robinson and Region 1 workspaces reused across pressure and
 * composition trials) against the standalone state, and the liquid phase on a reused Peng-Robinson workspace.
 */
class DirectLiquidDeterminismTest {
    private static final String NETWORK="icheme:tjl20_methane_nitrogen";
    private final MaterialCatalog catalog=MaterialCatalog.bundled();
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(catalog,NETWORK);
    /** W3b: the node state with water runs on the network model, whose cubic holds the water (a crude package without it holds none). */
    private final FluidThermodynamics wet=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
    private static long bits(double value){return Double.doubleToRawLongBits(value);}

    @Test void aReusedRegionOneWorkspaceMatchesFreshOnesBitForBit() {
        var water=catalog.requirePackage(NETWORK).water();var reused=new WaterRegion1.Workspace();
        double[][] states={{350,1e5},{350,2e6},{300,5e5},{350,1e5},{450,1e6},{450,3e5},{300,5e5},{520,2.5e6},{350,2e4}};
        for(int pass=0;pass<2;pass++)for(int k=0;k<states.length;k++) {
            double[] s=states[pass==0?k:states.length-1-k];
            double saturation=model.saturationPressure(s[0]);
            var fresh=new WaterRegion1.Workspace();
            WaterRegion1.evaluateLiquid(water,s[0],s[1],reused);
            WaterRegion1.evaluateLiquid(water,s[0],s[1],fresh);
            String at=s[0]+" K "+s[1]+" Pa";
            assertEquals(bits(fresh.specificVolume()),bits(reused.specificVolume()),at);
            assertEquals(bits(fresh.specificEnthalpy()),bits(reused.specificEnthalpy()),at);
            assertEquals(bits(fresh.state().specificHeatCapacity()),bits(reused.state().specificHeatCapacity()),at);
            assertEquals(bits(fresh.volumePressureDerivative()),bits(reused.volumePressureDerivative()),at);
            assertEquals(bits(fresh.state().volumeTemperatureDerivative()),bits(reused.state().volumeTemperatureDerivative()),at);
            assertEquals(bits(fresh.state().volumeSecondTemperatureDerivative()),bits(reused.state().volumeSecondTemperatureDerivative()),at);
            // The stable-liquid record path is the same arithmetic.
            if(s[1]>=saturation)assertEquals(bits(fresh.specificVolume()),bits(WaterRegion1.evaluate(water,s[0],s[1]).specificVolume()),at);
        }
    }

    @Test void aNodeStateThroughAReusedPreparedTemperatureMatchesTheStandaloneStateBitForBit() {
        var model=wet;
        var crude=com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane");
        var feed=crude.crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions();
        double[] n=new double[model.componentCount()];
        for(int c=0;c<feed.length;c++)n[model.components().indexOf(crude.componentBasis().componentId(c))]=feed[c];
        n[model.components().indexOf("Nitrogen")]=.1;n[n.length-1]=.2;
        double t=350;var seed=model.flashTP(t,150000,n,()->{});
        assertTrue(TestPhases.liquidVolume(seed)>0&&seed.vaporVolume()>0&&TestPhases.waterLiquid(seed)>0&&TestPhases.waterVapor(seed)>0,"vapour, oil and water");
        var prepared=model.prepare(t);
        for(int pass=0;pass<2;pass++)for(int k=0;k<24;k++) {
            int trial=pass==0?k:23-k;double scale=1+1e-3*(trial-12);
            double p=seed.pressure()*scale,pc=TestPhases.hydrocarbonPartialPressure(seed)*scale;
            double[] l=TestPhases.liquid(seed),v=TestPhases.vapor(seed);l[trial%l.length]*=1+1e-4*trial;
            var reused=TestPhases.state(model,t,p,l,v,TestPhases.waterLiquid(seed)*scale,TestPhases.waterVapor(seed),prepared);
            var fresh=TestPhases.state(model,t,p,l,v,TestPhases.waterLiquid(seed)*scale,TestPhases.waterVapor(seed));
            String at="trial "+trial;
            assertEquals(bits(fresh.volume()),bits(reused.volume()),at);assertEquals(bits(fresh.enthalpy()),bits(reused.enthalpy()),at);
            assertEquals(bits(fresh.internalEnergy()),bits(reused.internalEnergy()),at);assertEquals(bits(TestPhases.waterVolume(fresh)),bits(TestPhases.waterVolume(reused)),at);
            assertEquals(bits(TestPhases.waterCompressibility(fresh)),bits(TestPhases.waterCompressibility(reused)),at);
            assertEquals(bits(TestPhases.liquidProperties(fresh).volumePressureDerivative()),bits(TestPhases.liquidProperties(reused).volumePressureDerivative()),at);
            assertArrayEquals(TestPhases.liquidProperties(fresh).logFugacityView(),TestPhases.liquidProperties(reused).logFugacityView(),at);
            assertEquals(bits(model.isothermalAcousticBound(fresh)),bits(model.isothermalAcousticBound(reused)),at);
        }
    }

    @Test void theLiquidOnAReusedPengRobinsonWorkspaceMatchesFreshOnes() {
        var hydrocarbon=model.hydrocarbon;int n2=hydrocarbon.components().indexOf("Nitrogen");
        double[] pure=new double[hydrocarbon.componentCount()];pure[n2]=1;
        for(double t:new double[]{77.355,90,110}) {
            var terms=hydrocarbon.prepare(t);
            for(double p:new double[]{2e6,1.5e6,1.1e6,2e6,1.8e6}) {
                var reused=hydrocarbon.phase(t,p,TestPhases.conserved(hydrocarbon,pure),Root.LIQUID,terms,false);
                var fresh=hydrocarbon.phase(t,p,TestPhases.conserved(hydrocarbon,pure),Root.LIQUID);
                assertEquals(bits(fresh.molarVolume()),bits(reused.molarVolume()));assertEquals(bits(fresh.molarEnthalpy()),bits(reused.molarEnthalpy()));
                assertEquals(bits(fresh.volumePressureDerivative()),bits(reused.volumePressureDerivative()));
                assertArrayEquals(fresh.logFugacityView(),reused.logFugacityView());
            }
        }
    }
}
