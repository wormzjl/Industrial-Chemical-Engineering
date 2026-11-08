package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
public class WriteLayouts {
    public static void main(String[] args) throws Exception {
        var lines = EquipmentLayoutGoldenTest.shapes().stream().map(EquipmentLayoutGoldenTest::line).toList();
        Files.write(Path.of("tmp/turbine-inlet/layouts.txt"), lines);
        var old = Files.readAllLines(Path.of("src/test/resources/compat/layouts/layouts.txt"));
        for (int i = 0; i < lines.size(); i++) if (!old.get(i).equals(lines.get(i))) {
            System.out.println("BEFORE " + old.get(i));
            System.out.println("AFTER  " + lines.get(i));
        }
        System.out.println("Recorded " + lines.size() + " layouts");
    }
}
