package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoolingTowerGeometryTest {
    @Test void allowedDimensionsUseTheSameNominalAreasAndBasinVolumes(){
        for(int width=4;width<=8;width+=2)for(int fill=2;fill<=6;fill++){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width,fill,EquipmentShape.Heading.NORTH,0);
            assertEquals(width*width,shape.coolingTower().area());
            assertEquals(2*width*width,shape.volume());
            assertEquals(1,ProcessUnit.of(shape).motions.size());
        }
        for(int diameter=40;diameter<=80;diameter+=10){
            int height=2*diameter;
            var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,diameter,height,EquipmentShape.Heading.NORTH,0);
            assertEquals(Math.PI*diameter*diameter/4,shape.coolingTower().area());
            assertEquals(2*shape.coolingTower().area(),shape.volume());
            assertEquals(height,shape.sizeY());
            var unit=ProcessUnit.of(shape);double axis=diameter/2.0+.5;
            assertFalse(unit.inside(new double[]{axis,height*.75,axis}));
            assertTrue(unit.mesh().facets().size()<15000,"The shell has a bounded surface mesh");
            assertEquals(64,CoolingTowerBasin.surface(shape).size());
            assertEquals(1,InstrumentGeometry.levelSockets(shape).size());
            assertEquals(1,InstrumentGeometry.levelSockets(shape).getFirst().y());
        }
    }
    @Test void aBankRepeatsFansAndHasNoSolidAirVolume(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,8,2,EquipmentShape.Heading.NORTH,0).withTowerBank(new TowerBank(6,4));
        var unit=ProcessUnit.of(shape);
        assertEquals(24,unit.motions.size());
        assertEquals(2,shape.nozzles().size());
        assertEquals(24*64*2,shape.volume());
        assertTrue(ShapeRasterizer.layout(shape).interior().isEmpty());
        assertThrows(IllegalArgumentException.class,()->EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,10,2,EquipmentShape.Heading.NORTH,0));
    }
    @Test void aFullHeightChimneyStaysHollowAndHasEveryConnection(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,80,160,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);double axis=40.5;
        assertFalse(unit.inside(new double[]{axis,80,axis}));
        assertFalse(unit.filled(new double[]{axis,80,axis}));
        assertEquals(2,shape.nozzles().size());
        assertEquals(1,shape.nozzles().stream().filter(n->n.role()==EquipmentShape.Role.HOT_WATER_INLET).count());
        assertEquals(1,shape.nozzles().stream().filter(n->n.role()==EquipmentShape.Role.COLD_WATER_OUTLET).count());
        var layout=ShapeRasterizer.layout(shape);
        assertTrue(layout.interior().isEmpty());
        assertTrue(layout.shell().size()<shape.sizeX()*shape.sizeY()*shape.sizeZ()/5,"Cost follows surfaces, not chimney volume");
        for(var nozzle:shape.nozzles())for(int k=0;k<3;k++)if(k!=nozzle.axis())assertEquals(.5,nozzle.base()[k]-Math.floor(nozzle.base()[k]),1e-10);
    }
    @Test void naturalChimneysUseOneSurfaceMeshAndNoUpperChunkQuads(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,40,80,EquipmentShape.Heading.NORTH,0);
        var shell=ShapeRasterizer.towerShell(shape);
        assertFalse(shell.isEmpty());assertTrue(shell.size()<4000);
        int upperCells=0;
        for(var cell:ShapeRasterizer.layout(shape).shell())if(cell.y()==40){
            assertTrue(ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape,cell.x(),cell.y(),cell.z())).isEmpty());upperCells++;
        }
        assertTrue(upperCells>50,"Collision cells still occupy the chimney wall");
        for(var polygon:shell){
            for(double uv:polygon.uvs())assertTrue(uv>=0&&uv<=1);
            for(double normal:polygon.normals())assertTrue(Double.isFinite(normal));
        }
        assertTrue(ShapeRasterizer.towerPreview(shape).size()<5000,"Preview cost follows the coarse facets, not block cuts");
    }
}
