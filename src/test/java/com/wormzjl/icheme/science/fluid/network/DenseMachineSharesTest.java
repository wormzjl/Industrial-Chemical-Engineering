package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalCompressor;
import com.wormzjl.icheme.science.machine.GasEntrainment;
import com.wormzjl.icheme.science.machine.Wear;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What a pump and a compressor read of one dense fluid (S4 of batch 2026-11-15-supercritical-fluids,
 * S4_MACHINES_REVIEW.md): its vapour share {@code 1 - w} and condensed share {@code w} on its liquid-like weight, whichever
 * slot holds it, so the pump's head share and the compressor's wet efficiency follow its density across the slot line,
 * and are the slots' own values to the bit outside reduced densities 0.5 to 1.5. A gas that is no dense fluid's window
 * reads what it read before; neither share refuses a machine. What needs a second phase does not occur in one dense fluid
 * (S4b): a pump drawing it through a line is not vapour-locked by its density, it has no vapour pressure, and a
 * compressor sees no droplets in it, while its wet efficiency still reads {@code w}.
 */
class DenseMachineSharesTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    /** The bound on a neighbour's change, per 0.05 of reduced density: the weight's slope is at most 1.5 per unit of it, so
     * the condensed share moves at most 0.075, the wet efficiency half that; the head share also reads the pressure. */
    private static final double HEAD_STEP=.1,WET_STEP=.04;

    private double[] pure(String name){double[] n=new double[model.componentCount()];n[model.components().indexOf(name)]=1;return n;}
    private FluidThermodynamics.State at(double t,double p,double[] n){return model.flashTP(t,p,n,()->{});}
    private double pressureAt(double t,double[] n,double target) {
        double lo=1e5,hi=8e7;
        for(int i=0;i<200&&hi-lo>1e-13*hi;i++){double mid=(lo+hi)/2;if(at(t,mid,n).reducedDensity()<target)lo=mid;else hi=mid;}
        return (lo+hi)/2;
    }
    private static double head(InletPhase.Supply s,double p){return GasEntrainment.headShare(s.vapourVolumeShare(),p);}
    private static double wet(InletPhase.Supply s){return CentrifugalCompressor.wetEfficiency(1,s.condensedMassShare());}

    @Test void carbonDioxideAt310KReadsItsDensityAcrossTheSlotLine() {
        double[] co2=pure("CarbonDioxide");
        double lastHead=Double.NaN,lastWet=Double.NaN;
        for(int i=0;i<=30;i++) {
            double delta=.3+.05*i;var s=at(310,pressureAt(310,co2,delta),co2);var supply=InletPhase.bulk(model,s);
            assertTrue(s.dense(),"one dense fluid at reduced density "+delta);
            double w=FluidThermodynamics.liquidLikeWeight(s),p=s.pressure();
            assertEquals(1-w,supply.vapourVolumeShare(),0,"the vapour share at "+delta);
            assertEquals(w,supply.condensedMassShare(),0,"the condensed share at "+delta);
            // Outside the window the slot's own values, as they were before the weight.
            if(s.reducedDensity()<=FluidThermodynamics.GAS_LIKE_UNTIL){
                assertEquals(FluidThermodynamics.State.VAPOR,s.oneFluidSlot());assertEquals(1.0,supply.vapourVolumeShare(),0);assertEquals(0.0,supply.condensedMassShare(),0);
                assertEquals(GasEntrainment.headShare(1,p),head(supply,p),0);assertEquals(1.0,wet(supply),0);
            }
            if(s.reducedDensity()>=FluidThermodynamics.LIQUID_LIKE_FROM){
                assertEquals(FluidThermodynamics.State.LIQUID,s.oneFluidSlot());assertEquals(0.0,supply.vapourVolumeShare(),0);assertEquals(1.0,supply.condensedMassShare(),0);
                assertEquals(1.0,head(supply,p),0);assertEquals(1-CentrifugalCompressor.WET_EFFICIENCY_LOSS,wet(supply),0);
            }
            if(i>0){
                assertTrue(Math.abs(head(supply,p)-lastHead)<HEAD_STEP,"the head share between neighbours at "+delta+": "+lastHead+" to "+head(supply,p));
                assertTrue(Math.abs(wet(supply)-lastWet)<WET_STEP,"the wet efficiency between neighbours at "+delta+": "+lastWet+" to "+wet(supply));
            }
            lastHead=head(supply,p);lastWet=wet(supply);
        }
    }

    @Test void theSlotLineIsNoStep() {
        double[] co2=pure("CarbonDioxide");
        var below=at(310,pressureAt(310,co2,1/1.0005),co2);var above=at(310,pressureAt(310,co2,1.0005),co2);
        assertEquals(FluidThermodynamics.State.VAPOR,below.oneFluidSlot());assertEquals(FluidThermodynamics.State.LIQUID,above.oneFluidSlot());
        var a=InletPhase.bulk(model,below);var b=InletPhase.bulk(model,above);
        // S2c measured 0.5303 to 1 (head) and x1 to x0.5 (wet efficiency) across these two states.
        assertEquals(head(a,below.pressure()),head(b,above.pressure()),1e-2,"the head share is continuous at the slot line");
        assertEquals(wet(a),wet(b),1e-3,"the wet efficiency is continuous at the slot line");
        assertEquals(a.vapourVolumeShare(),b.vapourVolumeShare(),2e-3);
    }

    @Test void nitrogenAt300KReadsWhatItReadBefore() {
        double[] n2=pure("Nitrogen");
        for(double p:new double[]{1e5,3e5,1e6,3e6,5e6,7e6,1e7}) {
            var s=at(300,p,n2);var supply=InletPhase.bulk(model,s);
            assertEquals(FluidThermodynamics.State.VAPOR,s.oneFluidSlot());
            assertTrue(s.reducedDensity()<FluidThermodynamics.GAS_LIKE_UNTIL,"a gas below the window at "+p);
            assertEquals(1.0,supply.vapourVolumeShare(),0);assertEquals(0.0,supply.condensedMassShare(),0);
            assertEquals(GasEntrainment.headShare(1,p),head(supply,p),0);assertEquals(1.0,wet(supply),0);
            assertFalse(InletPhase.of(model,s,null).condensed(),"no liquid to give the pump an edge");
            assertEquals(0,ReleasePressure.vapourVolumeShare(s),0,"a dense trial releases no vapour (S4b; NaN before S4)");
        }
    }

    /** Neither share enters the refusal: a pump and a compressor on a generator of dense carbon dioxide run at every density. */
    @Test void neitherMachineIsRefusedAcrossTheWindow() {
        double[] co2=pure("CarbonDioxide");
        for(double delta:new double[]{.3,.6,.9995,1.0005,1.4,1.8}) {
            var s=at(310,pressureAt(310,co2,delta),co2);
            for(FlowControl mover:List.<FlowControl>of(new FlowControl.Pump(.01,5e5),new FlowControl.Compressor(.01,2))) {
                var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,s,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,s,PassiveNetwork.NodeKind.VOID)),
                        List.of(new PassiveNetwork.Pipe(10,0,1,List.of(new PipeResistance.Geometry(10,.05,.000045,0)),mover,0,null,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK)));
                assertFalse(InletPhase.decide(model,graph,null,1)[0],mover+" at reduced density "+delta);
            }
        }
    }

    /**
     * A centrifugal pump drawing carbon dioxide at 310 K from a generator through a 10 m line and a junction, lifting it
     * 20 kPa into a void (S4b). As first implemented (S4) the pump's edge was its supplier's own pressure from reduced
     * density 0.5 to about 1.1, so it was vapour-locked there and delivered nothing; one dense fluid has no second phase, so
     * it runs on its head share {@code M(1 - w)} at every density. At 1.1 its isenthalp flashes from the bubble side at
     * 7.35 MPa, 1.1 MPa under the suction: an edge, and no lock.
     */
    @Test void aPumpDrawingDenseCarbonDioxideThroughALineIsNotVapourLocked() {
        double[] co2=pure("CarbonDioxide");var law=new com.wormzjl.icheme.science.machine.CentrifugalPump(.01,50,.7);
        for(double delta:new double[]{.6,.8,1.0,1.1}) {
            var s=at(310,pressureAt(310,co2,delta),co2);double p=s.pressure();
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,s,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,s,PassiveNetwork.NodeKind.JUNCTION),
                    new PassiveNetwork.Reservoir(3,0,at(310,p+2e4,co2),PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.05,.000045,0),new FlowControl.Passive()),new PassiveNetwork.Pipe(11,1,2,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Machine(law,1,true))));
            var solver=new PassiveStepSolver(model);var result=solver.solve(graph,1,()->{});
            assertFalse(solver.suctions.vapourLocked(1),"not vapour-locked at reduced density "+delta);
            assertNotEquals(FlowControl.Mode.CLOSED,result.modes().get(1),"the pump runs at "+delta);
            assertTrue(result.massFlows()[1]>0,"it delivers at "+delta+": "+result.massFlows()[1]);
            double w=FluidThermodynamics.liquidLikeWeight(s);
            var running=(com.wormzjl.icheme.science.machine.CentrifugalPump)((FlowControl.Machine)solver.suctions.mover(graph,1)).law();
            assertEquals(GasEntrainment.headShare(1-w,p),running.ratedHead()/law.ratedHead(),1e-12,"its head share M(1 - w) at "+delta);
            double limit=solver.suctions.suctionLimitPressure(graph,1,0);
            if(delta<1.05)assertEquals(Double.POSITIVE_INFINITY,limit,0,"no suction limit without a flash at "+delta);
            else assertEquals(7.3526e6,limit,7.3526e6*2e-4,"the bubble-side flash at "+delta);
        }
    }

    /**
     * A centrifugal compressor on dense carbon dioxide at 310 K (S4b): its observed droplet share, which its wet gas wear
     * and its WET GAS word read, is 0 at every density, while its wet efficiency still reads its liquid-like weight. As
     * first implemented the wear read {@code w} and eroded fully from reduced density about 0.65.
     */
    @Test void aCompressorOnADenseFluidSeesNoDroplets() {
        double[] co2=pure("CarbonDioxide");
        for(double delta:new double[]{.7,1.0,1.4}) {
            var s=at(310,pressureAt(310,co2,delta),co2);double w=FluidThermodynamics.liquidLikeWeight(s);
            var machine=new FlowControl.Machine(new CentrifugalCompressor(.02,24400,CentrifugalCompressor.DEFAULT_EFFICIENCY),1,true);
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,s,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,at(310,1.2*s.pressure(),co2),PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(1,.1,.000045,0),machine)));
            var result=new PassiveStepSolver(model).solve(graph,1,()->{});
            double observed=result.observations().getFirst().condensedShare();
            assertEquals(0,observed,0,"no droplets at "+delta);
            assertEquals(0,Wear.wetGas(observed),0,"no wet gas wear at "+delta);
            assertEquals(CentrifugalCompressor.wetEfficiency(1,w),PassiveStepSolver.wetShare(model,graph,0,1),0,"the wet efficiency reads w at "+delta);
            assertEquals(w,InletPhase.bulk(model,s).condensedMassShare(),0);
        }
    }

    /** A dense natural gas at 300 K (methane 0.90, ethane 0.06, propane 0.03, nitrogen 0.01) has no vapour pressure in either
     * slot (S4b): in the liquid slot it read the bubble pressure of its ethane and propane, 2.66 MPa. */
    @Test void aDenseNaturalGasHasNoVapourPressureInEitherSlot() {
        double[] g=new double[model.componentCount()];var c=model.components();g[c.indexOf("Methane")]=.90;g[c.indexOf("Ethane")]=.06;g[c.indexOf("Propane")]=.03;g[c.indexOf("Nitrogen")]=.01;
        var solver=new PassiveStepSolver(model);
        for(double delta:new double[]{.9995,1.0005,1.6}) {
            var s=at(300,pressureAt(300,g,delta),g);
            assertTrue(s.dense());
            assertEquals(Double.POSITIVE_INFINITY,solver.suctions.release(s,()->{}),"no vapour pressure at reduced density "+delta+", slot "+s.oneFluidSlot());
        }
    }

    /** The NPSH density of a pump on one dense fluid is its own density in either slot while it is liquid-like at all. */
    @Test void aPumpReadsTheDenseFluidsOwnDensityInEitherSlot() {
        double[] co2=pure("CarbonDioxide");
        for(double delta:new double[]{.3,.6,.9995,1.0005,1.4}) {
            var s=at(310,pressureAt(310,co2,delta),co2);
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,s,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,s,PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(10,0,1,List.of(new PipeResistance.Geometry(10,.05,.000045,0)),new FlowControl.Pump(.01,5e5),0,null,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK)));
            double rho=InletPhase.liquidDensity(model,graph,0,.01,0),own=s.mass()/s.volume();
            if(delta<=FluidThermodynamics.GAS_LIKE_UNTIL)assertTrue(Double.isNaN(rho),"a gas-like fluid holds no liquid, as before: "+rho);
            else assertEquals(own,rho,own*1e-12,"its own density at "+delta);
            assertEquals(own,InletPhase.density(model,graph,0,.01,0),own*1e-12,"the rise reads its own density at "+delta);
        }
    }

    /** Wear (step 4): a pump on dense carbon dioxide has no vapour pressure, so no cavitation read and no erosion from it;
     * its gas lock reads the same vapour share as its head. */
    @Test void aDenseFluidGivesNoCavitationWear() {
        double[] co2=pure("CarbonDioxide");
        var solver=new PassiveStepSolver(model);
        for(double delta:new double[]{.6,1.0005,1.6}) {
            var s=at(320,pressureAt(320,co2,delta),co2);
            assertEquals(Double.POSITIVE_INFINITY,solver.suctions.release(s,()->{}),"no vapour pressure above Tc at "+delta);
            assertEquals(0,Wear.Fault.CAVITATION.intensity(Double.NaN),0,"no cavitation read, no erosion");
            var supply=InletPhase.bulk(model,s);
            assertEquals(1-FluidThermodynamics.liquidLikeWeight(s),supply.vapourVolumeShare(),0,"the share the gas lock and the head read");
        }
    }
}
