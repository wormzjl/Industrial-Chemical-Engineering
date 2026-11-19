package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.runtime.fluid.PlantTerrain;
import com.wormzjl.icheme.science.pollution.CubeKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/** The pollution field's air, ignition centre and surface-water samples from a single terrain snapshot. */
final class PollutionTerrain {
    private final PlantTerrain terrain;

    PollutionTerrain(ServerLevel level) { terrain = new PlantTerrain(level); }

    boolean live(int x, int z) { return terrain.live(x, z); }

    CellCache.Reading read(int x, int y, int z) {
        try {
            return read(terrain.chunk(x, z), y);
        } catch (RuntimeException unreadable) {
            return CellCache.Reading.UNKNOWN;
        }
    }

    private static CellCache.Reading read(PlantTerrain.Chunk terrain, int sectionY) {
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
            return water(terrain.chunk(x, z));
        } catch (RuntimeException unreadable) {
            return WaterCache.Surface.UNKNOWN;
        }
    }

    /** The ground uses a 4 × 4 grid of surface blocks and a threshold of nine water blocks. */
    private static WaterCache.Surface water(PlantTerrain.Chunk terrain) {
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
