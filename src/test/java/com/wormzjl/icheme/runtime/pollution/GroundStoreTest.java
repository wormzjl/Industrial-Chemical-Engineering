package com.wormzjl.icheme.runtime.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.GroundEnvironment;
import com.wormzjl.icheme.science.pollution.GroundField;
import com.wormzjl.icheme.science.pollution.GroundLedger;
import com.wormzjl.icheme.science.pollution.GroundSettings;
import com.wormzjl.icheme.science.pollution.GroundSnapshot;
import com.wormzjl.icheme.science.pollution.MapColumns;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/**
 * The pollution store's ground (batch 2026-11-18-ground-pollution, G2; one amount per column): schema 2 round-trips the ground, its ledger and
 * its pending deposits bit for bit; a schema-1 store reads with an empty ground; damaged ground arrays drop that
 * dimension's ground alone, with a note; an unknown compound name is kept. And the published snapshot.
 */
class GroundStoreTest {
    private static final String OVERWORLD = PollutionStoreTest.OVERWORLD;

    private static PollutionStore.Saved withGround() {
        var deposits = List.of(new GroundField.Deposit(MapColumns.column(-2, 5), 3e6 + 1e6 / 3,
                List.of(new GroundField.Compound("crude_pc08", Math.PI), new GroundField.Compound("no_package_has_it", Math.E))));
        var r = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, deposits, GroundEnvironment.open(false), 20, GroundSettings.DEFAULT);
        var pending = new GroundField.Deposit(MapColumns.column(1, 1), 0.1, List.of());
        return new PollutionStore.Saved(PollutionField.EMPTY, PollutionLedger.EMPTY, List.of(), r.field(), r.ledger(), List.of(pending));
    }

    private static long[] bits(double[] values) {
        return PollutionStoreTest.bits(values);
    }

    @Test void theGroundRoundTripsBitForBit() {
        var saved = withGround();
        var tag = PollutionStore.write(Map.of(OVERWORLD, saved));
        var loaded = PollutionStore.read(tag);
        assertEquals(List.of(), loaded.notes());
        var back = loaded.dimensions().get(OVERWORLD);
        assertArrayEquals(saved.ground().columns(), back.ground().columns());
        assertArrayEquals(bits(saved.ground().amounts()), bits(back.ground().amounts()));
        assertEquals(saved.ground().entries(), back.ground().entries());
        assertEquals(saved.groundLedger(), back.groundLedger());
        assertEquals(1, back.groundPending().size());
        assertEquals(Double.doubleToRawLongBits(saved.groundPending().getFirst().amount()), Double.doubleToRawLongBits(back.groundPending().getFirst().amount()));
        assertEquals(saved.ground().moles(MapColumns.column(-2, 5), "no_package_has_it"), back.ground().moles(MapColumns.column(-2, 5), "no_package_has_it"), 0, "a compound no package holds is a fact: kept");
        assertTrue(back.ground().moles(MapColumns.column(-2, 5), "no_package_has_it") > 0);
        assertEquals(tag, PollutionStore.write(loaded.dimensions()), "written again, the same tag");
    }

    @Test void aDimensionWithoutGroundWritesNoGroundKeysAndASchemaOneStoreReadsWithAnEmptyGround() {
        var tag = PollutionStore.write(PollutionStoreTest.sample());
        var overworld = tag.getCompound("dimensions").getCompound(OVERWORLD);
        assertFalse(overworld.contains("ground_columns"), "no ground, no ground keys");
        tag.putInt("schema", 1);
        var loaded = PollutionStore.read(tag);
        assertFalse(loaded.setAside());
        assertEquals(List.of(), loaded.notes());
        assertEquals(0, loaded.dimensions().get(OVERWORLD).ground().size());
        assertEquals(GroundLedger.EMPTY, loaded.dimensions().get(OVERWORLD).groundLedger());
    }

    @Test void damagedGroundArraysDropThatDimensionsGroundAlone() {
        var tag = PollutionStore.write(Map.of(OVERWORLD, withGround(), PollutionStoreTest.NETHER, withGround()));
        var overworld = tag.getCompound("dimensions").getCompound(OVERWORLD);
        overworld.put("ground_amounts", new LongArrayTag(new long[] {1, 2, 3}));
        var loaded = PollutionStore.read(tag);
        assertFalse(loaded.setAside());
        assertEquals(1, loaded.notes().size(), loaded.notes().toString());
        assertTrue(loaded.notes().getFirst().contains("ground dropped"), loaded.notes().toString());
        assertEquals(0, loaded.dimensions().get(OVERWORLD).ground().size(), "the damaged ground is dropped");
        assertEquals(1, loaded.dimensions().get(OVERWORLD).groundPending().size(), "its pending deposits are read alone");
        assertTrue(loaded.dimensions().get(PollutionStoreTest.NETHER).ground().size() > 0, "the other dimension keeps its ground");
    }

    @Test void aDamagedGroundLedgerIsRebuiltFromTheColumns() {
        var saved = withGround();
        var tag = PollutionStore.write(Map.of(OVERWORLD, saved));
        tag.getCompound("dimensions").getCompound(OVERWORLD).put("ground_ledger", StringTag.valueOf("broken"));
        var loaded = PollutionStore.read(tag);
        assertEquals(1, loaded.notes().size(), loaded.notes().toString());
        var back = loaded.dimensions().get(OVERWORLD);
        assertEquals(0, back.groundLedger().closure(back.ground()), 1e-9);
        assertEquals(0, back.groundLedger().closure("crude_pc08", back.ground()), 1e-12);
    }

    @Test void theSnapshotHoldsEachColumnsByte() {
        var saved = withGround();
        var snapshot = GroundSnapshot.of(saved.ground(), GroundSettings.DEFAULT);
        long column = MapColumns.column(-2, 5);
        double index = saved.ground().index(column, GroundSettings.DEFAULT);
        assertEquals(MapColumns.quantise(index), snapshot.value(column));
        assertEquals(MapColumns.dequantise(MapColumns.quantise(index)), snapshot.index(column), 0);
        assertEquals(0, snapshot.value(MapColumns.column(9, 9)), "an absent column is 0");
        assertEquals(GroundSnapshot.EMPTY.size(), GroundSnapshot.of(GroundField.EMPTY, GroundSettings.DEFAULT).size());
    }
}
