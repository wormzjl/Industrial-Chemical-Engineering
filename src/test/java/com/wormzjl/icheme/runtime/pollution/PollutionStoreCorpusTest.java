package com.wormzjl.icheme.runtime.pollution;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pollution store's corpus (AGENTS.md, "Saves and data formats"; batch 2026-11-15-pollution-system, WP3): every image
 * under {@code compat/pollution/} reads without throwing to the counts recorded beside it ({@code Expected}: set aside,
 * notes, cubes and pending releases per dimension, and from schema 2 the ground columns, compound entries and pending
 * deposits). A clean image of this build's schema saves back to exactly its tag; every image saves back to
 * a store that reads the same with no note. The image of this build's schema must exist.
 */
class PollutionStoreCorpusTest {
    private static final Path CORPUS = Path.of("src/test/resources/compat/pollution");

    @Test
    void everyCorpusImageReadsToItsRecordedCounts() throws IOException, CommandSyntaxException {
        assertTrue(Files.exists(CORPUS.resolve("pollution-" + PollutionStore.SCHEMA + ".snbt")),
                "no corpus image of pollution store schema " + PollutionStore.SCHEMA);
        int images = 0;
        try (var files = Files.list(CORPUS)) {
            for (var file : files.sorted().toList()) {
                for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    images++;
                    var entry = TagParser.parseTag(line);
                    var saved = entry.getCompound("Saved");
                    var expected = entry.getCompound("Expected");
                    var loaded = PollutionStore.read(saved);
                    String where = file + ": " + loaded.notes();
                    assertEquals(expected.getBoolean("set_aside"), loaded.setAside(), where);
                    assertEquals(expected.getInt("notes"), loaded.notes().size(), where);
                    var dimensions = expected.getCompound("dimensions");
                    assertEquals(dimensions.getAllKeys(), loaded.dimensions().keySet(), where);
                    for (String id : dimensions.getAllKeys()) {
                        var counts = dimensions.getCompound(id);
                        var dimension = loaded.dimensions().get(id);
                        assertEquals(counts.getInt("cubes"), dimension.field().size(), where + " " + id);
                        assertEquals(counts.getInt("pending"), dimension.pending().size(), where + " " + id);
                        // Schema 2 (batch 2026-11-18-ground-pollution): absent counts are 0 (a schema-1 image has no ground).
                        assertEquals(counts.getInt("columns"), dimension.ground().size(), where + " " + id + " ground columns");
                        assertEquals(counts.getInt("compounds"), dimension.ground().entries().size(), where + " " + id + " ground compounds");
                        assertEquals(counts.getInt("ground_pending"), dimension.groundPending().size(), where + " " + id + " pending deposits");
                    }
                    var written = PollutionStore.write(loaded.dimensions());
                    // An image of this build's schema saves back to exactly its tag; an older one saves as this schema.
                    if (!loaded.setAside() && loaded.notes().isEmpty() && saved.getInt("schema") == PollutionStore.SCHEMA) assertEquals(saved, written, file + " saved again");
                    var again = PollutionStore.read(written);
                    assertTrue(again.notes().isEmpty() && !again.setAside(), file + " read again: " + again.notes());
                    assertEquals(written, PollutionStore.write(again.dimensions()), file + " saved twice");
                }
            }
        }
        assertTrue(images >= 3, "only " + images + " corpus images");
    }
}
