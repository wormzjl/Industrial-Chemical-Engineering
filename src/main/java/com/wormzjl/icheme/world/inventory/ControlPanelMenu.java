package com.wormzjl.icheme.world.inventory;

import com.wormzjl.icheme.registry.ModMenus;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** A requested view, bound to one physical panel and one menu session. */
public final class ControlPanelMenu extends AbstractContainerMenu {
    private final Player player;
    private final BlockPos pos;
    private final UUID panel;
    private final boolean dcs;
    private CompoundTag snapshot = new CompoundTag();
    private long sequence;
    private long ack;
    private UUID answered;
    private String reply = "";
    private int controllerPage, devicePage;

    public int controllerPage() {
        return controllerPage;
    }

    public int devicePage() {
        return devicePage;
    }

    public void browse(int controllers, int devices) {
        controllerPage = Math.max(0, controllers);
        devicePage = Math.max(0, devices);
    }

    public void answer(UUID id, String text) {
        ack++;
        answered = id;
        reply = text;
    }

    public ControlPanelMenu(int id, Inventory inv, RegistryFriendlyByteBuf b) {
        this(id, inv, b.readBlockPos(), b.readUUID(), b.readBoolean());
    }

    private ControlPanelMenu(int id, Inventory inv, BlockPos pos, UUID panel, boolean dcs) {
        super(ModMenus.CONTROL_PANEL.get(), id);
        this.player = inv.player;
        this.pos = pos.immutable();
        this.panel = panel;
        this.dcs = dcs;
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof ControlPanelBlockEntity box)) return;
        var world = FluidWorldAuthority.find(player.server).orElse(null);
        if (world == null || world.controls().facts().panel(box.identity()) == null) return;
        player.openMenu(
                new SimpleMenuProvider(
                        (id, inv, p) ->
                                new ControlPanelMenu(id, inv, pos, box.identity(), box.dcs()),
                        Component.translatable(
                                box.dcs() ? "block.icheme.dcs" : "block.icheme.local_controller")),
                b -> {
                    b.writeBlockPos(pos);
                    b.writeUUID(box.identity());
                    b.writeBoolean(box.dcs());
                });
        if (player.containerMenu instanceof ControlPanelMenu menu) world.controls().watch(menu);
    }

    public UUID panel() {
        return panel;
    }

    public boolean dcs() {
        return dcs;
    }

    public Player player() {
        return player;
    }

    public CompoundTag snapshot() {
        return snapshot.copy();
    }

    public long sequence() {
        return sequence;
    }

    public void publish(CompoundTag tag) {
        if (player instanceof ServerPlayer server) {
            var copy = tag.copy();
            copy.putLong("ack", ack);
            if (answered != null) copy.putUUID("ackPanel", answered);
            copy.putString("reply", reply);
            com.wormzjl.icheme.network.ControlNetwork.send(server, containerId, panel, copy);
        }
    }

    public void receive(CompoundTag tag) {
        snapshot = tag.copy();
        sequence++;
    }

    @Override
    public boolean stillValid(Player p) {
        return !p.isSpectator()
                && p.level().hasChunkAt(pos)
                && p.level().getBlockEntity(pos) instanceof ControlPanelBlockEntity box
                && box.identity().equals(panel)
                && box.dcs() == dcs
                && p.canInteractWithBlock(pos, 4);
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        if (p instanceof ServerPlayer server)
            FluidWorldAuthority.find(server.server).ifPresent(w -> w.controls().unwatch(this));
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        return ItemStack.EMPTY;
    }
}
