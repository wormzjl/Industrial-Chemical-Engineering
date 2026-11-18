package com.wormzjl.icheme.client.pollution.ftbchunks;

import com.wormzjl.icheme.client.pollution.MapLayer;
import com.wormzjl.icheme.client.pollution.PollutionMapClient;
import com.wormzjl.icheme.client.pollution.PollutionMapStore;
import com.wormzjl.icheme.runtime.pollution.PollutionMeters;
import com.wormzjl.icheme.science.pollution.MapColumns;
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
            // The chunk and the layer shown, then always both layers (batch 2026-11-18-ground-pollution, G3, option A).
            var layer = PollutionMapStore.layer();
            for (var line : layer.tooltip(column, PollutionMapStore.value(dimension, column), PollutionMapStore.ground(dimension, column))) list.add(component(line));
        } catch (LinkageError | RuntimeException failure) {
            PollutionMapClient.off(failure);
        }
    }

    /** One line of {@link MapLayer#tooltip} as text: the title plain, a layer's header aqua, a channel in its word's colour. */
    private static Component component(MapLayer.Line line) {
        var args = new Object[line.args().size()];
        for (int i = 0; i < args.length; i++) args[i] = line.args().get(i) instanceof MapLayer.Key key ? Component.translatable(key.key()) : line.args().get(i);
        var text = Component.translatable(line.key(), args);
        if (line.word() != null) return text.withStyle(PollutionMeters.colour(line.word()));
        return line.args().isEmpty() ? text.withStyle(ChatFormatting.AQUA) : text;
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
