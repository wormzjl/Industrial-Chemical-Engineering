package com.wormzjl.icheme.science.equipment;

import java.util.*;

/** Pipe and conduit picking follows their hub and face points, with a bounded number of selection boxes. */
public final class LineSelection {
    private LineSelection(){}
    public static int joints(int joints,int facing){
        if(joints==0)joints=3<<(facing&~1);
        else if(Integer.bitCount(joints)==1)joints|=1<<(Integer.numberOfTrailingZeros(joints)^1);
        return joints;
    }
    public static List<double[]> boxes(int joints,int facing,int outside,long offsets){
        joints=joints(joints,facing);
        double h=Math.min(15/32.0,Math.max(PipeSizes.outsideOf(outside)/2,3/32.0));
        var result=new ArrayList<double[]>(7);result.add(new double[]{.5-h,.5-h,.5-h,.5+h,.5+h,.5+h});
        var key=new LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,facing,joints,outside,0,0,false,offsets);
        for(int face=0;face<6;face++)if((joints&(1<<face))!=0){
            var p=LineGeometry.point(key,face);var box=new double[6];int axis=(face/2+1)%3;
            for(int k=0;k<3;k++){
                box[k]=Math.max(0,Math.min(.5,p[k])-(k==axis?0:h));
                box[k+3]=Math.min(1,Math.max(.5,p[k])+(k==axis?0:h));
            }
            result.add(box);
        }
        return List.copyOf(result);
    }
}
