package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;
import java.util.List;
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
            assertEquals(CoolingTowerDesign.naturalFacets(diameter),CoolingTowerBasin.surface(shape).size());
            assertEquals(1,InstrumentGeometry.levelSockets(shape).size());
            assertEquals(1,InstrumentGeometry.levelSockets(shape).getFirst().y());
        }
    }
    @Test void naturalWaterSharesTheBasinInnerWallAtBothDiameterExtremesAndEveryRotation(){
        for(int diameter:new int[]{40,80})for(var heading:EquipmentShape.Heading.values()){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,diameter,(int)(1.3*diameter),heading,0);
            double radius=diameter/2.0,axis=radius+.5;
            var wall=ProcessUnit.of(shape).mesh().facets().stream()
                    .filter(f->f.material()==Mesh.Material.CONCRETE&&f.min()[1]==.2&&f.max()[1]==2)
                    .filter(f->f.positions().stream().allMatch(p->Math.abs(Math.hypot(p[0]-axis,p[2]-axis)-radius)<1e-9)).toList();
            var water=CoolingTowerBasin.surface(shape);
            assertEquals(wall.size(),water.size());
            for(int segment=0;segment<wall.size();segment++){
                var corners=wall.get(segment).positions();var triangle=water.get(segment);
                double[] centre=shape.transform().point(new double[]{axis,0,axis});
                double[] first=shape.transform().point(corners.get(0)),last=shape.transform().point(corners.get(1));
                assertEquals(centre[0],triangle.a().x(),1e-9);assertEquals(centre[2],triangle.a().z(),1e-9);
                assertEquals(first[0],triangle.b().x(),1e-9);assertEquals(first[2],triangle.b().z(),1e-9);
                assertEquals(last[0],triangle.c().x(),1e-9);assertEquals(last[2],triangle.c().z(),1e-9);
                double bx=triangle.b().x()-triangle.a().x(),bz=triangle.b().z()-triangle.a().z();
                double cx=triangle.c().x()-triangle.a().x(),cz=triangle.c().z()-triangle.a().z();
                assertTrue(bz*cx-bx*cz>0,"The water triangles face upward");
            }
        }
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
    @Test void theChimneyIsOneBoundedMeshOutsideTheChunkMeshes(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,80,104,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);var shell=ShapeRasterizer.towerShell(shape);
        int facets=Math.max(48,(int)Math.ceil(Math.PI*80/4)),bands=(int)Math.ceil((104-shape.coolingTower().shellBaseHeight())/8);
        assertEquals(2*facets*bands,shell.size(),"Only chimney bands join the static shell VBO, not concrete supports");
        assertEquals(4*facets*bands,shell.stream().mapToInt(p->p.size()-2).sum());
        int triangles=unit.mesh().facets().stream().mapToInt(f->f.positions().size()-2).sum();
        assertTrue(triangles<8000,"A bounded whole tower: "+triangles);
        for(var cell:ShapeRasterizer.layout(shape).shell())if(cell.y()==60)assertTrue(ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape,cell.x(),cell.y(),cell.z())).isEmpty(),"The chunks draw none of the chimney");
        for(var polygon:shell)for(int i=1;i<polygon.positions().length;i+=3)
            assertTrue(polygon.positions()[i]>=shape.coolingTower().shellBaseHeight()-1e-9);
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
    @Test void towerNozzleNecksStartInsideTheirWallsSoConnectedPipeSizesTheWholeNeck(){
        for(var shape:List.of(
                EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,4,2,EquipmentShape.Heading.NORTH,0),
                EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,40,52,EquipmentShape.Heading.NORTH,0))){
            var unit=ProcessUnit.of(shape);
            for(var nozzle:shape.nozzles())
                assertTrue(unit.inside(nozzle.base()),shape.kind()+" "+nozzle.role()+" root is under the wall, with no fixed pipe stub outside it");
        }
    }
}
