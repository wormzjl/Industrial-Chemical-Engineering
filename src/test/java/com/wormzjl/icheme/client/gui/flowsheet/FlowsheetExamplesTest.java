package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled example flowsheets (batch 2026-11-02-process-simulator, owner 2026-11-04: "Add some example flowsheets to the game",
 * "Crude unit (CDU)", "In Open, listed first"). That each runs, the sample gates prove: {@link SheetFixture#sample()} is the crude unit.
 */
final class FlowsheetExamplesTest {
    private static final Function<String, InputStream> BUNDLED = name -> FlowsheetExamplesTest.class.getResourceAsStream("/" + name);

    private static Function<String, InputStream> files(Map<String, String> files) {
        return name -> files.containsKey(name) ? new ByteArrayInputStream(files.get(name).getBytes(StandardCharsets.UTF_8)) : null;
    }

    @Test void theIndexNamesFilesThatExist() throws IOException {
        var names = FlowsheetExamples.index(BUNDLED);
        assertFalse(names.isEmpty());
        assertEquals(names.size(), names.stream().distinct().count(), "each once");
        for (var name : names) {
            assertTrue(name.matches("[a-z0-9_-]+\\.json"), name);
            try (var in = BUNDLED.apply(FlowsheetExamples.FOLDER + name)) {
                assertNotNull(in, name + " is bundled");
            }
        }
    }

    /** A material revision bump moves the package's revision; the example then needs writing again on the new one. */
    @Test void eachExampleIsTheCurrentFormatOnTheCurrentPackage() throws IOException {
        var basis = SheetFixture.basis();
        for (var name : FlowsheetExamples.index(BUNDLED)) {
            try (var in = BUNDLED.apply(FlowsheetExamples.FOLDER + name)) {
                var json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                assertEquals(FlowsheetFile.FORMAT, json.get("format").getAsInt(), name + " is written in the current format: re-record it");
            }
        }
        for (var example : FlowsheetExamples.read(BUNDLED)) {
            assertNotNull(example.sheet(), () -> example.stem() + " reads: " + example.refused());
            assertEquals(basis.packageId(), example.sheet().basis().packageId(), example.stem() + ": the network package changed, re-record the example on it");
            assertEquals(basis.revision(), example.sheet().basis().revision(), example.stem() + ": the package's revision moved, re-record the example (write it again on the new revision and run the sample gates)");
            assertEquals(basis.components(), example.sheet().basis().components(), example.stem() + ": the package's components changed, re-record the example");
        }
    }

    @Test void eachExampleHasItsName() {
        for (var example : FlowsheetExamples.read(BUNDLED)) assertTrue(SheetFixture.Texts.has("gui.icheme.flowsheet.example." + example.stem()), example.stem());
    }

    @Test void openListsTheExamplesFirstMarkedExample() throws IOException {
        var folder = Path.of("build/tmp/simulator-examples");
        Files.createDirectories(folder);
        try (var old = Files.list(folder)) {
            for (var file : old.toList()) Files.delete(file);
        }
        FlowsheetFile.save(FileColumns.file(folder, "Mine"), SheetFixture.water());
        var t = new SheetFixture.Texts();
        var examples = FlowsheetExamples.read(BUNDLED);
        var entries = FileColumns.entries(folder, examples, t);
        assertEquals(examples.size() + 1, entries.size());
        var crude = entries.getFirst();
        assertTrue(crude.example());
        assertEquals("Crude unit (CDU)", crude.name());
        assertEquals("Example", crude.saved());
        assertEquals("7 units", crude.size());
        assertEquals("Ready", crude.state());
        assertTrue(crude.opens());
        assertNull(crude.file(), "an example is no file of the player's");
        assertEquals("Mine", entries.getLast().name(), "the player's files after");
        assertFalse(entries.getLast().example());
        assertEquals("Crude unit (CDU)", FileColumns.open(entries, 0, t).rows().getFirst().cells()[0]);
    }

    @Test void openingAnExampleGivesAnUnnamedSheet() throws IOException {
        var t = new SheetFixture.Texts();
        var entries = FileColumns.entries(Path.of("build/tmp/simulator-examples-none"), FlowsheetExamples.read(BUNDLED), t);
        var opened = FileColumns.opened(entries.getFirst());
        assertNull(opened.name(), "no file name: Save asks for one, and the example itself never changes");
        assertSame(entries.getFirst().sheet(), opened.sheet());
        assertEquals(SheetFixture.sample().units().size(), opened.sheet().units().size());
    }

    /** A bundled file that does not read is listed with its reason, as a damaged file is, and Open never throws. */
    @Test void aBrokenExampleIsListedWithItsReason() throws IOException {
        var t = new SheetFixture.Texts();
        var read = FlowsheetExamples.read(files(Map.of(FlowsheetExamples.INDEX, "[\"broken.json\", \"gone.json\"]", FlowsheetExamples.FOLDER + "broken.json", "{ not a sheet")));
        assertEquals(List.of("broken", "gone"), read.stream().map(FlowsheetExamples.Example::stem).toList());
        for (var example : read) assertEquals("file.damaged", example.refused());
        var entry = FileColumns.entries(Path.of("build/tmp/simulator-examples-none"), read, t).getFirst();
        assertFalse(entry.opens());
        assertTrue(entry.warn());
        assertEquals("Example", entry.saved());
        assertThrows(IOException.class, () -> FileColumns.opened(entry));
        var older = FlowsheetExamples.read(files(Map.of(FlowsheetExamples.INDEX, "[\"old.json\"]", FlowsheetExamples.FOLDER + "old.json", "{\"format\": 1}")));
        assertEquals("file.older", older.getFirst().refused());
        assertEquals("index", FlowsheetExamples.read(files(Map.of())).getFirst().stem(), "no index: one row says so");
        assertEquals("index", FlowsheetExamples.read(files(Map.of(FlowsheetExamples.INDEX, "[\"../x.json\"]"))).getFirst().stem(), "a name outside the folder is refused");
    }
}
