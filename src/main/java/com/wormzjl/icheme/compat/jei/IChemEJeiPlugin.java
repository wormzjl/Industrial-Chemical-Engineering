package com.wormzjl.icheme.compat.jei;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import com.wormzjl.icheme.world.item.EquipmentInfoItem;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * JEI information pages (batch 2026-11-12-tutorial-system, P3; owner decision D8): each equipment reference item's
 * lines (what it serves, what it needs, where it stands, and its shape-only line where it has one), and one page for
 * each other item of the mod (what it serves, and what it needs where it needs something). JEI finds this class by its
 * annotation; nothing in the mod names it, so the mod loads without JEI. No recipe categories and no screens.
 */
@JeiPlugin
public final class IChemEJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        for (var item : BuiltInRegistries.ITEM) {
            var id = BuiltInRegistries.ITEM.getKey(item);
            if (!id.getNamespace().equals(IChemE.MOD_ID)) continue;
            List<String> keys = item instanceof EquipmentInfoItem info ? EquipmentInfo.pageKeys(info.kind()) : EquipmentInfo.itemPageKeys(id.getPath());
            var lines = new Component[keys.size()];
            for (int i = 0; i < lines.length; i++) lines[i] = Component.translatable(keys.get(i));
            registration.addIngredientInfo(item, lines);
        }
    }
}
