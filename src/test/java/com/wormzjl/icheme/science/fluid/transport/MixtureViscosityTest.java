package com.wormzjl.icheme.science.fluid.transport;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.ViscosityCorrelation;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MixtureViscosityTest {
    /**
     * The declared error of the dense viscosity against CoolProp 8.0.0 on its pure-fluid table (CoolProp's own dilute
     * viscosity plus the residual at CoolProp's density), percent: the first run's worst (2026-11-15), rounded up to the
     * next whole unit.
     */
    static final double DENSE_DECLARED_PERCENT = 16;

    /**
     * The dense-fluid residual viscosity (batch 2026-11-15-supercritical-fluids, S3, owner decision O5 A): exactly zero at
     * zero density (a gas keeps its dilute viscosity, so {@code dense} is {@code vapor} there), increasing in the reduced
     * density from 0 to 3 for pure carbon dioxide, and, added to CoolProp's dilute viscosity at CoolProp's density, within
     * {@link #DENSE_DECLARED_PERCENT} of CoolProp's viscosity on the five fluids of {@code viscosity-reference.json} (Tr 1.05
     * to 1.5, 5 to 30 MPa).
     */
    @Test void theDenseResidualIsZeroAtZeroDensityIncreasingAndWithinItsDeclaredError() throws Exception {
        String id=com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.NETWORK_PACKAGE;var catalog=MaterialCatalog.bundled();
        var model=new MixtureViscosity(catalog,id);var basis=catalog.conservedComponents(id);var p=catalog.requirePackage(id);
        double[] co2=new double[basis.size()];co2[basis.indexOf("CarbonDioxide")]=1;
        double[] mixture=new double[basis.size()];mixture[basis.indexOf("Methane")]=.7;mixture[basis.indexOf("crude_pc05")]=.2;mixture[basis.size()-1]=.1;
        for(double[] n:java.util.List.of(co2,mixture)) {
            assertEquals(0.0,model.residual(0,n),"zero at zero density");
            assertEquals(model.vapor(300,n),model.dense(300,n,0,null,null),"dense is dilute at zero density");
        }
        double vc=p.properties().get(p.components().indexOf("CarbonDioxide")).criticalMolarVolume();
        double last=-1;
        for(int k=0;k<=300;k++) {
            double r=model.residual(k*0.01/vc,co2);
            assertTrue(r>last,"increasing at rho_r "+k*0.01);last=r;
        }
        java.util.Map<String,String> ids=java.util.Map.of("CarbonDioxide","CarbonDioxide","Nitrogen","Nitrogen","Methane","Methane","Ethane","Ethane","n-Propane","Propane");
        double worst=0;String at="-";int states=0;
        try(var input=getClass().getResourceAsStream("/science/thermo/coolprop/viscosity-reference.json")) {
            var json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var value:json.getAsJsonArray("fluids")) {
                var fluid=value.getAsJsonObject();double[] n=new double[basis.size()];n[basis.indexOf(ids.get(fluid.get("fluid").getAsString()))]=1;
                for(var s:fluid.getAsJsonArray("states")) {
                    var state=s.getAsJsonObject();
                    double mu=state.get("viscosity_dilute").getAsDouble()+model.residual(state.get("Dmolar").getAsDouble(),n);
                    double deviation=100*(mu/state.get("viscosity").getAsDouble()-1);states++;
                    if(Math.abs(deviation)>Math.abs(worst)){worst=deviation;at=fluid.get("fluid").getAsString()+" "+state.get("T").getAsDouble()+" K "+state.get("P").getAsDouble()+" Pa";}
                }
            }
        }
        System.out.println(String.format(java.util.Locale.ROOT,"dense viscosity against CoolProp: %d states, worst %+.2f %% at %s",states,worst,at));
        assertEquals(60,states);
        assertTrue(Math.abs(worst)<=DENSE_DECLARED_PERCENT,"worst "+worst+" % at "+at);
    }
    /**
     * S2b of batch 2026-11-15-supercritical-fluids: {@code dense} takes up the residual between reduced densities 0.25 and
     * 0.5. At or below 0.25 it is the Wilke value itself, bit for bit; from 0.5 it is the dilute value plus the whole
     * residual, bit for bit; the weight is a smoothstep, so the value and its slope in density are continuous at both ends
     * (one-sided differences agree).
     */
    @Test void theResidualsOnsetIsExactBelowAQuarterAndC1AtBothEnds() {
        String id=com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.NETWORK_PACKAGE;var catalog=MaterialCatalog.bundled();
        var model=new MixtureViscosity(catalog,id);var basis=catalog.conservedComponents(id);
        double[] co2=new double[basis.size()];co2[basis.indexOf("CarbonDioxide")]=1;
        double[] mixture=new double[basis.size()];mixture[basis.indexOf("Methane")]=.7;mixture[basis.indexOf("crude_pc05")]=.2;mixture[basis.size()-1]=.1;
        for(double[] n:java.util.List.of(co2,mixture))for(double t:new double[]{250,320,500}) {
            double perReduced=1/model.reducedDensity(1,n),dilute=model.vapor(t,n);
            for(double r:new double[]{0,1e-3,.1,.2,.249999,.25}) {
                assertEquals(Double.doubleToLongBits(dilute),Double.doubleToLongBits(model.dense(t,n,r*perReduced,null,null)),"exact at rho_r "+r);
            }
            for(double r:new double[]{.5,.5000001,1,2,3})
                assertEquals(Double.doubleToLongBits(dilute+model.residual(r*perReduced,n)),Double.doubleToLongBits(model.dense(t,n,r*perReduced,null,null)),"the whole residual at rho_r "+r);
            double inside=model.dense(t,n,.375*perReduced,null,null);
            assertTrue(inside>dilute&&inside<dilute+model.residual(.375*perReduced,n),"between at rho_r 0.375");
            double h=1e-5;
            for(double edge:new double[]{MixtureViscosity.DENSE_ONSET,MixtureViscosity.DENSE_FULL}) {
                double at=model.dense(t,n,edge*perReduced,null,null);
                double left=(at-model.dense(t,n,(edge-h)*perReduced,null,null))/h,right=(model.dense(t,n,(edge+h)*perReduced,null,null)-at)/h;
                double scale=model.residual(1.1*perReduced,n)-model.residual(.9*perReduced,n);
                assertEquals(left,right,2e-3*scale/.2,"C1 at rho_r "+edge+" ("+t+" K)");
            }
        }
        assertEquals(0,MixtureViscosity.smoothstep(Double.NaN));assertEquals(0,MixtureViscosity.smoothstep(-1));
        assertEquals(1,MixtureViscosity.smoothstep(1));assertEquals(1,MixtureViscosity.smoothstep(2));assertEquals(.5,MixtureViscosity.smoothstep(.5));
    }
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

    @Test void reusedPureTermsMatchFreshValuesAtEachTemperature() {
        String id="icheme:tjl20_methane";
        var catalog=MaterialCatalog.bundled();
        var model=new MixtureViscosity(catalog,id);
        var basis=catalog.conservedComponents(id);
        double[] vapor=new double[basis.size()],liquid=new double[basis.size()];
        vapor[basis.indexOf("Methane")]=.8;
        vapor[basis.indexOf("Water")]=.2;
        liquid[basis.indexOf("N-pentane")]=.9;
        liquid[basis.indexOf("Water")]=.1;
        var reuse=model.prepare(300);
        for(double temperature:new double[]{300,325,300}) {
            assertSame(reuse,model.prepare(temperature,reuse));
            assertEquals(model.vapor(temperature,vapor),model.vapor(temperature,vapor,null,reuse));
            assertEquals(model.liquid(temperature,liquid),model.liquid(temperature,liquid,reuse));
            assertEquals(model.waterLiquid(temperature),model.waterLiquid(temperature,reuse));
        }
        assertThrows(IllegalArgumentException.class,()->new MixtureViscosity(catalog,id).prepare(300,reuse));
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
