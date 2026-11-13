package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** The fluid topology's position of a block: its level's dimension and its coordinates, on no lane. */
public final class Positions {
    private Positions() {
    }

    /** The position of the block at {@code at} in {@code level}. */
    public static PhysicalFluidTopology.Position of(Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }
}
