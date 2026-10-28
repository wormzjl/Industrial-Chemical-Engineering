package com.wormzjl.icheme.science.column.v3;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.network.ColumnCodecs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.level.block.entity.ColumnInputPreset;
import java.util.Arrays;
import java.util.HashMap;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColumnInputPresetTest {
    @Test void everySelectablePresetIsValidAndSurvivesSaving() throws Exception {
        var catalog=MaterialCatalog.bundled();
        for (var choice : ColumnInputPreset.values()) {
            var input=choice.input(catalog);
            assertTrue(choice.matches(input));
            V3ColumnProblemResolver.validateInput(input);
            assertEquals(input,ColumnCodecs.readInputNbt(ColumnCodecs.writeInputNbt(input)));
            var thermo=V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            double[] expected=thermo.crudeFeed(input.assayId()).moleFractions();
            double total=Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum();
            for (int i=0;i<expected.length;i++)
                assertEquals(expected[i],input.feedComponentMolarFlowsMolPerSecond()[i]/total,1e-14);
        }
        assertEquals(V3TestInputs.methaneCduInput(),ColumnInputPreset.fromId(catalog,"tia_juana").input(catalog));
        assertThrows(IllegalArgumentException.class,()->ColumnInputPreset.fromId("arbitrary:package"));
    }

    @Test void newCrudesKeepEditableCurrentColumnConditionsAndDistinctFeeds() {
        var catalog=MaterialCatalog.bundled();
        var base=ColumnInputPreset.fromId(catalog,"tia_juana").input(catalog);
        for (var choice : new ColumnInputPreset[]{ColumnInputPreset.fromId(catalog,"wti_light_export"),ColumnInputPreset.fromId(catalog,"upper_zakum"),
                ColumnInputPreset.fromId(catalog,"bonga"),ColumnInputPreset.fromId(catalog,"dalia"),ColumnInputPreset.fromId(catalog,"cold_lake_blend")}) {
            var input=choice.input(catalog);
            assertEquals(base.componentBasis(),input.componentBasis());
            assertEquals(base.stageCount(),input.stageCount());
            assertEquals(base.feedStageNumber(),input.feedStageNumber());
            assertEquals(base.feedTemperatureKelvin(),input.feedTemperatureKelvin());
            assertEquals(base.topPressurePascal(),input.topPressurePascal());
            assertEquals(base.specifications(),input.specifications());
            var rates=V3TestInputs.packagePreset(catalog,input.packageId()).draws().stream().map(V3SideDrawSpec::molarFlowMolPerSecond).toList();
            if(!rates.isEmpty())assertEquals(rates,input.sideDraws().stream().map(V3SideDrawSpec::molarFlowMolPerSecond).toList());
            assertEquals(base.steamFeeds(),input.steamFeeds());
            assertEquals(base.pumparounds(),input.pumparounds());
            double volume=0;var properties=catalog.requirePackage(input.packageId()).properties();
            var amounts=input.feedComponentMolarFlowsMolPerSecond();
            for(int i=0;i<amounts.length;i++)volume+=amounts[i]*properties.get(i).molecularWeight()/properties.get(i).density();
            assertEquals(V3TestInputs.packagePreset(catalog,input.packageId()).feedVolume(),volume,1e-14);
            assertFalse(Arrays.equals(base.feedComponentMolarFlowsMolPerSecond(),input.feedComponentMolarFlowsMolPerSecond()));
        }
    }

    @Test void loadingPresetUsesTheSuppliedServerCatalogIncludingOverrides() {
        var original=MaterialCatalog.bundled();
        var resources=new HashMap<>(original.resources());
        String path="data/icheme/materials/assays/bonga_tjl20.json";
        var assay=JsonParser.parseString(resources.get(path)).getAsJsonObject();
        assay.getAsJsonObject("amounts_by_component").addProperty("crude_pc01",.8);
        resources.put(path,assay.toString());
        var edited=MaterialCatalog.parse(resources);
        var before=ColumnInputPreset.fromId(original,"bonga").input(original);
        var after=ColumnInputPreset.fromId(original,"bonga").input(edited);
        assertFalse(Arrays.equals(before.feedComponentMolarFlowsMolPerSecond(),after.feedComponentMolarFlowsMolPerSecond()));
        assertEquals(before,ColumnInputPreset.fromId(original,"bonga").input(original));
        assertSame(original,MaterialRuntime.current());
    }
}
