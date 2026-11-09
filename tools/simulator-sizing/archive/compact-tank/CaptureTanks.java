package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
import java.util.*;
public class CaptureTanks {
    public static void main(String[] args)throws Exception {
        var lines=new ArrayList<String>();
        for(int d:new int[]{4,5,6})for(int h:new int[]{4,5,6,8})for(var heading:EquipmentShape.Heading.values()) {
            var shape=EquipmentShape.storage(EquipmentShape.Kind.TANK,d,h,heading,0);
            var out=new StringBuilder(d+":"+h+":"+heading+" box "+Arrays.toString(ProcessUnit.of(shape).box())+" pipes");
            for(var port:ShapeRasterizer.ports(shape))out.append(" ").append(port.cell().x()+port.dx()).append(",").append(port.cell().y()+port.dy()).append(",").append(port.cell().z()+port.dz());
            lines.add(out.toString());
        }
        Files.write(Path.of(args[0]),lines);
        System.out.println("Recorded "+lines.size()+" tank layouts");
    }
}
