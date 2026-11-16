package com.wormzjl.icheme.client.pollution;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.client.fx.FxCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The pollution map overlay on a client (batch 2026-11-15-pollution-system, WP9): finds the map integration once, then
 * each tick asks the server while the map is open ({@link PollutionMapStore#watch}: one screen check, no allocation).
 *
 * <p>The integration is the only code that names the map mod's types ({@code client/pollution/ftbchunks/}). It is reached
 * only when that mod is loaded, behind a guard: a {@link LinkageError} or {@link RuntimeException} from it turns the overlay
 * off for the session with one warning, and the client carries on.</p>
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class PollutionMapClient {
    /** FTB Chunks' mod id. */
    public static final String FTB_CHUNKS = "ftbchunks";

    /** What the overlay needs of a map mod. */
    public interface Overlay {
        /** Whether the map's full-screen view is open now. Called once a tick; must not allocate. */
        boolean largeMapOpen();

        /** The map gathers its squares again (a new set of columns); a no-op when it is closed. */
        void refresh();
    }

    private static Overlay overlay;
    private static boolean searched;
    private static ClientLevel level;

    private PollutionMapClient() {
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!searched) {
            searched = true;
            overlay = find();
        }
        var minecraft = Minecraft.getInstance();
        if (minecraft.level != level) {
            level = minecraft.level;
            PollutionMapStore.levelChanged();
        }
        if (overlay == null || level == null) {
            return;
        }
        boolean open;
        try {
            open = overlay.largeMapOpen();
        } catch (LinkageError | RuntimeException failure) {
            off(failure);
            open = false;
        }
        PollutionMapStore.watch(open);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PollutionMapStore.clear();
        level = null;
    }

    /** The integration, when FTB Chunks is loaded and its API links; null otherwise (one warning). */
    private static Overlay find() {
        if (!FxCompat.loaded(FTB_CHUNKS)) {
            return null;
        }
        try {
            var found = com.wormzjl.icheme.client.pollution.ftbchunks.FtbChunksOverlay.install();
            PollutionMapStore.onChange(() -> {
                var current = overlay;
                if (current == null) {
                    return;
                }
                try {
                    current.refresh();
                } catch (LinkageError | RuntimeException failure) {
                    off(failure);
                }
            });
            IChemE.LOGGER.info("pollution status=MAP_OVERLAY detail=FTB Chunks {}", FxCompat.version(FTB_CHUNKS));
            return found;
        } catch (LinkageError | RuntimeException failure) {
            IChemE.LOGGER.warn("pollution status=MAP_OVERLAY_OFF detail=the FTB Chunks ({}) map overlay did not load: {}",
                    FxCompat.version(FTB_CHUNKS), String.valueOf(failure));
            return null;
        }
    }

    /** The integration failed: off for the session, with one warning; the map closes its ask. */
    public static void off(Throwable failure) {
        if (overlay == null) {
            return;
        }
        overlay = null;
        PollutionMapStore.onChange(null);
        PollutionMapStore.watch(false);
        IChemE.LOGGER.warn("pollution status=MAP_OVERLAY_OFF detail=the FTB Chunks ({}) map overlay failed and is off for the session: {}",
                FxCompat.version(FTB_CHUNKS), String.valueOf(failure));
    }
}
