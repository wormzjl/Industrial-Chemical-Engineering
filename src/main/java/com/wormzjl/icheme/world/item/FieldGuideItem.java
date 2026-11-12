package com.wormzjl.icheme.world.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The Field Guide (batch 2026-11-12-tutorial-system, owner decision D3): given once at first join by the tutorial's root
 * advancement's reward ({@code icheme:tutorial/field_guide}); using it opens the guide on the client. The server does
 * nothing with it. The client call stays behind the client-side check, so a dedicated server never loads a client class.
 */
public final class FieldGuideItem extends Item {
    public FieldGuideItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (level.isClientSide()) com.wormzjl.icheme.client.gui.tutorial.GuideClient.open();
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
