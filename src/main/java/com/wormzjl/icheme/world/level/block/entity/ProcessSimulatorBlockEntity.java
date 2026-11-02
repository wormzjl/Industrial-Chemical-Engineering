package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.science.flowsheet.SheetStore;
import com.wormzjl.icheme.world.inventory.ProcessSimulatorMenu;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * A process simulator's data (batch 2026-11-02-process-simulator, plan sections 3.4 and 3.6): the sheet with its results as one packed body, what it
 * could not read, and the saved column design a constructor imports. Sheet and results go as one body and only to who asks, so they ride in no update tag:
 * this entity has none. It stores what it is sent and never solves it; {@link SheetStore} holds the rules. Who edits is state, and is not saved.
 */
public class ProcessSimulatorBlockEntity extends BlockEntity implements MenuProvider {
    private static final String TAG_SHEET = "Sheet", TAG_KEPT = "KeptSheets", TAG_DESIGN = "Design", TAG_ID = "SheetId";
    private final SheetStore store = new SheetStore();
    private @Nullable UUID sheetId;
    private Optional<ColumnDesign> savedDesign = Optional.empty();

    public ProcessSimulatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PROCESS_SIMULATOR.get(), pos, state);
    }

    public SheetStore store() {
        return store;
    }

    /** This block's own name, for what a client keeps of its sheet: a position says nothing across worlds. Made at the first opening, and it stays. */
    public UUID sheetId() {
        if (sheetId == null) {
            sheetId = UUID.randomUUID();
            setChanged();
        }
        return sheetId;
    }

    /** Whether {@code player} edits, once they open this block. A holder gone without word does not keep it: only a player still at this block's screen does. */
    public boolean opens(Player player) {
        return store.open(player.getUUID(), this::atThisBlock);
    }

    private boolean atThisBlock(UUID id) {
        if (!(level instanceof ServerLevel server)) return false;
        var holder = server.getServer().getPlayerList().getPlayer(id);
        return holder != null && holder.containerMenu instanceof ProcessSimulatorMenu menu && menu.blockPos().equals(worldPosition) && holder.level() == level;
    }

    /** Takes a body from {@code player}, or refuses it and leaves what is stored. */
    public void accept(UUID player, byte[] packed) throws SheetBody.Refused {
        store.accept(player, packed, ProcessSimulatorMenu.READ_ONLY);
        setChanged();
    }

    /** The saved design a constructor's Import takes: the last the editor saved. A read: nothing is solved. */
    public Optional<ColumnDesign> savedDesign() {
        return savedDesign;
    }

    public void saveDesign(ColumnDesign design) {
        savedDesign = Optional.of(design);
        setChanged();
    }

    @Override
    public void setRemoved() {
        store.free();
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (sheetId != null) tag.putUUID(TAG_ID, sheetId);
        if (store.body().length > 0) tag.putByteArray(TAG_SHEET, store.body());
        if (!store.kept().isEmpty()) {
            var kept = new ListTag();
            for (var body : store.kept()) kept.add(new ByteArrayTag(body));
            tag.put(TAG_KEPT, kept);
        }
        savedDesign.ifPresent(design -> tag.put(TAG_DESIGN, ColumnDesignTags.write(design)));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        sheetId = tag.hasUUID(TAG_ID) ? tag.getUUID(TAG_ID) : null;
        var kept = new ArrayList<byte[]>();
        for (var body : tag.getList(TAG_KEPT, Tag.TAG_BYTE_ARRAY)) kept.add(((ByteArrayTag) body).getAsByteArray());
        store.load(tag.getByteArray(TAG_SHEET), kept);
        savedDesign = Optional.empty();
        if (tag.contains(TAG_DESIGN, Tag.TAG_COMPOUND))
            try {
                savedDesign = Optional.of(ColumnDesignTags.read(tag.getCompound(TAG_DESIGN)));
            } catch (IllegalArgumentException invalid) {
                IChemE.LOGGER.warn("Process simulator at {}: saved design refused ({})", worldPosition, invalid.getMessage());
            }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.icheme.process_simulator");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return level == null ? null : new ProcessSimulatorMenu(containerId, ContainerLevelAccess.create(level, worldPosition), worldPosition);
    }
}
