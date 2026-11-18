package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.pollution.GroundEffects;
import com.wormzjl.icheme.science.pollution.MapColumns;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * Polluted ground stops growth (batch 2026-11-18-ground-pollution, G4; config {@code pollution.groundGrowth}): where the
 * ground index of a chunk column is at least {@link GroundEffects#GROWTH_LIMIT}, a crop does not grow
 * ({@link CropGrowEvent.Pre}), a sapling or fungus does not become its tree ({@link BlockGrowFeatureEvent}) and bonemeal
 * does nothing ({@link BonemealEvent}). Reads only the published ground snapshot; no block is changed. Grass spread has no
 * event in NeoForge 21.1 and is not stopped. Never throws into the event.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID)
public final class GroundGrowth {
    private static boolean failureLogged;

    private GroundGrowth() {
    }

    @SubscribeEvent
    public static void crop(CropGrowEvent.Pre event) {
        if (blocked(event.getLevel(), event.getPos())) {
            event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
        }
    }

    @SubscribeEvent
    public static void tree(BlockGrowFeatureEvent event) {
        if (blocked(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void bonemeal(BonemealEvent event) {
        if (blocked(event.getLevel(), event.getPos())) {
            event.setSuccessful(false);
            event.setCanceled(true);
        }
    }

    /** Whether the ground under {@code pos} stops growth now; false off the server, with the rule off, or on any failure. */
    static boolean blocked(LevelAccessor level, BlockPos pos) {
        try {
            if (!(level instanceof ServerLevel server) || pos == null || !IChemE.pollutionGroundGrowth()) {
                return false;
            }
            var service = PollutionService.find(server.getServer()).orElse(null);
            if (service == null || !service.enabled()) {
                return false;
            }
            long column = MapColumns.column(pos.getX() >> 4, pos.getZ() >> 4);
            return GroundEffects.blocksGrowth(service.groundSnapshot(server.dimension()).value(column));
        } catch (RuntimeException failure) {
            if (!failureLogged) {
                failureLogged = true;
                IChemE.LOGGER.warn("pollution status=GROWTH_FAILED detail={}", String.valueOf(failure));
            }
            return false;
        }
    }
}
