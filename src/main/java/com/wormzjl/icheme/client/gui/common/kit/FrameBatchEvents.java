package com.wormzjl.icheme.client.gui.common.kit;

import com.wormzjl.icheme.IChemE;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ContainerScreenEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The screen events a frame batch spans (finding C4, batch 2026-11-13-codebase-review): a container screen's background and
 * foreground events and a screen's background event fire inside {@code render}, where another mod may blit at once. Before
 * any other listener, what the batch holds goes out and the buffer draws ({@link GuiCanvas#drawOut}), so that mod's drawing
 * lies over the screen's as it would without a batch. Outside a batch nothing happens.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class FrameBatchEvents {
    private FrameBatchEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void background(ContainerScreenEvent.Render.Background event) {
        GuiCanvas.drawOut(event.getGuiGraphics());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void foreground(ContainerScreenEvent.Render.Foreground event) {
        GuiCanvas.drawOut(event.getGuiGraphics());
    }

    @SuppressWarnings("removal")
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void backgroundRendered(ScreenEvent.BackgroundRendered event) {
        GuiCanvas.drawOut(event.getGuiGraphics());
    }
}
