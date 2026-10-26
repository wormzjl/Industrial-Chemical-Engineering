package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** A full-height floating-roof tank instrument; its scale comes from the tank, without per-item settings. */
public final class LiquidLevelGaugeItem extends Item {
    public LiquidLevelGaugeItem(Properties properties){super(properties.stacksTo(64));}
    private InteractionResult interact(Player player,InteractionHand hand){
        if(player instanceof ServerPlayer server)FluidWorldAuthority.find(server.server).ifPresent(w->w.instruments().request(server,hand,false));
        return InteractionResult.sidedSuccess(player.level().isClientSide());
    }
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){return context.getPlayer()==null?InteractionResult.PASS:interact(context.getPlayer(),context.getHand());}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){return new InteractionResultHolder<>(interact(player,hand),player.getItemInHand(hand));}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){lines.add(Component.translatable("tooltip.icheme.liquid_level_gauge"));lines.add(Component.translatable("tooltip.icheme.liquid_level_gauge.remove"));}
}
