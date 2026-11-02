package com.wormzjl.icheme.science.flowsheet;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The block's half of the sheet (W8 of batch 2026-11-02-process-simulator, plan section 3.4): an upload is untrusted, and bounded before it inflates; a stored sheet never refuses a
 * world; the first player edits, another reads, and the sheet is free when the editor goes.
 */
class SheetStoreTest {
    private static final UUID EDITOR = UUID.randomUUID(), READER = UUID.randomUUID();
    private static final String READ_ONLY = "store.read_only";

    private static Flowsheet sheet() {
        return new Flowsheet(new Flowsheet.Basis("icheme:test", "r1", List.of("Methane", "Water")), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT);
    }

    private static byte[] body() {
        return SheetBody.pack(FlowsheetFile.write(sheet()), "results".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] gzip(byte[] content) throws IOException {
        var packed = new ByteArrayOutputStream();
        try (var zip = new GZIPOutputStream(packed)) {
            zip.write(content);
        }
        return packed.toByteArray();
    }

    private static String refusal(byte[] packed) {
        return assertThrows(SheetBody.Refused.class, () -> SheetBody.unpack(packed)).key();
    }

    @Test
    void aBodyOpensToWhatWasPacked() throws Exception {
        var contents = SheetBody.unpack(body());
        assertArrayEquals(FlowsheetFile.write(sheet()), contents.sheet());
        assertEquals("results", new String(contents.results(), StandardCharsets.UTF_8));
    }

    /** Bytes that are no gzip would read as damaged if anything inflated them, so a size refusal proves nothing did. */
    @Test
    void aBodyOverTheBoundIsRefusedBeforeItInflates() {
        assertEquals(SheetBody.TOO_LARGE, refusal(new byte[SheetBody.MAX_PACKED + 1]));
        var liar = new byte[1024];
        ByteBuffer.wrap(liar).order(ByteOrder.LITTLE_ENDIAN).putInt(liar.length - 4, SheetBody.MAX_UNPACKED + 1);
        assertEquals(SheetBody.TOO_LARGE, refusal(liar));
        assertEquals(SheetBody.DAMAGED, refusal(new byte[1024]), "and the same bytes with no claim do reach the inflater");
    }

    /** A trailer is the sender's word: a bomb that claims to be small stops at the bound. */
    @Test
    void aBombWithAFalseTrailerStopsAtTheBound() throws Exception {
        var bomb = gzip(new byte[8 * SheetBody.MAX_UNPACKED]);
        assertTrue(bomb.length < SheetBody.MAX_PACKED);
        ByteBuffer.wrap(bomb).order(ByteOrder.LITTLE_ENDIAN).putInt(bomb.length - 4, 1000);
        assertEquals(SheetBody.TOO_LARGE, refusal(bomb));
    }

    @Test
    void aFullBodyThatDoesNotShrinkStillFits() throws Exception {
        var noise = new byte[SheetBody.MAX_UNPACKED - 5];
        new Random(1).nextBytes(noise);
        assertTrue(SheetBody.fits(noise.length, 0));
        assertFalse(SheetBody.fits(noise.length, 1));
        var packed = SheetBody.pack(noise, new byte[0]);
        assertTrue(packed.length <= SheetBody.MAX_PACKED, () -> "packed " + packed.length);
        assertArrayEquals(noise, SheetBody.unpack(packed).sheet());
        var parts = SheetBody.parts(packed);
        assertTrue(parts.size() <= SheetBody.MAX_PARTS);
        var assembly = new SheetBody.Assembly();
        byte[] whole = null;
        for (int i = 0; i < parts.size(); i++) whole = assembly.add(i, parts.size(), parts.get(i));
        assertArrayEquals(packed, whole);
    }

    @Test
    void badPartsAreRefusedAndKeepNothing() throws Exception {
        var part = new byte[100];
        var assembly = new SheetBody.Assembly();
        assertEquals(SheetBody.TOO_LARGE, assertThrows(SheetBody.Refused.class, () -> assembly.add(0, SheetBody.MAX_PARTS + 1, part)).key(), "a count over the bound stops it at part 0");
        assertEquals(SheetBody.PARTS, assertThrows(SheetBody.Refused.class, () -> assembly.add(1, 3, part)).key(), "no part 0");
        assertNull(assembly.add(0, 3, part));
        assertEquals(SheetBody.PARTS, assertThrows(SheetBody.Refused.class, () -> assembly.add(2, 3, part)).key(), "a part skipped");
        assertEquals(SheetBody.PARTS, assertThrows(SheetBody.Refused.class, () -> assembly.add(1, 3, part)).key(), "a refused body keeps nothing");
        assertNull(assembly.add(0, 2, part));
        assertEquals(SheetBody.PARTS, assertThrows(SheetBody.Refused.class, () -> assembly.add(1, 3, part)).key(), "a count that changes");
        assertEquals(SheetBody.PARTS, assertThrows(SheetBody.Refused.class, () -> assembly.add(0, 1, new byte[SheetBody.PART + 1])).key(), "a part over its size");
        assertEquals(SheetBody.PARTS, assertThrows(SheetBody.Refused.class, () -> assembly.add(0, 0, part)).key());
        assertNull(assembly.add(0, 2, part));
        assertArrayEquals(new byte[200], assembly.add(1, 2, part));
        var full = new byte[SheetBody.PART];
        var last = new SheetBody.Assembly();
        for (int i = 0; i < SheetBody.MAX_PARTS - 1; i++) assertNull(last.add(i, SheetBody.MAX_PARTS, full));
        assertEquals(SheetBody.TOO_LARGE, assertThrows(SheetBody.Refused.class, () -> last.add(SheetBody.MAX_PARTS - 1, SheetBody.MAX_PARTS, full)).key(), "the sum over the bound");
    }

    @Test
    void theFirstPlayerEditsAndAnotherReads() throws Exception {
        var store = new SheetStore();
        assertTrue(store.open(EDITOR, id -> true));
        assertFalse(store.open(READER, id -> true));
        assertEquals(EDITOR, store.holder());
        assertTrue(store.open(EDITOR, id -> true), "the editor coming back still edits");
        store.accept(EDITOR, body(), READ_ONLY);
        var stored = store.body();
        assertEquals(READ_ONLY, assertThrows(SheetBody.Refused.class, () -> store.accept(READER, body(), READ_ONLY)).key());
        assertSame(stored, store.body(), "a reader's upload leaves what is stored");
    }

    @Test
    void theHolderFreesOnCloseOnLeavingAndWhenTheBlockGoes() {
        var store = new SheetStore();
        store.open(EDITOR, id -> true);
        store.release(READER);
        assertEquals(EDITOR, store.holder(), "a reader who closes frees nothing");
        store.release(EDITOR);
        assertNull(store.holder());
        assertTrue(store.open(READER, id -> true), "the next to open edits");
        store.free();
        assertNull(store.holder());
        store.open(EDITOR, id -> true);
        assertTrue(store.open(READER, id -> false), "a holder no longer at the block holds nothing, whatever word went missing");
    }

    @Test
    void aBadUploadLeavesTheStoredSheetAndSaysWhy() throws Exception {
        var store = new SheetStore();
        store.open(EDITOR, id -> true);
        store.accept(EDITOR, body(), READ_ONLY);
        var stored = store.body();
        var bad = List.of(new byte[SheetBody.MAX_PACKED + 1], new byte[40], gzip(new byte[10]), SheetBody.pack("not json".getBytes(StandardCharsets.UTF_8), new byte[0]), SheetBody.pack("{\"format\":99}".getBytes(StandardCharsets.UTF_8), new byte[0]));
        var keys = new ArrayList<String>();
        for (var upload : bad) keys.add(assertThrows(SheetBody.Refused.class, () -> store.accept(EDITOR, upload, READ_ONLY)).key());
        assertEquals(List.of(SheetBody.TOO_LARGE, SheetBody.DAMAGED, SheetBody.DAMAGED, "file.damaged", "file.newer"), keys);
        assertSame(stored, store.body());
    }

    @Test
    void aDamagedStoredSheetOpensEmptySaysSoAndKeepsItsBytes() throws Exception {
        for (var damaged : List.of(new byte[]{1, 2, 3}, SheetBody.pack("{\"format\":99}".getBytes(StandardCharsets.UTF_8), new byte[0]))) {
            var store = new SheetStore();
            store.load(damaged, List.of());
            assertSame(damaged, store.body(), "a load parses nothing");
            assertEquals(0, store.replay().length);
            assertTrue(store.damaged());
            assertEquals(1, store.kept().size());
            assertArrayEquals(damaged, store.kept().getFirst());
            assertEquals(0, store.replay().length, "and it stays so");
            store.open(EDITOR, id -> true);
            store.accept(EDITOR, body(), READ_ONLY);
            assertFalse(store.damaged(), "a new sheet stands over it");
            assertEquals(1, store.kept().size(), "and the old bytes stay for a later version");
            assertArrayEquals(damaged, store.kept().getFirst());
        }
    }

    @Test
    void anUploadOverABodyNoOneOpenedSetsItAside() throws Exception {
        var store = new SheetStore();
        var damaged = new byte[]{9, 9};
        store.load(damaged, List.of());
        store.open(EDITOR, id -> true);
        store.accept(EDITOR, body(), READ_ONLY);
        assertArrayEquals(damaged, store.kept().getFirst());
    }

    @Test
    void aGoodStoredSheetReplaysAsStored() {
        var store = new SheetStore();
        var body = body();
        store.load(body, List.of());
        assertSame(body, store.replay());
        assertFalse(store.damaged());
        assertTrue(store.kept().isEmpty());
        assertTrue(Arrays.equals(body, store.body()));
    }
}
