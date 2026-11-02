package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoadedTowersTest {
    private static final EquipmentShape SHAPE = EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER, 80, 104, EquipmentShape.Heading.NORTH, 0);

    /** The chunk of the tower's node leaves the view while a chunk of its rim stays: the tower stays with it. */
    @Test void aTowerStandsWhileAnyOfItsCellsIsLoaded() {
        var index = new LoadedTowers();
        var origin = new BlockPos(16, 64, 16);
        var node = index.add(origin, SHAPE, Paint.DEFAULT);
        var rim = index.add(origin, SHAPE, Paint.DEFAULT);
        assertSame(node, rim, "One tower, however many cells");
        assertEquals(1, index.all().size());
        index.remove(node);
        assertEquals(1, index.all().size(), "Its rim is still loaded");
        index.remove(rim);
        assertTrue(index.all().isEmpty(), "Its last cell takes it");
    }

    @Test void towersAreToldApartByOrigin() {
        var index = new LoadedTowers();
        index.add(new BlockPos(0, 64, 0), SHAPE, Paint.DEFAULT);
        index.add(new BlockPos(200, 64, 0), SHAPE, Paint.DEFAULT);
        assertEquals(2, index.all().size());
    }
}
