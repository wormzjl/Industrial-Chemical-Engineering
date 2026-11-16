package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;

/**
 * S2c step 2: what the slot readers give for one dense fluid on each side of reduced density 1, on two states 0.1 % apart in
 * density (carbon dioxide at 310 K, nitrogen at 150 K), and the pump of step 3 (carbon dioxide at 320 K and 15 MPa).
 */
public final class SlotReaderProbe {
    static final FluidThermodynamics MODEL=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:refinery");
    static final PassiveStepSolver SOLVER=new PassiveStepSolver(MODEL);
    static String f(String s,Object... a){return String.format(Locale.ROOT,s,a);}
    static double[] pure(String name){double[] n=new double[MODEL.componentCount()];n[MODEL.components().indexOf(name)]=1;return n;}
    static FluidThermodynamics.State at(double t,double p,double[] n){return MODEL.flashTP(t,p,n,()->{});}
    /** The pressure at temperature t where the reduced density is target. */
    static double pressureAt(double t,double[] n,double target) {
        double lo=1e5,hi=6e7;
        for(int i=0;i<200&&hi-lo>1e-13*hi;i++){double mid=(lo+hi)/2;if(at(t,mid,n).reducedDensity()<target)lo=mid;else hi=mid;}
        return (lo+hi)/2;
    }
    static double[] all(FluidThermodynamics.State s,int phase){double[] d=new double[3];d[phase]=FluidThermodynamics.phaseVolume(s,phase);return d;}
    static double safe(java.util.function.DoubleSupplier g){try{return g.getAsDouble();}catch(RuntimeException e){System.out.println("   (refused: "+e.getMessage()+")");return Double.NaN;}}
    static void side(String name,FluidThermodynamics.State s,double height) {
        int slot=s.oneFluidSlot();int label=slot==FluidThermodynamics.State.VAPOR?FluidThermodynamics.GAS:FluidThermodynamics.OIL;
        double p=s.pressure(),rho=s.mass()/s.volume();
        // The level head of a LIQUID port of a full prism vessel of the height: g m_c H / V (PortStreams.portHead, PRISM).
        double head=PassiveStepSolver.GRAVITY*MODEL.liquidMass(s)*height/s.volume();
        var bulk=InletPhase.bulk(MODEL,s);
        var drawn=InletPhase.of(MODEL,s,all(s,label));
        double release=safe(()->SOLVER.suctions.release(s,()->{}));
        double edge=safe(()->SOLVER.suctions.zoneEdge(drawn,()->{}));
        double limit=ReleasePressure.limit(release,edge,0);
        double headShare=GasEntrainment.headShare(bulk.vapourVolumeShare(),p);
        double wet=CentrifugalCompressor.wetEfficiency(1,bulk.condensedMassShare());
        System.out.println(f("%s: %.4f MPa slot %d delta %.6f rho %.2f kg/m3 | LIQUID-port head %.1f Pa | pump: vapour share %.3f head share %.4f clean %s, drawn condensed %s, p_v %s, edge E %s, limit %s | compressor: condensed share %.3f wet efficiency x%.3f | blanket gas %s",
                name,p/1e6,slot,s.reducedDensity(),rho,head,bulk.vapourVolumeShare(),headShare,bulk.vapourVolumeShare()<GasEntrainment.cleanShare(p),drawn.condensed(),
                release==Double.POSITIVE_INFINITY?"none":f("%.4f MPa",release/1e6),edge==Double.POSITIVE_INFINITY?"none":f("%.4f MPa",edge/1e6),limit==Double.POSITIVE_INFINITY?"none":f("%.4f MPa",limit/1e6),
                bulk.condensedMassShare(),wet,FluidThermodynamics.holdsPhase(s,FluidThermodynamics.GAS)&&s.vaporVolume()>0));
    }
    public static void main(String[] args) {
        Object[][] cases={{"CarbonDioxide",310.0},{"Nitrogen",150.0}};
        for(var c:cases) {
            double[] n=pure((String)c[0]);double t=(Double)c[1];
            double p1=pressureAt(t,n,1/1.0005),p2=pressureAt(t,n,1.0005);
            System.out.println("== "+c[0]+" at "+t+" K, reduced density 0.9995 and 1.0005");
            side("below",at(t,p1,n),5);side("above",at(t,p2,n),5);
        }
        System.out.println("== Step 3: carbon dioxide at 320 K and 15 MPa");
        side("pump suction",at(320,15e6,pure("CarbonDioxide")),5);
        System.out.println("== A dense natural gas (CH4 .90, C2H6 .06, C3H8 .03, N2 .01) at 300 K, reduced density 0.9995 and 1.0005");
        double[] g=new double[MODEL.componentCount()];g[MODEL.components().indexOf("Methane")]=.90;g[MODEL.components().indexOf("Ethane")]=.06;g[MODEL.components().indexOf("Propane")]=.03;g[MODEL.components().indexOf("Nitrogen")]=.01;
        double q1=pressureAt(300,g,1/1.0005),q2=pressureAt(300,g,1.0005);
        side("below",at(300,q1,g),5);side("above",at(300,q2,g),5);
    }
}
