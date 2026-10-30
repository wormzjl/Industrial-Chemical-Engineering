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
            int height=(int)(1.3*diameter);
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
        var natural=ProcessUnit.of(EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,40,52,EquipmentShape.Heading.NORTH,0));
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
    @Test void naturalSupportScalesWithDiameterAndIncludesAnInternalGrillage(){
        for(int diameter:new int[]{40,80}){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,diameter,(int)(1.3*diameter),EquipmentShape.Heading.NORTH,0);
            var tower=shape.coolingTower();var unit=ProcessUnit.of(shape);
            double axis=diameter/2.0+.5,foot=diameter/2.0-.5,ring=.9*diameter/2.0-diameter/200.0,base=tower.shellBaseHeight(),strut=diameter/100.0;
            assertEquals(.14*diameter,tower.airInletHeight());
            assertEquals(base+3.5,tower.distributorHeight());
            double a=Math.PI/32;
            double[] bottom={axis+foot,0,axis},top={axis+ring*Math.cos(a),base,axis+ring*Math.sin(a)};
            assertTrue(ring<foot-strut,"Every perimeter member leans radially inward, not just around the circumference");
            assertTrue(unit.inside(new double[]{axis+foot,.1,axis}),"The V-strut foot reaches the basin floor");
            assertTrue(unit.inside(new double[]{(bottom[0]+top[0])/2,(bottom[1]+top[1])/2,(bottom[2]+top[2])/2}),
                    "The inclined concrete V-strut has matching collision");
            assertTrue(unit.mesh().facets().stream().anyMatch(f->f.material()==Mesh.Material.CONCRETE&&f.max()[1]-f.min()[1]>=tower.airInletHeight()));
            assertTrue(unit.inside(new double[]{axis+ring,base-strut/2,axis}),"Every V-strut top meets the resized chimney's ring");
            assertTrue(unit.inside(new double[]{axis,base/2,axis}),"An internal post reaches the basin floor");
            assertTrue(unit.inside(new double[]{axis+foot/4,base-diameter/320.0,axis}),"A grillage beam carries the fill");
            assertFalse(unit.inside(new double[]{axis+foot/4,base/2,axis+foot/4}),"Air passes between the internal supports");
        }
    }
    @Test void naturalShellHasOneQuarterOfItsFormerGpuGeometry(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,80,104,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);var shell=ShapeRasterizer.towerShell(shape);
        int facets=Math.max(48,(int)Math.ceil(Math.PI*80/4)),bands=(int)Math.ceil((104-shape.coolingTower().shellBaseHeight())/8);
        assertEquals(2*facets*bands,shell.size(),"Only chimney bands join the static shell VBO, not concrete supports");
        assertEquals(4*facets*bands,shell.stream().mapToInt(p->p.size()-2).sum());
        int triangles=unit.mesh().facets().stream().mapToInt(f->f.positions().size()-2).sum();
        assertTrue(triangles<8000,"The whole 80 m tower stays below one third of its former 23,016 triangles: "+triangles);
        for(var polygon:shell)for(int i=1;i<polygon.positions().length;i+=3)
            assertTrue(polygon.positions()[i]>=shape.coolingTower().shellBaseHeight()-1e-9);
    }
    @Test void towerMeshBatchingDoesNotAffectOrdinaryEquipment(){
        var shape=EquipmentShape.drum(5,10,EquipmentShape.Orientation.VERTICAL,EquipmentShape.Heading.NORTH,3);
        var cell=ShapeRasterizer.layout(shape).shell().getFirst();
        assertFalse(ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape,cell.x(),cell.y(),cell.z())).isEmpty());
    }
    @Test void aFullHeightChimneyStaysHollowAndHasEveryConnection(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,80,104,EquipmentShape.Heading.NORTH,0);
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
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,40,52,EquipmentShape.Heading.NORTH,0);
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
