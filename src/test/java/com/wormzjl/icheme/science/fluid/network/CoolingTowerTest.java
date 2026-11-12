package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoolingTowerTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry LINE=new PipeResistance.Geometry(10,.05,.000045,0);
    private int water(){return model.componentCount()-1;}
    private FluidThermodynamics.State water(double t,double pressure,double volume){
        var amounts=new double[model.componentCount()];amounts[water()]=1;
        double scale=volume/model.flashTP(t,pressure,amounts,()->{}).volume();amounts[water()]*=scale;
        return model.flashTP(t,pressure,amounts,()->{});
    }
    /** The engine's enthalpy, W, of {@code mass} kg/s of liquid water at {@code t} and the atmosphere. */
    private double enthalpy(double t,double mass){var amounts=new double[model.componentCount()];amounts[water()]=mass/model.waterMolecularWeight;return model.flashTP(t,101325,amounts,()->{}).enthalpy();}
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
        var ambient=TowerTransfer.exhaust(model,new TowerTransfer.Result(298.15,new double[model.componentCount()],0,0,0,0,0,298.15,0));
        assertEquals(CoolingTower.AIR_TEMPERATURE,ambient.temperature());assertEquals(CoolingTower.RELATIVE_HUMIDITY,ambient.saturation());
        assertEquals(ambient.humidity()+fill.loss(water())/fill.dryAirFlow(),exhaust.humidity(),1e-12,"the water the air took up");
        // A real fill's exhaust (one 8 m fan cell at full speed) is warmer than the air it drew and wetter.
        var cell=TowerTransfer.exhaust(model,TowerTransfer.fill(model,CoolingTower.induced(8,2,1,1),2*64,308.15,1));
        assertTrue(cell.temperature()>CoolingTower.AIR_TEMPERATURE&&cell.temperature()<308.15&&cell.humidity()>ambient.humidity(),cell.toString());
    }
    /**
     * The marched fill's references. Since W3B_NETWORK_PLAN.md section 20 the water is the engine's (the cubic's saturation
     * curve, 0.1 % above Wagner-Pruss at 35 °C) and the calibration utility water's: the outlets stand within 0.04 K, the
     * evaporation within 1.8 %.
     */
    @Test void theRatingStaysNearTheMarchedFillItReplaced(){
        record Point(CoolingTower tower,double loading,double inlet,double speed,double outlet,double evaporation){}
        var fan=CoolingTower.induced(8,2,1,1);var chimney=CoolingTower.natural(40);
        for(var p:List.of(new Point(fan,2,308.15,1,297.401,2.0746),new Point(fan,2,308.15,.5,300.200,1.5078),new Point(fan,20,308.15,1,306.511,3.0906),
                new Point(chimney,2,308.15,0,298.734,35.32),new Point(chimney,20,308.15,0,306.650,55.55))){
            var r=TowerTransfer.fill(model,p.tower(),p.loading()*p.tower().area(),p.inlet(),p.speed());
            assertEquals(p.outlet(),r.outletTemperature(),.15,p.toString());
            assertEquals(p.evaporation(),r.loss(water()),.05*p.evaporation(),p.toString());
        }
    }
    @Test void transferSweepConservesWaterEnergyAndRespectsItsAmbientLimit(){
        for(double fill:new double[]{1,3})for(double loading:new double[]{.02,.2,2,20})for(double inlet:new double[]{298.15,308.15,350})for(double speed:new double[]{0,1}){
            var tower=CoolingTower.induced(8,fill,1,1);double water=tower.area()*loading;
            var r=TowerTransfer.fill(model,tower,water,inlet,speed);
            String point="fill="+fill+" loading="+loading+" inlet="+inlet+" speed="+speed;
            assertTrue(r.outletTemperature()>=290&&r.outletTemperature()<=inlet+.01,point+" T="+r.outletTemperature());
            assertTrue(r.evaporation()<water,point);
            // The engine's liquid balance: what leaves carries the energy the fill books, within the fill's constant heat
            // capacity and its latent heat from the fugacity's slope (the water share's enthalpy is not in the fugacity).
            assertEquals(enthalpy(inlet,water)-enthalpy(r.outletTemperature(),water-r.loss(water())),r.energyWatt(),.03*Math.abs(r.dutyWatt())+1e-3,point);
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
    /**
     * COOLING_TOWER_PLAN.md test 4. A two-phase stream (water with free nitrogen and a little methane) through a bulk end:
     * all its gas leaves with the air and none reaches the basin, every component's and the energy ledger close, and the
     * basin holds no gas phase. Through a liquid port the same donor delivers no gas, and the tower removes none.
     */
    @Test void aTwoPhaseStreamsGasLeavesWithTheAir(){
        int nitrogen=model.components().indexOf("Nitrogen"),methane=model.components().indexOf("Methane");
        var amounts=new double[model.componentCount()];amounts[water()]=1;amounts[nitrogen]=.05;amounts[methane]=.005;
        var source=model.flashTP(308.15,300000,amounts,()->{});assertTrue(FluidThermodynamics.holdsVapor(source)&&FluidThermodynamics.holdsLiquid(source));
        var tower=CoolingTower.induced(8,2,1,1);var basin=basin(2,tower,water(298.15,PhaseLayout.atmosphere(CoolingTower.BASIN_DEPTH),64));
        var graph=new PassiveNetwork(List.of(generator(1,tower.distributorHeight()+2,source),basin),List.of(feed()));
        var result=balancedStep(graph);
        double q=result.massFlows()[0];assertTrue(q>0);
        var rates=result.towers().get(2L);double share=q/source.mass();
        for(int c:new int[]{nitrogen,methane})
            assertTrue(rates.loss(c)>=share*source.vaporAmountsView()[c]*model.molecularWeight(c),"the gas of "+model.components().get(c)+" leaves with the air");
        assertFalse(FluidThermodynamics.holdsVapor(result.states().get(1)),"no gas phase in the basin");
        var liquidOnly=new double[6];liquidOnly[FluidThermodynamics.AQUEOUS]=q*model.phaseMass(source,FluidThermodynamics.AQUEOUS)/source.mass();
        var drawn=new double[1][];drawn[0]=liquidOnly;
        var throughLiquid=TowerExchange.at(graph,1,List.of(source,basin.state()),new double[]{q},drawn,model);
        double dissolved=liquidOnly[FluidThermodynamics.AQUEOUS]/model.phaseMass(source,FluidThermodynamics.AQUEOUS)*source.aqueousAmountsView()[nitrogen]*model.molecularWeight(nitrogen);
        assertTrue(throughLiquid.loss(nitrogen)<=dissolved*(1+1e-9),"no gas is removed: "+throughLiquid.loss(nitrogen)+" of "+dissolved+" dissolved");
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
            var rates=TowerExchange.at(new PassiveNetwork(List.of(generator(1,8.5,source),basin(2,tower,state)),List.of(feed())),1,List.of(source,state),new double[]{flow},null,model);
            assertEquals(target,rates.fill().outletTemperature(),.001);
            assertEquals(inlet,rates.inletTemperature());
            assertTrue(rates.fill().speed()>0&&rates.fill().speed()<1);
            if(previous>=0)assertEquals(previous,rates.fill().speed(),1e-5);
            previous=rates.fill().speed();
        }
    }
    /** {@code mass} kg/s of a liquid of composition {@code liquid} (any scale, one slot) at {@code t}, through a tower at full speed. */
    private static TowerTransfer.Prepared liquid(FluidThermodynamics model,double[] liquid,double mass,double t){
        int n=model.componentCount();double m=0;for(int c=0;c<n;c++)m+=liquid[c]*model.molecularWeight(c);
        double[] scaled=liquid.clone();for(int c=0;c<n;c++)scaled[c]*=mass/m;
        return TowerTransfer.prepare(model,new TowerTransfer.Stream(new double[n],new double[n],scaled,0,0,mass,t));
    }
    private static TowerTransfer.Result spray(FluidThermodynamics model,TowerTransfer.Prepared p,double t){return TowerTransfer.spray(model,CoolingTower.induced(8,2,1,1),p,t,1);}
    private static final double FLOW=2*CoolingTower.induced(8,2,1,1).area();
    /**
     * COOLING_TOWER_PLAN.md test 2. Water with 10 mol % ethanol at 35 °C (the pilot aqueous package): over the liquid the
     * equation of state holds ethanol at 4.1 kPa beside water's 5.2 kPa, and the ambient air already holds 1.6 kPa of water,
     * so ethanol leaves faster than water by moles (1.5 times: the air moves towards 4.1 kPa of ethanol from none, and towards
     * water's 5.2 from 1.6) and nearly four times by mass; no compound leaves more than the stream brings, and the liquid cools
     * further than water alone.
     */
    @Test void ethanolInWaterEvaporatesBesideTheWater(){
        var pilot=FluidThermodynamics.forNetwork(com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.catalog(),com.wormzjl.icheme.science.material.PilotAqueousTestCatalog.PACKAGE_ID);
        int ethanol=pilot.components().indexOf("Ethanol"),w=pilot.componentCount()-1;
        double[] mix=new double[pilot.componentCount()];mix[ethanol]=.1;mix[w]=.9;
        var p=liquid(pilot,mix,FLOW,308.15);var r=spray(pilot,p,308.15);
        double molar=(r.loss(ethanol)/pilot.molecularWeight(ethanol))/(r.loss(w)/pilot.waterMolecularWeight);
        assertEquals(1.5,molar,.15,"ethanol to water by moles");
        assertTrue(r.loss(ethanol)/r.loss(w)>3,"by mass");
        assertTrue(r.loss(ethanol)<p.stream().aqueous()[ethanol]*pilot.molecularWeight(ethanol));
        double[] plain=new double[pilot.componentCount()];plain[w]=1;var water=spray(pilot,liquid(pilot,plain,FLOW,308.15),308.15);
        assertTrue(r.outletTemperature()<water.outletTemperature()&&r.dutyWatt()>water.dutyWatt(),"the volatile cools the liquid further");
    }
    /**
     * COOLING_TOWER_PLAN.md test 3. Air-free water leaves the fill saturated with the air at its leaving temperature: each gas
     * at the model's own Henry value, {@code x = p_air / (phi P)} (about 9e-6 nitrogen and 5e-6 oxygen at 25 °C); at the
     * basin's pressure that water holds no gas phase (at exactly one atmosphere it stands 0.1 % under its bubble pressure:
     * it left the fill colder than the air, so its own water stands above the air's humidity).
     */
    @Test void airFreeWaterLeavesSaturatedWithAir(){
        int nitrogen=model.components().indexOf("Nitrogen"),oxygen=model.components().indexOf("Oxygen");
        double[] pure=new double[model.componentCount()];pure[water()]=1;double moles=FLOW/model.waterMolecularWeight;
        var r=spray(model,liquid(model,pure,FLOW,298.15),298.15);double out=r.outletTemperature();
        double dry=CoolingTower.PRESSURE-CoolingTower.RELATIVE_HUMIDITY*model.saturationPressure(CoolingTower.AIR_TEMPERATURE);
        var phase=model.hydrocarbon.phase(out,CoolingTower.PRESSURE,pure,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID);
        double[] leaving=pure.clone();leaving[water()]=moles;
        for(int[] gas:new int[][]{{nitrogen,7808},{oxygen,2095}}){
            double expected=gas[1]*1e-4*dry/(Math.exp(phase.logFugacityView()[gas[0]])*CoolingTower.PRESSURE),x=-r.loss(gas[0])/model.molecularWeight(gas[0])/moles;
            assertEquals(expected,x,.01*expected,model.components().get(gas[0]));
            leaving[gas[0]]=x*moles;
        }
        assertEquals(9e-6,leaving[nitrogen]/moles,1.2e-6);assertEquals(5e-6,leaving[oxygen]/moles,.6e-6);
        assertFalse(FluidThermodynamics.holdsVapor(model.flashTP(out,PhaseLayout.atmosphere(CoolingTower.BASIN_DEPTH),leaving,()->{})),"no gas phase in the basin");
    }
    /**
     * COOLING_TOWER_PLAN.md test 5. Water at 380 K under pressure flashes at the nozzle to the model's boiling point at one
     * atmosphere: the vapour share is the liquid's heat above that point over the latent heat (about 1.2 %), and the rest
     * then cools in the fill. Across the boiling point the rates are continuous. The table's straight line in 1/T between
     * 380 K and the wet bulb puts that point 0.5 K above the engine's, and its latent heat (their mean) above the
     * engine's there: the vapour share comes out 13 % below the engine's heat balance.
     */
    @Test void hotWaterFlashesAtTheNozzleThenCools(){
        double[] pure=new double[model.componentCount()];pure[water()]=1;
        double lo=360,hi=390;for(int k=0;k<60;k++){double mid=(lo+hi)/2;if(FluidThermodynamics.holdsVapor(model.flashTP(mid,CoolingTower.PRESSURE,pure,()->{})))hi=mid;else lo=mid;}
        double boiling=hi;
        var liquidAt=(java.util.function.DoubleUnaryOperator)t->model.hydrocarbon.phase(t,CoolingTower.PRESSURE,pure,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID).molarEnthalpy();
        double latent=model.hydrocarbon.phase(boiling,CoolingTower.PRESSURE,pure,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.VAPOR).molarEnthalpy()-liquidAt.applyAsDouble(boiling);
        double expected=(liquidAt.applyAsDouble(380)-liquidAt.applyAsDouble(boiling))/latent;
        var p=liquid(model,pure,FLOW,380);var r=spray(model,p,380);
        assertEquals(boiling,p.nozzle().temperature(),.75,"the nozzle's let-down to the boiling point");
        assertEquals(expected,1-p.nozzle().remaining(),.15*expected,"the nozzle's vapour");
        double table=p.nozzle().temperature();
        assertTrue(r.outletTemperature()<boiling-30,"the fill then cools what is left: "+r.outletTemperature());
        var below=spray(model,liquid(model,pure,FLOW,table-.15),table-.15);var above=spray(model,liquid(model,pure,FLOW,table+.15),table+.15);
        assertEquals(below.waterLoss(),above.waterLoss(),.005*below.waterLoss(),"continuous across the boiling point");
        assertEquals(below.dutyWatt(),above.dutyWatt(),.005*below.dutyWatt());
    }
    /**
     * COOLING_TOWER_PLAN.md test 6. Water saturated with methane at 10 bar: at one atmosphere it keeps, by Henry's law, the
     * share {@code (P - p_w) / (10 bar - p_w)} of its methane, so about 90 % leaves at the nozzle; the fill strips most of the
     * rest (the air holds no methane), 95 % or more in all.
     */
    @Test void methaneSaturatedWaterLosesMostOfItAtTheNozzle(){
        int methane=model.components().indexOf("Methane");double[] feed=new double[model.componentCount()];feed[water()]=1;feed[methane]=.01;
        double t=298.15,pw=model.saturationPressure(t);var saturated=model.flashTP(t,1e6,feed,()->{});assertTrue(FluidThermodynamics.holdsVapor(saturated));
        var p=liquid(model,saturated.aqueousAmounts(),FLOW,t);var r=spray(model,p,t);
        double brought=p.stream().aqueous()[methane],kept=(CoolingTower.PRESSURE-pw)/(1e6-pw);
        assertEquals(1-kept,FLOW*p.nozzle().vapourPerKg()[methane]/brought,.02,"the nozzle's share");
        assertTrue(r.loss(methane)/(brought*model.molecularWeight(methane))>.95,"in all");
        assertTrue(r.loss(methane)<=brought*model.molecularWeight(methane)*(1+1e-12));
    }
    /**
     * COOLING_TOWER_PLAN.md test 7. Water holding carbon dioxide (2e-4 by moles) at 303 and at 305 K, either side of its
     * critical temperature (304.1 K): no rule switches there, so its duty over that of water alone at the same inlet moves by a
     * fraction of a per cent.
     */
    @Test void dissolvedCarbonDioxideBringsNoJumpAtItsCriticalTemperature(){
        int co2=model.components().indexOf("CarbonDioxide");double[] pure=new double[model.componentCount()],carbonated=pure.clone();pure[water()]=1;carbonated[water()]=1;carbonated[co2]=2e-4;
        double[] ratio=new double[2];int k=0;
        for(double t:new double[]{303,305})ratio[k++]=spray(model,liquid(model,carbonated,FLOW,t),t).dutyWatt()/spray(model,liquid(model,pure,FLOW,t),t).dutyWatt();
        assertEquals(ratio[0],ratio[1],.005,java.util.Arrays.toString(ratio));
    }
    /**
     * COOLING_TOWER_PLAN.md test 8. A dry oil (n-hexane at 35 °C): its light compound leaves by its partial pressure; the air's
     * water enters it only up to equilibrium with the ambient air at the leaving temperature, {@code x = p_w,air / (phi_w P)}.
     */
    @Test void aDryOilTakesUpTheAirsWaterOnlyToEquilibrium(){
        int hexane=model.components().indexOf("N-hexane");double[] oil=new double[model.componentCount()];oil[hexane]=1;
        int n=model.componentCount();double[] moles=oil.clone();moles[hexane]=FLOW/model.molecularWeight(hexane);
        var r=TowerTransfer.spray(model,CoolingTower.induced(8,2,1,1),TowerTransfer.prepare(model,new TowerTransfer.Stream(new double[n],moles,new double[n],0,0,FLOW,308.15)),308.15,1);
        assertTrue(r.loss(hexane)>0);
        double gained=-r.waterLoss()/model.waterMolecularWeight;assertTrue(gained>0,"the air's water enters");
        var phase=model.hydrocarbon.phase(r.outletTemperature(),CoolingTower.PRESSURE,oil,com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID);
        double x=CoolingTower.RELATIVE_HUMIDITY*model.saturationPressure(CoolingTower.AIR_TEMPERATURE)/(Math.exp(phase.logFugacityView()[water()])*CoolingTower.PRESSURE);
        double left=moles[hexane]-r.loss(hexane)/model.molecularWeight(hexane);
        assertTrue(gained<=1.02*x/(1-x)*left,"at most equilibrium: "+gained+" against "+x/(1-x)*left);
    }
    @Test void targetModeStopsFansWithoutSprayWhileTheWarmBasinStillCools(){
        var tower=CoolingTower.induced(8,2,1,1).withControl(CoolingTower.Mode.TARGET_TEMPERATURE,1,298.15);
        var node=basin(1,tower,water(308.15,101325,64));
        var rates=TowerExchange.at(new PassiveNetwork(List.of(node),List.of()),0,List.of(node.state()),new double[0],null,model);
        assertEquals(0,rates.fill().speed());assertEquals(0,rates.fill().fanPowerWatt());
        assertEquals(0,rates.massLoss());assertTrue(rates.surfaceWatt()>0);
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
            if(stage==4)assertEquals(0,result.towers().get(2L).massLoss(),"shutdown stops fill transfer");
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
        for(int c=0;c<model.componentCount();c++){double in=0,out=0;
            for(int i=0;i<graph.reservoirs().size();i++)if(!graph.reservoirs().get(i).fixed()){in+=graph.reservoirs().get(i).inventory().moles()[c];out+=result.inventories().get(i).moles()[c];}
assertEquals(in+result.externalMoles()[c],out,1e-9*Math.max(1,Math.abs(out)),model.components().get(c));}
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
