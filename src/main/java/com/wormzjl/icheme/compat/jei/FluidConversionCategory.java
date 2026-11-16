package com.wormzjl.icheme.compat.jei;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.conversion.ConversionLines;
import com.wormzjl.icheme.registry.ModFluids;
import com.wormzjl.icheme.science.fluid.conversion.ConversionRule;
import com.wormzjl.icheme.science.material.MaterialName;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The JEI category "Fluid conversion" (batch 2026-11-15-fluid-interfaces, W5, D10): one recipe per conversion rule the
 * server sent. Left, the Forge fluid (1 000 mB; a tag rule cycles through the tag's source fluids); right, a scroll box
 * with the rule's text ({@link ConversionLines}): the stream a litre becomes (forward, "→"), the drawn phase that becomes
 * the fluid (reverse, "←"). The converters are its catalysts.
 */
final class FluidConversionCategory extends AbstractRecipeCategory<ConversionRule> {
    static final RecipeType<ConversionRule> TYPE = RecipeType.create(IChemE.MOD_ID, "fluid_conversion", ConversionRule.class);
    private static final int WIDTH = 160, HEIGHT = 84, TEXT_X = 22;

    FluidConversionCategory(IGuiHelper gui, IDrawable icon) {
        super(TYPE, Component.translatable(ConversionLines.TITLE), icon, WIDTH, HEIGHT);
    }

    /**
     * The Forge fluids rule {@code rule} names on this client: its one fluid, or the source fluids of its tag; the
     * placeholder crude oil left out while another mod's fluid is in {@code c:crude_oil} (D9). Empty where none is present.
     */
    static List<Fluid> fluids(ConversionRule rule) {
        var out = new ArrayList<Fluid>();
        var name = rule.tag() ? rule.fluid().substring(1) : rule.fluid();
        var id = ResourceLocation.tryParse(name);
        if (id == null) return out;
        if (rule.tag()) {
            boolean hide = ModFluids.othersInTag();
            BuiltInRegistries.FLUID.getTag(TagKey.create(Registries.FLUID, id)).ifPresent(set -> set.forEach(holder -> {
                var fluid = holder.value();
                if (fluid != Fluids.EMPTY && fluid.defaultFluidState().isSource() && !(hide && fluid == ModFluids.CRUDE_OIL.get())) out.add(fluid);
            }));
        } else {
            BuiltInRegistries.FLUID.getOptional(id).filter(f -> f != Fluids.EMPTY).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, ConversionRule rule, IFocusGroup focuses) {
        var stacks = new ArrayList<FluidStack>();
        for (var fluid : fluids(rule)) stacks.add(new FluidStack(fluid, 1000));
        var role = rule.toIChemE() != null ? RecipeIngredientRole.INPUT : RecipeIngredientRole.OUTPUT;
        builder.addSlot(role, 1, 1).setStandardSlotBackground().addIngredients(NeoForgeTypes.FLUID_STACK, stacks).setFluidRenderer(1000, false, 16, 16);
        // A rule both ways also answers "how is this fluid made": the fluid is its output too.
        if (rule.toIChemE() != null && rule.toForge() != null)
            builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT).addIngredients(NeoForgeTypes.FLUID_STACK, stacks);
    }

    @Override
    public void createRecipeExtras(IRecipeExtrasBuilder builder, ConversionRule rule, IFocusGroup focuses) {
        var text = new ArrayList<FormattedText>();
        var catalog = MaterialRuntime.active();
        for (var line : ConversionLines.of(rule)) {
            var args = new ArrayList<Object>();
            if (line.compound() != null) args.add(name(catalog.name(line.compound())));
            args.addAll(line.args());
            text.add(Component.translatable(line.key(), args.toArray()));
        }
        builder.addScrollBoxWidget(WIDTH - TEXT_X, HEIGHT, TEXT_X, 0).setContents(text);
    }

    /** A compound's name in the client's language; a petroleum fraction with its boiling range (as the device pages show it). */
    private static Component name(MaterialName n) {
        var base = Component.translatableWithFallback(n.translationKey(), n.fallback());
        if (!n.kind().equals("petroleum_fraction")) return base;
        if (n.lowerKelvin() == null) return Component.translatableWithFallback(n.rangeKey(), "%s, NBP below %s°C", base, n.upperCelsius());
        if (n.upperKelvin() == null) return Component.translatableWithFallback(n.rangeKey(), "%s, NBP above %s°C", base, n.lowerCelsius());
        return Component.translatableWithFallback(n.rangeKey(), "%s, NBP %s–%s°C", base, n.lowerCelsius(), n.upperCelsius());
    }

    @Override
    public ResourceLocation getRegistryName(ConversionRule rule) {
        var id = ResourceLocation.tryParse(rule.id());
        return id != null ? id : ResourceLocation.fromNamespaceAndPath(IChemE.MOD_ID, "fluid_conversion/unnamed");
    }
}
