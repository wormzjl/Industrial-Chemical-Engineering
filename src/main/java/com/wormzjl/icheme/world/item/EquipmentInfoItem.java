package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * An equipment reference item (batch 2026-11-12-tutorial-system, P3; owner decisions D8 and O1): one per
 * {@link EquipmentShape.Kind}, in the "Equipment guide" creative tab and on its JEI page. It builds nothing, has no
 * recipe and no loot; its tooltip says what the equipment serves, and using it opens the Field Guide. The client call
 * stays behind the client-side check, so a dedicated server never loads a client class.
 */
public final class EquipmentInfoItem extends Item {
    private final EquipmentShape.Kind kind;

    public EquipmentInfoItem(Properties properties, EquipmentShape.Kind kind) {
        super(properties.stacksTo(1));
        this.kind = kind;
    }

    public EquipmentShape.Kind kind() {
        return kind;
    }

    @Override
    public String getDescriptionId() {
        return EquipmentInfo.nameKey(kind);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable(EquipmentInfo.servesKey(kind)));
        var status = EquipmentInfo.statusKey(kind);
        if (status != null) lines.add(Component.translatable(status).withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable(EquipmentInfo.REFERENCE_KEY).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (level.isClientSide()) openGuide(kind);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** Client only. Opens the Field Guide; phase P5 opens the kind's own page here. */
    private static void openGuide(EquipmentShape.Kind kind) {
        com.wormzjl.icheme.client.gui.tutorial.GuideClient.open();
    }
}
