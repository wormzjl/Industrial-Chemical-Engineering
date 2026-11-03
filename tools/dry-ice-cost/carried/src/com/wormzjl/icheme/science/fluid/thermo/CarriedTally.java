package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Scratch (tmp/dry-ice-symptoms/run): engine answers of the step-level flashes by kind and outcome; printed at exit to -Dcarried.tally=<file>. The site is the caller frame. */
public final class CarriedTally {
    static final Map<String,LongAdder> COUNTS=new ConcurrentHashMap<>();
    static final Map<String,double[]> SAMPLES=new ConcurrentHashMap<>();
    static final String FILE=System.getProperty("carried.tally");
    static {if(FILE!=null)Runtime.getRuntime().addShutdownHook(new Thread(CarriedTally::print));}
    static void print(){try(var out=new java.io.PrintWriter(FILE,java.nio.charset.StandardCharsets.UTF_8)){for(var e:new TreeMap<>(COUNTS).entrySet()){var s=SAMPLES.get(e.getKey());out.printf(Locale.ROOT,"%-58s %9d %s%n",e.getKey(),e.getValue().sum(),s==null?"":String.format(Locale.ROOT,"e.g. T=%.3f P=%.0f z=[%.4f %.4f %.4f %.4f %.4f]",s[0],s[1],s[2],s[3],s[4],s[5],s[6]));}}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    static String site(){return StackWalker.getInstance().walk(f->f.skip(2).filter(x->!x.getClassName().endsWith("FluidThermodynamics")&&!x.getClassName().endsWith("CarriedTally")).findFirst().map(x->x.getMethodName()).orElse("?"));}
    public static void note(String kind,double t,double p,double[] overall,EquilibriumResult r){
        if(FILE==null)return;
        String outcome=r.converged()?r.classification().toString():r.detail().split("[ :]")[0];
        if(outcome.startsWith("the"))outcome=r.detail().contains("step of")?"STEP":r.detail().substring(0,Math.min(30,r.detail().length()));
        String key=kind+" "+site()+" "+outcome;
        COUNTS.computeIfAbsent(key,x->new LongAdder()).increment();
        if(!r.converged())SAMPLES.computeIfAbsent(key,x->{double n=0;for(double v:overall)n+=v;double[] s=new double[2+overall.length];s[0]=t;s[1]=p;for(int i=0;i<overall.length&&i<5;i++)s[2+i]=overall[i]/n;return s;});
    }
}
