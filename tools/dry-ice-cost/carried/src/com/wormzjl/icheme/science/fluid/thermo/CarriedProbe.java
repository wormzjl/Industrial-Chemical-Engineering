package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import java.util.Arrays;
import java.util.Locale;

/** Scratch (tmp/dry-ice-symptoms/run): mode (a) on the plan's probe states, against the competition after adopt. Basis N2, CH4, C2H6, CO2, water. */
public final class CarriedProbe {
    static FluidThermodynamics m;
    static String phases(EquilibriumResult r){
        if(r==null)return "null";
        if(!r.converged())return r.status()+" "+r.detail().substring(0,Math.min(110,r.detail().length()));
        StringBuilder b=new StringBuilder(r.classification().toString());
        for(var f:r.phases()){double[] a=f.amountsView();double s=0;for(double x:a)s+=x;b.append(String.format(Locale.ROOT," %s[%.5g co2=%.4g]",f.kind(),s,s>0?a[3]/s:0));}
        return b.toString();
    }
    static String adopted(double t,double p,double[] n,EquilibriumResult r){
        try{var s=m.adopt(t,p,n,r,null);return String.format(Locale.ROOT,"L=%.5g V=%.5g H=%.1f Vol=%.5g cso=%b",sum(s.liquid()),sum(s.vapor()),s.enthalpy()/sum(n),s.volume()/sum(n),m.condensedShareOnly(s));}
        catch(RuntimeException e){return "REFUSED "+e.getClass().getSimpleName();}
    }
    static double sum(double[] a){double s=0;for(double x:a)s+=x;return s;}
    static void tp(String label,double t,double p,double... n){
        double[] o=n.clone();
        long c0=System.nanoTime();var a=m.phaseEngine().tp(t,p,o,true);long c1=System.nanoTime();
        var b=m.phaseEngine().tp(t,p,o,PhaseCompetition.FLUID_CARRYING_CRYSTALS);long c2=System.nanoTime();
        var f=m.phaseEngine().tp(t,p,o,false);
        System.out.printf(Locale.ROOT,"%-34s T=%.2f P=%.0f%n  comp  %s kern=%d %.2fms%n        %s%n  carr  %s kern=%d %.2fms%n        %s%n  fluid %s%n",label,t,p,
            phases(a),a.diagnostics().kernelEvaluations(),(c1-c0)/1e6,adopted(t,p,o,a),phases(b),b.diagnostics().kernelEvaluations(),(c2-c1)/1e6,adopted(t,p,o,b),phases(f));
    }
    static double h(double t,double p,double[] n,PhaseCompetition c){var r=m.phaseEngine().tp(t,p,n,c);return r.converged()?r.enthalpy():Double.NaN;}
    /** H(T) of the carried engine answer and the adopted state along an isobar. */
    static void isobar(String label,double p,double t0,double t1,int steps,double... n){
        System.out.println("isobar "+label+" P="+p);
        double last=Double.NaN;
        for(int i=0;i<=steps;i++){double t=t0+(t1-t0)*i/steps;var r=m.phaseEngine().tp(t,p,n,PhaseCompetition.FLUID_CARRYING_CRYSTALS);var c=m.phaseEngine().tp(t,p,n,true);
            double e=r.converged()?r.enthalpy()/sum(n):Double.NaN;
            System.out.printf(Locale.ROOT,"  T=%.3f  dH=%10.2f H=%.2f  %s  | comp-adopted %s%n",t,e-last,e,phases(r),adopted(t,p,n,c));last=e;}
    }
    /** PH along H targets through the region; a jump is a refused answer or a T that does not move. */
    static void phRun(String label,double p,double t0,double t1,int steps,double... n){
        System.out.println("PH run "+label+" P="+p);
        double h0=h(t0,p,n,PhaseCompetition.FLUID_CARRYING_CRYSTALS),h1=h(t1,p,n,PhaseCompetition.FLUID_CARRYING_CRYSTALS);int bad=0;
        for(int i=0;i<=steps;i++){double target=h0+(h1-h0)*i/steps;var r=m.phaseEngine().ph(p,target,n,PhaseCompetition.FLUID_CARRYING_CRYSTALS);
            String s=r.converged()?String.format(Locale.ROOT,"T=%.4f kern=%d",r.phases().getFirst().temperature(),r.diagnostics().kernelEvaluations()):"REFUSED "+r.detail().substring(0,Math.min(120,r.detail().length()));
            if(!r.converged())bad++;
            System.out.printf(Locale.ROOT,"  h=%.1f %s %s%n",target/sum(n),s,r.converged()?phases(r):"");}
        System.out.println("  refused "+bad+" of "+(steps+1));
    }
    public static void main(String[] a) throws Exception{
        m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        // Basis N2, CH4, C2H6, CO2, H2O
        tp("LNG 112 K 101 kPa 3% CO2",112,101325,0.01,0.95,0.01,0.03,0);
        tp("LNG 112 K 101 kPa 2.8%",112.36,101200,0.0,0.97,0.002,0.028,0);
        tp("LNG 118 K 500 kPa 2% CO2",118,5e5,0.01,0.96,0.01,0.02,0);
        tp("LNG 114 K 1000 kPa 4% CO2",114,1e6,0.01,0.94,0.01,0.04,0);
        tp("LNG 150 K 2766 kPa 2.76%",149.9,2.766e6,0.01,0.95,0.0124,0.0276,0);
        tp("gas 138 K 541 kPa 0.14%",138.005,541000,0.02,0.97,0.0086,0.0014,0);
        tp("gas just above dew 145 K 541 kPa",145,541000,0.02,0.97,0.0086,0.0014,0);
        tp("gas below dew 134 K 541 kPa",134,541000,0.02,0.97,0.0086,0.0014,0);
        tp("gas below dew 130 K 541 kPa",130,541000,0.02,0.97,0.0086,0.0014,0);
        tp("CO2 let-down 40% 112 K 1 MPa",112,1e6,0.0,0.6,0.0,0.4,0);
        tp("CO2 let-down 40% 150 K 500 kPa",150,5e5,0.0,0.6,0.0,0.4,0);
        tp("CO2 let-down 40% 180 K 101 kPa",180,101325,0.0,0.6,0.0,0.4,0);
        tp("CO2 let-down 90% 190 K 101 kPa",190,101325,0.0,0.1,0.0,0.9,0);
        for(double t:new double[]{91,100,110,120})tp("G3F7 1% CO2",t,1e6,0,0.99,0,0.01,0);
        tp("N2/C2H6 5% CO2 95 K 2 MPa",95,2e6,0.5,0.0,0.45,0.05,0);
        isobar("0.14% CO2 dew",541000,125,142,17,0.02,0.97,0.0086,0.0014,0);
        isobar("2.8% CO2 dew",541000,125,150,25,0.02,0.95,0.002,0.028,0);
        phRun("0.14% across dew",541000,125,142,34,0.02,0.97,0.0086,0.0014,0);
        phRun("2.8% across dew",541000,125,150,50,0.02,0.95,0.002,0.028,0);
        phRun("40% CO2",1e6,120,260,40,0.0,0.6,0.0,0.4,0);
        phRun("LNG at 101 kPa",101325,105,135,30,0.01,0.95,0.01,0.03,0);
    }
}
