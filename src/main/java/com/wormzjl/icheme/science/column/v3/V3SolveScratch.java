package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.linalg.V3BandedPivotedSolver;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * The arrays one Newton attempt works in: the banded solver's workspace and the local Jacobian's stage blocks. A column
 * calculation runs many attempts of one shape (rungs, recoveries, feed rounds), so {@link #within} keeps one set for the
 * whole calculation and each attempt borrows it ({@link #borrow}, {@link #release}). Nothing outlives the calculation: the
 * set is dropped when the outermost {@link #within} returns. An attempt outside any calculation, or one nested inside
 * another attempt, gets a fresh set, as before.
 *
 * <p>Like {@link V3BandedPivotedSolver.Workspace#clearedMatrix}, the blocks {@link #blocks} returns are overwritten by the
 * next assembly of the same attempt: a block Jacobian is consumed before the attempt assembles another. Reused arrays are
 * zeroed exactly as fresh ones are, so the arithmetic is unchanged.</p>
 */
final class V3SolveScratch {
    private static final ThreadLocal<Pool> OPEN = new ThreadLocal<>();

    final V3BandedPivotedSolver.Workspace linear = new V3BandedPivotedSolver.Workspace();
    /** Lower, diagonal and upper stage blocks of the last layout assembled, by column offset + 1. */
    private final double[][][][] blocks = new double[3][][][];

    private static final class Pool {
        private V3SolveScratch free = new V3SolveScratch();
    }

    /** Runs {@code body} as one calculation: its attempts share one scratch set, dropped when it returns. */
    static <T> T within(Supplier<T> body) {
        if (OPEN.get() != null) return body.get();
        OPEN.set(new Pool());
        try {
            return body.get();
        } finally {
            OPEN.remove();
        }
    }

    /** The calculation's set when it is free, otherwise a fresh one. */
    static V3SolveScratch borrow() {
        var pool = OPEN.get();
        if (pool == null || pool.free == null) return new V3SolveScratch();
        var scratch = pool.free;
        pool.free = null;
        return scratch;
    }

    /** Gives {@code scratch} back to the calculation, if one is open and holds none. */
    static void release(V3SolveScratch scratch) {
        var pool = OPEN.get();
        if (pool != null && pool.free == null) pool.free = scratch;
    }

    /** Zeroed blocks of {@code layout} for column offset {@code columnOffset} (-1, 0 or 1), in the shape a fresh assembly allocates. */
    double[][][] blocks(V3StageBlockLayout layout, int columnOffset) {
        var held = blocks[columnOffset + 1];
        if (held == null || !fits(held, layout, columnOffset)) return blocks[columnOffset + 1] = emptyBlocks(layout, columnOffset);
        for (var node : held) for (var row : node) Arrays.fill(row, 0.0);
        return held;
    }

    static double[][][] emptyBlocks(V3StageBlockLayout layout, int columnOffset) {
        double[][][] result = new double[layout.nodeCount()][][];
        for (int node = 0; node < result.length; node++) result[node] = new double[layout.size(node)][columns(layout, node, columnOffset)];
        return result;
    }

    private static boolean fits(double[][][] held, V3StageBlockLayout layout, int columnOffset) {
        if (held.length != layout.nodeCount()) return false;
        for (int node = 0; node < held.length; node++) {
            if (held[node].length != layout.size(node)) return false;
            int columns = columns(layout, node, columnOffset);
            for (var row : held[node]) if (row.length != columns) return false;
        }
        return true;
    }

    private static int columns(V3StageBlockLayout layout, int node, int columnOffset) {
        return node + columnOffset < 0 || node + columnOffset >= layout.nodeCount() ? 0 : layout.size(node + columnOffset);
    }
}
