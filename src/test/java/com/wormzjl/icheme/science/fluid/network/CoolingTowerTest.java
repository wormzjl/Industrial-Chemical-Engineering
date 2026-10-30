package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoolingTowerTest {
    private final com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model=FluidTestSupport.networkModel();
    @Test void sharedFillMatchesItsDeclaredCalibrationPoint(){assertEquals(298.15,TowerTransfer.reference(model).outletTemperature(),1e-5);}
    @Test void transferSweepConservesWaterEnergyAndRespectsItsAmbientLimit(){
        for(double fill:new double[]{2,6})for(double loading:new double[]{.02,.2,2,20})for(double inlet:new double[]{298.15,308.15,350})for(double speed:new double[]{0,1}){
            var tower=CoolingTower.induced(6,fill,1,1);double water=tower.area()*loading;
            var r=TowerTransfer.fill(model,tower,water,inlet,speed);
            String point="fill="+fill+" loading="+loading+" inlet="+inlet+" speed="+speed;
            assertTrue(r.outletTemperature()>=290&&r.outletTemperature()<=inlet+.01,point+" T="+r.outletTemperature());
            assertTrue(r.waterLoss()<water,point);
            double hi=model.waterLiquid(inlet,101325).molarEnthalpy()/model.waterMolecularWeight;
            double ho=model.waterLiquid(r.outletTemperature(),101325).molarEnthalpy()/model.waterMolecularWeight;
            assertEquals(water*hi-(water-r.waterLoss())*ho,r.energyWatt(),Math.max(1e-4,Math.abs(r.energyWatt())*1e-7),point);
        }
    }
    @Test void buoyancyReversesForColdWaterAndFansOffStillAllowNaturalCooling(){
        var tower=CoolingTower.natural(60,120);
        var warm=TowerTransfer.fill(model,tower,2*tower.area(),308.15,0);
        var cold=TowerTransfer.fill(model,tower,2*tower.area(),278.15,0);
        assertTrue(warm.dryAirFlow()>0);assertTrue(cold.dryAirFlow()<0);
        assertTrue(cold.outletTemperature()>278.15);
        assertEquals(0,warm.fanPowerWatt());
    }
    @Test void cellSizeAndBankCountsAreBounded(){
        assertThrows(IllegalArgumentException.class,()->CoolingTower.induced(10,2,1,1));
        assertThrows(IllegalArgumentException.class,()->CoolingTower.induced(8,2,7,1));
        assertThrows(IllegalArgumentException.class,()->CoolingTower.induced(8,2,6,5));
        assertEquals(24,CoolingTower.induced(8,2,6,4).cells());
        assertThrows(IllegalArgumentException.class,()->CoolingTower.natural(80,100));
    }
    @Test void parallelBanksRepeatTheirExtensiveResults(){
        var one=TowerTransfer.fill(model,CoolingTower.induced(6,2,1,1),72,308.15,1);
        var bank=TowerTransfer.fill(model,CoolingTower.induced(6,2,6,4),72*24,308.15,1);
        assertTrue(one.outletTemperature()<308.15);
        assertTrue(one.evaporation()>0);assertTrue(one.fanPowerWatt()>0);
        assertEquals(one.outletTemperature(),bank.outletTemperature(),1e-9);
        assertEquals(one.energyWatt()*24,bank.energyWatt(),1e-7);
        assertEquals(one.evaporation()*24,bank.evaporation(),1e-9);
        assertEquals(one.dryAirFlow()*24,bank.dryAirFlow(),1e-7);
        assertEquals(one.fanPowerWatt()*24,bank.fanPowerWatt(),1e-7);
    }
    @Test void waterAndAirEnergyUseTheSameReference(){
        double water=72,t=308.15;
        var f=TowerTransfer.fill(model,CoolingTower.induced(6,2,1,1),water,t,1);
        double hi=model.waterLiquid(t,101325).molarEnthalpy()/model.waterMolecularWeight;
        double ho=model.waterLiquid(f.outletTemperature(),101325).molarEnthalpy()/model.waterMolecularWeight;
        assertEquals(water*hi-(water-f.waterLoss())*ho,f.energyWatt(),Math.abs(f.energyWatt())*1e-7);
    }
    @Test void fanPowerDependsOnlyOnSpeedForAGivenTower(){
        var tower=CoolingTower.induced(6,2,1,1);
        double full=TowerTransfer.fill(model,tower,72,308.15,1).fanPowerWatt();
        for(double water:new double[]{0,7.2,72,720})for(double inlet:new double[]{298.15,308.15,350}){
            assertEquals(full,TowerTransfer.fill(model,tower,water,inlet,1).fanPowerWatt(),1e-9);
            assertEquals(full/8,TowerTransfer.fill(model,tower,water,inlet,.5).fanPowerWatt(),1e-9);
        }
        assertEquals(0,TowerTransfer.fill(model,tower,72,308.15,0).fanPowerWatt());
    }
    @Test void basinSurfaceExchangesHeatWithoutEvaporation(){
        var tower=CoolingTower.induced(6,2,1,1);
        var warm=TowerTransfer.surface(model,tower,308.15,10000);
        var cold=TowerTransfer.surface(model,tower,278.15,10000);
        assertTrue(warm.sensibleWatt()>0);assertTrue(cold.sensibleWatt()<0);
        for(var result:new TowerTransfer.Result[]{warm,cold}){
            assertEquals(0,result.waterLoss());assertEquals(0,result.latentWatt());
            assertEquals(result.sensibleWatt(),result.energyWatt());
        }
        assertEquals(0,TowerTransfer.fill(model,tower,0,308.15,1).waterLoss());
    }
    @Test void naturalDraftAccepts80CWater(){
        for(int diameter:new int[]{40,60,80}){
            var tower=CoolingTower.natural(diameter);
            for(double loading:new double[]{.001,.02,.2,2,20}){
                var result=TowerTransfer.fill(model,tower,loading*tower.area(),353.15,0);
                assertTrue(result.outletTemperature()<353.15,"diameter="+diameter+" loading="+loading);
                assertTrue(result.evaporation()>0,"diameter="+diameter+" loading="+loading);
            }
        }
    }
    private com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.State water(double t,double pressure,double volume){
        var amounts=new double[model.componentCount()];amounts[amounts.length-1]=1;
        double scale=volume/model.flashTP(t,pressure,amounts,()->{}).volume();amounts[amounts.length-1]*=scale;
        return model.flashTP(t,pressure,amounts,()->{});
    }
    @Test void aStandingBasinBooksSensibleHeatWithoutWaterLoss(){
        var tower=CoolingTower.induced(4,2,1,1);var state=water(308.15,101325,16);
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var graph=new PassiveNetwork(java.util.List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory)),java.util.List.of());
        var result=new PassiveStepSolver(model).solve(graph,.1,()->{});
        assertEquals(0,result.externalMoles()[model.componentCount()-1]);
        assertTrue(result.states().getFirst().temperature()<state.temperature());
        assertEquals(inventory.moles()[model.componentCount()-1],result.inventories().getFirst().moles()[model.componentCount()-1],1e-7);
        assertTrue(result.boundaries().stream().anyMatch(v->v.kind()==ConservativeTransport.BoundaryTransfer.Kind.ATMOSPHERE));
    }
    @Test void aSprayInletCoolsItsWaterWithinTheCoupledBalance(){
        var tower=CoolingTower.induced(4,2,1,1);var state=water(298.15,101325,16);
        var source=water(308.15,250000,1);
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var nodes=java.util.List.of(new PassiveNetwork.Reservoir(1,7.5,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory));
        var pipe=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);
        var result=new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,java.util.List.of(pipe)),.1,()->{});
        assertTrue(result.massFlows()[0]>0);
        assertTrue(result.towers().get(2L).fill().evaporation()>0);
        assertTrue(result.towers().get(2L).fill().outletTemperature()<source.temperature());
    }
    @Test void naturalSprayAdmits80CWaterWithinTheCoupledBalance(){
        var tower=CoolingTower.natural(60);var state=water(298.15,101325,16);
        var source=water(353.15,250000,1);
        assertTrue(TowerExchange.liquidWater(model,source));
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var nodes=java.util.List.of(new PassiveNetwork.Reservoir(1,13.5,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory));
        var pipe=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);
        var result=new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,java.util.List.of(pipe)),.1,()->{});
        assertTrue(result.massFlows()[0]>0);
        var rates=result.towers().get(2L);
        assertEquals(source.enthalpy()/source.mass(),model.waterLiquid(rates.inletTemperature(),101325).molarEnthalpy()/model.waterMolecularWeight,.001);
        assertTrue(rates.fill().evaporation()>0);
    }
    @Test void naturalDraftStartsFromAnEmptyBasinWith80CWater(){
        var tower=CoolingTower.natural(60);
        var empty=PassiveNetwork.emptyVessel(2,0,tower.basinVolume(),CoolingTower.BASIN_DEPTH,true,model);
        var basin=new PassiveNetwork.Reservoir(2,0,empty.state(),empty.kind(),empty.inventory().withTower(tower));
        var source=new PassiveNetwork.Reservoir(1,13.5,water(353.15,250000,1),PassiveNetwork.NodeKind.GENERATOR);
        var feed=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);
        var result=new PassiveStepSolver(model).solve(new PassiveNetwork(java.util.List.of(source,basin),java.util.List.of(feed)),.1,()->{});
        assertTrue(result.massFlows()[0]>0);
        assertTrue(result.inventories().get(1).moles()[model.componentCount()-1]>0);
        assertTrue(result.towers().get(2L).fill().evaporation()>0);
    }
    @Test void unsupportedFeedIsStoppedWithoutChangingItsSource(){
        var tower=CoolingTower.induced(4,2,1,1);var state=water(298.15,101325,16);
        var source=model.initialNitrogenCharge(1,298.15,250000,()->{});
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var nodes=java.util.List.of(new PassiveNetwork.Reservoir(1,7.5,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory));
        var pipe=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);
        var result=new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,java.util.List.of(pipe)),.1,()->{});
        assertEquals(0,result.massFlows()[0],1e-9);
        assertEquals(source,result.states().getFirst());
    }
    @Test void targetTemperatureControlsLeavingWaterIndependentlyOfBasinHoldup(){
        var base=CoolingTower.induced(4,2,1,1);double flow=32,inlet=308.15;
        double target=(TowerTransfer.fill(model,base,flow,inlet,0).outletTemperature()+TowerTransfer.fill(model,base,flow,inlet,1).outletTemperature())/2;
        double previous=-1;
        for(double basinTemperature:new double[]{285,308.15}){
            var tower=base.withControl(CoolingTower.Mode.TARGET_TEMPERATURE,1,target);
            var state=water(basinTemperature,101325,16);var source=water(inlet,250000,1);
            var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
            var nodes=java.util.List.of(new PassiveNetwork.Reservoir(1,7.5,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory));
            var pipe=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);
            var rates=TowerExchange.at(new PassiveNetwork(nodes,java.util.List.of(pipe)),1,java.util.List.of(source,state),new double[]{flow},.1,model);
            assertEquals(target,rates.fill().outletTemperature(),.001);
            assertEquals(source.enthalpy()/source.mass(),model.waterLiquid(rates.inletTemperature(),101325).molarEnthalpy()/model.waterMolecularWeight,.001,"Atmospheric discharge preserves inlet enthalpy");
            assertTrue(rates.fill().speed()>0&&rates.fill().speed()<1);
            if(previous>=0)assertEquals(previous,rates.fill().speed(),1e-5);
            previous=rates.fill().speed();
        }
    }
    @Test void liquidWaterDoesNotBecomeAnUnsupportedInputBelowItsFreezingPoint(){
        var zero=new double[model.hydrocarbon.componentCount()];
        var supercooled=model.state(270,101325,zero,zero,1,0,0);
        assertTrue(TowerExchange.liquidWater(model,supercooled));
        var hot=water(420,1000000,1);
        assertFalse(TowerExchange.liquidWater(model,hot),"Compressed water that flashes at discharge is not a liquid-only feed");
    }
    @Test void targetModeStopsFansWithoutSprayWhileTheWarmBasinStillCools(){
        var tower=CoolingTower.induced(4,2,1,1).withControl(CoolingTower.Mode.TARGET_TEMPERATURE,1,298.15);
        var state=water(308.15,101325,16);
        var stock=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var graph=new PassiveNetwork(java.util.List.of(new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,stock)),java.util.List.of());
        var rates=TowerExchange.at(graph,0,java.util.List.of(state),new double[0],.1,model);
        assertEquals(0,rates.fill().speed());assertEquals(0,rates.fill().fanPowerWatt());
        assertEquals(0,rates.fill().waterLoss());assertEquals(0,rates.surface().evaporation());
        assertTrue(rates.surface().sensibleWatt()>0);assertEquals(0,rates.waterLoss());
    }
    @Test void aBasinTemperatureDifferenceCannotDriveAnAtmosphericInlet(){
        var tower=CoolingTower.induced(4,2,1,1);var state=water(298.15,101325,16);var source=water(308.15,101325,1);
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var nodes=java.util.List.of(new PassiveNetwork.Reservoir(1,7.5,source,PassiveNetwork.NodeKind.GENERATOR),new PassiveNetwork.Reservoir(2,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory));
        var pipe=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0)).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);
        var result=new PassiveStepSolver(model).solve(new PassiveNetwork(nodes,java.util.List.of(pipe)),.1,()->{});
        assertEquals(0,result.massFlows()[0],1e-7);
    }

    @Test void startupCirculationStarvationShutdownAndOverflowKeepTheAcceptedLedger(){
        var tower=CoolingTower.induced(4,2,1,1);
        var empty=PassiveNetwork.emptyVessel(2,0,tower.basinVolume(),CoolingTower.BASIN_DEPTH,true,model);
        var basin=new PassiveNetwork.Reservoir(2,0,empty.state(),empty.kind(),empty.inventory().withTower(tower));
        var source=new PassiveNetwork.Reservoir(1,7.5,water(308.15,250000,1),PassiveNetwork.NodeKind.GENERATOR);
        var sink=new PassiveNetwork.Reservoir(3,-2,water(298.15,101325,1),PassiveNetwork.NodeKind.VOID);
        var feed=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0))
            .withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);
        var draw=new PassiveNetwork.Pipe(6,1,2,new PipeResistance.Geometry(10,.05,.000045,0))
            .withPorts(PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK);
        for(int stage=0;stage<5;stage++){
            var pipes=switch(stage){
                case 0,4 -> java.util.List.<PassiveNetwork.Pipe>of();
                case 1 -> java.util.List.of(feed);
                case 2 -> java.util.List.of(feed,draw);
                default -> java.util.List.of(draw);
            };
            double before=basin.inventory().moles()[model.componentCount()-1];
            var result=balancedStep(new PassiveNetwork(java.util.List.of(source,basin,sink),pipes));
            basin=new PassiveNetwork.Reservoir(2,0,result.states().get(1),basin.kind(),result.inventories().get(1));
            if(stage==0)assertTrue(basin.empty());
            if(stage==1)assertTrue(basin.inventory().moles()[model.componentCount()-1]>0,"startup fills an empty basin");
            if(stage==2)assertTrue(result.massFlows()[1]>0,"basin supplies the external circuit");
            if(stage==3)assertTrue(basin.inventory().moles()[model.componentCount()-1]<before,"starvation draws down storage");
            if(stage==4)assertEquals(0,result.towers().get(2L).fill().waterLoss(),"shutdown stops fill transfer");
        }
        var full=water(308.15,101325,16*3.2);
        var stock=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(full),full.internalEnergy(),full.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        basin=new PassiveNetwork.Reservoir(2,0,full,PassiveNetwork.NodeKind.RESERVOIR,stock);
        var overflowing=balancedStep(new PassiveNetwork(java.util.List.of(basin),java.util.List.of()));
        assertTrue(overflowing.boundaries().stream().anyMatch(b->b.kind()==ConservativeTransport.BoundaryTransfer.Kind.SPILL));
    }

    private PassiveStepSolver.Result balancedStep(PassiveNetwork graph){
        var result=new PassiveStepSolver(model).solve(graph,.1,()->{});
        double before=0,after=0,energyBefore=0,energyAfter=0;
        for(int i=0;i<graph.reservoirs().size();i++)if(!graph.reservoirs().get(i).fixed()){
            var initial=graph.reservoirs().get(i).inventory();var end=result.inventories().get(i);
            before+=initial.moles()[model.componentCount()-1];after+=end.moles()[model.componentCount()-1];
            energyBefore+=initial.internalEnergy();energyAfter+=end.internalEnergy();
        }
        assertEquals(before+result.externalMoles()[model.componentCount()-1],after,1e-7*Math.max(1,after));
        assertEquals(energyBefore+result.externalEnergyJoule()+result.pumpWorkJoule(),energyAfter,1e-5*Math.max(1,Math.abs(energyAfter)));
        return result;
    }

    @Test void drawingTheLastWaterLeavesAnEmptyBasinAndBooksItsSurfaceExchange(){
        var tower=CoolingTower.induced(4,2,1,1);var state=water(308.15,101325,.00001);
        var inventory=new PassiveNetwork.Inventory(tower.basinVolume(),com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        var basin=new PassiveNetwork.Reservoir(1,0,state,PassiveNetwork.NodeKind.RESERVOIR,inventory);
        var sink=new PassiveNetwork.Reservoir(2,-2,water(298.15,50000,1),PassiveNetwork.NodeKind.VOID);
        var draw=new PassiveNetwork.Pipe(5,0,1,new PipeResistance.Geometry(10,.05,.000045,0))
            .withPorts(PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK);
        var result=balancedStep(new PassiveNetwork(java.util.List.of(basin,sink),java.util.List.of(draw)));
        assertEquals(0,result.inventories().getFirst().moles()[model.componentCount()-1],1e-9);
        assertEquals(0,result.inventories().getFirst().internalEnergy(),1e-6);
    }
}
