package com.wormzjl.icheme.runtime.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;

import java.util.HashMap;
import java.util.Map;

/** The water-body boundary condition. Its deadline advances on engine time even when the strainer is unloaded. */
public final class WaterIntakes {
    public static final int RECHECK_TICKS = 1200;

    private WaterIntakes() {}

    public static boolean supplying(ServerLevel level, BlockPos pos) {
        return supplying(new PlantTerrain(level), pos.getX(), pos.getY(), pos.getZ(), false);
    }

    private static boolean supplying(PlantTerrain terrain, int x, int y, int z, boolean previous) {
        boolean[] incomplete={false};
        boolean found=WaterBody.count(x, y, z, (px, py, pz) -> {
            var state = terrain.stateAt(px, py, pz);
            if (state == null && terrain.unreadable(px >> 4,pz >> 4)) incomplete[0]=true;
            return state != null && state.getFluidState().is(FluidTags.WATER);
        }).body();
        return found || incomplete[0] && previous;
    }

    public static void changed(FluidWorldAuthority world,ServerLevel level,BlockPos pos) {
        var record=world.at(com.wormzjl.icheme.world.level.block.Positions.of(level,pos)).orElse(null);
        if (record!=null && FluidWorldAuthority.waterIntake(record))
            world.waterBody(record.device().id(),supplying(new PlantTerrain(level),pos.getX(),pos.getY(),pos.getZ(),record.device().generatorEnabled()));
    }

    static void recheck(FluidWorldAuthority world) {
        Map<ServerLevel, PlantTerrain> terrains = new HashMap<>();
        var intakes = world.registrations().values().stream().filter(FluidWorldAuthority::waterIntake).toList();
        for (var intake : intakes) {
            var pos = intake.device().position();
            var level = world.serverLevel(pos.dimension());
            if (level == null) continue;
            var terrain = terrains.computeIfAbsent(level, PlantTerrain::new);
            world.waterBody(intake.device().id(), supplying(terrain, pos.x(), pos.y(), pos.z(), intake.device().generatorEnabled()));
        }
    }
}
