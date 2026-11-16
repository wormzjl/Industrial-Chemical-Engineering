package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.network.InletPhase;
import com.wormzjl.icheme.science.fluid.network.ReleasePressure;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.Locale;

/**
 * S4 step 6: what the simulator's machine readers give for one dense fluid on each side of the slot line and inside the
 * window: the line service (open and into a pump), the compressor's liquid share (as CompressorSizing reads it, and the
 * world's condensed share), the pump's E on the sheet's TP trial (the trial before S4 replayed here, and the current one),
 * and the pump's bubble warning (the sheet's flash test, and the dense edge). Run before and after the simulator commit.
 */
public final class S4SheetProbe {
    static final String PACKAGE=FluidMaterialCatalog.NETWORK_PACKAGE;
    static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    static final FluidThermodynamics MODEL=MaterialRuntime.with(CATALOG,PACKAGE,()->FluidThermodynamics.forNetwork(CATALOG,PACKAGE));
    static final FlowThermo THERMO=new FlowThermo(MODEL,CATALOG,PACKAGE,()->{});
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double[] pure(String name){double[] n=new double[MODEL.componentCount()];n[MODEL.components().indexOf(name)]=100;return n;}
    static double pressureAt(double t,double[] n,double target) {
        double lo=1e5,hi=8e7;
        for(int i=0;i<200&&hi-lo>1e-13*hi;i++){double mid=(lo+hi)/2;if(MODEL.flashTP(t,mid,n,()->{}).reducedDensity()<target)lo=mid;else hi=mid;}
        return (lo+hi)/2;
    }
    /** The sheet's trial as it was before S4: the vapour slot NaN made 1, the liquid slot 0. */
    static double oldShare(double t,double p,double[] n) {
        try {
            var s=MODEL.flashTP(t,p,n,()->{});
            if(!(FluidThermodynamics.holdsPhase(s,FluidThermodynamics.OIL)||FluidThermodynamics.holdsPhase(s,FluidThermodynamics.AQUEOUS)))return FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)?1:Double.NaN;
            if(!FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS))return 0;
            double v=s.vaporVolume(),total=v+s.condensedVolume();return total>0&&v>0?v/total:Double.MIN_VALUE;
        }catch(RuntimeException e){return Double.NaN;}
    }
    static String mpa(double p){return p==Double.POSITIVE_INFINITY?"none":f("%.6f MPa",p/1e6);}
    static void row(String name,double t,double p,double[] n) {
        FlowStream s=THERMO.atTemperature(t,p,n,SolidInventory.EMPTY);var st=s.state();
        var open=Service.of(s,Service.Ends.OPEN,THERMO,null);var pump=Service.of(s,new Service.Ends(false,true),THERMO,null);
        double w=FluidThermodynamics.liquidLikeWeight(st);
        double liquidOld=100-Service.vapourShare(st,MODEL),liquidNew=st.dense()&&w>0&&w<1?100*w:liquidOld;
        double eOld=ReleasePressure.lowest(q->oldShare(t,q,n),GasEntrainment::cleanShare,100,p,1.0005-1),eNow=THERMO.zoneEdge(t,p,n);
        boolean bubbleFlash=THERMO.boils(t,p*(1-1e-3),n);
        boolean bubbleDense=st.dense()&&InletPhase.bulk(MODEL,st).vapourVolumeShare()>=GasEntrainment.cleanShare(p);
        System.out.println(f("%s | %.6f MPa | slot %d | delta %.4f | w %.4f | rho %.2f | service open %s | service pump %s | liquid%% slot %.4f | liquid%% weight %.4f | E before %s | E now %s | warn flash %s | warn dense %s",
                name,p/1e6,st.oneFluidSlot(),st.reducedDensity(),w,s.mass()/st.volume(),open,pump,liquidOld,liquidNew,mpa(eOld),mpa(eNow),bubbleFlash,bubbleDense));
    }
    public static void main(String[] args) {
        MaterialRuntime.with(CATALOG,PACKAGE,()->{
            double[] co2=pure("CarbonDioxide"),n2=pure("Nitrogen");
            for(double d:new double[]{.6,.9995,1.0005,1.05,1.1,1.2,1.6})row(f("CO2 310 K delta %.4f",d),310,pressureAt(310,co2,d),co2);
            row("CO2 320 K 15 MPa",320,15e6,co2);
            row("N2 300 K 5 MPa",300,5e6,n2);
            row("CO2 280 K 6 MPa",280,6e6,co2);
            return null;
        });
    }
}
