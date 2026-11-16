package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.runtime.pollution.PollutionMeters;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The handheld pollution meter (batch 2026-11-15-pollution-system, WP8; plan section 11). Held in either hand, the server
 * shows its holder the cube's four indices on the action bar once a second and clicks by the worst ({@link PollutionMeters});
 * used, it prints the full reading in chat. No menu and no screen; the item holds no data.
 */
public final class PollutionMeterItem extends Item {
    public PollutionMeterItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server) {
            PollutionMeters.report(server);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.icheme.pollution_meter"));
    }
}
