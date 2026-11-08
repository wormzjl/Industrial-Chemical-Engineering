package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.science.equipment.SightGlassGeometry;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Installs a pipe fitting without replacing the host pipe or its identity: an axis-locked shutoff, check or pressure
 * valve, Y-strainer (its original filter physics unchanged) or inline pump or compressor, or the static visual sight
 * glass, which edits no fluid registration.
 */
public final class PipelineFittingItem extends Item {
    private final Instrument.Type type;
    private final PhysicalFluidTopology.Cutoff installed;
    private final String translation;
    public PipelineFittingItem(Properties properties,Instrument.Type type){
        super(properties.stacksTo(64));
        this.type=java.util.Objects.requireNonNull(type);
        installed=switch(type){case SHUTOFF->PhysicalFluidTopology.Cutoff.OPEN;case CHECK->PhysicalFluidTopology.Cutoff.CHECK;case FILTER,PRESSURE_VALVE,PUMP,COMPRESSOR,SIGHT_GLASS->PhysicalFluidTopology.Cutoff.NONE;case PRESSURE,TEMPERATURE,LEVEL_GAUGE->throw new IllegalArgumentException("A gauge is not a pipe fitting");};
        translation=switch(type){case PUMP->"item.icheme.fluid_pump.";case COMPRESSOR->"item.icheme.fluid_compressor.";case FILTER->"item.icheme.inline_filter.";case PRESSURE_VALVE->"item.icheme.pressure_control_valve.";case SIGHT_GLASS->"item.icheme.pipeline_sight_glass.";case CHECK->"item.icheme.pipeline_check_valve.";default->"item.icheme.pipeline_shutoff_valve.";};
    }

    private boolean mover(){return type==Instrument.Type.PUMP||type==Instrument.Type.COMPRESSOR;}

    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context) {
        var player=context.getPlayer();var level=context.getLevel();var pos=context.getClickedPos();var state=level.getBlockState(pos);
        if(!(state.getBlock() instanceof FluidDeviceBlock block)||block.kind()!=TopologyCompiler.Kind.PIPE)return InteractionResult.PASS;
        if(player==null||!player.mayBuild()||player.isSpectator()||!level.mayInteract(player,pos)||!player.mayUseItemAt(pos,context.getClickedFace(),stack))return InteractionResult.FAIL;
        if(type==Instrument.Type.SIGHT_GLASS)return sightGlass(stack,player,level,pos,state);
        boolean duplicate=switch(type){case PUMP->FluidDeviceBlock.hasPump(state);case COMPRESSOR->FluidDeviceBlock.hasCompressor(state);case FILTER->FluidDeviceBlock.hasFilter(state);case PRESSURE_VALVE->FluidDeviceBlock.hasPressureValve(state);case SHUTOFF->FluidDeviceBlock.hasShutoff(state);default->FluidDeviceBlock.cutoff(state)==installed;};
        if(duplicate)return InteractionResult.sidedSuccess(level.isClientSide());
        if(state.getValue(FluidDeviceBlock.SIGHT_GLASS)||FluidDeviceBlock.hasAxialAccessory(state)) {
            if(!level.isClientSide())player.displayClientMessage(Component.translatable(translation+"one_accessory"),true);
            return InteractionResult.FAIL;
        }
        int mask=FluidDeviceBlock.connectionMask(state);
        if(!SightGlassGeometry.supports(mask)||transition(level,pos)||mover()&&!com.wormzjl.icheme.science.equipment.InlineMoverGeometry.supports(mask,state.getValue(FluidDeviceBlock.FACING).ordinal())) {
            if(!level.isClientSide())player.displayClientMessage(Component.translatable(translation+"straight_only"),true);
            return InteractionResult.FAIL;
        }
        if(level instanceof ServerLevel server) {
            if(!(level.getBlockEntity(pos) instanceof FluidDeviceBlockEntity entity))return InteractionResult.FAIL;
            var world=FluidWorldAuthority.find(server.getServer()).orElse(null);
            if(world==null||entity.fluidIdentity()==0)return InteractionResult.FAIL;
            if(world.instruments().occupied(entity.fluidIdentity()))return InteractionResult.FAIL;
            var record=world.registrations().get(entity.fluidIdentity());
            var at=new PhysicalFluidTopology.Position(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());
            if(record==null||record.device().kind()!=TopologyCompiler.Kind.PIPE||!record.device().position().equals(at))return InteractionResult.FAIL;
            if(record.device().cutoff()!=PhysicalFluidTopology.Cutoff.NONE||!(record.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Passive))return InteractionResult.SUCCESS;
            var facing=mask==0?state.getValue(FluidDeviceBlock.FACING):Direction.values()[Integer.numberOfTrailingZeros(mask)];
            if(mover()||type==Instrument.Type.CHECK||type==Instrument.Type.PRESSURE_VALVE) {
                var look=player.getLookAngle();double along=look.x*facing.getStepX()+look.y*facing.getStepY()+look.z*facing.getStepZ();
                if(along<0)facing=facing.getOpposite();
            }
            var changed=state.setValue(FluidDeviceBlock.FACING,facing);
            changed=mover()?changed.setValue(FluidDeviceBlock.INLINE_MOVER,type==Instrument.Type.PUMP?FluidDeviceBlock.InlineMover.PUMP:FluidDeviceBlock.InlineMover.COMPRESSOR):type==Instrument.Type.FILTER?changed.setValue(FluidDeviceBlock.INLINE_FILTER,true):type==Instrument.Type.PRESSURE_VALVE?changed.setValue(FluidDeviceBlock.PRESSURE_CONTROL,true):changed.setValue(FluidDeviceBlock.SHUTOFF,FluidDeviceBlock.Shutoff.of(installed));
            changed=FluidDeviceBlock.connectedState(changed,level,pos);
            if(!level.setBlock(pos,changed,Block.UPDATE_ALL))return InteractionResult.FAIL;
            var axis=PhysicalFluidTopology.Direction.valueOf(facing.name());
            if(!(mover()?world.installPipeMover(entity.fluidIdentity(),axis,type==Instrument.Type.PUMP?TopologyCompiler.Kind.PUMP:TopologyCompiler.Kind.COMPRESSOR):type==Instrument.Type.FILTER?world.installPipeFilter(entity.fluidIdentity(),axis):type==Instrument.Type.PRESSURE_VALVE?world.installPipePressureValve(entity.fluidIdentity(),axis):world.installPipeValve(entity.fluidIdentity(),axis,installed))) {
                level.setBlock(pos,state,Block.UPDATE_ALL);return InteractionResult.FAIL;
            }
            world.instruments().legacyInstalled(entity.fluidIdentity(),type);
            entity.refreshGeometry(entity.bore(),true);
            if(!player.getAbilities().instabuild)stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** A transition pipe beside a rack lane (plan 6): its run bends off the centre line, so it takes no fitting. */
    private static boolean transition(net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos){return level.getBlockEntity(pos) instanceof FluidDeviceBlockEntity entity&&entity.transition();}

    /** The sight glass is static and visual: its window faces the player, and no fluid registration changes. */
    private static InteractionResult sightGlass(ItemStack stack,net.minecraft.world.entity.player.Player player,net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos,BlockState state) {
        if(FluidDeviceBlock.hasAxialAccessory(state)) {
            if(!level.isClientSide())player.displayClientMessage(Component.translatable("item.icheme.pipeline_shutoff_valve.one_accessory"),true);
            return InteractionResult.FAIL;
        }
        if(!FluidDeviceBlock.supportsSightGlass(state)||transition(level,pos)) {
            if(!level.isClientSide())player.displayClientMessage(Component.translatable("item.icheme.pipeline_sight_glass.straight_only"),true);
            return InteractionResult.FAIL;
        }
        if(FluidDeviceBlock.hasSightGlass(state))return InteractionResult.sidedSuccess(level.isClientSide());
        if(!level.isClientSide()) {
            if(!(level.getBlockEntity(pos) instanceof FluidDeviceBlockEntity entity))return InteractionResult.FAIL;
            var world=FluidWorldAuthority.find(((ServerLevel)level).getServer()).orElse(null);
            if(world==null||world.instruments().occupied(entity.fluidIdentity()))return InteractionResult.FAIL;
            var eye=player.getEyePosition().subtract(pos.getCenter());
            var window=SightGlassGeometry.towards(FluidDeviceBlock.connectionMask(state),state.getValue(FluidDeviceBlock.FACING).ordinal(),eye.x,eye.y,eye.z);
            var installed=state.setValue(FluidDeviceBlock.SIGHT_GLASS,true).setValue(FluidDeviceBlock.SIGHT_GLASS_AXIS,Direction.Axis.valueOf(window.name()));
            if(!level.setBlock(pos,installed,Block.UPDATE_ALL))return InteractionResult.FAIL;
            world.instruments().legacyInstalled(entity.fluidIdentity(),Instrument.Type.SIGHT_GLASS);
            entity.refreshGeometry(entity.bore(),true);
            if(!player.getAbilities().instabuild)stack.shrink(1);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> tooltip,TooltipFlag flag) {
        for(String key:type==Instrument.Type.SIGHT_GLASS?List.of("install","straight_only","recover"):List.of("install","toggle","straight_only","one_accessory","recover"))
            tooltip.add(Component.translatable(translation+key).withStyle(ChatFormatting.GRAY));
    }
}
