package com.wormzjl.icheme.client.fx.silhouette;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan section 4.7 (batch 2026-11-10-custom-shader-system, W10): the silhouette boxes cover the layout cells, grown by 0.02 m, within their budgets. */
class SilhouetteShapesTest {
    private static final double EPS=1e-9;
    private static final Paint PAINT=new Paint(0xA1A1A0,0x5B686D);

    private static List<EquipmentShape> shapes() {
        return List.of(
                EquipmentShape.drum(2,6,EquipmentShape.Orientation.HORIZONTAL,EquipmentShape.Heading.EAST,EquipmentShape.defaultSupport(2,EquipmentShape.Orientation.HORIZONTAL)),
                EquipmentShape.drum(2,4,EquipmentShape.Orientation.VERTICAL,EquipmentShape.Heading.NORTH,EquipmentShape.defaultSupport(2,EquipmentShape.Orientation.VERTICAL)));
    }
    private static Set<ShapeRasterizer.Cell> cells(EquipmentShape shape) {
        var layout=ShapeRasterizer.layout(shape);var out=new HashSet<ShapeRasterizer.Cell>(layout.shell());out.addAll(layout.interior());return out;
    }
    private static boolean whole(double v){return Math.abs(v-Math.rint(v))<EPS;}

    @Test void nearBoxesCoverEveryLayoutCellOnceGrownByTwoCentimetres() {
        for(var shape:shapes()){
            var cells=cells(shape);var finishes=SilhouetteShapes.cells(shape);
            var built=SilhouetteShapes.build(shape,PAINT);
            assertFalse(built.near().isEmpty(),shape.toString());
            var covered=new HashSet<ShapeRasterizer.Cell>();
            for(var b:built.near()){
                double x0=b.x0()+SilhouetteShapes.GROW,y0=b.y0()+SilhouetteShapes.GROW,z0=b.z0()+SilhouetteShapes.GROW;
                double x1=b.x1()-SilhouetteShapes.GROW,y1=b.y1()-SilhouetteShapes.GROW,z1=b.z1()-SilhouetteShapes.GROW;
                // Grown by exactly GROW on every side of whole cells.
                assertTrue(whole(x0)&&whole(y0)&&whole(z0)&&whole(x1)&&whole(y1)&&whole(z1),b.toString());
                for(int x=(int)Math.rint(x0);x<(int)Math.rint(x1);x++)for(int y=(int)Math.rint(y0);y<(int)Math.rint(y1);y++)for(int z=(int)Math.rint(z0);z<(int)Math.rint(z1);z++){
                    var cell=new ShapeRasterizer.Cell(x,y,z);
                    assertTrue(cells.contains(cell),"a box covers only layout cells: "+cell);
                    assertTrue(covered.add(cell),"one box per cell: "+cell);
                    var finish=SilhouetteShapes.Finish.ALL[finishes.at(x-finishes.x0(),y-finishes.y0(),z-finishes.z0())];
                    assertEquals(finish.rgb(PAINT),b.rgb(),"a box has its cells' colour");
                    assertEquals(finish.stone,b.stone());
                }
            }
            assertEquals(cells,covered,"every layout cell is covered");
            assertTrue(built.near().size()<cells.size(),"greedy merge joins cells: "+built.near().size()+" boxes for "+cells.size()+" cells");
        }
    }

    @Test void theHullHasAtMostSixteenBoxesAndCoversEveryCell() {
        for(var shape:shapes()){
            var built=SilhouetteShapes.build(shape,PAINT);
            assertTrue(!built.far().isEmpty()&&built.far().size()<=SilhouetteShapes.HULL_BOXES,"hull boxes: "+built.far().size());
            for(var c:cells(shape)){
                double g=SilhouetteShapes.FAR_GROW-EPS;
                assertTrue(built.far().stream().anyMatch(b->b.contains(c.x()-g,c.y()-g,c.z()-g)&&b.contains(c.x()+1+g,c.y()+1+g,c.z()+1+g)),"hull covers "+c+" grown by FAR_GROW");
            }
        }
    }

    @Test void aStructureOverItsBoxBudgetShowsItsHullOnly() {
        // A 32 x 16 x 32 checkerboard of two finishes: one box per cell, 16,384 > STRUCTURE_BOXES.
        int n=32,h=16;var finish=new byte[n*h*n];
        for(int y=0;y<h;y++)for(int z=0;z<n;z++)for(int x=0;x<n;x++)finish[(y*n+z)*n+x]=(byte)((x+y+z)%2);
        var cells=new SilhouetteShapes.Cells(0,0,0,n,h,n,finish);
        assertTrue(SilhouetteShapes.near(cells,PAINT).size()>SilhouetteShapes.STRUCTURE_BOXES);
        var built=SilhouetteShapes.build(cells,PAINT);
        assertTrue(built.near().isEmpty());
        assertTrue(!built.far().isEmpty()&&built.far().size()<=SilhouetteShapes.HULL_BOXES);
    }

    @Test void aCellTakesTheFinishWithTheMostSurface() {
        var big=square(1,Mesh.Material.PIPE);var small=square(.5,Mesh.Material.SHELL);var glass=square(2,Mesh.Material.GLASS);
        assertEquals(SilhouetteShapes.Finish.STEEL,SilhouetteShapes.finish(List.of(small,big,glass)));
        assertEquals(SilhouetteShapes.Finish.BODY,SilhouetteShapes.finish(List.of(small,glass)));
        assertEquals(SilhouetteShapes.Finish.STRUCTURE,SilhouetteShapes.finish(List.of(small,square(.8,Mesh.Material.SUPPORT))));
        assertEquals(SilhouetteShapes.Finish.CONCRETE,SilhouetteShapes.finish(List.of(square(1,Mesh.Material.CONCRETE))));
        assertEquals(SilhouetteShapes.Finish.BODY,SilhouetteShapes.finish(List.of()),"a cell that shows nothing takes the body paint");
        assertEquals(.25,SilhouetteShapes.area(small.positions()),EPS);
    }
    private static Mesh.Polygon square(double s,Mesh.Material material) {
        return new Mesh.Polygon(new double[]{0,0,0,s,0,0,s,s,0,0,s,0},new double[8],new double[12],material);
    }
}
