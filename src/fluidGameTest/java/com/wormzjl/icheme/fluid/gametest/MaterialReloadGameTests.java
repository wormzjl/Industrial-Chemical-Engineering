package com.wormzjl.icheme.fluid.gametest;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.nio.file.*;
import java.util.*;

/** Runs only in the dedicated, disposable material GameTest server; exercises the actual /reload path. */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public class MaterialReloadGameTests {
    @GameTest(template="empty",timeoutTicks=2400,batch="material-resource-reload")
    public static void materialReloadIsAtomic(GameTestHelper helper) throws Exception {
        var server=helper.getLevel().getServer();
        var original=MaterialRuntime.active();
        var feedPackage=original.requirePackage("icheme:tjl20_methane");
        for (String crude : List.of("wti_light_export", "upper_zakum", "bonga", "dalia", "cold_lake_blend")) {
            var assay=feedPackage.assays().get("icheme:"+crude);
            if (assay==null || !assay.basis().equals("mass")) {
                helper.fail("Bundled regrouped crude assay was not loaded: "+crude);return;
            }
        }
        String packageId="icheme:tjl20_methane";
        String resource="data/icheme/materials/properties/tjl20_methane.json";
        var root=server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("material-reload-test");
        var file=root.resolve(resource);Files.createDirectories(file.getParent());
        Files.writeString(root.resolve("pack.mcmeta"),"{\"pack\":{\"pack_format\":48,\"description\":\"Material reload integration test\"}}");
        var json=JsonParser.parseString(original.resources().get(resource)).getAsJsonObject();
        json.addProperty("molecular_weight_kg_per_mol",.017);
        Files.writeString(file,json.toString());
        var selected=new ArrayList<>(server.getPackRepository().getSelectedIds());
        server.getPackRepository().reload();
        var withOverride=new ArrayList<>(selected);withOverride.add("file/material-reload-test");
        server.reloadResources(withOverride).whenComplete((ignored,error)->server.execute(()-> {
            if(error!=null){helper.fail("Valid material override reload failed: "+error);return;}
            var changed=MaterialRuntime.active();
            if(changed.requirePackage(packageId).equals(original.requirePackage(packageId))
                    || changed.requirePackage(packageId).properties().getFirst().molecularWeight()!=.017) {
                helper.fail("Higher priority pack was not published");return;
            }
            try {json.addProperty("molecular_weight_kg_per_mol",-1);Files.writeString(file,json.toString());}
            catch(Exception e){helper.fail(e.toString());return;}
            server.reloadResources(withOverride).whenComplete((unused,invalid)->server.execute(()-> {
                if(invalid==null || MaterialRuntime.active()!=changed){helper.fail("Invalid reload replaced the valid snapshot");return;}
                server.reloadResources(selected).whenComplete((done,restore)->server.execute(()-> {
                    if(restore!=null || !MaterialRuntime.active().requirePackage(packageId).equals(original.requirePackage(packageId)))
                        helper.fail("Restoring the original data pack selection failed");
                    else helper.succeed();
                }));
            }));
        }));
    }
}
