package com.wormzjl.icheme.science.equipment;

import java.nio.file.*;
import java.util.*;

public class TankLayoutRows {
    public static void main(String[] args) throws Exception {
        var table=Path.of("src/test/resources/compat/layouts/layouts.txt");
        var rows=Files.readAllLines(table);
        var before=new ArrayList<String>();
        var after=new ArrayList<String>();
        for(var shape:EquipmentLayoutGoldenTest.shapes()) {
            if(shape.kind()!=EquipmentShape.Kind.TANK) continue;
            String next=EquipmentLayoutGoldenTest.line(shape);
            String prefix=shape.toString().replace(", exchangerType=STANDARD]", "]")+" |";
            int index=-1;
            for(int k=0;k<rows.size();k++) if(rows.get(k).startsWith(prefix)){index=k;break;}
            if(index<0)throw new IllegalStateException("Missing tank layout row: "+prefix);
            before.add(rows.get(index));after.add(next);rows.set(index,next);
        }
        Files.write(Path.of(args[0],"tank-layouts-before.txt"),before);
        Files.write(Path.of(args[0],"tank-layouts-after.txt"),after);
        Files.write(table,rows);
        System.out.println("Updated "+after.size()+" tank layout rows");
    }
}
