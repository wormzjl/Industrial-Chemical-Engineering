package com.wormzjl.icheme.world.inventory;

import com.wormzjl.icheme.registry.ModMenus;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.level.block.entity.AlarmBeaconBlockEntity;

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

/**
 * An alarm beacon's view (batch 2026-11-15-fluid-interfaces, W9), bound to one beacon and one menu session: opening it
 * replays the last published view and subscribes; closing it ends the subscription. Edits are queued engine events.
 */
public final class AlarmBeaconMenu extends AbstractContainerMenu {
    private final Player player;
    private final BlockPos pos;
    private final UUID alarm;
    private CompoundTag snapshot = new CompoundTag();
    private long sequence;
    private long ack;
    private String reply = "";
    private int inputPage;

    public AlarmBeaconMenu(int id, Inventory inv, RegistryFriendlyByteBuf b) {
        this(id, inv, b.readBlockPos(), b.readUUID());
    }

    private AlarmBeaconMenu(int id, Inventory inv, BlockPos pos, UUID alarm) {
        super(ModMenus.ALARM_BEACON.get(), id);
        this.player = inv.player;
        this.pos = pos.immutable();
        this.alarm = alarm;
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        if (!(player.level().getBlockEntity(pos) instanceof AlarmBeaconBlockEntity beacon)) return;
        var world = FluidWorldAuthority.find(player.server).orElse(null);
        if (world == null || world.controls().facts().alarm(beacon.identity()) == null) return;
        player.openMenu(
                new SimpleMenuProvider(
                        (id, inv, p) -> new AlarmBeaconMenu(id, inv, pos, beacon.identity()),
                        Component.translatable("block.icheme.alarm_beacon")),
                b -> {
                    b.writeBlockPos(pos);
                    b.writeUUID(beacon.identity());
                });
        if (player.containerMenu instanceof AlarmBeaconMenu menu) world.controls().watch(menu);
    }

    public UUID alarm() {
        return alarm;
    }

    public Player player() {
        return player;
    }

    /** The page of input choices this menu asks for. */
    public int inputPage() {
        return inputPage;
    }

    public void browse(int page) {
        inputPage = Math.max(0, page);
    }

    /** The answer to the last queued edit, sent with the next publication. */
    public void answer(String text) {
        ack++;
        reply = text;
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
            copy.putString("reply", reply);
            com.wormzjl.icheme.network.ControlNetwork.sendAlarm(server, containerId, alarm, copy);
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
                && p.level().getBlockEntity(pos) instanceof AlarmBeaconBlockEntity beacon
                && beacon.identity().equals(alarm)
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
