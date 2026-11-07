package com.wormzjl.icheme.mixin;

import com.wormzjl.icheme.world.level.block.EquipmentProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Also covers direct world/chunk replacements used by commands and mechanical block breakers. */
@Mixin(LevelChunk.class)
public abstract class EquipmentChunkMixin {
    @Shadow @Final private Level level;
    @Shadow public abstract BlockState getBlockState(BlockPos position);

    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void ichemeProtectEquipment(BlockPos position, BlockState replacement, boolean moving,
                                       CallbackInfoReturnable<BlockState> result) {
        if (!EquipmentProtection.mayReplace(level, position, getBlockState(position), replacement)) result.setReturnValue(null);
    }
}
