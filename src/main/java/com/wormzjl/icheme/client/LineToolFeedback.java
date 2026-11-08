package com.wormzjl.icheme.client;

import com.wormzjl.icheme.science.equipment.PipeRoute;
import com.wormzjl.icheme.world.level.block.PipeAnchor;
import com.wormzjl.icheme.world.level.block.PipeLaying;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Shared route measurements and player feedback; the adapter supplies its material and size
 * wording.
 */
final class LineToolFeedback {
    private LineToolFeedback() {}

    static Component route(
            PipeAnchor start,
            PipeAnchor end,
            PipeRoute.Result route,
            double diameter,
            boolean creative,
            Component material) {
        int count = route.cells().size(),
                elbows = PipeRoute.elbows(route.cells(), start.end(), end.end());
        var size = String.format(Locale.ROOT, "%.3f", diameter);
        var items = Component.literal(creative ? "—" : Integer.toString(count));
        if (material == null)
            return PipeLaying.message(
                    "hud.route",
                    PipeLaying.describe(start),
                    PipeLaying.describe(end),
                    count,
                    elbows,
                    size,
                    items);
        return Component.translatable(
                "message.icheme.conduit.route",
                PipeLaying.describe(start),
                PipeLaying.describe(end),
                count,
                elbows,
                size,
                material,
                items);
    }
}
