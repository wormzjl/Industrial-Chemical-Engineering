package com.wormzjl.icheme.science.equipment;

import java.util.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/** Weatherproof equipment bus socket. Its cable entry can sit off-centre on a block face. */
public final class DataPortGeometry {
    private DataPortGeometry(){}
    public static List<Polygon> polygons(double[] centre,double[] outward){
        var mesh=new Mesh();
        var ref=Math.abs(outward[1])<.8?new double[]{0,1,0}:new double[]{1,0,0};
        var f=new Frame(centre,outward,ref);
        mesh.cylinder(f,.03,-.22,-.04,8,Material.PIPE);
        mesh.cylinder(f,.11,-.04,.015,8,Material.FLANGE);
        mesh.cylinder(f,.075,.015,.05,6,Material.PIPE);
        mesh.annulus(f,.035,.075,.05,8,1,Material.GUARD);
        mesh.cylinder(f,.035,.025,.055,8,Material.OPENING);
        return LineGeometry.overhanging(mesh);
    }
}
