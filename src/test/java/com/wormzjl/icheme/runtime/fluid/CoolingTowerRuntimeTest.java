package com.wormzjl.icheme.runtime.fluid;

import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoolingTowerRuntimeTest {
    private final com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model=FluidTestSupport.networkModel();

    /**
     * Batch 2026-11-10-custom-shader-system (fx-noise): a plume leaves only with air rising through the fill. A reversed
     * draught (air drawn down the stack, negative flow) and still air publish no plume; a rising draught keeps its flow.
     */
    @Test void aReversedDraughtPublishesNoPlume(){
        assertEquals(0,FluidWorldAuthority.plumeAir(-250),"reversed: air leaves by the inlets, not the stack");
        assertEquals(0,FluidWorldAuthority.plumeAir(TowerTransfer.NO_AIR/2),"still air");
        assertEquals(0,FluidWorldAuthority.plumeAir(Double.NaN));
        assertEquals(250,FluidWorldAuthority.plumeAir(250),1e-12);
    }

    private PassiveNetwork basin(){
        var tower=CoolingTower.induced(8,2,2,1);
        var amounts=new double[model.componentCount()];amounts[amounts.length-1]=1;
        double scale=128/model.flashTP(308.15,101325,amounts,()->{}).volume();amounts[amounts.length-1]*=scale;
        var state=model.flashTP(308.15,101325,amounts,()->{});
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory)),List.of());
    }
    private static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,y,z);}
    private static long tower(EventPathWorld w,CoolingTower tower,PhysicalFluidTopology.Port... ports){
        long id=w.ledger.nextIdentity();double volume=tower.basinVolume();
        var device=new PhysicalFluidTopology.Device(id,at(1,0,1),Kind.RESERVOIR,NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),List.of(ports));
        var spec=new FluidDeviceSpec(volume,298.15,101325,FluidDeviceSpec.nitrogen(w.catalog).composition(),SlurryFeed.NONE,CoolingTower.BASIN_DEPTH,true,VesselGeometry.prism(volume,CoolingTower.BASIN_DEPTH),tower);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        return id;
    }
    private static FluidDeviceSpec water(double temperature,double pressure){return new FluidDeviceSpec(1,temperature,pressure,FluidDeviceSpec.water(EventPathWorld.CATALOG).composition());}

    @Test void aPumpedLoopKeepsUpWithItsIntervalsInEveryFanMode(){
        for(var control:List.of(CoolingTower.induced(8,2,1,1),CoolingTower.induced(8,2,1,1).withControl(CoolingTower.Mode.TARGET_TEMPERATURE,1,303))){
            var w=new EventPathWorld();w.runTo(20);
            long id=tower(w,control,new PhysicalFluidTopology.Port(at(1,0,0),NORTH,PassiveNetwork.PhasePort.SPRAY),new PhysicalFluidTopology.Port(at(0,0,2),WEST,PassiveNetwork.PhasePort.SPRAY),new PhysicalFluidTopology.Port(at(1,0,4),SOUTH,PassiveNetwork.PhasePort.LIQUID));
            w.place(-1,0,2,Kind.PIPE,WEST,.05);w.place(-2,0,2,Kind.GENERATOR,WEST,water(320,400000),.05);
            w.place(1,0,5,Kind.PIPE,SOUTH,.1);w.place(2,0,5,Kind.PIPE,EAST,.1);
            w.place(3,0,5,Kind.PUMP,EAST,new FlowControl.Pump(.02,800000),null,.1);
            w.place(4,0,5,Kind.PIPE,EAST,.1);w.place(5,0,5,Kind.PIPE,EAST,.1);
            for(int z=4;z>=-1;z--)w.place(5,0,z,Kind.PIPE,NORTH,.1);
            for(int x=4;x>=1;x--)w.place(x,0,-1,Kind.PIPE,WEST,.1);
            w.runTo(2400);w.assertKeptUp(control.mode()+" loop");
            var snapshot=w.coordinator.observe(w.registry.owner(id));var last=snapshot.lastResult().orElseThrow();var rates=last.towers().get(id);
            // Built with the basin empty: the seed gas in the line leaves by the spray inlet and the pump primes.
            int pump=java.util.stream.IntStream.range(0,snapshot.graph().pipes().size()).filter(e->snapshot.graph().pipes().get(e).control() instanceof FlowControl.Pump).findFirst().orElseThrow();
            assertTrue(last.averageMassFlows()[pump]>0,"the pump circulates: "+last.endpointModes());
            assertTrue(rates.loss(rates.fill().losses().length-1)>0,"the fill runs: "+rates);
            assertFalse(new IslandCertificate.Summary(new IslandCertificate.Interval(0,100,snapshot.graph(),last),w.coordinator.model(snapshot.id()).molecularWeights()).flushed,"Evaporation is a rate a steady tower repeats, not a flush");
        }
    }

    /**
     * No stream is refused (W3B_NETWORK_PLAN.md section 20). Ethyl mercaptan at 330 K boils at the atmosphere (its normal
     * boiling point is 308 K): it reaches the basin with no air contact, and its vapour leaves by the basin's flash and vent.
     */
    @Test void aPumpedBoilingHydrocarbonReachesTheBasinWithoutContactOrHold(){
        var w=new EventPathWorld();w.runTo(20);
        long id=tower(w,CoolingTower.induced(8,2,1,1),new PhysicalFluidTopology.Port(at(1,7,0),NORTH,PassiveNetwork.PhasePort.SPRAY),new PhysicalFluidTopology.Port(at(1,0,4),SOUTH,PassiveNetwork.PhasePort.LIQUID));
        double[] oil=new double[model.componentCount()];oil[oil.length-3]=1;
        w.place(1,7,-1,Kind.PIPE,NORTH,.05);w.place(1,7,-2,Kind.PUMP,SOUTH,new FlowControl.Pump(.005,800000),null,.05);
        w.place(1,7,-3,Kind.PIPE,NORTH,.05);w.place(1,7,-4,Kind.GENERATOR,NORTH,new FluidDeviceSpec(1,330,300000,oil),.05);
        w.runTo(600);w.assertKeptUp("pumped hydrocarbon");
        var snapshot=w.coordinator.observe(w.registry.owner(id));
        var rates=snapshot.lastResult().orElseThrow().towers().get(id);
        assertTrue(rates.status().startsWith("BOILING"),rates.status());assertEquals(0,rates.massLoss());
    }

    @Test void anEmptyBasinPublishesItsFansThroughTheScheduledDisplay(){
        for(var mode:CoolingTower.Mode.values()){
            var tower=CoolingTower.induced(8,2,1,1).withControl(mode,1,298.15);
            var empty=PassiveNetwork.emptyVessel(1,0,tower.basinVolume(),CoolingTower.BASIN_DEPTH,true,model);
            var node=new PassiveNetwork.Reservoir(1,0,empty.state(),empty.kind(),empty.inventory().withTower(tower));
            var result=new PassiveIntervalSolver(model).solve(new PassiveNetwork(List.of(node),List.of()),1,PassiveIntervalSolver.Settings.defaults(),()->{});
            var rates=result.towers().get(1L);assertNotNull(rates);
            assertEquals(mode==CoolingTower.Mode.FAN_SPEED?1:0,rates.fill().speed());
            assertEquals(0,rates.massLoss());
            if(mode==CoolingTower.Mode.FAN_SPEED)assertTrue(rates.fill().fanPowerWatt()>0);
            var view=new FluidView(1,0,0,0,"EMPTY",null,0,List.of()).withTank(new FluidView.Tank(CoolingTower.BASIN_DEPTH,true,0,0,tower.basinVolume(),null,null))
                .withTower(new FluidView.Tower(tower,rates));
            var display=TankDisplay.of(view,model.components(),model.molecularWeights(),EventPathWorld.CATALOG,
                com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(EventPathWorld.CATALOG));
            assertEquals(rates.fill().speed(),display.fanSpeed());assertEquals(0,display.level());
        }
    }

    @Test void populatedBasinAndAcceptedAtmosphericReadingsSurviveAnExactCheckpoint(){
        var solved=new PassiveIntervalSolver(model).solve(basin(),.1,new PassiveIntervalSolver.Settings(.1,.1,32),()->{});
        var snapshot=new IslandCoordinator.Snapshot(1,0,solved.graph(),new IslandClock.Snapshot(2,2,4,20),Optional.of(solved),"FULL");
        var checkpoint=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld","icheme:tjl20_methane_nitrogen",snapshot)));
        var image=FluidCheckpointCodec.encode(checkpoint,key->key.on(model));
        var loaded=FluidCheckpointCodec.decode(image,key->key.on(model));
        var restored=loaded.islands().getFirst().snapshot();
        assertEquals(solved.graph().reservoirs().getFirst().inventory(),restored.graph().reservoirs().getFirst().inventory());
        assertEquals(solved.towers(),restored.lastResult().orElseThrow().towers());
        var boundary=solved.boundaries().getFirst();var loadedBoundary=restored.lastResult().orElseThrow().boundaries().getFirst();
        assertEquals(boundary.kind(),loadedBoundary.kind());
        assertArrayEquals(boundary.moles(),loadedBoundary.moles());
        assertEquals(boundary.totalEnergyJoule(),loadedBoundary.totalEnergyJoule());
        FluidCheckpointCodecTest.assertSameImage(image,FluidCheckpointCodec.encode(loaded,key->key.on(model)),"populated tower");
    }

    @Test void certificateReplayScalesRecordedAtmosphericAndInventoryDeltas(){
        var before=basin();
        var solved=new PassiveIntervalSolver(model).solve(before,.1,new PassiveIntervalSolver.Settings(.1,.1,32),()->{});
        assertFalse(NetworkReduction.atRest(before,model),"A wet basin still exchanges with the atmosphere");
        var interval=new IslandCertificate.Interval(0,2,before,solved);
        var saved=new IslandCertificate.Saved(2,6,interval,IslandCertificate.Signature.of(model,CertificatePolicy.defaults(),solved.graph()));
        // Exercise the saved linear arithmetic independently of the stationarity qualification policy.
        var replayed=IslandCertificate.rebuild(saved,model.molecularWeights()).replay(2,6);
        var initial=solved.graph().reservoirs().getFirst().inventory();
        var end=replayed.graph().reservoirs().getFirst().inventory();
        var exchange=replayed.boundaries().stream().filter(b->b.kind()==ConservativeTransport.BoundaryTransfer.Kind.ATMOSPHERE).findFirst().orElseThrow();
        int water=model.componentCount()-1;
        assertEquals(initial.moles()[water]+exchange.moles()[water],end.moles()[water],1e-7);
        assertEquals(initial.internalEnergy()+exchange.totalEnergyJoule(),end.internalEnergy(),1);
        assertEquals(solved.boundaries().getFirst().totalEnergyJoule()*2,exchange.totalEnergyJoule());
        assertEquals(solved.towers(),replayed.towers(),"Rates remain rates during replay");
    }
}
