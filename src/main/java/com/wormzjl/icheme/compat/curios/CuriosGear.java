package com.wormzjl.icheme.compat.curios;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

/**
 * The respirator in a Curios slot (batch 2026-11-18-respirator; owner: "Curios slot if present"). Curios is optional: this
 * class, and {@link CuriosRespiratorClient} on the client, are the only ones that name its types, and both are reached only
 * after {@code ModList.isLoaded("curios")}. The slot is Curios' own {@code head} slot, given to the player and opened to
 * the respirator by the data files {@code data/icheme/curios/entities/respirator.json} and {@code data/curios/tags/item/head.json}.
 */
public final class CuriosGear {
    private CuriosGear() {
    }

    /** The first stack of {@code item} in the entity's Curios slots (the live stack, so a change to it is kept), or empty. */
    public static ItemStack find(LivingEntity entity, Item item) {
        return CuriosApi.getCuriosInventory(entity)
                .flatMap(inventory -> inventory.findFirstCurio(item))
                .map(SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }
}
