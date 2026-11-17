package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.EAST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The review world's crude charge line at the column's start (batch 2026-11-16-review-plant-sizing, START_HOLD_REVIEW.md):
 * Tia Juana crude at 300 K from a generator, a 0.4 m suction pipe, P-101's law at full speed, a run that passes 2 x design
 * (318.85 kg/s) on 7.5 bar, the column-sensor flow valve, a void. The valve stands shut while the pump dead-heads, then
 * takes its target. At a 150 kPa supply the pump's suction breaks down at 216.46 kg/s (its NPSH3 is 12.5 m at rated flow):
 * a target above it leaves the valve open there with the pump on its suction limit, one below it regulates. No case holds,
 * from the committed interval or from a fresh solver.
 */
class StartHoldTest {
    static final double T=300,DESIGN=159.42,TWO=318.85,BORE=0.11232481275267124,OPEN=216.4562;
    static final CentrifugalPump STRONG=new CentrifugalPump(.20279,170.98,.75,2950),WEAK=new CentrifugalPump(.21206,100,.75,2950);
    private static double[] crude(){return Arrays.copyOf(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane_nitrogen").crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions(),MaterialTestBasis.NETWORK+1);}
    private static FlowControl.ControlValve inlet(double target,long revision){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,target,.01,PortPlan.COLUMN_SENSOR,revision);}

    @Test void aTargetAboveTheSuctionsBreakdownLeavesTheValveOpen(){
        check("2 x design",150000,250000,STRONG,TWO,OPEN,FlowControl.Mode.VALVE_OPEN,FlowControl.Mode.PUMP_SUCTION_LIMIT);
        check("1.05 x the open flow",150000,250000,STRONG,1.05*OPEN,OPEN,FlowControl.Mode.VALVE_OPEN,FlowControl.Mode.PUMP_SUCTION_LIMIT);
    }
    @Test void aTargetBelowTheSuctionsBreakdownRegulates(){
        check("1.05 x design",150000,250000,STRONG,1.05*DESIGN,1.05*DESIGN,FlowControl.Mode.VALVE_REGULATING,FlowControl.Mode.MOVER_LAW);
        check("0.95 x the open flow",150000,250000,STRONG,.95*OPEN,.95*OPEN,FlowControl.Mode.VALVE_REGULATING,FlowControl.Mode.MOVER_LAW);
    }
    @Test void linesFarFromTheSuctionLimitKeepTheirResults(){
        check("1000 kPa supply",1e6,250000,STRONG,TWO,TWO,FlowControl.Mode.VALVE_REGULATING,FlowControl.Mode.MOVER_LAW);
        check("weak curve",150000,250000,WEAK,TWO,221.5245,FlowControl.Mode.VALVE_OPEN,FlowControl.Mode.PUMP_SUCTION_LIMIT);
        check("400 kPa supply, 500 kPa sink",400000,500000,STRONG,TWO,TWO,FlowControl.Mode.VALVE_REGULATING,FlowControl.Mode.MOVER_LAW);
    }

    /** A running island whose target crosses the breakdown flow in later intervals: above it the valve stands open with the
     * pump on its suction limit; lowered to 1.05 x design it regulates with the pump back on its law (option E1); raised again
     * it opens and the pump returns to its limit. */
    @Test void aTargetMovedAcrossTheBreakdownFollowsIt(){
        var w=new EventPathWorld();long valve=line(w,150000,250000,STRONG);w.runTo(800);
        long tick=800;
        for(double[] step:new double[][]{{TWO,OPEN,0},{1.05*DESIGN,1.05*DESIGN,1},{TWO,OPEN,0}}) {
            double target=step[0];
            LevelLoopBlanketStartTest.edit(w,valve,d->d.withControl(inlet(target,0)),s->s);
            tick+=800;w.runTo(tick);
            assertTrue(w.held.isEmpty(),"target "+target+": holds "+w.held);
            var s=island(w);var r=s.lastResult().orElseThrow();int v=edge(s.graph(),FlowControl.ControlValve.class),p=edge(s.graph(),FlowControl.Machine.class);
            boolean regulates=step[2]==1;
            assertEquals(step[1],r.averageMassFlows()[v],1e-3*step[1],"target "+target+": valve flow");
            assertEquals(regulates?FlowControl.Mode.VALVE_REGULATING:FlowControl.Mode.VALVE_OPEN,r.endpointModes().get(v),"target "+target+": valve mode");
            assertEquals(regulates?FlowControl.Mode.MOVER_LAW:FlowControl.Mode.PUMP_SUCTION_LIMIT,r.endpointModes().get(p),"target "+target+": pump mode");
        }
    }
    /** The line, its valve shut; the valve's device id. */
    private static long line(EventPathWorld w,double supply,double sink,CentrifugalPump pump) {
        double[] x=crude();
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,T,supply,x.clone()),.4);
        w.place(1,0,0,Kind.PIPE,EAST,.4);
        w.place(2,0,0,Kind.PUMP,EAST,new FlowControl.Machine(pump,1,true),null,.3);
        for(int i=0;i<6;i++)w.place(3+i,0,0,Kind.PIPE,EAST,BORE);
        long valve=w.place(9,0,0,Kind.PIPE,EAST,inlet(0,1),null,BORE);
        w.place(10,0,0,Kind.PIPE,EAST,BORE);
        w.place(11,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,T,sink,x.clone()),BORE);
        return valve;
    }
    /** Dead-headed for 800 ticks, then the target: solved on the island's graph from the committed interval and fresh, and in the world. */
    private static void check(String name,double supply,double sink,CentrifugalPump pump,double target,double flow,FlowControl.Mode valveMode,FlowControl.Mode pumpMode) {
        var w=new EventPathWorld();long valve=line(w,supply,sink,pump);
        w.runTo(800);
        var island=island(w);var last=island.lastResult().orElseThrow();var g=w.worldGraph(island);
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var q:g.pipes())pipes.add(q.control() instanceof FlowControl.ControlValve?q.withControl(inlet(target,0)):q);
        var next=new PassiveNetwork(g.reservoirs(),List.copyOf(pipes),g.scheduledTransfers());
        int v=edge(next,FlowControl.ControlValve.class),p=edge(next,FlowControl.Machine.class);
        for(boolean carried:new boolean[]{true,false}) {
            var solver=new RetainedSolver();if(carried)solver.committed(last);
            var r=solver.solve(FluidTestSupport.networkModel(),next,1,PassiveIntervalSolver.Settings.defaults(),()->{});
            String label=name+(carried?", carried":", fresh");
            assertEquals(flow,r.averageMassFlows()[v],1e-3*flow,label+": valve flow");
            assertEquals(valveMode,r.endpointModes().get(v),label+": valve mode");
            assertEquals(pumpMode,r.endpointModes().get(p),label+": pump mode");
        }
        LevelLoopBlanketStartTest.edit(w,valve,d->d.withControl(inlet(target,0)),s->s);
        w.runTo(1600);
        assertTrue(w.held.isEmpty(),name+": holds "+w.held);
        var s=island(w);var r=s.lastResult().orElseThrow();int wv=edge(s.graph(),FlowControl.ControlValve.class),wp=edge(s.graph(),FlowControl.Machine.class);
        assertEquals(flow,r.averageMassFlows()[wv],1e-3*flow,name+", world: valve flow");
        assertEquals(valveMode,r.endpointModes().get(wv),name+", world: valve mode");
        assertEquals(pumpMode,r.endpointModes().get(wp),name+", world: pump mode");
    }
    private static IslandCoordinator.Snapshot island(EventPathWorld w){return w.coordinator.snapshots().stream().filter(s->s.graph().pipes().stream().anyMatch(q->q.control() instanceof FlowControl.ControlValve)).findFirst().orElseThrow();}
    private static int edge(PassiveNetwork g,Class<?> type){for(int e=0;e<g.pipes().size();e++)if(type.isInstance(g.pipes().get(e).control()))return e;return -1;}
}
