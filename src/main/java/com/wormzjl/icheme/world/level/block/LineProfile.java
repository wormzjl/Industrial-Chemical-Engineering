package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;

import net.minecraft.world.item.*;

/** Medium data; spatial and command decisions belong to the common line owners. */
public enum LineProfile {
    FLUID,
    DATA,
    POWER;

    public static LineProfile of(ItemStack tool) {
        return PipeLayingToolItem.power(tool)
                ? POWER
                : PipeLayingToolItem.data(tool) ? DATA : FLUID;
    }

    public CableMedium medium() {
        if (this == FLUID) throw new IllegalStateException("A fluid line is not a cable");
        return this == POWER ? CableMedium.POWER : CableMedium.DATA;
    }

    public static LineProfile of(CableMedium medium) {
        return medium == CableMedium.POWER ? POWER : DATA;
    }

    public Item item() {
        return (this == FLUID
                        ? ModItems.FLUID_PIPE
                        : this == POWER ? ModItems.POWER_CABLE : ModItems.DATA_CABLE)
                .get();
    }

    public Settings settings(ItemStack tool) {
        var setting = tool.get(ModItems.PIPE_LAYING_SETTINGS.get());
        double bore =
                this == FLUID ? (setting == null ? .05 : setting.diameter()) : medium().outside();
        return new Settings(
                this,
                bore,
                this == FLUID ? PipeSizes.outside(bore) : medium().outside(),
                PipeLayingToolItem.paint(tool),
                PipeLayingToolItem.secondary(tool));
    }

    public record Settings(
            LineProfile profile, double bore, double outside, int paint, int secondary) {
        public Settings {
            if (profile == null
                    || !Double.isFinite(bore)
                    || bore <= 0
                    || !Double.isFinite(outside)
                    || outside <= 0
                    || outside > PipeSizes.MAX_OUTSIDE
                    || !Paint.line(paint)
                    || !Paint.line(secondary))
                throw new IllegalArgumentException("Invalid line settings");
        }
    }
}
