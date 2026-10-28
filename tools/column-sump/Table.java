package com.wormzjl.icheme.science.equipment;
public class Table { public static void main(String[] a) throws Exception { java.nio.file.Files.write(java.nio.file.Path.of(a[0]), EquipmentLayoutGoldenTest.shapes().stream().map(EquipmentLayoutGoldenTest::line).toList(), java.nio.charset.StandardCharsets.UTF_8); } }
