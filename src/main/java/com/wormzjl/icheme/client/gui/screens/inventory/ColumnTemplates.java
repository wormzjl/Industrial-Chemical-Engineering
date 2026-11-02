package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.gui.flowsheet.FileColumns;
import com.wormzjl.icheme.client.gui.flowsheet.StreamProps;
import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * The column templates an equipment constructor builds a column from (owner request 2026-10-28: "it should allow
 * default templates"; owner choice: column templates): the column calculator's library, its bundled templates first,
 * then the player's own from {@code IChemE/columnpreset} in the game folder. A template's input is the calculator's
 * default input with the template's column settings at the template's design feed; its design takes an air cooler and
 * standard pumps ({@link #choices}). The server checks the design again ({@link ColumnDesign#of}), as it checks a
 * calculator's Save design.
 */
public final class ColumnTemplates {
    private static final double WATER_WEIGHT = .018015;

    private ColumnTemplates() {
    }

    /** A template: its name as shown, whether it is bundled, its input (null where none was made), and why it builds no column (null where it does). */
    public record Template(String name, boolean bundled, @Nullable V3ColumnInput input, @Nullable String problem) {
        public boolean usable() {
            return input != null && problem == null;
        }
    }

    /** The templates, and how many files could not be read. */
    public record Library(List<Template> templates, int unreadable) {
        public Library {
            templates = List.copyOf(templates);
        }
    }

    /** The components a template's design takes: an air cooler and a standard pump for the reflux and each pumparound. */
    public static ColumnComponents.Choices choices(V3ColumnInput input) {
        return ColumnComponents.Choices.DEFAULT.fit(input.pumparounds().size());
    }

    /** The templates as they stand on disk, not yet on any input: what the simulator's column door lists. */
    public static List<com.wormzjl.icheme.client.gui.flowsheet.FileColumns.ColumnTemplate> listed(Minecraft minecraft) {
        var out = new ArrayList<com.wormzjl.icheme.client.gui.flowsheet.FileColumns.ColumnTemplate>();
        for (var entry : library(minecraft).refresh(V3ClientPresets.Kind.COLUMN).entries())
            if (entry.preset() instanceof V3ClientPresets.Column column) out.add(new com.wormzjl.icheme.client.gui.flowsheet.FileColumns.ColumnTemplate(name(column), column.template(), entry.bundled(), entry.file()));
        return out;
    }

    /** The stream templates a feed lists: the catalog's own mixtures, then the library's, with their conditions. One holding a component the sheet's axis has not is left out. */
    public static List<FileColumns.StreamTemplate> streams(Minecraft minecraft, MaterialCatalog catalog, StreamProps.Fluids fluids) {
        return streams(FluidPresetCatalog.resolve(catalog), library(minecraft).refresh(V3ClientPresets.Kind.MIXTURE), fluids, ColumnTemplates::name);
    }

    static List<FileColumns.StreamTemplate> streams(List<FluidPresetCatalog.Preset> presets, V3ClientPresets.Snapshot snapshot, StreamProps.Fluids fluids, Function<V3ClientPresets.Preset, String> names) {
        var out = new ArrayList<FileColumns.StreamTemplate>();
        var axis = fluids.axis();
        for (var preset : presets) {
            var amounts = new LinkedHashMap<String, Double>();
            double[] shares = preset.moleFractions();
            for (int i = 0; i < shares.length && i < axis.size(); i++) if (shares[i] > 0) amounts.put(axis.get(i), shares[i]);
            out.add(new FileColumns.StreamTemplate(preset.name(), amounts, Double.NaN, Double.NaN, Double.NaN));
        }
        var properties = fluids.pack().properties();
        for (var entry : snapshot.entries()) {
            if (!(entry.preset() instanceof V3ClientPresets.Mixture mixture) || !axis.containsAll(mixture.amounts().keySet())) continue;
            var amounts = new LinkedHashMap<String, Double>();
            mixture.amounts().forEach((id, amount) -> {
                int at = axis.indexOf(id);
                amounts.put(id, mixture.mass() ? amount / (at < properties.size() ? properties.get(at).molecularWeight() : WATER_WEIGHT) : amount);
            });
            out.add(new FileColumns.StreamTemplate(names.apply(mixture), amounts, mixture.temperature().orElse(Double.NaN), mixture.pressure().orElse(Double.NaN) * 1e5, mixture.flow().orElse(Double.NaN) * 1000 / 3600));
        }
        return out;
    }

    /** Saves a feed as the player's own stream template, with its T, P and flow. */
    public static void saveStream(Minecraft minecraft, String name, Flowsheet.Feed feed) throws IOException {
        double total = feed.amounts().values().stream().mapToDouble(Double::doubleValue).sum();
        library(minecraft).save(V3ClientPresets.Kind.MIXTURE, name, V3ClientPresets.streamJson(name, feed.amounts(), feed.temperature(), feed.pressure() / 1e5, total * 3.6));
    }

    /** Saves a column as the player's own column template. */
    public static void saveColumn(Minecraft minecraft, String name, V3ColumnInput input) throws IOException {
        library(minecraft).save(V3ClientPresets.Kind.COLUMN, name, V3ClientPresets.columnJson(name, input));
    }

    /** Deletes the player's own column template in {@code file}. */
    public static void deleteColumn(Minecraft minecraft, String file) throws IOException {
        library(minecraft).delete(V3ClientPresets.Kind.COLUMN, file);
    }

    private static String name(V3ClientPresets.Preset preset) {
        return preset.translationKey().isBlank() ? preset.name() : Component.translatable(preset.translationKey()).getString();
    }

    /** The library as it stands on disk now: read again each time, as the calculator's Refresh reads it. */
    public static Library load(Minecraft minecraft) {
        var library = library(minecraft);
        var catalog = MaterialRuntime.current();
        var base = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        return of(library.refresh(V3ClientPresets.Kind.COLUMN), base, ColumnTemplates::name);
    }

    private static V3ClientPresets library(Minecraft minecraft) {
        return new V3ClientPresets(minecraft.gameDirectory.toPath(), name -> {
            try {
                return minecraft.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath("icheme", name.substring("assets/icheme/".length()))).orElseThrow().open();
            } catch (IOException | NoSuchElementException e) {
                return null;
            }
        });
    }

    /** The column templates of {@code snapshot} applied to {@code base}, named by {@code names}. */
    static Library of(V3ClientPresets.Snapshot snapshot, V3ColumnInput base, Function<V3ClientPresets.Preset, String> names) {
        var out = new ArrayList<Template>();
        for (var entry : snapshot.entries()) {
            if (!(entry.preset() instanceof V3ClientPresets.Column column)) continue;
            V3ColumnInput input = null;
            String problem = null;
            try {
                var draft = new V3EditorDraft(base);
                draft.loadColumnAtDesignFeed(column.template());
                input = draft.assembleOperating();
                ColumnDesign.of(input, choices(input));
            } catch (RuntimeException refused) {
                problem = Objects.toString(refused.getMessage(), refused.getClass().getSimpleName());
            }
            out.add(new Template(names.apply(column), entry.bundled(), input, problem));
        }
        return new Library(out, snapshot.problems().size());
    }
}
