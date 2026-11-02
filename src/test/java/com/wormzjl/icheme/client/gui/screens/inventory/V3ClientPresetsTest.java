package com.wormzjl.icheme.client.gui.screens.inventory;

import com.google.gson.*;
import com.wormzjl.icheme.science.column.v3.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class V3ClientPresetsTest {
    @TempDir Path game;
    private V3ClientPresets library(){return new V3ClientPresets(game,n->getClass().getClassLoader().getResourceAsStream(n));}
    private static V3ColumnInput reference(){var c=MaterialCatalog.bundled();return c.presets().column("tia_juana").input(c);}
    private static V3EditorDraft draft(){
        var input=reference();return new V3EditorDraft(input,weights(input));
    }
    private static java.util.List<Double> weights(V3ColumnInput input){
        return MaterialCatalog.bundled().requirePackage(input.packageId()).properties().stream().map(MaterialCatalog.Property::molecularWeight).toList();
    }
    /** The column a stream of {@code parts} sits on: the one with the fewest components that holds them. */
    private static V3ColumnInput sourceFor(java.util.Collection<String> parts){
        var c=MaterialCatalog.bundled();
        return c.presets().columns().keySet().stream().sorted().map(id->c.presets().column(id)).filter(p->p.visible()).map(p->p.input(c))
                .filter(i->i.componentBasis().componentIds().containsAll(parts)).min(java.util.Comparator.comparingInt(i->i.componentBasis().componentCount())).orElseThrow();
    }
    @Test void bundledLibrariesAreIndependentAndPreserveAuthoredOperatingData(){
        var l=library();var columns=l.refresh(V3ClientPresets.Kind.COLUMN);var mixtures=l.refresh(V3ClientPresets.Kind.MIXTURE);
        assertTrue(columns.problems().isEmpty(),()->columns.problems().toString());assertTrue(mixtures.problems().isEmpty());
        assertEquals(7,columns.entries().size());assertEquals(8,mixtures.entries().size());
        for(var entry:columns.entries()){
            if(entry.file().equals("pot.json"))continue;
            // A crude is two files now: the stream, then the tower on it, as the calculator's Templates load them.
            var stream=(V3ClientPresets.Mixture)mixtures.entries().stream().filter(e->e.file().equals(entry.file())).findFirst().orElseThrow().preset();
            var d=draft();var on=sourceFor(stream.amounts().keySet());
            d.loadMixture(stream,on,weights(on));d.loadColumn(((V3ClientPresets.Column)entry.preset()).template());
            var changed=d.assemble();assertEquals(reference().packageId(),changed.packageId());
            var crude=V3TestInputs.crude(MaterialCatalog.bundled(),entry.file().replace(".json",""));
            assertEquals(crude.stageCount(),changed.stageCount());assertEquals(crude.feedStageNumber(),changed.feedStageNumber());
            assertArrayEquals(crude.feedComponentMolarFlowsMolPerSecond(),changed.feedComponentMolarFlowsMolPerSecond(),1e-9);
            assertEquals(crude.feedTemperatureKelvin(),changed.feedTemperatureKelvin(),1e-9);
            assertEquals(crude.steamFeeds(),changed.steamFeeds());
            for(int i=0;i<crude.sideDraws().size();i++)assertEquals(crude.sideDraws().get(i).molarFlowMolPerSecond(),changed.sideDraws().get(i).molarFlowMolPerSecond(),1e-9);
            for(int i=0;i<crude.pumparounds().size();i++)assertEquals(crude.pumparounds().get(i).dutyWatts(),changed.pumparounds().get(i).dutyWatts(),1e-3);
        }
    }
    /** A stream sits on the fewest components that hold it (a file names no package), and a part nothing holds finds nothing. */
    @Test void aStreamFindsItsComponentsByTheirIds(){
        var mixtures=library().refresh(V3ClientPresets.Kind.MIXTURE).entries();
        for(var entry:mixtures){
            var mixture=(V3ClientPresets.Mixture)entry.preset();
            assertEquals(entry.file().equals("tia_juana_nitrogen.json")?"icheme:tjl20_methane_nitrogen":"icheme:tjl20_methane",sourceFor(mixture.amounts().keySet()).packageId(),entry.file());
        }
        assertThrows(NoSuchElementException.class,()->sourceFor(Set.of("Unobtainium")));
    }
    /** Old files (process simulator plan, section 3.7): a column's feed flow is its design feed, and a mixture's package, gone or not, decides nothing. */
    @Test void filesOfTheOlderBuildsLoad()throws Exception{
        String column=corpus("column-schema-2.json"),mixture=corpus("mixture-schema-1.json");
        assertTrue(mixture.contains("icheme:bonga_tjl20"),"the image names a package this build does not have");
        var c=(V3ClientPresets.Column)V3ClientPresets.decode(column,V3ClientPresets.Kind.COLUMN);
        var m=(V3ClientPresets.Mixture)V3ClientPresets.decode(mixture,V3ClientPresets.Kind.MIXTURE);
        assertEquals(2650.277212975707,c.template().designFeed());
        assertTrue(m.flow().isEmpty()&&m.temperature().isEmpty()&&m.pressure().isEmpty(),"an old mixture is a mixture only");
        var l=library();
        Files.createDirectories(l.directory(V3ClientPresets.Kind.COLUMN));Files.createDirectories(l.directory(V3ClientPresets.Kind.MIXTURE));
        Files.writeString(l.directory(V3ClientPresets.Kind.COLUMN).resolve("old.json"),column);Files.writeString(l.directory(V3ClientPresets.Kind.MIXTURE).resolve("old.json"),mixture);
        var columns=l.refresh(V3ClientPresets.Kind.COLUMN);var mixtures=l.refresh(V3ClientPresets.Kind.MIXTURE);
        assertTrue(columns.problems().isEmpty()&&mixtures.problems().isEmpty(),()->columns.problems()+" "+mixtures.problems());
        assertEquals(8,columns.entries().size());assertEquals(9,mixtures.entries().size());
        // On the default feed: the feed and its temperature stay, the mixture changes, and the tower's flows scale to the feed they meet.
        var on=sourceFor(m.amounts().keySet());assertEquals(reference().packageId(),on.packageId());
        var d=draft();d.loadMixture(m,on,weights(on));d.loadColumn(c.template());var loaded=d.assemble();
        double feed=Arrays.stream(reference().feedComponentMolarFlowsMolPerSecond()).sum(),k=feed*3.6/c.template().designFeed();
        assertEquals(feed,Arrays.stream(loaded.feedComponentMolarFlowsMolPerSecond()).sum(),1e-9);assertEquals(reference().feedTemperatureKelvin(),loaded.feedTemperatureKelvin(),1e-9);
        assertFalse(Arrays.equals(reference().feedComponentMolarFlowsMolPerSecond(),loaded.feedComponentMolarFlowsMolPerSecond()),"the mixture is the old file's");
        assertEquals(c.template().draws().getFirst().flow()*k/3.6,loaded.sideDraws().getFirst().molarFlowMolPerSecond(),1e-9);
        assertEquals(c.template().steam().getFirst().flow()*k/3.6,loaded.steamFeeds().getFirst().molarFlowMolPerSecond(),1e-9);
        assertEquals(-c.template().pumparounds().getFirst().cooling()*k*1e6,loaded.pumparounds().getFirst().dutyWatts(),1e-3);
    }
    /** A saved column names its feed as a design feed and holds no feed of its own; a stream template's conditions read as they stand. */
    @Test void aColumnFileHoldsNoFeedAndAStreamFileMayHoldConditions(){
        var o=JsonParser.parseString(V3ClientPresets.columnJson("case",reference())).getAsJsonObject();
        assertEquals(3,o.get("schema_version").getAsInt());assertFalse(o.has("feed_flow_kmol_h")||o.has("feed_temperature_kelvin"));
        assertEquals(Arrays.stream(reference().feedComponentMolarFlowsMolPerSecond()).sum()*3.6,o.get("design_feed_kmol_h").getAsDouble(),1e-9);
        var d=draft();d.set("s0","1000");d.loadColumn(((V3ClientPresets.Column)V3ClientPresets.decode(o.toString(),V3ClientPresets.Kind.COLUMN)).template());
        assertEquals(reference().sideDraws().getFirst().molarFlowMolPerSecond()*1000/o.get("design_feed_kmol_h").getAsDouble(),d.assemble().sideDraws().getFirst().molarFlowMolPerSecond(),1e-9);
        String json="{\"schema_version\":1,\"kind\":\"mixture\",\"name\":\"tank\",\"translation_key\":\"\",\"basis\":\"mole\",\"temperature_kelvin\":313.15,\"pressure_bar\":1.01,\"flow_kmol_h\":5000,\"components\":[{\"id\":\"Propane\",\"amount\":1}]}";
        var m=(V3ClientPresets.Mixture)V3ClientPresets.decode(json,V3ClientPresets.Kind.MIXTURE);
        assertEquals(313.15,m.temperature().orElseThrow());assertEquals(1.01,m.pressure().orElseThrow());assertEquals(5000,m.flow().orElseThrow());
        assertThrows(IllegalArgumentException.class,()->V3ClientPresets.decode(json.replace("\"flow_kmol_h\":5000","\"flow_kmol_h\":-1"),V3ClientPresets.Kind.MIXTURE));
        var loaded=draft();var on=sourceFor(m.amounts().keySet());loaded.loadMixture(m,on,weights(on));
        assertEquals(5000/3.6,Arrays.stream(loaded.assemble().feedComponentMolarFlowsMolPerSecond()).sum(),1e-9);assertEquals(313.15,loaded.assemble().feedTemperatureKelvin(),1e-9);
        for(var entry:library().refresh(V3ClientPresets.Kind.MIXTURE).entries()){
            var stream=(V3ClientPresets.Mixture)entry.preset();boolean residue=entry.file().equals("tia_juana_residue.json");
            assertEquals(!residue,stream.flow().isPresent(),entry.file());assertEquals(!residue,stream.temperature().isPresent(),entry.file());assertTrue(stream.pressure().isEmpty(),entry.file());
        }
    }
    private String corpus(String file)throws Exception{
        try(var stream=getClass().getClassLoader().getResourceAsStream("compat/presets/"+file)){return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    /** The VDU feed template (batch 2026-10-26-multi-machine-framework, plan 4.4): the crude from 378 C up in its own proportions, and a 5 % gas oil tail. */
    @Test void theVduFeedTemplateIsTheCrudesResidueWithAGasOilTail(){
        var mixtures=library().refresh(V3ClientPresets.Kind.MIXTURE).entries();
        var residue=(V3ClientPresets.Mixture)mixtures.stream().filter(e->e.file().equals("tia_juana_residue.json")).findFirst().orElseThrow().preset();
        var crude=(V3ClientPresets.Mixture)mixtures.stream().filter(e->e.file().equals("tia_juana.json")).findFirst().orElseThrow().preset();
        assertFalse(residue.mass());
        var d=draft();d.composition().load(residue.amounts(),residue.mass());
        var ids=List.copyOf(reference().componentBasis().componentIds());var x=d.composition().moleFractions();
        assertEquals(.05,x[ids.indexOf("crude_pc07")],1e-12);
        double heavy=0,crudeHeavy=0;
        for(int k=8;k<=12;k++){heavy+=x[ids.indexOf("crude_pc"+k(k))];crudeHeavy+=crude.amounts().get("crude_pc"+k(k));}
        assertEquals(.95,heavy,1e-12);
        for(int k=8;k<=12;k++)assertEquals(crude.amounts().get("crude_pc"+k(k))/crudeHeavy,x[ids.indexOf("crude_pc"+k(k))]/heavy,1e-5,"pc"+k(k));
        for(int i=0;i<ids.size();i++)if(!ids.get(i).matches("crude_pc(0[7-9]|1[0-2])"))assertEquals(0,x[i],ids.get(i));
    }
    private static String k(int k){return String.format(Locale.ROOT,"%02d",k);}
    @Test void customFilesRefreshOnlyOnRequestAndNeverOverwriteAnExistingFile()throws Exception{
        var l=library();var first=l.refresh(V3ClientPresets.Kind.MIXTURE);var d=draft();
        d.composition().clear();d.composition().add(0);d.composition().set(0,"27");
        String json=V3ClientPresets.mixtureJson("custom",d.composition());
        Path file=l.save(V3ClientPresets.Kind.MIXTURE,"custom",json);
        assertEquals(game.resolve("IChemE/mixturepreset/custom.json"),file);
        assertEquals(8,first.entries().size());assertEquals(9,l.refresh(V3ClientPresets.Kind.MIXTURE).entries().size());
        assertNotEquals(file,l.save(V3ClientPresets.Kind.MIXTURE,"custom",json));assertEquals(json,Files.readString(file));
        assertThrows(IllegalArgumentException.class,()->l.save(V3ClientPresets.Kind.MIXTURE,"../escape",json));
    }
    @Test void jsonRoundTripRetainsIndependentMixtureAndColumnConfiguration(){
        var d=draft();for(int i=0;i<V3ColumnInput.MAX_SIDE_DRAWS;i++)d.removeDraw(i);for(int i=0;i<4;i++)d.removePumparound(i);d.set("s2","2");d.set("s3","2");d.set("s8","0.7");
        // The tray drop is the base's (owner choice (a)): an edit of s8 does not reach the input, and no preset carries it.
        var pot=d.assemble();assertEquals(0,pot.stageCount());assertEquals(1,pot.feedStageNumber());assertEquals(d.base().stagePressureDropPascal(),pot.stagePressureDropPascal(),1e-9);
        assertTrue(pot.sideDraws().isEmpty());assertTrue(pot.pumparounds().isEmpty());assertEquals(1,pot.steamFeeds().getFirst().stageNumber());
        String column=V3ClientPresets.columnJson("pot",pot);
        assertFalse(column.contains("package"));assertFalse(column.contains("components"));assertFalse(column.contains("stage_pressure_drop_kpa"));
        var c=(V3ClientPresets.Column)V3ClientPresets.decode(column,V3ClientPresets.Kind.COLUMN);
        var restored=draft();restored.loadColumn(c.template());
        var reloaded=restored.assemble();assertEquals(pot.stageCount(),reloaded.stageCount());assertEquals(pot.steamFeeds(),reloaded.steamFeeds());
        assertEquals(pot.stagePressureDropPascal(),reloaded.stagePressureDropPascal(),1e-9);
        assertArrayEquals(pot.feedComponentMolarFlowsMolPerSecond(),reloaded.feedComponentMolarFlowsMolPerSecond(),1e-12);
        d.composition().toggleBasis();
        String mixture=V3ClientPresets.mixtureJson("feed",d.composition());
        assertFalse(mixture.contains("tray_count"));assertFalse(mixture.contains("flow_kmol_h"));
        var m=(V3ClientPresets.Mixture)V3ClientPresets.decode(mixture,V3ClientPresets.Kind.MIXTURE);
        restored.composition().load(m.amounts(),m.mass());
        assertArrayEquals(d.composition().moleFractions(),restored.composition().moleFractions(),1e-14);
        assertEquals(com.wormzjl.icheme.runtime.fluid.SlurryFeed.NONE,m.solids(),"a mixture without a solids block carries none");
    }
    @Test void aMixtureFileCarriesItsSolidsForTheFluidGenerator(){
        String json="{\"schema_version\":1,\"kind\":\"mixture\",\"name\":\"slurry\",\"translation_key\":\"\",\"package\":\"icheme:tjl20_methane_nitrogen\","
            +"\"basis\":\"mole\",\"components\":[{\"id\":\"Water\",\"amount\":1}],"
            +"\"solids\":{\"volume_fraction_percent\":10,\"grades\":[{\"material\":\"icheme:demo_particle\",\"diameter_um\":100,\"mass_share\":1}]}}";
        var m=(V3ClientPresets.Mixture)V3ClientPresets.decode(json,V3ClientPresets.Kind.MIXTURE);
        assertEquals(com.wormzjl.icheme.runtime.fluid.SlurryFeed.demo(),m.solids());
        assertThrows(IllegalArgumentException.class,()->V3ClientPresets.decode(json.replace("\"volume_fraction_percent\":10","\"volume_fraction_percent\":100"),V3ClientPresets.Kind.MIXTURE));
    }
    @Test void changingPercentBasisAndRateBasisProducesPhysicalMassAndMolarValues(){
        var d=new V3CompositionDraft(V3EditorDraftTest.input(),List.of(0.02,0.2));d.clear();d.add(0);d.add(1);d.set(0,"2");d.set(1,"3");
        assertArrayEquals(new double[]{0.4,0.6},d.displayFractions(),1e-14);
        assertEquals(14.4,d.componentFlow(0,10,false),1e-12);assertEquals(288,d.componentFlow(0,10,true),1e-12);
        d.toggleBasis();assertEquals(0.0625,d.displayFractions()[0],1e-14);
        assertEquals(14.4,d.componentFlow(0,10,false),1e-12);
    }
    @Test void invalidCustomFilesAreIsolatedFromValidEntries()throws Exception{
        var l=library();Files.createDirectories(l.directory(V3ClientPresets.Kind.COLUMN));
        Files.writeString(l.directory(V3ClientPresets.Kind.COLUMN).resolve("broken.json"),"{broken");
        Files.writeString(l.directory(V3ClientPresets.Kind.COLUMN).resolve("large.json")," ".repeat(V3ClientPresets.MAX_BYTES+1));
        var result=l.refresh(V3ClientPresets.Kind.COLUMN);assertEquals(7,result.entries().size());assertEquals(2,result.problems().size());
        var o=JsonParser.parseString(V3ClientPresets.columnJson("case",reference())).getAsJsonObject();o.addProperty("tray_count",1);
        assertThrows(IllegalArgumentException.class,()->V3ClientPresets.decode(o.toString(),V3ClientPresets.Kind.COLUMN));
        // A schema 1 or a newer file is refused by its version; a schema 2 file that still carries the stage drop reads, the drop ignored.
        var old=JsonParser.parseString(V3ClientPresets.columnJson("case",reference())).getAsJsonObject();old.addProperty("schema_version",1);
        assertThrows(IllegalArgumentException.class,()->V3ClientPresets.decode(old.toString(),V3ClientPresets.Kind.COLUMN));
        old.addProperty("schema_version",4);
        assertThrows(IllegalArgumentException.class,()->V3ClientPresets.decode(old.toString(),V3ClientPresets.Kind.COLUMN));
        assertThrows(IllegalArgumentException.class,()->V3ClientPresets.decode("{\"schema_version\":2,\"kind\":\"mixture\",\"name\":\"m\",\"translation_key\":\"\",\"basis\":\"mole\",\"components\":[{\"id\":\"Propane\",\"amount\":1}]}",V3ClientPresets.Kind.MIXTURE));
        var dropped=JsonParser.parseString(V3ClientPresets.columnJson("case",reference())).getAsJsonObject();dropped.addProperty("schema_version",2);dropped.addProperty("stage_pressure_drop_kpa",5.0);
        var carried=(V3ClientPresets.Column)V3ClientPresets.decode(dropped.toString(),V3ClientPresets.Kind.COLUMN);
        var loaded=draft();loaded.loadColumn(carried.template());
        assertEquals(loaded.base().stagePressureDropPascal(),loaded.assemble().stagePressureDropPascal(),1e-9);
    }
}
