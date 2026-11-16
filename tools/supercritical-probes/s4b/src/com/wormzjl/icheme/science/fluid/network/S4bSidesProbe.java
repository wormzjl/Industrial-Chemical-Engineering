package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import java.util.Locale;

/** S4b: the isenthalps of CO2 at 310 K, reduced density 1.03 (gas side of the seam) and 1.10 (bubble side), in fine steps
 * from 7.45 to 7.25 MPa around the critical pressure: the trial E is bisected on, and the label of each state. */
public final class S4bSidesProbe {
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    public static void main(String[] args) {
        var model=S4SharesProbe.MODEL;double[] co2=S4SharesProbe.pure("CarbonDioxide");
        for(double delta:new double[]{1.03,1.10}) {
            var s=S4SharesProbe.at(310,S4SharesProbe.pressureAt(310,co2,delta),co2);double h=s.fluidEnthalpy();
            System.out.println(f("-- CO2 310 K delta %.2f (%.4f MPa): E %s",delta,s.pressure()/1e6,S4SharesProbe.mpa(S4SeamProbe.edge(s))));
            for(int i=0;i<=40;i++){double p=7.45e6-i*5e3;
                try{var at=model.equilibriumPH(s.temperature(),p,h,s.totals(),()->{});
                    System.out.println(f("   %.4f MPa | T %.4f K | dense %s | slot %d | gas %s oil %s | delta %.4f | share %.6g | clean %.4f",p/1e6,at.temperature(),at.dense(),at.oneFluidSlot(),
                            FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS),FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL),at.reducedDensity(),ReleasePressure.vapourVolumeShare(at),GasEntrainment.cleanShare(p)));
                }catch(IllegalArgumentException r){System.out.println(f("   %.4f MPa refused",p/1e6));}}
        }
    }
}
