package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A flow valve that regulates a line its open pass chokes (batch 2026-11-02-valve-hold). The line stands well under its
 * caps at the target and the valve takes the drop, whatever the ratio across it. Before the fix the regulated pass carried
 * the open pass's law flows, deep on the saturated law's plateau, and the Newton never left it: the island held at
 * residual (cap - target) at every step size, at 5 to 4 bar as at 5 to 2.5 bar.
 */
class RegulatingValveHoldTest {
    private static final UUID SENSOR=new UUID(1,1);
    private static double[] pure(int component){double[] n=new double[MaterialTestBasis.NETWORK+1];n[component]=1;return n;}
    private static double[] crude(){return Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane_nitrogen").crudeFeed("icheme:tia_juana_light_methane").moleFractions(),MaterialTestBasis.NETWORK+1);}
    private static FlowControl.ControlValve valve(double target){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,target,SENSOR,1);}
    private static FluidDeviceSpec sink(double pressure,double temperature){return new FluidDeviceSpec(1,temperature,pressure,pure(MaterialTestBasis.NITROGEN));}

    /** Generator, pipe, valve, pipe, void, as the column's boundary lines are laid, placed with the target set. */
    private static void assertRegulates(String name,double[] composition,double temperature,double supply,double sink,double bore,double target) {
        var w=new EventPathWorld();
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,temperature,supply,composition),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        w.place(2,0,0,Kind.PIPE,EAST,valve(target),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,Kind.VOID,EAST,sink(sink,temperature),bore);
        w.runTo(800);
        w.assertKeptUp(name);
        var result=w.coordinator.snapshots().getFirst().lastResult().orElseThrow();
        for(double flow:result.averageMassFlows())assertEquals(target,flow,1e-6*target,name);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,result.endpointModes().getLast(),name);
    }
    @Test void theColumnsBoundaryLinesRegulateAtAnyRatio() {
        double[] steam=pure(MaterialTestBasis.NETWORK),oil=crude();
        for(double sink:new double[]{250000,400000}) {
            assertRegulates("steam to "+sink,steam,533,500000,sink,.15,.75);
            assertRegulates("crude to "+sink,oil,638.2,500000,sink,.25,19.9);
        }
        assertRegulates("a small flow of nitrogen",pure(MaterialTestBasis.NITROGEN),298.15,500000,400000,.05,.05);
    }
    /** Two supplies into one header above the valve: no closure of the series line tells either supply's share. */
    @Test void aHeaderOfTwoSuppliesRegulates() {
        var w=new EventPathWorld();double[] steam=pure(MaterialTestBasis.NETWORK);double bore=.15,target=.75;
        w.place(0,0,1,Kind.GENERATOR,SOUTH,new FlowControl.Passive(),new FluidDeviceSpec(1,533,500000,steam),bore);
        w.place(0,0,-1,Kind.GENERATOR,NORTH,new FlowControl.Passive(),new FluidDeviceSpec(1,533,500000,steam),bore);
        w.place(0,0,0,Kind.PIPE,EAST,bore);
        w.place(1,0,0,Kind.PIPE,EAST,valve(target),null,bore);
        w.place(2,0,0,Kind.PIPE,EAST,bore);
        w.place(3,0,0,Kind.VOID,EAST,sink(250000,533),bore);
        w.runTo(800);
        w.assertKeptUp("a header of two supplies");
        var result=w.coordinator.snapshots().getFirst().lastResult().orElseThrow();
        var graph=w.coordinator.snapshots().getFirst().graph();
        for(int e=0;e<graph.pipes().size();e++)if(graph.pipes().get(e).control() instanceof FlowControl.ControlValve)assertEquals(target,result.averageMassFlows()[e],1e-6*target);
    }
    /**
     * A running island whose closed valve gets its target, as a column's cycle sets it: the solve carries the closed point, so
     * no balance starts it; the valve goes closed, open, regulating within one step.
     */
    @Test void aClosedValveTakesItsTargetOnARunningIsland() {
        var model=FluidTestSupport.networkModel();var w=new EventPathWorld();double[] steam=pure(MaterialTestBasis.NETWORK);double bore=.15,target=.75;
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,533,500000,steam),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        w.place(2,0,0,Kind.PIPE,EAST,valve(0),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,Kind.VOID,EAST,sink(250000,533),bore);
        w.runTo(200);
        var graph=w.coordinator.snapshots().getFirst().graph();
        var solver=new PassiveIntervalSolver(model);
        var rest=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var pipe:rest.graph().pipes())pipes.add(pipe.control() instanceof FlowControl.ControlValve?pipe.withControl(valve(target)):pipe);
        var running=solver.solve(new PassiveNetwork(rest.graph().reservoirs(),List.copyOf(pipes),rest.graph().scheduledTransfers()),5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,running.endpointModes().getLast());
        for(double flow:running.endpointFlows())assertEquals(target,flow,1e-6*target);
    }
}
