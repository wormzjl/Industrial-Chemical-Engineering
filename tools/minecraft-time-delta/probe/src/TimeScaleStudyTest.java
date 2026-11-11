package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.diagnostics.SolverDiagnostics;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Study of single hold-fuzz seeds under the time-scale probe (tools/minecraft-time-delta): each seed of {@code study.seeds}
 * runs alone after one warm-up seed, with the solver's step-attempt trace on and a sampler that reads the running thread's
 * stack every {@code study.sampleMs} (50). Per seed it writes the outcome, the step attempts (step, accepted, reason,
 * change), the hottest frames and the stalls: stretches of wall time in which no step attempt finished.
 * Compiled beside HoldFuzzTest (bundled or cold) by probe.init.gradle; not part of any suite.
 */
class TimeScaleStudyTest {
    record Sample(long nanos,long attempts,long iterations,StackTraceElement[] stack) {}

    @Test void study() throws Exception {
        var seeds=HoldFuzzTest.seeds(System.getProperty("study.seeds","15"));int slices=Integer.getInteger("holdFuzz.slices",120);
        long sampleMs=Long.getLong("study.sampleMs",50);var out=new StringBuilder();
        var entry=entry();
        entry.invoke(null,Long.getLong("study.warm",1L),slices,false);
        for(long seed:seeds) {
            SolverDiagnostics.reset();SolverDiagnostics.ENABLED=true;
            var runner=Thread.currentThread();var samples=Collections.synchronizedList(new ArrayList<Sample>());
            var sampler=new Thread(()->{
                try{while(true){Thread.sleep(sampleMs);samples.add(new Sample(System.nanoTime(),SolverDiagnostics.stepAttempts.sum(),SolverDiagnostics.newtonIterations.sum(),runner.getStackTrace()));}}
                catch(InterruptedException done){}
            });
            sampler.setDaemon(true);long started=System.nanoTime();sampler.start();
            var o=(HoldFuzzTest.Outcome)entry.invoke(null,seed,slices,true);
            sampler.interrupt();sampler.join();SolverDiagnostics.ENABLED=false;
            var sample=SolverDiagnostics.sample();
            out.append("==== STUDY seed=").append(seed).append(" ms=").append(o.millis()).append(" end=").append(o.finalTick()).append(" holds=").append(o.held().size())
                    .append(" exceptions=").append(o.exceptions().size()).append(" islands=").append(o.islands()).append('\n');
            out.append("layout ");for(var p:o.placements())out.append(p.tick()).append(':').append(p.block().label()).append(' ');out.append('\n');
            for(var h:o.held())out.append("HELD tick=").append(h.tick()).append(" island=").append(h.island()).append(" committed=").append(h.committed()).append(" status=").append(h.status()).append('\n');
            for(var e:o.exceptions())out.append("EXCEPTION ").append(e).append('\n');
            out.append("COUNTERS");
            for(var name:List.of("implicitSolves","activeSetPasses","newtonSolves","newtonIterations","jacobianBuilds","residualEvaluations","stepAttempts","stepAttemptsAccepted","equationGateRejections","restIntervals","phaseSeeds","phaseRefusals","newtonBacktracks"))
                try{out.append(' ').append(name).append('=').append(sample.value(name));}catch(IllegalArgumentException absent){}
            out.append('\n');
            // Step attempts: runs of the same outcome folded.
            var attempts=SolverDiagnostics.attempts();out.append("ATTEMPTS ").append(attempts.size()).append('\n');
            int shown=0;
            for(int i=0;i<attempts.size()&&shown<400;) {
                var a=attempts.get(i);int j=i;double least=a.step(),most=a.step();
                while(j+1<attempts.size()&&attempts.get(j+1).accepted()==a.accepted()&&Objects.equals(attempts.get(j+1).dominant(),a.dominant())){j++;least=Math.min(least,attempts.get(j).step());most=Math.max(most,attempts.get(j).step());}
                out.append(String.format(Locale.ROOT,"  #%d..%d x%d %s %s step=%.4g..%.4g change=%.3g%n",i,j,j-i+1,a.accepted()?"ok":"REJECT",a.dominant(),least,most,a.error()));
                i=j+1;shown++;
            }
            // Stalls: consecutive samples with no new step attempt.
            out.append("STALLS (no step attempt finished for at least 300 ms)\n");
            for(int i=0;i<samples.size();) {
                int j=i;while(j+1<samples.size()&&samples.get(j+1).attempts()==samples.get(i).attempts())j++;
                long ms=(samples.get(j).nanos()-samples.get(i).nanos())/1_000_000;
                if(ms>=300)out.append("  at ").append((samples.get(i).nanos()-started)/1_000_000).append(" ms for ").append(ms).append(" ms after attempt ").append(samples.get(i).attempts())
                        .append(", newton iterations ").append(samples.get(j).iterations()-samples.get(i).iterations()).append(", in ").append(leaf(samples.get((i+j)/2).stack())).append('\n');
                i=j+1;
            }
            // Hot frames: the deepest mod frame, and each mod method's inclusive share.
            var leaves=new HashMap<String,Integer>();var inclusive=new HashMap<String,Integer>();
            for(var s:samples) {
                leaves.merge(leaf(s.stack()),1,Integer::sum);
                var seen=new HashSet<String>();
                for(var f:s.stack())if(f.getClassName().startsWith("com.wormzjl.icheme.science")||f.getClassName().startsWith("com.wormzjl.icheme.runtime")){String k=shortName(f);if(seen.add(k))inclusive.merge(k,1,Integer::sum);}
            }
            out.append("SAMPLES ").append(samples.size()).append(" every ").append(sampleMs).append(" ms\nLEAF frames\n");top(out,leaves,samples.size(),12);
            out.append("INCLUSIVE methods\n");top(out,inclusive,samples.size(),45);
            // The stack of the middle of the longest stall.
            int best=-1;long longest=0;
            for(int i=0;i<samples.size();){int j=i;while(j+1<samples.size()&&samples.get(j+1).attempts()==samples.get(i).attempts())j++;long ms=samples.get(j).nanos()-samples.get(i).nanos();if(ms>longest){longest=ms;best=(i+j)/2;}i=j+1;}
            if(best>=0){out.append("STACK in the longest stall\n");int n=0;for(var f:samples.get(best).stack()){if(n++>=45)break;out.append("    ").append(f.getClassName().replace("com.wormzjl.icheme.","")).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber()).append('\n');}}
        }
        var path=Path.of(System.getProperty("study.out","build/time-scale-study.txt"));Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path,out.toString(),StandardCharsets.UTF_8);
        System.out.println("STUDY written "+path+" seeds="+seeds);
    }
    static java.lang.reflect.Method entry() throws Exception {
        java.lang.reflect.Method m;
        try{m=HoldFuzzTest.class.getDeclaredMethod("runSeed",long.class,int.class,boolean.class);}
        catch(NoSuchMethodException bundled){m=HoldFuzzTest.class.getDeclaredMethod("run",long.class,int.class,boolean.class);}
        m.setAccessible(true);return m;
    }
    static String shortName(StackTraceElement f){String c=f.getClassName();return c.substring(c.lastIndexOf('.')+1)+"."+f.getMethodName();}
    static String leaf(StackTraceElement[] stack) {
        for(var f:stack)if(f.getClassName().startsWith("com.wormzjl.icheme"))return shortName(f)+":"+f.getLineNumber();
        return stack.length==0?"(none)":stack[0].getClassName()+"."+stack[0].getMethodName();
    }
    static void top(StringBuilder out,Map<String,Integer> counts,int total,int limit) {
        counts.entrySet().stream().sorted((a,b)->b.getValue()-a.getValue()).limit(limit)
                .forEach(e->out.append(String.format(Locale.ROOT,"  %5.1f%% %s%n",100.0*e.getValue()/Math.max(1,total),e.getKey())));
    }
}
