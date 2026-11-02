package com.wormzjl.icheme.world.inventory;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.registry.ModMenus;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.world.level.block.entity.ProcessSimulatorBlockEntity;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * A process simulator open on a block (batch 2026-11-02-process-simulator, plan section 3.4): a menu with no slots, which ties a player to it. Its life is the
 * subscription: closing, leaving and the block going all end here. It carries a sheet body's parts, in either direction, so they end with it as well.
 */
public final class ProcessSimulatorMenu extends AbstractContainerMenu {
    /** The translation key suffix of a body from a player who does not hold the sheet. */
    public static final String READ_ONLY = "store.read_only";
    private static final int MAX_TOKENS = 64, MAX_TEXT = 256;

    /**
     * What an opening says before the sheet's parts come.
     *
     * @param reader  whether this player only reads
     * @param holder  who edits, for a reader
     * @param stored  whether a body follows
     * @param damaged whether the block stands empty over a sheet it could not read
     * @param tokens  the server's revision tokens
     */
    public record Opening(BlockPos pos, boolean reader, String holder, boolean stored, boolean damaged, Map<String, String> tokens) {
        public void write(FriendlyByteBuf buffer) {
            buffer.writeBlockPos(pos);
            buffer.writeBoolean(reader);
            buffer.writeUtf(holder, MAX_TEXT);
            buffer.writeBoolean(stored);
            buffer.writeBoolean(damaged);
            buffer.writeMap(tokens, (b, key) -> b.writeUtf(key, MAX_TEXT), (b, value) -> b.writeUtf(value));
        }

        public static Opening read(FriendlyByteBuf buffer) {
            var pos = buffer.readBlockPos();
            boolean reader = buffer.readBoolean();
            String holder = buffer.readUtf(MAX_TEXT);
            boolean stored = buffer.readBoolean(), damaged = buffer.readBoolean();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_TOKENS) throw new io.netty.handler.codec.DecoderException("Invalid token count");
            var tokens = new TreeMap<String, String>();
            for (int i = 0; i < count; i++) tokens.put(buffer.readUtf(MAX_TEXT), buffer.readUtf());
            return new Opening(pos, reader, holder, stored, damaged, tokens);
        }
    }

    private final ContainerLevelAccess access;
    private final BlockPos blockPos;
    private final @Nullable Opening opening;
    private final SheetBody.Assembly parts = new SheetBody.Assembly();
    private Consumer<byte[]> replay = body -> {
    };
    private BiConsumer<String, String> answers = (key, detail) -> {
    };

    /** The client's side, from what the opening wrote. */
    public ProcessSimulatorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(containerId, ContainerLevelAccess.NULL, Opening.read(buffer));
    }

    private ProcessSimulatorMenu(int containerId, ContainerLevelAccess access, Opening opening) {
        super(ModMenus.PROCESS_SIMULATOR.get(), containerId);
        this.access = access;
        this.opening = opening;
        blockPos = opening.pos();
    }

    /** The server's side. */
    public ProcessSimulatorMenu(int containerId, ContainerLevelAccess access, BlockPos blockPos) {
        super(ModMenus.PROCESS_SIMULATOR.get(), containerId);
        this.access = access;
        this.blockPos = blockPos.immutable();
        opening = null;
    }

    public BlockPos blockPos() {
        return blockPos;
    }

    /** What the opening said; on the client alone. */
    public @Nullable Opening opening() {
        return opening;
    }

    public SheetBody.Assembly parts() {
        return parts;
    }

    /** The block this menu stands on, on the server, or null if it has gone. */
    public @Nullable ProcessSimulatorBlockEntity block() {
        return access.evaluate((level, pos) -> Optional.ofNullable(level.getBlockEntity(pos) instanceof ProcessSimulatorBlockEntity block ? block : null)).flatMap(block -> block).orElse(null);
    }

    /** Who hears the client's side: a whole body as it lands, and the server's answers. */
    public void listen(Consumer<byte[]> replay, BiConsumer<String, String> answers) {
        this.replay = replay;
        this.answers = answers;
    }

    public void replayed(byte[] body) {
        replay.accept(body);
    }

    public void answered(String key, String detail) {
        answers.accept(key, detail);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return access.evaluate((level, pos) -> player.level() == level, true) && stillValid(access, player, ModBlocks.PROCESS_SIMULATOR.get());
    }

    /** The screen closed or its player left: a disconnect comes here too ({@code Player.remove}), and only the holder frees anything. */
    @Override
    public void removed(Player player) {
        super.removed(player);
        var block = block();
        if (block != null) block.store().release(player.getUUID());
    }
}
