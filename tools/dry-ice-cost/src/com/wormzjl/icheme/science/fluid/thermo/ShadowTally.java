package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import java.util.*;

/**
 * Scratch (tmp/dry-ice-symptoms/run, measurement A): beside every competing step-level flash (equilibriumTP, equilibriumPH,
 * equilibriumUV, when the competition is on), the fluid-only flash of the same request is run in the shadow, after the
 * real answer was adopted, and tallied per call site: its outcome (adoptable, LIQUID_LIQUID, THREE_PHASES, other
 * refusal) and both costs. The run's own path is unchanged: the shadow answer is discarded. Report on exit.
 */
public final class ShadowTally {
    static final class Cell { long calls,competitionNanos,fluidNanos; final TreeMap<String,Integer> fluid=new TreeMap<>(),competition=new TreeMap<>(); }
    static final Map<String,Cell> CELLS=new TreeMap<>();
    static { Runtime.getRuntime().addShutdownHook(new Thread(()->System.out.println(report()))); }

    public static void tp(FluidThermodynamics m,double t,double p,double[] overall,EquilibriumResult real,long realNanos) {
        long t0=System.nanoTime();var shadow=m.phaseEngine().tp(t,p,overall,false);String outcome=outcome(m,shadow,t,p,overall);
        tally("TP",p,real,realNanos,outcome,System.nanoTime()-t0);
    }
    public static void ph(FluidThermodynamics m,double t,double p,double target,double[] overall,EquilibriumResult real,long realNanos) {
        long t0=System.nanoTime();var shadow=m.phaseEngine().ph(p,target,overall,false);
        String outcome=shadow.converged()&&!shadow.phases().isEmpty()?outcome(m,shadow,shadow.phases().getFirst().temperature(),p,overall):outcome(m,shadow,t,p,overall);
        tally("PH",p,real,realNanos,outcome,System.nanoTime()-t0);
    }
    public static void uv(FluidThermodynamics m,double u,double v,double[] overall,double t,double p,EquilibriumResult real,long realNanos) {
        long t0=System.nanoTime();var shadow=m.phaseEngine().crystalUv(u,v,overall,false,t,p);
        String outcome=shadow.converged()&&!shadow.phases().isEmpty()?outcome(m,shadow,shadow.phases().getFirst().temperature(),shadow.phases().getFirst().pressure(),overall):outcome(m,shadow,t,p,overall);
        tally("UV",p,real,realNanos,outcome,System.nanoTime()-t0);
    }
    /** The fluid-only answer's fate at the network's adapter: adopted (with its classification), or refused and why. */
    static String outcome(FluidThermodynamics m,EquilibriumResult shadow,double t,double p,double[] overall) {
        if(!shadow.converged()) {
            String d=shadow.detail()==null?"":shadow.detail();
            if(d.startsWith(EquilibriumResult.UNSTABLE_PRODUCT))return "refused:THREE_PHASES(unstable)";
            if(d.startsWith(EquilibriumResult.UNRESOLVED_PRODUCT))return "refused:THREE_PHASES(unresolved)";
            return "refused:"+shadow.status()+(shadow.diagnostics().criticalBand()?"(critical band)":"");
        }
        if(shadow.phases().isEmpty())return "refused:no phases";
        try{m.adopt(t,p,overall,shadow,null);return "adopted:"+shadow.classification();}
        catch(FluidThermodynamics.UnsupportedPhases hold){return "refused:"+hold.kind();}
        catch(RuntimeException other){return "refused:"+other.getClass().getSimpleName();}
    }
    static synchronized void tally(String spec,double p,EquilibriumResult real,long realNanos,String outcome,long shadowNanos) {
        String key=site()+" | "+spec+" | "+(p<=1.5e5?"P<=1.5bar":"P>1.5bar");
        var cell=CELLS.computeIfAbsent(key,k->new Cell());
        cell.calls++;cell.competitionNanos+=realNanos;cell.fluidNanos+=shadowNanos;
        cell.fluid.merge(outcome,1,Integer::sum);
        cell.competition.merge(real.converged()?"converged:"+real.classification():"failed:"+(real.detail()==null?real.status().toString():real.detail().substring(0,Math.min(40,real.detail().length()))),1,Integer::sum);
    }
    /** The caller: the first frame outside the adapter's entries, then its own caller, as Class.method < Class.method. */
    static String site() {
        var frames=Thread.currentThread().getStackTrace();var names=new ArrayList<String>();
        for(var f:frames) {
            String c=f.getClassName(),mth=f.getMethodName();
            if(c.equals(ShadowTally.class.getName())||c.equals(Thread.class.getName()))continue;
            if(c.endsWith("FluidThermodynamics")&&(mth.startsWith("equilibrium")||mth.equals("flashTP")||mth.equals("adopt")))continue;
            if(c.endsWith("NetworkPhaseEngine"))continue;
            names.add(c.substring(c.lastIndexOf('.')+1)+"."+mth);
            if(names.size()==2)break;
        }
        return String.join(" < ",names);
    }
    public static synchronized String report() {
        var b=new StringBuilder("SHADOW_TALLY site | spec | pressure : calls, competition ms (ms/call), fluid-only ms (ms/call), fluid outcomes, competition outcomes\n");
        for(var e:CELLS.entrySet()){var c=e.getValue();
            b.append(String.format(Locale.ROOT,"  %s : %d, %.1f (%.3f), %.1f (%.3f), %s, %s%n",e.getKey(),c.calls,c.competitionNanos/1e6,c.competitionNanos/1e6/c.calls,c.fluidNanos/1e6,c.fluidNanos/1e6/c.calls,c.fluid,c.competition));}
        return b.toString();
    }
}
