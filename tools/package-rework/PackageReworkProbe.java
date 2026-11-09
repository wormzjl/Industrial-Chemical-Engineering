package com.wormzjl.icheme.science.material;

import com.google.gson.*;
import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * One-off probe of batch 2026-11-04-refinery-compounds (package rework W1): dumps everything each bundled package
 * resolves to, so the reworked catalog can be compared with the old one. Writes build/package-rework/snapshot.json.
 * Moves to tools/ with the batch's tooling cleanup.
 */
class PackageReworkProbe {
    @Test void dump() throws Exception {
        String out = "build/package-rework/snapshot.json";
        var catalog = MaterialCatalog.bundled();
        var gson = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
        var root = new TreeMap<String, Object>();
        for (String id : new TreeSet<>(catalog.packages().keySet())) {
            var p = catalog.requirePackage(id);
            var d = new LinkedHashMap<String, Object>();
            d.put("revision", p.revision());
            d.put("model", p.model());
            d.put("components", p.components());
            d.put("properties", p.properties());
            d.put("interactions", p.interactions());
            d.put("nrtl", p.nrtlPairs());
            d.put("aliases", new TreeMap<>(p.aliases()));
            d.put("water", p.water());
            d.put("range", List.of(p.minimumTemperature(), p.maximumTemperature(), p.minimumPressure(), p.maximumPressure()));
            d.put("evidence", p.evidence());
            d.put("spine", p.spine());
            d.put("crystals", p.crystals());
            d.put("groups", p.groupContributions() == null ? null : p.groupContributions().science(p.components(), p.components()));
            d.put("physics", catalog.physicsContent(id, p.components()));
            d.put("scientificRevision", p.scientificRevision());
            d.put("fluidRevision", catalog.fluidRevision(id));
            d.put("spineRevision", catalog.spineRevision(id));
            d.put("fluidValidity", catalog.fluidValidity(id).orElse(null));
            var perComponent = new LinkedHashMap<String, Object>();
            for (String c : p.components()) {
                var e = new LinkedHashMap<String, Object>();
                e.put("validity", catalog.fluidValidity(id, c).orElse(null));
                e.put("translation", catalog.volumeTranslation(id, c).orElse(null));
                e.put("appearance", catalog.componentAppearance(id, c));
                for (var phase : ViscosityCorrelation.Phase.values()) e.put("viscosity." + phase, catalog.viscosity(id, c, phase).orElse(null));
                perComponent.put(c, e);
            }
            var water = new LinkedHashMap<String, Object>();
            water.put("appearance", catalog.componentAppearance(id, "Water"));
            for (var phase : ViscosityCorrelation.Phase.values()) water.put("viscosity." + phase, catalog.viscosity(id, "Water", phase).orElse(null));
            perComponent.put("Water", water);
            d.put("perComponent", perComponent);
            var mixture = catalog.liquidMixture(id);
            d.put("liquidMixture", mixture);
            var assays = new TreeMap<String, Object>();
            for (String a : new TreeSet<>(p.assays().keySet())) {
                var e = new LinkedHashMap<String, Object>();
                e.put("assay", p.assays().get(a));
                e.put("fractions", MaterialPresets.moleFractions(p, a));
                e.put("appearance", catalog.assayAppearance(id, a));
                assays.put(a, e);
            }
            d.put("assays", assays);
            root.put(id, d);
        }
        var presets = new TreeMap<String, Object>();
        for (String id : new TreeSet<>(catalog.presets().columns().keySet())) presets.put("column/" + id, catalog.presets().column(id).input(catalog));
        presets.put("network", catalog.presets().network());
        presets.put("fluid", FluidPresetCatalog.resolve(catalog).stream().map(f -> List.of(f.id(), f.name(), f.moleFractions())).toList());
        root.put("~presets", presets);
        Files.createDirectories(Path.of(out).getParent());
        Files.writeString(Path.of(out), gson.toJson(root));
    }
}
