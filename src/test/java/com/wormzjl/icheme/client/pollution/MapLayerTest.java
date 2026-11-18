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

    @Test void eachLayerShowsItsBytesAndBothTheWorse() {
        int air = packed(Channel.TOXIC, 30) | packed(Channel.CORROSIVE, 2);
        int ground = packed(Channel.TOXIC, 5) | packed(Channel.CORROSIVE, 40);
        assertEquals(air, MapLayer.AIR.shown(air, ground));
        assertEquals(ground, MapLayer.GROUND.shown(air, ground));
        int both = MapLayer.BOTH.shown(air, ground);
        assertEquals(30, MapColumns.value(both, Channel.TOXIC));
        assertEquals(40, MapColumns.value(both, Channel.CORROSIVE));
    }

    @Test void theTooltipNamesTheLayerShownAndListsBothLayers() {
        long column = MapColumns.column(-2, 9);
        var lines = MapLayer.GROUND.tooltip(column, packed(Channel.TOXIC, 30), 0);
        assertEquals("pollution.icheme.map.title", lines.get(0).key());
        assertEquals(java.util.List.of("-2", "9", new MapLayer.Key("pollution.icheme.map.layer.ground")), lines.get(0).args());
        assertEquals("pollution.icheme.map.air", lines.get(1).key());
        assertEquals("0.60", lines.get(2).args().get(1), "the air's toxic byte 30 is index 0.60");
        assertEquals("pollution.icheme.map.ground", lines.get(6).key(), "the ground is listed though it is clean");
        assertNull(lines.get(6).word());
        assertNotNull(lines.get(7).word());
    }
}
