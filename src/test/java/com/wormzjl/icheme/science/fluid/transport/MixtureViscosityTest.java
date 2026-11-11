package com.wormzjl.icheme.science.fluid.transport;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.ViscosityCorrelation;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MixtureViscosityTest {
    @Test void dissolvedReferencesMatchIndependentApiCheckpointsWithoutExtrapolatingPureLiquidTables() throws Exception {
        String id=com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.NETWORK_PACKAGE;var catalog=MaterialCatalog.bundled();
        var model=new MixtureViscosity(catalog,id);var p=catalog.requirePackage(id);
        try(var input=getClass().getResourceAsStream("/data/icheme/materials/transport/dissolved_viscosity.json")) {
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:json.getAsJsonArray("curves")) {
                var curve=value.getAsJsonObject();int solute=p.components().indexOf(curve.get("component").getAsString());
                var pure=catalog.viscosity(id,p.components().get(solute),ViscosityCorrelation.Phase.LIQUID).orElseThrow();
                int carrierIndex=p.components().indexOf("crude_pc12");
                var carrier=catalog.viscosity(id,p.components().get(carrierIndex),ViscosityCorrelation.Phase.LIQUID).orElseThrow();
                int checked=0;
                for(var check:curve.getAsJsonArray("checks")) {
                    var row=check.getAsJsonObject();double t=row.get("temperature_kelvin").getAsDouble();
                    if(t<=pure.maximumTemperatureKelvin()||t<carrier.minimumTemperatureKelvin()||t>carrier.maximumTemperatureKelvin())continue;
                    double[] n=new double[catalog.conservedComponents(id).size()];n[solute]=.05;n[carrierIndex]=.95;
                    checked++;
                    double expected=Math.exp(.05*Math.log(row.get("viscosity_pascal_seconds").getAsDouble())
                            +.95*Math.log(carrier.dynamicViscosityPascalSeconds(t,ViscosityCorrelation.Phase.LIQUID)));
                    var actual=model.liquid(t,n);
                    assertTrue(actual.conditionalSoluteApproximation());
                    assertEquals(expected,actual.pascalSeconds(),expected*.0005);
                }
                assertTrue(checked>0,"Conditional viscosity checks must not be skipped: "+curve.get("component"));
            }
        }
    }
    @Test void pureUnsupportedSoluteDoesNotBecomeAnInventedLiquid() {
        var model=new MixtureViscosity(MaterialCatalog.bundled(),"icheme:tjl20_methane");
        double[] n=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE+1];n[0]=1;
        assertThrows(IllegalArgumentException.class,()->model.liquid(300,n));
        assertTrue(model.vapor(300,n)>0);
        double[] humid=n.clone();humid[humid.length-1]=.1;assertTrue(model.vapor(300,humid)>0);
    }
    @Test void crudeCompositionsHaveDifferentMaterialDependentViscosities() {
        var catalog=MaterialCatalog.bundled();var model=new MixtureViscosity(catalog,"icheme:tjl20_methane");
        double[] light=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE+1],heavy=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE+1];light[6]=.99;light[1]=.01;heavy[com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE-1]=.99;heavy[1]=.01;
        assertTrue(model.liquid(300,heavy).pascalSeconds()>model.liquid(300,light).pascalSeconds());
        // Past its tables a crude liquid is continued, its table's end value held (owner rule of 2026-10-16), not refused.
        double hot=model.liquid(1000,heavy).pascalSeconds();
        assertTrue(hot>0&&Double.isFinite(hot),"continued crude liquid viscosity "+hot);
    }
}
