package com.wormzjl.icheme.compat.create;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
/** Saved explicit couplings remain available, and now exchange power in either direction. */
public final class CreateCouplingBlockEntity extends CreateBridgeBlockEntity {
    public CreateCouplingBlockEntity(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    @Override protected boolean connected(){return true;}
    public float impact(){return calculateStressApplied();}
}
