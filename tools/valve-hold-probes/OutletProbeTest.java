package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/** Scratch probe: an outlet line's first flowing step, from the states the review world dumped. */
class OutletProbeTest {
    @Test void probe() throws Exception {
        var model=FluidTestSupport.networkModel();
        var lines=Files.readAllLines(Path.of("tmp/review/run2.log"));var seen=new HashSet<String>();var out=new StringBuilder();
        var pat=Pattern.compile("GENERATOR P=([0-9.E-]+) T=([0-9.E-]+) m=([0-9.E-]+) .* n=.(.*).$");
        for(int i=0;i<lines.size();i++){
            var m=Pattern.compile("HELD_DUMP island=([0-9]+)").matcher(lines.get(i));if(!m.find()||!seen.add(m.group(1)))continue;
            for(int k=i+1;k<i+6;k++){var g=pat.matcher(lines.get(k));if(!g.find())continue;
                double t=Double.parseDouble(g.group(2));var n=Arrays.stream(g.group(4).split(", ")).mapToDouble(Double::parseDouble).toArray();
                double mass=Double.parseDouble(g.group(3));
                for(double target:new double[]{.1,1}) {
                    var w=new EventPathWorld();double[] n2=new double[MaterialTestBasis.NETWORK+1];n2[MaterialTestBasis.NITROGEN]=1;double bore=.05;
                    w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,638.15,250000,n2),bore);
                    w.place(1,0,0,Kind.PIPE,EAST,bore);
                    w.place(2,0,0,Kind.PIPE,EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,0,new UUID(1,1),1),null,bore);
                    w.place(3,0,0,Kind.PIPE,EAST,bore);
                    w.place(4,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,298.15,101325,n2),bore);
                    w.runTo(200);
                    var graph=w.coordinator.snapshots().getFirst().graph();var solver=new PassiveIntervalSolver(model);
                    var rest=solver.solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
                    long gen=-1,valve=-1;var g2=rest.graph();
                    for(var node:g2.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.GENERATOR)gen=node.id();
                    for(var pipe:g2.pipes())if(pipe.control() instanceof FlowControl.ControlValve)valve=g2.reservoirs().get(pipe.first()).id();
                    var ported=new PassiveStepSolver(model).ports(g2,Map.of(gen,new PortPlan(gen,valve,target,t,n)),()->{});
                    String result;
                    try{var r=solver.solve(ported,5,PassiveIntervalSolver.Settings.defaults(),()->{},1);result="OK modes="+r.endpointModes()+" flows="+Arrays.toString(r.endpointFlows())+" rejected="+r.rejectedSubsteps();}
                    catch(RuntimeException e){result="HELD "+e.getMessage();}
                    {
                        var f=new EventPathWorld();var product=n.clone();
                        String fresh;
                        try{
                            f.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,t,250000,product),bore);
                            f.place(1,0,0,Kind.PIPE,EAST,bore);
                            f.place(2,0,0,Kind.PIPE,EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,target,new UUID(1,1),1),null,bore);
                            f.place(3,0,0,Kind.PIPE,EAST,bore);
                            f.place(4,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,298.15,101325,n2),bore);
                            f.runTo(200);
                            fresh=f.held.isEmpty()?"fresh OK "+f.coordinator.snapshots().getFirst().lastResult().map(r->r.endpointModes()+Arrays.toString(r.endpointFlows())).orElse("-"):"fresh HELD "+f.held.getFirst().status();
                        }catch(RuntimeException e){fresh="fresh threw "+e;}
                        out.append(m.group(1)).append(" ").append(fresh.length()>170?fresh.substring(0,170):fresh).append(System.lineSeparator());
                    }
                    out.append(m.group(1)).append(" T=").append(t).append(" rho=").append(mass).append(" target=").append(target).append(": ").append(result).append('\n');
                }
            }
        }
        Files.writeString(Path.of("tmp/review/outlet-probe.txt"),out.toString());
    }
    @Test void secondSlice() throws Exception {
        var model=FluidTestSupport.networkModel();var pat=Pattern.compile("GENERATOR P=([0-9.E-]+) T=([0-9.E-]+) m=([0-9.E-]+) .* n=.(.*).$");
        var first=new HashMap<String,double[]>();var tFirst=new HashMap<String,Double>();var second=new HashMap<String,double[]>();
        for(var file:List.of("tmp/review/run2.log","tmp/review/run3.log")){var lines=Files.readAllLines(Path.of(file));var map=file.contains("run2")?first:second;
            for(int i=0;i<lines.size();i++){var m=Pattern.compile("HELD_DUMP island=([0-9]+)").matcher(lines.get(i));if(!m.find()||map.containsKey(m.group(1)))continue;
                for(int k=i+1;k<i+6;k++){var g=pat.matcher(lines.get(k));if(g.find()){map.put(m.group(1),Arrays.stream(g.group(4).split(", ")).mapToDouble(Double::parseDouble).toArray());tFirst.put(m.group(1),Double.parseDouble(g.group(2)));}}}}
        var out=new StringBuilder();
        for(var id:new TreeSet<>(second.keySet()))for(String form:List.of("same","changed","steady")){
            double target=.2467,t=tFirst.get(id);double[] n2=new double[MaterialTestBasis.NETWORK+1];n2[MaterialTestBasis.NITROGEN]=1;double bore=.05;var w=new EventPathWorld();
            w.place(0,0,0,Kind.GENERATOR,EAST,new FlowControl.Passive(),new FluidDeviceSpec(1,638.15,250000,n2),bore);
            w.place(1,0,0,Kind.PIPE,EAST,bore);
            w.place(2,0,0,Kind.PIPE,EAST,new FlowControl.ControlValve(FlowControl.ValveSource.MASS_FLOW,0,new UUID(1,1),1),null,bore);
            w.place(3,0,0,Kind.PIPE,EAST,bore);
            w.place(4,0,0,Kind.VOID,EAST,new FluidDeviceSpec(1,298.15,101325,n2),bore);
            w.runTo(200);
            var solver=new PassiveIntervalSolver(model);var rest=solver.solve(w.coordinator.snapshots().getFirst().graph(),5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
            var g=rest.graph();long gen=-1,valve=-1;
            for(var node:g.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.GENERATOR)gen=node.id();
            for(var pipe:g.pipes())if(pipe.control() instanceof FlowControl.ControlValve)valve=g.reservoirs().get(pipe.first()).id();
            var a=first.getOrDefault(id,second.get(id));var b=form.equals("changed")?second.get(id):a;
            String result;
            try{
                var one=solver.solve(new PassiveStepSolver(model).ports(g,Map.of(gen,new PortPlan(gen,valve,target,t,a)),()->{}),4.5,PassiveIntervalSolver.Settings.defaults(),()->{},1);
                var two=solver.solve(new PassiveStepSolver(model).ports(one.graph(),Map.of(gen,new PortPlan(gen,valve,form.equals("steady")?target:target*1.3,t,b)),()->{}),5,PassiveIntervalSolver.Settings.defaults(),()->{},Math.max(.05,solver.nextStepEstimate()));
                result="OK "+two.endpointModes()+Arrays.toString(two.endpointFlows())+" rejected="+two.rejectedSubsteps();
            }catch(RuntimeException e){result="HELD "+e.getMessage();}
            out.append(id).append(' ').append(form).append(": ").append(result.length()>210?result.substring(0,210):result).append(System.lineSeparator());
        }
        Files.writeString(Path.of("tmp/review/second-slice.txt"),out.toString());
    }
}
