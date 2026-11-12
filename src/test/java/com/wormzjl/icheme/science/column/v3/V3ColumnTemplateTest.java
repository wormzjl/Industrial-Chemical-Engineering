package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A column template holds no feed; its flows and duties follow the feed it meets (process simulator plan, section 3.7, D4). */
class V3ColumnTemplateTest {
    private static final V3ColumnTemplate TOWER = new V3ColumnTemplate(2000, 42, 38, 332.15, 5, 4.17, 2.5, 8,
            List.of(new V3ColumnTemplate.Draw(11, 500)), List.of(new V3ColumnTemplate.Steam(42, 1200, 533.15)),
            List.of(new V3ColumnTemplate.Pumparound(11, 9, 12.84, V3PumparoundSpec.Split.UNIFORM)));

    private static JsonObject file(String path) throws IOException {
        try (var stream = V3ColumnTemplateTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static V3ColumnTemplate noFeed(V3ColumnTemplate t) {
        return new V3ColumnTemplate(0, t.trays(), t.feedTray(), t.topTemperature(), t.reboilerDuty(), t.refluxRatio(), t.topPressure(), t.diameter(), t.draws(), t.steam(), t.pumparounds());
    }

    @Test
    void theFlowsAndDutiesGoWithTheFeedAndTheTowerStands() {
        var half = TOWER.scaledTo(1000);
        assertEquals(1000, half.designFeed());
        assertEquals(250, half.draws().getFirst().flow(), 1e-9);
        assertEquals(600, half.steam().getFirst().flow(), 1e-9);
        assertEquals(6.42, half.pumparounds().getFirst().cooling(), 1e-9);
        assertEquals(2.5, half.reboilerDuty(), 1e-9);
        assertEquals(TOWER.trays(), half.trays());
        assertEquals(TOWER.feedTray(), half.feedTray());
        assertEquals(TOWER.topTemperature(), half.topTemperature());
        assertEquals(TOWER.topPressure(), half.topPressure());
        assertEquals(TOWER.refluxRatio(), half.refluxRatio());
        assertEquals(TOWER.diameter(), half.diameter());
        assertEquals(533.15, half.steam().getFirst().temperature());
        assertEquals(11, half.draws().getFirst().tray());
        assertEquals(V3PumparoundSpec.Split.UNIFORM, half.pumparounds().getFirst().split());
        assertEquals(TOWER, half.scaledTo(2000), "back to its design feed");
    }

    @Test
    void aTemplateWithNoDesignFeedAndNoFeedAtAllApplyAsWritten() {
        assertSame(TOWER, TOWER.scaledTo(0), "no feed to scale to");
        assertSame(TOWER, TOWER.scaledTo(Double.NaN), "a feed that is no number");
        assertSame(TOWER, TOWER.scaledTo(TOWER.designFeed()), "its own feed");
        var written = noFeed(TOWER);
        assertSame(written, written.scaledTo(1000));
    }

    @Test
    void aFileKeepsItsColumn() {
        var o = new JsonObject();
        o.addProperty("schema_version", 3);
        TOWER.write(o);
        assertEquals(TOWER, V3ColumnTemplate.read(o));
        assertFalse(o.has("feed_temperature_kelvin"));
        assertFalse(o.has("feed_flow_kmol_h"));
        var none = new JsonObject();
        none.addProperty("schema_version", 3);
        noFeed(TOWER).write(none);
        assertFalse(none.has("design_feed_kmol_h"), "the field is optional");
        assertEquals(0, V3ColumnTemplate.read(none).designFeed());
    }

    @Test
    void aSchemaTwoFilesFeedFlowIsItsDesignFeed() throws Exception {
        var old = file("compat/presets/column-schema-2.json");
        assertEquals(2, old.get("schema_version").getAsInt());
        assertTrue(old.has("feed_temperature_kelvin"), "the image holds the feed as the older builds wrote it");
        var template = V3ColumnTemplate.read(old);
        assertEquals(old.get("feed_flow_kmol_h").getAsDouble(), template.designFeed());
        assertEquals(42, template.trays());
        assertEquals(3, template.draws().size());
        assertEquals(1, template.steam().size());
        assertEquals(3, template.pumparounds().size());
    }

    @Test
    void everyBundledTowerHoldsNoFeedAndSaysItsDesignFeed() throws Exception {
        var index = JsonParser.parseString(new String(V3ColumnTemplateTest.class.getClassLoader().getResourceAsStream("assets/icheme/columnpreset/index.json").readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        assertEquals(6, index.size());
        for (var name : index) {
            var o = file("assets/icheme/columnpreset/" + name.getAsString());
            assertEquals(3, o.get("schema_version").getAsInt(), name.getAsString());
            assertFalse(o.has("feed_flow_kmol_h") || o.has("feed_temperature_kelvin") || o.has("package"), name.getAsString());
            assertTrue(V3ColumnTemplate.read(o).designFeed() > 0, name.getAsString());
        }
    }

    @Test
    void aColumnNoTowerCouldBeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnTemplate(-1, 42, 38, 332, 0, 4, 2.5, 8, List.of(), List.of(), List.of()), "a feed below zero");
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnTemplate(2000, 1, 2, 332, 0, 4, 2.5, 8, List.of(), List.of(), List.of()), "one tray");
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnTemplate(2000, 42, 43, 332, 0, 4, 2.5, 8, List.of(), List.of(), List.of()), "a feed below the column");
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnTemplate(2000, 42, 38, 332, 0, 4, 2.5, 8, List.of(new V3ColumnTemplate.Draw(42, 1)), List.of(), List.of()), "a draw off the reboiler");
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnTemplate(2000, 42, 38, 332, 0, 4, 2.5, 8, List.of(), List.of(new V3ColumnTemplate.Steam(1, 1, 500)), List.of()), "steam into the condenser");
        assertThrows(IllegalArgumentException.class, () -> new V3ColumnTemplate(2000, 42, 38, 332, 0, 4, 2.5, 8, List.of(), List.of(),
                List.of(new V3ColumnTemplate.Pumparound(9, 11, 1, V3PumparoundSpec.Split.UNIFORM))), "a pumparound drawn above its return");
    }
}
