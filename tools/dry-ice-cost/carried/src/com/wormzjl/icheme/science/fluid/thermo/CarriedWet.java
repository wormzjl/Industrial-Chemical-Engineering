package com.wormzjl.icheme.science.fluid.thermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.*;
import java.util.*;
/** Scratch: the refused window of the grid's wet feed, dry and wet. */
public final class CarriedWet {
    public static void main(String[] a){
        var m=com.wormzjl.icheme.fluid.support.FluidTestSupport.model(MaterialCatalog.bundled(),"icheme:pilot_cryogenic");
        double[] wet={.1,.6,.1,.1,.1},dry={.1,.6,.1,.1,0};
        for(double t:new double[]{118,119,119.5,120,121,123,125,130,135,140}){
            System.out.println("T="+t+" dry "+CarriedGrid.pr(m.phaseEngine().tp(t,1e5,dry,PhaseCompetition.FLUID_CARRYING_CRYSTALS)));
        }
        System.out.println("wet 120 "+CarriedGrid.pr(m.phaseEngine().tp(120,1e5,wet,PhaseCompetition.FLUID_CARRYING_CRYSTALS)));
    }
}
