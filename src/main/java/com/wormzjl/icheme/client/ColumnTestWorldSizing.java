package com.wormzjl.icheme.client;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.testworld.ColumnTestWorld;
import com.wormzjl.icheme.testworld.SizingCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * While the Create World screen has the world type "IChemE column test", the review plant's sizes start on a background
 * thread ({@link SizingCache#prepare}), so the world's build finds them made or nearly.
 */
@EventBusSubscriber(modid = IChemE.MOD_ID, value = Dist.CLIENT)
public final class ColumnTestWorldSizing {
    private ColumnTestWorldSizing() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!(Minecraft.getInstance().screen instanceof CreateWorldScreen screen)) return;
        var type = screen.getUiState().getWorldType();
        if (type == null || type.preset() == null || !type.preset().is(ColumnTestWorld.PRESET)) return;
        SizingCache.prepare(MaterialRuntime.active());
    }
}
