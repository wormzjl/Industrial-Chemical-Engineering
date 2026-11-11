package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

class CoolingTowerTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(10,.05,.000045,0);
    private int water(){return model.componentCount()-1;}
    private FluidThermodynamics.State water(double t,double pressure,double volume){
        var amounts=new double[model.componentCount()];amounts[water()]=1;
        double scale=volume/model.flashTP(t,pressure,amounts,()->{}).volume();amounts[water()]*=scale;
        return model.flashTP(t,pressure,amounts,()->{});
    }
    private static double specific(FluidThermodynamics model,double t){return model.waterLiquid(t,101325).molarEnthalpy()/model.waterMolecularWeight;}
    private PassiveNetwork.Reservoir basin(long id,CoolingTower tower,FluidThermodynamics.State state){
        var stock=new PassiveNetwork.Inventory(tower.basinVolume(),PhaseLayout.totalAmounts(state),state.internalEnergy(),state.solids(),CoolingTower.BASIN_DEPTH,true).withTower(tower);
        return new PassiveNetwork.Reservoir(id,0,state,PassiveNetwork.NodeKind.RESERVOIR,stock);
    }
    private PassiveNetwork.Reservoir emptyBasin(long id,CoolingTower tower){
        var empty=PassiveNetwork.emptyVessel(id,0,tower.basinVolume(),CoolingTower.BASIN_DEPTH,true,model);
        return new PassiveNetwork.Reservoir(id,0,empty.state(),empty.kind(),empty.inventory().withTower(tower));
    }
    private static PassiveNetwork.Reservoir generator(long id,double elevation,FluidThermodynamics.State state){return new PassiveNetwork.Reservoir(id,elevation,state,PassiveNetwork.NodeKind.GENERATOR);}
    /** A connection into the basin at node 1 from the source at node 0, through the spray inlet. */
    private static PassiveNetwork.Pipe feed(){return new PassiveNetwork.Pipe(5,0,1,LINE).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.SPRAY);}
    private PassiveStepSolver.Result step(PassiveNetwork graph){return new PassiveStepSolver(model).solve(graph,.1,()->{});}

    @Test void sharedFillMatchesItsDeclaredCalibrationPoint(){assertEquals(298.15,TowerTransfer.referenceOutlet(model),1e-5);}
    /**
     * The plume's exhaust (batch 2026-11-10-custom-shader-system, W4): at the calibration point (water 35 to 25 °C, L/G 1, air
     * at 25 °C and 50 %) the air leaves near 28.5 °C and close to saturation, as the plan's mixing-line table takes it; its
     * humidity is the ambient's plus the evaporation over the dry air; no air leaves the ambient air.
     */
    @Test void theExhaustAtTheCalibrationPointLeavesNearlySaturated(){
        var fill=TowerTransfer.reference(model);
        var exhaust=TowerTransfer.exhaust(model,fill);
        System.out.printf(java.util.Locale.ROOT,"calibration exhaust: %.2f K, w %.5f, %.1f %% of saturation%n",exhaust.temperature(),exhaust.humidity(),100*exhaust.saturation());
        assertEquals(301.65,exhaust.temperature(),1.2,"about 28.5 °C");
        assertTrue(exhaust.saturation()>.9&&exhaust.saturation()<1.01,"saturated within a few %: "+exhaust.saturation());
        var ambient=TowerTransfer.exhaust(model,new TowerTransfer.Result(298.15,0,0,0,0,0,0));
        assertEquals(CoolingTower.AIR_TEMPERATURE,ambient.temperature());assertEquals(CoolingTower.RELATIVE_HUMIDITY,ambient.saturation());
        assertEquals(ambient.humidity()+fill.evaporation()/fill.dryAirFlow(),exhaust.humidity(),1e-12,"the water the air took up");
        // A real fill's exhaust (one 8 m fan cell at full speed) is warmer than the air it drew and wetter.
        var cell=TowerTransfer.exhaust(model,TowerTransfer.fill(model,CoolingTower.induced(8,2,1,1),2*64,308.15,1));
        assertTrue(cell.temperature()>CoolingTower.AIR_TEMPERATURE&&cell.temperature()<308.15&&cell.humidity()>ambient.humidity(),cell.toString());
    }
    @Test void theRatingStaysNearTheMarchedFillItReplaced(){
        record Point(CoolingTower tower,double loading,double inlet,double speed,double outlet,double evaporation){}
        var fan=CoolingTower.induced(8,2,1,1);var chimney=CoolingTower.natural(40);
        for(var p:List.of(new Point(fan,2,308.15,1,297.401,2.0746),new Point(fan,2,308.15,.5,300.200,1.5078),new Point(fan,20,308.15,1,306.511,3.0906),
                new Point(chimney,2,308.15,0,298.734,35.32),new Point(chimney,20,308.15,0,306.650,55.55))){
            var r=TowerTransfer.fill(model,p.tower(),p.loading()*p.tower().area(),p.inlet(),p.speed());
            assertEquals(p.outlet(),r.outletTemperature(),.15,p.toString());
            assertEquals(p.evaporation(),r.evaporation(),.05*p.evaporation(),p.toString());
        }
    }
    @Test void transferSweepConservesWaterEnergyAndRespectsItsAmbientLimit(){
        for(double fill:new double[]{1,3})for(double loading:new double[]{.02,.2,2,20})for(double inlet:new double[]{298.15,308.15,350})for(double speed:new double[]{0,1}){
            var tower=CoolingTower.induced(8,fill,1,1);double water=tower.area()*loading;
            var r=TowerTransfer.fill(model,tower,water,inlet,speed);
            String point="fill="+fill+" loading="+loading+" inlet="+inlet+" speed="+speed;
            assertTrue(r.outletTemperature()>=290&&r.outletTemperature()<=inlet+.01,point+" T="+r.outletTemperature());
            assertTrue(r.evaporation()<water,point);
            assertEquals(water*specific(model,inlet)-(water-r.evaporation())*specific(model,r.outletTemperature()),r.energyWatt(),Math.max(1e-4,Math.abs(r.energyWatt())*1e-7),point);
        }
    }
    @Test void buoyancyReversesForColdWaterAndFansOffStillAllowNaturalCooling(){
        var tower=CoolingTower.natural(60);
        var warm=TowerTransfer.fill(model,tower,2*tower.area(),308.15,0);
        var cold=TowerTransfer.fill(model,tower,2*tower.area(),278.15,0);
        assertTrue(warm.dryAirFlow()>0);assertTrue(cold.dryAirFlow()<0);
        assertTrue(cold.outletTemperature()>278.15);
        assertEquals(0,warm.fanPowerWatt());
    }
    @Test void cellSizeAndBankCountsAreBounded(){
        assertThrows(IllegalArgumentException.class,()->CoolingTower.induced(6,2,1,1),"No walkway would stay round its fan");
        assertThrows(IllegalArgumentException.class,()->CoolingTower.induced(16,2,1,1));
        assertThrows(IllegalArgumentException.class,()->CoolingTower.induced(8,2,9,1));
        assertThrows(IllegalArgumentException.class,()->CoolingTower.induced(8,2,6,3));
        assertEquals(16,CoolingTower.induced(8,2,8,2).cells());
        assertThrows(IllegalArgumentException.class,()->CoolingTower.natural(80,100));
    }
    @Test void parallelBanksRepeatTheirExtensiveResults(){
        var one=TowerTransfer.fill(model,CoolingTower.induced(8,2,1,1),128,308.15,1);
        var bank=TowerTransfer.fill(model,CoolingTower.induced(8,2,8,2),128*16,308.15,1);
        assertTrue(one.outletTemperature()<308.15);
        assertTrue(one.evaporation()>0);assertTrue(one.fanPowerWatt()>0);
        assertEquals(one.outletTemperature(),bank.outletTemperature(),1e-9);
        assertEquals(one.energyWatt()*16,bank.energyWatt(),1e-5);
        assertEquals(one.evaporation()*16,bank.evaporation(),1e-9);
        assertEquals(one.dryAirFlow()*16,bank.dryAirFlow(),1e-7);
        assertEquals(one.fanPowerWatt()*16,bank.fanPowerWatt(),1e-7);
    }
    @Test void fanPowerDependsOnlyOnSpeedForAGivenTower(){
        var tower=CoolingTower.induced(8,2,1,1);
        double full=TowerTransfer.fill(model,tower,128,308.15,1).fanPowerWatt();
        for(double water:new double[]{0,12.8,1280})for(double inlet:new double[]{298.15,350}){
            assertEquals(full,TowerTransfer.fill(model,tower,water,inlet,1).fanPowerWatt(),1e-9);
            assertEquals(full/8,TowerTransfer.fill(model,tower,water,inlet,.5).fanPowerWatt(),1e-9);
        }
        assertEquals(0,TowerTransfer.fill(model,tower,128,308.15,0).fanPowerWatt());
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
    @Test void aStandingBasinBooksSensibleHeatWithoutWaterLoss(){
        var tower=CoolingTower.induced(8,2,1,1);var node=basin(1,tower,water(308.15,PhaseLayout.atmosphere(CoolingTower.BASIN_DEPTH),64));
        var result=step(new PassiveNetwork(List.of(node),List.of()));
        assertEquals(0,result.externalMoles()[water()]);
        assertTrue(result.states().getFirst().temperature()<node.state().temperature());
        assertEquals(node.inventory().moles()[water()],result.inventories().getFirst().moles()[water()],1e-7);
        assertTrue(result.boundaries().stream().anyMatch(v->v.kind()==ConservativeTransport.BoundaryTransfer.Kind.ATMOSPHERE));
    }
    @Test void naturalDraftStartsFromAnEmptyBasinWith80CWater(){
        var tower=CoolingTower.natural(60);
        var result=step(new PassiveNetwork(List.of(generator(1,tower.distributorHeight(),water(353.15,250000,1)),emptyBasin(2,tower)),List.of(feed())));
        assertTrue(result.massFlows()[0]>0);
        assertTrue(result.inventories().get(1).moles()[water()]>0);
        assertTrue(result.towers().get(2L).fill().evaporation()>0);
    }
    @Test void unsupportedFeedIsStoppedWithoutChangingItsSource(){
        var tower=CoolingTower.induced(8,2,1,1);var source=model.initialNitrogenCharge(1,298.15,250000,()->{});
        var result=step(new PassiveNetwork(List.of(generator(1,8.5,source),basin(2,tower,water(298.15,101325,64))),List.of(feed())));
        assertEquals(0,result.massFlows()[0],1e-9);
        assertEquals(source,result.states().getFirst());
    }
    @Test void aTowersEndsAreAirBreaks(){
        var tower=CoolingTower.induced(8,2,1,1);var full=basin(2,tower,water(298.15,101325,64));
        // A line pressed against the outlet does not fill the basin, and a low line at the inlet does not siphon it.
        var pressed=new PassiveNetwork.Pipe(5,0,1,LINE).withPorts(PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.LIQUID);
        assertEquals(0,step(new PassiveNetwork(List.of(generator(1,0,water(308.15,300000,1)),full),List.of(pressed))).massFlows()[0],1e-9);
        var sink=new PassiveNetwork.Reservoir(1,-20,water(298.15,101325,1),PassiveNetwork.NodeKind.VOID);
        assertEquals(0,step(new PassiveNetwork(List.of(sink,full),List.of(feed()))).massFlows()[0],1e-9);
    }
    @Test void targetTemperatureControlsLeavingWaterIndependentlyOfBasinHoldup(){
        var base=CoolingTower.induced(8,2,1,1);double flow=128,inlet=308.15;
        double target=(TowerTransfer.fill(model,base,flow,inlet,0).outletTemperature()+TowerTransfer.fill(model,base,flow,inlet,1).outletTemperature())/2;
        double previous=-1;
        for(double basinTemperature:new double[]{285,308.15}){
            var tower=base.withControl(CoolingTower.Mode.TARGET_TEMPERATURE,1,target);
            var state=water(basinTemperature,101325,64);var source=water(inlet,250000,1);
            var rates=TowerExchange.at(new PassiveNetwork(List.of(generator(1,8.5,source),basin(2,tower,state)),List.of(feed())),1,List.of(source,state),new double[]{flow},model);
            assertEquals(target,rates.fill().outletTemperature(),.001);
            assertEquals(inlet,rates.inletTemperature());
            assertTrue(rates.fill().speed()>0&&rates.fill().speed()<1);
            if(previous>=0)assertEquals(previous,rates.fill().speed(),1e-5);
            previous=rates.fill().speed();
        }
    }
    @Test void liquidWaterDoesNotBecomeAnUnsupportedInputBelowItsFreezingPoint(){
        var zero=new double[model.hydrocarbon.componentCount()];
        assertTrue(TowerExchange.liquidWater(model,TestPhases.state(model,270,101325,zero,zero,1,0)));
        assertFalse(TowerExchange.liquidWater(model,water(420,1000000,1)),"Compressed water that flashes at discharge is not a liquid-only feed");
        var gas=zero.clone();gas[gas.length-1]=.001;
        assertTrue(TowerExchange.liquidWater(model,TestPhases.state(model,300,101325,zero,gas,1,0)),"A stream's gas passes to the basin's vent");
    }
    @Test void targetModeStopsFansWithoutSprayWhileTheWarmBasinStillCools(){
        var tower=CoolingTower.induced(8,2,1,1).withControl(CoolingTower.Mode.TARGET_TEMPERATURE,1,298.15);
        var node=basin(1,tower,water(308.15,101325,64));
        var rates=TowerExchange.at(new PassiveNetwork(List.of(node),List.of()),0,List.of(node.state()),new double[0],model);
        assertEquals(0,rates.fill().speed());assertEquals(0,rates.fill().fanPowerWatt());
        assertEquals(0,rates.waterLoss());assertTrue(rates.surfaceWatt()>0);
    }
    @Test void aBasinTemperatureDifferenceCannotDriveAnAtmosphericInlet(){
        var tower=CoolingTower.induced(8,2,1,1);
        var result=step(new PassiveNetwork(List.of(generator(1,tower.distributorHeight(),water(308.15,101325,1)),basin(2,tower,water(298.15,101325,64))),List.of(feed())));
        assertEquals(0,result.massFlows()[0],1e-7);
    }

    @Test void startupCirculationStarvationShutdownAndOverflowKeepTheAcceptedLedger(){
        var tower=CoolingTower.induced(8,2,1,1);var basin=emptyBasin(2,tower);
        var source=generator(1,8.5,water(308.15,250000,1));
        var sink=new PassiveNetwork.Reservoir(3,-2,water(298.15,101325,1),PassiveNetwork.NodeKind.VOID);
        var draw=new PassiveNetwork.Pipe(6,1,2,LINE).withPorts(PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK);
        for(int stage=0;stage<5;stage++){
            var pipes=switch(stage){
                case 0,4 -> List.<PassiveNetwork.Pipe>of();
                case 1 -> List.of(feed());
                case 2 -> List.of(feed(),draw);
                default -> List.of(draw);
            };
            double before=basin.inventory().moles()[water()];
            var result=balancedStep(new PassiveNetwork(List.of(source,basin,sink),pipes));
            basin=new PassiveNetwork.Reservoir(2,0,result.states().get(1),basin.kind(),result.inventories().get(1));
            if(stage==0)assertTrue(basin.empty());
            if(stage==1)assertTrue(basin.inventory().moles()[water()]>0,"startup fills an empty basin");
            if(stage==2)assertTrue(result.massFlows()[1]>0,"basin supplies the external circuit");
            if(stage==3)assertTrue(basin.inventory().moles()[water()]<before,"starvation draws down storage");
            if(stage==4)assertEquals(0,result.towers().get(2L).waterLoss(),"shutdown stops fill transfer");
        }
        var overflowing=balancedStep(new PassiveNetwork(List.of(basin(2,tower,water(308.15,101325,64*3.2))),List.of()));
        assertTrue(overflowing.boundaries().stream().anyMatch(b->b.kind()==ConservativeTransport.BoundaryTransfer.Kind.SPILL));
    }

    private PassiveStepSolver.Result balancedStep(PassiveNetwork graph){
        var result=step(graph);
        double before=0,after=0,energyBefore=0,energyAfter=0;
        for(int i=0;i<graph.reservoirs().size();i++)if(!graph.reservoirs().get(i).fixed()){
            var initial=graph.reservoirs().get(i).inventory();var end=result.inventories().get(i);
            before+=initial.moles()[water()];after+=end.moles()[water()];
            energyBefore+=initial.internalEnergy();energyAfter+=end.internalEnergy();
        }
        assertEquals(before+result.externalMoles()[water()],after,1e-7*Math.max(1,after));
        assertEquals(energyBefore+result.externalEnergyJoule()+result.pumpWorkJoule(),energyAfter,1e-5*Math.max(1,Math.abs(energyAfter)));
        return result;
    }

    @Test void drawingTheLastWaterLeavesAnEmptyBasinAndBooksItsSurfaceExchange(){
        var tower=CoolingTower.induced(8,2,1,1);
        var sink=new PassiveNetwork.Reservoir(2,-2,water(298.15,50000,1),PassiveNetwork.NodeKind.VOID);
        var draw=new PassiveNetwork.Pipe(5,0,1,LINE).withPorts(PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK);
        var result=balancedStep(new PassiveNetwork(List.of(basin(1,tower,water(308.15,101325,.00001)),sink),List.of(draw)));
        assertEquals(0,result.inventories().getFirst().moles()[water()],1e-9);
        assertEquals(0,result.inventories().getFirst().internalEnergy(),1e-6);
    }
}
