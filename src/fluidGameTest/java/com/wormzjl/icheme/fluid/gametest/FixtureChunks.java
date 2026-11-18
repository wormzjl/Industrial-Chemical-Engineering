package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Comparator;

final class FixtureChunks {
    private static final TicketType<ChunkPos> ONE_CHUNK =
            TicketType.create("icheme_exchanger_test", Comparator.comparingLong(ChunkPos::toLong));

    private FixtureChunks() {}

    /** A level-33 ticket requests one FULL chunk. A force-loaded chunk keeps a FULL halo. */
    static void ticket(ServerLevel level, ChunkPos chunk, boolean add) {
        if (add) level.getChunkSource().addRegionTicket(ONE_CHUNK, chunk, 0, chunk);
        else level.getChunkSource().removeRegionTicket(ONE_CHUNK, chunk, 0, chunk);
    }

    static boolean full(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    static BlockPos at(BlockPos origin, ShapeRasterizer.Cell cell) {
        return origin.offset(cell.x(), cell.y(), cell.z());
    }

    static BlockPos remote(GameTestHelper helper) {
        var nearby = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        var remote = new ChunkPos(nearby.x + 64, nearby.z);
        return new BlockPos(remote.getMinBlockX() + 8, 81, remote.getMinBlockZ() + 8);
    }
}
