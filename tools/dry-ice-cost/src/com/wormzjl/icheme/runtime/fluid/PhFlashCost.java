package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.thermo.EngineAccess;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.*;

/**
 * Scratch (tmp/dry-ice-symptoms/run, 2026-11-02): the cost of one PH flash of a cold seed's LNG let down to a junction
 * pressure, with and without the crystal competition, as BalanceNet.mixed asks it. Arguments: seed pressurePa [pressurePa...].
 * The LNG is the seed's first LNG generator (HoldFuzzTest's draws); the enthalpy is that of 1 mol at the generator's T and P.
 */
final class PhFlashCost {
    static final Runnable NONE=()->{};
    public static void main(String[] args) {
        long seed=Long.parseLong(args[0]);
        double[] pressures=new double[args.length-1];for(int i=1;i<args.length;i++)pressures[i-1]=Double.parseDouble(args[i]);
        MaterialRuntime.with(HoldFuzzTest.CATALOG,HoldFuzzTest.PACKAGE,()->{run(seed,pressures);return null;});
    }
    static void run(long seed,double[] pressures) {
        var r=new Random(seed);var blocks=HoldFuzzTest.world(r);
        HoldFuzzTest.Block gen=null;for(var b:blocks)if(b.kind()==Kind.GENERATOR&&"LNG".equals(b.fluid())){gen=b;break;}
        if(gen==null){System.out.println("seed "+seed+" has no LNG generator");return;}
        var model=FluidThermodynamics.forNetwork(HoldFuzzTest.CATALOG,HoldFuzzTest.PACKAGE);
        double[] n=gen.spec().composition().clone();double t0=gen.spec().temperature(),p0=gen.spec().pressure();
        var source=model.flashTP(t0,p0,n.clone(),NONE);double h=source.enthalpy();
        System.out.printf(Locale.ROOT,"seed %d LNG %s at %.2f K %.0f kPa: n=%s h=%.1f J/mol competes=%b%n",seed,gen.label(),t0,p0/1000,Arrays.toString(n),h,EngineAccess.competes(model,t0,n));
        for(double p:pressures) {
            for(boolean crystals:new boolean[]{true,false}) {
                var result=EngineAccess.ph(model,p,h,n.clone(),crystals);var d=result.diagnostics();
                for(int i=0;i<10;i++)model.equilibriumPH(t0,p,h,n.clone(),crystals,NONE);
                int reps=40;long started=System.nanoTime();FluidThermodynamics.State last=null;
                for(int i=0;i<reps;i++)last=model.equilibriumPH(t0,p,h,n.clone(),crystals,NONE);
                double ms=(System.nanoTime()-started)/1e6/reps;
                System.out.printf(Locale.ROOT,"  P=%.0f kPa crystals=%-5b %.2f ms/flash  engine: %s %s T=%.3f outerIterations=%d kernelEvaluations=%d flashIterations=%d%n    adopted: T=%.3f liq=%.4g m3 vap=%.4g m3 H=%.1f (target %.1f, +%.1f J/mol)%n    detail: %s%n",
                        p/1000,crystals,ms,result.status(),result.classification(),result.phases().isEmpty()?Double.NaN:result.phases().getFirst().temperature(),d.outerIterations(),d.kernelEvaluations(),d.flashIterations(),
                        last==null?Double.NaN:last.temperature(),last==null?Double.NaN:last.liquidVolume(),last==null?Double.NaN:last.vaporVolume(),last==null?Double.NaN:last.enthalpy(),h,last==null?Double.NaN:last.enthalpy()-h,
                        result.detail()==null?"":result.detail().substring(0,Math.min(300,result.detail().length())));
            }
            // The TP flash at the PH answer's temperature, as the step's phase check asks it, with and without the competition.
            var fluid=model.equilibriumPH(t0,p,h,n.clone(),false,NONE);
            if(fluid!=null)for(boolean crystals:new boolean[]{true,false}) {
                var result=EngineAccess.tp(model,fluid.temperature(),p,n.clone(),crystals);var d=result.diagnostics();
                for(int i=0;i<10;i++)EngineAccess.tp(model,fluid.temperature(),p,n.clone(),crystals);
                int reps=40;long started=System.nanoTime();
                for(int i=0;i<reps;i++)EngineAccess.tp(model,fluid.temperature(),p,n.clone(),crystals);
                System.out.printf(Locale.ROOT,"  TP at %.3f K %.0f kPa crystals=%-5b %.3f ms/flash %s %s kernelEvaluations=%d%n",fluid.temperature(),p/1000,crystals,(System.nanoTime()-started)/1e6/reps,result.status(),result.classification(),d.kernelEvaluations());
            }
        }
    }
}
