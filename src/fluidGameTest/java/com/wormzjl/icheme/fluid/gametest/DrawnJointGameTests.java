package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * What a block draws joined is what the network joins (batch 2026-11-09-drawn-connections): the drawing reads the
 * registry through the network's own as-built rule, {@code PhysicalFluidTopology.builtFaces}.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class DrawnJointGameTests {
    private DrawnJointGameTests() {}

    /**
     * An In-line Pump installed as {@code PipelineFittingItem} installs it registers an in-line machine (since 2260953dd),
     * which the redraw read as no mover: the pump vanished from the block, and a pipe placed beside it drew a branch the
     * network does not have. The block now draws the engine's classification, and the branch draws no joint.
     */
    @GameTest(template="empty",batch="drawn-joints")
    public static void anInLinePumpInstalledAsTheItemDoesDrawsAsAPumpAndNoBranch(GameTestHelper helper) {
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var west=helper.absolutePos(new BlockPos(1,1,1));var middle=west.east();var east=middle.east();var side=middle.south();
        var pipe=ModBlocks.FLUID_PIPE.get().defaultBlockState();
        for(var at:List.of(west,middle,east))level.setBlock(at,pipe,Block.UPDATE_ALL);
        try {
            var entity=(FluidDeviceBlockEntity)level.getBlockEntity(middle);
            // The item's steps: the state with its pump on the straight pipe, the registration's edit, then the redraw.
            var state=level.getBlockState(middle).setValue(FluidDeviceBlock.FACING,Direction.EAST).setValue(FluidDeviceBlock.INLINE_MOVER,FluidDeviceBlock.InlineMover.PUMP);
            level.setBlock(middle,FluidDeviceBlock.connectedState(state,level,middle),Block.UPDATE_ALL);
            helper.assertTrue(world.installPipeMover(entity.fluidIdentity(),PhysicalFluidTopology.Direction.EAST,TopologyCompiler.Kind.PUMP),"The straight pipe took no pump");
            entity.refreshGeometry(entity.bore(),true);
            helper.assertTrue(world.registrations().get(entity.fluidIdentity()).device().control() instanceof FlowControl.Machine,"The item installs an in-line machine");
            var drawn=level.getBlockState(middle);
            helper.assertTrue(FluidDeviceBlock.hasPump(drawn),"The installed pump is not drawn");
            helper.assertTrue(drawn.getValue(FluidDeviceBlock.connection(Direction.WEST))&&drawn.getValue(FluidDeviceBlock.connection(Direction.EAST)),"The pump is not drawn joined along its axis");
            // A pipe placed beside the pump: the network joins it nowhere, so neither block draws the joint.
            level.setBlock(side,pipe,Block.UPDATE_ALL);
            helper.assertTrue(!level.getBlockState(middle).getValue(FluidDeviceBlock.connection(Direction.SOUTH)),"The pump draws a branch the network does not have");
            helper.assertTrue(!FluidDeviceBlock.connectedState(level.getBlockState(side),level,side).getValue(FluidDeviceBlock.connection(Direction.NORTH)),"The pipe beside the pump draws a joint into it");
        } finally {
            for(var at:List.of(side,east,middle,west))level.removeBlock(at,false);
        }
        helper.succeed();
    }

    /**
     * A column is no fluid device and has no registration, so a pipe drew no joint to its nozzle: the overhead line, which
     * bends at its first block, ended in a blind flange and the nozzle kept its blind cap (owner, review plant build 9). The
     * pipe's drawn faces now meet the nozzles of the structures the registry records (NOZZLE_JOIN_REVIEW.md). For the top
     * nozzle (the line bending at its first block) and a side nozzle: the pipe's block is joined towards the nozzle, its
     * face is flanged, the nozzle is drawn at the pipe's size; a removed pipe leaves the nozzle at its default. Then the
     * other order: the pipes stand first, the column comes second, and its removal drops the joints. The test removes
     * everything it placed before it passes or fails. Its plant's box reaches beyond the test's structure, so it runs in a
     * batch of its own, as the assembly tests do: no other test of its batch builds inside that box.
     */
    @GameTest(template="empty",batch="drawn-joints-nozzle",timeoutTicks=600)
    public static void aPipeOnAColumnNozzleDrawsItsJointInEitherOrder(GameTestHelper helper) {
        var plant=new AssemblyGameTests.Plant(helper);
        var level=plant.level;
        var components=new ColumnComponents(ColumnComponents.Condenser.EXCHANGER,ColumnComponents.Pump.STANDARD,List.of(),false,false);
        var shape=EquipmentShape.column(2,new ColumnTrays(8,4,List.of(),List.of(9),List.of(),components),EquipmentShape.Heading.NORTH,EquipmentShape.minimumSkirt(2));
        String[] failure={null};
        // The second column's place: east of the first, clear of its pipes.
        int second=shape.sizeX()+8;
        Vessel[] columns={null,null};
        var sequence=helper.startSequence();
        try {
            plant.hold();
            columns[0]=plant.place(shape,0,0,0);
            lines(plant,columns[0]);
        } catch(RuntimeException e) {
            failure[0]="build: "+e;
        }
        sequence=sequence.thenWaitUntil(()->helper.assertTrue(failure[0]!=null||joinedBoth(plant,columns[0])==null,"Waiting for the pipes to meet the column: "+joinedBoth(plant,columns[0])))
                .thenExecute(()->{
                    if(failure[0]!=null)return;
                    // The top pipe removed: the nozzle is drawn at its default again.
                    var top=AssemblyGameTests.Plant.front(columns[0],Role.VAPOUR_OUTLET);
                    level.removeBlock(top,false);plant.devices.remove(top);
                })
                .thenWaitUntil(()->helper.assertTrue(failure[0]!=null||AssemblyGameTests.Plant.nozzleCode(level,columns[0],AssemblyGameTests.Plant.port(columns[0],Role.VAPOUR_OUTLET))==0,
                        "Waiting for the top nozzle's default size"))
                .thenExecute(()->{
                    if(failure[0]!=null)return;
                    try {
                        // The other order: the pipes first, then the column.
                        var vessel=new Vessel(plant.base.offset(second,0,0),shape);
                        lines(plant,vessel);
                        if(joinedTowards(level,vessel))failure[0]="a pipe is joined towards the second column's nozzle before the column stands";
                        else columns[1]=plant.place(shape,second,0,0);
                    } catch(RuntimeException e) {
                        failure[0]="second build: "+e;
                    }
                })
                .thenWaitUntil(()->helper.assertTrue(failure[0]!=null||joinedBoth(plant,columns[1])==null,"Waiting for the pipes to meet the second column: "+joinedBoth(plant,columns[1])))
                .thenExecute(()->{
                    if(failure[0]!=null)return;
                    // The column removed: its pipes draw no joint towards where its nozzles stood.
                    var vessel=columns[1];
                    EquipmentTestCleanup.remove(level,vessel.controller());plant.structures.remove(vessel);
                })
                .thenWaitUntil(()->helper.assertTrue(failure[0]!=null||!joinedTowards(level,columns[1]),"Waiting for the pipes to drop their joints to the removed column"));
        sequence.thenExecute(plant::remove).thenWaitUntil(()->helper.assertTrue(plant.removed(),"Waiting for the pipes and columns to leave"))
                .thenExecute(()->{
                    plant.release();
                    if(failure[0]!=null)helper.fail(failure[0]);
                }).thenSucceed();
    }

    /**
     * Pipes on {@code column}'s top nozzle, the line bending at its first block, and on its feed nozzle, straight out: hand
     * placed, every face a joint, as a player places them.
     */
    private static void lines(AssemblyGameTests.Plant plant,Vessel column) {
        var top=AssemblyGameTests.Plant.front(column,Role.VAPOUR_OUTLET);
        Direction bend=null;
        for(var d:Direction.Plane.HORIZONTAL)if(plant.level.getBlockState(top.relative(d)).isAir()&&plant.level.getBlockState(top.relative(d,2)).isAir()&&plant.level.getBlockState(top.relative(d).below()).isAir()){bend=d;break;}
        if(bend==null)throw new IllegalStateException("No room for the overhead line's bend at "+top.toShortString());
        plant.pipe(top);plant.pipe(top.relative(bend));plant.pipe(top.relative(bend,2));
        var feed=AssemblyGameTests.Plant.front(column,Role.FEED);var out=AssemblyGameTests.Plant.out(column,Role.FEED);
        plant.pipe(feed);plant.pipe(feed.relative(out));
    }

    /** Whether the top or the feed pipe's block state is joined towards {@code column}'s nozzle. */
    private static boolean joinedTowards(net.minecraft.server.level.ServerLevel level,Vessel column) {
        for(var role:List.of(Role.VAPOUR_OUTLET,Role.FEED)) {
            var p=AssemblyGameTests.Plant.port(column,role);var state=level.getBlockState(AssemblyGameTests.Plant.front(column,p));
            if(state.getBlock() instanceof FluidDeviceBlock&&state.getValue(FluidDeviceBlock.connection(Direction.fromDelta(-p.dx(),-p.dy(),-p.dz()))))return true;
        }
        return false;
    }

    /** Null when the top and the feed pipes both meet {@code column} as the player sees it; else what is wrong. */
    private static String joinedBoth(AssemblyGameTests.Plant plant,Vessel column) {
        if(column==null)return "no column";
        var top=plant.joined(column,Role.VAPOUR_OUTLET);
        return top!=null?top:plant.joined(column,Role.FEED);
    }
}
