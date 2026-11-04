package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.wormzjl.icheme.science.fluid.solver.*;

class NitrogenInitializationTest {
    private static final String COLUMN="icheme:tjl20_methane";
    private static final String NETWORK=FluidMaterialCatalog.NETWORK_PACKAGE;
    @Test void placementCreatesRealNitrogenInventoryWithoutChangingTheColumnPackage() {
        var catalog=MaterialCatalog.bundled();var original=catalog.requirePackage(COLUMN);
        var model=FluidThermodynamics.forNetwork(catalog,NETWORK);var state=model.initialNitrogenCharge(1,298.15,101325,()->{});
        assertEquals(catalog.requirePackage(NETWORK).components().size(),model.hydrocarbon.componentCount());assertEquals(1,state.volume(),1e-12);assertEquals(0,state.liquidVolume());
        assertTrue(state.mass()>1.13&&state.mass()<1.16);assertEquals(0,state.waterLiquid()+state.waterVapor());
        int nitrogen=model.hydrocarbon.components().indexOf("Nitrogen");assertEquals(catalog.requirePackage(NETWORK).components().indexOf("Nitrogen"),nitrogen);assertTrue(state.vapor()[nitrogen]>40);
        assertTrue(model.viscosity.vapor(298.15,state.vapor(),0)>1.7e-5);
        assertSame(original,catalog.requirePackage(COLUMN));assertEquals(model.hydrocarbon.componentCount()-1,catalog.requirePackage(COLUMN).components().size());
    }
    /** The model is the named package's, whatever its id (a saved world names its basis by compound ids, batch
     * 2026-11-04-refinery-compounds, W4); one without nitrogen refuses a nitrogen charge rather than place another gas. */
    @Test void aModelWithoutNitrogenRefusesANitrogenCharge() {
        var catalog=MaterialCatalog.bundled();var column=FluidThermodynamics.forNetwork(catalog,COLUMN);
        assertEquals(catalog.requirePackage(COLUMN).components().size()+1,column.components().size());
        assertThrows(IllegalStateException.class,()->column.initialNitrogenCharge(1,298.15,101325,()->{}));
    }
    @Test void nitrogenCaloricFitMatchesIndependentShomateCheckpoints() throws Exception {
        var cp=MaterialCatalog.bundled().requirePackage(NETWORK).properties().getLast().cp();
        try(var input=getClass().getResourceAsStream("/fluid/reference/nitrogen-cp-checks.json")) {
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var entry:json.getAsJsonArray("points")) {
                var row=entry.getAsJsonObject();double t=row.get("temperatureKelvin").getAsDouble(),actual=0,power=1;
                for(double coefficient:cp){actual+=power*coefficient;power*=t-298.15;}
                assertEquals(row.get("referenceCp").getAsDouble(),actual,actual*.001);
            }
        }
    }
    @Test void smallWaterChargeConservesEnergyUsingOnlyTheInitialNitrogenAndFluid() {
        var model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),NETWORK);
        var initial=model.initialNitrogenCharge(1,298.15,101325,()->{});var amounts=PhaseLayout.totalAmounts(initial);
        int water=model.hydrocarbon.componentCount();amounts[water]=.01/model.waterMolecularWeight;
        double energy=initial.internalEnergy()+amounts[water]*model.waterLiquid(298.15,101325).molarEnthalpy();
        var seed=model.flashTP(280,101325,amounts,()->{});var layout=new PhaseLayout(model,seed);
        var solution=SparseNewton.solve(layout.wallClosure(amounts,energy,1,Double.NaN),layout.encode(seed),new SparseNewton.Settings(30,1e-10,1e-6,24),()->{});
        var result=layout.decode(solution.variables(),0);
        assertEquals(energy,result.internalEnergy(),1e-4);assertArrayEquals(amounts,PhaseLayout.totalAmounts(result),1e-10);
        assertTrue(result.temperature()>273.16&&result.temperature()<298.15);assertTrue(result.waterLiquid()>0);assertTrue(result.waterVapor()>0);
    }
}
