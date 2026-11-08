package com.wormzjl.icheme.client;

import com.wormzjl.icheme.science.equipment.Paint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Client preferences keyed by account, independent of the constructor block and of the world. */
public final class EquipmentPaints {
    private record Entry(UUID player, Paint paint) {}

    private EquipmentPaints() {}

    private static Entry parse(String text) {
        var fields = text.split(":", -1);
        if (fields.length != 3)
            throw new IllegalArgumentException(
                    "A paint preference holds an account and two colours");
        var player = UUID.fromString(fields[0]);
        if (!player.toString().equalsIgnoreCase(fields[0]))
            throw new IllegalArgumentException("Invalid account UUID");
        return new Entry(player, new Paint(Paint.parseHex(fields[1]), Paint.parseHex(fields[2])));
    }

    public static boolean valid(Object value) {
        if (!(value instanceof String text)) return false;
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static Paint find(List<? extends String> entries, UUID player, Paint fallback) {
        for (var text : entries) {
            if (!valid(text)) continue;
            var entry = parse(text);
            if (entry.player.equals(player)) return entry.paint;
        }
        return fallback;
    }

    public static List<String> remember(List<? extends String> entries, UUID player, Paint paint) {
        var next = new ArrayList<String>();
        for (var text : entries) {
            // Retain unrelated preferences, including ones a later version might understand.
            if (!valid(text) || !parse(text).player.equals(player)) next.add(text);
        }
        next.add(player + ":" + Paint.hex(paint.body()) + ":" + Paint.hex(paint.structure()));
        return List.copyOf(next);
    }
}
