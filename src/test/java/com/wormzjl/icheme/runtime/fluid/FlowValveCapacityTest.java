package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A flow valve holds its target while its line can pass it; asked for more, it stands open and the line carries what it
 * can. Two flow valves in series carry the lower target's flow, the other standing open (owner decision of 2026-11-02,
 * "Lower target wins"). None of this depends on what the line did before (batch 2026-11-16-review-plant-sizing,
 * VALVE_HOLD_REVIEW.md and VALVE_HOLD_TRACE.md; the case letters are the review's).
 *
 * <p>The line: a generator, a centrifugal pump on its curve (rated 20 L/s at 50 m) or a plain pipe, an optional player's
 * flow valve ("charge"), an optional parallel pair of runs, the column-sensor flow valve ("inlet") and a void at 200 kPa,
 * bore 0.1 m, water. The open line passes the pump's runout: 51.0 kg/s, 51.2 with the pair; 172.2 kg/s with no pump
 * from 600 kPa.</p>
 */
class FlowValveCapacityTest {
    private static final UUID PLAYER=new UUID(1,1);
    /** The open lines' flows (kg/s) and the band they are asserted to: the pump's runout against the line. */
    private static final double OPEN=51.0,OPEN_PAIR=51.22,OPEN_PAIRS=51.10,OPEN_UNPUMPED=172.2,OPEN_BAND=.1;

    /** A line: the pair, a second pair after the inlet, the charge valve's target (0 for none), the supply's pressure, the pump,
     * and what its open line passes. */
    private record Line(boolean pair,boolean pairAfter,double charge,double supply,boolean pump,double open) {}
    private static final Line ONE_VALVE=new Line(false,false,0,200000,true,OPEN),PAIR=new Line(true,false,0,200000,true,OPEN_PAIR),
            UNPUMPED=new Line(false,false,0,600000,false,OPEN_UNPUMPED),PUMPED_FROM_600=new Line(false,false,0,600000,true,Double.POSITIVE_INFINITY),
            PAIRS=new Line(true,true,5,200000,true,OPEN_PAIRS);
    private static Line charged(boolean pair,double charge){return new Line(pair,false,charge,200000,true,pair?OPEN_PAIR:OPEN);}

    @Test void aValveSetAboveTheLineFromClosedStandsOpen(){plans("I",ONE_VALVE,1000);}
    @Test void aValveSetWithinTheLineRegulates(){plans("J",ONE_VALVE,3,30);plans("K",ONE_VALVE,3,50);}
    @Test void aRegulatingValveSetAboveTheLineOpens(){plans("L",ONE_VALVE,3,60);plans("P",ONE_VALVE,30,60);plans("Q",ONE_VALVE,1000,3,60);}
    @Test void aValveSteppedUpThroughTheLineOpensPastIt(){plans("M",ONE_VALVE,3,20,40,50,60,1000);}
    @Test void aStrongerSupplyPassesTheTarget(){plans("N",PUMPED_FROM_600,3,60);}
    @Test void anUnpumpedLineOpensToWhatItsSupplyDrives(){plans("O",UNPUMPED,3,1000);}
    @Test void aValveBehindAPairOpensAboveTheLine(){plans("C",PAIR,3,1000);}
    @Test void theChargeRegulatesWhenTheInletOpensFromClosed(){plans("E",charged(true,5),10);}
    @Test void theInletRegulatesBelowTheCharge(){plans("F",charged(true,5),3,4.9);}
    @Test void theChargeRegulatesWhenTheInletRisesAboveIt(){plans("A",charged(true,5),3,10);plans("G",charged(true,5),3,5.1);plans("H",charged(true,30),3,40);}
    @Test void twoValvesAboveTheLineBothStandOpen(){plans("B",charged(true,1000),3,1000);plans("D",charged(false,1000),3,1000);}
    /** Moved from FlowValvesInSeriesTest (tmp/review-plant-fix, build 7 of the review plant): every change of the inlet's plan. */
    @Test void theLowerTargetRegulatesThroughEveryPlan(){plans("series",charged(true,5),10,3,10,0,10);}

    /**
     * The column start with a pair on both sides of the inlet (option C3 of VALVE_HOLD_TRACE.md): the inlet is one-way between
     * two junctions, so the balance at the charge valve's switch to regulation, started from the open line, does not converge
     * (2.80 kg/s after 20 iterations and 13 sweeps); the regulated pass starts from the open line's own flows instead.
     */
    @Test void theChargeRegulatesWhenTheBalanceAtItsSwitchFails(){plans("A2, pairs",PAIRS,10);replay("A2, pairs",PAIRS,0,10);}
    /** A2: the column start (the inlet closed, then planned) solved again on the island's graph from both starts. */
    @Test void bothStartsSolveTheColumnStart(){replay("A2",charged(true,5),0,10);}
    @Test void bothStartsOpenAValveSetAboveTheLine() {
        replay("B",charged(true,1000),3,1000);replay("C",PAIR,3,1000);replay("L",ONE_VALVE,3,60);replay("O",UNPUMPED,3,1000);
    }

    private static double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NETWORK]=1;return n;}
    private static FlowControl.ControlValve charge(double target){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,target,PLAYER,1);}
    private static FlowControl.ControlValve inlet(double target,long revision){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,target,.01,PortPlan.COLUMN_SENSOR,revision);}

    /** The line built in the test world with the inlet at {@code first}; returns the inlet's device id. */
    private static long build(EventPathWorld w,Line line,double first) {
        double bore=.1;
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,300,line.supply(),water()),bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        if(line.pump())w.place(2,0,0,Kind.PUMP,EAST,new FlowControl.Machine(new CentrifugalPump(.02,50,.7,1475),1,true),null,bore);else w.place(2,0,0,Kind.PIPE,EAST,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        if(line.charge()>0)w.place(4,0,0,Kind.PIPE,EAST,charge(line.charge()),null,bore);else w.place(4,0,0,Kind.PIPE,EAST,bore);
        for(int x=5;x<=7;x++){w.place(x,0,0,Kind.PIPE,EAST,bore);if(line.pair())w.place(x,0,1,Kind.PIPE,EAST,bore);}
        w.place(8,0,0,Kind.PIPE,EAST,bore);
        long feed=w.place(9,0,0,Kind.PIPE,EAST,inlet(first,1),null,bore);
        if(line.pairAfter()){for(int x=10;x<=12;x++){w.place(x,0,0,Kind.PIPE,EAST,bore);w.place(x,0,-1,Kind.PIPE,EAST,bore);}w.place(13,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,300,200000,water()),bore);}
        else{w.place(10,0,0,Kind.PIPE,EAST,bore);w.place(11,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,300,200000,water()),bore);}
        return feed;
    }

    /** The line from a closed inlet through each of the inlet's {@code targets}, 400 ticks each, asserted at each. */
    private static void plans(String name,Line line,double... targets) {
        var w=new EventPathWorld();long feed=build(w,line,0);long tick=400;w.runTo(tick);
        assertLine(w,name+": closed at the start",0,false,line.charge()>0?null:FlowControl.Mode.CLOSED,FlowControl.Mode.CLOSED);
        for(double target:targets) {
            double plan=target;
            LevelLoopBlanketStartTest.edit(w,feed,d->d.withControl(inlet(plan,0)),s->s);
            tick+=400;w.runTo(tick);
            double charge=line.charge()>0?line.charge():Double.POSITIVE_INFINITY,flow=Math.min(Math.min(charge,target),line.open());
            boolean open=flow==line.open();
            var inletMode=target==0?FlowControl.Mode.CLOSED:target<Math.min(charge,line.open())?FlowControl.Mode.VALVE_REGULATING:FlowControl.Mode.VALVE_OPEN;
            var chargeMode=line.charge()==0||target==0?null:charge<Math.min(target,line.open())?FlowControl.Mode.VALVE_REGULATING:FlowControl.Mode.VALVE_OPEN;
            assertLine(w,name+": inlet planned at "+target,flow,open,chargeMode,inletMode);
        }
    }

    /** Each valve's flow and mode at the island's last result (a null mode: not asserted), and no hold. */
    private static void assertLine(EventPathWorld w,String name,double flow,boolean open,FlowControl.Mode charge,FlowControl.Mode inlet) {
        w.assertKeptUp(name);
        assertEquals(List.of(),w.held,name+": holds");
        var island=w.coordinator.snapshots().stream().filter(s->s.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.ControlValve)).findFirst().orElseThrow();
        var result=island.lastResult().orElseThrow();var graph=island.graph();
        for(int e=0;e<graph.pipes().size();e++) {
            if(!(graph.pipes().get(e).control() instanceof FlowControl.ControlValve v))continue;
            boolean isCharge=PLAYER.equals(v.sensor());
            assertEquals(flow,Math.abs(result.averageMassFlows()[e]),open?OPEN_BAND:Math.max(1e-6*flow,1e-6),name+": "+(isCharge?"charge":"inlet")+" flow");
            var expected=isCharge?charge:inlet;
            if(expected!=null)assertEquals(expected,result.endpointModes().get(e),name+": "+(isCharge?"charge":"inlet")+" mode");
        }
    }

    /**
     * The island run to the inlet's {@code first} plan, then {@code second} solved on its graph from the committed interval
     * (carried) and by a fresh solver at 0.1, 1, 5 and 20 s: both solve, and they agree on every flow and mode.
     */
    private static void replay(String name,Line line,double first,double second) {
        var w=new EventPathWorld();build(w,line,first);w.runTo(800);w.assertKeptUp(name+" before");
        var island=w.coordinator.snapshots().stream().filter(x->x.graph().pipes().stream().anyMatch(q->q.control() instanceof FlowControl.ControlValve)).findFirst().orElseThrow();
        var last=island.lastResult().orElseThrow();var g=w.worldGraph(island);var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var q:g.pipes())pipes.add(q.control() instanceof FlowControl.ControlValve v&&PortPlan.COLUMN_SENSOR.equals(v.sensor())?q.withControl(inlet(second,0)):q);
        var next=new PassiveNetwork(g.reservoirs(),List.copyOf(pipes),g.scheduledTransfers());
        var model=FluidTestSupport.networkModel();var settings=PassiveIntervalSolver.Settings.defaults();
        for(double seconds:new double[]{.1,1,5,20}) {
            var carriedSolver=new RetainedSolver();carriedSolver.committed(last);
            var carried=carriedSolver.solve(model,next,seconds,settings,()->{});
            var fresh=new RetainedSolver().solve(model,next,seconds,settings,()->{});
            String at=name+" at "+seconds+" s";
            assertEquals(seconds,carried.advancedSeconds(),1e-12,at+": carried advanced");
            assertEquals(seconds,fresh.advancedSeconds(),1e-12,at+": fresh advanced");
            assertEquals(carried.endpointModes(),fresh.endpointModes(),at+": modes");
            for(int e=0;e<next.pipes().size();e++)
                assertEquals(carried.averageMassFlows()[e],fresh.averageMassFlows()[e],1e-6*Math.max(1,Math.abs(carried.averageMassFlows()[e])),at+": flow of connection "+e);
            double charge=line.charge()>0?line.charge():Double.POSITIVE_INFINITY,flow=Math.min(Math.min(charge,second),line.open());
            for(int e=0;e<next.pipes().size();e++)if(next.pipes().get(e).control() instanceof FlowControl.ControlValve)
                assertEquals(flow,Math.abs(carried.averageMassFlows()[e]),flow==line.open()?OPEN_BAND:1e-6*flow,at+": valve flow");
        }
    }
}
