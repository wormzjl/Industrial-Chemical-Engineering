package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;

/**
 * S2c premise check: which one-fluid states on a supercritical isotherm reach FluidThermodynamics.placed's default
 * branch (the engine's FLUID label) and which arrive labelled LIQUID or VAPOR by the engine (DryCore.singleLabel).
 * For each mixture, over T 1.0..3.0 x pseudo-Tc and P 0.5..30 MPa: today's slot by flashTP against O2 A (liquid iff
 * reduced density >= 1).
 */
public final class MixtureSlotProbe {
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:refinery");
    static double[] mix(Object... nameShare){double[] n=new double[MODEL.componentCount()];for(int k=0;k<nameShare.length;k+=2)n[MODEL.components().indexOf((String)nameShare[k])]=(Double)nameShare[k+1];return n;}
    public static void main(String[] args) {
        Object[][] cases={
            {"N2","Nitrogen",1.0},
            {"N2+1%CH4","Nitrogen",.99,"Methane",.01},
            {"CO2","CarbonDioxide",1.0},
            {"CO2+2%N2","CarbonDioxide",.98,"Nitrogen",.02},
            {"natural gas","Methane",.90,"Ethane",.06,"Propane",.03,"Nitrogen",.01},
        };
        for(var c:cases) {
            Object[] spec=new Object[c.length-1];System.arraycopy(c,1,spec,0,spec.length);double[] n=mix(spec);
            int states=0,supercritical=0,disagree=0,disagreeOld=0;String first=null;
            for(double t=100;t<=500;t+=10)for(double p=.5e6;p<=30e6;p+=.5e6) {
                FluidThermodynamics.State s;
                try{s=MODEL.flashTP(t,p,n,()->{});}catch(RuntimeException refused){continue;}
                int slot=s.oneFluidSlot();if(slot<0)continue;states++;
                if(!s.dense())continue;supercritical++;
                boolean liquidToday=slot!=FluidThermodynamics.State.VAPOR,liquidO2a=s.reducedDensity()>=1;
                if(liquidToday!=liquidO2a){disagree++;if(p<=10e6)disagreeOld++;
                    if(first==null)first=String.format(Locale.ROOT,"%.0f K %.1f MPa delta %.3f today %s",t,p/1e6,s.reducedDensity(),liquidToday?"liquid":"vapour");}
            }
            System.out.println(String.format(Locale.ROOT,"%-12s one-fluid states %d, dense %d, disagree with O2 A %d (%d at or below 10 MPa); first: %s",
                    c[0],states,supercritical,disagree,disagreeOld,first));
        }
    }
}
