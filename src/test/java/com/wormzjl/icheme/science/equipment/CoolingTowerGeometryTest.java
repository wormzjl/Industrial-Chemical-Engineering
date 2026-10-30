package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoolingTowerGeometryTest {
    @Test void allowedDimensionsUseTheSameNominalAreasAndBasinVolumes(){
        for(int width=4;width<=8;width+=2)for(int fill=2;fill<=6;fill++){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width,fill,EquipmentShape.Heading.NORTH,0);
            assertEquals(width*width,shape.coolingTower().area());
            assertEquals(4.0/3*width*width,shape.volume(),1e-9);
            assertEquals(4.0/3,shape.levelHeight());
            assertEquals(4.0/3,shape.fluidGeometry().height());
            assertEquals(1,ProcessUnit.of(shape).motions.size());
        }
        for(int diameter=40;diameter<=80;diameter+=10){
            int height=2*diameter;
            var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,diameter,height,EquipmentShape.Heading.NORTH,0);
            assertEquals(Math.PI*diameter*diameter/4,shape.coolingTower().area());
            assertEquals(4.0/3*shape.coolingTower().area(),shape.volume(),1e-9);
            assertEquals(4.0/3,shape.levelHeight());
            assertEquals(height,shape.sizeY());
            var unit=ProcessUnit.of(shape);double axis=diameter/2.0+.5;
            assertFalse(unit.inside(new double[]{axis,height*.75,axis}));
            assertTrue(unit.mesh().facets().size()<15000,"The shell has a bounded surface mesh");
            assertEquals(64,CoolingTowerBasin.surface(shape).size());
            assertEquals(1,InstrumentGeometry.levelSockets(shape).size());
            assertEquals(1,InstrumentGeometry.levelSockets(shape).getFirst().y());
        }
    }
    @Test void inducedFillDepthDefaultsToHalfTheCellWidth(){
        for(int width=4;width<=8;width+=2)
            assertEquals(width/2,ProcessUnit.defaultLength(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width));
    }
    @Test void bothBasinWallsAreTwoThirdsTheirOriginalHeight(){
        var induced=ProcessUnit.of(EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,4,2,EquipmentShape.Heading.NORTH,0));
        var basin=induced.mesh().facets().stream().filter(f->f.material()==Mesh.Material.SUPPORT).toList();
        assertTrue(basin.stream().anyMatch(f->f.max()[1]==2));
        assertTrue(basin.stream().allMatch(f->f.max()[1]<=2));
        var natural=ProcessUnit.of(EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,40,80,EquipmentShape.Heading.NORTH,0));
        assertTrue(natural.mesh().facets().stream().anyMatch(f->f.material()==Mesh.Material.CONCRETE&&f.min()[1]==.2&&f.max()[1]==2));
        assertFalse(natural.mesh().facets().stream().anyMatch(f->f.material()==Mesh.Material.CONCRETE&&f.min()[1]==.2&&f.max()[1]==3));
    }
    @Test void aBankRepeatsFansAndHasNoSolidAirVolume(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,8,2,EquipmentShape.Heading.NORTH,0).withTowerBank(new TowerBank(6,4));
        var unit=ProcessUnit.of(shape);
        assertEquals(24,unit.motions.size());
        assertEquals(2,shape.nozzles().size());
        assertEquals(24*64*(4.0/3),shape.volume(),1e-9);
        assertTrue(ShapeRasterizer.layout(shape).interior().isEmpty());
        assertThrows(IllegalArgumentException.class,()->EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,10,2,EquipmentShape.Heading.NORTH,0));
    }
    @Test void inducedFrameReachesBasinFloorAndFanDeckCollides(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,4,2,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);
        assertTrue(unit.mesh().facets().stream().anyMatch(f->f.material()==Mesh.Material.STEELWORK
                && f.min()[0]>=.1-1e-9&&f.max()[0]<=.3+1e-9&&f.min()[2]>=.1-1e-9&&f.max()[2]<=.3+1e-9
                && f.min()[1]==0&&f.max()[1]==9),"A leg is drawn from the basin floor to the fan deck");
        assertTrue(unit.inside(new double[]{.2,.5,.2}),"The leg also collides below the basin rim");
        double deck=9,centre=2;
        assertTrue(unit.inside(new double[]{centre+.425*4,deck+.01,centre}),"The square fan deck collides outside the cowl");
        assertTrue(unit.inside(new double[]{3.95,deck+.01,3.95}),"The fan deck reaches the square cell's corners");
        assertFalse(unit.inside(new double[]{-.05,deck+.01,centre}),"Its collision does not project beyond the cell");
        assertTrue(unit.inside(new double[]{centre,deck+.01,centre}),"The fan rotor collides at the bottom of its cowl");
        assertTrue(unit.inside(new double[]{centre,deck+.99,centre}),"The fan grille collides at the top of its cowl");
    }
    @Test void inducedCasingEnclosesOnePackingLayer(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,4,2,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);
        assertEquals(6,unit.mesh().facets().stream().filter(f->f.material()==Mesh.Material.AC_FINS).count(),"One fill box and no second packing layer");
        assertTrue(unit.inside(new double[]{.05,5,2}),"The casing collides beside the packing");
        assertFalse(unit.inside(new double[]{2,7.1,2}),"The removed upper packing layer leaves open plenum air");
    }
    @Test void naturalSupportIsAXBracedPerimeterRing(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,40,80,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);double axis=20.5,r=19.5,a=2*Math.PI/32;
        double[] p={axis+r,2,axis},q={axis+r*Math.cos(a),10,axis+r*Math.sin(a)};
        assertTrue(unit.inside(new double[]{(p[0]+q[0])/2,6,(p[2]+q[2])/2}),"A diagonal support has matching collision");
        assertTrue(unit.mesh().facets().stream().anyMatch(f->f.material()==Mesh.Material.STEELWORK&&f.max()[1]-f.min()[1]>4));
        assertFalse(unit.inside(new double[]{axis,6,axis}),"The support ring leaves the tower's central air open");
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
