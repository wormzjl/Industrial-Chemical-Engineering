package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

class DcsGeometryTest {
    @Test
    void theLocalControllerUsesItsAuthoredCabinetBounds() throws Exception {
        check(model("models/block/local_controller.json"), DcsGeometry.localParts());
        for (var part : DcsGeometry.localParts())
            for (int turn = 0; turn < 4; turn++)
                assertArrayEquals(
                        DcsGeometry.bounds(part, turn), DcsGeometry.bounds(part, turn + 4));
    }

    private static JsonObject model(String path) throws Exception {
        try (var in = DcsGeometryTest.class.getResourceAsStream("/assets/icheme/" + path)) {
            assertNotNull(in, path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    @Test
    void bothWorldHalvesAndTheInventoryUseTheCollisionGeometry() throws Exception {
        for (boolean upper : new boolean[] {false, true})
            check(
                    model("models/block/" + (upper ? "dcs_upper" : "dcs") + ".json"),
                    DcsGeometry.parts(upper));
        check(model("models/block/dcs_complete.json"), DcsGeometry.parts());
        assertEquals(
                "icheme:block/dcs_complete",
                model("models/item/dcs.json").get("parent").getAsString());
    }

    private static void check(JsonObject model, List<DcsGeometry.Part> parts) {
        var elements = model.getAsJsonArray("elements");
        assertEquals(parts.size(), elements.size());
        for (int i = 0; i < parts.size(); i++) {
            var p = parts.get(i);
            var element = elements.get(i).getAsJsonObject();
            assertEquals(p.name(), element.get("name").getAsString());
            var actual = new double[6];
            for (int k = 0; k < 3; k++) {
                actual[k] = element.getAsJsonArray("from").get(k).getAsDouble();
                actual[k + 3] = element.getAsJsonArray("to").get(k).getAsDouble();
            }
            assertArrayEquals(DcsGeometry.bounds(p, 0), actual, 1e-9, p.name());
            for (var face : element.getAsJsonObject("faces").entrySet()) {
                var key =
                        face.getValue().getAsJsonObject().get("texture").getAsString().substring(1);
                var texture =
                        model.getAsJsonObject("textures")
                                        .get(key)
                                        .getAsString()
                                        .replace(":", "/textures/")
                                + ".png";
                assertNotNull(DcsGeometryTest.class.getResource("/assets/" + texture), texture);
            }
        }
    }

    @Test
    void geometryFitsTwoRealCellsAtEveryHeading() {
        assertEquals(
                32,
                DcsGeometry.parts().stream().mapToDouble(DcsGeometry.Part::Y).max().orElseThrow());
        assertEquals(
                DcsGeometry.parts().size(),
                DcsGeometry.parts(false).size() + DcsGeometry.parts(true).size());
        for (boolean upper : new boolean[] {false, true})
            for (int turn = 0; turn < 4; turn++)
                for (var part : DcsGeometry.parts(upper)) {
                    var b = DcsGeometry.bounds(part, turn);
                    for (int k = 0; k < 3; k++)
                        assertTrue(b[k] >= 0 && b[k] < b[k + 3] && b[k + 3] <= 16, part.name());
                    assertArrayEquals(
                            DcsGeometry.bounds(part, turn), DcsGeometry.bounds(part, turn + 4));
                }
        assertTrue(
                DcsGeometry.parts().stream()
                        .filter(p -> p.front() == DcsGeometry.Finish.FRONT)
                        .allMatch(p -> p.y() > 20 && p.Y() < 30),
                "The process display belongs at standing eye height");
    }

    @Test
    void blockstatesDrawBothPartsInEveryDirection() throws Exception {
        var variants = model("blockstates/dcs.json").getAsJsonObject("variants");
        assertEquals(8, variants.size());
        int turn = 0;
        for (var facing : List.of("north", "east", "south", "west")) {
            for (var half : List.of("lower", "upper")) {
                var variant = variants.getAsJsonObject("facing=" + facing + ",half=" + half);
                assertEquals(90 * turn, variant.get("y").getAsInt());
                assertEquals(
                        "icheme:block/" + (half.equals("upper") ? "dcs_upper" : "dcs"),
                        variant.get("model").getAsString());
            }
            turn++;
        }
    }
}
