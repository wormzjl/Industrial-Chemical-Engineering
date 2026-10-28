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
        assertEquals(Set.of("Bonga", "Cold Lake Blend", "Dalia", "Tia Juana Light (default)", "Upper Zakum", "WTI Light - Export", "Two-tray pot"),
                library.templates().stream().map(ColumnTemplates.Template::name).collect(Collectors.toSet()));
        assertEquals(7, library.templates().size());
        for (var template : library.templates()) {
            assertTrue(template.bundled(), template.name());
            if (template.name().equals("Two-tray pot")) {
                assertFalse(template.usable(), "a pot has no tray between its condenser and reboiler");
                assertTrue(template.problem().contains("tray"), template.problem());
                continue;
            }
            assertTrue(template.usable(), template.name() + ": " + template.problem());
            // As the server makes it: 40 trays between the condenser and the reboiler, at the template's 8 m.
            var design = ColumnDesign.of(template.input(), ColumnTemplates.choices(template.input()));
            assertEquals(40, design.trays().trays(), template.name());
            assertEquals(8.0, design.diameter(), template.name());
            assertEquals(template.input().pumparounds().size(), design.components().pumparoundPumps().size(), template.name() + ": a pump each pumparound");
        }
    }

    @Test
    void aPlayersOwnTemplateFollowsTheBundledOnes() throws Exception {
        var bundled = templates().templates();
        var own = bundled.stream().filter(t -> t.name().equals("Bonga")).findFirst().orElseThrow();
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
