package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;

/** Scratch: carried answers through the refused windows. */
public final class CarriedWindow {
    public static void main(String[] a) throws Exception{
        var m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        CarriedProbe.m=m;
        double[] lng={0.01,0.95,0.01,0.03,0};
        for(double t:new double[]{112,113,113.1,113.2,113.3,113.5,114,115,117,120,123,125,127,128,129,130,132})
            line(m,t,101325,lng);
        double[] g={0.02,0.95,0.002,0.028,0};
        for(double t:new double[]{138,138.5,138.7,138.8,139,139.5,140,140.3,140.5,141})
            line(m,t,541000,g);
        double[] c={0,0.6,0,0.4,0};
        for(double t:new double[]{147,150,151,151.3,151.39,151.4,152,155})
            line(m,t,1e6,c);
    }
    public static void line(FluidThermodynamics m,double t,double p,double[] n){
        var r=m.phaseEngine().tp(t,p,n,PhaseCompetition.FLUID_CARRYING_CRYSTALS);
        String s=CarriedProbe.phases(r);
        System.out.printf(Locale.ROOT,"T=%.2f P=%.0f %s %s%n",t,p,r.converged()?String.format(Locale.ROOT,"H=%.1f",r.enthalpy()):"",s);
    }
}
