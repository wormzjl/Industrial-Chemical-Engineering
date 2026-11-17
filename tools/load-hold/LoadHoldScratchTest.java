package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Scratch (tools/load-hold, never committed under src): the review world's load hold (batch 2026-11-16-review-plant-sizing,
 * LOAD_HOLD_REVIEW.md). Loads the copy of review-plant-drive-flow's fluid checkpoint from tmp/load-hold/world/data, reloads
 * it as FluidWorldAuthority starts (EventPathWorld, one synchronous worker, factor 4), runs ticks and prints every job with
 * the solver counters it spent; then solves island 1402's first job again from the committed interval and from a fresh
 * solver under a residual-evaluation cap. Prints, asserts nothing.
 */
class LoadHoldScratchTest {
    static final long ISLAND=1402;
    static final long CAP=3_000_000;
    private static void out(String s){System.out.println("SCRATCH "+s);}
    private static String f(double v){return String.format(Locale.ROOT,"%.4f",v);}
    private static final String[] SHOWN={"activeSetPasses","implicitSolves","newtonSolves","newtonIterations","newtonBacktracks","residualEvaluations","jacobianBuilds","luFactorizations","stateCalls","flashCalls","phaseSeeds","phaseRefusals","stepAttempts","stepAttemptsAccepted","equationGateRejections","restIntervals"};
    private static Map<String,Long> sample(){return SolverDiagnostics.sample().counters();}
    private static String diff(Map<String,Long> a,Map<String,Long> b){var t=new StringBuilder();for(var k:SHOWN){long d=b.get(k)-a.get(k);if(d!=0)t.append(' ').append(k).append('=').append(d);}return t.toString();}

    /** Hashes of what a job reads and must not change: the committed result's arrays and states, the job graph's states, the reads. */
    static String fp(ProcessSolveJobs.FluidIslandCommand c,PassiveIntervalSolver.Result last) {
        return "committed flows "+java.util.Arrays.hashCode(last.averageMassFlows())+" end "+java.util.Arrays.hashCode(last.endpointFlows())+" heads "+java.util.Arrays.hashCode(last.endpointHeads())
                +" states "+states(last.graph())+" | job states "+states(c.snapshot())+" | reads "+c.reads().toString().hashCode()+" ports "+c.ports().toString().hashCode();
    }
    static int states(PassiveNetwork g){int h=1;for(var r:g.reservoirs()){var st=r.state();h=31*h+Double.hashCode(st.pressure());h=31*h+Double.hashCode(st.temperature());h=31*h+Double.hashCode(st.mass());h=31*h+java.util.Arrays.hashCode(st.vaporAmounts());h=31*h+java.util.Arrays.hashCode(st.liquidAmounts());h=31*h+java.util.Arrays.hashCode(st.aqueousAmounts());}return h;}
    static String summary(IslandCoordinator.Snapshot s) {
        var g=s.graph();var kinds=new TreeMap<String,Integer>();var controls=new TreeMap<String,Integer>();
        for(var r:g.reservoirs())kinds.merge(String.valueOf(r.kind()),1,Integer::sum);
        for(var p:g.pipes())controls.merge(p.control().getClass().getSimpleName(),1,Integer::sum);
        return "island "+s.id()+" committed="+s.clock().committedTick()+" status="+s.status().replaceAll("reasons=.*","")+" nodes="+g.reservoirs().size()+" "+kinds+" pipes="+g.pipes().size()+" "+controls
                +" lastResult="+s.lastResult().isPresent()+" certificate="+s.certificate().isPresent()+" clock="+s.clock();
    }

    @Test void loadHold() throws Exception {
        var time=SimulationTime.of(4);EventPathWorld.time=time;
        var model=FluidTestSupport.networkModel().withTime(time);
        var folder=Path.of(System.getProperty("user.dir"),"tmp","load-hold","world","data");
        var store=FluidCheckpointStore.directory(folder,Runnable::run,false);
        var loaded=FluidSavedData.read(store,key->key.on(model)).orElseThrow();
        out("route="+loaded.route()+" reason="+loaded.reason()+" islands="+loaded.checkpoint().islands().size()+" world="+loaded.world().isPresent());
        for(var e:loaded.checkpoint().islands())out("  saved "+e.dimension()+" "+e.packageId()+" compounds="+(e.compounds()==null?"whole":e.compounds().size())+" "+summary(e.snapshot()));
        var saved1402=loaded.checkpoint().islands().stream().filter(e->e.snapshot().id()==ISLAND).findFirst();
        saved1402.ifPresent(e->{var r=e.snapshot().lastResult();var g=e.snapshot().graph();
            if(r.isPresent())for(int p=0;p<g.pipes().size();p++){var q=g.pipes().get(p);if(!(q.control() instanceof FlowControl.Passive))out("    1402 pipe "+p+" "+q.first()+">"+q.second()+" "+q.control()+" flow="+f(r.get().averageMassFlows()[p])+" mode="+r.get().endpointModes().get(p));}
            for(int n=0;n<g.reservoirs().size();n++){var v=g.reservoirs().get(n);out("    1402 node "+n+" id="+v.id()+" "+v.kind()+" P="+f(v.state().pressure()/1e3)+" kPa T="+f(v.state().temperature())+" m="+f(v.state().mass())+" vapour="+f(v.state().vaporVolume()));}});

        exercise("final save",loaded.world().orElseThrow(),loaded.checkpoint().islands(),time);
        // Island 1402 as the save before the last wrote it (pack 3, generation 30: committed 9961), beside the final save's other islands.
        var coreTag=net.minecraft.nbt.NbtIo.readCompressed(folder.resolve(FluidCheckpointStore.CORE_NAME),net.minecraft.nbt.NbtAccounter.unlimitedHeap()).getCompound("data");
        var core=FluidCheckpointCodec.readCore(coreTag);
        var byKey=new java.util.HashMap<FluidCheckpointCodec.PackageKey,com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics>();
        for(var p:core.packages())byKey.put(p.key(),p.key().on(model));
        var row=core.islands().stream().filter(x->x.id()==ISLAND).findFirst().orElseThrow();
        byte[] pack3=java.nio.file.Files.readAllBytes(folder.resolve(FluidCheckpointStore.UNIT_DIRECTORY).resolve("fluid-3.pack"));
        int offset=2629,length=22474-2629;
        var row3=new FluidCheckpointCodec.IndexRow(ISLAND,4,30,row.online(),9961,0,row.cadence(),row.base(),row.since(),row.start(),row.horizon(),new FluidCheckpointCodec.UnitRef(3,offset,length,FluidCheckpointCodec.sha256(pack3,offset,length)));
        var old=FluidCheckpointCodec.island(pack3,row3,core.epoch(),core.strings(),byKey);
        out("pack 3: "+summary(old.snapshot()));
        // The trim law on paper at 9961 (LOAD_HOLD_REVIEW.md, "Built"): the pump's rise and the series valve's drop in the committed
        // interval, and the speed share whose curve gives the rise less the drop with 5 % left on the valve at the measured flow.
        {var r=old.snapshot().lastResult().orElseThrow();var g=r.graph();int pe=-1;for(int e=0;e<g.pipes().size();e++)if(g.pipes().get(e).control() instanceof FlowControl.Machine)pe=e;
            var m=(FlowControl.Machine)g.pipes().get(pe).control();var suction=g.reservoirs().get(g.pipes().get(pe).first()).state();double rho=suction.mass()/suction.volume();
            double q=r.endpointFlows()[pe]/rho,rise=r.endpointHeads()[pe],drop=ShaftTrains.seriesValveDrop(r,pe);
            double kappa=com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.suctionKappa(model,m,suction);
            double trim=com.wormzjl.icheme.science.machine.SpeedControl.requiredSpeed(m.law(),q,(rise-drop)/(1-com.wormzjl.icheme.science.machine.SpeedControl.VALVE_TRIM_MARGIN),rho,suction.pressure(),kappa,com.wormzjl.icheme.science.machine.Motor.LOWEST_SPEED,m.speed());
            out(String.format(Locale.ROOT,"    trim at 9961: speed %.4f, flow %.4f kg/s = %.5f m3/s (set %.5f), suction %.1f kPa, density %.2f, rise %.1f kPa (%.2f m), valve drop %.1f kPa (%.3f of the rise), trimmed speed %.4f (a step of %.4f)",
                    m.speed(),r.endpointFlows()[pe],q,m.setFlow(),suction.pressure()/1e3,rho,rise/1e3,rise/(rho*9.80665),drop/1e3,drop/rise,trim,trim-m.speed()));}
        {var g=old.snapshot().graph();var r=old.snapshot().lastResult().orElseThrow();for(int p=0;p<g.pipes().size();p++){var q=g.pipes().get(p);if(!(q.control() instanceof FlowControl.Passive)||q.heat()!=null)out("    9961 pipe "+p+" "+q.first()+">"+q.second()+" "+q.control()+" heat="+(q.heat()==null?"-":q.heat().getClass().getSimpleName()+" "+q.heat().shape())+" flow="+f(r.averageMassFlows()[p])+" mode="+r.endpointModes().get(p));}}
        {var g=loaded.checkpoint().islands().stream().filter(e->e.snapshot().id()==ISLAND).findFirst().orElseThrow().snapshot().graph();for(int p=0;p<g.pipes().size();p++){var q=g.pipes().get(p);if(q.heat()!=null)out("    13400 pipe "+p+" heat="+q.heat().getClass().getSimpleName()+" "+q.heat().shape());}}
        var mixed=new ArrayList<FluidCheckpointCodec.IslandEntry>();
        for(var e:loaded.checkpoint().islands())mixed.add(e.snapshot().id()==ISLAND?old:e);
        exercise("1402 at 9961",loaded.world().orElseThrow(),mixed,time);
    }

    static void exercise(String name,WorldTopologyLedger.Snapshot saved,List<FluidCheckpointCodec.IslandEntry> islands,SimulationTime time) {
        out("==== "+name);
        var w=new EventPathWorld(1,saved,islands);
        out("reloaded at online tick "+w.ledger.onlineTick());
        for(var s:w.coordinator.snapshots())out("  live "+summary(s));

        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        var first=new ArrayList<ProcessSolveJobs.FluidIslandCommand>();var lastFirst=new ArrayList<PassiveIntervalSolver.Result>();
        var job=new LinkedHashMap<Long,Long>();
        w.beforeSolves=()->{for(var a:w.attempts.entrySet()){job.put(a.getKey(),a.getValue().islandId());
            if(a.getValue().islandId()==ISLAND&&first.isEmpty()){first.add(w.commands.get(a.getKey()));w.coordinator.snapshots().stream().filter(s->s.id()==ISLAND).findFirst().flatMap(IslandCoordinator.Snapshot::lastResult).ifPresent(lastFirst::add);}}};
        int jobs1402=0;long start=w.ledger.onlineTick();
        while(w.ledger.onlineTick()<start+4000&&jobs1402<10) {
            int heldBefore=w.held.size();job.clear();
            var before=sample();
            var cmds=new LinkedHashMap<Long,ProcessSolveJobs.FluidIslandCommand>(w.commands);
            w.tick();
            var after=sample();
            for(var j:job.entrySet()){if(w.attempts.containsKey(j.getKey()))continue;var c=cmds.get(j.getKey());
                var s=w.coordinator.snapshots().stream().filter(x->x.id()==j.getValue()).findFirst().orElseThrow();
                out("t="+w.ledger.onlineTick()+" job island "+j.getValue()+" slice "+(c==null?"?":f(c.durationSeconds())+" s")+" reads="+(c==null?"?":c.reads().size())+" -> committed="+s.clock().committedTick()+" "+s.status().replaceAll("reasons=.*","").replaceAll("(.{220}).*","$1")+" |"+diff(before,after));
                if(j.getValue()==ISLAND)jobs1402++;}
            for(int i=heldBefore;i<w.held.size();i++)out("  HELD "+w.held.get(i));
        }
        for(var s:w.coordinator.snapshots())out("  end "+summary(s));
        // The trim law on paper: the pump's rise and the series valve's drop in the island's last live result (the drive's speed held: no shafts here).
        w.coordinator.snapshots().stream().filter(x->x.id()==ISLAND).findFirst().flatMap(IslandCoordinator.Snapshot::lastResult).ifPresent(r->{
            var g=r.graph();int pe=-1;for(int e=0;e<g.pipes().size();e++)if(g.pipes().get(e).control() instanceof FlowControl.Machine)pe=e;
            var m=(FlowControl.Machine)g.pipes().get(pe).control();var suction=g.reservoirs().get(g.pipes().get(pe).first()).state();double rho=suction.mass()/suction.volume();
            double q=r.endpointFlows()[pe]/rho,rise=r.endpointHeads()[pe],drop=ShaftTrains.seriesValveDrop(r,pe);
            var tmodel=com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel().withTime(time);
            double kappa=com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.suctionKappa(tmodel,m,suction);
            double trim=com.wormzjl.icheme.science.machine.SpeedControl.requiredSpeed(m.law(),q,(rise-drop)/(1-com.wormzjl.icheme.science.machine.SpeedControl.VALVE_TRIM_MARGIN),rho,suction.pressure(),kappa,com.wormzjl.icheme.science.machine.Motor.LOWEST_SPEED,m.speed());
            out(String.format(Locale.ROOT,"    trim, live: speed %.4f, flow %.4f kg/s = %.5f m3/s (set %.5f), suction %.1f kPa, density %.2f, rise %.1f kPa (%.2f m), valve drop %.1f kPa (%.3f of the rise), trimmed speed %.4f (a step of %.4f)",
                    m.speed(),r.endpointFlows()[pe],q,m.setFlow(),suction.pressure()/1e3,rho,rise/1e3,rise/(rho*9.80665),drop/1e3,drop/rise,trim,trim-m.speed()));});

        if(first.isEmpty()){out("no job of island "+ISLAND);return;}
        var c=first.getFirst();
        out("direct: island "+ISLAND+" first job, slice "+f(c.durationSeconds())+" s, reads "+c.reads().keySet()+", ports "+c.ports().keySet());
        for(boolean carried:new boolean[]{true,false})for(double seconds:new double[]{c.durationSeconds(),time.factor()/20.0}) {
            out("    fp before: "+fp(c,lastFirst.getFirst()));
            var solver=new RetainedSolver();if(carried&&!lastFirst.isEmpty())solver.committed(lastFirst.getFirst());
            SolverDiagnostics.reset();var before=sample();long cap=CAP;
            Runnable hard=()->{if(SolverDiagnostics.residualEvaluations.sum()>cap)throw new IllegalStateException("residual-evaluation cap "+cap);};
            String label=(carried?"carried":"fresh  ")+" "+f(seconds)+" s";
            try{var r=solver.solve(c.model(),c.snapshot(),seconds,c.settings(),hard,c.reads(),c.ports());
                var modes=new StringBuilder();var g=c.snapshot();
                for(int p=0;p<g.pipes().size();p++)if(!(g.pipes().get(p).control() instanceof FlowControl.Passive))modes.append(" e").append(p).append(' ').append(f(r.averageMassFlows()[p])).append(' ').append(r.endpointModes().get(p)).append(';');
                out("  "+label+": ok substeps "+r.acceptedSubsteps()+"/"+r.rejectedSubsteps()+" rejected, reasons "+r.rejectionReasons()+" |"+diff(before,sample())+" |"+modes);}
            catch(RuntimeException e){out("  "+label+": FAILED "+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage()).replaceAll("(.{400}).*","$1")+" |"+diff(before,sample()));}
            var attempts=SolverDiagnostics.attempts();
            out("    step attempts "+attempts.size()+(attempts.isEmpty()?"":" last "+attempts.subList(Math.max(0,attempts.size()-6),attempts.size())));
        }
        // The same job from the committed interval with only the pump's speed changed (an electric store's SPEED plan: 1 powered, 0 not).
        for(double factor:new double[]{1.001,1.01,1.2}) {
            var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(var q:c.snapshot().pipes())pipes.add(q.control() instanceof FlowControl.Machine m?q.withControl(m.withSpeed(m.speed()*factor)):q);
            var g=new PassiveNetwork(c.snapshot().reservoirs(),List.copyOf(pipes),c.snapshot().scheduledTransfers());
            out("    fp before: "+fp(c,lastFirst.getFirst()));
            var solver=new RetainedSolver();solver.committed(lastFirst.getFirst());
            SolverDiagnostics.reset();var before=sample();long cap=CAP;
            Runnable hard=()->{if(SolverDiagnostics.residualEvaluations.sum()>cap)throw new IllegalStateException("residual-evaluation cap "+cap);};
            String label="carried, pump speed x"+factor+", "+f(c.durationSeconds())+" s";
            try{var r=solver.solve(c.model(),g,c.durationSeconds(),c.settings(),hard,c.reads(),c.ports());
                var modes=new StringBuilder();
                for(int p=0;p<g.pipes().size();p++)if(!(g.pipes().get(p).control() instanceof FlowControl.Passive))modes.append(" e").append(p).append(' ').append(f(r.averageMassFlows()[p])).append(' ').append(r.endpointModes().get(p)).append(';');
                out("  "+label+": ok substeps "+r.acceptedSubsteps()+"/"+r.rejectedSubsteps()+" rejected, reasons "+r.rejectionReasons()+" |"+diff(before,sample())+" |"+modes);}
            catch(RuntimeException e){out("  "+label+": FAILED "+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage()).replaceAll("(.{400}).*","$1")+" |"+diff(before,sample()));}
        }
        // Which rebuilt object changes the path: the network object alone, the pump's pipe or the valve's pipe rebuilt with the same control, the valve's target moved 0.1 %.
        var variants=new LinkedHashMap<String,PassiveNetwork>();var s0=c.snapshot();
        variants.put("same object",s0);
        variants.put("network copied",new PassiveNetwork(new ArrayList<>(s0.reservoirs()),new ArrayList<>(s0.pipes()),s0.scheduledTransfers()));
        for(String which:new String[]{"pump pipe rebuilt","valve pipe rebuilt","valve target +0.1 %"}) {
            var pipes=new ArrayList<PassiveNetwork.Pipe>();
            for(var q:s0.pipes()) {
                if(which.startsWith("pump")&&q.control() instanceof FlowControl.Machine)pipes.add(q.withControl(q.control()));
                else if(which.startsWith("valve pipe")&&q.control() instanceof FlowControl.ControlValve)pipes.add(q.withControl(q.control()));
                else if(which.startsWith("valve target")&&q.control() instanceof FlowControl.ControlValve v)pipes.add(q.withControl(v.withMassTarget(v.massTarget()*1.001)));
                else pipes.add(q);
            }
            variants.put(which,new PassiveNetwork(s0.reservoirs(),List.copyOf(pipes),s0.scheduledTransfers()));
        }
        for(var v:variants.entrySet()) {
            var solver=new RetainedSolver();solver.committed(lastFirst.getFirst());
            SolverDiagnostics.reset();var before=sample();long cap=CAP;
            Runnable hard=()->{if(SolverDiagnostics.residualEvaluations.sum()>cap)throw new IllegalStateException("residual-evaluation cap "+cap);};
            try{var r=solver.solve(c.model(),v.getValue(),c.durationSeconds(),c.settings(),hard,c.reads(),c.ports());
                out("  variant "+v.getKey()+" (equal "+v.getValue().equals(s0)+"): ok substeps "+r.acceptedSubsteps()+"/"+r.rejectedSubsteps()+" |"+diff(before,sample()));}
            catch(RuntimeException e){out("  variant "+v.getKey()+": FAILED "+String.valueOf(e.getMessage()).replaceAll("(.{200}).*","$1")+" |"+diff(before,sample()));}
        }
        SolverDiagnostics.ENABLED=false;
    }
}
