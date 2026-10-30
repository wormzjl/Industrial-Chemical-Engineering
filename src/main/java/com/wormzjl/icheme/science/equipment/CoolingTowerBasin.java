package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;
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
        if(tower.draft()==com.wormzjl.icheme.science.fluid.network.CoolingTower.Draft.INDUCED){
            var a=point(shape,0,0);var b=point(shape,0,tower.width()*tower.rows());
            var c=point(shape,tower.width()*tower.columns(),tower.width()*tower.rows());
            var d=point(shape,tower.width()*tower.columns(),0);
            triangles.add(new Triangle(a,b,c));triangles.add(new Triangle(a,c,d));
        }else {
            double radius=tower.width()/2-.22,axis=tower.width()/2+.5;
            var centre=point(shape,axis,axis);
            for(int segment=0;segment<64;segment++){
                double first=2*Math.PI*segment/64,last=2*Math.PI*(segment+1)/64;
                triangles.add(new Triangle(centre,point(shape,axis+radius*DeterministicMath.cos(last),axis+radius*DeterministicMath.sin(last)),
                    point(shape,axis+radius*DeterministicMath.cos(first),axis+radius*DeterministicMath.sin(first))));
            }
        }
        return List.copyOf(triangles);
    }
    private static Point point(EquipmentShape shape,double x,double z){
        var point=shape.transform().point(new double[]{x,0,z});return new Point(point[0],point[2]);
    }
}
