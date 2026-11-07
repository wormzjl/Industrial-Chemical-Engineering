package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.world.level.block.EquipmentProtection;
import net.minecraft.world.level.Level;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Test-only access to the package-confined authority, including nesting, exception and thread boundaries. */
public final class EquipmentPermissionChecks {
    private EquipmentPermissionChecks() {}

    /** Fixture only: reproduce a saved interior state, without deleting or dismantling a structure. */
    static void savedInterior(ServerLevel level, BlockPos position, net.minecraft.world.level.block.state.BlockState state, int flags) {
        require(level.getBlockState(position).getBlock() instanceof com.wormzjl.icheme.world.level.block.EquipmentInteriorBlock
                && state.getBlock() instanceof com.wormzjl.icheme.world.level.block.EquipmentInteriorBlock);
        require((boolean) edit(level, position, () -> level.setBlock(position, state, flags)));
    }

    public static void verify(ServerLevel level, BlockPos a, BlockPos b) {
        try {
            edit(level, a, () -> {
                require(editing(level, a) && !editing(level, b));
                try { edit(level, b, () -> { require(!editing(level, a)); throw new IllegalStateException("probe"); }); }
                catch (IllegalStateException expected) { require(expected.getMessage().equals("probe")); }
                require(editing(level, a) && !editing(level, b));
                var other = new FutureTask<Boolean>(() -> {
                    if (editing(level, a)) return false;
                    try { edit(level, a, () -> true); return false; }
                    catch (IllegalStateException expected) { return true; }
                });
                Thread thread = new Thread(other, "equipment-permission-check");
                thread.setDaemon(true);
                thread.start();
                try { require(other.get(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                catch (Exception failed) { throw new AssertionError(failed); }
                throw new IllegalStateException("outer");
            });
        } catch (IllegalStateException expected) { require(expected.getMessage().equals("outer")); }
        require(!editing(level, a) && !editing(level, b));
    }

    private static final Method EDIT = method("edit", Level.class, BlockPos.class, Supplier.class);
    private static final Method EDITING = method("editing", Level.class, BlockPos.class);

    private static Method method(String name, Class<?>... parameters) {
        try {
            var method = EquipmentProtection.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); }
    }

    private static boolean editing(Level level, BlockPos position) { return (boolean) invoke(EDITING, level, position); }
    private static Object edit(Level level, BlockPos position, Supplier<?> action) { return invoke(EDIT, level, position, action); }
    private static Object invoke(Method method, Object... arguments) {
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            if (failed.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(failed.getCause());
        } catch (ReflectiveOperationException invalid) { throw new AssertionError(invalid); }
    }

    private static void require(boolean condition) { if (!condition) throw new AssertionError("Equipment authority leaked"); }
}
