package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Scratch (tmp/dry-ice-symptoms/run, 2026-11-02): HoldFuzzTest's world for one seed, Gradle-free, every island job timed
 * on its own with the solver counters' deltas, written as one TSV row per job; the island's state at every hold and at
 * every job over {@code probe.describeMs} (default 500 ms) goes to a side file. Same draws as HoldFuzzTest (its own
 * world(), placements(), place()); only the tick loop is copied, to time command.solve.
 *
 * <p>Arguments: seed, output prefix. System properties as HoldFuzzTest's (holdFuzz.cold, holdFuzz.slices,
 * holdFuzz.wallNanos, holdFuzz.softNanos, holdFuzz.defaults).
 */
final class DryIceProbe {
    public static void main(String[] args) throws Exception {
        long seed=Long.parseLong(args[0]);String prefix=args[1];int slices=Integer.getInteger("holdFuzz.slices",120);
        SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
        if(HoldFuzzTest.COLD)MaterialRuntime.with(HoldFuzzTest.CATALOG,HoldFuzzTest.PACKAGE,()->run(seed,slices,prefix));
        else run(seed,slices,prefix);
    }
    static final List<String> COUNTERS=SolverDiagnostics.names();
    static Object run(long seed,int slices,String prefix) {
        try(var jobs=Files.newBufferedWriter(Path.of(prefix+"-jobs.tsv"),StandardCharsets.UTF_8);
            var states=Files.newBufferedWriter(Path.of(prefix+"-states.txt"),StandardCharsets.UTF_8)) {
            var head=new StringBuilder("job\tonlineTick\tisland\tnodes\tpipes\tsliceStart\tsliceEnd\tsliceTicks\twallMs\tcpuMs\tepochStartMs\tepochEndMs\taccepted\trejected\tcommitted\tstatus");
            for(var n:COUNTERS)head.append('\t').append(n);
            // Last column: the job's hard checkpoints (ProcessSolveServices' token call per solver checkpoint: one per engine
            // kernel evaluation plus one per flash), a cost measure independent of the machine's load (CrystalFlushBudgetTest's).
            head.append("\tcheckpoints");
            jobs.write(head.toString());jobs.newLine();
            long[] checkpoints={0};long totalCheckpoints=0;
            var token=new com.wormzjl.icheme.runtime.BoundedCpuSolveService.CancellationToken(){public void throwIfCancellationRequested(){checkpoints[0]++;}};
            var r=new Random(seed);var blocks=HoldFuzzTest.world(r);var placements=HoldFuzzTest.placements(r,blocks);
            var layout=new StringBuilder("LAYOUT seed="+seed+' ');for(var p:placements)layout.append(p.tick()).append(':').append(p.block().label()).append(' ');
            states.write(layout.toString());states.newLine();System.out.println(layout);
            long describeMs=Long.getLong("probe.describeMs",500L);
            var w=new HoldFuzzTest.World(HoldFuzzTest.MODELS.get());long job=0;int reported=0;long started=System.nanoTime();
            long end=Long.MAX_VALUE;int placed=0;
            while(w.ledger.onlineTick()<end) {
                while(placed<placements.size()&&w.ledger.onlineTick()>=placements.get(placed).tick()) {
                    try{w.place(placements.get(placed).block());}catch(IllegalArgumentException invalid){states.write("REFUSED "+placements.get(placed).block().label()+": "+invalid.getMessage());states.newLine();}
                    placed++;
                    if(placed==placements.size())end=placements.getLast().tick()+100L*slices;
                }
                // HoldFuzzTest.World.tick, with each job timed.
                w.ledger.tick();w.coordinator.tick();
                if(w.ledger.hasPendingEvents()){w.registry.applyPending();w.coordinator.pumpIfUseful();}
                for(long request:List.copyOf(w.attempts.keySet())) {
                    var attempt=w.attempts.remove(request);var command=w.commands.remove(request);
                    var before=SolverDiagnostics.sample();long epoch0=System.currentTimeMillis();long t0=System.nanoTime();checkpoints[0]=0;
                    var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(token);
                    long nanos=System.nanoTime()-t0;long epoch1=System.currentTimeMillis();var after=SolverDiagnostics.sample();totalCheckpoints+=checkpoints[0];
                    var snapshot=w.coordinator.observe(attempt.islandId());
                    var row=new StringBuilder();job++;
                    row.append(job).append('\t').append(w.ledger.onlineTick()).append('\t').append(attempt.islandId()).append('\t').append(snapshot.graph().reservoirs().size()).append('\t').append(snapshot.graph().pipes().size())
                       .append('\t').append(attempt.slice().startTick()).append('\t').append(attempt.slice().endTick()).append('\t').append(attempt.slice().endTick()-attempt.slice().startTick())
                       .append('\t').append(String.format(Locale.ROOT,"%.3f",nanos/1e6)).append('\t').append(String.format(Locale.ROOT,"%.3f",result.workerCpuNanos()/1e6)).append('\t').append(epoch0).append('\t').append(epoch1)
                       .append('\t').append(result.candidate().map(c->c.acceptedSubsteps()).orElse(-1)).append('\t').append(result.candidate().map(c->c.rejectedSubsteps()).orElse(-1))
                       .append('\t').append(snapshot.clock().committedTick()).append('\t').append(result.detail()==null?"":result.detail().replace('\t',' ').replace('\n',' ').substring(0,Math.min(160,result.detail().length())));
                    for(var n:COUNTERS)row.append('\t').append(after.value(n)-before.value(n));
                    row.append('\t').append(checkpoints[0]);
                    jobs.write(row.toString());jobs.newLine();jobs.flush();
                    if(nanos>=describeMs*1_000_000L){states.write("JOB "+job+" tick="+w.ledger.onlineTick()+" wallMs="+String.format(Locale.ROOT,"%.1f",nanos/1e6)+" slice="+attempt.slice().startTick()+".."+attempt.slice().endTick()+" detail="+result.detail());states.newLine();states.write(describe(w,attempt.islandId()));states.flush();}
                    w.coordinator.completed(attempt,Optional.of(result));
                }
                w.coordinator.pump();
                if(w.held.size()>reported){for(var h:w.held.subList(reported,w.held.size())){states.write("HELD seed="+seed+" "+h);states.newLine();states.write(describe(w,h.island()));}states.flush();reported=w.held.size();}
            }
            long millis=(System.nanoTime()-started)/1_000_000;
            states.write("END seed="+seed+" ms="+millis+" holds="+w.held.size()+" jobs="+job+" checkpoints="+totalCheckpoints);states.newLine();
            for(var s:w.coordinator.observe())states.write(describe(w,s.id()));
            var sample=SolverDiagnostics.sample();var counters=new StringBuilder("COUNTERS");for(var n:COUNTERS)counters.append(' ').append(n).append('=').append(sample.value(n));
            states.write(counters.toString());states.newLine();
            System.out.println("END seed="+seed+" ms="+millis+" holds="+w.held.size()+" jobs="+job+" checkpoints="+totalCheckpoints);
            System.out.println(counters);
        } catch(IOException failure){throw new UncheckedIOException(failure);}
        return null;
    }
    /** HoldFuzzTest.World.describe plus each node's CO2 share, solids and crystals. */
    static String describe(HoldFuzzTest.World w,long island) {
        var s=w.coordinator.observe(island);var b=new StringBuilder("island "+island+" committed="+s.clock().committedTick()+" status="+s.status()+"\n");
        var g=s.graph();int co2=HoldFuzzTest.CO2;
        for(int i=0;i<g.reservoirs().size();i++) {
            var n=g.reservoirs().get(i);var st=n.state();double[] l=st.liquidView(),v=st.vaporView();double lt=0,vt=0;for(double x:l)lt+=x;for(double x:v)vt+=x;
            double solidKg=0;var crystals=new StringBuilder();
            for(var p:n.inventory().solids().populations()){solidKg+=p.massKg();crystals.append(p.material().id()).append('=').append(String.format(Locale.ROOT,"%.4g",p.massKg())).append("kg ");}
            b.append("    node ").append(i).append(" id=").append(n.id()).append(' ').append(n.kind()).append(" z=").append(n.elevation())
             .append(String.format(Locale.ROOT," P=%.1f T=%.3f m=%.4g liq=%.4g vap=%.4g water=%.3g",st.pressure(),st.temperature(),st.mass(),st.liquidVolume(),st.vaporVolume(),st.waterVolume()))
             .append(String.format(Locale.ROOT," molL=%.4g molV=%.4g xCO2=%.4g yCO2=%.4g",lt,vt,co2>=0&&lt>0?l[co2]/lt:0,co2>=0&&vt>0?v[co2]/vt:0))
             .append(" solidKg=").append(String.format(Locale.ROOT,"%.4g",solidKg)).append(' ').append(crystals).append('\n');
        }
        for(int i=0;i<g.pipes().size();i++){var p=g.pipes().get(i);
            b.append("    pipe ").append(i).append(' ').append(p.first()).append("->").append(p.second()).append(' ').append(p.control()).append(" d=").append(p.minimumArea()).append(" blocked=").append(p.blockedDirections()).append(" filter=").append(p.filter()!=null).append('\n');}
        s.lastResult().ifPresent(r->b.append("    last modes=").append(r.endpointModes()).append(" flows=").append(Arrays.toString(r.averageMassFlows())).append(" accepted=").append(r.acceptedSubsteps()).append(" rejected=").append(r.rejectedSubsteps()).append(" rejections=").append(r.rejectionReasons()).append('\n'));
        return b.toString();
    }
}
