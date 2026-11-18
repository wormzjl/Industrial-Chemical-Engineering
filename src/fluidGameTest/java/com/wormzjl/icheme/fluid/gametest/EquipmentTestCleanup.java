package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.testworld.PlantBuilder;
import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.world.level.block.EquipmentProtection;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/**
 * Fixtures remove constructed equipment through the real creative-player path, never by bypassing
 * protection.
 */
final class EquipmentTestCleanup {
    private EquipmentTestCleanup() {}

    static void remove(ServerLevel level, BlockPos position) {
        com.wormzjl.icheme.testworld.PlantBuilder.removeStructure(level, position);
    }

    static void replace(ServerLevel level, BlockPos position, BlockState state) {
        remove(level, position);
        level.setBlock(position, state, 3);
    }
}
