package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
public class WriteLayoutTable {
    public static void main(String[] args) throws Exception {
        var lines = EquipmentLayoutGoldenTest.shapes().stream().map(EquipmentLayoutGoldenTest::line).toList();
        Files.write(Path.of("tmp/compressor-shaft/layouts.txt"), lines);
        System.out.println("Wrote " + lines.size() + " layout records");
    }
}
