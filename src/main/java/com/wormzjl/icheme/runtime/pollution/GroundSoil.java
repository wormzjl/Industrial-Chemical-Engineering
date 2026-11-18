package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.science.pollution.GroundSpoil;
import com.wormzjl.icheme.science.pollution.MapColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Polluted ground changes blocks (batch 2026-11-18-ground-pollution, G4b; config {@code pollution.groundBlocks}), on the
 * world pass ({@link PollutionEffects#EFFECT_TICKS}) and the random tick of polluted soil, from the published ground
 * snapshot only. Each column at or above {@link GroundSpoil#SPOIL_LIMIT} gets {@link GroundSpoil#attempts} random picks; a
 * pick starts at the motion-blocking height and walks down at most {@link #WALK} blocks: a block of
 * {@link #KILLS} is removed with no drops (water where it stood in water), the first block of {@link #SPOILS} becomes
 * polluted soil, and any other block that blocks motion or holds a block entity ends the walk untouched. Every change is a
 * plain {@code setBlock}, so protection mods veto it as any other. Nothing is taken from the ground.
 */
public final class GroundSoil {
    /** Becomes polluted soil. */
    public static final TagKey<Block> SPOILS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "pollution_spoils"));
    /** Removed, no drops. */
    public static final TagKey<Block> KILLS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "pollution_kills"));
    /** Blocks a pick walks down below the motion-blocking height (four positions in all). */
    public static final int WALK = 3;

    private GroundSoil() {
    }

    /** The world pass: picks in every loaded column at or above the limit, at most {@code budget} columns. */
    static void spoil(ServerLevel level, PollutionService service, int budget) {
        if (!IChemE.pollutionGroundBlocks()) {
            return;
        }
        var snapshot = service.groundSnapshot(level.dimension());
        int served = 0;
        for (int i = 0; i < snapshot.size() && served < budget; i++) {
            double index = GroundSpoil.index(snapshot.value(i));
            if (index < GroundSpoil.SPOIL_LIMIT) {
                continue;
            }
            long column = snapshot.column(i);
            int x = MapColumns.x(column), z = MapColumns.z(column);
            if (level.getChunkSource().getChunkNow(x, z) == null) {
                continue;
            }
            served++;
            var random = level.getRandom();
            int attempts = GroundSpoil.attempts(index, random.nextDouble());
            for (int a = 0; a < attempts; a++) {
                pick(level, (x << 4) + random.nextInt(16), (z << 4) + random.nextInt(16));
            }
        }
    }

    /** One pick at block column ({@code bx}, {@code bz}); returns how many blocks changed. */
    public static int pick(ServerLevel level, int bx, int bz) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        int changed = 0;
        for (int y = top; y >= top - WALK && y >= level.getMinBuildHeight(); y--) {
            var pos = new BlockPos(bx, y, bz);
            BlockState state = level.getBlockState(pos);
            if (state.hasBlockEntity()) {
                break;
            }
            if (state.is(KILLS)) {
                boolean wet = state.getFluidState().is(FluidTags.WATER);
                if (level.setBlock(pos, wet ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) changed++;
                continue;
            }
            if (state.is(SPOILS)) {
                if (level.setBlock(pos, ModBlocks.POLLUTED_SOIL.get().defaultBlockState(), Block.UPDATE_ALL)) changed++;
                break;
            }
            if (state.blocksMotion()) {
                break;
            }
        }
        return changed;
    }

    /** The ground byte of the column holding {@code pos}; 0 (clean) with pollution off or on any failure. */
    static int value(ServerLevel level, BlockPos pos) {
        try {
            var service = PollutionService.find(level.getServer()).orElse(null);
            if (service == null || !service.enabled()) return 0;
            return service.groundSnapshot(level.dimension()).value(MapColumns.column(pos.getX() >> 4, pos.getZ() >> 4));
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    /** Whether polluted soil at {@code pos} turns back to dirt now: its column's index is below the recovery limit. */
    public static boolean recovers(ServerLevel level, BlockPos pos) {
        return GroundSpoil.recovers(value(level, pos));
    }
}
