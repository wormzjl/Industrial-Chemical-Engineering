package com.wormzjl.icheme.world.inventory;

import com.wormzjl.icheme.network.MachineNetwork;
import com.wormzjl.icheme.registry.ModMenus;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.MachineView;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A machine's own page (batch 2026-11-03-rotating-machinery, W7, reading A89): the shaft part {@code unit} at
 * {@code origin} (a structure's origin, or a creative motor's block), of kind {@code kind}, with or without a datasheet. On
 * the server the menu is a session of the engine's presentation: opening it replays the part's view, its buckets bring
 * the next, closing it ends the session. On the client it holds the last view delivered, the last edit answered and the
 * last refusal.
 */
public final class MachineMenu extends AbstractContainerMenu {
    /** How far from the part the player may stand, blocks, beyond the structure's own size. */
    public static final double REACH = 8;

    private final BlockPos origin;
    private final double reach;
    private final long unit;
    private final ShaftNetwork.Kind kind;
    private final boolean datasheet;
    private final @Nullable ServerPlayer serverPlayer;
    private @Nullable MachineView view;
    private int answered;
    private String refusal = "";

    public MachineMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos(), buffer.readDouble(), buffer.readLong(), buffer.readEnum(ShaftNetwork.Kind.class), buffer.readBoolean());
    }

    public MachineMenu(int id, Inventory inventory, BlockPos origin, double reach, long unit, ShaftNetwork.Kind kind, boolean datasheet) {
        super(ModMenus.MACHINE.get(), id);
        this.origin = origin.immutable();
        this.reach = reach;
        this.unit = unit;
        this.kind = kind;
        this.datasheet = datasheet;
        serverPlayer = inventory.player instanceof ServerPlayer p ? p : null;
    }

    /** What the opening packet carries, read back by the client's constructor. */
    public static void write(RegistryFriendlyByteBuf buffer, BlockPos origin, double reach, long unit, ShaftNetwork.Kind kind, boolean datasheet) {
        buffer.writeBlockPos(origin);
        buffer.writeDouble(reach);
        buffer.writeLong(unit);
        buffer.writeEnum(kind);
        buffer.writeBoolean(datasheet);
    }

    public BlockPos origin() {
        return origin;
    }

    public long unit() {
        return unit;
    }

    public ShaftNetwork.Kind kind() {
        return kind;
    }

    /** Whether the part is a structure with a datasheet (a creative motor has none). */
    public boolean datasheet() {
        return datasheet;
    }

    public @Nullable ServerPlayer serverPlayer() {
        return serverPlayer;
    }

    /** Client: the last view delivered, or null before the first publication. */
    public @Nullable MachineView view() {
        return view;
    }

    /** Client: the sequence of the last edit the engine answered. */
    public int answered() {
        return answered;
    }

    /** Client: the reason the last answered edit was refused, or empty. */
    public String refusal() {
        return refusal;
    }

    /** Client: a bucket's view for this page, with the last edit it answers and that edit's refusal. */
    public void accept(MachineView view, int answered, String refusal) {
        this.view = view;
        this.answered = answered;
        this.refusal = refusal;
    }

    @Override
    public boolean stillValid(Player player) {
        var level = player.level();
        if (!level.hasChunkAt(origin) || player.distanceToSqr(origin.getCenter()) > reach * reach) return false;
        if (!(player instanceof ServerPlayer server)) return true;
        // The part is still there: a broken structure ends its page.
        return FluidWorldAuthority.find(server.server).map(world -> world.shafts().unit(unit) != null).orElse(false);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    /** Closing ends the session; an edit it sent still applies. */
    @Override
    public void removed(Player player) {
        super.removed(player);
        if (serverPlayer != null) MachineNetwork.closed(this);
    }
}
