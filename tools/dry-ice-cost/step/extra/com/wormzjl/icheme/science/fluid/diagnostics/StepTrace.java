package com.wormzjl.icheme.science.fluid.diagnostics;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Scratch (tmp/dry-ice-symptoms/run/step, 2026-11-02): read-only event trace of chosen island jobs. Every hook reads, none writes solver state. */
public final class StepTrace {
    private StepTrace() {}
    public static boolean on=false;
    private static PrintWriter out;static final Set<Integer> jobs=new HashSet<>();
    static {
        String spec=System.getProperty("step.jobs");String file=System.getProperty("step.out");
        if(spec!=null&&file!=null) {
            for(var part:spec.split(",")){var r=part.split("-");int a=Integer.parseInt(r[0]),b=Integer.parseInt(r[r.length-1]);for(int j=a;j<=b;j++)jobs.add(j);}
            try{out=new PrintWriter(Files.newBufferedWriter(Path.of(file)));}catch(IOException e){throw new UncheckedIOException(e);}
        }
    }
    static int job;static long last,attemptStart;static String stage="other";static final Map<String,long[]> stages=new TreeMap<>();
    static long newton0,solves0,jac0;
    public static void job(int n,int island,long sliceStart,long sliceEnd) {
        stagesOut();job=n;on=jobs.contains(n);
        if(on){out.printf(Locale.ROOT,"JOB %d island=%d slice=%d..%d%n",n,island,sliceStart,sliceEnd);out.flush();last=System.nanoTime();stages.clear();}
    }
    public static void jobEnd(){stagesOut();on=false;if(out!=null)out.flush();}
    private static void stagesOut() {
        if(!on)return;mark("end");var b=new StringBuilder("STAGES");
        for(var e:stages.entrySet())b.append(' ').append(e.getKey()).append('=').append(String.format(Locale.ROOT,"%.1fms/%d",e.getValue()[0]/1e6,e.getValue()[1]));
        out.println(b);stages.clear();
    }
    /** The time since the last mark goes to {@code name}. */
    public static void mark(String name){if(!on)return;long now=System.nanoTime();var a=stages.computeIfAbsent(name,k->new long[2]);a[0]+=now-last;a[1]++;last=now;}
    public static void run(double duration,double startingStep,String guard){if(on)out.printf(Locale.ROOT,"RUN duration=%.6g start=%.6g guard=%s%n",duration,startingStep,guard);}
    public static void attemptStart(int attempt,double elapsed,double step,double h) {
        if(!on)return;mark("between");attemptStart=System.nanoTime();
        newton0=SolverDiagnostics.newtonIterations.sum();solves0=SolverDiagnostics.implicitSolves.sum();jac0=SolverDiagnostics.jacobianBuilds.sum();
        out.printf(Locale.ROOT,"ATT %d t=%.6f step=%.6g h=%.6g%n",attempt,elapsed,step,h);
    }
    public static void attemptEnd(String outcome,double change,String note) {
        if(!on)return;
        out.printf(Locale.ROOT,"END %s change=%.5g ms=%.3f newton=%d solves=%d jac=%d note=%s%n",outcome,change,(System.nanoTime()-attemptStart)/1e6,
                SolverDiagnostics.newtonIterations.sum()-newton0,SolverDiagnostics.implicitSolves.sum()-solves0,SolverDiagnostics.jacobianBuilds.sum()-jac0,note==null?"":note.replace('\n',' '));
    }
    public static void solveStart(boolean rate,double dt){if(!on)return;mark("beforeSolve");out.printf(Locale.ROOT,"SOLVE rate=%b dt=%.6g%n",rate,dt);}
    public static void line(String text){if(on)out.println(text);}
    public static void mob(String tag,int edge,int a,int b,double flow,String reason,double v,double min,int donor,boolean active,double solidV,double liquidV,double totalV,double solidKg) {
        if(on)out.printf(Locale.ROOT,"MOB %s e=%d %d->%d q=%.5g %s v=%.5g vmin=%.5g donor=%d active=%b solidV=%.4g liqV=%.4g V=%.4g frac=%.4g kg=%.4g%n",tag,edge,a,b,flow,reason,v,min,donor,active,solidV,liquidV,totalV,solidV/totalV,solidKg);
    }
    public static void gate(double[] r,int[] offsets,int[] sizes,boolean[] junction,int edgeOffset) {
        if(!on)return;var order=new ArrayList<Integer>();for(int i=0;i<r.length;i++)order.add(i);order.sort((x,y)->Double.compare(Math.abs(r[y]),Math.abs(r[x])));var b=new StringBuilder("GATE");
        for(int k=0;k<Math.min(5,r.length);k++){int row=order.get(k);int node=-1;for(int n=0;n<offsets.length;n++)if(sizes[n]>0&&row>=offsets[n]&&row<offsets[n]+sizes[n])node=n;
            b.append(String.format(Locale.ROOT," [%s%d row %d of %d: %.4g]",node<0?"edge":(junction[node]?"j":"n"),node<0?row-edgeOffset:node,node<0?0:row-offsets[node],node<0?0:sizes[node],r[row]));}
        out.println(b);
    }
    public static void pass(int pass,String reason){if(on)out.println("PASS "+pass+" "+reason);}
    public static void newton(boolean ok,long before,String message){if(on)out.printf(Locale.ROOT,"NEWTON ok=%b its=%d %s%n",ok,SolverDiagnostics.newtonIterations.sum()-before,message==null?"":message);}
    public static long iterations(){return on?SolverDiagnostics.newtonIterations.sum():0;}
    public static void check(int node,boolean junction,double t,double p,int bits,boolean complete,int tp,int excluded,int proposed,int ph,int own,int refused,int added,boolean changed,long nanos) {
        if(on)out.printf(Locale.ROOT,"CHECK n=%d j=%b T=%.4f P=%.1f bits=%d complete=%b tp=%d excl=%d prop=%d ph=%d own=%d ref=%d add=%d changed=%b us=%.1f%n",node,junction,t,p,bits,complete,tp,excluded,proposed,ph,own,refused,added,changed,nanos/1e3);
    }
    public static long now(){return on?System.nanoTime():0;}
    public static void flash(String kind,long t0,double a,double b,double[] z,boolean crystals,String classification,boolean converged,int kernel) {
        if(!on)return;long nanos=System.nanoTime()-t0;
        var site=new StringBuilder();
        StackWalker.getInstance().forEach(f->{if(site.length()<160&&f.getClassName().contains("fluid.")&&!f.getClassName().endsWith("StepTrace")&&!f.getClassName().endsWith("NetworkPhaseEngine")&&!f.getMethodName().startsWith("lambda"))site.append(f.getMethodName()).append('<');});
        double total=0;for(double x:z)total+=x;var c=new StringBuilder();for(double x:z)c.append(String.format(Locale.ROOT,"%.6e,",x/total));
        out.printf(Locale.ROOT,"FLASH %s comp=%b a=%.7e b=%.7e n=%.6e z=%s cls=%s conv=%b kernel=%d us=%.1f site=%s%n",kind,crystals,a,b,total,c,classification,converged,kernel,nanos/1e3,site);
    }
    /** The worst vessel of the state-change measure, by the rule of PassiveIntervalSolver.stateChange. */
    public static void change(PassiveNetwork before,List<FluidThermodynamics.State> after,double[] flows,double dt,double massReferenceDensity) {
        if(!on)return;double dp=0,dm=0;int ip=-1,im=-1;
        for(int i=0;i<after.size();i++) {
            var node=before.reservoirs().get(i);if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
            var a=node.state();var b=after.get(i);double capacity=node.inventory().volume();double mass=node.empty()?0:a.mass();
            double volume=!node.empty()&&com.wormzjl.icheme.science.fluid.solver.PhaseLayout.taut(a,node.inventory().wallCapacity())?a.volume():capacity;
            double p=Math.abs(b.pressure()-a.pressure())/Math.max(100,a.pressure()),m=Math.abs(b.mass()-mass)/Math.max(1e-12,Math.max(mass,massReferenceDensity*volume));
            if(p>dp){dp=p;ip=i;}if(m>dm){dm=m;im=i;}
        }
        out.printf(Locale.ROOT,"CHANGE dP=%.5g at %d dm=%.5g at %d%n",dp,ip,dm,im);
        nodes(before,after,flows);
    }
    private static void nodes(PassiveNetwork graph,List<FluidThermodynamics.State> states,double[] flows) {
        for(int i=0;i<states.size();i++){var n=graph.reservoirs().get(i);var s=states.get(i);if(n.fixed())continue;double l=0,v=0;double[] x=s.liquidView(),y=s.vaporView();for(double q:x)l+=q;for(double q:y)v+=q;
            int co2=x.length-1;double c=l>0?x[co2]:0,cv=v>0?y[co2]:0;
            out.printf(Locale.ROOT,"NODE %d %s T=%.4f P=%.1f m=%.5g V=%.5g vl=%.5g vv=%.5g nl=%.5g nv=%.5g co2l=%.5g co2v=%.5g solid=%.4g bits=%d%n",i,n.kind(),s.temperature(),s.pressure(),s.mass(),s.volume(),s.liquidVolume(),s.vaporVolume(),l,v,c,cv,s.solidMoments().mass(),FluidThermodynamics.phaseBits(s));}
        var b=new StringBuilder("FLOWS");for(double q:flows)b.append(String.format(Locale.ROOT," %.4g",q));out.println(b);
    }
}
