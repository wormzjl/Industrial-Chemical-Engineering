package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Locale;

/** A passive transmitter; physical installation is immediate, readings follow the engine's publications. */
public final class SensorItem extends Item {
    private final Sensor.Kind kind;
    public SensorItem(Properties properties,Sensor.Kind kind){super(properties.stacksTo(64));this.kind=kind;}
    public Sensor.Kind kind(){return kind;}
    private InteractionResult interact(Player player,InteractionHand hand){
        if(player instanceof ServerPlayer server)FluidWorldAuthority.find(server.server).ifPresent(w->w.instruments().requestSensor(server,hand,kind));
        return InteractionResult.sidedSuccess(player.level().isClientSide());
    }
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext context){return context.getPlayer()==null?InteractionResult.PASS:interact(context.getPlayer(),context.getHand());}
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player player,InteractionHand hand){return new InteractionResultHolder<>(interact(player,hand),player.getItemInHand(hand));}
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,List<Component> lines,TooltipFlag flag){
        lines.add(Component.translatable("tooltip.icheme.sensor."+kind.name().toLowerCase(Locale.ROOT)));
        lines.add(Component.translatable("tooltip.icheme.sensor.install"));
    }
}
