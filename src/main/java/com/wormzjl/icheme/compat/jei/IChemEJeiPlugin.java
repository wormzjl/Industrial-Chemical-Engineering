package com.wormzjl.icheme.compat.jei;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModFluids;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRule;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRules;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRuntime;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import com.wormzjl.icheme.world.item.EquipmentInfoItem;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JEI information pages (batch 2026-11-12-tutorial-system, P3; owner decision D8): each equipment reference item's
 * lines (what it serves, what it needs, where it stands, and its shape-only line where it has one), and one page for
 * each other item of the mod (what it serves, and what it needs where it needs something). JEI finds this class by its
 * annotation; nothing in the mod names it, so the mod loads without JEI.
 *
 * <p>The fluid conversion category (batch 2026-11-15-fluid-interfaces, W5, D10): one recipe per rule the server sent
 * ({@link ConversionRuntime#client}), the fluid and gas converters' reference items as catalysts. Rules that arrive after
 * JEI started (the login sync racing JEI's start, or a reload) reach the running recipe manager: new ones are added, gone
 * ones hidden, returning ones shown again. While another mod's fluid is in {@code c:crude_oil} the placeholder crude oil
 * and its bucket are removed from the ingredient list (D9).</p>
 */
@JeiPlugin
public final class IChemEJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "jei_plugin");
    /** The running JEI, or null; touched on the client's main thread only. */
    private static IJeiRuntime runtime;
    /** The rules JEI holds (registered or added), and those of them hidden since. */
    private static final Set<ConversionRule> held = new LinkedHashSet<>(), hidden = new LinkedHashSet<>();
    private static boolean listening;

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var gui = registration.getJeiHelpers().getGuiHelper();
        var icon = gui.createDrawableItemLike(ModItems.EQUIPMENT_INFO.get(EquipmentShape.Kind.FLUID_CONVERTER).get());
        registration.addRecipeCategories(new FluidConversionCategory(gui, icon));
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
        held.clear();
        hidden.clear();
        var shown = shown(ConversionRuntime.client());
        held.addAll(shown);
        registration.addRecipes(FluidConversionCategory.TYPE, shown);
        if (!listening) {
            listening = true;
            ConversionRuntime.listenClient(rules -> Minecraft.getInstance().execute(() -> update(rules)));
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(ModItems.EQUIPMENT_INFO.get(EquipmentShape.Kind.FLUID_CONVERTER).get(), FluidConversionCategory.TYPE);
        registration.addRecipeCatalyst(ModItems.EQUIPMENT_INFO.get(EquipmentShape.Kind.GAS_CONVERTER).get(), FluidConversionCategory.TYPE);
        // The liquid hopper converts by the same rules (their liquid forward direction).
        registration.addRecipeCatalyst(ModItems.LIQUID_HOPPER.get(), FluidConversionCategory.TYPE);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        if (ModFluids.othersInTag()) {
            var ingredients = jeiRuntime.getIngredientManager();
            ingredients.removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, List.of(ModFluids.CRUDE_OIL_BUCKET.get().getDefaultInstance()));
            ingredients.removeIngredientsAtRuntime(NeoForgeTypes.FLUID_STACK, List.of(new FluidStack(ModFluids.CRUDE_OIL.get(), 1000)));
        }
        update(ConversionRuntime.client());
    }

    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
        held.clear();
        hidden.clear();
    }

    /** The rules of {@code rules} that name a fluid present on this client. */
    private static List<ConversionRule> shown(ConversionRules rules) {
        var out = new ArrayList<ConversionRule>();
        for (var rule : rules.rules()) if (!FluidConversionCategory.fluids(rule).isEmpty()) out.add(rule);
        return out;
    }

    /** Brings the running JEI to {@code rules}: adds the new rules, hides the gone ones, shows the returning ones. */
    private static void update(ConversionRules rules) {
        if (runtime == null) return;
        var recipes = runtime.getRecipeManager();
        var now = new LinkedHashSet<>(shown(rules));
        List<ConversionRule> added = new ArrayList<>(), back = new ArrayList<>(), gone = new ArrayList<>();
        for (var rule : now) {
            if (!held.contains(rule)) added.add(rule);
            else if (hidden.contains(rule)) back.add(rule);
        }
        for (var rule : held) if (!now.contains(rule) && !hidden.contains(rule)) gone.add(rule);
        if (!added.isEmpty()) {
            recipes.addRecipes(FluidConversionCategory.TYPE, added);
            held.addAll(added);
        }
        if (!back.isEmpty()) {
            recipes.unhideRecipes(FluidConversionCategory.TYPE, back);
            back.forEach(hidden::remove);
        }
        if (!gone.isEmpty()) {
            recipes.hideRecipes(FluidConversionCategory.TYPE, gone);
            hidden.addAll(gone);
        }
    }
}
