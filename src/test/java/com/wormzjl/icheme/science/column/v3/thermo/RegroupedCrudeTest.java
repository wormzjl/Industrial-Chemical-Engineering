package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.material.*;
import com.wormzjl.icheme.science.column.v3.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.TiaJuanaExtension.class)
class RegroupedCrudeTest {
    @Test void replacementHasTwelveConsistentCutsAndNoRetiredProductionBasis() {
        // Two column packages and the network package icheme:refinery; the literature package icheme:tjl19_dwsim (O3) and the
        // pilot icheme:pilot_cryogenic (O5, E25) are test fixtures since batch 2026-11-04-open-thermo-data.
        var catalog=MaterialCatalog.bundled();assertEquals(3,catalog.packages().size());
        assertFalse(catalog.packages().containsKey(PilotCryogenicTestCatalog.PACKAGE_ID));
        assertFalse(catalog.packages().containsKey("icheme:cdu17_tjl_acs2018"));
        var p=catalog.requirePackage("icheme:tjl20_methane");assertEquals(19,p.components().size());
        for(int i=1;i<=12;i++)assertEquals("crude_pc%02d".formatted(i),p.components().get(i+6));
        double[] edges={651.0249814474507,723.15,823.15,923.15,1023.15};
        for(int i=0;i<5;i++) {
            var name=catalog.name("crude_pc%02d".formatted(i+8));assertTrue(name.estimated());
            assertEquals(edges[i],name.lowerKelvin());
            if(i<4)assertEquals(edges[i+1],name.upperKelvin());else assertNull(name.upperKelvin());
        }
        assertTrue(catalog.name("crude_pc12").english().contains("above 750.0"));
        assertTrue(p.aliases().isEmpty());
    }
    @Test void reconstructedFeedConservesIndependentOriginalMassVolumeAndCaloricMoments() {
        var current=TiaJuanaTestCatalog.catalog().requirePackage("icheme:tjl19_dwsim");
        var amounts=current.assays().get("icheme:tia_juana_light").amounts();
        double mass=0,volume=0;
        for(int i=0;i<amounts.size();i++) {
            var p=current.properties().get(i);mass+=amounts.get(i)*p.molecularWeight();volume+=amounts.get(i)*p.molecularWeight()/p.density();
        }
        assertEquals(159.65286000000003,mass,1e-9);
        assertEquals(.18401666666666672,volume,1e-12);
        // The independent original is the frozen TJL19 table the reference catalog carries.
        var original=MaterialRuntime.with(V3TjlReferenceCatalog.catalog(),current.id(),()->V3CatalogPropertyPackage.require(current.id()));
        var oldFractions=original.crudeFeed("icheme:tia_juana_light").moleFractions();
        var loaded=V3CatalogPropertyPackage.require(current.id());
        for(double t:new double[]{293.15,298.15,500,638.15,900}) {
            double oldCp=0,newCp=0,oldH=0,newH=0;
            for(int i=0;i<oldFractions.length;i++) {
                oldCp+=737.6996333000835*oldFractions[i]*original.component(i).idealGasHeatCapacity(t);
                oldH+=737.6996333000835*oldFractions[i]*original.component(i).idealGasEnthalpy(t);
            }
            for(int i=0;i<amounts.size();i++) {
                newCp+=amounts.get(i)*loaded.component(i).idealGasHeatCapacity(t);
                newH+=amounts.get(i)*loaded.component(i).idealGasEnthalpy(t);
            }
            assertEquals(oldCp,newCp,Math.max(1e-8,Math.abs(oldCp)*1e-11));
            assertEquals(oldH,newH,Math.max(1e-8,Math.abs(oldH)*1e-11));
        }
    }
    /**
     * Each cut's liquid table is its published correlation, evaluated here apart from the generator
     * (tools/crude-grades/cut-tables.mjs): Twu, Ind. Eng. Chem. Process Des. Dev. 24 (1985) 1287, the kinematic viscosity
     * at 100 F and 210 F from the record's normal boiling point and specific gravity 60/60 F; the ASTM D341 two-point
     * (Walther) line through them, with the low-viscosity terms of its 2009 form; times the Rackett saturated-liquid
     * density (Spencer and Danner 1972, Yamada and Gunn Z_RA = 0.29056 - 0.08775 omega) anchored at the record's standard
     * density and held at its critical value above Tc; capped at 1e12 Pa s. Compared at table nodes (1 K apart, from
     * 293.15 K), within 1e-9; between them the table interpolates ln(mu) linearly.
     */
    @Test void cutLiquidViscosityIsTwu1985WithAstmD341() {
        var catalog=MaterialCatalog.bundled();String id="icheme:tjl20_methane";var p=catalog.requirePackage(id);
        double previous=0;
        for(int k=1;k<=12;k++) {
            String cut="crude_pc%02d".formatted(k);var record=p.properties().get(p.components().indexOf(cut));
            var table=catalog.viscosity(id,cut,ViscosityCorrelation.Phase.LIQUID).orElseThrow();
            for(double t:new double[]{293.15,311.15,323.15,372.15,450.15,600.15,800.15,900}) {
                double expected=twu(record,t),actual=table.dynamicViscosityPascalSeconds(t,ViscosityCorrelation.Phase.LIQUID);
                assertEquals(expected,actual,expected*1e-9,cut+" at "+t+" K");
            }
            // Heavier cuts are more viscous near 100 F: no shared tail.
            double at100F=table.dynamicViscosityPascalSeconds(311.15,ViscosityCorrelation.Phase.LIQUID);
            assertTrue(at100F>previous,cut);previous=at100F;
        }
    }
    private static double twu(MaterialCatalog.Property record,double kelvin) {
        double tb=record.normalBoilingPoint()*1.8,sg=record.density()/999.016;
        double tc0=tb/(.533272+.191017e-3*tb+.779681e-7*tb*tb-.284376e-10*tb*tb*tb+.959468e28/Math.pow(tb,13));
        double a=1-tb/tc0,sg0=.843593-.128624*a-3.36159*Math.pow(a,3)-13749.5*Math.pow(a,12);
        double v2ref=Math.exp(4.73227-27.0975*a+49.4491*a*a-50.4706*Math.pow(a,4))-1.5,v1ref=Math.exp(.801621+1.37179*Math.log(v2ref));
        double d=sg-sg0,x=Math.abs(1.99873-56.7394/Math.sqrt(tb));
        double f1=1.33932*x*d-21.1141*d*d/Math.sqrt(tb),f2=x*d-21.1141*d*d/Math.sqrt(tb);
        double v100=Math.exp(Math.log(v1ref+450/tb)*Math.pow((1+2*f1)/(1-2*f1),2))-450/tb;
        double v210=Math.exp(Math.log(v2ref+450/tb)*Math.pow((1+2*f2)/(1-2*f2),2))-450/tb;
        double y1=Math.log10(Math.log10(walther(v100))),y2=Math.log10(Math.log10(walther(v210)));
        double slope=(y1-y2)/(Math.log10(372.0389)-Math.log10(310.9278)),intercept=y1+slope*Math.log10(310.9278);
        double z=Math.pow(10,Math.pow(10,intercept-slope*Math.log10(kelvin))),y=z-.7;
        double centistokes=y<20?y-Math.exp(-.7487-3.295*y+.6119*y*y-.3193*y*y*y):y;
        double tc=record.pr().criticalTemperature(),zra=.29056-.08775*record.pr().acentricFactor();
        double density=record.density()*Math.pow(zra,Math.pow(1-Math.min(record.standardTemperature()/tc,1),2./7)-Math.pow(1-Math.min(kelvin/tc,1),2./7));
        return Math.min(centistokes*1e-6*density,1e12);
    }
    private static double walther(double centistokes) { return centistokes+.7+Math.exp(-1.47-1.84*centistokes-.51*centistokes*centistokes); }
    @Test void gameplayDrawsRetainTheCapturedPhysicalVolumeTargets() throws Exception {
        var catalog=MaterialCatalog.bundled();
        try(var stream=getClass().getResourceAsStream("/materials/column-draw-volume-targets.json")) {
            assertNotNull(stream);
            var targets=com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var missed=new java.util.ArrayList<String>();
            for(var element:targets.getAsJsonArray("targets")) {
                var row=element.getAsJsonObject();String crude=row.get("preset").getAsString();
                // Captured in the prescribed-drop mode; the shipped diameter marches a per-tray profile that
                // moves every product volume, and is covered by V3TrayHydraulicsColumnTest.
                var input=V3TestInputs.crude(catalog,crude).withColumnDiameter(0.0);var success=assertInstanceOf(V3ColumnOutcome.Success.class,com.wormzjl.icheme.science.column.v3.V3TestInputs.calculate(input));
                var properties=catalog.requirePackage(input.packageId());
                for(var target:row.getAsJsonObject("targets_m3_per_second").entrySet()) {
                    var product=success.result().streams().stream().filter(v->v.streamId().equals(target.getKey())).findFirst().orElseThrow();
                    double volume=0;
                    for(var fraction:product.moleFractions()) {
                        int index=properties.components().indexOf(fraction.componentId());
                        if(index>=0)volume+=product.molarFlowMolPerSecond()*fraction.moleFraction()*properties.properties().get(index).molecularWeight()/properties.properties().get(index).density();
                    }
                    double expected=target.getValue().getAsDouble();
                    System.out.println("draw volume target "+crude+" "+target.getKey()+": "+volume+" m3/s (target "+expected+", "+(volume/expected-1)+")");
                    if(!(Math.abs(volume-expected)<=expected*1e-5))missed.add(crude+" "+target.getKey()+": "+volume+" against "+expected);
                }
            }
            assertTrue(missed.isEmpty(),String.join("; ",missed));
        }
    }

    @Test void previouslyDifficultCrudesPublishAuditedClassicalSolutionsWithoutLearnedSeeds() {
        for(String crude:List.of("heavy_crude","oil_sand_dilbit")) {
            var input=V3TestInputs.crude(MaterialCatalog.bundled(),crude);long start=System.nanoTime();
            var result=V3ColumnCalculator.calculate(input,()->{
                if(System.nanoTime()-start>45_000_000_000L)throw new java.util.concurrent.CancellationException("Regrouped preset deadline");
            },0,0,V3InitializationOptions.CURRENT,V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
            var success=assertInstanceOf(V3ColumnOutcome.Success.class,result,result::toString);
            assertTrue(success.result().convergenceEvidence().satisfiesGates());
            assertTrue(success.result().acceptanceAudit().accepted());
            // Dew-point advisories are retained explicitly; numerical convergence is not a new water-phase model.
        }
    }
}
