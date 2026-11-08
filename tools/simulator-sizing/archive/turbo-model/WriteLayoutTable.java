package com.wormzjl.icheme.science.equipment;
import java.nio.file.*;
public class WriteLayoutTable {
    public static void main(String[] args) throws Exception {
        var lines = EquipmentLayoutGoldenTest.shapes().stream().map(EquipmentLayoutGoldenTest::line).toList();
        Files.write(Path.of("tmp/turbo-model/layouts.txt"), lines);
        System.out.println("Wrote " + lines.size() + " layout records");
        for (var line : lines) if (line.contains("kind=CENTRIFUGAL_COMPRESSOR,")) System.out.println(line);
    }
}
