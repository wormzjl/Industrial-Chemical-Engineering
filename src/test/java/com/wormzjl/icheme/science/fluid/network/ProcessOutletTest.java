package com.wormzjl.icheme.science.fluid.network;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;
class ProcessOutletTest extends PortFixture {
    private FluidThermodynamics.State mixture(double oil,double waterFraction,double capacity){
        double[] n=new double[mw.length];double t=298.15,p=250000;
        n[index("N-pentane")]=oil*626/mw[index("N-pentane")];n[water]=waterFraction*997/mw[water];n[nitrogen]=p*Math.max(.05,1-oil-waterFraction)/(FluidThermodynamics.R*t);
        var state=model.flashTP(t,p,n,NOOP);
        for(int i=0;i<10;i++){
            if(oil>0)n[index("N-pentane")]*=oil*capacity/TestPhases.liquidVolume(state);
            if(waterFraction>0)n[water]*=waterFraction*capacity/TestPhases.waterVolume(state);
            n[nitrogen]*=Math.max(.05,1-oil-waterFraction)*capacity/state.vaporVolume();
            state=model.flashTP(t,p,n,NOOP);
        }
        return state;
    }
    private PassiveStepSolver.Result run(PassiveNetwork.PhasePort port,double oil,double waterFraction,VesselGeometry geometry,double dt){return run(port,oil,waterFraction,geometry,dt,.01);}
    private PassiveStepSolver.Result run(PassiveNetwork.PhasePort port,double oil,double waterFraction,VesselGeometry geometry,double dt,double bore){
        var state=mixture(oil,waterFraction,geometry.capacity());
        var inventory=new PassiveNetwork.Inventory(geometry.capacity(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),geometry.height(),false,geometry);
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
            List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,bore,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)).withPorts(port,PassiveNetwork.PhasePort.BULK)));
        return new PassiveStepSolver(model).solve(graph,dt,NOOP);
    }
    @Test void ordinaryBottomDrawsEqualLiquidVolumes(){
        var result=run(PassiveNetwork.PhasePort.LIQUID,.4,.4,VesselGeometry.prism(1,1),.1);
        var stream=result.pipeTransfers().getFirst().forward();assertTrue(stream.phaseVolumes()[0]>0);assertTrue(stream.phaseVolumes()[1]>0);
        assertEquals(stream.phaseVolumes()[0],stream.phaseVolumes()[1],1e-8*java.util.Arrays.stream(stream.phaseVolumes()).sum());
        assertEquals(0,stream.phaseVolumes()[2],1e-14);
    }
    @Test void bootAndBodyHaveSeparateLiquidStreams(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        var boot=run(PassiveNetwork.PhasePort.BOOT_WATER,.4,.4,g,.1).pipeTransfers().getFirst().forward();
        var body=run(PassiveNetwork.PhasePort.BOOT_HC,.4,.4,g,.1).pipeTransfers().getFirst().forward();
        assertTrue(boot.phaseVolumes()[1]>0);assertEquals(0,boot.phaseVolumes()[0],1e-14);
        assertTrue(body.phaseVolumes()[0]>0);assertEquals(0,body.phaseVolumes()[1],1e-14);
    }
    @Test void hcOutletKeepsWaterBelowWeirAndSpillsAboveIt(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        var low=run(PassiveNetwork.PhasePort.BOOT_HC,0,.1,g,.1).pipeTransfers().getFirst().forward();
        var high=run(PassiveNetwork.PhasePort.BOOT_HC,0,.8,g,.1).pipeTransfers().getFirst().forward();
        assertEquals(0,low.phaseVolumes()[1],1e-14);assertTrue(low.phaseVolumes()[2]>0);
        assertTrue(high.phaseVolumes()[1]>0);assertEquals(0,high.phaseVolumes()[2],1e-14);
    }
    @Test void bootFallsBackToHydrocarbonAndThenGas(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        var oil=run(PassiveNetwork.PhasePort.BOOT_WATER,.4,0,g,.1).pipeTransfers().getFirst().forward();
        var gas=run(PassiveNetwork.PhasePort.BOOT_WATER,0,0,g,.1).pipeTransfers().getFirst().forward();
        assertTrue(oil.phaseVolumes()[0]>0);assertEquals(0,oil.phaseVolumes()[1],1e-14);
        assertTrue(gas.phaseVolumes()[2]>0);assertEquals(0,gas.phaseVolumes()[0]+gas.phaseVolumes()[1],1e-14);
    }
    @Test void aStepCrossingTheWeirRetainsItsWaterAndCarriesTheRestAsGas(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        double fraction=g.weirVolume()/g.capacity()*1.0001;
        var result=run(PassiveNetwork.PhasePort.BOOT_HC,0,fraction,g,1,.05);
        var stream=result.pipeTransfers().getFirst().forward();
        assertEquals(g.weirVolume(),FluidThermodynamics.phaseVolume(result.states().getFirst(),FluidThermodynamics.AQUEOUS),1e-7);
        assertTrue(stream.phaseVolumes()[1]>0);assertTrue(stream.phaseVolumes()[2]>0);
    }
    @Test void waterFeedAtTheWeirIsHandledWithinTheSameStep(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        var old=mixture(0,g.weirVolume()/g.capacity(),g.capacity());
        double[] n=PhaseLayout.totalAmounts(old);double factor=g.weirVolume()/TestPhases.waterVolume(old);
        n[water]*=factor;
        var state=model.flashTP(old.temperature(),old.pressure(),n,NOOP);
        // Set the water interface exactly at the weir independently of the approximate charge density.
        for(int i=0;i<3;i++){n[water]*=g.weirVolume()/TestPhases.waterVolume(state);state=model.flashTP(old.temperature(),old.pressure(),n,NOOP);}
        var inventory=new PassiveNetwork.Inventory(g.capacity(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),g.height(),false,g);
        var feed=waterSupply(300000);double massRate=.1;var rate=PhaseLayout.totalAmounts(feed);for(int c=0;c<rate.length;c++)rate[c]*=massRate/feed.mass();
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0)).withPorts(PassiveNetwork.PhasePort.BOOT_HC,PassiveNetwork.PhasePort.BULK)),
                List.of(new ScheduledTransfer.Injection(90,0,rate,massRate*feed.enthalpy()/feed.mass())));
        for(double step:new double[]{1,.5,.2}){
            var current=graph;var solver=new PassiveStepSolver(model);
            for(int i=0;i<Math.round(1/step);i++){
                var result=solver.solve(current,step,NOOP);
                assertEquals(g.weirVolume(),TestPhases.waterVolume(result.states().getFirst()),1e-7,"retained water cannot chatter across the weir");
                assertTrue(result.pipeTransfers().getFirst().forward().phaseVolumes()[1]>0);
                current=PassiveIntervalSolver.replace(current,result);
            }
        }
    }
    /**
     * A bottom end's vanishing-flow stream follows the amounts (the seed-236 world): 1 m3 of water carrying an 8e-24 m3
     * pentane film draws the water's own density and viscosity to round-off over a 2 L line, not a half-and-half mix. The
     * stream is half and half once each liquid fills half the line, and continuous on the way there.
     */
    @Test void aTraceFilmDoesNotTurnTheBottomStreamHalfOil(){
        int n=mw.length-1,pentane=index("N-pentane");double t=298.15,p=250000,waterMoles=997/mw[water],line=.002;
        double[] film=new double[n];film[pentane]=7e-20;
        var filmed=TestPhases.state(model,t,p,film,new double[n],waterMoles,0);var clean=TestPhases.state(model,t,p,new double[n],new double[n],waterMoles,0);
        assertTrue(FluidThermodynamics.holdsPhase(filmed,FluidThermodynamics.OIL)&&PhaseDraw.mixedLiquids(filmed,PassiveNetwork.PhasePort.LIQUID));
        double oil=FluidThermodynamics.phaseVolume(filmed,FluidThermodynamics.OIL);assertTrue(oil<1e-22);
        assertEquals(oil/line,PhaseDraw.mixedShares(filmed,line)[FluidThermodynamics.OIL],1e-12*oil/line);
        double rho=PassiveStepSolver.endDensity(model,clean,PassiveNetwork.PhasePort.LIQUID,line);
        assertEquals(rho,PassiveStepSolver.endDensity(model,filmed,PassiveNetwork.PhasePort.LIQUID,line),1e-12*rho);
        var scratch=new com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.Workspace();
        double mu=model.phaseViscosity(clean,FluidThermodynamics.AQUEOUS,scratch,null);
        assertEquals(mu,PassiveStepSolver.mixedViscosity(model,filmed,line,scratch),1e-12*mu);
        double previous=0;
        boolean half=false;
        for(double moles:new double[]{1e-6,1e-3,.1,1,5,8,10,20,100}){
            double[] layer=new double[n];layer[pentane]=moles;var state=TestPhases.state(model,t,p,layer,new double[n],waterMoles,0);
            double share=PhaseDraw.mixedShares(state,line)[FluidThermodynamics.OIL],held=FluidThermodynamics.phaseVolume(state,FluidThermodynamics.OIL);
            assertTrue(share>=previous&&share<=.5,"the oil share grows with the film and stops at half");
            if(held>=line/2){assertEquals(.5,share);half=true;}else assertEquals(held/line,share,1e-12);
            previous=share;
        }
        assertTrue(half,"the sweep reaches a film filling half the line");
    }
    /** A round vessel's bottom head weighs the solids its liquid carries, as the prismatic law's condensed mass does. */
    @Test void aSlurryHeadWeighsItsSolids(){
        var solids=new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(
                model.solids.require("icheme:demo_particle"),com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("100"),100)));
        var state=waterUnderNitrogen(.5,101325).withSolids(solids);
        var g=VesselGeometry.round(VesselGeometry.Form.SPHERE,Math.cbrt(6/Math.PI),0,0,0);
        var inventory=new PassiveNetwork.Inventory(g.capacity(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),g.height(),false,g);
        var node=new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory);
        double volume=TestPhases.waterVolume(state)+state.solidMoments().volume(),mass=model.phaseMass(state,FluidThermodynamics.AQUEOUS)+state.solidMoments().mass();
        double head=PassiveStepSolver.portHead(model,node,PassiveNetwork.PhasePort.LIQUID,state);
        assertEquals(PassiveStepSolver.GRAVITY*mass/volume*g.heightAt(volume),head,1e-9*head);
        assertTrue(head>PassiveStepSolver.GRAVITY*model.phaseMass(state,FluidThermodynamics.AQUEOUS)/volume*g.heightAt(volume),"the solids' weight is in the head");
    }
    @Test void aGasOnlyStateHasZeroLiquidStreamVolumes(){
        var gas=nitrogenAt(200000);
        assertEquals(0,FluidThermodynamics.phaseVolume(gas,FluidThermodynamics.OIL));
        assertEquals(0,FluidThermodynamics.phaseVolume(gas,FluidThermodynamics.AQUEOUS));
    }
    @Test void simultaneousBootDrainAndFeedShareTheWaterBalance(){
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        for(double bootBore:new double[]{.001,.05}){
            var state=mixture(0,g.weirVolume()/g.capacity(),g.capacity());
            var inventory=new PassiveNetwork.Inventory(g.capacity(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),g.height(),false,g);
            var feed=waterSupply(300000);double[] rate=PhaseLayout.totalAmounts(feed);double massRate=.5;for(int c=0;c<rate.length;c++)rate[c]*=massRate/feed.mass();
            var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory),new PassiveNetwork.Reservoir(2,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID),new PassiveNetwork.Reservoir(3,0,nitrogenAt(101325),PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(10,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withPorts(PassiveNetwork.PhasePort.BOOT_HC,PassiveNetwork.PhasePort.BULK),
                            new PassiveNetwork.Pipe(11,0,2,new PipeResistance.Geometry(10,bootBore,.000045,0)).withPorts(PassiveNetwork.PhasePort.BOOT_WATER,PassiveNetwork.PhasePort.BULK)),
                    List.of(new ScheduledTransfer.Injection(90,0,rate,massRate*feed.enthalpy()/feed.mass())));
            var result=new PassiveStepSolver(model).solve(graph,1,NOOP);
            var hc=result.pipeTransfers().stream().filter(t->t.pipeId()==10).findFirst().orElseThrow().forward();
            var boot=result.pipeTransfers().stream().filter(t->t.pipeId()==11).findFirst().orElseThrow().forward();
            assertTrue(boot.phaseVolumes()[1]>0);
            if(bootBore<.01){assertEquals(g.weirVolume(),TestPhases.waterVolume(result.states().getFirst()),1e-7);assertTrue(hc.phaseVolumes()[1]>0);}
            else{assertTrue(TestPhases.waterVolume(result.states().getFirst())<g.weirVolume());assertEquals(0,hc.phaseVolumes()[1],1e-12);}
        }
    }
}
