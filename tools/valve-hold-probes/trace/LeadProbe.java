package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Scratch: replays a held job on the job path, from its own snapshot and committed interval, with one change each. */
final class LeadProbe {
    private static final Path ROOT=Path.of("tmp/trace");
    private static final ThreadLocal<Boolean> INSIDE=ThreadLocal.withInitial(()->false);
    private static final Set<String> SEEN=ConcurrentHashMap.newKeySet();
    private static synchronized void write(String file,String line){try{Files.writeString(ROOT.resolve(file),line+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(java.io.IOException e){throw new RuntimeException(e);}}
    private static int last(double[] n){int at=-1;for(int i=0;i<n.length-1;i++)if(n[i]>0)at=i;return at;}
    static String levels(PassiveNetwork g) {
        var b=new StringBuilder();
        for(var n:g.reservoirs()){if(!n.junction())continue;var a=PhaseLayout.totalAmounts(n.state());double t=0;for(double v:a)t+=v;int l=last(a);
            b.append(" node ").append(n.id()).append(" T=").append(n.state().temperature()).append(" P=").append(n.state().pressure()).append(" vap=").append(n.state().vaporVolume()).append(" last=").append(l).append(" x_last=").append(l<0?0:a[l]/t).append(" x_N2=").append(a[19]/t).append(" x_water=").append(a[20]/t);}
        return b.toString();
    }
    static void level(PassiveNetwork g,Map<Long,PortPlan> ports){if(!INSIDE.get()&&!ports.isEmpty())write("levels.txt",levels(g));}
    private static PassiveNetwork variant(FluidThermodynamics model,PassiveNetwork g,double dT,double trace) {
        var nodes=new ArrayList<>(g.reservoirs());
        for(int i=0;i<nodes.size();i++) {
            var n=nodes.get(i);if(!n.junction())continue;
            var s=n.state();double[] l=s.liquidView().clone(),v=s.vaporView().clone(),a=PhaseLayout.totalAmounts(s),moles=n.inventory().moles().clone();
            int at=last(a);double total=0,inv=0;for(double x:a)total+=x;for(double x:moles)inv+=x;double factor=inv/total;
            if(trace>=0&&at>=0){l[at]=trace*total;moles[at]=trace*total*factor;v[at]=0;}
            var state=model.state(s.temperature()+dT,s.pressure(),l,v,s.waterLiquid(),s.waterVapor(),s.hydrocarbonPartialPressure());
            nodes.set(i,new PassiveNetwork.Reservoir(n.id(),n.elevation(),state,n.kind(),new PassiveNetwork.Inventory(n.inventory().volume(),moles,state.internalEnergy()*factor,state.solids())));
        }
        return new PassiveNetwork(nodes,g.pipes(),g.scheduledTransfers());
    }
    private static String run(FluidThermodynamics model,PassiveNetwork g,PassiveIntervalSolver.Result committed,double duration,PassiveIntervalSolver.Settings settings,
                              Map<Long,ExchangerRelay.Read> reads,Map<Long,PortPlan> ports) {
        try {
            var solver=new RetainedSolver();solver.committed(committed);
            var r=solver.solve(model,g,duration,settings,()->{},reads,ports);
            return "OK flows="+Arrays.toString(r.endpointFlows())+" modes="+r.endpointModes()+levels(r.graph());
        } catch(RuntimeException failure){var m=String.valueOf(failure.getMessage());return "FAILED "+m.substring(0,Math.min(150,m.length()));}
    }
    static void run(FluidThermodynamics model,PassiveNetwork g,PassiveIntervalSolver.Result committed,double duration,PassiveIntervalSolver.Settings settings,
                    Map<Long,ExchangerRelay.Read> reads,Map<Long,PortPlan> ports,RuntimeException failure) {
        if(INSIDE.get()||ports.isEmpty())return;
        var m=String.valueOf(failure.getMessage());String type=m.contains("Singular")?"singular":m.contains("substep limit")?"limit":"other";
        var ids=new StringBuilder();for(var n:g.reservoirs())ids.append(n.id()).append(' ');
        if(!SEEN.add(type+ids))return;
        INSIDE.set(true);
        try {
            write("leads.txt","==== "+type+" nodes "+ids+" committed="+(committed!=null)+" duration="+duration+levels(g));
            write("leads.txt","failure: "+m.substring(0,Math.min(150,m.length())));
            write("leads.txt","control:     "+run(model,g,committed,duration,settings,reads,ports));
            var colder=new HashMap<Long,PortPlan>();
            for(var e:ports.entrySet()){var p=e.getValue();colder.put(e.getKey(),new PortPlan(p.node(),p.valve(),p.massTarget(),p.moles()==null?p.temperature():p.temperature()-20,p.moles()));}
            write("leads.txt","20K colder:  "+run(model,variant(model,g,-20,-1),committed,duration,settings,reads,colder));
            write("leads.txt","no trace:    "+run(model,variant(model,g,0,0),committed,duration,settings,reads,ports));
            write("leads.txt","trace 1e-8:  "+run(model,variant(model,g,0,1e-8),committed,duration,settings,reads,ports));
        } finally{INSIDE.set(false);}
    }
}
