package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import java.util.Locale;

/**
 * S2c step 1 after the slot rule: on the code, by flashTP, every one-fluid state on a supercritical isotherm sits in the
 * liquid slot iff its reduced density (the network state's own, State.reducedDensity) is at least 1; and the states whose
 * slot moved from the identification parameter's rule (PIP > max(1, Z) on the same root), with those inside the old
 * pressure ranges (2 MPa for carbon dioxide, 10 MPa for nitrogen and methane) listed.
 */
public final class SlotAfterProbe {
    static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(CATALOG,"icheme:refinery");
    static double[] mix(Object... nameShare){double[] n=new double[MODEL.componentCount()];for(int k=0;k<nameShare.length;k+=2)n[MODEL.components().indexOf((String)nameShare[k])]=(Double)nameShare[k+1];return n;}
    static int[] count=new int[4];
    /** One state: counts {one-fluid dense states, disagreements with O2 A, moved from the old rule, moved inside old range}. */
    static void at(String name,double t,double p,double[] n,double oldMax,String where) {
        FluidThermodynamics.State s;
        try{s=MODEL.flashTP(t,p,n,()->{});}catch(RuntimeException refused){return;}
        int slot=s.oneFluidSlot();if(slot<0||!s.dense())return;
        count[0]++;
        boolean liquid=slot!=FluidThermodynamics.State.VAPOR;
        if(liquid!=(s.reducedDensity()>=1)){count[1]++;System.out.println(String.format(Locale.ROOT,"DISAGREE %s %s delta %.6f slot %d",name,where,s.reducedDensity(),slot));}
        var tr=s.phase(slot).traits();
        boolean before=PhaseIdentification.liquidLike(tr.identificationParameter(),tr.compressibility());
        if(before!=liquid){count[2]++;
            if(p<=oldMax){count[3]++;System.out.println(String.format(Locale.ROOT,"moved inside the old range: %s %s (%.1f K, %.3f MPa) delta %.4f PIP %.4f Z %.4f: %s -> %s",
                    name,where,t,p/1e6,s.reducedDensity(),tr.identificationParameter(),tr.compressibility(),before?"liquid":"vapour",liquid?"liquid":"vapour"));}
        }
    }
    static void report(String what){System.out.println(String.format(Locale.ROOT,"%s: dense one-fluid states %d, disagree with O2 A %d, moved from the old rule %d (%d inside the old range)",what,count[0],count[1],count[2],count[3]));count=new int[4];}
    public static void main(String[] args) {
        var pack=CATALOG.requirePackage("icheme:refinery");
        for(String name:new String[]{"Nitrogen","Methane","CarbonDioxide"}) {
            var pr=pack.properties().get(pack.components().indexOf(name)).pr();double tc=pr.criticalTemperature(),pc=pr.criticalPressure();
            double oldMax=name.equals("CarbonDioxide")?2e6:1e7;double[] n=mix(name,1.0);
            for(int a=0;a<=40;a++)for(int b=1;b<=90;b++){double tr=1+.05*a,prr=.1*b;at(name,tr*tc,prr*pc,n,oldMax,String.format(Locale.ROOT,"Tr %.2f Pr %.1f",tr,prr));}
            report("reduced grid "+name);
        }
        Object[][] cases={{"N2","Nitrogen",1.0},{"N2+1%CH4","Nitrogen",.99,"Methane",.01},{"CO2","CarbonDioxide",1.0},
                {"CO2+2%N2","CarbonDioxide",.98,"Nitrogen",.02},{"natural gas","Methane",.90,"Ethane",.06,"Propane",.03,"Nitrogen",.01}};
        for(var c:cases) {
            Object[] spec=new Object[c.length-1];System.arraycopy(c,1,spec,0,spec.length);double[] n=mix(spec);
            double oldMax=((String)c[0]).startsWith("CO2")?2e6:1e7;
            for(double t=100;t<=500;t+=10)for(double p=.5e6;p<=30e6;p+=.5e6)at((String)c[0],t,p,n,oldMax,"");
            report("T-P grid "+c[0]);
        }
    }
}
