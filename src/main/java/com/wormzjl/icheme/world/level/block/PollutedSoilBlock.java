package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.pollution.GroundSoil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Polluted soil (batch 2026-11-18-ground-pollution, G4b): what polluted ground makes of grass, dirt and farmland. No plant
 * stands or grows on it, bonemeal does nothing and grass does not spread onto it (it is not dirt). On its random tick it
 * turns back to dirt where its chunk column's worst ground index is below 0.1 ({@link GroundSoil#recovers}); grass then
 * spreads onto that dirt by vanilla's own rule.
 */
public final class PollutedSoilBlock extends Block {
    public PollutedSoilBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (GroundSoil.recovers(level, pos)) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public TriState canSustainPlant(BlockState state, BlockGetter level, BlockPos soilPosition, Direction facing, BlockState plant) {
        return TriState.FALSE;
    }
}
