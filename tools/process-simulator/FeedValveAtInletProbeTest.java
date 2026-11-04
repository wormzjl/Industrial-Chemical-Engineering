package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * One-time probe (batch 2026-11-02-process-simulator, owner 2026-11-03: "one time test only"): a control valve placed as a
 * real world places it, a {@code fluid_pipe} ({@code Kind.PIPE}) carrying a {@link FlowControl.ControlValve}
 * (FluidWorldAuthority.place), straight against a heat side's INLET port, a water generator beyond it, the side's outlet
 * through a pipe to a void. Not EventPathWorld's {@code Kind.VALVE}, which no real block makes. Asserts one island, no
 * dead-end diagnostic, and flow through the valve. Detached to tools/process-simulator/ after its one run.
 */
class FeedValveAtInletProbeTest {
    private static PhysicalFluidTopology.Device side(long id,int z,HeatUnit heat) {
        var in=new PhysicalFluidTopology.Position(EventPathWorld.DIM,2,64,z);var out=new PhysicalFluidTopology.Position(EventPathWorld.DIM,4,64,z);
        var ports=List.of(new PhysicalFluidTopology.Port(in,WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.INLET),
                new PhysicalFluidTopology.Port(out,EAST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(id,in,Kind.HEAT_SIDE,NORTH,HeatSides.tubePassage(.5,3),new FlowControl.Passive(),ports,true,
                PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
    }

    @Test void aValvePipeStraightAgainstAHeatSidesInletJoinsItsIslandAndPasses() {
        for(var valveLaw:List.of(new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,.05,new UUID(5,5),1),FlowControl.ControlValve.unconfigured())) {
            var world=new EventPathWorld();world.runTo(10);
            int m=world.model.componentCount();var water=new double[m];water[m-1]=1;
            // Row 0: generator (0), the valve pipe (1) against the tube side's inlet (2..4), a pipe (5), a void (6).
            long generator=world.place(0,64,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,300,300000,water),.05);
            long valve=world.place(1,64,0,Kind.PIPE,EAST,valveLaw,null,.05);
            world.place(5,64,0,Kind.PIPE,EAST,.05);
            long sink=world.place(6,64,0,Kind.VOID,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,300,200000,water),.05);
            // Row 4: the shell side's own plain line.
            world.place(0,64,4,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,330,300000,water),.05);
            world.place(1,64,4,Kind.PIPE,EAST,.05);world.place(5,64,4,Kind.PIPE,EAST,.05);
            world.place(6,64,4,Kind.VOID,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,330,200000,water),.05);
            long tube=world.ledger.nextIdentity(),shell=tube+1;double area=ProcessUnit.tubeSurface(.5,3);
            world.registry.submit(List.of(
                    new WorldTopologyLedger.Edit(tube,new WorldTopologyLedger.Registration(side(tube,0,new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,area,shell)),new FluidDeviceSpec(1,300,300000,water),0)),
                    new WorldTopologyLedger.Edit(shell,new WorldTopologyLedger.Registration(side(shell,4,new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.SHELL,area,tube)),new FluidDeviceSpec(1,330,300000,water),0))),shell+1,null);
            world.runTo(2000);
            Long island=world.registry.owner(valve);
            String diagnostic=world.registry.diagnostic(valve);
            double target=Double.NaN,flow=Double.NaN;
            assertNotNull(island,"the valve pipe belongs to an island");
            var snap=world.coordinator.observe(island);var graph=snap.graph();
            for(int e=0;e<graph.pipes().size();e++){var p=graph.pipes().get(e);
                if(p.control() instanceof FlowControl.ControlValve law&&(graph.reservoirs().get(p.first()).id()==valve||graph.reservoirs().get(p.second()).id()==valve)){target=law.massTarget();flow=snap.lastResult().orElseThrow().endpointFlows()[e];}}
            System.out.printf(Locale.ROOT,"FEED_VALVE_AT_INLET law=%s island=%d members=%s generator=%s side=%s sink=%s diagnostic=%s target=%.6f kg/s flow=%.6f kg/s status=%s held=%s%n",
                    valveLaw.bare()?"bare":"mass-flow",island,world.registry.members(island),world.registry.owner(generator),world.registry.owner(PhysicalFluidTopology.sideIdentity(tube)),world.registry.owner(sink),diagnostic,target,flow,snap.status(),world.held);
            assertEquals(island,world.registry.owner(generator),"the generator beyond the valve is in its island");
            assertEquals(island,world.registry.owner(sink),"the side's outlet void is in its island: one island through the side");
            assertTrue(diagnostic==null||!diagnostic.startsWith("NO FLOW"),"no dead-end diagnostic: "+diagnostic);
            assertTrue(flow>1e-4,"water passes the open valve: "+flow);
            assertTrue(world.held.isEmpty(),"no island held: "+world.held);
        }
    }
}
