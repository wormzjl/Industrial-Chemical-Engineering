package com.wormzjl.icheme.science.fluid.thermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.*;
import java.util.*;
/** Scratch: the spine grid's refused state on the carried form, with the fluid answer and the competition beside it. */
public final class CarriedGrid {
    public static void main(String[] a){
        var m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        double[][] feeds={{1,0,0,0,0},{0,1,0,0,0},{0,0,1,0,0},{0,0,0,1,0},{.5,.5,0,0,0},{.1,.7,.2,0,0},{0,.8,0,.2,0},{.2,0,0,.8,0},{0,0,.6,.4,0},{.05,.8,.1,.05,0},{.1,.6,.1,.1,.1}};
        double[] ts={80,100,120,150,180,230,260,300,400,700,1100},ps={1e5,3e5,1e6,2e6,5e6,1e7};
        for(double t:ts)for(double p:ps)for(double[] f:feeds){
            try{m.flashTP(t,p,f,()->{});}
            catch(RuntimeException e){
                System.out.println(t+" K "+p+" Pa "+Arrays.toString(f)+": "+e.getClass().getSimpleName()+" "+e.getMessage().substring(0,Math.min(170,e.getMessage().length())));
                System.out.println("  carried "+pr(m.phaseEngine().tp(t,p,f,PhaseCompetition.FLUID_CARRYING_CRYSTALS)));
                System.out.println("  fluid   "+pr(m.phaseEngine().tp(t,p,f,false)));
                System.out.println("  comp    "+pr(m.phaseEngine().tp(t,p,f,true)));
                for(double dt:new double[]{-4,-2,-1,1,2,4}) System.out.println("  T"+(dt>0?"+":"")+dt+" "+pr(m.phaseEngine().tp(t+dt,p,f,PhaseCompetition.FLUID_CARRYING_CRYSTALS)));
            }
        }
    }
    static String pr(EquilibriumResult r){var b=new StringBuilder(r.status()+" "+r.classification());if(!r.converged())b.append(" ").append(r.detail(),0,Math.min(120,r.detail().length()));
        for(var f:r.phases()){b.append(String.format(Locale.ROOT," %s[%.5g co2=%.4g n2=%.3g ch4=%.3g c2=%.3g]",f.kind(),f.total(),f.amount(3)/f.total(),f.amount(0)/f.total(),f.amount(1)/f.total(),f.amount(2)/f.total()));}return b.toString();}
}
