package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.FiredCoil;
import com.wormzjl.icheme.science.fluid.network.HeatSide;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The passages of a unit's heat sides (batch 2026-10-27-heat-exchanger-design, work package W2, plan section 5.1). */
class HeatSidesTest {
    private static final double WATER_DENSITY=998,WATER_VISCOSITY=1.0e-3;

    @Test void manyChannelsLoseWhatOneLosesAtItsShareOfTheFlow() {
        var one=new PipeResistance.Geometry(12,.0148,HeatSides.TUBE_ROUGHNESS,8);
        var many=one.withChannels(630);
        for(double flow:new double[]{.01,1,50,163}) {
            var single=PipeResistance.evaluate(one,flow/630,WATER_DENSITY,WATER_VISCOSITY);var whole=PipeResistance.evaluate(many,flow,WATER_DENSITY,WATER_VISCOSITY);
            assertEquals(single.pressureDrop(),whole.pressureDrop(),1e-12*single.pressureDrop());
            assertEquals(single.massFlowDerivative()/630,whole.massFlowDerivative(),1e-12*whole.massFlowDerivative());
            assertEquals(single.reynolds(),whole.reynolds(),1e-9*single.reynolds());
        }
        assertEquals(630*one.area(),many.area(),1e-15);
        // One channel is the pipe it always was: the same doubles.
        assertEquals(PipeResistance.evaluate(new PipeResistance.Geometry(10,.05,.000045,0),.1,1000,.001),PipeResistance.evaluate(new PipeResistance.Geometry(10,.05,.000045,0,1),.1,1000,.001));
        assertThrows(IllegalArgumentException.class,()->one.withChannels(0));
    }
    /** Plan section 5.1's example, shell 1 m and tubes 6 m: about 4 kPa at 50 kg/s of water (0.46 m/s), about 35 kPa at the design velocity of 1.5 m/s. */
    @Test void theExampleExchangersTubeSideLosesWhatThePlanStates() {
        var tubes=HeatSides.tubePassage(1,6);
        assertEquals(630,tubes.channels());assertEquals(12,tubes.length());
        assertEquals(.46,50/(WATER_DENSITY*tubes.area()),.01);
        assertEquals(4000,PipeResistance.evaluate(tubes,50,WATER_DENSITY,WATER_VISCOSITY).pressureDrop(),400);
        double design=1.5*WATER_DENSITY*tubes.area();
        assertEquals(163,design,2);
        assertEquals(35000,PipeResistance.evaluate(tubes,design,WATER_DENSITY,WATER_VISCOSITY).pressureDrop(),3500);
        var shell=HeatSides.shellPassage(1,6);
        assertEquals(.1,shell.area(),.001);assertEquals(15,shell.length());
    }
    @Test void anExchangerHasATubeAndAShellSideOnTheTubeSurface() {
        var shape=EquipmentShape.unit(EquipmentShape.Kind.EXCHANGER,1,6,EquipmentShape.Heading.SOUTH,2);
        var sides=HeatSides.of(shape);
        assertEquals(2,sides.size());
        assertEquals(HeatSides.Side.TUBE,sides.get(0).side());assertEquals(HeatSides.Side.SHELL,sides.get(1).side());
        for(var side:sides)assertEquals(450,side.area(),1e-9);
        assertEquals(EquipmentShape.Role.TUBE_INLET,sides.get(0).inlet());assertEquals(EquipmentShape.Role.SHELL_OUTLET,sides.get(1).outlet());
        assertTrue(shape.fluidDevice());
        // One node per side, each at its first inlet's cell; the first is the structure's fluid cell.
        var cells=ShapeRasterizer.fluidCells(shape);
        assertEquals(2,cells.size());assertNotEquals(cells.get(0),cells.get(1));assertEquals(cells.getFirst(),ShapeRasterizer.fluidCell(shape));
        assertTrue(HeatSides.of(EquipmentShape.unit(EquipmentShape.Kind.KETTLE,1,6,EquipmentShape.Heading.SOUTH,2)).isEmpty(),"the kettle's sides come with W7");
    }
    /**
     * Plan section 4: an air cooler's process stream sees air at 25 °C through UA_eff = C (1 − e^(−UA/C)) per m² of bundle
     * face: 2.15 (liquid), 2.44 (condensing) and 0.43 (gas) kW/K with the fans running; natural draught, a quarter of the
     * air and its film, leaves 0.63, 0.65 and 0.29.
     */
    @Test void anAirCoolersFilmsAreTheAirsEffectiveConductancePerPhase() {
        double[][] perFace={{2149.6,2435.7,426.3},{629.2,650.5,293.3}};
        for(int fans=1;fans>=0;fans--) {
            var films=HeatSides.airFilms(fans);double[] expected=perFace[1-fans];
            assertEquals(expected[0],5*films.liquid(),.5);assertEquals(expected[1],5*films.twoPhase(),.5);assertEquals(expected[2],5*films.gas(),.5);
        }
        assertThrows(IllegalArgumentException.class,()->HeatSides.airFilms(1.5));
        // The largest bay, liquid at 85 °C in 25 °C air with every tube meeting fresh air: about 1.6 MW (plan section 4).
        assertEquals(1.6e6,HeatSides.airFilms(1).liquid()*5*12.2*60,.05e6);
    }
    /** An air cooler bank has one process side over 5 m² of bare tube per m² of face, the bays' tubes in one pass each. */
    @Test void anAirCoolerHasOneProcessSideOverItsBaysTubes() {
        var bay=EquipmentShape.unit(EquipmentShape.Kind.AIR_COOLER,3,6,EquipmentShape.Heading.SOUTH,4);
        var sides=HeatSides.of(bay);
        assertEquals(1,sides.size());var side=sides.getFirst();
        assertEquals(HeatSides.Side.PROCESS,side.side());assertEquals(EquipmentShape.Role.PROCESS_INLET,side.inlet());assertEquals(EquipmentShape.Role.PROCESS_OUTLET,side.outlet());
        double face=ProcessUnit.bundleFace(3,6);
        assertEquals(5*face,side.area(),1e-9);
        // 174 tubes of 21.2 mm bore, 6 m, two header turns: water at 1.5 m/s loses about 10 kPa.
        assertEquals(174,side.passage().channels());assertEquals(6,side.passage().length());
        double design=1.5*WATER_DENSITY*side.passage().area();
        assertEquals(10000,PipeResistance.evaluate(side.passage(),design,WATER_DENSITY,WATER_VISCOSITY).pressureDrop(),2000);
        assertEquals(1,ShapeRasterizer.fluidCells(bay).size());assertTrue(bay.fluidDevice());
        // Fan power at full speed, 1 kW per m² of face, and by the fan laws an eighth of it at half speed.
        assertEquals(1000*face,HeatSides.fanPower(side.area(),1),1e-6);assertEquals(125*face,HeatSides.fanPower(side.area(),.5),1e-6);
        assertEquals(0,HeatSides.fanPower(side.area(),0));
        assertInstanceOf(HeatSide.class,HeatSides.heatSide(HeatSides.Unit.AIR_COOLER,HeatSides.Side.PROCESS,side.area(),1,0));
        assertNull(HeatSides.heatSide(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,450,0,0),"an exchanger's side waits for its partner (W3)");
    }
    /**
     * A fired heater (W6, plan section 4): a convection and a radiant coil, 30 % and 70 % of the datasheet duty at 15 and
     * 35 kW/m² of bare tube, each firing towards the set point (the convection coil 30 % of the way) up to its share.
     */
    @Test void aFiredHeatersCoilsShareItsDatasheetDuty() {
        for(var shape:new EquipmentShape[]{EquipmentShape.unit(EquipmentShape.Kind.VERTICAL_HEATER,4,8,EquipmentShape.Heading.NORTH,3),
                EquipmentShape.unit(EquipmentShape.Kind.CABIN_HEATER,6,12,EquipmentShape.Heading.WEST,3)}) {
            var sides=HeatSides.of(shape);double duty=1e6*ProcessUnit.heaterDuty(shape.kind(),shape.diameter(),shape.tangentLength());
            assertEquals(2,sides.size());assertTrue(shape.fluidDevice());
            var convection=sides.get(0);var radiant=sides.get(1);
            assertEquals(HeatSides.Side.CONVECTION,convection.side());assertEquals(EquipmentShape.Role.CONVECTION_INLET,convection.inlet());assertEquals(EquipmentShape.Role.CONVECTION_OUTLET,convection.outlet());
            assertEquals(HeatSides.Side.RADIANT,radiant.side());assertEquals(EquipmentShape.Role.RADIANT_INLET,radiant.inlet());assertEquals(EquipmentShape.Role.RADIANT_OUTLET,radiant.outlet());
            assertEquals(.3*duty,HeatSides.coilDuty(HeatSides.Side.CONVECTION,convection.area(),1),1e-9*duty);
            assertEquals(.7*duty,HeatSides.coilDuty(HeatSides.Side.RADIANT,radiant.area(),1),1e-9*duty);
            var cells=ShapeRasterizer.fluidCells(shape);assertEquals(2,cells.size());assertNotEquals(cells.get(0),cells.get(1));
            var radiantCoil=assertInstanceOf(FiredCoil.class,HeatSides.heatSide(HeatSides.Unit.FIRED_HEATER,HeatSides.Side.RADIANT,radiant.area(),1,573.15));
            assertEquals(573.15,radiantCoil.setTemperature());assertEquals(1,radiantCoil.reach());assertEquals(.7*duty,radiantCoil.duty(),1e-9*duty);
            var convectionCoil=assertInstanceOf(FiredCoil.class,HeatSides.heatSide(HeatSides.Unit.FIRED_HEATER,HeatSides.Side.CONVECTION,convection.area(),0,573.15));
            assertEquals(.3,convectionCoil.reach(),1e-12);assertEquals(0,convectionCoil.duty(),"burners out");
        }
        assertEquals(6.29e6,1e6*ProcessUnit.heaterDuty(EquipmentShape.Kind.VERTICAL_HEATER,4,8),1e4);
        // A 10 MW heater's radiant coil: one pass of 380 m, about 1 bar of oil at 2 m/s; 40 MW takes five passes.
        var coil=HeatSides.heaterPassage(200,10);
        assertEquals(1,coil.channels());assertEquals(379,coil.length(),1);
        double oil=750;
        assertEquals(1e5,PipeResistance.evaluate(coil,2*oil*coil.area(),oil,1e-3).pressureDrop(),2e4);
        assertEquals(5,HeatSides.heaterPassage(800,40).channels());
    }
}
