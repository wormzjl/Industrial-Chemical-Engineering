package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.network.CoolingTower;
import java.util.ArrayList;
import java.util.List;

/** Small, immutable surface mesh. Height comes from the scheduled basin display. */
public final class CoolingTowerBasin {
    private CoolingTowerBasin() {}
    public record Point(double x,double z) {}
    public record Triangle(Point a,Point b,Point c) {}

    public static List<Triangle> surface(EquipmentShape shape){
        if(!shape.kind().coolingTower())throw new IllegalArgumentException("A tower basin is required");
        var tower=shape.coolingTower();
        var triangles=new ArrayList<Triangle>();
        if(tower.draft()==CoolingTower.Draft.INDUCED){
            var a=point(shape,0,0);var b=point(shape,0,tower.width()*tower.rows());
            var c=point(shape,tower.width()*tower.columns(),tower.width()*tower.rows());
            var d=point(shape,tower.width()*tower.columns(),0);
            triangles.add(new Triangle(a,b,c));triangles.add(new Triangle(a,c,d));
        }else {
            double radius=tower.width()/2;int facets=CoolingTowerDesign.naturalFacets(tower.width());
            var frame=CoolingTowerDesign.naturalFrame(tower.width());
            var centre=point(shape,frame.origin()[0],frame.origin()[2]);
            for(int segment=0;segment<facets;segment++){
                double[] first=frame.at(2*Math.PI*segment/facets,radius,0),last=frame.at(2*Math.PI*(segment+1)/facets,radius,0);
                triangles.add(new Triangle(centre,point(shape,first[0],first[2]),point(shape,last[0],last[2])));
            }
        }
        return List.copyOf(triangles);
    }
    /** The floor's small clearance avoids coplanar flicker; a full basin cannot draw above its rim. */
    public static double height(double waterLevel){return Math.min(CoolingTower.BASIN_HEIGHT,Math.max(.205,waterLevel));}
    private static Point point(EquipmentShape shape,double x,double z){
        var point=shape.transform().point(new double[]{x,0,z});return new Point(point[0],point[2]);
    }
}
