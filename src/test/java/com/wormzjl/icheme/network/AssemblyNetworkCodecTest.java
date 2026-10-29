package com.wormzjl.icheme.network;

import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assembly protocol's wire (batch 2026-10-26-multi-machine-framework, plan section 11): a view crosses whole but for
 * its footprint, which no client reads; a read refuses counts out of range.
 */
class AssemblyNetworkCodecTest {
    private static final long COLUMN = new BlockPos(120, 64, -30).asLong(), DRUM = new BlockPos(131, 80, -30).asLong();

    private static AssemblyView roundTrip(AssemblyView view) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var payload = new AssemblyNetwork.StatePayload(7, view);
            AssemblyNetwork.StatePayload.STREAM_CODEC.encode(buffer, payload);
            var read = AssemblyNetwork.StatePayload.STREAM_CODEC.decode(buffer);
            assertEquals(7, read.container());
            assertEquals(0, buffer.readableBytes());
            return read.view();
        } finally {
            buffer.release();
        }
    }

    @Test
    void aViewCrossesWholeButForItsFootprint() {
        var report = new AssemblyReport(List.of(new SlotReport(ColumnBlueprint.COLUMN, State.OK, COLUMN, AssemblyReport.NONE, ""),
                new SlotReport(ColumnBlueprint.RECEIVER, State.WRONG_KIND, DRUM, DRUM, "DRUM_STANDING"),
                new SlotReport(ColumnBlueprint.drawValve(2), State.OPEN_END, AssemblyReport.NONE, new BlockPos(-5, -60, 7).asLong(), "")),
                List.of(new PortReport(COLUMN, Role.VAPOUR_OUTLET, 0, PortState.CONNECTED), new PortReport(DRUM, Role.SIDE, 3, PortState.UNUSED)),
                Set.of(1L, 2L, 3L), List.of(COLUMN, DRUM));
        var checked = new AssemblyView(AssemblyView.Status.CHECKED, report, AssemblyReport.NONE);
        var read = roundTrip(checked);
        assertEquals(new AssemblyView(AssemblyView.Status.CHECKED, new AssemblyReport(report.slots(), report.ports(), Set.of(), report.members()), AssemblyReport.NONE), read);
        assertEquals(1, read.report().connected());
        assertEquals(1, read.report().misplaced());
        assertEquals(1, read.report().missing());
        var reverted = new AssemblyView(AssemblyView.Status.NOT_VALIDATED, null, DRUM);
        assertEquals(reverted, roundTrip(reverted));
        assertEquals(AssemblyView.NO_DESIGN, roundTrip(AssemblyView.NO_DESIGN));
    }

    /** The Operation tab (C7): a view's operation crosses whole, a column never run and a running one; a command with its set points. */
    @Test
    void anOperationAndItsCommandsCross() {
        var report = new AssemblyReport(List.of(new SlotReport(ColumnBlueprint.COLUMN, State.OK, COLUMN, AssemblyReport.NONE, "")), List.of(), Set.of(), List.of(COLUMN));
        var complete = new AssemblyView(AssemblyView.Status.COMPLETE, report, AssemblyReport.NONE);
        var s = com.wormzjl.icheme.client.gui.common.OperationColumnTest.setpoints(2, 4, 4, true);
        for (var operation : List.of(com.wormzjl.icheme.client.gui.common.OperationColumnTest.running(s, com.wormzjl.icheme.science.column.operation.OperationView.Phase.LIMITED),
                com.wormzjl.icheme.science.column.operation.OperationView.idle(com.wormzjl.icheme.science.column.operation.OperationView.Phase.NOT_READY, "a vacuum column cannot run yet", s))) {
            var view = complete.withOperation(operation);
            assertEquals(view, roundTrip(view));
        }
        // A view with no operation (no design, a watch) crosses with none.
        assertNull(roundTrip(complete).operation());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (var payload : List.of(new AssemblyNetwork.OperationPayload(3, AssemblyNetwork.Action.APPLY, List.of(33.3, 318.15, 2.5, 0.0, .05, -63000.0)),
                    new AssemblyNetwork.OperationPayload(3, AssemblyNetwork.Action.START, List.of()), new AssemblyNetwork.OperationPayload(3, AssemblyNetwork.Action.STOP, List.of()))) {
                AssemblyNetwork.OperationPayload.STREAM_CODEC.encode(buffer, payload);
                assertEquals(payload, AssemblyNetwork.OperationPayload.STREAM_CODEC.decode(buffer));
            }
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    /** The laying tool's watch (W7): a watched column's view, or its end, crosses with its master. */
    @Test
    void aWatchedViewOrItsEndCrossesWithItsMaster() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var reverted = new AssemblyView(AssemblyView.Status.NOT_VALIDATED, null, DRUM);
            for (var payload : List.of(new AssemblyNetwork.WatchedPayload(COLUMN, java.util.Optional.of(reverted)), new AssemblyNetwork.WatchedPayload(COLUMN, java.util.Optional.empty()))) {
                AssemblyNetwork.WatchedPayload.STREAM_CODEC.encode(buffer, payload);
                assertEquals(payload, AssemblyNetwork.WatchedPayload.STREAM_CODEC.decode(buffer));
            }
            AssemblyNetwork.WatchPayload.STREAM_CODEC.encode(buffer, new AssemblyNetwork.WatchPayload(true));
            assertTrue(AssemblyNetwork.WatchPayload.STREAM_CODEC.decode(buffer).on());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void aReadRefusesCountsOutOfRange() {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            buffer.writeVarInt(1);
            buffer.writeEnum(AssemblyView.Status.CHECKED);
            buffer.writeLong(AssemblyReport.NONE);
            buffer.writeVarInt(AssemblyNetwork.MAX_SLOTS + 1);
            assertThrows(DecoderException.class, () -> AssemblyNetwork.StatePayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
