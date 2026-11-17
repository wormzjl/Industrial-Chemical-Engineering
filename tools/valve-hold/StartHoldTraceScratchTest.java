package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Scratch (tools/valve-hold, never committed under src): the start hold's Newton trace. Needs trace.patch applied and
 * StartHoldScratchTest beside it (its line). Each case runs the dead-headed line, gives the valve its target on the island's
 * graph and solves 1 s once, dumps under tmp/start-hold/trace/<case>/.
 */
class StartHoldTraceScratchTest {
    @Test void traces() {
        StartHoldScratchTest.bore=0.11232481275267124;
        trace("a-carried",150000,250000,StartHoldScratchTest.STRONG,StartHoldScratchTest.TWO,true);
        trace("a-fresh",150000,250000,StartHoldScratchTest.STRONG,StartHoldScratchTest.TWO,false);
        trace("b-carried",150000,250000,StartHoldScratchTest.STRONG,1.05*StartHoldScratchTest.DESIGN,true);
        trace("e-carried",150000,250000,StartHoldScratchTest.WEAK,StartHoldScratchTest.TWO,true);
    }

    static void trace(String name,double supply,double sink,CentrifugalPump pump,double target,boolean carried) {
        var l=StartHoldScratchTest.line(supply,sink,pump,0);var w=l.w();w.runTo(800);
        var island=StartHoldScratchTest.island(w);var last=island.lastResult().orElseThrow();var g=w.worldGraph(island);
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var q:g.pipes())pipes.add(q.control() instanceof FlowControl.ControlValve?q.withControl(new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,target,.01,com.wormzjl.icheme.science.fluid.network.PortPlan.COLUMN_SENSOR,0)):q);
        var next=new PassiveNetwork(g.reservoirs(),List.copyOf(pipes),g.scheduledTransfers());
        var text=new StringBuilder();
        for(int e=0;e<next.pipes().size();e++){var p=next.pipes().get(e);text.append(" e").append(e).append('(').append(p.first()).append('>').append(p.second()).append(',').append(p.control().getClass().getSimpleName()).append(')');}
        var nodes=new StringBuilder();
        for(int n=0;n<next.reservoirs().size();n++){var r=next.reservoirs().get(n);nodes.append(String.format(Locale.ROOT," n%d %s %.4f kPa",n,r.kind(),r.state().pressure()/1e3));}
        System.out.println("SCRATCH == "+name+"; committed modes "+last.endpointModes()+"; edges"+text+"; nodes"+nodes);
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();var settings=com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver.Settings.defaults();
        var solver=new RetainedSolver();if(carried)solver.committed(last);
        SparseNewton.traceCount=0;SparseNewton.TRACE_DIR=Path.of(System.getProperty("user.dir"),"tmp","start-hold","trace",name);
        try{var r=solver.solve(model,next,1,settings,()->{});var t=new StringBuilder();
            for(int e=0;e<next.pipes().size();e++)t.append(String.format(Locale.ROOT," e%d %.4f kg/s %s;",e,r.averageMassFlows()[e],r.endpointModes().get(e)));
            System.out.println("SCRATCH   ok: substeps "+r.acceptedSubsteps()+"/"+r.rejectedSubsteps()+" rejected;"+t+" reasons "+r.rejectionReasons());}
        catch(RuntimeException e){System.out.println("SCRATCH   FAILED "+String.valueOf(e.getMessage()));}
        finally{SparseNewton.TRACE_DIR=null;System.out.println("SCRATCH   dumps "+SparseNewton.traceCount);}
    }
}
