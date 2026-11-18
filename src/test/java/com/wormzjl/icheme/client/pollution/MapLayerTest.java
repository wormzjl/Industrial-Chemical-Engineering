package com.wormzjl.icheme.client.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.MapColumns;
import org.junit.jupiter.api.Test;

/** The pollution map's layer key and tooltip (batch 2026-11-18-ground-pollution, G3, option A). */
class MapLayerTest {
    private static int packed(Channel channel, int value) {
        return value << 8 * channel.ordinal();
    }

    @Test void theKeyCyclesAirGroundBoth() {
        assertEquals(MapLayer.GROUND, MapLayer.AIR.next());
        assertEquals(MapLayer.BOTH, MapLayer.GROUND.next());
        assertEquals(MapLayer.AIR, MapLayer.BOTH.next());
    }

    @Test void eachLayerDrawsItsColourAndBothTheHigherIndex() {
        int air = packed(Channel.TOXIC, 30) | packed(Channel.CORROSIVE, 2);
        assertEquals(MapColumns.argb(air), MapLayer.AIR.argb(air, 40));
        assertEquals(MapColumns.GROUND_RGB, MapLayer.GROUND.argb(air, 40) & 0xFFFFFF, "the ground has its own brown");
        assertEquals(MapColumns.groundArgb(40), MapLayer.BOTH.argb(air, 40), "ground 0.80 over the air's worst 0.60");
        assertEquals(MapColumns.argb(air), MapLayer.BOTH.argb(air, 30), "a tie shows the air");
        assertEquals(0, MapLayer.GROUND.argb(air, 0), "no ground, no square");
        for (int rgb : new int[] {MapColumns.TOXIC_RGB, MapColumns.COMBUSTIBLE_RGB, MapColumns.CORROSIVE_RGB, MapColumns.PARTICULATE_RGB}) assertNotEquals(rgb, MapColumns.GROUND_RGB);
    }

    @Test void theTooltipNamesTheLayerShownAndListsBothLayers() {
        long column = MapColumns.column(-2, 9);
        var lines = MapLayer.GROUND.tooltip(column, packed(Channel.TOXIC, 30), 0);
        assertEquals("pollution.icheme.map.title", lines.get(0).key());
        assertEquals(java.util.List.of("-2", "9", new MapLayer.Key("pollution.icheme.map.layer.ground")), lines.get(0).args());
        assertEquals("pollution.icheme.map.air", lines.get(1).key());
        assertEquals("0.60", lines.get(2).args().get(1), "the air's toxic byte 30 is index 0.60");
        assertEquals(7, lines.size(), "a title, the air header and four lines, one ground line");
        assertEquals("pollution.icheme.map.ground", lines.get(6).key(), "the ground is listed though it is clean");
        assertEquals("0.00", lines.get(6).args().get(0));
        assertEquals(com.wormzjl.icheme.science.pollution.Reading.Word.SAFE, lines.get(6).word());
    }
}
