package com.wormzjl.icheme.world.level.block.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N1 (batch 2026-11-02-process-simulator): a device's name in its block entity's NBT, the field a structure's shell cell
 * and a one-block device both save beside their paint and send to the clients in their update tag. The corpus image
 * {@code compat/names/names-1.snbt} holds saved tags (named, saved before names, and names that break the rule) with
 * the name each reads to; this build writes each back and reads it again unchanged.
 */
class DeviceNamesCompatTest {
    private static final Path CORPUS = Path.of("src/test/resources/compat/names/names-1.snbt");

    @Test
    void everySavedNameReadsToItsRecordedNameAndRoundTrips() throws Exception {
        var lines = Files.readAllLines(CORPUS, StandardCharsets.UTF_8);
        assertTrue(lines.size() >= 8, "the corpus image holds its cases");
        for (var line : lines) {
            if (line.isBlank()) continue;
            var entry = TagParser.parseTag(line);
            String expected = entry.getString("Expected");
            String read = DeviceNames.read(entry.getCompound("Saved"), "test");
            assertEquals(expected, read, line);
            var written = DeviceNames.write(new CompoundTag(), read);
            assertEquals(!expected.isEmpty(), written.contains(DeviceNames.KEY), "a name is written only when there is one: " + line);
            assertEquals(expected, DeviceNames.read(written, "test"), "round trip: " + line);
        }
    }
}
