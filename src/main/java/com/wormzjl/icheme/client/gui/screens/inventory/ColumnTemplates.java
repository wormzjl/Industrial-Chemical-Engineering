package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;

/**
 * The column templates an equipment constructor builds a column from (owner request 2026-10-28: "it should allow
 * default templates"; owner choice: column templates): the column calculator's library, its bundled templates first,
 * then the player's own from {@code IChemE/columnpreset} in the game folder. A template's input is the calculator's
 * default input with the template's column settings, as the calculator's Templates load them; its design takes an air
 * cooler and standard pumps ({@link #choices}). The server checks the design again ({@link ColumnDesign#of}), as it
 * checks a calculator's Save design.
 */
public final class ColumnTemplates {
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

    /** The library as it stands on disk now: read again each time, as the calculator's Refresh reads it. */
    public static Library load(Minecraft minecraft) {
        var library = new V3ClientPresets(minecraft.gameDirectory.toPath(), name -> {
            try {
                return minecraft.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath("icheme", name.substring("assets/icheme/".length()))).orElseThrow().open();
            } catch (IOException | NoSuchElementException e) {
                return null;
            }
        });
        var catalog = MaterialRuntime.current();
        var base = catalog.presets().column(catalog.presets().network().defaultColumnPreset()).input(catalog);
        return of(library.refresh(V3ClientPresets.Kind.COLUMN), base,
                preset -> preset.translationKey().isBlank() ? preset.name() : Component.translatable(preset.translationKey()).getString());
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
                draft.loadColumn(column.fields());
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
