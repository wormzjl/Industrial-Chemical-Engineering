package com.wormzjl.icheme.science.fluid.thermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.*;
import java.util.*;
/** Scratch: dew and bubble pressure of 907's LNG, carried, at its generator temperature. */
public final class CarriedBubble {
    public static void main(String[] a){
        var m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        double[] lng={.002242095,.9497475,.02145535,.02655503,0};
        String last="";
        for(double p=1e5;p<3e6;p+=1e4){var r=m.phaseEngine().tp(154.8,p,lng,PhaseCompetition.FLUID_CARRYING_CRYSTALS);String s=r.classification().toString();if(!s.equals(last)){System.out.printf(Locale.ROOT,"154.8 K %.0f Pa %s%n",p,s);last=s;}}
    }
}
