package com.wormzjl.icheme.compat.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.wormzjl.icheme.world.level.block.ShaftBlock;
import net.minecraft.core.*;
import net.minecraft.world.level.block.state.BlockState;

public final class CreateShaftBlockEntity extends CreateBridgeBlockEntity {
    public CreateShaftBlockEntity(BlockPos pos,BlockState state){super(CreateCompat.SHAFT_ENTITY.get(),pos,state);}
    @Override protected boolean connected(){
        if(level==null)return false;
        for(var face:Direction.values())if(face.getAxis()==getBlockState().getValue(ShaftBlock.AXIS)){
            var at=worldPosition.relative(face);if(!level.hasChunkAt(at)||level.getBlockState(at).getBlock() instanceof ShaftBlock)continue;
            if(level.getBlockEntity(at) instanceof KineticBlockEntity)return true;
        }
        return false;
    }
}
