package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import java.util.Locale;
import java.util.Random;

/**
 * S2c step 5: the cold and bundled campaigns' generator draws (HoldFuzzTest, tools/unified-thermo-port-forms), sampled as
 * the harness draws them, at their own pressure and at the pressure a compressor of ratio 6 gives them: how many are one
 * dense fluid (supercritical isotherm), how many above reduced density 0.25 (S2b's viscosity onset), and how many changed
 * slot with the S2c rule (the identification parameter's label against reduced density 1).
 */
public final class CampaignDrawProbe {
    static int[] c=new int[5];
    static void count(FluidThermodynamics m,double t,double p,double[] n) {
        FluidThermodynamics.State s;
        try{s=m.flashTP(t,p,n,()->{});}catch(RuntimeException refused){c[4]++;return;}
        c[0]++;
        int slot=s.oneFluidSlot();if(slot<0||!s.dense())return;
        c[1]++;
        double rr=m.viscosity.reducedDensity(FluidThermodynamics.total(s.amountsView(slot))/s.slotVolume(slot),s.amountsView(slot));
        if(rr>.25)c[2]++;
        var tr=s.phase(slot).traits();
        if(PhaseIdentification.liquidLike(tr.identificationParameter(),tr.compressibility())!=(slot!=FluidThermodynamics.State.VAPOR))c[3]++;
    }
    static void report(String what){System.out.println(String.format(Locale.ROOT,"%-34s states %6d, one dense fluid %6d, rho_r > 0.25 %6d, slot moved %5d, refused %d",what,c[0],c[1],c[2],c[3],c[4]));c=new int[5];}
    public static void main(String[] args) {
        var cold=FluidThermodynamics.forNetwork(PilotCryogenicTestCatalog.catalog(),FluidMaterialCatalog.networkPackage(PilotCryogenicTestCatalog.catalog()));
        var bundled=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),FluidMaterialCatalog.networkPackage(MaterialCatalog.bundled()));
        int co2=cold.components().indexOf("CarbonDioxide"),ch4=cold.components().indexOf("Methane"),c2=cold.components().indexOf("Ethane"),n2=cold.components().indexOf("Nitrogen");
        Random r=new Random(1);
        for(double ratio:new double[]{1,6}) {
            for(int k=0;k<4000;k++) {
                double[] n=new double[cold.componentCount()];
                double x=.005+r.nextDouble()*.045,e=r.nextDouble()*.08,nn=r.nextDouble()*.01;n[co2]=x;n[c2]=e;n[n2]=nn;n[ch4]=1-x-e-nn;
                double t=110+r.nextDouble()*60,p=Math.min(6e6,Math.exp(20.744-1029.7/t)*(1.3+r.nextDouble()*1.7));
                count(cold,t,Math.min(3e7,p*ratio),n);
            }
            report("cold LNG, pressure x"+ratio);
            for(int k=0;k<4000;k++) {
                double[] n=new double[cold.componentCount()];
                double x=.5+r.nextDouble()*.45,split=r.nextDouble();n[co2]=x;n[ch4]=(1-x)*split;n[n2]=(1-x)*(1-split);
                double p=1e6+r.nextDouble()*5e6,t=Math.max(250,1993/(22.359-Math.log(1.2*x*p)))+r.nextDouble()*40;
                count(cold,t,Math.min(3e7,p*ratio),n);
            }
            report("cold CO2-rich gas, pressure x"+ratio);
            int bn2=bundled.components().indexOf("Nitrogen"),bch4=bundled.components().indexOf("Methane");
            for(int k=0;k<4000;k++) {
                double[] n=new double[bundled.componentCount()];n[k%2==0?bn2:bch4]=1;
                double t=250+r.nextDouble()*150,p=60000+r.nextDouble()*440000;
                count(bundled,t,p*ratio,n);
            }
            report("bundled N2 and CH4, pressure x"+ratio);
        }
    }
}
