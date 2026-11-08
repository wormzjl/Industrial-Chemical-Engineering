package com.wormzjl.icheme.world.level.block.entity;

import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.world.level.block.*;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

import java.util.*;

/**
 * Disposable copy; canonical port/endpoint facts determine whether the occupied cell is still
 * needed.
 */
public final class LinePieceBlockEntity extends BlockEntity implements LinePieces.Copy {
    private List<LinePieces.Piece> pieces = List.of();
    private volatile VoxelShape shape = Shapes.empty();

    public LinePieceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LINE_PIECE.get(), pos, state);
    }

    public List<LinePieces.Piece> linePieces() {
        return pieces;
    }

    public VoxelShape shape() {
        return shape;
    }

    public void linePieces(List<LinePieces.Piece> value) {
        if (pieces.equals(value)) return;
        pieces = List.copyOf(value);
        rebuild();
        setChanged();
        LinePieces.clientCopy(this, pieces);
        if (level != null && !level.isClientSide())
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private void rebuild() {
        shape = Shapes.empty();
        for (var p : pieces) {
            var part = p.cells().get(worldPosition);
            if (part != null) shape = Shapes.or(shape, part);
        }
        shape = shape.optimize();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        LinePieces.clientCopy(this, pieces);
        if (level instanceof ServerLevel server) {
            if (pieces.isEmpty()) server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
            else for (var p : pieces) LinePieces.queue(server.getServer(), p.parent());
        }
    }

    @Override
    public void setRemoved() {
        LinePieces.unloaded(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        LinePieces.unloaded(this);
        super.onChunkUnloaded();
    }

    public void broken() {
        // A proxy has no independent fact to delete. Native player removal is admitted by
        // LineLaying;
        // unrelated block destruction only schedules reconstruction from the surviving connection
        // facts.
        if (level instanceof ServerLevel server)
            for (var p : pieces) LinePieces.queue(server.getServer(), p.parent());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.put("LinePieces", LinePieces.save(pieces));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        pieces = LinePieces.load(tag);
        rebuild();
        LinePieces.clientCopy(this, pieces);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        return saveWithoutMetadata(provider);
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }
}
