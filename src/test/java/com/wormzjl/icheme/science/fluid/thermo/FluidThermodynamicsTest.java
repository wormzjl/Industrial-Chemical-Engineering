package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import org.junit.jupiter.api.Test;
import java.util.Arrays;

/** W3b: on the network package, which holds water in its cubic (a crude package without it holds no water). */
class FluidThermodynamicsTest {
    private final FluidThermodynamics model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();
    private final int water=model.componentCount()-1;
    private double[] pure(int i) {double[] n=new double[model.componentCount()];n[i]=1;return n;}
    private void closure(double[] expected,FluidThermodynamics.State state) {
        for(int i=0;i<model.hydrocarbon.componentCount();i++)assertEquals(expected[i],TestPhases.liquid(state)[i]+TestPhases.vapor(state)[i],1e-13);
        assertEquals(expected[water],TestPhases.waterLiquid(state)+TestPhases.waterVapor(state),1e-13);
        assertEquals(state.enthalpy()-state.pressure()*state.volume(),state.internalEnergy(),1e-10);
        if(state.vaporVolume()>0)assertEquals(state.pressure(),TestPhases.hydrocarbonPartialPressure(state)+TestPhases.waterPartialPressure(state),.01);
        assertTrue(Double.isFinite(state.volume())&&state.volume()>0);
    }
    @Test void pureWaterLiquidSteamAndHydrocarbonLimits() {
        var weights=model.molecularWeights();
        assertEquals(model.componentCount(),weights.length);
        assertEquals("Water",model.components().getLast());
        assertThrows(UnsupportedOperationException.class,()->model.components().add("Mutated"));
        double original=weights[0];weights[0]=0;
        assertEquals(original,model.molecularWeights()[0],0,"Weight arrays must be independently owned");
        var pureWater=pure(water);var l=model.flashTP(300,101325,pureWater,()->{});closure(pureWater,l);assertEquals(1,TestPhases.waterLiquid(l));
        var steam=model.flashTP(400,100000,pureWater,()->{});closure(pureWater,steam);assertEquals(1,TestPhases.waterVapor(steam));
        var methane=pure(0);var gas=model.flashTP(300,101325,methane,()->{});closure(methane,gas);assertEquals(1,TestPhases.vapor(gas)[0],1e-8);
        assertEquals(model.componentCount(),gas.componentCount());double[] exposed=TestPhases.vapor(gas);exposed[0]=0;
        assertEquals(1,TestPhases.vapor(gas)[0],1e-8,"State component access must remain defensive");
        var pentane=pure(6);var liquid=model.flashTP(300,1e6,pentane,()->{});closure(pentane,liquid);assertEquals(1,TestPhases.liquid(liquid)[6],1e-8);
        exposed=TestPhases.liquid(liquid);exposed[6]=0;assertEquals(1,TestPhases.liquid(liquid)[6],1e-8,"State component access must remain defensive");
    }
    @Test void crudePresetsAndThreePhaseWaterMaintainComponentInventory() {
        var catalog=MaterialCatalog.bundled();
        for(String id:new String[]{"icheme:light_crude","icheme:medium_crude","icheme:heavy_crude"}) {
            // The dilbit is left out: its condensate vapour at 350 K and 1 atm takes up the 0.2 mol of water whole (no free water).
            var p=catalog.requirePackage("icheme:tjl20_methane");
            var composition=V3PengRobinsonThermo.fromRegisteredPackage(p.id()).crudeFeed(id).moleFractions();
            var n=new double[model.componentCount()];
            for(int c=0;c<composition.length;c++)n[model.components().indexOf(p.components().get(c))]+=composition[c];
            n[water]=.2;
            var state=model.flashTP(350,101325,n,()->{});closure(n,state);
            assertTrue(TestPhases.liquidVolume(state)>0,id);assertTrue(TestPhases.waterVolume(state)>0,id);assertTrue(state.vaporVolume()>0,id);
        }
    }
    @Test void subsaturatedWaterUsesTheSameVaporVolumeAsHydrocarbons() {
        var n=pure(0);n[water]=.001;var state=model.flashTP(350,101325,n,()->{});closure(n,state);
        assertEquals(0,TestPhases.waterLiquid(state));assertTrue(TestPhases.waterPartialPressure(state)<model.saturationPressure(350));
    }
}
