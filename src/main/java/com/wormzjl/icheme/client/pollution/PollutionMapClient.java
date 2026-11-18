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

    /** The pollution map layer key (G3, option A): unbound by default; cycles Air, Ground, Both on the map. */
    public static final net.minecraft.client.KeyMapping LAYER_KEY = new net.minecraft.client.KeyMapping("key.icheme.pollution_layer",
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(), "key.categories.icheme");

    /** The key with no screen open (the map shows the new layer when it next opens). */
    @SubscribeEvent
    public static void key(net.neoforged.neoforge.client.event.InputEvent.Key event) {
        boolean pressed = false;
        while (LAYER_KEY.consumeClick()) pressed = true;
        if (pressed) cycle();
    }

    /** The key on an open screen: only while the map is open (the map draws the new layer at once). */
    @SubscribeEvent
    public static void screenKey(net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre event) {
        if (PollutionMapStore.watching() && !LAYER_KEY.isUnbound() && LAYER_KEY.matches(event.getKeyCode(), event.getScanCode())) {
            cycle();
            event.setCanceled(true);
        }
    }

    /** The next layer, saved, and its name on the action bar. */
    static void cycle() {
        var next = PollutionMapStore.layer().next();
        PollutionMapStore.layer(next);
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.translatable("pollution.icheme.map.layer_shown",
                net.minecraft.network.chat.Component.translatable(next.key())), true);
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
