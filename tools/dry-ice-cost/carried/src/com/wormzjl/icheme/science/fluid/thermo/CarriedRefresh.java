package com.wormzjl.icheme.science.fluid.thermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
/** Scratch: the refresh's crystal answers at one taut and one slack state, printed in full digits, to compare builds. */
public final class CarriedRefresh {
    public static void main(String[] a){
        var m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        double[] n={1,95,1,3,0};
        var at=m.crystalsAtVolume(n,-1.5e6,0.04,114,8e5,true,()->{});
        System.out.printf(Locale.ROOT,"UV T=%.15g P=%.15g co2=%.15g vol=%.15g%n",at.temperature(),at.pressure(),at.crystalMoles()[0],at.volume());
        var ap=m.crystalsAtPressure(n,-1.2e6,101325,true,()->{});
        System.out.printf(Locale.ROOT,"PH T=%.15g P=%.15g co2=%.15g vol=%.15g%n",ap.temperature(),ap.pressure(),ap.crystalMoles()[0],ap.volume());
    }
}
