package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;

/**
 * S2c step 3: what the clean zone's edge E of a dense carbon dioxide suction is. The trial MoverSuction.releaseShare makes
 * (the PH equilibrium of the drawn stream at its own enthalpy, its vapour volume share) along the isenthalp below the
 * state, and E for neighbouring states, so a jump between the slot line and the dome shows.
 */
public final class ZoneEdgeProbe {
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:refinery");
    static final PassiveStepSolver SOLVER=new PassiveStepSolver(MODEL);
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double[] co2(){double[] n=new double[MODEL.componentCount()];n[MODEL.components().indexOf("CarbonDioxide")]=1;return n;}
    static String trial(FluidThermodynamics.State s,double p) {
        double[] x=PhaseLayout.totalAmounts(s);double h=s.fluidEnthalpy();
        FluidThermodynamics.State at;
        try{at=MODEL.equilibriumPH(s.temperature(),p,h,x.clone(),()->{});}catch(IllegalArgumentException refused){return "refused";}
        if(at==null)return "none";
        double share=ReleasePressure.vapourVolumeShare(at);
        return f("T %.3f K slots %s%s%s delta %s share %s (clean %.4f)",at.temperature(),FluidThermodynamics.holdsPhase(at,FluidThermodynamics.GAS)?"V":"-",
                FluidThermodynamics.holdsPhase(at,FluidThermodynamics.OIL)?"L":"-",FluidThermodynamics.holdsPhase(at,FluidThermodynamics.AQUEOUS)?"A":"-",
                at.oneFluidSlot()>=0?f("%.4f",at.reducedDensity()):"two phases",Double.isNaN(share)?"NaN":f("%.4f",share),GasEntrainment.cleanShare(p));
    }
    static double edge(FluidThermodynamics.State s) {
        int label=s.oneFluidSlot()==FluidThermodynamics.State.VAPOR?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
        double[] d=new double[3];d[label]=FluidThermodynamics.phaseVolume(s,label);
        return SOLVER.suctions.zoneEdge(InletPhase.of(MODEL,s,d),()->{});
    }
    public static void main(String[] args) {
        double[] n=co2();
        var a=MODEL.flashTP(320,15e6,n,()->{});
        System.out.println(f("== 320 K, 15 MPa: delta %.4f, E %.4f MPa; the trial on its isenthalp:",a.reducedDensity(),edge(a)/1e6));
        for(double p:new double[]{14e6,12e6,10e6,9e6,8e6,7.5e6,7e6,6.8e6,6.6e6,6.53e6,6.52e6,6.51e6,6.4e6,6e6,5e6})System.out.println(f("  %.3f MPa: %s",p/1e6,trial(a,p)));
        // 310 K just above reduced density 1.
        double lo=8e6,hi=9e6;for(int i=0;i<100;i++){double mid=(lo+hi)/2;if(MODEL.flashTP(310,mid,n,()->{}).reducedDensity()<1.0005)lo=mid;else hi=mid;}
        var b=MODEL.flashTP(310,hi,n,()->{});
        System.out.println(f("== 310 K, %.4f MPa: delta %.4f, E %.4f MPa; the trial on its isenthalp:",b.pressure()/1e6,b.reducedDensity(),edge(b)/1e6));
        for(double p:new double[]{8.326e6,8.322e6,8.320e6,8.318e6,8.3178e6,8.317e6,8.31e6,8.2e6,8e6,7.5e6,7e6,6.5e6,6e6,5e6})System.out.println(f("  %.4f MPa: %s",p/1e6,trial(b,p)));
        System.out.println("== E of neighbouring liquid-slot states (the zone edge against the suction's own pressure):");
        for(double t:new double[]{305,310,320,330})for(double p=7.5e6;p<=20e6;p+=.5e6) {
            var s=MODEL.flashTP(t,p,n,()->{});if(s.oneFluidSlot()!=FluidThermodynamics.State.LIQUID)continue;
            double e=edge(s);
            System.out.println(f("  %.0f K %5.1f MPa delta %.4f h %.2f kJ/kg: E %s",t,p/1e6,s.reducedDensity(),s.fluidEnthalpy()/s.mass()/1e3,e==Double.POSITIVE_INFINITY?"none":f("%.4f MPa (%.4f of P)",e/1e6,e/p)));
        }
    }
}
