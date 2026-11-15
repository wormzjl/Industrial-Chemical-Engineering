package com.wormzjl.icheme.client.pollution.ftbchunks;

import com.wormzjl.icheme.client.pollution.PollutionMapClient;
import com.wormzjl.icheme.client.pollution.PollutionMapStore;
import dev.ftb.mods.ftbchunks.api.client.event.MapIconEvent;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftblibrary.util.client.ClientUtils;

/**
 * The pollution overlay on FTB Chunks' large map (batch 2026-11-15-pollution-system, WP9; plan section 12). With
 * {@link ColumnIcon} the only classes that name FTB Chunks, FTB Library or Architectury types; reached only when FTB
 * Chunks is loaded ({@link PollutionMapClient}), compiled against FTB Chunks 2101.1.24 and never on a test runtime. After
 * a failure ({@link PollutionMapClient#off}) the store holds nothing, so the listener adds no square and the squares draw
 * nothing.
 *
 * <p>FTB Chunks gathers the large map's icons when the map's region panel builds its widgets ({@code RegionMapPanel.addWidgets}:
 * at opening, at a dimension switch, after a waypoint edit, and on {@link LargeMapScreen#refreshIconsIfOpen}), not each
 * frame. So each polluted column of the store's last answer is one long-lived {@link ColumnIcon} that reads its value from
 * the store when it draws; a new set of columns asks the panel to gather its icons again ({@link #refresh}).</p>
 */
public final class FtbChunksOverlay implements PollutionMapClient.Overlay {
    private FtbChunksOverlay() {
    }

    /** Registers the large map's icon listener; called once, with FTB Chunks loaded. Throws a {@link LinkageError} if its API differs. */
    public static PollutionMapClient.Overlay install() {
        var overlay = new FtbChunksOverlay();
        MapIconEvent.LARGE_MAP.register(overlay::icons);
        return overlay;
    }

    /** One square per polluted column of the store's answer for the map's dimension. Never throws into FTB Chunks. */
    private void icons(MapIconEvent event) {
        try {
            if (!event.getMapType().isLargeMap()) {
                return;
            }
            String dimension = event.getDimension().location().toString();
            if (!PollutionMapStore.shows(dimension)) {
                return;
            }
            for (long column : PollutionMapStore.columns()) {
                event.add(new ColumnIcon(dimension, column));
            }
        } catch (LinkageError | RuntimeException failure) {
            PollutionMapClient.off(failure);
        }
    }

    @Override
    public boolean largeMapOpen() {
        return ClientUtils.getCurrentGuiAs(LargeMapScreen.class) != null;
    }

    @Override
    public void refresh() {
        LargeMapScreen.refreshIconsIfOpen();
    }
}
