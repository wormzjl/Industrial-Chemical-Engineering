package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.runtime.pollution.RespiratorGear;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The respirator's filter (batch 2026-11-18-respirator): one combined cartridge for every channel (owner), new and full,
 * {@link com.wormzjl.icheme.science.pollution.Respirator#FILTER_CAPACITY} index-seconds. Used while a respirator is worn, it
 * replaces the fitted filter; a part-used filter that is replaced is lost.
 */
public final class RespiratorFilterItem extends Item {
    public RespiratorFilterItem(Properties properties) {
        super(properties.stacksTo(16));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        var worn = RespiratorGear.worn(player);
        if (!worn.isEmpty() && RespiratorItem.fitFrom(player, worn, stack)) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        return InteractionResultHolder.pass(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.icheme.respirator_filter").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.icheme.respirator_filter.replace").withStyle(ChatFormatting.DARK_GRAY));
    }
}
