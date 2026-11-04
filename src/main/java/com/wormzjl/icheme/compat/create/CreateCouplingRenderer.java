package com.wormzjl.icheme.compat.create;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/** Create's half shaft on the coupling's facing side, turning at the coupling's Create speed (as Create's creative motor renderer). */
final class CreateCouplingRenderer extends KineticBlockEntityRenderer<CreateCouplingBlockEntity> {
    CreateCouplingRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected SuperByteBuffer getRotatedModel(CreateCouplingBlockEntity entity, BlockState state) {
        return CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state);
    }
}
