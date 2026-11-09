package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.client.gui.flowsheet.SizingCoverageTest;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
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
    /**
     * The designs' fields revision: the corpus file this build's tags are kept in. Raise it with a field change (2: a column's water trim count
     * and its air-then-water condenser, COOLER_MODEL_PLAN.md section 6).
     */
    private static final int FIELDS = 2;

    /** Member counts as an image written before the water trim's count shows them. */
    private static final java.util.regex.Pattern UNITS_BEFORE_TRIM = java.util.regex.Pattern.compile("(Units\\[condenser=\\d+, kettle=\\d+, coolers=\\[[0-9, ]*\\])\\]");
    /** A machine's duty (batch 2026-11-03-rotating-machinery W4): an image written before the shape's duty field reads back with
     * the default duty of its size, which its expected text does not show. */
    private static final java.util.regex.Pattern DUTY = java.util.regex.Pattern.compile(", duty=MachineDuty\\[[^\\]]*\\]");

    /** Every size the stage proposes, as Send to block stores it, members and counts among them; and an air-then-water column. */
    static List<SentDesign> designs() {
        var out = new ArrayList<SentDesign>();
        for (var size : SizingCoverageTest.samples()) {
            var shape = size.shape();
            if (shape == null) continue;
            out.add(size instanceof ColumnSizing.Size c ? SentDesign.column("T-101", new ColumnDesign(c.layout(), c.diameter()))
                    : new SentDesign("U-" + out.size(), out.size() % 2 == 0 ? "" : "pa " + out.size() + " cooler", size.count(), shape, null));
        }
        var trimmed = new ColumnComponents(ColumnComponents.Condenser.AIR_THEN_WATER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true,
                new ColumnComponents.Units(2, 1, List.of(1), 3));
        out.add(SentDesign.column("T-102", new ColumnDesign(new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), trimmed), 3)));
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
                    // Counts written before the water trim's read one trim shell.
                    var expected = UNITS_BEFORE_TRIM.matcher(entry.getString("Expected")).replaceAll("$1, trim=1]");
                    // Appended defaults leave the historical saved payload untouched.
                    if (!expected.contains("baseSpeed=")) expected = expected.replaceAll("(exhaust=[0-9.Ee+-]+)\\]", "$1, baseSpeed=0.0]");
                    if (!expected.contains("exchangerType=")) expected = expected.replace("], column=", ", exchangerType=STANDARD], column=");
                    var design = read.designs().getFirst();
                    if (!expected.contains("exchangers=Exchangers[") && design.shape().trays() != null && design.shape().trays().components() != null) {
                        var pattern = java.util.regex.Pattern.compile("(units=Units\\[condenser=\\d+, kettle=\\d+, coolers=\\[[0-9, ]*\\], trim=\\d+\\])\\]");
                        var match = pattern.matcher(expected);
                        var upgraded = new StringBuilder();
                        while (match.find()) match.appendReplacement(upgraded, java.util.regex.Matcher.quoteReplacement(match.group(1) + ", exchangers=" + design.shape().trays().components().exchangers() + "]"));
                        match.appendTail(upgraded);
                        expected = upgraded.toString();
                    }
                    var actual = design.toString();
                    if (!expected.contains("duty=")) actual = DUTY.matcher(actual).replaceAll("");
                    assertEquals(expected, actual, file.toString());
                }
        }
    }
}
