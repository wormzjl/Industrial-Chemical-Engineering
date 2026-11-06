package com.wormzjl.icheme.world.inventory;

import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.registry.ModMenus;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.world.item.PipeLayingToolItem;
import com.wormzjl.icheme.world.item.PipelineToolItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * A slot-free settings session bound to the exact held stack; it requests no fluid presentation. It serves the sizing
 * tool and the laying tool (batch 2026-10-26-pipe-laying-system): each keeps its bore in its own item component, and
 * the laying tool also the paint of its lines.
 */
public final class PipelineToolMenu extends AbstractContainerMenu {
    private final InteractionHand hand;
    private final int selectedSlot;
    private final ItemStack tool;
    private final PipelineToolItem.Settings initialSettings;
    private final int initialPaint, initialSecondary;
    private final boolean initialData;

    public PipelineToolMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, buffer.readEnum(InteractionHand.class), buffer.readVarInt(), PipelineToolItem.Settings.STREAM_CODEC.decode(buffer), buffer.readInt(), buffer.readInt(), buffer.readBoolean());
    }

    private PipelineToolMenu(int id, Inventory inventory, InteractionHand hand, int selectedSlot, PipelineToolItem.Settings settings, int paint, int secondary, boolean data) {
        super(ModMenus.PIPELINE_TOOL.get(), id);
        this.hand = hand;
        this.selectedSlot = selectedSlot;
        tool = inventory.player.getItemInHand(hand);
        initialSettings = Objects.requireNonNull(settings);
        initialPaint = Paint.line(paint) ? paint : Paint.BARE;
        initialSecondary = Paint.line(secondary) ? secondary : initialPaint;
        initialData = data && tool.is(ModItems.PIPE_LAYING_TOOL.get());
    }

    public static void open(ServerPlayer player, InteractionHand hand) {
        int slot = player.getInventory().selected;
        var stack = player.getItemInHand(hand);
        var settings = Objects.requireNonNull(stack.get(component(stack)));
        int paint = PipeLayingToolItem.paint(stack), secondary = PipeLayingToolItem.secondary(stack);
        boolean data=PipeLayingToolItem.data(stack);
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new PipelineToolMenu(id, inventory, hand, slot, settings, paint, secondary, data),
                stack.getHoverName()), buffer -> {
            buffer.writeEnum(hand);
            buffer.writeVarInt(slot);
            PipelineToolItem.Settings.STREAM_CODEC.encode(buffer, settings);
            buffer.writeInt(paint);
            buffer.writeInt(secondary);
            buffer.writeBoolean(data);
        });
    }

    /** The component holding {@code stack}'s bore: the laying tool's own, else the sizing tool's. */
    private static net.minecraft.core.component.DataComponentType<PipelineToolItem.Settings> component(ItemStack stack) {
        return stack.is(ModItems.PIPE_LAYING_TOOL.get()) ? ModItems.PIPE_LAYING_SETTINGS.get() : ModItems.PIPELINE_TOOL_SETTINGS.get();
    }

    public PipelineToolItem.Settings initialSettings() {
        return initialSettings;
    }

    /** The laying tool's primary paint when the session opened; {@link Paint#BARE} for the sizing tool. */
    public int initialPaint() {
        return initialPaint;
    }

    /** The laying tool's secondary paint when the session opened. */
    public int initialSecondary() {
        return initialSecondary;
    }

    /** Whether the tool lays lines, which take a paint: the laying tool, not the sizing tool. */
    public boolean paints() {
        return tool.is(ModItems.PIPE_LAYING_TOOL.get());
    }
    public boolean initialData(){return initialData;}

    /** Server: the packet handler has checked this session's id, lifetime, diameter and paints. The sizing tool keeps no paint; a secondary paint equal to the primary is kept as unset. */
    public void save(PipelineToolItem.Settings settings, int paint, int secondary) {
        save(settings,paint,secondary,initialData);
    }
    public void save(PipelineToolItem.Settings settings, int paint, int secondary,boolean data) {
        tool.set(component(tool), settings);
        if (paints()) {
            if(data)tool.set(ModItems.PIPE_LAYING_DATA.get(),true);
            else tool.remove(ModItems.PIPE_LAYING_DATA.get());
            if (paint == Paint.BARE) tool.remove(ModItems.PIPE_LAYING_PAINT.get());
            else tool.set(ModItems.PIPE_LAYING_PAINT.get(), paint);
            if (secondary == paint) tool.remove(ModItems.PIPE_LAYING_SECONDARY.get());
            else tool.set(ModItems.PIPE_LAYING_SECONDARY.get(), secondary);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return !player.isSpectator() && (tool.is(ModItems.PIPELINE_TOOL.get()) || tool.is(ModItems.PIPE_LAYING_TOOL.get())) && player.getItemInHand(hand) == tool
                && (hand == InteractionHand.OFF_HAND || player.getInventory().selected == selectedSlot);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
