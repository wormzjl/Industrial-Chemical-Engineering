package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * Scratch (tools/valve-hold, never committed under src): mechanism M2's Newton trace. Needs trace.patch applied. Each case
 * solves the second target once on the island's graph and writes one dump per Newton solve under tmp/valve-hold-trace/<case>/.
 */
class ValveHoldTraceScratchTest {
    private static final UUID PLAYER=new UUID(1,1);
    private static double[] water(){double[] n=new double[MaterialTestBasis.NETWORK+1];n[MaterialTestBasis.NETWORK]=1;return n;}
    private static FlowControl.ControlValve charge(double t){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,t,PLAYER,1);}
    private static FlowControl.ControlValve inlet(double target,long revision){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,target,.01,PortPlan.COLUMN_SENSOR,revision);}

    @Test void traces() {
        trace("a2-fresh",true,5,0,10,200000,true,false,1);
        trace("a2-carried",true,5,0,10,200000,true,true,1);
        trace("nopair-fresh",false,5,0,10,200000,true,false,1);
        trace("h2-fresh",true,30,0,40,200000,true,false,1);
        trace("l-carried",false,0,3,60,200000,true,true,1);
    }

    private static void trace(String name,boolean pair,double chargeTarget,double first,double second,double supply,boolean pump,boolean carried,double seconds) {
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
        var text0=new StringBuilder();
        for(int e=0;e<next.pipes().size();e++){var p=next.pipes().get(e);text0.append(" e").append(e).append('(').append(p.first()).append('>').append(p.second()).append(',').append(p.control().getClass().getSimpleName()).append(')');}
        System.out.println("SCRATCH == "+name+"; held before: "+w.held.size()+"; committed modes "+last.endpointModes()+"; edges"+text0);
        var solver=new RetainedSolver();if(carried)solver.committed(last);
        SparseNewton.traceCount=0;SparseNewton.TRACE_DIR=Path.of(System.getProperty("user.dir"),"tmp","valve-hold-trace",name);
        try{var r=solver.solve(model,next,seconds,settings,()->{});var text=new StringBuilder();
            for(int e=0;e<next.pipes().size();e++)text.append(String.format(java.util.Locale.ROOT," e%d %.4f kg/s %s;",e,r.averageMassFlows()[e],r.endpointModes().get(e)));
            System.out.println("SCRATCH   "+(carried?"carried ":"fresh   ")+seconds+" s ok: advanced "+r.advancedSeconds()+" s, substeps "+r.acceptedSubsteps()+" accepted "+r.rejectedSubsteps()+" rejected;"+text);}
        catch(RuntimeException e){System.out.println("SCRATCH   "+(carried?"carried ":"fresh   ")+seconds+" s FAILED "+String.valueOf(e.getMessage()).replaceAll("reasons=.*",""));}
        finally{SparseNewton.TRACE_DIR=null;System.out.println("SCRATCH   dumps "+SparseNewton.traceCount);}
    }
}
