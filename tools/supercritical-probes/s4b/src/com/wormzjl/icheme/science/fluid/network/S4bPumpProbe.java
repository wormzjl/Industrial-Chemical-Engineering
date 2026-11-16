package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.machine.VacuumPump;
import java.util.Locale;

/** S4b addition: a pump's own weight (0.5 to 1.0) against the film's (0.5 to 1.5): the vapour share and head share a
 * pump reads of carbon dioxide at 310 K, reduced density 0.45 to 1.2, at each state's own pressure; nitrogen at 300 K; and
 * the pump readers of S4b's table on the new share. */
public final class S4bPumpProbe {
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static void row(String name,FluidThermodynamics.State s) {
        double p=s.pressure(),wf=FluidThermodynamics.liquidLikeWeight(s),wp=FluidThermodynamics.pumpLiquidLikeWeight(s);var supply=InletPhase.bulk(S4SharesProbe.MODEL,s);
        System.out.println(f("%s | %.4f MPa | delta %.4f | w film %.6f | w pump %.6f | alpha before %.6f, now %.6f | head before %.6f, now %.6f | condensed %.6f",
                name,p/1e6,s.reducedDensity(),wf,wp,1-wf,supply.vapourVolumeShare(),GasEntrainment.headShare(1-wf,p),GasEntrainment.headShare(supply.vapourVolumeShare(),p),supply.condensedMassShare()));
    }
    public static void main(String[] args) {
        double[] co2=S4SharesProbe.pure("CarbonDioxide"),n2=S4SharesProbe.pure("Nitrogen");
        System.out.println("== CO2 at 310 K");
        double last=Double.NaN,largest=0,lastBefore=Double.NaN,largestBefore=0;
        for(int i=0;i<=15;i++){double d=.45+.05*i;var s=S4SharesProbe.at(310,S4SharesProbe.pressureAt(310,co2,d),co2);row(f("CO2 310 K delta %.2f",d),s);
            double h=GasEntrainment.headShare(InletPhase.bulk(S4SharesProbe.MODEL,s).vapourVolumeShare(),s.pressure()),hb=GasEntrainment.headShare(1-FluidThermodynamics.liquidLikeWeight(s),s.pressure());
            if(i>0){largest=Math.max(largest,Math.abs(h-last));largestBefore=Math.max(largestBefore,Math.abs(hb-lastBefore));}last=h;lastBefore=hb;}
        System.out.println(f("largest head-share step between neighbours 0.05 apart: before %.4f, now %.4f",largestBefore,largest));
        for(double d:new double[]{.4995,.73,1.0005,1.5})row(f("CO2 310 K delta %.4f",d),S4SharesProbe.at(310,S4SharesProbe.pressureAt(310,co2,d),co2));
        System.out.println("== N2 at 300 K");
        for(double p:new double[]{15e6,25e6,30e6})row(f("N2 300 K %.0f MPa",p/1e6),S4SharesProbe.at(300,p,n2));
        System.out.println("== the pump readers on the new share, CO2 at 310 K");
        for(double d:new double[]{.6,.73,1.0,1.4}){var s=S4SharesProbe.at(310,S4SharesProbe.pressureAt(310,co2,d),co2);double p=s.pressure(),alpha=InletPhase.bulk(S4SharesProbe.MODEL,s).vapourVolumeShare(),onset=GasEntrainment.onset(p),r=alpha/onset;
            System.out.println(f("delta %.2f | alpha %.4f | head %.4f | r %.3f | churn %.4f (%d/255) | GAS %s | gas lock %s | dry screw liquid %.4f, LIQUID %.4f, word %s",d,alpha,GasEntrainment.headShare(alpha,p),r,GasEntrainment.churn(r),Math.round(255*GasEntrainment.churn(r)),
                    r>GasEntrainment.CLEAN_ONSETS,alpha>onset,1-alpha,VacuumPump.liquidIntensity(1-alpha),1-alpha>VacuumPump.LIQUID_TRACE));}
    }
}
