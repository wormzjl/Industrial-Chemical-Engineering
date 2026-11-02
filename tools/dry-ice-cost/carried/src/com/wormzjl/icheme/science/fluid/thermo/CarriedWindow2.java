package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import java.util.Locale;

/** Scratch: the refusals left in 244. */
public final class CarriedWindow2 {
    public static void main(String[] a) throws Exception{
        var m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        CarriedProbe.m=m;
        double[] z={0.0030,0.9168,0.0351,0.0451,0};
        for(double t:new double[]{126,128,129,130,131,132,133,133.46,134,135,136,138,140,144,148.46}) CarriedWindow.line(m,t,228065,z);
        double[] y={0.0024,0.9216,0.0392,0.0367,0};
        for(double t:new double[]{126,128,129.55,131,133}) CarriedWindow.line(m,t,191161,y);
    }
}
