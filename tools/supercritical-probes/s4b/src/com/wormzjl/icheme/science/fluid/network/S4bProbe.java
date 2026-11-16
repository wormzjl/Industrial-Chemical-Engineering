package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.machine.VacuumPump;
import com.wormzjl.icheme.science.machine.Wear;
import java.util.Locale;

/**
 * S4b: (1) the place of the seam in E (none to a flash) at 310, 320 and 330 K, by bisection on the supplier's reduced
 * density, with the isenthalps traced on each side of it: does the stream enter the dome from the bubble side (a liquid
 * one-root state beside which vapour appears with a share rising from 0) or the dew side; (2) the pump readers of the
 * vapour share on CO2 at 310 K, reduced density 0.6, 1.0 and 1.4, for S5; (3) the compressor's observed droplet share
 * against its wet efficiency.
 */
public final class S4bProbe {
    static final FluidThermodynamics MODEL=S4SharesProbe.MODEL;
    static final PassiveStepSolver SOLVER=S4SharesProbe.SOLVER;
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double edge(FluidThermodynamics.State s){return S4SeamProbe.edge(s);}
    static void trace(String name,FluidThermodynamics.State s) {
        System.out.println(f("-- isenthalp of %s: %.6f MPa, delta %.5f, E %s",name,s.pressure()/1e6,s.reducedDensity(),S4SharesProbe.mpa(edge(s))));
        double h=s.fluidEnthalpy();
        for(int i=0;i<=40;i++) {
            double p=s.pressure()*Math.pow(6.8e6/s.pressure(),i/40.0);
            try {
                var at=MODEL.equilibriumPH(s.temperature(),p,h,s.totals(),()->{});
                boolean gas=FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS),oil=FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL);
                System.out.println(f("   %.5f MPa | T %.4f K | dense %s | slot %d | gas %s oil %s | delta %.4f | share %.6g | clean %.4f",p/1e6,at.temperature(),at.dense(),at.oneFluidSlot(),gas,oil,
                        at.reducedDensity(),ReleasePressure.vapourVolumeShare(at),GasEntrainment.cleanShare(p)));
            }catch(IllegalArgumentException refused){System.out.println(f("   %.5f MPa refused",p/1e6));}
        }
    }
    public static void main(String[] args) {
        double[] co2=S4SharesProbe.pure("CarbonDioxide");
        System.out.println("== (1) the seam: the reduced density where E goes from none to a flash");
        for(double t:new double[]{310,320,330}) {
            double lo=1.0,hi=1.5;
            for(int i=0;i<40;i++){double mid=(lo+hi)/2;var s=S4SharesProbe.at(t,S4SharesProbe.pressureAt(t,co2,mid),co2);if(edge(s)<Double.POSITIVE_INFINITY)hi=mid;else lo=mid;}
            var below=S4SharesProbe.at(t,S4SharesProbe.pressureAt(t,co2,lo),co2);var above=S4SharesProbe.at(t,S4SharesProbe.pressureAt(t,co2,hi),co2);
            System.out.println(f("%.0f K | seam delta %.6f to %.6f | suction %.6f MPa | E below %s | E above %s | suction minus E %.4f MPa | T at the seam's isenthalp %s",
                    t,lo,hi,above.pressure()/1e6,S4SharesProbe.mpa(edge(below)),S4SharesProbe.mpa(edge(above)),(above.pressure()-edge(above))/1e6,"(see trace)"));
            if(t==310){trace("310 K below the seam",below);trace("310 K above the seam",above);}
        }
        System.out.println("== (2) the pump readers of the vapour share, CO2 at 310 K (none changed in S4b)");
        for(double delta:new double[]{.6,1.0,1.4}) {
            var s=S4SharesProbe.at(310,S4SharesProbe.pressureAt(310,co2,delta),co2);double p=s.pressure();
            var supply=InletPhase.bulk(MODEL,s);double alpha=supply.vapourVolumeShare(),w=FluidThermodynamics.liquidLikeWeight(s),onset=GasEntrainment.onset(p),r=alpha/onset;
            System.out.println(f("delta %.2f | %.4f MPa | w %.4f | alpha %.4f | onset %.4f | head share %.4f | gas ratio r %.3f | churn %.4f (loudness %d/255) | GAS word %s | gas lock (DRY at no flow) %s | page vapour share %.4f",
                    delta,p/1e6,w,alpha,onset,GasEntrainment.headShare(alpha,p),r,GasEntrainment.churn(r),Math.round(255*GasEntrainment.churn(r)),r>GasEntrainment.CLEAN_ONSETS,alpha>onset,alpha));
            System.out.println(f("           dry screw: liquid share %.4f | LIQUID intensity %.4f | LIQUID word %s",1-alpha,VacuumPump.liquidIntensity(1-alpha),1-alpha>VacuumPump.LIQUID_TRACE));
        }
        System.out.println("== (3) a compressor's observed droplet share and wet efficiency, CO2 at 310 K");
        for(double delta:new double[]{.6,.7,1.0,1.4,1.6}) {
            var s=S4SharesProbe.at(310,S4SharesProbe.pressureAt(310,co2,delta),co2);var supply=InletPhase.bulk(MODEL,s);
            System.out.println(f("delta %.2f | w %.4f | condensed share %.4f | droplet share %.4f | Wear.wetGas before %.4f, now %.4f | wet efficiency %.4f",delta,FluidThermodynamics.liquidLikeWeight(s),
                    supply.condensedMassShare(),supply.dropletMassShare(),Wear.wetGas(supply.condensedMassShare()),Wear.wetGas(supply.dropletMassShare()),CentrifugalCompressor.wetEfficiency(1,supply.condensedMassShare())));
        }
    }
}
