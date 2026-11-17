package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/** Scratch (tmp/solver-hold, never committed): which start makes the open-line solve stall. Prints, asserts nothing. */
class SeriesHoldScratchTest {
    private static final UUID PLAYER=new UUID(1,1);
    private static double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NETWORK]=1;return n;}
    private static FlowControl.ControlValve charge(double t){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,t,PLAYER,1);}
    private static FlowControl.ControlValve inlet(double target,long revision){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,target,.01,PortPlan.COLUMN_SENSOR,revision);}

    @Test void variants() {
        both("A charge 5, pair, pump: inlet 3 then 10",true,5,3,10,200000,true);
        both("A2 charge 5, pair, pump: inlet 0 then 10 (the column start)",true,5,0,10,200000,true);
    }

    /** The island run to the first plan, then the second plan solved on its graph from the committed interval (carried) and by a fresh solver. */
    private static void both(String name,boolean pair,double chargeTarget,double first,double second,double supply,boolean pump) {
        var w=new EventPathWorld();double bore=.1;var spec=new FluidDeviceSpec(1,300,supply,water());
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),spec,bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        if(pump)w.place(2,0,0,Kind.PUMP,EAST,new FlowControl.Machine(new CentrifugalPump(.02,50,.7,1475),1,true),null,bore);else w.place(2,0,0,Kind.PIPE,EAST,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        if(chargeTarget>0)w.place(4,0,0,Kind.PIPE,EAST,charge(chargeTarget),null,bore);else w.place(4,0,0,Kind.PIPE,EAST,bore);
        for(int x=5;x<=7;x++){w.place(x,0,0,Kind.PIPE,EAST,bore);if(pair)w.place(x,0,1,Kind.PIPE,EAST,bore);}
        w.place(8,0,0,Kind.PIPE,EAST,bore);
        w.place(9,0,0,Kind.PIPE,EAST,inlet(first,1),null,bore);
        w.place(10,0,0,Kind.PIPE,EAST,bore);
        w.place(11,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,300,200000,water()),bore);
        w.runTo(800);
        var island=w.coordinator.snapshots().stream().filter(x->x.graph().pipes().stream().anyMatch(q->q.control() instanceof FlowControl.ControlValve)).findFirst().orElseThrow();
        var last=island.lastResult().orElseThrow();var g=w.worldGraph(island);var pipes=new java.util.ArrayList<com.wormzjl.icheme.science.fluid.network.PassiveNetwork.Pipe>();
        for(var q:g.pipes())pipes.add(q.control() instanceof FlowControl.ControlValve v&&PortPlan.COLUMN_SENSOR.equals(v.sensor())?q.withControl(inlet(second,0)):q);
        var next=new com.wormzjl.icheme.science.fluid.network.PassiveNetwork(g.reservoirs(),java.util.List.copyOf(pipes),g.scheduledTransfers());
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();var settings=com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver.Settings.defaults();
        System.out.println("SCRATCH == "+name+"; held in the world before: "+w.held.size()+"; standing "+com.wormzjl.icheme.science.fluid.network.ControlConstraints.standing(next));
        for(double seconds:new double[]{.1,1,5,20})for(boolean carried:new boolean[]{true,false}) {
            var solver=new RetainedSolver();if(carried)solver.committed(last);
            try{var r=solver.solve(model,next,seconds,settings,()->{});var text=new StringBuilder();
                for(int e=0;e<next.pipes().size();e++){var c=next.pipes().get(e).control();if(c instanceof FlowControl.ControlValve||c instanceof FlowControl.Machine)text.append(String.format(java.util.Locale.ROOT," %.4f kg/s %s;",r.averageMassFlows()[e],r.endpointModes().get(e)));}
                System.out.println("SCRATCH   "+(carried?"carried ":"fresh   ")+seconds+" s"+" ok: advanced "+r.advancedSeconds()+" s, substeps "+r.acceptedSubsteps()+" accepted "+r.rejectedSubsteps()+" rejected;"+text);}
            catch(RuntimeException e){System.out.println("SCRATCH   "+(carried?"carried ":"fresh   ")+seconds+" s"+" FAILED "+String.valueOf(e.getMessage()).replaceAll("reasons=.*",""));}
        }
    }

    private static void run(String name,boolean pair,double chargeTarget,double[] targets,double supply,boolean pump) {
        var w=new EventPathWorld();double bore=.1;var spec=new FluidDeviceSpec(1,300,supply,water());
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),spec,bore);
        w.place(1,0,0,Kind.PIPE,EAST,bore);
        if(pump)w.place(2,0,0,Kind.PUMP,EAST,new FlowControl.Machine(new CentrifugalPump(.02,50,.7,1475),1,true),null,bore);else w.place(2,0,0,Kind.PIPE,EAST,bore);
        w.place(3,0,0,Kind.PIPE,EAST,bore);
        if(chargeTarget>0)w.place(4,0,0,Kind.PIPE,EAST,charge(chargeTarget),null,bore);else w.place(4,0,0,Kind.PIPE,EAST,bore);
        for(int x=5;x<=7;x++){w.place(x,0,0,Kind.PIPE,EAST,bore);if(pair)w.place(x,0,1,Kind.PIPE,EAST,bore);}
        w.place(8,0,0,Kind.PIPE,EAST,bore);
        long feed=w.place(9,0,0,Kind.PIPE,EAST,inlet(0,1),null,bore);
        w.place(10,0,0,Kind.PIPE,EAST,bore);
        w.place(11,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,300,200000,water()),bore);
        long tick=400;w.runTo(tick);
        System.out.println("SCRATCH == "+name);
        for(double target:targets) {
            double plan=target;int before=w.held.size();
            LevelLoopBlanketStartTest.edit(w,feed,d->d.withControl(inlet(plan,0)),s->s);
            tick+=400;w.runTo(tick);
            var island=w.coordinator.snapshots().stream().filter(s->s.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.ControlValve)).findFirst().orElseThrow();
            var result=island.lastResult().orElseThrow();var graph=island.graph();
            var text=new StringBuilder();
            for(int e=0;e<graph.pipes().size();e++) {
                var c=graph.pipes().get(e).control();
                if(c instanceof FlowControl.ControlValve||c instanceof FlowControl.Machine)
                    text.append(String.format(java.util.Locale.ROOT," %s %.4f kg/s %s head=%.0f;",c instanceof FlowControl.Machine?"pump":PLAYER.equals(((FlowControl.ControlValve)c).sensor())?"charge":"inlet",
                            result.averageMassFlows()[e],result.endpointModes().get(e),result.endpointHeads()==null||e>=result.endpointHeads().length?Double.NaN:result.endpointHeads()[e]));
            }
            System.out.println("SCRATCH   inlet "+target+": status="+island.status().replaceAll("reasons=.*","")+" holds+"+(w.held.size()-before)+text);
        }
    }
}
