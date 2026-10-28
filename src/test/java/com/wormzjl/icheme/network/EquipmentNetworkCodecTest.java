package com.wormzjl.icheme.network;

import com.wormzjl.icheme.network.EquipmentNetwork.Action;
import com.wormzjl.icheme.network.EquipmentNetwork.ConstructorPayload;
import com.wormzjl.icheme.network.EquipmentNetwork.ImportAnswer;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.Design;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The equipment protocol's wire (batch 2026-10-27-constructor-import): the screen's draft crosses with what to do with
 * it, an import lands on that draft, and the answer carries the imported design whole or only why there is none.
 */
class EquipmentNetworkCodecTest {
    private static final BlockPos AT = new BlockPos(40, 70, -12);
    private static final ColumnComponents COMPONENTS = new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.IN_LINE,
            List.of(ColumnComponents.Pump.STANDARD), true, false);
    /** The constructor's stored design: a 6 m column imported earlier, right in front of the constructor. */
    private static final Design STORED = Design.DEFAULT.imported(new ColumnDesign(new ColumnTrays(20, 15, List.of(8), List.of(21),
            List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS), 6));
    /** The open screen's draft: the player moved the column, repainted it and turned its sump steam to the front. */
    private static final Design DRAFT = new Design(STORED.diameter(), STORED.tangentLength(), STORED.form(), STORED.support(), new VesselPlacement(5, -2, 1),
            STORED.trays(), STORED.connections(), new Paint(0x123456, 0x654321), STORED.bank(), STORED.boot(), STORED.outlet(), STORED.column(),
            List.of(new ColumnTrays.Turn(EquipmentShape.Role.STEAM, 1, 0)));

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T payload) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, payload);
            var read = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return read;
        } finally {
            buffer.release();
        }
    }

    @Test
    void aDraftCrossesWithWhatToDoWithIt() {
        for (var action : Action.values()) {
            var read = roundTrip(ConstructorPayload.STREAM_CODEC, ConstructorPayload.of(AT, DRAFT, action));
            assertEquals(AT, read.position());
            assertEquals(action, read.action());
            // The payload carries no column: the server keeps its own.
            assertEquals(DRAFT, read.design(STORED));
        }
    }

    /** Import sends the draft first: the calculator's column lands on it, and the player's edits stay. */
    @Test
    void anImportLandsOnTheDraftTheScreenSent() {
        var next = new ColumnDesign(new ColumnTrays(24, 12, List.of(6), List.of(25), List.of(new ColumnTrays.Pumparound(6, 4)), COMPONENTS), 4);
        var imported = roundTrip(ConstructorPayload.STREAM_CODEC, ConstructorPayload.of(AT, DRAFT, Action.IMPORT)).design(STORED).imported(next);
        assertEquals(next, imported.column());
        assertEquals(DRAFT.placement(), imported.placement());
        assertEquals(DRAFT.paint(), imported.paint());
        assertEquals(DRAFT.turns(), imported.turns());
        // On the stored design, as before the fix, the edits are lost.
        assertNotEquals(DRAFT.placement(), STORED.imported(next).placement());
    }

    /**
     * The answer's design tags (what {@link ImportAnswer} carries) read back on the client as the server wrote them, with
     * no repair. The answer's message is Minecraft's component codec, which reaches the item registry a unit test lacks.
     */
    @Test
    void anAnsweredDesignReadsBackAsTheServerWroteIt() {
        var tag = new CompoundTag();
        EquipmentConstructorBlockEntity.writeDesign(tag, DRAFT);
        var repairs = new ArrayList<String>();
        assertEquals(DRAFT, EquipmentConstructorBlockEntity.readDesign(tag, Design.DEFAULT, repairs::add));
        assertEquals(List.of(), repairs);
    }
}
