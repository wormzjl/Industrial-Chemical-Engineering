package com.wormzjl.icheme.science.equipment;

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
}
