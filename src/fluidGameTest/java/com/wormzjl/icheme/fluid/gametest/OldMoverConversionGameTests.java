package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.List;

/**
 * A world saved with the one-block pump or compressor (batch 2026-10-28-old-mover-removal): the block id loads as a pipe,
 * and the chunk's load turns the registration into the in-line mover on it, with its identity, facing and control.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class OldMoverConversionGameTests {
    private OldMoverConversionGameTests() {}

    @GameTest(template="empty",timeoutTicks=100,batch="old-mover-alias")
    public static void theOldBlockIdsLoadAsPipesWithTheirFacingAndJoints(GameTestHelper helper) {
        for(var name:List.of("fluid_pump","fluid_compressor")) {
            var id=ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID,name);
            helper.assertTrue(BuiltInRegistries.BLOCK.get(id)==ModBlocks.FLUID_PIPE.get(),name+" does not resolve to the pipe");
            // A chunk section's palette entry, as a world saved before the removal holds it.
            var properties=new CompoundTag();properties.putString("facing","east");properties.putString("east","true");properties.putString("west","true");
            var entry=new CompoundTag();entry.putString("Name",id.toString());entry.put("Properties",properties);
            var state=BlockState.CODEC.parse(NbtOps.INSTANCE,entry).getOrThrow();
            helper.assertTrue(state.getBlock()==ModBlocks.FLUID_PIPE.get(),name+" palette entry reads as "+state);
            helper.assertTrue(state.getValue(FluidDeviceBlock.FACING)==Direction.EAST,name+" lost its facing: "+state);
            helper.assertTrue(state.getValue(FluidDeviceBlock.connection(Direction.EAST))&&state.getValue(FluidDeviceBlock.connection(Direction.WEST)),name+" lost its joints: "+state);
        }
        helper.succeed();
    }

    @GameTest(template="empty",timeoutTicks=3000,batch="old-mover-conversion")
    public static void aLoadedOneBlockMoverBecomesTheInlineMoverWithItsSettings(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        // A chunk of its own, away from every test structure, so it can unload and load again.
        var origin=helper.absolutePos(BlockPos.ZERO);var base=new BlockPos(((origin.getX()>>4)+112)*16+4,80,(origin.getZ()>>4)*16+4);
        var chunk=new ChunkPos(base);level.setChunkForced(chunk.x,chunk.z,true);
        // A pump between a 0.05 m and a 0.1 m pipe, a compressor between two 0.05 m pipes, and a pump standing upright alone.
        var pump=base.east();var compressor=base.south(2).east();var upright=base.east(6);
        var cells=List.of(base,pump,pump.east(),compressor.west(),compressor,compressor.east(),upright);
        for(var cell:cells)level.setBlock(cell,ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.EAST),3);
        long wide=identity(level,pump.east());
        var widened=world.registrations().get(wide);var w=widened.device().geometry();
        world.edit(wide,widened.revision(),widened.device().withGeometry(new PipeResistance.Geometry(w.length(),.1,w.roughness(),w.minorLoss())),widened.spec());
        // Each as the one-block block registered it: a portless mover of 0.05 m with the player's settings.
        var pumpControl=new FlowControl.Pump(.0002,300000);var compressorControl=new FlowControl.Compressor(.003,2.5);var uprightControl=new FlowControl.Pump(.0003,200000);
        long pumpId=oneBlock(world,identity(level,pump),TopologyCompiler.Kind.PUMP,PhysicalFluidTopology.Direction.EAST,pumpControl);
        long compressorId=oneBlock(world,identity(level,compressor),TopologyCompiler.Kind.COMPRESSOR,PhysicalFluidTopology.Direction.EAST,compressorControl);
        long uprightId=oneBlock(world,identity(level,upright),TopologyCompiler.Kind.PUMP,PhysicalFluidTopology.Direction.UP,uprightControl);
        var pumpSpec=world.registrations().get(pumpId).spec();
        level.setChunkForced(chunk.x,chunk.z,false);
        helper.startSequence()
            .thenWaitUntil(()->helper.assertTrue(!level.hasChunkAt(base),"Waiting for the chunk to unload"))
            .thenExecute(()->level.setChunkForced(chunk.x,chunk.z,true))
            .thenWaitUntil(()->{
                helper.assertTrue(level.hasChunkAt(base),"Waiting for the chunk to load");
                helper.assertTrue(world.capture().world().events().isEmpty(),"Waiting for the conversion to apply");
                helper.assertTrue(level.getBlockState(pump).getValue(FluidDeviceBlock.INLINE_MOVER)==FluidDeviceBlock.InlineMover.PUMP,"Waiting for the pump to be drawn: "+level.getBlockState(pump));
            })
            .thenExecute(()->{
                try {
                    var p=world.registrations().get(pumpId);var d=p.device();
                    helper.assertTrue(d.kind()==TopologyCompiler.Kind.PIPE&&d.hydraulicKind()==TopologyCompiler.Kind.PUMP,"The pump is no in-line mover: "+d);
                    helper.assertTrue(d.facing()==PhysicalFluidTopology.Direction.EAST&&d.control().equals(pumpControl)&&p.spec().equals(pumpSpec),"The pump lost a setting: "+d);
                    helper.assertTrue(d.geometry().diameter()==.1,"The pump does not take the line's largest bore: "+d.geometry());
                    helper.assertTrue(identity(level,pump)==pumpId,"The pump's block lost its identity");
                    var c=world.registrations().get(compressorId).device();
                    helper.assertTrue(c.kind()==TopologyCompiler.Kind.PIPE&&c.control().equals(compressorControl)&&c.facing()==PhysicalFluidTopology.Direction.EAST&&c.geometry().diameter()==.05,"The compressor did not convert whole: "+c);
                    helper.assertTrue(level.getBlockState(compressor).getValue(FluidDeviceBlock.INLINE_MOVER)==FluidDeviceBlock.InlineMover.COMPRESSOR,"The compressor is not drawn: "+level.getBlockState(compressor));
                    // Option V1: an in-line mover is horizontal only, so an upright one keeps its one-block registration.
                    var u=world.registrations().get(uprightId).device();
                    helper.assertTrue(u.kind()==TopologyCompiler.Kind.PUMP&&u.control().equals(uprightControl),"The upright pump changed: "+u);
                } finally {for(var cell:cells)level.removeBlock(cell,false);level.setChunkForced(chunk.x,chunk.z,false);}
            })
            .thenWaitUntil(()->helper.assertTrue(world.registrations().values().stream().noneMatch(r->r.device().position().x()>>4==chunk.x&&r.device().position().z()>>4==chunk.z),"Waiting for cleanup"))
            .thenSucceed();
    }

    private static long identity(net.minecraft.server.level.ServerLevel level,BlockPos at){return ((FluidDeviceBlockEntity)level.getBlockEntity(at)).fluidIdentity();}

    /**
     * The registration a one-block mover left under pipe {@code pipe}'s block: the pipe's registration replaced by a
     * portless mover of {@code kind} with {@code control}, as the old block registered it. Returns its identity.
     */
    private static long oneBlock(FluidWorldAuthority world,long pipe,TopologyCompiler.Kind kind,PhysicalFluidTopology.Direction facing,FlowControl control) {
        var position=world.registrations().get(pipe).device().position();world.remove(pipe);
        var r=world.place(position,kind,facing);
        world.edit(r.device().id(),r.revision(),r.device().withControl(control),r.spec());return r.device().id();
    }
}
