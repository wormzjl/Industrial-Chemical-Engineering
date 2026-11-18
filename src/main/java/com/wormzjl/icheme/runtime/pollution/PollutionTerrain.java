package com.wormzjl.icheme.runtime.pollution;

import com.mojang.serialization.Codec;
import com.wormzjl.icheme.runtime.fluid.SavedChunkData;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
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
import java.util.Optional;

/** Terrain for one pollution snapshot, from live sections first and otherwise from complete saved chunks. */
final class PollutionTerrain {
    private static final Codec<PalettedContainer<BlockState>> STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final Heightmap.Types SURFACE = Heightmap.Types.WORLD_SURFACE;

    private interface Terrain {
        PalettedContainer<BlockState> states(int sectionY);

        int surface(int x, int z);
    }

    private record Live(LevelChunk chunk) implements Terrain {
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

    private static final class Saved implements Terrain {
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

    PollutionTerrain(ServerLevel level) {
        this.level = level;
    }

    boolean live(int x, int z) {
        return loaded(x, z).isPresent();
    }

    private Optional<Live> loaded(int x, int z) {
        return live.computeIfAbsent(ChunkPos.asLong(x, z), ignored ->
                Optional.ofNullable(level.getChunkSource().getChunkNow(x, z)).map(Live::new));
    }

    private Terrain terrain(int x, int z) {
        var chunk = loaded(x, z);
        if (chunk.isPresent()) return chunk.get();
        return stored.computeIfAbsent(ChunkPos.asLong(x, z), ignored -> {
            try {
                var tag = SavedChunkData.read(level, x, z);
                if (tag != null && tag.contains(ChunkSerializer.X_POS_TAG, Tag.TAG_INT)
                        && tag.contains(ChunkSerializer.Z_POS_TAG, Tag.TAG_INT)
                        && tag.getInt(ChunkSerializer.X_POS_TAG) == x && tag.getInt(ChunkSerializer.Z_POS_TAG) == z
                        && ChunkSerializer.getChunkTypeFromTag(tag) == ChunkType.LEVELCHUNK) {
                    return Optional.of(new Saved(tag, level.getMinBuildHeight(), level.getHeight()));
                }
            } catch (RuntimeException unreadable) {
                // The pass treats unreadable terrain as unavailable.
            }
            return Optional.empty();
        }).orElse(null);
    }

    CellCache.Reading read(int x, int y, int z) {
        try {
            return read(terrain(x, z), y);
        } catch (RuntimeException unreadable) {
            return CellCache.Reading.UNKNOWN;
        }
    }

    private static CellCache.Reading read(Terrain terrain, int sectionY) {
        if (terrain == null) return CellCache.Reading.UNKNOWN;
        var states = terrain.states(sectionY);
        int air = CubeKey.SECTION_BLOCKS;
        if (states != null) {
            int[] count = {0};
            states.count((state, n) -> {
                if (passes(state)) count[0] += n;
            });
            air = count[0];
        }
        boolean open = (sectionY << 4) + 15 >= terrain.surface(8, 8);
        return new CellCache.Reading(CellCache.cell(air, open), blastCentre(states));
    }

    private static int blastCentre(PalettedContainer<BlockState> states) {
        if (states != null) {
            try {
                for (int y = 8; y < 16; y++) {
                    var state = states.get(8, y, 8);
                    if (state.isAir() || state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) return y;
                }
            } catch (RuntimeException unreadable) {
                // Fall back to the geometric centre if a block cannot answer its collision shape.
            }
        }
        return 8;
    }

    WaterCache.Surface water(int x, int z) {
        try {
            return water(terrain(x, z));
        } catch (RuntimeException unreadable) {
            return WaterCache.Surface.UNKNOWN;
        }
    }

    /** The ground uses a 4 × 4 grid of surface blocks and a threshold of nine water blocks. */
    private static WaterCache.Surface water(Terrain terrain) {
        if (terrain == null) return WaterCache.Surface.UNKNOWN;
        int wet = 0;
        for (int x = 2; x < 16; x += 4) {
            for (int z = 2; z < 16; z += 4) {
                int top = terrain.surface(x, z);
                var states = terrain.states(top >> 4);
                if (states != null && states.get(x, top & 15, z).getFluidState().is(FluidTags.WATER)) wet++;
            }
        }
        return wet >= 9 ? WaterCache.Surface.WATER : WaterCache.Surface.LAND;
    }

    /** Air for the field: air, or a block with no collision shape and no fluid. */
    private static boolean passes(BlockState state) {
        try {
            return state.isAir() || state.getFluidState().isEmpty()
                    && state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
