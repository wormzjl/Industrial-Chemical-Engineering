package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A vessel's own fitting, placed where the vessel's shape gives it: the floating-roof tank's full-height level gauge (its
 * scale comes from the tank), or a drum's gas blanketing valve ({@code gas_blanketing_valve}, batch
 * 2026-11-11-gas-blanketing). Neither carries per-item settings.
 */
public final class LiquidLevelGaugeItem extends Item {
    private final String tooltip;

    public LiquidLevelGaugeItem(Properties properties) {
        this(properties, "liquid_level_gauge");
    }

    /** The fitting whose tooltip lines are {@code tooltip.icheme.<tooltip>} and {@code tooltip.icheme.<tooltip>.remove}. */
    public LiquidLevelGaugeItem(Properties properties, String tooltip) {
        super(properties.stacksTo(64));
        this.tooltip = tooltip;
    }

    private InteractionResult interact(Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server)
            FluidWorldAuthority.find(server.server)
                    .ifPresent(w -> w.instruments().request(server, hand, false));
        return InteractionResult.sidedSuccess(player.level().isClientSide());
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return context.getPlayer() == null
                ? InteractionResult.PASS
                : interact(context.getPlayer(), context.getHand());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        return new InteractionResultHolder<>(interact(player, hand), player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.icheme." + tooltip));
        lines.add(Component.translatable("tooltip.icheme." + tooltip + ".remove"));
    }
}
