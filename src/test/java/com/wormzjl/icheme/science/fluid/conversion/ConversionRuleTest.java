package com.wormzjl.icheme.science.fluid.conversion;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The fluid conversion rules (batch 2026-11-12-fluid-conversion, W1; plan 2 and 3.3): each refusal, the match order, the
 * reverse hysteresis and the volume laws, on the bundled network package and the fixture rules under
 * {@code src/test/resources/fluid_conversion/fixture/}.
 */
class ConversionRuleTest {
    static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    static final ConversionRule.Names NAMES=ConversionRules.names(CATALOG,FluidMaterialCatalog.networkPackage(CATALOG));

    /** The fixture rule files by id ({@code test:<file>}). */
    static Map<String,String> fixture() throws IOException {
        var folder=Path.of("src/test/resources/fluid_conversion/fixture");var files=new TreeMap<String,String>();
        try(var list=Files.list(folder)){for(var p:list.toList()){var name=p.getFileName().toString();files.put("test:"+name.substring(0,name.length()-5),Files.readString(p,StandardCharsets.UTF_8));}}
        return files;
    }
    static ConversionRules fixtureRules() throws IOException {return ConversionRules.parse(fixture(),NAMES,ns->!ns.equals("absentmod"));}

    private static ConversionRule.Reason refusal(String json) {
        var e=assertThrows(ConversionRule.Refusal.class,()->ConversionRule.parse("test:rule",JsonParser.parseString(json).getAsJsonObject(),NAMES));
        assertTrue(e.getMessage().startsWith("test:rule: "),e.getMessage());
        return e.reason;
    }
    @Test void eachRefusalNamesItsReason() {
        assertEquals(ConversionRule.Reason.UNKNOWN_COMPOUND,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_icheme\":{\"composition\":{\"Unobtainium\":1}}}"));
        assertEquals(ConversionRule.Reason.UNKNOWN_COMPOUND,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_forge\":{\"components\":{\"Unobtainium\":{\"min\":0.9}}}}"));
        assertEquals(ConversionRule.Reason.UNKNOWN_PRESET,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_icheme\":{\"preset\":\"no_such_crude\"}}"));
        assertEquals(ConversionRule.Reason.FRACTIONS_SUM,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_icheme\":{\"composition\":{\"Water\":0.5,\"Nitrogen\":0.4}}}"));
        assertEquals(ConversionRule.Reason.FRACTIONS_SUM,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_forge\":{\"components\":{\"Water\":{\"max\":0.5}},\"others_max\":0.01}}"));
        assertEquals(ConversionRule.Reason.EMPTY_RANGE,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_forge\":{\"components\":{\"Water\":{\"min\":0.9,\"max\":0.8}}}}"));
        assertEquals(ConversionRule.Reason.EMPTY_RANGE,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_forge\":{\"components\":{\"Water\":{\"min\":0.9}},\"temperature\":{\"min\":400,\"max\":300}}}"));
        assertEquals(ConversionRule.Reason.GAS_WITHOUT_MOLES_PER_LITRE,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"gas\",\"to_icheme\":{\"composition\":{\"Nitrogen\":1}}}"));
        assertEquals(ConversionRule.Reason.NO_DIRECTION,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\"}"));
        assertEquals(ConversionRule.Reason.TAG_TO_FORGE,refusal("{\"revision\":\"1\",\"fluid\":\"#c:water\",\"phase\":\"liquid\",\"to_forge\":{\"components\":{\"Water\":{\"min\":0.9}}}}"));
        assertEquals(ConversionRule.Reason.REVISION,refusal("{\"revision\":\"r 1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_icheme\":{\"composition\":{\"Water\":1}}}"));
        assertEquals(ConversionRule.Reason.SYNTAX,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"plasma\",\"to_icheme\":{\"composition\":{\"Water\":1}}}"));
        assertEquals(ConversionRule.Reason.SYNTAX,refusal("{\"revision\":\"1\",\"fluid\":\"a:b\",\"phase\":\"liquid\",\"to_icheme\":{\"preset\":\"medium_crude\",\"composition\":{\"Water\":1}}}"));
    }
    @Test void aRefusedFileLeavesTheOthersAndAnAbsentModIsSkippedSilently() throws IOException {
        var rules=fixtureRules();
        assertEquals(List.of("test:broken"),rules.refused().stream().map(ConversionRules.Refused::id).toList());
        assertEquals(ConversionRule.Reason.UNKNOWN_COMPOUND,rules.refused().getFirst().reason());
        assertNull(rules.rule("test:absent"),"its mod is absent");
        assertEquals(4,rules.rules().size());
        // A preset resolves to the package's own assay composition, summing to 1.
        var crude=rules.rule("test:crude").toIChemE().composition();
        assertTrue(crude.size()>5);assertEquals(1,crude.values().stream().mapToDouble(Double::doubleValue).sum(),1e-9);
        assertEquals("2",rules.rule("test:crude").revision());
    }
    @Test void rulesSortByPriorityThenId() throws IOException {
        var rules=fixtureRules();
        assertEquals(List.of("test:hot_water","test:crude","test:nitrogen","test:water"),rules.rules().stream().map(ConversionRule::id).toList());
        var water=drawnWater(300);
        assertEquals("test:water",rules.reverse(water,null,false).id());
        assertEquals("test:hot_water",rules.reverse(drawnWater(360),null,false).id(),"the higher priority wins where both hold");
        assertEquals("test:water",rules.forward("minecraft:water",Set.of(),false).id());
        assertEquals("test:crude",rules.forward("othermod:crude",Set.of("c:crude_oil"),false).id(),"a tag matches its members");
        assertNull(rules.forward("test:nitrogen",Set.of(),false),"the open converter refuses a gas rule");
        assertEquals("test:nitrogen",rules.forward("test:nitrogen",Set.of(),true).id());
        assertNull(rules.reverse(drawnGas(),null,false),"a gas rule matches only a drawn gas on the closed converter");
        assertEquals("test:nitrogen",rules.reverse(drawnGas(),null,true).id());
    }
    @Test void theRuleThatMatchedLastKeepsMatchingInsideTwoPercent() throws IOException {
        var rules=fixtureRules();
        // hot_water holds from 350 K: widened by 2 %, from 343 K while it matched last.
        assertEquals("test:water",rules.reverse(drawnWater(345),null,false).id());
        assertEquals("test:hot_water",rules.reverse(drawnWater(345),"test:hot_water",false).id(),"hysteresis in");
        assertEquals("test:water",rules.reverse(drawnWater(342),"test:hot_water",false).id(),"hysteresis out");
        // water holds to 373.15 K; widened, to 380.6 K, but the higher-priority hot_water takes over only when water lets go.
        assertEquals("test:water",rules.reverse(drawnWater(376),"test:water",false).id());
        assertEquals("test:hot_water",rules.reverse(drawnWater(382),"test:water",false).id());
        // Composition: water's 0.99 floor widens to 0.9702; the others' 0.01 to 0.0102.
        var wet=drawn(Map.of("Water",.985,"Nitrogen",.015),300);
        assertNull(rules.reverse(wet,null,false));
        assertNull(rules.reverse(wet,"test:water",false),"0.015 others exceed even the widened 0.0102");
        var near=drawn(Map.of("Water",.9899,"Nitrogen",.0101),300);
        assertNull(rules.reverse(near,null,false));
        assertEquals("test:water",rules.reverse(near,"test:water",false).id());
    }
    @Test void theVolumeLawsOfSectionTwo() throws IOException {
        // Reverse: 1 L/s of an 850 kg/m3 liquid is 0.85 kg/s; 0.85 kg of it fills 1 L (1 mB).
        assertEquals(.85,ConversionLaws.kilograms(1,850),1e-15);
        assertEquals(1,ConversionLaws.litres(.85,850),1e-15);
        // Forward: a litre gives rho_rule / M moles, rho_rule the package's liquid density at the rule's T and P.
        FluidThermodynamics model=FluidTestSupport.networkModel();
        var water=fixtureRules().rule("test:water").toIChemE();
        var per=ConversionLaws.perLitre(water,model);
        int w=model.components().indexOf("Water");
        var state=model.flashTP(298.15,101325,per.fractions(),()->{});
        double rho=model.liquidDensity(state),m=model.molecularWeight(w);
        assertEquals(rho,per.density(),1e-12);
        assertEquals(rho/m/1000,per.moles(),1e-12);
        assertTrue(rho>990&&rho<1005,"water at 25 C: "+rho+" kg/m3");
        assertEquals(rho/1000,per.moles(1)[w]*m,1e-12,"a litre weighs rho / 1000 kg");
        // A gas rule gives its own moles per litre.
        var n2=ConversionLaws.perLitre(fixtureRules().rule("test:nitrogen").toIChemE(),model);
        assertEquals(.0409,n2.moles(),0);
        // A rule naming a compound off the model's list gives none.
        assertNull(ConversionLaws.fractions(water,List.of("Nitrogen")));
    }

    /** The bundled rules (W6) under {@code data/icheme/icheme/fluid_conversion/}, by id ({@code icheme:<path>}). */
    static Map<String,String> bundled() throws IOException {
        var folder=Path.of("src/main/resources/data/icheme/icheme/fluid_conversion");var files=new TreeMap<String,String>();
        try(var walk=Files.walk(folder)){for(var p:walk.filter(f->f.toString().endsWith(".json")).toList()){var name=folder.relativize(p).toString().replace('\\','/');files.put("icheme:"+name.substring(0,name.length()-5),Files.readString(p,StandardCharsets.UTF_8));}}
        return files;
    }
    /** Every bundled rule loads on the real catalog with every mod present; the absent mods' rules are skipped, never refused. */
    @Test void everyBundledRuleLoadsOnTheRealCatalog() throws IOException {
        var files=bundled();
        assertTrue(files.size()>=20,"the bundled rules: "+files.keySet());
        var all=ConversionRules.parse(files,NAMES,ns->true);
        assertEquals(List.of(),all.refused().stream().map(ConversionRules.Refused::message).toList());
        assertEquals(files.size(),all.rules().size());
        var none=ConversionRules.parse(files,NAMES,ns->ns.equals("minecraft")||ns.equals("c"));
        assertEquals(List.of(),none.refused());
        assertEquals(List.of("icheme:c/crude_oil","icheme:minecraft/water"),none.rules().stream().map(ConversionRule::id).sorted().toList(),"without the other mods, water and the crude tag stay");
        assertEquals("icheme:minecraft/water",all.forward("minecraft:water",Set.of(),false).id());
        assertEquals("icheme:tfmg/crude_oil",all.forward("tfmg:crude_oil",Set.of("c:crude_oil"),false).id(),"an explicit id before the tag");
        assertEquals("icheme:c/crude_oil",none.forward("othermod:crude",Set.of("c:crude_oil"),false).id());
        assertEquals("icheme:minecraft/water",all.reverse(drawnWater(300),null,false).id());
        // A column's cuts, by mass on the package's pseudo-components, go to their products; each window holds its own cut alone.
        assertEquals("kerosene",product(all,cut(Map.of("crude_pc03",.3,"crude_pc04",.4,"crude_pc05",.3))));
        assertEquals("diesel",product(all,cut(Map.of("crude_pc04",.2,"crude_pc05",.3,"crude_pc06",.35,"crude_pc07",.15))));
        assertEquals("lubricant",product(all,cut(Map.of("crude_pc07",.2,"crude_pc08",.6,"crude_pc09",.2))));
        assertEquals("heavy_oil",product(all,cut(Map.of("crude_pc08",.2,"crude_pc09",.3,"crude_pc10",.3,"crude_pc11",.1,"crude_pc12",.1))));
        assertEquals("gasoline",product(all,cut(Map.of("N-pentane",.2,"crude_pc01",.5,"crude_pc02",.3))),"gasoline before naphtha, by priority");
        assertEquals("naphtha",product(all,cut(Map.of("crude_pc01",.3,"crude_pc02",.4,"crude_pc03",.3))),"too heavy for gasoline");
    }
    /** The product (the fluid id's path) of the reverse rule {@code rules} picks for {@code drawn}; equal priorities between mods go by rule id. */
    private static String product(ConversionRules rules,ConversionRules.Drawn drawn){var f=rules.reverse(drawn,null,false).fluid();return f.substring(f.indexOf(':')+1);}
    /** A drawn liquid at 300 K and 1 atm with mass fractions {@code w} on the network package's list. */
    private static ConversionRules.Drawn cut(Map<String,Double> w) {
        FluidThermodynamics model=FluidTestSupport.networkModel();var ids=model.components();var mw=model.molecularWeights();var n=new double[ids.size()];
        for(var e:w.entrySet()){int i=ids.indexOf(e.getKey());assertTrue(i>=0,e.getKey());n[i]=e.getValue()/mw[i];}
        return new ConversionRules.Drawn(ids,n,mw,300,101325,false);
    }

    private static ConversionRules.Drawn drawnWater(double t){return drawn(Map.of("Water",1.),t);}
    private static ConversionRules.Drawn drawn(Map<String,Double> x,double t) {
        var ids=List.of("Nitrogen","Water");var n=new double[]{x.getOrDefault("Nitrogen",0.),x.getOrDefault("Water",0.)};
        return new ConversionRules.Drawn(ids,n,new double[]{.0280134,.01801528},t,101325,false);
    }
    private static ConversionRules.Drawn drawnGas() {
        return new ConversionRules.Drawn(List.of("Nitrogen","Water"),new double[]{.99,.01},new double[]{.0280134,.01801528},300,101325,true);
    }
}
