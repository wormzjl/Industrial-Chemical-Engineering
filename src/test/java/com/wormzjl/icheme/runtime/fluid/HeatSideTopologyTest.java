package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/** A unit's heat side in the topology (batch 2026-10-27-heat-exchanger-design, work package W2). */
class HeatSideTopologyTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry STUB=new PipeResistance.Geometry(1,.05,.000045,0);
    private static final HeatUnit TUBE=new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,450,0);
    private static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position("minecraft:overworld",x,y,z);}
    private static PhysicalFluidTopology.Device plain(long id,int x,int y,int z,Kind kind){return new PhysicalFluidTopology.Device(id,at(x,y,z),kind,EAST,STUB,new FlowControl.Passive());}
    private static PhysicalFluidTopology.Port port(PhysicalFluidTopology.Position cell,PhysicalFluidTopology.Direction face,PhysicalFluidTopology.ConnectionRole role){return new PhysicalFluidTopology.Port(cell,face,PassiveNetwork.PhasePort.BULK,role);}
    private static PhysicalFluidTopology.Device side(long id,List<PhysicalFluidTopology.Port> ports,PipeResistance.Geometry passage,HeatUnit heat) {
        return new PhysicalFluidTopology.Device(id,ports.getFirst().cell(),Kind.HEAT_SIDE,NORTH,passage,new FlowControl.Passive(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
    }
    private PassiveNetwork.Reservoir nitrogenAt(long id,int y,PassiveNetwork.NodeKind kind,double pressure){return new PassiveNetwork.Reservoir(id,y,model.initialNitrogenCharge(1,298.15,pressure,()->{}),kind);}

    /**
     * Nitrogen from a 1.5 bar generator through a pipe into the side's inlet, out of its outlet 2 m higher through a pipe
     * into a 1 atm void. The side compiles as a filter does: its inlet is its node, its outlet a junction of its own at the
     * outlet's height, and a passive pipe of its passage joins them.
     */
    @Test void aSideIsAPassivePipeOfItsPassageBetweenItsInletNodeAndAnOutletJunction() {
        var passage=HeatSides.tubePassage(.5,2);
        var ports=List.of(port(at(10,0,10),WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(at(0,2,0),EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
        var devices=List.of(plain(1,8,0,10,Kind.GENERATOR),plain(20,9,0,10,Kind.PIPE),side(10,ports,passage,TUBE),plain(30,1,2,0,Kind.PIPE),plain(2,2,2,0,Kind.VOID));
        var compiled=PhysicalFluidTopology.compile(devices,Map.of(1L,nitrogenAt(1,0,PassiveNetwork.NodeKind.GENERATOR,150000),2L,nitrogenAt(2,2,PassiveNetwork.NodeKind.VOID,101325)));
        assertEquals(1,compiled.islands().size());
        var graph=compiled.islands().getFirst().graph();
        var own=graph.pipes().stream().filter(p->p.id()==PhysicalFluidTopology.sideIdentity(10)).findFirst().orElseThrow();
        assertEquals(List.of(passage),own.sections());assertInstanceOf(FlowControl.Passive.class,own.control());assertNull(own.heat(),"no partner read yet: the side moves no heat");
        assertEquals(10,graph.reservoirs().get(own.first()).id());
        var outlet=graph.reservoirs().get(own.second());assertEquals(PassiveNetwork.NodeKind.JUNCTION,outlet.kind());assertEquals(2,outlet.elevation());
        // The outlet run leaves from the outlet junction; the inlet run ends at the node; the nozzles are stubs at the line's bore.
        var runs=graph.pipes().stream().filter(p->p!=own).toList();assertEquals(2,runs.size());
        for(var run:runs)assertTrue(run.sections().stream().allMatch(g->g.channels()==1&&g.diameter()==.05));
        assertTrue(runs.stream().anyMatch(p->p.first()==own.second()||p.second()==own.second()));
        assertEquals(own.id(),compiled.pipeViews().get(10L).getFirst().pipeId());
        // Three runs in series carry one flow; the passage takes its own drop.
        var result=new PassiveIntervalSolver(model).solve(graph,.05,PassiveIntervalSolver.Settings.defaults(),()->{});
        var q=result.averageMassFlows();int index=graph.pipes().indexOf(own);
        assertTrue(q[index]>0);
        for(int i=0;i<q.length;i++)assertEquals(q[index],Math.abs(q[i]),1e-6*q[index]);
        var end=result.graph().reservoirs();
        assertTrue(end.get(own.first()).state().pressure()>end.get(own.second()).state().pressure());
    }
    @Test void onlyASideCarriesAUnitAndInletAndOutletPorts() {
        var ports=List.of(port(at(0,0,0),WEST,PhysicalFluidTopology.ConnectionRole.INLET),port(at(2,0,0),EAST,PhysicalFluidTopology.ConnectionRole.OUTLET));
        assertDoesNotThrow(()->side(5,ports,STUB,TUBE));
        assertThrows(IllegalArgumentException.class,()->side(5,ports,STUB,null),"a side without its unit");
        assertThrows(IllegalArgumentException.class,()->side(5,List.of(ports.getLast(),ports.getFirst()),STUB,TUBE),"its node is its first inlet's cell");
        assertThrows(IllegalArgumentException.class,()->side(5,List.of(ports.getFirst()),STUB,TUBE),"an inlet without an outlet");
        assertThrows(IllegalArgumentException.class,()->side(5,ports,STUB,TUBE.withPartner(5)),"its own partner");
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Device(6,at(0,0,0),Kind.PUMP,NORTH,STUB,new FlowControl.Pump(.01,500000),ports),"a pump with inlet and outlet ports");
        assertThrows(IllegalArgumentException.class,()->new PhysicalFluidTopology.Device(7,at(0,0,0),Kind.PIPE,NORTH,STUB,new FlowControl.Passive(),List.of(),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,TUBE),"a pipe with a unit");
    }
}
