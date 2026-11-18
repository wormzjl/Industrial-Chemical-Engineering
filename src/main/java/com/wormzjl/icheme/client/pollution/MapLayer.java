package com.wormzjl.icheme.client.pollution;

import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.MapColumns;
import com.wormzjl.icheme.science.pollution.Reading;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which pollution layer the map's squares show (batch 2026-11-18-ground-pollution, G3, option A), and the square's
 * tooltip as lines of language keys. Names no Minecraft or map-mod class, so the overlay and the render tool
 * ({@code tools/heat-gui-renders/GuiPrototypes}) draw the same lines.
 */
public enum MapLayer {
    /** The worse of air and ground per channel. */
    BOTH,
    AIR,
    GROUND;

    /** The layer the key moves to: Air, Ground, Both, Air, ... */
    public MapLayer next() {
        return switch (this) {
            case AIR -> GROUND;
            case GROUND -> BOTH;
            case BOTH -> AIR;
        };
    }

    /** The layer's name key. */
    public String key() {
        return "pollution.icheme.map.layer." + name().toLowerCase(Locale.ROOT);
    }

    /** The four bytes this layer's square shows from the air's and the ground's. */
    public int shown(int air, int ground) {
        return switch (this) {
            case AIR -> air;
            case GROUND -> ground;
            case BOTH -> MapColumns.worse(air, ground);
        };
    }

    /** A translated argument inside a line. */
    public record Key(String key) {
    }

    /** One tooltip line: a key, its arguments (strings or {@link Key}s), and its word (null for a title or a header). */
    public record Line(String key, List<Object> args, Reading.Word word) {
    }

    /**
     * The tooltip of chunk column {@code column}: a title naming the chunk and the layer shown, then the "Air" and the
     * "Ground" layer, each with its four channel lines, always both.
     */
    public List<Line> tooltip(long column, int air, int ground) {
        var out = new ArrayList<Line>();
        out.add(new Line("pollution.icheme.map.title", List.of(Integer.toString(MapColumns.x(column)), Integer.toString(MapColumns.z(column)), new Key(key())), null));
        layer(out, "pollution.icheme.map.air", air);
        layer(out, "pollution.icheme.map.ground", ground);
        return out;
    }

    private static void layer(List<Line> out, String header, int packed) {
        out.add(new Line(header, List.of(), null));
        for (var channel : Channel.values()) {
            int value = MapColumns.value(packed, channel);
            double index = MapColumns.dequantise(value);
            var word = Reading.word(channel, index);
            String number = Reading.number(index) + (value >= MapColumns.SATURATED ? "+" : "");
            out.add(new Line("pollution.icheme.meter.line", List.of(new Key(nameKey(channel)), number, new Key(wordKey(word))), word));
        }
    }

    /** The channel's name key (as the meter's). */
    public static String nameKey(Channel channel) {
        return "pollution.icheme.name." + channel.name().toLowerCase(Locale.ROOT);
    }

    /** The word's key (as the meter's). */
    public static String wordKey(Reading.Word word) {
        return "pollution.icheme.word." + word.name().toLowerCase(Locale.ROOT);
    }
}
