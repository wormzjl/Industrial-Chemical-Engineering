package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The alarm beacon's geometry (batch 2026-11-15-fluid-interfaces, W9): its block models are its boxes, under 150 faces;
 * each mounting turns it as its blockstate does (a rotation, its top toward the facing); its socket faces north on a
 * floor, south from a ceiling and down on a wall.
 */
class AlarmGeometryTest {
    private static JsonObject json(String path) throws Exception {
        try (var in = AlarmGeometryTest.class.getResourceAsStream("/assets/icheme/" + path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void bothModelsAreTheBoxesUnderAHundredAndFiftyFaces() throws Exception {
        var parts = AlarmGeometry.parts();
        for (var name : new String[] {"alarm_beacon", "alarm_beacon_lit"}) {
            var elements = json("models/block/" + name + ".json").getAsJsonArray("elements");
            assertEquals(parts.size(), elements.size(), name);
            int faces = 0;
            for (int i = 0; i < parts.size(); i++) {
                var e = elements.get(i).getAsJsonObject();
                var p = parts.get(i);
                assertEquals(p.name(), e.get("name").getAsString());
                for (int k = 0; k < 3; k++) {
                    assertEquals(p.box()[k], e.getAsJsonArray("from").get(k).getAsDouble(), 1e-9, p.name());
                    assertEquals(p.box()[k + 3], e.getAsJsonArray("to").get(k).getAsDouble(), 1e-9, p.name());
                }
                faces += e.getAsJsonObject("faces").size();
                // Only the lit lens is drawn full-bright.
                assertEquals(name.endsWith("_lit") && p.finish() == AlarmGeometry.Finish.LENS, e.has("neoforge_data"), p.name());
            }
            assertTrue(faces < 150, name + ": " + faces + " faces");
        }
        assertEquals("icheme:block/alarm_beacon", json("models/item/alarm_beacon.json").get("parent").getAsString());
    }

    /** The determinant of the turn's linear part: +1 for a rotation. */
    private static double determinant(int facing) {
        double[] o = AlarmGeometry.point(8, 8, 8, facing), x = AlarmGeometry.point(9, 8, 8, facing), y = AlarmGeometry.point(8, 9, 8, facing),
                z = AlarmGeometry.point(8, 8, 9, facing);
        double[][] m = new double[3][3];
        for (int k = 0; k < 3; k++) {
            m[k][0] = x[k] - o[k];
            m[k][1] = y[k] - o[k];
            m[k][2] = z[k] - o[k];
        }
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    @Test
    void eachMountingIsARotationWithItsTopTowardTheFacing() throws Exception {
        int[][] normals = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        String[] names = {"down", "up", "north", "south", "west", "east"};
        var variants = json("blockstates/alarm_beacon.json").getAsJsonObject("variants");
        for (int f = 0; f < 6; f++) {
            assertEquals(1, determinant(f), 1e-9, names[f]);
            double[] o = AlarmGeometry.point(8, 8, 8, f), top = AlarmGeometry.point(8, 9, 8, f);
            for (int k = 0; k < 3; k++) assertEquals(normals[f][k], top[k] - o[k], 1e-9, names[f]);
            for (var b : AlarmGeometry.parts()) {
                var t = AlarmGeometry.turned(b.box(), f);
                for (double v : t) assertTrue(v >= 0 && v <= 16, names[f] + ": " + b.name());
            }
            for (var state : new String[] {"clear", "active", "acknowledged"}) {
                var v = variants.getAsJsonObject("facing=" + names[f] + ",state=" + state);
                assertNotNull(v, names[f] + " " + state);
                int[] r = AlarmGeometry.rotation(f);
                assertEquals(r[0], v.has("x") ? v.get("x").getAsInt() : 0, names[f]);
                assertEquals(r[1], v.has("y") ? v.get("y").getAsInt() : 0, names[f]);
                assertEquals(state.equals("clear") ? "icheme:block/alarm_beacon" : "icheme:block/alarm_beacon_lit", v.get("model").getAsString());
            }
        }
        // A wall mounting turns the standing beacon 90 degrees about X: its X axis stays (the blockstate's x = 90).
        assertArrayEquals(new double[] {16, 8, 8}, AlarmGeometry.point(16, 8, 8, 2), 1e-9);
    }

    @Test
    void theSocketFacesNorthOnAFloorSouthFromACeilingAndDownOnAWall() {
        assertEquals(2, AlarmGeometry.socketOutward(1));
        assertEquals(3, AlarmGeometry.socketOutward(0));
        for (int f = 2; f < 6; f++) assertEquals(0, AlarmGeometry.socketOutward(f), "wall " + f);
        // On the gland's face, inside the cell, near the mounting face.
        for (int f = 0; f < 6; f++) for (double v : AlarmGeometry.socket(f)) assertTrue(v >= 0 && v <= 16);
        assertArrayEquals(new double[] {8, 1.5, 1.5}, AlarmGeometry.socket(1), 1e-9);
    }
}
