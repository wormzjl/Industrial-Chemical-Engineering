package com.wormzjl.icheme.material;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRules;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import java.io.*;
import java.util.*;

/**
 * The fluid conversion rules (batch 2026-11-12-fluid-conversion, plan 3.3): {@code data/<namespace>/icheme/fluid_conversion/*.json}.
 * Registered after {@link MaterialReloadListener}, so its apply runs after the material catalog's and the rules resolve
 * their compounds and presets on the catalog that reload published. A rule that fails validation is refused alone, logged;
 * the others load.
 */
public final class ConversionReloadListener extends SimplePreparableReloadListener<Map<String,String>> {
    static final String FOLDER="icheme/fluid_conversion";
    public static void register(AddReloadListenerEvent event){event.addListener(new ConversionReloadListener());}
    @Override protected Map<String,String> prepare(ResourceManager manager,ProfilerFiller profiler) {
        var files=new TreeMap<String,String>();
        manager.listResources(FOLDER,id->id.getPath().endsWith(".json")).forEach((id,resource)->{
            try(Reader reader=resource.openAsReader()) {
                var text=new StringBuilder();char[] buffer=new char[8192];int count;
                while((count=reader.read(buffer))!=-1)text.append(buffer,0,count);
                var path=id.getPath().substring(FOLDER.length()+1,id.getPath().length()-".json".length());
                files.put(id.getNamespace()+":"+path,text.toString());
            }catch(IOException e){throw new UncheckedIOException(id.toString(),e);}
        });
        return files;
    }
    @Override protected void apply(Map<String,String> files,ResourceManager manager,ProfilerFiller profiler) {
        var catalog=MaterialRuntime.active();
        var rules=ConversionRules.parse(files,ConversionRules.names(catalog,FluidMaterialCatalog.networkPackage(catalog)),ns->ns.equals("minecraft")||ns.equals("c")||ModList.get().isLoaded(ns));
        for(var r:rules.refused())IChemE.LOGGER.warn("fluid_conversion status=REFUSED rule={} reason={} message={}",r.id(),r.reason(),r.message());
        IChemE.LOGGER.info("fluid_conversion status=LOADED rules={} refused={}",rules.rules().size(),rules.refused().size());
        ConversionRuntime.publish(rules);
    }
}
