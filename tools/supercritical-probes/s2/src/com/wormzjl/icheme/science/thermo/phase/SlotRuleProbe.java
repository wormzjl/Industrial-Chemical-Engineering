package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.PhaseTraits;
import java.util.Locale;

/**
 * S2b step 5 (batch 2026-11-15-supercritical-fluids): where the slot rule O2 A (a root on a supercritical isotherm is liquid
 * iff its reduced density is at least 1) and today's rule (the phase identification parameter) disagree, over every
 * converged digest answer's FLUID phases and a grid of pure nitrogen, methane and carbon dioxide.
 */
public final class SlotRuleProbe {
    static boolean today(PhaseTraits t){return PhaseIdentification.liquidLike(t.identificationParameter(),t.compressibility());}
    static boolean o2a(PhaseTraits t){return t.supercriticalIsotherm()?t.reducedDensity()>=1:today(t);}

    public static void main(String[] args) {
        int fluid=0,supercritical=0,disagree=0;
        for(var answer:EngineDigestTest.answers()) {
            var r=answer.result();if(!r.converged())continue;
            for(var phase:r.phases()) {
                if(phase.kind()!=PhaseKind.FLUID||phase.traits()==null)continue;
                fluid++;var t=phase.traits();if(t.supercriticalIsotherm())supercritical++;
                if(today(t)!=o2a(t)){disagree++;
                    System.out.println(String.format(Locale.ROOT,"digest %s | T %.3f K P %.0f Pa | delta %.4f PIP %.4f Z %.4f | today %s O2A %s",
                            answer.id(),phase.temperature(),phase.pressure(),t.reducedDensity(),t.identificationParameter(),t.compressibility(),
                            today(t)?"liquid":"vapour",o2a(t)?"liquid":"vapour"));}
            }
        }
        System.out.println("digest: "+fluid+" FLUID phases of converged answers, "+supercritical+" on a supercritical isotherm, "+disagree+" disagree");
        var catalog=MaterialCatalog.bundled();
        var model=FluidThermodynamics.forNetwork(catalog,"icheme:refinery");
        var pack=catalog.requirePackage("icheme:refinery");
        int grid=0,gridDisagree=0,oldRange=0;
        for(String name:new String[]{"Nitrogen","Methane","CarbonDioxide"}) {
            int i=model.hydrocarbon.components().indexOf(name);
            var pr=pack.properties().get(pack.components().indexOf(name)).pr();
            double tc=pr.criticalTemperature(),pc=pr.criticalPressure();
            double oldMax=name.equals("CarbonDioxide")?2e6:1e7;
            int count=0,dis=0;double firstDelta=Double.NaN;
            for(int a=0;a<=40;a++)for(int b=1;b<=90;b++) {
                double tr=1.0+0.05*a,prr=0.1*b,t=tr*tc,p=prr*pc;
                double[] n=new double[model.hydrocarbon.componentCount()+1];n[i]=1;
                HydrocarbonPhaseHolder h=HydrocarbonPhaseHolder.of(model,t,p,n);
                if(h==null)continue;
                var tr0=h.traits;count++;grid++;
                if(today(tr0)!=o2a(tr0)){dis++;gridDisagree++;
                    boolean inside=p<=oldMax;if(inside)oldRange++;
                    System.out.println(String.format(Locale.ROOT,"grid %s Tr %.2f Pr %.1f (%.3f MPa%s) | delta %.4f PIP %.4f Z %.4f | today %s O2A %s",
                            name,tr,prr,p/1e6,inside?", INSIDE the old range":"",tr0.reducedDensity(),tr0.identificationParameter(),tr0.compressibility(),
                            today(tr0)?"liquid":"vapour",o2a(tr0)?"liquid":"vapour"));}
            }
            System.out.println("grid "+name+" (Tc "+tc+" K, Pc "+pc+" Pa): "+count+" states, "+dis+" disagree");
        }
        System.out.println("grid: "+grid+" states, "+gridDisagree+" disagree, "+oldRange+" inside the old ranges");
    }

    /** One root of a pure fluid at (t, p) on the network model: its traits (one root above Tc, so either slot's). */
    static final class HydrocarbonPhaseHolder {
        final PhaseTraits traits;
        private HydrocarbonPhaseHolder(PhaseTraits traits){this.traits=traits;}
        static HydrocarbonPhaseHolder of(FluidThermodynamics model,double t,double p,double[] n) {
            try {
                var phase=model.hydrocarbon.phase(t,p,n,PengRobinsonKernel.Root.VAPOR);
                return new HydrocarbonPhaseHolder(phase.traits());
            } catch(RuntimeException refused) {
                System.out.println("refused at "+t+" K "+p+" Pa: "+refused.getMessage());
                return null;
            }
        }
    }
}
