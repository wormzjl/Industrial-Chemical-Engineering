package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;

/** The numbers of S2_NETWORK_REVIEW.md (S2b of batch 2026-11-15-supercritical-fluids), on the network package. */
public final class DenseLawProbe {
    static final MaterialCatalog CATALOG=MaterialCatalog.bundled();
    static final String NETWORK="icheme:refinery";
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(CATALOG,NETWORK);
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double[] pure(String name){double[] n=new double[MODEL.componentCount()];n[MODEL.components().indexOf(name)]=1;return n;}
    static double[] critical(String name){var p=CATALOG.requirePackage(NETWORK);var pr=p.properties().get(p.components().indexOf(name)).pr();return new double[]{pr.criticalTemperature(),pr.criticalPressure()};}
    static FluidThermodynamics.State flash(double t,double p,double[] n){return MODEL.flashTP(t,p,n,()->{});}
    static int slot(FluidThermodynamics.State s){return s.oneFluidSlot();}
    static double slotDensity(FluidThermodynamics.State s,int slot){return FluidThermodynamics.total(s.amountsView(slot))/s.slotVolume(slot);}
    static String slotName(int slot){return slot==0?"vapour":slot==1?"liquid":slot==2?"aqueous":"-";}

    static double safe(java.util.function.DoubleSupplier f){try{return f.getAsDouble();}catch(RuntimeException refused){System.out.println("   (refused: "+refused.getMessage()+")");return Double.NaN;}}
    public static void main(String[] args) {
        System.out.println("== 1. The onset: the residual over the dilute value, gas at 300 K (vapour slot)");
        for(String name:new String[]{"Nitrogen","Methane"})for(double mpa:new double[]{0.1,0.4,2,7,10}) {
            double[] n=pure(name);var s=flash(300,mpa*1e6,n);int k=slot(s);double rho=slotDensity(s,k);
            double rr=MODEL.viscosity.reducedDensity(rho,s.amountsView(k)),dilute=MODEL.viscosity.vapor(300,s.amountsView(k)),res=MODEL.viscosity.residual(rho,s.amountsView(k));
            double now=MODEL.slotViscosity(s,k,null,null);
            System.out.println(f("%-8s %5.1f MPa slot %-6s rho_r %.4f delta %.4f dilute %.3f uPa s residual %.4f uPa s = %.3f %% | weight %.4f | now %.4f uPa s (%s the dilute value)",
                    name,mpa,slotName(k),rr,s.reducedDensity(),dilute*1e6,res*1e6,100*res/dilute,MixtureViscosity.denseOnset(rr),now*1e6,
                    Double.doubleToLongBits(now)==Double.doubleToLongBits(dilute)?"bit for bit":"not"));
        }
        System.out.println("== 2. The viscosity at Tc above Pc: below (Tc - 0.05 K), above (Tc + 0.05 K), uPa s");
        for(String name:new String[]{"CarbonDioxide","Propane"})for(double pr:new double[]{1.1,1.5,3}) {
            double[] c=critical(name),n=pure(name);double p=pr*c[1];
            var below=flash(c[0]-.05,p,n);var above=flash(c[0]+.05,p,n);int kb=slot(below),ka=slot(above);
            double muBelow=safe(()->MODEL.slotViscosity(below,kb,null,null)),muAboveNow=MODEL.slotViscosity(above,ka,null,null);
            // Before S2b: the vapour slot read Wilke dilute; a liquid slot read the log-mixing rule (VAPOR_DATA above Tc: dilute).
            double muAboveOld=safe(()->ka==0?MODEL.viscosity.vapor(above.temperature(),above.amountsView(ka)):MODEL.viscosity.liquid(above.temperature(),above.amountsView(ka)).pascalSeconds());
            double muBelowOld=safe(()->kb==0?MODEL.viscosity.vapor(below.temperature(),below.amountsView(kb)):MODEL.viscosity.liquid(below.temperature(),below.amountsView(kb)).pascalSeconds());
            System.out.println(f("%-13s Pr %.1f (%.2f MPa): below slot %s %.2f (before %.2f) | above slot %s delta %.3f before %.2f now %.2f | step before x%.2f now x%.2f",
                    name,pr,p/1e6,slotName(kb),muBelow*1e6,muBelowOld*1e6,slotName(ka),above.reducedDensity(),muAboveOld*1e6,muAboveNow*1e6,muBelowOld/muAboveOld,muBelow/muAboveNow));
        }
        var films=HeatCurve.Films.DEFAULT;
        System.out.println("== 3. The film of carbon dioxide at 320 K under bounds 0.5/1.5 and 0.75/1.25 (films "+films+")");
        for(double mpa:new double[]{6,8,10,12,20}) {
            var s=flash(320,mpa*1e6,pure("CarbonDioxide"));double d=s.reducedDensity();
            double w1=MixtureViscosity.smoothstep((d-.5)/1.0),w2=MixtureViscosity.smoothstep((d-.75)/.5);
            System.out.println(f("%4.0f MPa slot %-6s delta %.3f | 0.5/1.5: w %.3f film %.0f | 0.75/1.25: w %.3f film %.0f | HeatCurve.liquidLikeWeight %.3f",
                    mpa,slotName(slot(s)),d,w1,films.at(1-w1,w1),w2,films.at(1-w2,w2),HeatCurve.liquidLikeWeight(s)));
        }
        System.out.println("== 4. The film seam at a bubble point: the single-phase liquid just above saturation, Tr 0.94 to 0.995");
        for(String name:new String[]{"CarbonDioxide","Propane"}) {
            double[] c=critical(name),n=pure(name);
            for(double tr:new double[]{.94,.96,.97,.98,.99,.995}) {
                double t=tr*c[0];double lo=.01*c[1],hi=c[1];
                for(int i=0;i<80;i++){double mid=Math.sqrt(lo*hi);var s=flash(t,mid,n);if(s.oneFluidSlot()==1)hi=mid;else lo=mid;}
                var liquid=flash(t,hi*(1+1e-6),n);double w=HeatCurve.liquidLikeWeight(liquid);
                System.out.println(f("%-13s Tr %.3f bubble %.4f MPa: liquid delta %.3f w %.3f film %.0f (the two-phase side's liquid end: %.0f)",
                        name,tr,hi/1e6,liquid.reducedDensity(),w,films.at(1-w,w),films.at(0)));
            }
        }
        System.out.println("== 4b. The alternative (weight on supercritical isotherms only): the film just above Tc, Pr 1.0 to 1.3");
        for(String name:new String[]{"CarbonDioxide","Propane"}) {
            double[] c=critical(name),n=pure(name);
            for(double pr:new double[]{1.0,1.05,1.1,1.2,1.3}) {
                var s=flash(c[0]+.05,pr*c[1],n);double w=HeatCurve.liquidLikeWeight(s);
                System.out.println(f("%-13s Pr %.2f, Tc + 0.05 K: delta %.3f w %.3f film %.0f (below Tc: %.0f)",name,pr,s.reducedDensity(),w,films.at(1-w,w),films.at(0)));
            }
        }
        System.out.println("== 5. The isothermal sound speed 1/sqrt(rho kappa) of carbon dioxide, m/s");
        for(double t:new double[]{305,310,320})for(double target:new double[]{.8,1.0,1.2}) {
            double[] n=pure("CarbonDioxide");double lo=1e6,hi=4e7;
            for(int i=0;i<100;i++){double mid=(lo+hi)/2;var s=flash(t,mid,n);if(s.reducedDensity()<target)lo=mid;else hi=mid;}
            var s=flash(t,(lo+hi)/2,n);int k=slot(s);double rho=MODEL.slotMass(s,k)/s.slotVolume(k),kappa=s.phase(k).isothermalCompressibility();
            int label=k==0?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
            System.out.println(f("%3.0f K delta %.2f: %.3f MPa slot %-6s rho %.1f kg/m3 c_T %.1f m/s | limit now %.1f m/s",t,s.reducedDensity(),s.pressure()/1e6,slotName(k),rho,1/Math.sqrt(rho*kappa),MODEL.phaseVelocityLimit(s,label)));
        }
        System.out.println("== 6. Nitrogen at 300 K, 0.1 to 7 MPa: the slot viscosity against Wilke, the film weight, the limit");
        for(double mpa=.1;mpa<=7.0001;mpa+=.1) {
            var s=flash(300,mpa*1e6,pure("Nitrogen"));int k=slot(s);
            double rr=MODEL.viscosity.reducedDensity(slotDensity(s,k),s.amountsView(k));
            boolean exact=Double.doubleToLongBits(MODEL.slotViscosity(s,k,null,null))==Double.doubleToLongBits(MODEL.viscosity.vapor(300,s.amountsView(k)));
            if(!exact||mpa>6.75)System.out.println(f("%.1f MPa slot %s rho_r %.5f delta %.4f exact %s w %.3f",mpa,slotName(k),rr,s.reducedDensity(),exact,HeatCurve.liquidLikeWeight(s)));
        }
    }
}
