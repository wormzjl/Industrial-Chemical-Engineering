package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The equipment constructor's column templates (owner request 2026-10-28: "it should allow default templates"): the
 * column calculator's library on its default input, every bundled crude template a column the server accepts, the pot
 * listed but off, the player's own templates after the bundled ones.
 */
class ColumnTemplatesTest {
    @TempDir Path game;

    /** Save as writes a template's conditions, Templates reads them back, and a mixture by mass comes to moles; one the sheet's axis cannot hold stays out of the list. */
    @org.junit.jupiter.api.Test
    void aStreamTemplateKeepsItsConditions() throws Exception {
        var catalog = MaterialCatalog.bundled();
        var network = catalog.presets().network().packageId();
        var fluids = new com.wormzjl.icheme.client.gui.flowsheet.StreamProps.Fluids(com.wormzjl.icheme.science.material.MaterialRuntime.with(catalog, network, () -> com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.forNetwork(catalog, network)), catalog.requirePackage(network), id -> id, network);
        var library = library();
        var json = V3ClientPresets.streamJson("My feed", java.util.Map.of("Water", 2.0), 350, 4, 90);
        library.save(V3ClientPresets.Kind.MIXTURE, "My feed", json);
        library.save(V3ClientPresets.Kind.MIXTURE, "Elsewhere", V3ClientPresets.streamJson("Elsewhere", java.util.Map.of("Unobtainium", 2.0), 350, 4, 90));
        var listed = ColumnTemplates.streams(java.util.List.of(), library.refresh(V3ClientPresets.Kind.MIXTURE), fluids, V3ClientPresets.Preset::name);
        var mine = listed.stream().filter(s -> s.name().equals("My feed")).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(350, mine.temperature());
        org.junit.jupiter.api.Assertions.assertEquals(4e5, mine.pressure(), 1e-9);
        org.junit.jupiter.api.Assertions.assertEquals(25, mine.flow(), 1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(listed.stream().noneMatch(s -> s.name().equals("Elsewhere")));
        org.junit.jupiter.api.Assertions.assertTrue(listed.stream().anyMatch(s -> Double.isNaN(s.temperature())), "a bundled mixture with no conditions lists all the same");
    }

    /** Delete takes a player's own file and can reach nothing else. */
    @org.junit.jupiter.api.Test
    void deleteTakesAPlayersOwnTemplateAndNothingOutsideItsFolder() throws Exception {
        var library = library();
        var own = library.save(V3ClientPresets.Kind.COLUMN, "Mine", V3ClientPresets.columnJson("Mine", base()));
        var bundled = library.refresh(V3ClientPresets.Kind.COLUMN).entries().size();
        library.delete(V3ClientPresets.Kind.COLUMN, own.getFileName().toString());
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(own));
        org.junit.jupiter.api.Assertions.assertEquals(bundled - 1, library.refresh(V3ClientPresets.Kind.COLUMN).entries().size());
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> library.delete(V3ClientPresets.Kind.COLUMN, "../mixturepreset/x.json"));
    }

    private V3ClientPresets library() {
        return new V3ClientPresets(game, n -> getClass().getClassLoader().getResourceAsStream(n));
    }

    private static V3ColumnInput base() {
        var c = MaterialCatalog.bundled();
        return c.presets().column(c.presets().network().defaultColumnPreset()).input(c);
    }

    private ColumnTemplates.Library templates() {
        return ColumnTemplates.of(library().refresh(V3ClientPresets.Kind.COLUMN), base(), V3ClientPresets.Preset::name);
    }

    @Test
    void everyBundledCrudeTemplateBuildsAColumnAndThePotIsOff() {
        var library = templates();
        assertEquals(0, library.unreadable());
        assertEquals(Set.of("Crude tower (Heavy crude)", "Crude tower (Light crude)", "Crude tower (Medium crude)", "Crude tower (Oil sand dilbit)", "Small crude tower (Medium crude)",
                "Two-tray pot"),
                library.templates().stream().map(ColumnTemplates.Template::name).collect(Collectors.toSet()));
        assertEquals(6, library.templates().size());
        for (var template : library.templates()) {
            assertTrue(template.bundled(), template.name());
            if (template.name().equals("Two-tray pot")) {
                assertFalse(template.usable(), "a pot has no tray between its condenser and reboiler");
                assertTrue(template.problem().contains("tray"), template.problem());
                continue;
            }
            assertTrue(template.usable(), template.name() + ": " + template.problem());
            // As the server makes it: the template's own trays between the condenser and the reboiler, at its own diameter.
            var design = ColumnDesign.of(template.input(), ColumnTemplates.choices(template.input()));
            assertEquals(template.input().stageCount(), design.trays().trays(), template.name());
            assertEquals(template.input().columnDiameterMetres(), design.diameter(), template.name());
            assertEquals(template.input().pumparounds().size(), design.components().pumparoundPumps().size(), template.name() + ": a pump each pumparound");
        }
    }

    /** The constructor's operating point stands at the template's own design feed, on the default input's mixture and temperature. */
    @Test
    void aTemplateBuildsAtItsDesignFeed() {
        for (var template : templates().templates()) {
            double feed = java.util.Arrays.stream(template.input().feedComponentMolarFlowsMolPerSecond()).sum() * 3.6;
            double design = template.name().equals("Two-tray pot") ? 360 : template.name().equals("Crude tower (Heavy crude)") ? 1897.651207174458 : feed;
            assertEquals(design, feed, 1e-9, template.name());
            assertEquals(base().feedTemperatureKelvin(), template.input().feedTemperatureKelvin(), 1e-9, template.name());
        }
    }

    @Test
    void aPlayersOwnTemplateFollowsTheBundledOnes() throws Exception {
        var bundled = templates().templates();
        var own = bundled.stream().filter(t -> t.name().equals("Crude tower (Heavy crude)")).findFirst().orElseThrow();
        var directory = library().directory(V3ClientPresets.Kind.COLUMN);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("my_column.json"), V3ClientPresets.columnJson("My column", own.input()));
        Files.writeString(directory.resolve("broken.json"), "{");
        var library = templates();
        assertEquals(1, library.unreadable(), "the broken file counted, not listed");
        var last = library.templates().getLast();
        assertEquals("My column", last.name());
        assertFalse(last.bundled());
        assertTrue(last.usable(), last.problem());
        assertEquals(bundled.size() + 1, library.templates().size());
    }
}
