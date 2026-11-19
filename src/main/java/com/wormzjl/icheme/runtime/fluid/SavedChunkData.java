package com.wormzjl.icheme.runtime.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read existing region data through Minecraft's own I/O queue, without generating or ticketing a chunk. */
public final class SavedChunkData {
    private record Address(String dimension, int x, int z) {}

    private final Map<Address, Map<BlockPos, CompoundTag>> entities = new LinkedHashMap<>();
    private long tick = Long.MIN_VALUE;

    /** Raw saved chunk, null if it was never written. Storage errors propagate to the caller. */
    public static @Nullable CompoundTag read(ServerLevel level, int x, int z) {
        return level.getChunkSource().chunkMap.read(new ChunkPos(x, z)).join().orElse(null);
    }

    @Nullable CompoundTag blockEntity(ServerLevel level, BlockPos pos) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        var loaded = level.getChunkSource().getChunkNow(x, z);
        if (loaded != null) return loaded.getBlockEntityNbtForSaving(pos, level.registryAccess());
        long now = level.getServer().getTickCount();
        if (tick != now) {
            entities.clear();
            tick = now;
        }
        var address = new Address(level.dimension().location().toString(), x, z);
        var contents = entities.get(address);
        if (contents == null) {
            var chunk = read(level, x, z);
            contents = chunk == null ? Map.of() : blockEntities(chunk);
            if (entities.size() >= 32) entities.remove(entities.keySet().iterator().next());
            entities.put(address, contents);
        }
        var entity = contents.get(pos);
        return entity == null ? null : entity.copy();
    }
    static Map<BlockPos,CompoundTag> blockEntities(CompoundTag chunk) {
        var contents = new HashMap<BlockPos,CompoundTag>();
        for (Tag entry : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
            var entity = (CompoundTag) entry;
            contents.put(new BlockPos(entity.getInt("x"), entity.getInt("y"), entity.getInt("z")), entity);
        }
        return contents;
    }

}
