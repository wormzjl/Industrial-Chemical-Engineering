package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A pump and a flow valve in one line (merge review of 2026-11-02, finding 2). One flow runs through both. At a target below
 * the pump's rate the valve regulates and the pump rides up to its head limit; behind a closed valve the pump stands at
 * shutoff; above its rate the valve stands open. Before the fix the target-holding pump and the regulating valve were two
 * prescribed flows into one junction, whose pressure no row placed: singular at every step size.
 */
class PumpAheadOfFlowValveTest {
    private static final double RATE=.01,HEAD=500000;
    private static FlowControl.ControlValve valve(double target){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,target,new UUID(1,1),1);}
    private static double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NETWORK]=1;return n;}

    private static EventPathWorld line(double target,boolean pumpFirst) {
        var w=new EventPathWorld();double bore=.1;var spec=new FluidDeviceSpec(1,300,200000,water());
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),spec,bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        w.place(2,0,0,pumpFirst?Kind.PUMP:Kind.PIPE,EAST,pumpFirst?new FlowControl.Pump(RATE,HEAD):valve(target),null,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        w.place(4,0,0,pumpFirst?Kind.PIPE:Kind.PUMP,EAST,pumpFirst?valve(target):new FlowControl.Pump(RATE,HEAD),null,bore);
        w.place(5,0,0,Kind.PIPE,EAST,bore);
        w.place(6,0,0,Kind.VOID,EAST,spec,bore);
        w.runTo(800);
        return w;
    }
    private static FlowControl.Mode modeOf(PassiveIntervalSolver.Result result,PassiveNetwork graph,Class<?> control) {
        for(int e=0;e<graph.pipes().size();e++)if(control.isInstance(graph.pipes().get(e).control()))return result.endpointModes().get(e);
        return fail("no such connection");
    }
    private static void assertLine(EventPathWorld w,String name,double flow,FlowControl.Mode pump,FlowControl.Mode valve) {
        assertLine(w,name,flow,java.util.EnumSet.of(pump),valve);
    }
    private static void assertLine(EventPathWorld w,String name,double flow,java.util.Set<FlowControl.Mode> pump,FlowControl.Mode valve) {
        w.assertKeptUp(name);
        var island=w.coordinator.snapshots().getFirst();var result=island.lastResult().orElseThrow();
        for(double f:result.averageMassFlows())assertEquals(flow,f,Math.max(1e-6*flow,1e-9),name);
        assertTrue(pump.contains(modeOf(result,island.graph(),FlowControl.Pump.class)),name+": "+modeOf(result,island.graph(),FlowControl.Pump.class));
        assertEquals(valve,modeOf(result,island.graph(),FlowControl.ControlValve.class),name);
    }
    @Test void aTargetBelowThePumpsRateRegulatesWithThePumpNearItsReliefRise() {
        assertLine(line(5,true),"a pump ahead of the valve",5,FlowControl.Mode.MOVER_LAW,FlowControl.Mode.VALVE_REGULATING);
    }
    /** A closed valve dead-heads the pump: it runs on its law at its relief rise, the relief passing its whole displacement
     * back (batch 2026-11-03-rotating-machinery, form F2e), or the sealed pocket between them closes it (decision D58). */
    @Test void aClosedValveLeavesThePumpAtShutoff() {
        assertLine(line(0,true),"a pump behind a closed valve",0,java.util.EnumSet.of(FlowControl.Mode.MOVER_LAW,FlowControl.Mode.CLOSED),FlowControl.Mode.CLOSED);
    }
    @Test void aTargetAboveThePumpsRateChangesNothing() {
        var w=line(50,true);w.assertKeptUp("above the pump's rate");
        var island=w.coordinator.snapshots().getFirst();var result=island.lastResult().orElseThrow();
        assertEquals(FlowControl.Mode.MOVER_LAW,modeOf(result,island.graph(),FlowControl.Pump.class));
        assertEquals(FlowControl.Mode.VALVE_OPEN,modeOf(result,island.graph(),FlowControl.ControlValve.class));
        assertEquals(RATE*result.graph().reservoirs().getFirst().state().mass()/result.graph().reservoirs().getFirst().state().volume(),result.averageMassFlows()[0],.05);
    }
    @Test void aValveAheadOfThePumpMetersItsSuction() {
        var w=line(5,false);w.assertKeptUp("a valve ahead of the pump");
        var island=w.coordinator.snapshots().getFirst();var result=island.lastResult().orElseThrow();
        for(double f:result.averageMassFlows())assertEquals(5,f,5e-6);
        assertEquals(FlowControl.Mode.VALVE_REGULATING,modeOf(result,island.graph(),FlowControl.ControlValve.class));
    }
    /** A running line whose target moves past the pump's rate and back, as a column's cycle moves it: each change takes effect within its solve. */
    @Test void aRunningLineFollowsItsTargetPastThePumpsRate() {
        var model=FluidTestSupport.networkModel();var w=line(5,true);var solver=new RetainedSolver();var settings=PassiveIntervalSolver.Settings.defaults();
        var committed=solver.solve(model,w.worldGraph(w.coordinator.snapshots().getFirst()),5,settings,()->{});
        for(double target:new double[]{6,50,5,0,4,50}) {
            var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(var pipe:committed.graph().pipes())pipes.add(pipe.control() instanceof FlowControl.ControlValve?pipe.withControl(valve(target)):pipe);
            solver.committed(committed);
            var running=solver.solve(model,new PassiveNetwork(committed.graph().reservoirs(),List.copyOf(pipes),committed.graph().scheduledTransfers()),5,settings,()->{});
            double expected=target==50?RATE*997:target;
            for(double f:running.endpointFlows())assertEquals(expected,f,target==50?.2:1e-6*Math.max(target,1),"target "+target);
            committed=running;
        }
    }
}
