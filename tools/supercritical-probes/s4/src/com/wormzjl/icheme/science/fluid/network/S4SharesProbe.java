package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;

/**
 * S4: what a pump and a compressor read of one fluid, across reduced density, before and after the shares follow the
 * liquid-like weight. Per state: slot, delta, weight, the bulk supply's vapour share and the pump's head share at the
 * state's pressure, the condensed share and the wet efficiency, Drawn.condensed, p_v (release) and the clean zone's edge E
 * on the stream drawn from the slot's phase, and the suction limit max(E, p_v). Run once on the code before, once after.
 */
public final class S4SharesProbe {
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:refinery");
    static final PassiveStepSolver SOLVER=new PassiveStepSolver(MODEL);
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double[] pure(String name){double[] n=new double[MODEL.componentCount()];n[MODEL.components().indexOf(name)]=1;return n;}
    static FluidThermodynamics.State at(double t,double p,double[] n){return MODEL.flashTP(t,p,n,()->{});}
    static double pressureAt(double t,double[] n,double target) {
        double lo=1e5,hi=8e7;
        for(int i=0;i<200&&hi-lo>1e-13*hi;i++){double mid=(lo+hi)/2;if(at(t,mid,n).reducedDensity()<target)lo=mid;else hi=mid;}
        return (lo+hi)/2;
    }
    static double safe(java.util.function.DoubleSupplier g){try{return g.getAsDouble();}catch(RuntimeException e){return Double.NaN;}}
    static String mpa(double p){return p==Double.POSITIVE_INFINITY?"none":Double.isNaN(p)?"refused":f("%.6f",p/1e6);}
    static void row(String name,FluidThermodynamics.State s) {
        int slot=s.oneFluidSlot();int label=slot==FluidThermodynamics.State.VAPOR?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
        double p=s.pressure();
        var bulk=InletPhase.bulk(MODEL,s);
        double[] d=new double[3];if(slot>=0)d[label]=FluidThermodynamics.phaseVolume(s,label);
        var drawn=InletPhase.of(MODEL,s,slot>=0?d:null);
        double release=safe(()->SOLVER.suctions.release(s,()->{}));
        double edge=safe(()->SOLVER.suctions.zoneEdge(drawn,()->{}));
        System.out.println(f("%s | %.6f MPa | slot %d | dense %s | delta %.4f | w %.6f | vapour %.17g | head %.17g | condensed %.17g | wet %.17g | drawnCondensed %s | p_v %s | E %s | limit %s",
                name,p/1e6,slot,s.dense(),s.reducedDensity(),FluidThermodynamics.liquidLikeWeight(s),bulk.vapourVolumeShare(),GasEntrainment.headShare(bulk.vapourVolumeShare(),p),
                bulk.condensedMassShare(),CentrifugalCompressor.wetEfficiency(1,bulk.condensedMassShare()),drawn.condensed(),mpa(release),mpa(edge),mpa(ReleasePressure.limit(release,edge,0))));
    }
    public static void main(String[] args) {
        double[] co2=pure("CarbonDioxide"),n2=pure("Nitrogen");
        for(double t:new double[]{310,320,330}) {
            System.out.println("== CO2 at "+t+" K, reduced density 0.30 to 1.80");
            for(int i=0;i<=30;i++){double delta=.3+.05*i;row(f("CO2 %.0f K delta %.2f",t,delta),at(t,pressureAt(t,co2,delta),co2));}
        }
        System.out.println("== S2c's states");
        row("CO2 320 K 15 MPa",at(320,15e6,co2));
        row("CO2 310 K delta 1.0005",at(310,pressureAt(310,co2,1.0005),co2));
        row("CO2 310 K delta 0.9995",at(310,pressureAt(310,co2,1/1.0005),co2));
        row("N2 150 K delta 1.0005",at(150,pressureAt(150,n2,1.0005),n2));
        row("N2 150 K delta 0.9995",at(150,pressureAt(150,n2,1/1.0005),n2));
        System.out.println("== N2 at 300 K, 0.1 to 10 MPa (not dense below delta 0.5: exact)");
        for(double p:new double[]{1e5,3e5,1e6,3e6,5e6,7e6,1e7})row(f("N2 300 K %.1f MPa",p/1e6),at(300,p,n2));
        System.out.println("== Subcritical CO2 (not dense: exact)");
        row("CO2 280 K 6 MPa liquid",at(280,6e6,co2));
        row("CO2 280 K 3 MPa gas",at(280,3e6,co2));
        double[] g=new double[MODEL.componentCount()];g[MODEL.components().indexOf("Methane")]=.90;g[MODEL.components().indexOf("Ethane")]=.06;g[MODEL.components().indexOf("Propane")]=.03;g[MODEL.components().indexOf("Nitrogen")]=.01;
        System.out.println("== Dense natural gas at 300 K, reduced density 0.6 to 1.6");
        for(double delta:new double[]{.6,.9,.9995,1.0005,1.1,1.6})row(f("gas 300 K delta %.4f",delta),at(300,pressureAt(300,g,delta),g));
    }
}
