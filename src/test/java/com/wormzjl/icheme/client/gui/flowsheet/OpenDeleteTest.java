package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open's Delete (batch 2026-11-02-process-simulator, owner 2026-11-04: "open flowsheet page should have a deleting function (Can't delete
 * the examples through)"): any of the player's own files goes, an example never; only the chosen file, and only inside the folder.
 */
final class OpenDeleteTest {
    private static Path folder(String name) throws IOException {
        var folder = Path.of("build/tmp/" + name);
        Files.createDirectories(folder);
        try (var old = Files.list(folder)) {
            for (var file : old.toList()) {
                if (Files.isDirectory(file)) {
                    try (var inner = Files.list(file)) {
                        for (var f : inner.toList()) Files.delete(f);
                    }
                }
                Files.delete(file);
            }
        }
        return folder;
    }

    private static FileColumns.Entry row(List<FileColumns.Entry> entries, String name) {
        return entries.stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " in " + entries.stream().map(FileColumns.Entry::name).toList()));
    }

    @Test void deleteIsOffWithNoRowAndOnAnExample() throws IOException {
        var folder = folder("simulator-delete-keys");
        FlowsheetFile.save(FileColumns.file(folder, "Mine"), SheetFixture.water());
        Files.writeString(FileColumns.file(folder, "Broken"), "{ not a sheet");
        FlowsheetFile.save(FileColumns.autosave(folder, "Mine", "a"), SheetFixture.water());
        var t = new SheetFixture.Texts();
        var entries = FileColumns.entries(folder, FlowsheetExamples.read(name -> OpenDeleteTest.class.getResourceAsStream("/" + name)), t);
        assertEquals("Choose one of your sheets first.", FileColumns.open(entries, -1, t).off()[FileColumns.OPEN_DELETE]);
        assertTrue(entries.getFirst().example());
        assertEquals("An example stays: only your own sheets can go.", FileColumns.open(entries, 0, t).off()[FileColumns.OPEN_DELETE]);
        for (var name : List.of("Mine", "Broken", "Autosave of Mine")) {
            int at = entries.indexOf(row(entries, name));
            assertNull(FileColumns.open(entries, at, t).off()[FileColumns.OPEN_DELETE], name + ": the player's own file goes, damaged or an autosave too");
        }
        assertNull(FileColumns.open(entries, 0, t).off()[FileColumns.OPEN_OPEN], "Open stays on");
        assertTrue(t.missing.isEmpty(), () -> "missing " + t.missing);
    }

    @Test void deleteTakesOnlyTheChosenFile() throws IOException {
        var folder = folder("simulator-delete-one");
        FlowsheetFile.save(FileColumns.file(folder, "Mine"), SheetFixture.water());
        FlowsheetFile.save(FileColumns.autosave(folder, "Mine", "a"), SheetFixture.water());
        FlowsheetFile.save(FileColumns.file(folder, "Other"), SheetFixture.water());
        var t = new SheetFixture.Texts();
        FileColumns.delete(folder, row(FileColumns.entries(folder, t), "Mine"));
        assertFalse(Files.exists(FileColumns.file(folder, "Mine")));
        assertEquals(List.of("Autosave of Mine", "Other"), FileColumns.entries(folder, t).stream().map(FileColumns.Entry::name).sorted().toList(), "its autosave has its own row and stays");
    }

    @Test void theSheetForgetsItsFileNameOnlyWhenItsOwnFileGoes() throws IOException {
        var folder = folder("simulator-delete-name");
        FlowsheetFile.save(FileColumns.file(folder, "Mine"), SheetFixture.water());
        FlowsheetFile.save(FileColumns.file(folder, "Other"), SheetFixture.water());
        var entries = FileColumns.entries(folder, new SheetFixture.Texts());
        assertNull(FileColumns.nameAfterDelete(folder, "Mine", row(entries, "Mine")), "Save asks for a name");
        assertEquals("Mine", FileColumns.nameAfterDelete(folder, "Mine", row(entries, "Other")));
        assertNull(FileColumns.nameAfterDelete(folder, null, row(entries, "Other")));
    }

    /** An example has no file, so it never reaches the file system; a path outside the folder or no regular file is refused and nothing changes. */
    @Test void onlyARegularFileDirectlyInTheFolderGoes() throws IOException {
        var folder = folder("simulator-delete-guard");
        var t = new SheetFixture.Texts();
        var example = FileColumns.entries(folder, FlowsheetExamples.read(name -> OpenDeleteTest.class.getResourceAsStream("/" + name)), t).getFirst();
        assertFalse(FileColumns.deletable(example));
        assertThrows(IOException.class, () -> FileColumns.delete(folder, example));
        var outside = folder("simulator-delete-outside").resolve("Elsewhere.json");
        FlowsheetFile.save(outside, SheetFixture.water());
        var stray = new FileColumns.Entry(outside, "Elsewhere", "", "", "", false, true);
        assertThrows(IOException.class, () -> FileColumns.delete(folder, stray));
        assertTrue(Files.exists(outside), "a file outside the folder stays");
        var climbing = new FileColumns.Entry(folder.resolve("../simulator-delete-outside/Elsewhere.json"), "Elsewhere", "", "", "", false, true);
        assertThrows(IOException.class, () -> FileColumns.delete(folder, climbing));
        assertTrue(Files.exists(outside));
        var directory = folder.resolve("Nested.json");
        Files.createDirectories(directory);
        assertThrows(IOException.class, () -> FileColumns.delete(folder, new FileColumns.Entry(directory, "Nested", "", "", "", false, true)));
        assertTrue(Files.isDirectory(directory), "a folder is no sheet");
    }

    @Test void thePromptSaysTheFileCannotBeRestored() {
        var t = new SheetFixture.Texts();
        assertEquals("Delete Mine?", t.get("flowsheet.file.delete_title", "Mine"));
        assertEquals(List.of("The file cannot be restored."), FileColumns.deleting(t).stream().map(FileColumns.Change::text).toList());
        assertTrue(t.missing.isEmpty(), () -> "missing " + t.missing);
    }
}
