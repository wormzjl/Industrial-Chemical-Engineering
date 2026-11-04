package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VelocityClampTest {
    private FluidThermodynamics model(double limit){return FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),"icheme:tjl20_methane_nitrogen",limit, FluidThermodynamics.DEFAULT_TRACE_CUTOFF_MOLE_FRACTION);}
    private FluidThermodynamics.State state(FluidThermodynamics model,double pressure,boolean wet) {
        double[] n=new double[model.componentCount()];if(wet){n=Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage("icheme:tjl20_methane_nitrogen").crudeFeed("icheme:tia_juana_light_methane").moleFractions(),model.componentCount());n[model.componentCount()-1]=.2;}else n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var unit=model.flashTP(350,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();return model.flashTP(350,pressure,n,()->{});
    }
    private PassiveNetwork graph(FluidThermodynamics model,boolean reverse,boolean wet,FlowControl control,boolean finite) {
        var a=state(model,reverse?101325:200000,wet);var b=state(model,reverse?200000:101325,wet);
        var kind=finite?PassiveNetwork.NodeKind.RESERVOIR:PassiveNetwork.NodeKind.PORT;
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,a,kind),new PassiveNetwork.Reservoir(2,0,b,kind)),List.of(new PassiveNetwork.Pipe(3,0,1,new PipeResistance.Geometry(.1,.01,0,0),control)));
    }
    private void bounded(FluidThermodynamics model,PassiveNetwork graph,PassiveStepSolver.Result result) {
        var pipe=graph.pipes().getFirst();double q=result.massFlows()[0];var donor=result.states().get(q>=0?0:1);
        double limit=donor.mass()/donor.volume()*pipe.minimumArea()*model.velocityLimit(donor);
        assertTrue(Math.abs(q)<=limit*(1+2e-8)+1e-12,"q="+q+" cap="+limit);
    }
    /** The velocity cap is part of the line's law (2026-10-18, M7/M8 option C): a 99 kPa drive on a 0.1 m line of 10 mm chokes it, and
     * the converged flow stands under the cap flow of the end it leaves, the saturation ({@link PassiveStepSolver#saturated})
     * of the flow the law gives for the driving pressure. */
    @Test void configuredAndAcousticLimitsCapBothDirectionsAndPreserveExternalLedgers() {
        for(double limit:new double[]{100,100000})for(boolean reverse:new boolean[]{false,true}) {
            var model=model(limit);var graph=graph(model,reverse,false,new FlowControl.Passive(),false);
            var result=new PassiveStepSolver(model).solve(graph,1,()->{});bounded(model,graph,result);
            var donor=result.states().get(reverse?1:0);var other=result.states().get(reverse?0:1);var pipe=graph.pipes().getFirst();
            double cap=donor.mass()/donor.volume()*pipe.minimumArea()*model.velocityLimit(donor),q=result.massFlows()[0];
            assertTrue(Math.abs(q)<cap&&Math.abs(q)>.9*cap,"q="+q+" cap="+cap);assertEquals(reverse?-1:1,Math.signum(q));
            double rho=donor.mass()/donor.volume(),mu=model.viscosity.vapor(350,donor.vapor(),0),driving=Math.abs(donor.pressure()-other.pressure());
            double lo=0,hi=cap;while(pipe.pressureDrop(hi,rho,mu)<driving)hi*=2;
            for(int j=0;j<60;j++){double mid=(lo+hi)/2;if(pipe.pressureDrop(mid,rho,mu)>driving)hi=mid;else lo=mid;}
            assertEquals(PassiveStepSolver.saturated((lo+hi)/2,cap),Math.abs(q),1e-6*cap,"the transported flow is the saturated law's");
            assertEquals(FlowControl.Mode.PASSIVE,result.modes().getFirst());
            assertArrayEquals(new double[model.componentCount()],result.externalMoles(),1e-9);assertEquals(0,result.externalEnergyJoule(),1e-6);
            assertEquals(350,result.states().getFirst().temperature()); // fixed boundary, not a clamp-imposed temperature
        }
    }
    @Test void threePhaseClampingTransfersAllPhasesAndDevicesRespectTheRateLimit() {
        var model=model(.2);
        for(FlowControl control:List.of(new FlowControl.Passive(),new FlowControl.Pump(1,500000),new FlowControl.PressureValve(150000))) {
            var graph=graph(model,false,true,control,false);var result=new PassiveStepSolver(model).solve(graph,.1,()->{});bounded(model,graph,result);
            if(control instanceof FlowControl.Pump) {
                // The liquid-only pump (decisions D1, D6 of the phase-ports batch) refuses the three-phase supply: closed with
                // its typed mode, carrying exactly nothing and doing no work; the island still solves.
                assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,result.modes().getFirst());assertEquals(0,result.massFlows()[0]);assertEquals(0,result.pumpWorkJoule());
                assertTrue(InletPhase.supply(model,graph,0,1,.1).vapourVolumeShare()>FlowControl.Pump.VAPOUR_REFUSE);
                assertArrayEquals(new double[model.componentCount()],result.externalMoles(),1e-9);assertEquals(0,result.externalEnergyJoule(),1e-4);
                continue;
            }
            var stream=result.pipeTransfers().getFirst().forward();for(double volume:stream.phaseVolumes())assertTrue(volume>0);
            double[] moved=stream.componentMoles();var donor=graph.reservoirs().getFirst();var n=donor.inventory().moles();
            for(int c=0;c<n.length;c++)assertEquals(stream.massKg()*n[c]/donor.state().mass(),moved[c],1e-10+1e-8*Math.abs(moved[c]));
            assertArrayEquals(new double[model.componentCount()],result.externalMoles(),1e-9);assertEquals(-result.pumpWorkJoule(),result.externalEnergyJoule(),1e-4);
        }
    }
    @Test void finiteReservoirTemperatureComesFromConservedEnergyAndEosAtTheClampedRate() {
        var model=model(1);var graph=graph(model,false,false,new FlowControl.Passive(),true);
        var result=new PassiveStepSolver(model).solve(graph,1,()->{});bounded(model,graph,result);
        double beforeU=0,afterU=0,beforeN=0,afterN=0;
        for(int i=0;i<2;i++) {
            var old=graph.reservoirs().get(i);var next=result.inventories().get(i);var state=result.states().get(i);
            beforeU+=old.inventory().internalEnergy();afterU+=next.internalEnergy();beforeN+=old.inventory().moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN];afterN+=next.moles()[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN];
            var eos=model.flashTP(state.temperature(),state.pressure(),next.moles(),()->{});
            assertEquals(next.volume(),eos.volume(),1e-8);assertEquals(next.internalEnergy(),eos.internalEnergy(),1e-4+1e-6*Math.abs(next.internalEnergy()));
        }
        assertEquals(beforeN,afterN,1e-10+1e-8*beforeN);assertEquals(beforeU,afterU,1e-4+1e-6*Math.abs(beforeU));
        assertTrue(Math.abs(result.states().getFirst().temperature()-350)>1e-5,"The clamp must not hold temperature fixed");
    }
    /** A certificate issued under one velocity limit is not kept under another (the model's revision is its signature's,
     * IslandCertificate.Signature), and a saved island's inventory stays readable across the change (the checkpoint's
     * package table holds the thermodynamic revision). Restated from the fallback anchor's refusal (owner decision D37, S2). */
    @Test void invalidConfigurationIsRejectedAndADifferentLimitMovesTheModelsRevision() {
        assertThrows(IllegalArgumentException.class,()->model(0));assertThrows(IllegalArgumentException.class,()->model(Double.NaN));
        assertNotEquals(model(100).revision(),model(10).revision());
        assertEquals(model(100).thermodynamicRevision(),model(10).thermodynamicRevision());
    }
    @Test void aWarmWorkspaceReleasesTheClampWhenThePressureDriveFalls() {
        var model=model(1);var solver=new PassiveStepSolver(model);var high=graph(model,false,false,new FlowControl.Passive(),false);
        var source=high.reservoirs().getFirst().state();double cap=source.mass()/source.volume()*high.pipes().getFirst().minimumArea()*model.velocityLimit(source);
        var capped=solver.solve(high,1,()->{});assertTrue(capped.massFlows()[0]<=cap&&capped.massFlows()[0]>cap*(1-1e-6),"choked: "+capped.massFlows()[0]+" of "+cap);
        var low=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state(model,101325.1,false),PassiveNetwork.NodeKind.PORT),new PassiveNetwork.Reservoir(2,0,state(model,101325,false),PassiveNetwork.NodeKind.PORT)),high.pipes());
        var released=solver.solve(low,1,()->{});assertEquals(FlowControl.Mode.PASSIVE,released.modes().getFirst());bounded(model,low,released);
        var donor=low.reservoirs().getFirst().state();double rho=donor.mass()/donor.volume(),mu=model.viscosity.vapor(350,donor.vapor(),0);
        double expected=.1*Math.PI*rho*Math.pow(.01,4)/(128*mu*.1);
        assertEquals(expected,released.massFlows()[0],1e-10+1e-4*expected);
        double again=solver.solve(high,1,()->{}).massFlows()[0];assertTrue(again<=cap&&again>cap*(1-1e-6),"choked again: "+again+" of "+cap);
    }
}
