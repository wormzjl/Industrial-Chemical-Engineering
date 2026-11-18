package com.wormzjl.icheme.runtime.fluid;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;

/** One server-thread terrain snapshot. Reads generated block palettes through the chunk I/O queue without tickets. */
public final class PlantTerrain implements BlockGetter {
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final Heightmap.Types SURFACE = Heightmap.Types.WORLD_SURFACE;

    public interface Chunk {
        PalettedContainer<BlockState> states(int sectionY);

        int surface(int x, int z);
    }

    private record Live(LevelChunk chunk) implements Chunk {
        @Override
        public PalettedContainer<BlockState> states(int sectionY) {
            int section = chunk.getSectionIndexFromSectionY(sectionY);
            return section >= 0 && section < chunk.getSectionsCount() ? chunk.getSection(section).getStates() : null;
        }

        @Override
        public int surface(int x, int z) {
            return chunk.getHeight(SURFACE, x, z);
        }
    }

    private static final class Saved implements Chunk {
        private final int minY, height;
        private final Map<Integer, CompoundTag> sections = new HashMap<>();
        private final Map<Integer, PalettedContainer<BlockState>> decoded = new HashMap<>();
        private final SimpleBitStorage surface;

        Saved(CompoundTag chunk, int minY, int height) {
            this.minY = minY;
            this.height = height;
            if (!(chunk.get(ChunkSerializer.SECTIONS_TAG) instanceof ListTag list)
                    || !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
                throw new IllegalArgumentException("saved chunk has no section list");
            }
            for (Tag entry : list) {
                var section = (CompoundTag) entry;
                if (!section.contains("Y", Tag.TAG_BYTE) || sections.put((int) section.getByte("Y"), section) != null) {
                    throw new IllegalArgumentException("invalid saved chunk section");
                }
            }
            var heights = chunk.getCompound(ChunkSerializer.HEIGHTMAPS_TAG).getLongArray(SURFACE.getSerializationKey());
            int bits = Mth.ceillog2(height + 1);
            int valuesPerLong = Long.SIZE / bits;
            surface = heights.length == Mth.positiveCeilDiv(256, valuesPerLong)
                    ? new SimpleBitStorage(bits, 256, heights) : null;
        }

        /** Null is an empty section, as in vanilla's reader for a full chunk. An unreadable palette throws. */
        @Override
        public PalettedContainer<BlockState> states(int sectionY) {
            if (sectionY < (minY >> 4) || sectionY > ((minY + height - 1) >> 4)) return null;
            var section = sections.get(sectionY);
            if (section == null || !section.contains("block_states")) return null;
            return decoded.computeIfAbsent(sectionY,
                    y -> STATES.parse(NbtOps.INSTANCE, section.getCompound("block_states")).getOrThrow());
        }

        @Override
        public int surface(int x, int z) {
            if (surface != null) {
                int firstAir = surface.get(x + 16 * z);
                if (firstAir <= height) return minY + firstAir - 1;
            }
            // A missing heightmap is rebuilt from the same predicate vanilla primes it with.
            for (int y = minY + height - 1; y >= minY; ) {
                var states = states(y >> 4);
                if (states != null && states.maybeHas(SURFACE.isOpaque())) {
                    int bottom = Math.max(minY, (y >> 4) << 4);
                    for (int scan = y; scan >= bottom; scan--) {
                        if (SURFACE.isOpaque().test(states.get(x, scan & 15, z))) return scan;
                    }
                }
                y = ((y >> 4) << 4) - 1;
            }
            return minY - 1;
        }
    }

    private final ServerLevel level;
    private final Map<Long, Optional<Live>> live = new HashMap<>();
    private final Map<Long, Optional<Saved>> stored = new HashMap<>();
    private final Set<Long> unreadable = new HashSet<>();

    public PlantTerrain(ServerLevel level) {
        this.level = level;
    }

    public boolean live(int x, int z) {
        return loaded(x, z).isPresent();
    }

    private Optional<Live> loaded(int x, int z) {
        return live.computeIfAbsent(ChunkPos.asLong(x, z), ignored ->
                Optional.ofNullable(level.getChunkSource().getChunkNow(x, z)).map(Live::new));
    }

    public Chunk chunk(int x, int z) {
        var chunk = loaded(x, z);
        if (chunk.isPresent()) return chunk.get();
        return stored.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> {
            try {
                var tag = SavedChunkData.read(level, x, z);
                if (tag == null || ChunkSerializer.getChunkTypeFromTag(tag) != ChunkType.LEVELCHUNK)
                    return Optional.empty();
                if (!tag.contains(ChunkSerializer.X_POS_TAG, Tag.TAG_INT) || !tag.contains(ChunkSerializer.Z_POS_TAG, Tag.TAG_INT)
                        || tag.getInt(ChunkSerializer.X_POS_TAG) != x || tag.getInt(ChunkSerializer.Z_POS_TAG) != z)
                    throw new IllegalArgumentException("Saved chunk coordinate mismatch");
                return Optional.of(new Saved(tag, level.getMinBuildHeight(), level.getHeight()));
            } catch (RuntimeException failure) {
                unreadable.add(ChunkPos.asLong(x,z));
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** An unavailable column has no readable state. An absent section in a complete column is air. */
    public @Nullable BlockState stateAt(int x, int y, int z) {
        if (level.isOutsideBuildHeight(y)) return Blocks.VOID_AIR.defaultBlockState();
        var column = chunk(x >> 4, z >> 4);
        if (column == null) return null;
        try {
            var states = column.states(y >> 4);
            return states == null ? Blocks.AIR.defaultBlockState() : states.get(x & 15, y & 15, z & 15);
        } catch (RuntimeException failure) {
            unreadable.add(ChunkPos.asLong(x >> 4,z >> 4));
            return null;
        }
    }

    /** Distinguish an I/O or palette failure from a location that has no generated terrain. */
    public boolean unreadable(int chunkX,int chunkZ) {
        return unreadable.contains(ChunkPos.asLong(chunkX,chunkZ));
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        var state = stateAt(pos.getX(), pos.getY(), pos.getZ());
        return state == null ? Blocks.VOID_AIR.defaultBlockState() : state;
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        var live = loaded(pos.getX() >> 4, pos.getZ() >> 4);
        return live.isPresent() ? live.get().chunk().getBlockEntity(pos) : null;
    }
    @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
    @Override public int getHeight() { return level.getHeight(); }
}
