package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;

/** DenseFluidGatesTest's sweeps measured first: the largest neighbour change of each law and where the slot line lies. */
public final class SweepProbe {
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:refinery");
    static double[] laws(FluidThermodynamics.State s) {
        int slot=s.oneFluidSlot();int label=slot==0?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
        return new double[]{MODEL.phaseViscosity(s,label,null,null),MODEL.phaseVelocityLimit(s,label),HeatCurve.liquidLikeWeight(s),slot,s.reducedDensity(),s.dense()?1:0};
    }
    public static void main(String[] args) {
        double[] co2=new double[MODEL.componentCount()];co2[MODEL.components().indexOf("CarbonDioxide")]=1;
        for(int sweep=0;sweep<2;sweep++) {
            double[] prev=null;double prevX=0;double[] max=new double[3];double[] at=new double[3];
            int steps=sweep==0?120:112;
            for(int k=0;k<=steps;k++) {
                double t=sweep==0?280+k:320,p=sweep==0?1e7:2e6+0.25e6*k,x=sweep==0?t:p;
                var s=MODEL.flashTP(t,p,co2,()->{});double[] l=laws(s);
                if(prev!=null) {
                    if(prev[3]!=l[3])System.out.println(String.format(Locale.ROOT,"sweep %c: slot %d -> %d between %.2f and %.2f (delta %.4f, %.4f; dense %.0f %.0f)",sweep==0?'A':'B',(int)prev[3],(int)l[3],prevX,x,prev[4],l[4],prev[5],l[5]));
                    boolean tcStep=sweep==0&&prev[5]!=l[5];
                    for(int q=0;q<3;q++) {
                        double d=Math.abs(l[q]-prev[q]);
                        if(tcStep&&q==0){System.out.println(String.format(Locale.ROOT,"sweep A: the viscosity step at Tc between %.0f and %.0f K: %.4e -> %.4e Pa s (x%.3f)",prevX,x,prev[0],l[0],l[0]/prev[0]));continue;}
                        if(d>max[q]){max[q]=d;at[q]=x;}
                    }
                }
                prev=l;prevX=x;
            }
            System.out.println(String.format(Locale.ROOT,"sweep %c: largest neighbour change: viscosity %.4e Pa s at %.2f, limit %.4f m/s at %.2f, weight %.4f at %.2f",
                    sweep==0?'A':'B',max[0],at[0],max[1],at[1],max[2],at[2]));
        }
        // The slot line: bisect the pressure at 320 K where the slot changes, and compare the laws on both sides.
        double lo=2e6,hi=3e7;int slotLo=(int)laws(MODEL.flashTP(320,lo,co2,()->{}))[3];
        for(int i=0;i<200&&hi-lo>1e-7*lo;i++){double mid=(lo+hi)/2;if((int)laws(MODEL.flashTP(320,mid,co2,()->{}))[3]==slotLo)lo=mid;else hi=mid;}
        double[] a=laws(MODEL.flashTP(320,lo,co2,()->{})),b=laws(MODEL.flashTP(320,hi,co2,()->{}));
        System.out.println(String.format(Locale.ROOT,"slot line at 320 K: %.6f..%.6f MPa slot %d|%d delta %.6f | viscosity rel %.3e limit rel %.3e weight diff %.3e",
                lo/1e6,hi/1e6,(int)a[3],(int)b[3],a[4],Math.abs(b[0]/a[0]-1),Math.abs(b[1]/a[1]-1),Math.abs(b[2]-a[2])));
    }
}
