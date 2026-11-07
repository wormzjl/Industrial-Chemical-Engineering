package com.wormzjl.icheme.science.equipment;

import java.util.List;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Larger orange-identified gland for the metallic power enclosure; the data socket remains yellow. */
public final class PowerPortGeometry {
    private PowerPortGeometry(){}
    public static List<Polygon> polygons(double[] centre,double[] outward,boolean box){
        return DataPortGeometry.polygons(centre,outward,box,CableMedium.POWER.outside()).stream()
                .map(p->new Polygon(p.positions(),p.uvs(),p.normals(),p.material()==Material.GUARD?Material.ACTUATOR:p.material())).toList();
    }
}
