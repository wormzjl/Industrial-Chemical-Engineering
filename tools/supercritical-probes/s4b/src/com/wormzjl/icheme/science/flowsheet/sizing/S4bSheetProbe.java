package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.ReleasePressure;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.Locale;

/**
 * S4b on the sheet: (1) the compressor sizing's discharge on the blended exponent against the solved outlet (the
 * compressor tile's own law, ratio 2), and the ideal-gas estimate S4 left; (2) the storage pressure (V/L = 1) of a dense
 * stream now, against S4's trial (1 - w) replayed, with what TankSizing and VesselUnit read of it (min with the bubble
 * pressure, the floating roof).
 */
public final class S4bSheetProbe {
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double[] pure(String name,double moles){double[] n=new double[S4SheetProbe.MODEL.componentCount()];n[S4SheetProbe.MODEL.components().indexOf(name)]=moles;return n;}
    static void compressor(String name,double t,double p,double[] n) {
        var thermo=S4SheetProbe.THERMO;var model=S4SheetProbe.MODEL;
        FlowStream in=thermo.atTemperature(t,p,n,SolidInventory.EMPTY);var st=in.state();
        double k=model.heatCapacityRatio(st),density=in.mass()/st.volume(),rise=p;
        double power=new FlowControl.Compressor(1,2).power(in.mass(),rise,density,p,k);
        var out=thermo.heated(in,power,p+rise);
        var load=CompressorSizing.Load.of(in,out,DesignBasis.DEFAULT,thermo,new MixtureViscosity.Workspace());
        double ideal=k>1?1/CentrifugalCompressor.sigma(k,CentrifugalCompressor.DEFAULT_EFFICIENCY):0;
        System.out.println(f("%s | dense %s | delta %.4f | w %.4f | liquid %% %.3f | exponent ideal %.6f, used %.6f | discharge ideal %.2f K, sized %.2f K, solved outlet %.2f K | sized minus solved %.2f K | ideal exponent kept to the bit %s",
                name,st.dense(),st.reducedDensity(),FluidThermodynamics.liquidLikeWeight(st),load.liquid(),ideal,load.exponent(),t*Math.pow(2,ideal),t*Math.pow(2,load.exponent()),out.temperature(),
                t*Math.pow(2,load.exponent())-out.temperature(),Double.compare(ideal,load.exponent())==0));
    }
    /** S4's sheet trial replayed: one dense fluid 1 - w, else today's. */
    static double s4Share(double t,double q,double[] n) {
        try{var s=S4SheetProbe.MODEL.flashTP(t,q,n,()->{});
            if(s.dense()){double w=FluidThermodynamics.liquidLikeWeight(s);return w>=1?0:Math.max(1-w,Double.MIN_VALUE);}
            double share=ReleasePressure.vapourVolumeShare(s);return Double.isNaN(share)&&FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)?1:share;
        }catch(RuntimeException e){return Double.NaN;}
    }
    static String bar(double pa){return pa==Double.POSITIVE_INFINITY?"none":f("%.4f bar",pa/1e5);}
    static void storage(String name,double t,double[] n) {
        var thermo=S4SheetProbe.THERMO;
        double now=thermo.storagePressure(t,n),s4=ReleasePressure.lowest(q->s4Share(t,q,n),q->.5,100,2e7,1.0005-1),bubble=thermo.bubblePressure(t,n);
        System.out.println(f("%s | storage now %s, S4 %s | bubble %s | tank min now %s, S4 %s | roof stores now %s, S4 %s",name,bar(now),bar(s4),bar(bubble),bar(Math.min(bubble,now)),bar(Math.min(bubble,s4)),
                now<=76600,s4<=76600));
    }
    public static void main(String[] args) {
        MaterialRuntime.with(S4SheetProbe.CATALOG,S4SheetProbe.PACKAGE,()->{
            System.out.println("== (1) the compressor's discharge, ratio 2, one stage");
            double[] co2=pure("CarbonDioxide",100),n2=pure("Nitrogen",100);
            compressor("N2 300 K 1 MPa",300,1e6,n2);
            compressor("N2 300 K 10 MPa",300,1e7,n2);
            compressor("CO2 320 K 1 MPa",320,1e6,co2);
            compressor("CO2 320 K 8 MPa",320,8e6,co2);
            compressor("CO2 310 K 8.3 MPa",310,8.3e6,co2);
            compressor("CO2 310 K 8.325088 MPa (delta 0.9995)",310,8.325088e6,co2);
            compressor("CO2 310 K 8.326483 MPa (delta 1.0005)",310,8.326483e6,co2);
            compressor("CO2 320 K 10 MPa",320,1e7,co2);
            compressor("CO2 320 K 15 MPa",320,15e6,co2);
            compressor("CO2 280 K 3 MPa gas",280,3e6,co2);
            System.out.println("== (2) the storage pressure of a dense stream");
            storage("N2 300 K",300,n2);
            storage("CH4 300 K",300,pure("Methane",100));
            storage("CO2 310 K",310,co2);
            storage("CO2 280 K (subcritical)",280,co2);
            double[] g=new double[S4SheetProbe.MODEL.componentCount()];var c=S4SheetProbe.MODEL.components();g[c.indexOf("Methane")]=90;g[c.indexOf("Ethane")]=6;g[c.indexOf("Propane")]=3;g[c.indexOf("Nitrogen")]=1;
            storage("natural gas 300 K",300,g);
            return null;
        });
    }
}
