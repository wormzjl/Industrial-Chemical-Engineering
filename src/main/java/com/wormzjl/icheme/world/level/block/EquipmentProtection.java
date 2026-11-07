package com.wormzjl.icheme.world.level.block;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Equipment edits are confined to one cell, level and calling thread; no permission survives an operation. */
public final class EquipmentProtection {
    private record Edit(Level level, BlockPos position) {}
    private static final ThreadLocal<Edit> EDIT = new ThreadLocal<>();

    private EquipmentProtection() {}

    public static boolean cell(BlockState state) {
        return state.getBlock() instanceof EquipmentShellBlock || state.getBlock() instanceof EquipmentInteriorBlock;
    }

    /** Read by the chunk write boundary before any state, block entity or lifecycle hook changes. */
    public static boolean mayReplace(Level level, BlockPos position, BlockState before, BlockState after) {
        return level.isClientSide() || before == after || !cell(before) || editing(level, position);
    }

    static boolean editing(Level level, BlockPos position) {
        var edit = EDIT.get();
        return edit != null && edit.level() == level && edit.position().equals(position);
    }

    /** Only this package's verified structure lifecycle and creative player removal can grant an edit. */
    static <T> T edit(Level level, BlockPos position, Supplier<T> action) {
        if (!level.isClientSide() && level.getServer() != null && !level.getServer().isSameThread())
            throw new IllegalStateException("Equipment edits belong to the server thread");
        var previous = EDIT.get();
        EDIT.set(new Edit(level, position.immutable()));
        try { return action.get(); }
        finally {
            if (previous == null) EDIT.remove();
            else EDIT.set(previous);
        }
    }

    static boolean playerRemoval(Level level, BlockPos position, Player player, BooleanSupplier remove) {
        return player.isCreative() && edit(level, position, remove::getAsBoolean);
    }
}
