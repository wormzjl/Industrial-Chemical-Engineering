package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.pollution.Respirator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;

/**
 * The respirator (batch 2026-11-18-respirator): worn in the head slot, or a Curios head slot when Curios is installed, it
 * divides what its wearer breathes by {@link Respirator#PROTECTION_FACTOR} while its filter lasts
 * ({@link com.wormzjl.icheme.runtime.pollution.RespiratorGear}). No armour value and no durability, so corrosive air does
 * not wear it. It holds at most one filter, as {@link ModItems#RESPIRATOR_FILTER} on the stack: its remaining capacity, or
 * absent for no filter.
 *
 * <p>Used in hand with no working filter (none fitted, or spent) and a filter in the inventory: it fits one. Otherwise use
 * puts it on, as any helmet.</p>
 */
public final class RespiratorItem extends ArmorItem {
    public RespiratorItem(Properties properties) {
        super(ModItems.RESPIRATOR_MATERIAL, Type.HELMET, properties.stacksTo(1));
    }

    /** The fitted filter's remaining capacity, or NaN when no filter is fitted. */
    public static double filter(ItemStack stack) {
        Double remaining = stack.get(ModItems.RESPIRATOR_FILTER.get());
        return remaining == null ? Double.NaN : remaining;
    }

    /** Whether a filter, spent or not, is fitted. */
    public static boolean fitted(ItemStack stack) {
        return stack.has(ModItems.RESPIRATOR_FILTER.get());
    }

    /** Sets the fitted filter's remaining capacity, clamped to 0 .. capacity. */
    public static void setFilter(ItemStack stack, double remaining) {
        double clamped = remaining > 0 ? Math.min(remaining, Respirator.FILTER_CAPACITY) : 0;
        stack.set(ModItems.RESPIRATOR_FILTER.get(), clamped);
    }

    /**
     * Fits a full filter to {@code respirator} from {@code source}, one of the player's filters: one filter is used, and a
     * filter fitted before (spent or part-used) is lost. Returns false, changing nothing, when {@code source} is no filter.
     * The change is made on the server; the client only reports success.
     */
    public static boolean fitFrom(Player player, ItemStack respirator, ItemStack source) {
        if (source == null || source.isEmpty() || !source.is(ModItems.RESPIRATOR_FILTER_ITEM.get())) {
            return false;
        }
        if (!player.level().isClientSide()) {
            setFilter(respirator, Respirator.FILTER_CAPACITY);
            source.consume(1, player);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER.value(),
                    SoundSource.PLAYERS, 0.8f, 1.2f);
        }
        return true;
    }

    /** The first filter in the player's inventory, or empty. */
    static ItemStack firstFilter(Player player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (stack.is(ModItems.RESPIRATOR_FILTER_ITEM.get())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (!Respirator.protects(filter(stack)) && fitFrom(player, stack, firstFilter(player))) {
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        return super.use(level, player, hand);
    }

    /** No armour and no toughness: the respirator protects only what is breathed. */
    @Override
    public ItemAttributeModifiers getDefaultAttributeModifiers() {
        return ItemAttributeModifiers.EMPTY;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return fitted(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13f * (float) Respirator.share(filter(stack)));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb((float) Respirator.share(filter(stack)) / 3f, 1f, 1f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(filterLine(stack));
        lines.add(Component.translatable("tooltip.icheme.respirator").withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("tooltip.icheme.respirator.fit").withStyle(ChatFormatting.DARK_GRAY));
    }

    /** "Filter: 73 %", "Filter spent" or "No filter". */
    public static Component filterLine(ItemStack stack) {
        if (!fitted(stack)) {
            return Component.translatable("tooltip.icheme.respirator.no_filter").withStyle(ChatFormatting.RED);
        }
        double remaining = filter(stack);
        if (!Respirator.protects(remaining)) {
            return Component.translatable("tooltip.icheme.respirator.spent").withStyle(ChatFormatting.RED);
        }
        return Component.translatable("tooltip.icheme.respirator.filter", percent(remaining))
                .withStyle(Respirator.share(remaining) > 0.25 ? ChatFormatting.GREEN : ChatFormatting.GOLD);
    }

    /** The remaining share in whole percent, at least 1 while any capacity is left (a working filter never reads 0 %). */
    public static String percent(double remaining) {
        long p = (long) Math.floor(Respirator.share(remaining) * 100);
        return Long.toString(Respirator.protects(remaining) ? Math.max(1, p) : 0);
    }
}
