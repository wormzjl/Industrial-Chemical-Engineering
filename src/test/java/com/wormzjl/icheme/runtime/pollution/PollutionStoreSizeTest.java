package com.wormzjl.icheme.runtime.pollution;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.GroundField;
import com.wormzjl.icheme.science.pollution.GroundLedger;
import com.wormzjl.icheme.science.pollution.MapColumns;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;

/**
 * What {@code icheme_pollution.dat} holds per cube and per ground column at the caps (batch
 * 2026-11-18-pollution-propagation, P2): the air 40 bytes a cube (a key and four amounts), the ground 16 bytes a column
 * and 41 a compound entry (a tag of its column, name and moles), before the file's compression.
 */
class PollutionStoreSizeTest {
    private static int bytes(CompoundTag tag, boolean compressed) throws IOException {
        var out = new ByteArrayOutputStream();
        if (compressed) {
            NbtIo.writeCompressed(tag, out);
        } else {
            NbtIo.write(tag, new DataOutputStream(out));
        }
        return out.size();
    }

    private static PollutionField air(int cubes) {
        long[] keys = new long[cubes];
        for (int i = 0; i < cubes; i++) keys[i] = CubeKey.of(i % 32 - 16, i / 1024 - 4, i / 32 % 32 - 16);
        Arrays.sort(keys);
        double[] amounts = new double[cubes * 4];
        for (int i = 0; i < amounts.length; i++) amounts[i] = 1000.0 + 7.0 * i;
        return PollutionField.of(keys, amounts);
    }

    private static GroundField ground(int columns, int compounds) {
        String[] names = {"Ammonia", "Benzene", "Hydrogen sulfide", "Phenol", "crude_pc08", "Sulfur dioxide"};
        long[] keys = new long[columns];
        for (int i = 0; i < columns; i++) keys[i] = MapColumns.column(i % 128 - 64, i / 128 - 64);
        Arrays.sort(keys);
        double[] amounts = new double[columns];
        List<GroundField.Entry> entries = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            amounts[i] = 1e5 + i;
            String[] chosen = new String[compounds];
            for (int c = 0; c < compounds; c++) chosen[c] = names[(i + 2 * c) % names.length];
            Arrays.sort(chosen);
            for (String name : chosen) entries.add(new GroundField.Entry(keys[i], name, 10.0 + i));
        }
        return GroundField.of(keys, amounts, entries);
    }

    @Test
    void theSaveHoldsFortyBytesACubeAndSixteenAColumnBeforeCompression() throws IOException {
        int empty = bytes(PollutionStore.write(Map.of("minecraft:overworld", new PollutionStore.Saved(PollutionField.EMPTY,
                PollutionLedger.EMPTY, List.of(), GroundField.EMPTY, GroundLedger.EMPTY, List.of()))), false);
        int cubes = 8192;
        int airOnly = bytes(PollutionStore.write(Map.of("minecraft:overworld", new PollutionStore.Saved(air(cubes),
                PollutionLedger.EMPTY, List.of(), GroundField.EMPTY, GroundLedger.EMPTY, List.of()))), false);
        int columns = 16384;
        var bare = new PollutionStore.Saved(PollutionField.EMPTY, PollutionLedger.EMPTY, List.of(), ground(columns, 0),
                GroundLedger.EMPTY, List.of());
        var rich = new PollutionStore.Saved(PollutionField.EMPTY, PollutionLedger.EMPTY, List.of(), ground(columns, 3),
                GroundLedger.EMPTY, List.of());
        int groundBare = bytes(PollutionStore.write(Map.of("minecraft:overworld", bare)), false);
        int groundRich = bytes(PollutionStore.write(Map.of("minecraft:overworld", rich)), false);
        int groundRichCompressed = bytes(PollutionStore.write(Map.of("minecraft:overworld", rich)), true);
        double perCube = (airOnly - empty) / (double) cubes;
        double perColumn = (groundBare - empty) / (double) columns;
        double perEntry = (groundRich - groundBare) / (3.0 * columns);
        System.out.printf("save bytes: empty %d; air %.2f a cube; ground %.2f a column, %.2f a compound entry; 16384 columns with 3"
                + " compounds each %d bytes, %d compressed%n", empty, perCube, perColumn, perEntry, groundRich, groundRichCompressed);
        assertTrue(perCube <= 40.1, "air bytes a cube " + perCube);
        assertTrue(perColumn <= 16.1, "ground bytes a column " + perColumn);
        assertTrue(perEntry <= 41.1, "ground bytes a compound entry " + perEntry);
    }
}
