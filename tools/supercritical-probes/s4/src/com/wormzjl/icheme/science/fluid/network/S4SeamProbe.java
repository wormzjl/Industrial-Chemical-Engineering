package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import java.util.Locale;

/**
 * S4 step 3: where E jumps. E against the supplier's reduced density in fine steps, and the trial E is bisected on (the
 * vapour volume share of the PH equilibrium at the stream's enthalpy) along the isenthalp of suppliers on each side of
 * the jump: temperature, dense or not, slot, delta, share and the clean share there.
 */
public final class S4SeamProbe {
    static final FluidThermodynamics MODEL=S4SharesProbe.MODEL;
    static final PassiveStepSolver SOLVER=S4SharesProbe.SOLVER;
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double edge(FluidThermodynamics.State s) {
        int label=s.oneFluidSlot()==FluidThermodynamics.State.VAPOR?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
        double[] d=new double[3];d[label]=FluidThermodynamics.phaseVolume(s,label);
        return SOLVER.suctions.zoneEdge(InletPhase.of(MODEL,s,d),()->{});
    }
    static void trace(String name,FluidThermodynamics.State s) {
        System.out.println(f("-- isenthalp of %s: %.4f MPa, delta %.4f, E %.6f MPa",name,s.pressure()/1e6,s.reducedDensity(),edge(s)/1e6));
        double h=s.fluidEnthalpy();
        for(int i=0;i<=60;i++) {
            double p=s.pressure()*Math.pow(6.0e6/s.pressure(),i/60.0);
            try {
                var at=MODEL.equilibriumPH(s.temperature(),p,h,s.totals(),()->{});
                System.out.println(f("   %.4f MPa | T %.3f K | dense %s | slot %d | delta %.4f | share %.6g | clean %.4f",p/1e6,at.temperature(),at.dense(),at.oneFluidSlot(),at.reducedDensity(),
                        ReleasePressure.vapourVolumeShare(at),GasEntrainment.cleanShare(p)));
            }catch(IllegalArgumentException refused){System.out.println(f("   %.4f MPa refused",p/1e6));}
        }
    }
    public static void main(String[] args) {
        double[] co2=S4SharesProbe.pure("CarbonDioxide");
        for(double t:new double[]{310,320,330}) {
            System.out.println("== CO2 at "+t+" K: E against delta");
            for(int i=0;i<=40;i++){double delta=1.0+.0125*i;var s=S4SharesProbe.at(t,S4SharesProbe.pressureAt(t,co2,delta),co2);
                System.out.println(f("delta %.4f | %.6f MPa | E %.6f MPa",delta,s.pressure()/1e6,edge(s)/1e6));}
        }
        trace("CO2 320 K delta 1.25",S4SharesProbe.at(320,S4SharesProbe.pressureAt(320,co2,1.25),co2));
        trace("CO2 320 K delta 1.30",S4SharesProbe.at(320,S4SharesProbe.pressureAt(320,co2,1.30),co2));
    }
}
