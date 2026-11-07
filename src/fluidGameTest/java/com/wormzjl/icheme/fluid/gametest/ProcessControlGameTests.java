package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class ProcessControlGameTests {
    private ProcessControlGameTests(){}
    @GameTest(template="empty",timeoutTicks=80,batch="sensor-socket")
    public static void removingTheLastPipeSensorRetiresItsSocketButKeepsTheCable(GameTestHelper helper){
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();
        var pos=helper.absolutePos(new BlockPos(1,2,1));
        level.setBlock(pos,ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.WEST),3);
        var pipe=(FluidDeviceBlockEntity)level.getBlockEntity(pos);
        var host=com.wormzjl.icheme.runtime.instrument.InstrumentHosts.at(level,pos);
        var sensor=new com.wormzjl.icheme.runtime.instrument.Sensor.Installed(new Instrument.Key(host.identity(),"pipe_north"),java.util.UUID.randomUUID(),1,com.wormzjl.icheme.runtime.instrument.Sensor.Kind.PRESSURE);
        pipe.instruments().sensors(java.util.List.of(sensor));world.controls().bind(host);
        var port=pipe.instruments().dataPort();helper.assertTrue(port!=null,"Sensor has no data connector");
        var cable=new com.wormzjl.icheme.runtime.control.DataBus.Cable(new com.wormzjl.icheme.runtime.control.DataBus.Site(port.dimension(),port.cell(),0),
                new com.wormzjl.icheme.science.equipment.ConduitGeometry.Key(0,0).connect(port.face().ordinal(),port.u(),port.v()),0,java.util.Map.of(port.face(),port.id()));
        world.controls().cable(cable);
        pipe.instruments().sensors(java.util.List.of());world.controls().bind(host);
        helper.assertTrue(pipe.instruments().dataPort()==null,"Removed sensor left a floating connector");
        helper.assertTrue(pipe.instruments().sensorIds().isEmpty(),"Removed sensor left a displayed ID");
        helper.assertTrue(world.controls().facts().port(port.id())==null,"Removed connector remains registered on the bus");
        helper.assertTrue(world.controls().facts().cable(cable.site()).equals(cable),"Removing a sensor changed the built cable");
        helper.assertTrue(world.controls().facts().sensorId(sensor.instance())!=null,"Historical sensor identity was lost");
        helper.assertTrue(!pipe.getUpdateTag(level.registryAccess()).contains("DataPort"),"Client update retained the removed connector");
        world.controls().bind(host);helper.assertTrue(pipe.instruments().dataPort()==null,"Rebinding recreated the orphan connector");
        world.controls().removeCable(cable.site());level.removeBlock(pos,false);helper.succeed();
    }
    /** The registered modular valve must survive both projected and committed geometry refreshes, with its direction. */
    @GameTest(template="empty",timeoutTicks=1000,batch="fluid-modular-valve")
    public static void modularValveKeepsItsFittingAcrossGeometryRefresh(GameTestHelper helper){
        var level=helper.getLevel();var world=FluidWorldAuthority.find(level.getServer()).orElseThrow();var pos=helper.absolutePos(BlockPos.ZERO);
        level.setBlock(pos,ModBlocks.FLUID_PIPE.get().defaultBlockState().setValue(FluidDeviceBlock.FACING,Direction.WEST),3);
        var entity=(FluidDeviceBlockEntity)level.getBlockEntity(pos);long id=entity.fluidIdentity(),start=world.onlineTick();
        helper.assertTrue(world.installPipePressureValve(id,PhysicalFluidTopology.Direction.EAST),"The valve was not queued");
        world.instruments().legacyInstalled(id,Instrument.Type.PRESSURE_VALVE);
        helper.assertTrue(entity.instruments().dataPort()!=null,"A bare actuator must keep its own data connector without a sensor");
        entity.refreshGeometry(entity.bore(),true);
        helper.assertTrue(FluidDeviceBlock.hasPressureValve(level.getBlockState(pos)),"Projected modular valve lost its physical fitting");
        helper.assertTrue(level.getBlockState(pos).getValue(FluidDeviceBlock.FACING)==Direction.EAST,"Projected valve lost its forward direction");
        boolean[] removed={false};
        helper.succeedWhen(()->{
            if(!removed[0]){
                helper.assertTrue(world.onlineTick()>=start+200,"Waiting for scheduled commits and publication");
                helper.assertTrue(world.committedDevice(id).orElseThrow().control() instanceof FlowControl.ControlValve,"The modular valve was not committed");
                entity.refreshGeometry(entity.bore(),true);
                helper.assertTrue(FluidDeviceBlock.hasPressureValve(level.getBlockState(pos)),"Committed modular valve lost its physical fitting");
                helper.assertTrue(level.getBlockState(pos).getValue(FluidDeviceBlock.FACING)==Direction.EAST,"Committed valve lost its forward direction");
                level.removeBlock(pos,false);removed[0]=true;
            }
            helper.assertTrue(!world.registrations().containsKey(id),"Waiting for cleanup");
        });
    }
}
