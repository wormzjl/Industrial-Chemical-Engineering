package com.wormzjl.icheme.science.column.v3;

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
        for (String crude : java.util.List.of("wti_light_export","upper_zakum","bonga","dalia","cold_lake_blend")) {
            var input=V3TestInputs.crude(catalog,crude);
            assertEquals(base.componentBasis(),input.componentBasis());
            assertEquals(base.stageCount(),input.stageCount());
            assertEquals(base.feedStageNumber(),input.feedStageNumber());
            assertEquals(base.feedTemperatureKelvin(),input.feedTemperatureKelvin());
            assertEquals(base.topPressurePascal(),input.topPressurePascal());
            assertEquals(base.specifications(),input.specifications());
            assertEquals(base.sideDraws().size(),input.sideDraws().size());
            assertEquals(base.steamFeeds().size(),input.steamFeeds().size());
            assertEquals(base.pumparounds().size(),input.pumparounds().size());
            assertFalse(Arrays.equals(base.feedComponentMolarFlowsMolPerSecond(),input.feedComponentMolarFlowsMolPerSecond()));
        }
    }

    @Test void loadingPresetUsesTheSuppliedServerCatalogIncludingOverrides() {
        var original=MaterialCatalog.bundled();
        var resources=new HashMap<>(original.resources());
        String path="data/icheme/materials/assays/tjl20.json";
        var assay=com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();
        assay.getAsJsonObject("amounts_by_component").addProperty("crude_pc01",.8);
        resources.put(path,assay.toString());
        var edited=MaterialCatalog.parse(resources);
        var before=ColumnInputPreset.fromId(original,"tia_juana").input(original);
        var after=ColumnInputPreset.fromId(original,"tia_juana").input(edited);
        assertFalse(Arrays.equals(before.feedComponentMolarFlowsMolPerSecond(),after.feedComponentMolarFlowsMolPerSecond()));
        assertEquals(before,ColumnInputPreset.fromId(original,"tia_juana").input(original));
        assertSame(original,MaterialRuntime.current());
    }
}
