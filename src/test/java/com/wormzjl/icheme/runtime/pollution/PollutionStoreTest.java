package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.science.pollution.Release;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pollution store's codec (batch 2026-11-15-pollution-system, WP3; plan section 8): it round-trips bit for bit and
 * never throws. A newer or unreadable schema sets the whole store aside; a damaged dimension, cube, ledger or pending
 * entry is dropped or rebuilt alone. The set-aside copy keeps the original file.
 */
class PollutionStoreTest {
    static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";

    static long[] bits(double... values) {
        long[] out = new long[values.length];
        for (int i = 0; i < values.length; i++) out[i] = Double.doubleToRawLongBits(values[i]);
        return out;
    }

    /** Two dimensions with fields, ledgers and pending releases; values with no short decimal form. */
    static Map<String, PollutionStore.Saved> sample() {
        var overworldField = PollutionField.of(new long[] {CubeKey.of(-3, 4, 7), CubeKey.of(0, 4, 0)},
                new double[] {Math.PI, 0, 1e-300, 12345.678901234567, 0.1, 0.2, 0.30000000000000004, Math.E});
        double[] book = new double[PollutionLedger.LENGTH];
        for (int i = 0; i < book.length; i++) book[i] = (i + 1) / 3.0;
        var netherField = PollutionField.of(new long[] {CubeKey.of(10, 2, -10)}, new double[] {0, 7.25, 0, 1.0 / 7});
        return Map.of(
                OVERWORLD, new PollutionStore.Saved(overworldField, PollutionLedger.of(book),
                        List.of(new Release(CubeKey.of(0, 4, 0), Channel.TOXIC, 2.0 / 3), new Release(CubeKey.of(1, 4, 0), Channel.PARTICULATE, 9.5))),
                NETHER, new PollutionStore.Saved(netherField, PollutionLedger.EMPTY, List.of()));
    }

    static void assertSameSaved(PollutionStore.Saved expected, PollutionStore.Saved actual) {
        assertArrayEquals(expected.field().keys(), actual.field().keys());
        assertArrayEquals(bits(expected.field().amounts()), bits(actual.field().amounts()));
        assertArrayEquals(bits(expected.ledger().values()), bits(actual.ledger().values()));
        assertEquals(expected.pending(), actual.pending());
    }

    @Test
    void twoDimensionsRoundTripBitForBit() {
        var sample = sample();
        var loaded = PollutionStore.read(PollutionStore.write(sample));
        assertFalse(loaded.setAside());
        assertEquals(List.of(), loaded.notes());
        assertEquals(sample.keySet(), loaded.dimensions().keySet());
        for (var id : sample.keySet()) assertSameSaved(sample.get(id), loaded.dimensions().get(id));
        assertEquals(PollutionStore.write(sample), PollutionStore.write(loaded.dimensions()), "written again, the same tag");
    }

    @Test
    void aNewerSchemaIsSetAside() {
        var tag = PollutionStore.write(sample());
        tag.putInt("schema", PollutionStore.SCHEMA + 1);
        var loaded = PollutionStore.read(tag);
        assertTrue(loaded.setAside());
        assertTrue(loaded.dimensions().isEmpty());
        assertFalse(loaded.notes().isEmpty());
    }

    @Test
    void aSchemaOfTheWrongTypeIsSetAside() {
        var tag = PollutionStore.write(sample());
        tag.putString("schema", "1");
        var loaded = PollutionStore.read(tag);
        assertTrue(loaded.setAside());
        assertTrue(loaded.dimensions().isEmpty());
        var missing = PollutionStore.write(sample());
        missing.remove("schema");
        assertTrue(PollutionStore.read(missing).setAside());
    }

    @Test
    void oneNaNCubeAmongTenIsDroppedAndTheOthersKept() {
        long[] keys = new long[10];
        double[] amounts = new double[40];
        for (int i = 0; i < 10; i++) {
            keys[i] = CubeKey.of(i, 4, 0);
            for (int c = 0; c < 4; c++) amounts[i * 4 + c] = 100.0 * (i + 1) + c;
        }
        var tag = PollutionStore.write(Map.of(OVERWORLD, new PollutionStore.Saved(PollutionField.of(keys, amounts), PollutionLedger.EMPTY, List.of())));
        var d = tag.getCompound("dimensions").getCompound(OVERWORLD);
        long[] saved = d.getLongArray("amounts");
        saved[3 * 4 + 1] = 0x7ff8000000000000L;
        d.putLongArray("amounts", saved);
        var loaded = PollutionStore.read(tag);
        assertFalse(loaded.setAside());
        var field = loaded.dimensions().get(OVERWORLD).field();
        assertEquals(9, field.size());
        assertTrue(field.find(CubeKey.of(3, 4, 0)) < 0);
        assertTrue(loaded.notes().stream().anyMatch(n -> n.contains("1 damaged cubes dropped")), loaded.notes().toString());
        // The kept ledger books the dropped cube's finite amounts as dissipated.
        assertEquals(400.0 + 402 + 403, loaded.dimensions().get(OVERWORLD).ledger().get(Channel.TOXIC, PollutionLedger.Total.DISSIPATED)
                + loaded.dimensions().get(OVERWORLD).ledger().get(Channel.CORROSIVE, PollutionLedger.Total.DISSIPATED)
                + loaded.dimensions().get(OVERWORLD).ledger().get(Channel.PARTICULATE, PollutionLedger.Total.DISSIPATED));
    }

    @Test
    void aRepeatedKeyIsDroppedAndUnsortedKeysAreAccepted() {
        var d = new CompoundTag();
        long a = CubeKey.of(5, 4, 5), b = CubeKey.of(-5, 4, -5), c = CubeKey.of(0, 0, 0);
        d.putLongArray("keys", new long[] {a, b, a, c});
        d.putLongArray("amounts", bits(1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4));
        var loaded = PollutionStore.read(root(d));
        var field = loaded.dimensions().get(OVERWORLD).field();
        long[] expected = {a, b, c};
        Arrays.sort(expected);
        assertArrayEquals(expected, field.keys());
        assertEquals(1.0, field.amount(a, Channel.TOXIC), "the first of a repeated key is kept");
        assertTrue(loaded.notes().stream().anyMatch(n -> n.contains("1 repeated cubes dropped")), loaded.notes().toString());
    }

    @Test
    void aLedgerOfNineteenValuesIsRebuiltFromTheCubes() {
        var d = new CompoundTag();
        d.putLongArray("keys", new long[] {CubeKey.of(1, 1, 1), CubeKey.of(2, 1, 1)});
        d.putLongArray("amounts", bits(1, 2, 3, 4, 10, 20, 30, 40));
        d.putLongArray("ledger", bits(new double[19]));
        var loaded = PollutionStore.read(root(d));
        var ledger = loaded.dimensions().get(OVERWORLD).ledger();
        assertEquals(11.0, ledger.get(Channel.TOXIC, PollutionLedger.Total.RELEASED));
        assertEquals(22.0, ledger.get(Channel.COMBUSTIBLE, PollutionLedger.Total.RELEASED));
        assertEquals(33.0, ledger.get(Channel.CORROSIVE, PollutionLedger.Total.RELEASED));
        assertEquals(44.0, ledger.get(Channel.PARTICULATE, PollutionLedger.Total.RELEASED));
        assertEquals(0.0, ledger.get(Channel.TOXIC, PollutionLedger.Total.DECAYED));
        for (var channel : Channel.values()) assertEquals(0.0, ledger.closure(channel, loaded.dimensions().get(OVERWORLD).field()));
        assertTrue(loaded.notes().stream().anyMatch(n -> n.contains("ledger rebuilt")));
    }

    @Test
    void anUnknownChannelNameInPendingIsDropped() {
        var d = new CompoundTag();
        d.putLongArray("keys", new long[0]);
        d.putLongArray("amounts", new long[0]);
        d.putLongArray("pending_keys", new long[] {7, 8});
        var channels = new ListTag();
        channels.add(StringTag.valueOf("TOXIC"));
        channels.add(StringTag.valueOf("RADIOACTIVE"));
        d.put("pending_channels", channels);
        d.putLongArray("pending_amounts", bits(1.5, 2.5));
        var loaded = PollutionStore.read(root(d));
        assertEquals(List.of(new Release(7, Channel.TOXIC, 1.5)), loaded.dimensions().get(OVERWORLD).pending());
        assertTrue(loaded.notes().stream().anyMatch(n -> n.contains("1 pending releases dropped")));
    }

    @Test
    void aDamagedDimensionIsDroppedAlone() {
        var tag = PollutionStore.write(sample());
        tag.getCompound("dimensions").getCompound(NETHER).put("amounts", new LongArrayTag(new long[3]));
        tag.getCompound("dimensions").put("Not A Dimension!", new CompoundTag());
        var loaded = PollutionStore.read(tag);
        assertFalse(loaded.setAside());
        assertEquals(List.of(OVERWORLD), List.copyOf(loaded.dimensions().keySet()));
        assertSameSaved(sample().get(OVERWORLD), loaded.dimensions().get(OVERWORLD));
        assertEquals(2, loaded.notes().size(), loaded.notes().toString());
    }

    @Test
    void anEmptyTagIsAFreshStore() {
        var loaded = PollutionStore.read(new CompoundTag());
        assertFalse(loaded.setAside());
        assertTrue(loaded.dimensions().isEmpty());
        assertTrue(loaded.notes().isEmpty());
        var schemaOnly = new CompoundTag();
        schemaOnly.putInt("schema", PollutionStore.SCHEMA);
        assertFalse(PollutionStore.read(schemaOnly).setAside());
    }

    @Test
    void aSaveThatFailsWritesTheLastGoodTagAndNeverThrows() {
        var store = new PollutionStore(PollutionStore.read(PollutionStore.write(sample())));
        var first = store.save(new CompoundTag(), null);
        assertEquals(PollutionStore.write(sample()), first, "the carried dimensions are saved untouched");
        store.live(() -> {
            throw new IllegalStateException("broken");
        });
        assertEquals(first, store.save(new CompoundTag(), null));
        var fresh = new PollutionStore();
        fresh.live(() -> {
            throw new IllegalStateException("broken");
        });
        var schemaOnly = fresh.save(new CompoundTag(), null);
        assertEquals(PollutionStore.SCHEMA, schemaOnly.getInt("schema"));
        assertEquals(1, schemaOnly.size());
    }

    @Test
    void aLiveDimensionReplacesItsCarriedOneAndAnEmptyOneIsNotWritten() {
        var store = new PollutionStore(PollutionStore.read(PollutionStore.write(sample())));
        store.live(() -> Map.of(NETHER, new PollutionStore.Saved(PollutionField.EMPTY, PollutionLedger.EMPTY, List.of())));
        var loaded = PollutionStore.read(store.save(new CompoundTag(), null));
        assertEquals(List.of(OVERWORLD), List.copyOf(loaded.dimensions().keySet()));
    }

    @Test
    void theSetAsideCopyKeepsTheOriginalAndTakesTheFirstFreeNumber() throws IOException {
        Path folder = Path.of("build/tmp/pollution-store-test");
        delete(folder);
        Files.createDirectories(folder);
        try {
            assertNull(PollutionStore.setAside(folder.resolve("icheme_pollution.dat")), "no file, no copy");
            Path file = folder.resolve("icheme_pollution.dat");
            Files.writeString(file, "damaged", StandardCharsets.UTF_8);
            Path first = PollutionStore.setAside(file);
            assertEquals(folder.resolve("icheme_pollution.damaged-1.dat"), first);
            Path second = PollutionStore.setAside(file);
            assertEquals(folder.resolve("icheme_pollution.damaged-2.dat"), second);
            assertEquals("damaged", Files.readString(file, StandardCharsets.UTF_8));
            assertEquals("damaged", Files.readString(first, StandardCharsets.UTF_8));
        } finally {
            delete(folder);
        }
    }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static CompoundTag root(CompoundTag overworld) {
        var root = new CompoundTag();
        root.putInt("schema", PollutionStore.SCHEMA);
        var dimensions = new CompoundTag();
        dimensions.put(OVERWORLD, overworld);
        root.put("dimensions", dimensions);
        return root;
    }
}
