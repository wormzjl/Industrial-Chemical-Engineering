package com.wormzjl.icheme.science.equipment;

import java.util.List;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Larger orange-identified gland for the metallic power enclosure; the data socket remains yellow. */
public final class PowerPortGeometry {
    private PowerPortGeometry(){}
    public static List<Polygon> polygons(double[] centre,double[] outward,boolean box){
        return DataPortGeometry.polygons(new double[]{0,0,0},outward,box,CableMedium.POWER.outside()/1.3).stream().map(p->{
            var positions=p.positions().clone();for(int i=0;i<positions.length;i++)positions[i]=positions[i]*1.3+centre[i%3];
            return new Polygon(positions,p.uvs(),p.normals(),p.material()==Material.GUARD?Material.ACTUATOR:p.material());
        }).toList();
    }
}
