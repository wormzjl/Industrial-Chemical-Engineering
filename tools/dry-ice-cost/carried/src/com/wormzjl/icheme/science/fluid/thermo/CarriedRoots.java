package com.wormzjl.icheme.science.fluid.thermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.*;
import java.util.*;
/** Scratch: carried answers on a grid, to compare two builds, and the bubble pressure of 907's LNG. */
public final class CarriedRoots {
    public static void main(String[] a){
        var m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        double[][] mix={{0,1,0,0},{.1,.9,0,0},{0,.7,.3,0},{.5,.5,0,0},{0,0,1,0},{.8,0,.2,0}};
        for(double co2:new double[]{.02,.05,.1,.2,.3,.4,.5,.6,.7,.8,.9})for(double[] b:mix)for(double t=90;t<216;t+=6)for(double lp=5;lp<=7.31;lp+=.11){
            double p=Math.pow(10,lp);double[] z=new double[5];for(int i=0;i<4;i++)z[i]=b[i]*(1-co2);z[3]+=co2;
            var r=m.phaseEngine().tp(t,p,z,PhaseCompetition.FLUID_CARRYING_CRYSTALS);
            if(!r.converged())continue;
            var f=r.phases().getFirst();
            if(r.phases().size()==1)System.out.printf(Locale.ROOT,"%.1f %.0f %.3f %s %s %.6g%n",t,p,co2,Arrays.toString(b),f.kind(),f.state().molarVolume());
        }
        double[] lng={.002242095,.9497475,.02145535,.02655503,0};
        for(double t:new double[]{154.8,156.6})for(double p=3e5;p<1.6e6;p+=2e4){var r=m.phaseEngine().tp(t,p,lng,PhaseCompetition.FLUID_CARRYING_CRYSTALS);var q=m.phaseEngine().tp(t,p,lng,false);if(r.phases().size()==1){System.err.printf(Locale.ROOT,"bubble %.1f K near %.0f Pa: carried %s fluid %s%n",t,p,r.classification(),q.classification());break;}}
    }
}
