package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.CoolingTower;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CoolingTowerGeometryTest {
    @Test void allowedDimensionsUseTheSameNominalAreasAndBasinVolumes(){
        for(int width=8;width<=14;width+=2)for(int fill=1;fill<=3;fill++){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width,fill,EquipmentShape.Heading.NORTH,0);
            assertEquals(width*width,shape.coolingTower().area());
            assertEquals(width*width,shape.volume(),1e-9);
            assertEquals(1,shape.levelHeight());
            assertEquals(1,shape.fluidGeometry().height());
            assertEquals(1,ProcessUnit.of(shape).motions.size());
        }
        for(int diameter=40;diameter<=80;diameter+=10){
            int height=(int)(1.3*diameter);
            var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,diameter,height,EquipmentShape.Heading.NORTH,0);
            assertEquals(Math.PI*diameter*diameter/4,shape.coolingTower().area());
            assertEquals(shape.coolingTower().area(),shape.volume(),1e-9);
            assertEquals(1,shape.levelHeight());
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
                    .filter(f->f.material()==Mesh.Material.CONCRETE&&f.min()[1]==.2&&f.max()[1]==CoolingTower.BASIN_HEIGHT)
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
        var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,8,2,EquipmentShape.Heading.NORTH,0).withTowerBank(new TowerBank(8,2));
        var unit=ProcessUnit.of(shape);
        assertEquals(16,unit.motions.size());
        assertEquals(2,shape.nozzles().size());
        assertEquals(16*64,shape.volume(),1e-9);
        assertTrue(ShapeRasterizer.layout(shape).interior().isEmpty());
        assertThrows(IllegalArgumentException.class,()->EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,16,2,EquipmentShape.Heading.NORTH,0));
        assertThrows(IllegalArgumentException.class,()->new TowerBank(1,3),"A middle row would have no face for its air");
    }
    @Test void aFanCellIsAClosedShellWithACollidingDeckAndNoAirSolid(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,8,2,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);var tower=shape.coolingTower();
        assertTrue(unit.mesh().facets().stream().noneMatch(f->f.material()==Mesh.Material.AC_FINS||f.material()==Mesh.Material.AC_PLENUM),"Nothing is drawn inside the shell");
        double deck=tower.deckHeight()+Access.DECK-.01,centre=4,bell=deck+.25*(tower.height()-deck);
        assertTrue(unit.inside(new double[]{centre+.45*8,deck,centre}),"The square fan deck collides outside the stack");
        assertTrue(unit.inside(new double[]{7.95,deck,7.95}),"The fan deck reaches the square cell's corners");
        assertFalse(unit.inside(new double[]{-.05,deck,centre}),"Its collision does not project beyond the cell");
        assertTrue(unit.inside(new double[]{centre,bell+.2,centre}),"Nobody walks through the blades");
        assertFalse(unit.inside(new double[]{centre,3,centre}),"Air inside the shell is no solid");
        assertTrue(unit.inside(new double[]{.05,3,centre}),"The casing collides as a wall");
        assertEquals(tower.distributorHeight(),shape.nozzles().stream().filter(n->n.role()==EquipmentShape.Role.HOT_WATER_INLET).findFirst().orElseThrow().base()[1],1e-9,"The spray head is the nozzle's own height");
    }
    @Test void aFanCellsLadderStandsOnTheWallAndLandsOnTheDeck(){
        for(int width=8;width<=14;width+=2){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width,2,EquipmentShape.Heading.NORTH,0);
            var unit=ProcessUnit.of(shape);double deck=shape.coolingTower().deckHeight()+Access.DECK;
            var ladders=unit.ladders();
            assertEquals(deck+Access.LANDING,ladders.getLast().top(),1e-9,width+" m: the last section lands on the deck");
            assertEquals(width+Access.GAP,ladders.getLast().x(),1e-9,width+" m: it stands against the end wall");
            assertTrue(ladders.size()<=2,width+" m: at most one rest");
            assertTrue(unit.mesh().facets().stream().noneMatch(f->f.material()==Mesh.Material.STEELWORK&&f.min()[1]==0),width+" m: no post stands away from the tower");
        }
    }
    /** Shell, stack and fan at most 200 polygons a cell, whatever its width: nothing is cut at the block grid. */
    @Test void aFanCellDrawsWithinItsPolygonBudget(){
        var access=java.util.EnumSet.of(Mesh.Material.HANDRAIL,Mesh.Material.STEELWORK);
        for(int width=8;width<=14;width+=2)for(var bank:List.of(TowerBank.ONE,new TowerBank(3,2),new TowerBank(8,2))){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width,2,EquipmentShape.Heading.NORTH,0).withTowerBank(bank);
            var shell=ShapeRasterizer.towerShell(shape);
            long body=shell.stream().filter(p->!access.contains(p.material())).count()+ShapeRasterizer.moving(shape).stream().mapToLong(m->m.polygons().size()).sum();
            long ladder=shell.stream().filter(p->access.contains(p.material())).count(),chunks=0;
            for(var cell:ShapeRasterizer.layout(shape).shell())chunks+=ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape,cell.x(),cell.y(),cell.z())).size();
            assertTrue(body<=200*bank.cells(),width+" m "+bank+": "+body);
            assertTrue(ladder<=1000+6*width*(bank.columns()+bank.rows()),width+" m "+bank+": "+ladder);
            assertTrue(chunks<=450,width+" m "+bank+": only the nozzles and the rest's grating stay in the chunks: "+chunks);
        }
    }
    /** The deck is a walkway: a vanilla player walks the square round the stack, between the bell and the rail. */
    @Test void aPlayerWalksRoundAFanStack(){
        for(int width=8;width<=14;width+=2){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width,2,EquipmentShape.Heading.NORTH,0);
            var transform=shape.transform();var walk=PlayerWalk.in(shape);
            double half=width/2.0,lane=half-Access.RAIL_BAND-PlayerWalk.HALF-.125,deck=shape.coolingTower().deckHeight()+Access.DECK;
            walk.p=transform.point(new double[]{half+lane,deck+.01,half+lane});
            for(double[] corner:new double[][]{{-1,1},{-1,-1},{1,-1},{1,1}}){
                var goal=transform.point(new double[]{half+corner[0]*lane,deck,half+corner[1]*lane});
                for(int tick=0;tick<1000&&Math.hypot(goal[0]-walk.p[0],goal[2]-walk.p[2])>.3;tick++)walk.tick(PlayerWalk.unit(new double[]{goal[0]-walk.p[0],0,goal[2]-walk.p[2]}),false);
                assertTrue(Math.hypot(goal[0]-walk.p[0],goal[2]-walk.p[2])<=.3,width+" m: stopped by "+walk.blocker);
                assertEquals(goal[1],walk.p[1],.2,width+" m: the walk stays on the deck");
            }
        }
    }
    /** Looking down the stack shows the drive on its beams over the eliminators, the bore running down to them. */
    @Test void aFanStandsOnItsDriveOverTheEliminators(){
        for(int width=8;width<=14;width+=2){
            var shape=EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,width,2,EquipmentShape.Heading.NORTH,0);
            var facets=ProcessUnit.of(shape).mesh().facets();var tower=shape.coolingTower();
            double floor=tower.distributorHeight()+.4;
            assertTrue(facets.stream().anyMatch(f->f.material()==Mesh.Material.TOWER_DRIFT&&f.max()[1]==floor),width+" m: the eliminators close the bore");
            assertEquals(floor,facets.stream().filter(f->f.material()==Mesh.Material.TOWER_STACK).mapToDouble(f->f.min()[1]).min().orElseThrow(),1e-9,width+" m: the bore reaches them");
            assertTrue(facets.stream().anyMatch(f->f.material()==Mesh.Material.MOTOR&&f.part()<0&&f.min()[1]<tower.height()),width+" m: the drive stands under the fan");
            assertTrue(facets.stream().anyMatch(f->f.material()==Mesh.Material.SUPPORT&&f.max()[1]-f.min()[1]<.3),width+" m: on beams across the bore");
        }
    }
    /** Every facet shows its own window of the sprite, and the stain over it wraps round the shell and does not repeat with it. */
    @Test void aNaturalShellHasNoVisibleRepeat(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,80,104,EquipmentShape.Heading.NORTH,0);
        var shell=ShapeRasterizer.towerShell(shape);
        assertFalse(shell.isEmpty());
        for(var polygon:shell)for(double uv:polygon.uvs())assertTrue(uv>=-1e-9&&uv<=1+1e-9,"A window stays in the sprite");
        double centre=40.5,far=-1;
        for(double y=30;y<90;y++){
            double[] behind={centre-30,y,centre-1e-4},ahead={centre-30,y,centre+1e-4};
            assertEquals(ShapeRasterizer.towerStain(shape,behind),ShapeRasterizer.towerStain(shape,ahead),1e-3,"The stain closes round the shell");
            for(double a=0;a<6.2;a+=.3)far=Math.max(far,Math.abs(ShapeRasterizer.towerStain(shape,new double[]{centre+30*Math.cos(a),y,centre+30*Math.sin(a)})-ShapeRasterizer.towerStain(shape,new double[]{centre+30*Math.cos(a),y+16,centre+30*Math.sin(a)})));
        }
        assertTrue(far>.03,"One sprite height up is another stain: "+far);
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
    /** A natural tower spans dozens of sections: its still parts are one mesh, and what it costs follows what shows. */
    @Test void aNaturalTowerIsOneBoundedMeshOutsideTheChunkMeshes(){
        var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,80,104,EquipmentShape.Heading.NORTH,0);
        var unit=ProcessUnit.of(shape);var shell=ShapeRasterizer.towerShell(shape);
        int facets=Math.max(48,(int)Math.ceil(Math.PI*80/4)),bands=(int)Math.ceil((104-shape.coolingTower().shellBaseHeight())/8);
        var chimney=shell.stream().filter(p->p.material()==Mesh.Material.TOWER_CONCRETE).toList();
        assertEquals(2*facets*bands,chimney.size(),"Each chimney band is its facets, inside and outside");
        assertTrue(shell.size()<=3000,"The whole tower: "+shell.size());
        assertEquals(4*facets*bands,chimney.stream().mapToInt(p->p.size()-2).sum());
        int triangles=unit.mesh().facets().stream().mapToInt(f->f.positions().size()-2).sum();
        assertTrue(triangles<8000,"A bounded whole tower: "+triangles);
        long chunks=0;var cells=ShapeRasterizer.layout(shape).shell();
        for(var cell:cells)chunks+=ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape,cell.x(),cell.y(),cell.z())).size();
        assertTrue(chunks<=520,"Only the nozzles stay in the chunks: "+chunks);
        assertTrue(cells.size()<=42000,"A cell for what collides, none for hidden packing: "+cells.size());
        for(var polygon:chimney)for(int i=1;i<polygon.positions().length;i+=3)
            assertTrue(polygon.positions()[i]>=shape.coolingTower().shellBaseHeight()-1e-9);
    }
    /** A wall a quarter of a metre thick shows as a knife edge at the top: a ring closes it, drawn and colliding, at every diameter. */
    @Test void aNaturalShellEndsInAThickRing(){
        for(int diameter=40;diameter<=80;diameter+=10){
            int height=(int)(1.3*diameter);double axis=diameter/2.0+.5,edge=diameter/100.0;
            var shape=EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,diameter,height,EquipmentShape.Heading.NORTH,0);
            var unit=ProcessUnit.of(shape);
            double lip=unit.mesh().facets().stream().filter(f->f.material()==Mesh.Material.TOWER_CONCRETE).flatMap(f->f.positions().stream())
                    .filter(p->p[1]==height).mapToDouble(p->Math.hypot(p[0]-axis,p[2]-axis)).max().orElseThrow();
            assertTrue(unit.mesh().facets().stream().anyMatch(f->f.material()==Mesh.Material.CONCRETE&&f.min()[1]==height&&f.max()[1]==height&&f.normal()[1]>.9),diameter+" m: the top is closed");
            for(double thick:new double[]{lip+.45*edge,lip-.25-.45*edge})assertTrue(unit.inside(new double[]{axis+thick,height-edge,axis}),diameter+" m: the ring collides at "+thick);
            assertFalse(unit.inside(new double[]{axis+lip+edge,height-edge,axis}),diameter+" m: it stops outside the wall");
        }
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
                EquipmentShape.unit(EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER,8,2,EquipmentShape.Heading.NORTH,0),
                EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,40,52,EquipmentShape.Heading.NORTH,0))){
            var unit=ProcessUnit.of(shape);
            for(var nozzle:shape.nozzles())
                assertTrue(unit.inside(nozzle.base()),shape.kind()+" "+nozzle.role()+" root is under the wall, with no fixed pipe stub outside it");
        }
    }
}
