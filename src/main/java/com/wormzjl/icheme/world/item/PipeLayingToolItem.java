package com.wormzjl.icheme.world.item;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.equipment.Paint;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * The pipe laying tool (batch 2026-10-26-pipe-laying-system): lays a line of pipe of its set bore between two anchors,
 * along a route the client searches, and dismantles one pipe at a time. Usable in survival, where each laid block takes
 * a pipe item (owner decision D1). The client turns each use into one explicit command ({@code PipeLayingClient});
 * the item's own use does nothing, so a Shift + use that cancels a line never also opens the settings.
 */
public final class PipeLayingToolItem extends Item {
    public static boolean data(ItemStack stack){return stack.getOrDefault(ModItems.PIPE_LAYING_DATA.get(),false);}
    public PipeLayingToolItem(Properties properties) {
        super(properties.stacksTo(1).component(ModItems.PIPE_LAYING_SETTINGS.get(), PipelineToolItem.Settings.DEFAULT));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(data(stack)?"gui.icheme.pipeline_tool.mode.data":"gui.icheme.pipeline_tool.mode.pipe").withStyle(ChatFormatting.AQUA));
        if(data(stack)){for(var line:List.of("lay","remove","configure","items"))tooltip.add(Component.translatable("item.icheme.pipe_laying_tool.data."+line).withStyle(ChatFormatting.GRAY));return;}
        var settings = stack.get(ModItems.PIPE_LAYING_SETTINGS.get());
        if (settings != null) tooltip.add(Component.translatable("item.icheme.pipe_laying_tool.size", Double.toString(settings.diameter())).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.icheme.pipe_laying_tool.paint", paintName(paint(stack))).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.icheme.pipe_laying_tool.secondary", paintName(secondary(stack))).withStyle(ChatFormatting.AQUA));
        for (var line : List.of("lay", "cancel", "dismantle", "configure", "apart"))
            tooltip.add(Component.translatable("item.icheme.pipe_laying_tool." + line).withStyle(ChatFormatting.GRAY));
    }

    /** The primary paint {@code stack} lays its lines' pipe in: 0xRRGGBB, or {@link Paint#BARE}. */
    public static int paint(ItemStack stack) {
        return stack.getOrDefault(ModItems.PIPE_LAYING_PAINT.get(), Paint.BARE);
    }

    /** The secondary paint {@code stack} lays its lines' flanges in: 0xRRGGBB, or {@link Paint#BARE}; unset, the primary. */
    public static int secondary(ItemStack stack) {
        return stack.getOrDefault(ModItems.PIPE_LAYING_SECONDARY.get(), paint(stack));
    }

    /** A line's paint as the tool names it: its palette number, #RRGGBB, or bare steel. */
    public static Component paintName(int rgb) {
        if (rgb == Paint.BARE) return Component.translatable("gui.icheme.pipeline_tool.paint.bare");
        var swatch = Paint.swatch(rgb);
        return swatch != null ? Component.translatable("gui.icheme.equipment_constructor.paint.swatch", swatch.ral(), Component.translatable("gui.icheme.paint." + swatch.key()))
                : Component.literal(Paint.hex(rgb));
    }
}
