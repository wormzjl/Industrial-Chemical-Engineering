package com.wormzjl.icheme.client.pollution.ftbchunks;

import com.wormzjl.icheme.client.pollution.PollutionMapClient;
import com.wormzjl.icheme.client.pollution.PollutionMapStore;
import com.wormzjl.icheme.runtime.pollution.PollutionMeters;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.MapColumns;
import com.wormzjl.icheme.science.pollution.Reading;
import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * One chunk column's square on FTB Chunks' large map (WP9): a translucent fill over the whole chunk, at every zoom
 * ({@link #isZoomDependant}; {@link #getIconScale} 4 is one chunk, as the region panel sizes an icon at
 * {@code regionTileSize / 128 x scale} pixels for 512 blocks), centred on the chunk's centre, below every other icon
 * ({@link #getPriority}). Its colour and opacity come from the store at draw time ({@link MapColumns#argb}), so the square
 * follows each answer; it draws nothing, and has no tooltip, while the store has nothing drawable for it.
 */
final class ColumnIcon implements MapIcon {
    /** Below FTB Chunks' own icons (entities 0 to 150, waypoints 1000), so they draw on top and win the tooltip. */
    static final int PRIORITY = -1000;
    /** One chunk: the panel draws an icon {@code regionTileSize / 128 x scale} pixels wide, a region being 32 chunks. */
    static final double SCALE = 4.0;

    private static final Channel[] CHANNELS = Channel.values();

    private final String dimension;
    private final long column;
    private final Vec3 position;

    ColumnIcon(String dimension, long column) {
        this.dimension = dimension;
        this.column = column;
        this.position = new Vec3(MapColumns.x(column) * 16 + 8, 64, MapColumns.z(column) * 16 + 8);
    }

    @Override
    public Vec3 getPos(float partialTick) {
        return position;
    }

    @Override
    public boolean isVisible(MapType mapType, double distanceToPlayer, boolean outsideVisibleArea) {
        return mapType.isLargeMap();
    }

    @Override
    public double getIconScale(MapType mapType) {
        return SCALE;
    }

    @Override
    public boolean isZoomDependant(MapType mapType) {
        return true;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void addTooltip(TooltipList list) {
        try {
            if (MapColumns.argb(PollutionMapStore.shown(dimension, column)) == 0) {
                return;
            }
            // Both layers whenever they hold something (batch 2026-11-18-ground-pollution, G3): the air's worst cube, the ground.
            lines(list, "pollution.icheme.map.header", PollutionMapStore.value(dimension, column));
            lines(list, "pollution.icheme.map.ground", PollutionMapStore.ground(dimension, column));
        } catch (LinkageError | RuntimeException failure) {
            PollutionMapClient.off(failure);
        }
    }

    /** A layer's header and four lines; nothing when the layer has nothing drawable at this column. */
    private void lines(TooltipList list, String header, int packed) {
        if (MapColumns.argb(packed) == 0) {
            return;
        }
        list.add(Component.translatable(header, Integer.toString(MapColumns.x(column)), Integer.toString(MapColumns.z(column)))
                .withStyle(ChatFormatting.AQUA));
        for (var channel : CHANNELS) {
            int value = MapColumns.value(packed, channel);
            double index = MapColumns.dequantise(value);
            var word = Reading.word(channel, index);
            String number = Reading.number(index) + (value >= MapColumns.SATURATED ? "+" : "");
            list.add(Component.translatable("pollution.icheme.meter.line", Component.translatable(PollutionMeters.nameKey(channel)),
                    number, Component.translatable(PollutionMeters.wordKey(word))).withStyle(PollutionMeters.colour(word)));
        }
    }

    @Override
    public boolean onMousePressed(BaseScreen screen, MouseButton button) {
        return false;
    }

    @Override
    public boolean onKeyPressed(BaseScreen screen, Key key) {
        return false;
    }

    @Override
    public void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h, boolean outsideVisibleArea, int iconAlpha) {
        try {
            int argb = MapColumns.argb(PollutionMapStore.shown(dimension, column));
            if (argb != 0 && w > 0 && h > 0) {
                graphics.fill(x, y, x + w, y + h, argb);
            }
        } catch (LinkageError | RuntimeException failure) {
            PollutionMapClient.off(failure);
        }
    }
}
