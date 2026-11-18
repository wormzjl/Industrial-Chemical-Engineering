package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * Scratch (tools/valve-hold, never committed under src): the review world's hold at the column's start, on a small line.
 * Supply of Tia Juana crude at 300 K, a centrifugal pump on its law at full speed, a discharge run, a column-sensor flow
 * valve shut while the pump dead-heads, then given its target; a void. Prints, asserts nothing.
 */
class StartHoldScratchTest {
    static final double T=300,DESIGN=159.42,TWO=318.85,SUCTION_BORE=.4,PUMP_BORE=.3;
    static final int RUN=6;
    static final CentrifugalPump STRONG=new CentrifugalPump(.20279,170.98,.75,2950),WEAK=new CentrifugalPump(.21206,100,.75,2950);
    static double bore=.1;
    private static double[] crude(){return Arrays.copyOf(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane_nitrogen").crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions(),MaterialTestBasis.NETWORK+1);}
    private static FlowControl.ControlValve inlet(double target,long revision){return new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,200000,target,.01,PortPlan.COLUMN_SENSOR,revision);}
    private static void out(String s){System.out.println("SCRATCH "+s);}
    private static String f(double v){return String.format(Locale.ROOT,"%.4f",v);}

    record Line(EventPathWorld w,long valve){}

    /** Generator, suction pipe, pump (or a pipe), RUN pipes of the run's bore, the flow valve, a pipe, a void. */
    static Line line(double supply,double sink,CentrifugalPump pump,double target) {
        var w=new EventPathWorld();double[] x=crude();
        w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,T,supply,x.clone()),SUCTION_BORE);
        w.place(1,0,0,Kind.PIPE,EAST,SUCTION_BORE);
        if(pump!=null)w.place(2,0,0,Kind.PUMP,EAST,new FlowControl.Machine(pump,1,true),null,PUMP_BORE);else w.place(2,0,0,Kind.PIPE,EAST,PUMP_BORE);
        for(int i=0;i<RUN;i++)w.place(3+i,0,0,Kind.PIPE,EAST,bore);
        long valve=w.place(3+RUN,0,0,Kind.PIPE,EAST,inlet(target,1),null,bore);
        w.place(4+RUN,0,0,Kind.PIPE,EAST,bore);
        w.place(5+RUN,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,T,sink,x.clone()),bore);
        return new Line(w,valve);
    }
    static IslandCoordinator.Snapshot island(EventPathWorld w){return w.coordinator.snapshots().stream().filter(s->s.graph().pipes().stream().anyMatch(p->p.control() instanceof FlowControl.ControlValve)).findFirst().orElseThrow();}
    static int edge(PassiveNetwork g,Class<?> type){for(int e=0;e<g.pipes().size();e++)if(type.isInstance(g.pipes().get(e).control()))return e;return -1;}

    /** One line of the island's last committed interval. */
    static String state(IslandCoordinator.Snapshot s) {
        var r=s.lastResult().orElse(null);if(r==null)return "status="+s.status()+" (no result)";
        var g=s.graph();int v=edge(g,FlowControl.ControlValve.class),p=edge(g,FlowControl.Machine.class);var rg=r.graph();var b=new StringBuilder();
        b.append("committed=").append(s.clock().committedTick()).append(" status=").append(s.status().replaceAll("reasons=.*",""));
        b.append(" | valve ").append(f(r.averageMassFlows()[v])).append(" kg/s ").append(r.endpointModes().get(v));
        if(p>=0){var pp=rg.pipes().get(p);b.append(" | pump ").append(f(r.averageMassFlows()[p])).append(" kg/s ").append(r.endpointModes().get(p))
                .append(" suction ").append(f(rg.reservoirs().get(pp.first()).state().pressure()/1e3)).append(" kPa discharge ").append(f(rg.reservoirs().get(pp.second()).state().pressure()/1e3)).append(" kPa");}
        var vp=rg.pipes().get(v);b.append(" | valve in ").append(f(rg.reservoirs().get(vp.first()).state().pressure()/1e3)).append(" out ").append(f(rg.reservoirs().get(vp.second()).state().pressure()/1e3)).append(" kPa");
        return b.toString();
    }

    /** The open line's flow: the valve placed with a target far above it (starts open), run to settle. */
    static double open(double supply,double sink,CentrifugalPump pump) {
        var l=line(supply,sink,pump,1e4);l.w.runTo(600);var s=island(l.w);var r=s.lastResult().orElseThrow();
        out("  open supply "+supply/1e3+" sink "+sink/1e3+" pump "+(pump==null?"none":pump.ratedHead()+" m")+" bore "+bore+": held "+l.w.held.size()+"; "+state(s));
        return r.averageMassFlows()[edge(s.graph(),FlowControl.ControlValve.class)];
    }

    @Test void startHold() {
        // The fluid.
        {var w=new EventPathWorld();double[] x=crude();
            var st=w.model.equilibriumTP(T,150000,x.clone(),()->{},null);
            out("crude at 300 K, 150 kPa: density "+f(st.mass()/st.volume())+" kg/m3, vapour volume "+st.vaporVolume());
            double lo=1000,hi=150000;
            for(int k=0;k<50;k++){double mid=Math.sqrt(lo*hi);boolean split;
                try{split=w.model.equilibriumTP(T,mid,x.clone(),()->{},null).vaporVolume()>0;}catch(RuntimeException two){split=true;}
                if(split)lo=mid;else hi=mid;}
            out("crude bubble pressure at 300 K (TP split edge): "+f(hi/1e3)+" kPa");}
        // Bore: the open line without a pump passes 2 x design on 7.5 bar (1000 to 250 kPa).
        double b0=.1,q0=open(1e6,250000,null);bore=.12;double b1=bore,q1=open(1e6,250000,null);
        for(int k=0;k<3;k++){double slope=Math.log(q1/q0)/Math.log(b1/b0);double next=b1*Math.exp(Math.log(TWO/q1)/slope);b0=b1;q0=q1;b1=next;bore=next;q1=open(1e6,250000,null);}
        out("bore chosen "+bore+" m: open flow on 7.5 bar "+f(q1)+" kg/s (target "+TWO+")");
        // The line's loss: the drop that passes 1.05, 1.9 and 2 x design on the quadratic, measured.
        for(double r:new double[]{1.05,1.9,2.0}){double dp=750000*(r/2)*(r/2)*(TWO/q1)*(TWO/q1);double q=open(250000+dp,250000,null);out("  drop "+f(dp/1e3)+" kPa passes "+f(q)+" kg/s ("+f(q/DESIGN)+" x design)");}
        // The open flows with the pump.
        double openA=open(150000,250000,STRONG),openE=open(150000,250000,WEAK),openD=open(1e6,250000,STRONG),openF=open(400000,500000,STRONG);
        out("open flows: a "+f(openA)+", e "+f(openE)+", d "+f(openD)+", f "+f(openF)+" kg/s");
        runCase("a supply 150, strong, target 2x",150000,250000,STRONG,TWO);
        runCase("b supply 150, strong, target 1.05x",150000,250000,STRONG,1.05*DESIGN);
        runCase("c1 supply 150, strong, target 0.95 open",150000,250000,STRONG,.95*openA);
        runCase("c2 supply 150, strong, target 1.05 open",150000,250000,STRONG,1.05*openA);
        runCase("d supply 1000, strong, target 2x",1e6,250000,STRONG,TWO);
        runCase("e supply 150, weak, target 2x",150000,250000,WEAK,TWO);
        runCase("f supply 400, sink 500, strong, target 2x",400000,500000,STRONG,TWO);
    }

    /** Dead-headed start (valve shut, pump running), the target given by an edit, slices printed; then the target solved directly, carried and fresh. */
    static void runCase(String name,double supply,double sink,CentrifugalPump pump,double target) {
        out("== "+name+" ("+f(target)+" kg/s)");
        var l=line(supply,sink,pump,0);var w=l.w;w.runTo(800);
        var before=island(w);out("  dead-headed: held "+w.held.size()+"; "+state(before));
        var last=before.lastResult().orElseThrow();var g=w.worldGraph(before);
        // Direct solves of the target on the dead-headed island's graph.
        var pipes=new ArrayList<PassiveNetwork.Pipe>();
        for(var q:g.pipes())pipes.add(q.control() instanceof FlowControl.ControlValve?q.withControl(inlet(target,0)):q);
        var next=new PassiveNetwork(g.reservoirs(),List.copyOf(pipes),g.scheduledTransfers());
        var model=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel();var settings=com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver.Settings.defaults();
        int v=edge(next,FlowControl.ControlValve.class),p=edge(next,FlowControl.Machine.class);
        for(double seconds:new double[]{1,5})for(boolean carried:new boolean[]{true,false}) {
            var solver=new RetainedSolver();if(carried)solver.committed(last);
            try{var r=solver.solve(model,next,seconds,settings,()->{});var rg=r.graph();
                String pumpText=p<0?"":" pump "+f(r.averageMassFlows()[p])+" "+r.endpointModes().get(p)+" suction "+f(rg.reservoirs().get(rg.pipes().get(p).first()).state().pressure()/1e3)+" discharge "+f(rg.reservoirs().get(rg.pipes().get(p).second()).state().pressure()/1e3)+" kPa";
                out("  direct "+(carried?"carried":"fresh  ")+" "+seconds+" s: ok, substeps "+r.acceptedSubsteps()+"/"+r.rejectedSubsteps()+" rejected; valve "+f(r.averageMassFlows()[v])+" "+r.endpointModes().get(v)+";"+pumpText);}
            catch(RuntimeException e){out("  direct "+(carried?"carried":"fresh  ")+" "+seconds+" s: FAILED "+String.valueOf(e.getMessage()).replaceAll("reasons=.*",""));}
        }
        // The world: the target by an edit, each committed interval printed.
        int held=w.held.size();
        LevelLoopBlanketStartTest.edit(w,l.valve,d->d.withControl(inlet(target,0)),s->s);
        long seen=island(w).clock().committedTick(),until=w.ledger.onlineTick()+800;
        while(w.ledger.onlineTick()<until){w.tick();var s=island(w);if(s.clock().committedTick()!=seen||!s.status().equals(before.status())){seen=s.clock().committedTick();out("  world t="+w.ledger.onlineTick()+" holds+"+(w.held.size()-held)+" "+state(s));before=s;}}
        for(int i=held;i<w.held.size();i++)out("  HELD "+w.held.get(i));
    }
}
