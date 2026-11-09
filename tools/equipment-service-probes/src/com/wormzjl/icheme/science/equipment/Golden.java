package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;import java.util.*;
public class Golden {
  public static void main(String[] a) throws Exception {
    var lines = EquipmentLayoutGoldenTest.shapes().stream().map(EquipmentLayoutGoldenTest::line).toList();
    var rec = Files.readAllLines(Path.of(a[0])).stream().filter(l->!l.isBlank()).toList();
    var before=new LinkedHashSet<>(rec); var after=new LinkedHashSet<>(lines); int n=0;
    for (var l: rec) if(!after.contains(l)){System.out.println("- "+l);n++;}
    for (var l: lines) if(!before.contains(l)){System.out.println("+ "+l);n++;}
    System.out.println("changes "+n+" of "+lines.size());
    if (a.length>1) Files.write(Path.of(a[1]), lines);
  }
}
