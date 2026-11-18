package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.pollution.Exposure;
import com.wormzjl.icheme.science.pollution.Respirator;
import com.wormzjl.icheme.world.item.RespiratorItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * The respirator as gear (batch 2026-11-18-respirator): where it is worn, and what it does in one effects pass of
 * {@link PollutionEffects}. Players only; a mob never wears it. The law is {@link Respirator}.
 *
 * <p>Curios is optional: its types are named only in {@code compat.curios.CuriosGear}, loaded only after
 * {@code ModList.isLoaded("curios")}, so the mod starts without it.</p>
 */
public final class RespiratorGear {
    /** Play seconds of one effects pass: the same conversion the damage uses. */
    static final double PASS_SECONDS = SimulationTime.playSeconds(PollutionEffects.EFFECT_TICKS);

    private static Boolean curios;

    private RespiratorGear() {
    }

    private static boolean curios() {
        Boolean loaded = curios;
        if (loaded == null) {
            var list = ModList.get();
            loaded = list != null && list.isLoaded("curios");
            curios = loaded;
        }
        return loaded;
    }

    /** The worn respirator: the helmet slot's, else (Curios installed) a Curios slot's; empty when none is worn. */
    public static ItemStack worn(Player player) {
        if (player == null) {
            return ItemStack.EMPTY;
        }
        var head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.is(ModItems.RESPIRATOR.get())) {
            return head;
        }
        if (curios()) {
            return com.wormzjl.icheme.compat.curios.CuriosGear.find(player, ModItems.RESPIRATOR.get());
        }
        return ItemStack.EMPTY;
    }

    /**
     * One pass of a living thing in a cube with real {@code indices} and their {@code levels} (by channel ordinal): returns the
     * levels to use for effects and damage, and books the filter load. Without a worn respirator with a working filter it
     * returns {@code levels} itself. The pass is protected by the capacity before its own load. {@code here} says the entity
     * stands in this cube (its block position's): only that cube books the load, so a player whose box touches two cubes
     * is charged once.
     */
    public static Exposure.Level[] protect(LivingEntity entity, boolean here, double[] indices, Exposure.Level[] levels) {
        if (!(entity instanceof Player player) || player.isCreative() || player.isSpectator()) {
            return levels;
        }
        var stack = worn(player);
        if (stack.isEmpty()) {
            return levels;
        }
        double remaining = RespiratorItem.filter(stack);
        var out = Respirator.protect(true, remaining, indices, levels);
        if (here && Respirator.protects(remaining)) {
            double left = Respirator.after(remaining, Respirator.load(indices, PASS_SECONDS));
            if (left != remaining) {
                RespiratorItem.setFilter(stack, left);
            }
        }
        return out;
    }
}
