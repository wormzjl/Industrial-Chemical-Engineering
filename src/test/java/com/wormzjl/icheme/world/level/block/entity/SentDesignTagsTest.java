package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.client.gui.flowsheet.SizingCoverageTest;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.SentDesign;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The designs Send to block stores on a process simulator (batch 2026-11-02-process-simulator, S5) are equipment facts: every size the stage
 * proposes writes and reads back whole, a field missing reads its default, an entry this build cannot read is kept and written back, and every
 * corpus image reads as it was saved.
 */
class SentDesignTagsTest {
    private static final Path CORPUS = Path.of("src/test/resources/compat/simulator");
    /** The designs' fields revision: the corpus file this build's tags are kept in. Raise it with a field change. */
    private static final int FIELDS = 1;

    /** Every size the stage proposes, as Send to block stores it, members and counts among them. */
    static List<SentDesign> designs() {
        var out = new ArrayList<SentDesign>();
        for (var size : SizingCoverageTest.samples()) {
            var shape = size.shape();
            if (shape == null) continue;
            out.add(size instanceof ColumnSizing.Size c ? SentDesign.column("T-101", new ColumnDesign(c.layout(), c.diameter()))
                    : new SentDesign("U-" + out.size(), out.size() % 2 == 0 ? "" : "pa " + out.size() + " cooler", size.count(), shape, null));
        }
        return out;
    }

    @Test
    void everyDesignWritesAndReadsBackWhole() {
        var designs = designs();
        var read = SentDesignTags.read(SentDesignTags.write(designs, List.of()));
        assertTrue(read.whole(), read.repairs().toString());
        assertEquals(designs, read.designs());
    }

    /** A design written with no count and no member is one unit, the block's own; a calculator's untagged design reads with no tag. */
    @Test
    void aMissingFieldReadsItsDefault() {
        var design = designs().getFirst();
        var tag = SentDesignTags.write(design);
        tag.remove("Count");
        tag.remove("Member");
        tag.remove("Tag");
        var list = new ListTag();
        list.add(tag);
        var read = SentDesignTags.read(list);
        assertTrue(read.whole(), read.repairs().toString());
        assertEquals(new SentDesign("", "", 1, design.shape(), design.column()), read.designs().getFirst());
    }

    /** A kind this build no longer has, or a column whose design does not read, stays as saved and is written back; each is named. */
    @Test
    void anEntryThatDoesNotReadIsKeptAndWrittenBack() {
        var designs = designs();
        var list = SentDesignTags.write(designs, List.of());
        var retired = list.getCompound(0).copy();
        retired.getCompound("Shape").putString("Kind", "retired_kind");
        var broken = SentDesignTags.write(designs.stream().filter(d -> d.column() != null).findFirst().orElseThrow());
        broken.getCompound("Column").remove("Trays");
        list.add(retired);
        list.add(broken);
        var read = SentDesignTags.read(list);
        assertFalse(read.whole());
        assertEquals(designs, read.designs());
        assertEquals(List.of(retired, broken), read.unread());
        assertEquals(2, read.repairs().size(), read.repairs().toString());
        var again = SentDesignTags.write(read.designs(), read.unread());
        assertEquals(list, again, "written back as saved");
    }

    /**
     * Every corpus image's saved designs read with no repair, to the designs recorded beside them. The image of this build's fields must exist
     * (AGENTS.md: a facts-schema change adds one); when it is missing the test writes it under {@code build/compat/simulator/} to be copied in.
     */
    @Test
    void everyCorpusImageReadsAsItWasSaved() throws IOException, com.mojang.brigadier.exceptions.CommandSyntaxException {
        var current = CORPUS.resolve("designs-" + FIELDS + ".snbt");
        if (!Files.exists(current)) {
            var written = Path.of("build/compat/simulator/designs-" + FIELDS + ".snbt");
            Files.createDirectories(written.getParent());
            var lines = new ArrayList<String>();
            for (var design : designs()) {
                var entry = new CompoundTag();
                entry.put("Saved", SentDesignTags.write(design));
                entry.putString("Expected", design.toString());
                lines.add(entry.toString());
            }
            Files.write(written, lines, StandardCharsets.UTF_8);
            fail("No corpus image of designs fields " + FIELDS + ": copy " + written.toAbsolutePath() + " to " + current);
        }
        try (var files = Files.list(CORPUS)) {
            for (var file : files.sorted().toList())
                for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    var entry = TagParser.parseTag(line);
                    var list = new ListTag();
                    list.add(entry.getCompound("Saved"));
                    var read = SentDesignTags.read(list);
                    assertTrue(read.whole(), file + ": " + read.repairs());
                    assertEquals(entry.getString("Expected"), read.designs().getFirst().toString(), file.toString());
                }
        }
    }
}
