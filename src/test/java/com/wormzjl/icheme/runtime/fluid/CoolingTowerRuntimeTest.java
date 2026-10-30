package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoolingTowerRuntimeTest {
    private final com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model=FluidTestSupport.networkModel();

    private PassiveNetwork basin(){
        var tower=CoolingTower.induced(4,2,2,1);
        var amounts=new double[model.componentCount()];amounts[amounts.length-1]=1;
        double scale=32/model.flashTP(308.15,101325,amounts,()->{}).volume();amounts[amounts.length-1]*=scale;
        var state=model.flashTP(308.15,101325,amounts,()->{});
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory)),List.of());
    }

    @Test void anEmptyBasinPublishesItsFansThroughTheScheduledDisplay(){
        for(var mode:CoolingTower.Mode.values()){
            var tower=CoolingTower.induced(4,2,1,1).withControl(mode,1,298.15);
            var empty=PassiveNetwork.emptyVessel(1,0,tower.basinVolume(),CoolingTower.BASIN_DEPTH,true,model);
            var node=new PassiveNetwork.Reservoir(1,0,empty.state(),empty.kind(),empty.inventory().withTower(tower));
            var result=new PassiveIntervalSolver(model).solve(new PassiveNetwork(List.of(node),List.of()),1,PassiveIntervalSolver.Settings.defaults(),()->{});
            var rates=result.towers().get(1L);assertNotNull(rates);
            assertEquals(mode==CoolingTower.Mode.FAN_SPEED?1:0,rates.fill().speed());
            assertEquals(0,rates.waterLoss());
            if(mode==CoolingTower.Mode.FAN_SPEED)assertTrue(rates.fill().fanPowerWatt()>0);
            var view=new FluidView(1,0,0,0,"EMPTY",null,0,List.of()).withTank(new FluidView.Tank(CoolingTower.BASIN_DEPTH,true,0,0,tower.basinVolume(),null,null))
                .withTower(new FluidView.Tower(tower,rates));
            var display=TankDisplay.of(view,model.components(),model.molecularWeights(),EventPathWorld.CATALOG,
                com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(EventPathWorld.CATALOG));
            assertEquals(rates.fill().speed(),display.fanSpeed());assertEquals(0,display.level());
        }
    }

    @Test void bothTowerTypesPublishTheirAcceptedBottomPressure(){
        for(var tower:new CoolingTower[]{CoolingTower.induced(4,2,1,1),CoolingTower.natural(40)}){
            var state=basin().reservoirs().getFirst().state();
            var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
            var node=new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory);
            var view=TankOutlets.tank(model,node,null);
            assertEquals(state.pressure()+PassiveStepSolver.portHead(model,node,PassiveNetwork.PhasePort.LIQUID,state),view.bottomPressure());
            assertTrue(view.bottomPressure()>view.topPressure());
        }
    }

    @Test void populatedBasinAndAcceptedAtmosphericReadingsSurviveAnExactCheckpoint(){
        var solved=new PassiveIntervalSolver(model).solve(basin(),.1,new PassiveIntervalSolver.Settings(.1,.1,32),()->{});
        var snapshot=new IslandCoordinator.Snapshot(1,0,solved.graph(),new IslandClock.Snapshot(2,2,4,20),Optional.of(solved),"FULL");
        var checkpoint=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld","icheme:tjl20_methane_nitrogen",snapshot)));
        var image=FluidCheckpointCodec.encode(checkpoint,key->model);
        var loaded=FluidCheckpointCodec.decode(image,key->model);
        var restored=loaded.islands().getFirst().snapshot();
        assertEquals(solved.graph().reservoirs().getFirst().inventory(),restored.graph().reservoirs().getFirst().inventory());
        assertEquals(solved.towers(),restored.lastResult().orElseThrow().towers());
        var boundary=solved.boundaries().getFirst();var loadedBoundary=restored.lastResult().orElseThrow().boundaries().getFirst();
        assertEquals(boundary.kind(),loadedBoundary.kind());
        assertArrayEquals(boundary.moles(),loadedBoundary.moles());
        assertEquals(boundary.totalEnergyJoule(),loadedBoundary.totalEnergyJoule());
        FluidCheckpointCodecTest.assertSameImage(image,FluidCheckpointCodec.encode(loaded,key->model),"populated tower");
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
