package com.wormzjl.icheme.network;

import com.wormzjl.icheme.science.column.v3.V3EditorCatalog;
import com.wormzjl.icheme.science.column.v3.V3TestInputs;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3State;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity.V3Status;
import com.wormzjl.icheme.world.level.block.entity.ColumnDesignTags;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A saved column design on the calculator's state packet and in its block's tags (batch 2026-10-26-multi-machine-framework). */
class ColumnDesignCodecTest {
    private static ColumnDesign design() {
        return ColumnDesign.of(V3TestInputs.methaneCduInput(), new ColumnComponents.Choices(ColumnComponents.Condenser.EXCHANGER,
                ColumnComponents.Pump.IN_LINE, List.of(ColumnComponents.Pump.STANDARD, ColumnComponents.Pump.IN_LINE)));
    }

    @Test
    void theSavedDesignRoundTripsOnTheStatePacket() throws Exception {
        var input = V3TestInputs.methaneCduInput();
        var state = new V3State(1, 2, 0, 1, -1, V3Status.DIRTY, input, Optional.empty(), List.of(), Map.of(), V3EditorCatalog.EMPTY, Optional.of(design()));
        assertEquals(state, ColumnCodecs.wireRoundTrip(state));
    }

    @Test
    void theSavedDesignRoundTripsInTheBlockTagsAndRefusesAnUnknownKind() {
        var design = design();
        assertNotNull(design.trays().operating(), "Save design keeps the operating point");
        var tag = ColumnDesignTags.write(design);
        assertEquals(design, ColumnDesignTags.read(tag));
        var pumps = new ListTag();
        pumps.add(StringTag.valueOf("JET"));
        tag.put("PumparoundPumps", pumps);
        assertThrows(IllegalArgumentException.class, () -> ColumnDesignTags.read(tag));
        var noTrays = ColumnDesignTags.write(design);
        noTrays.remove("Trays");
        assertThrows(IllegalArgumentException.class, () -> ColumnDesignTags.read(noTrays));
    }
}
