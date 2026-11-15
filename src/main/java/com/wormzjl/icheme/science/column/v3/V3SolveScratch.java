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
        /** The calculation's band factorizations by call site ({@link Factorization}). */
        private final int[] factorizations = new int[Factorization.values().length];
        /** The calculation's untruncated feed flashes, most recent last (P6). */
        private final java.util.ArrayList<FeedFlash> feedFlashes = new java.util.ArrayList<>();
        private int feedFlashesComputed, feedFlashesReused;
    }

    /**
     * One untruncated feed flash and what it depends on: the package's column identity (its records, F1), the feed's
     * temperature and flows, the feed tray's pressure.
     */
    private record FeedFlash(String identity, double temperature, double pressure, double[] feed,
            com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult result) {
        boolean answers(String identity, double temperature, double pressure, double[] feed) {
            return this.identity.equals(identity) && Double.compare(this.temperature, temperature) == 0
                    && Double.compare(this.pressure, pressure) == 0 && Arrays.equals(this.feed, feed);
        }
    }

    /** The most feed flashes a calculation keeps: one per feed-tray pressure its ramps and corrections visit. */
    private static final int FEED_FLASHES = 16;

    /**
     * The untruncated flash of the feed at the feed tray, computed once per calculation for each feed-tray pressure (P6 of
     * batch 2026-11-13-codebase-review). It depends on the package, the feed and that pressure only, takes no control (so
     * no checkpoint is skipped) and its result is immutable, so a repeat is the same object. Outside a calculation it is
     * computed each time; a refusal is never kept.
     */
    static com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult feedFlash(String identity, double temperature, double pressure,
            double[] feed, Supplier<com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult> flash) {
        var pool = OPEN.get();
        if (pool == null) return flash.get();
        for (var held : pool.feedFlashes) {
            if (held.answers(identity, temperature, pressure, feed)) {
                pool.feedFlashesReused++;
                return held.result();
            }
        }
        var result = flash.get();
        pool.feedFlashesComputed++;
        if (pool.feedFlashes.size() == FEED_FLASHES) pool.feedFlashes.removeFirst();
        pool.feedFlashes.add(new FeedFlash(identity, temperature, pressure, feed.clone(), result));
        return result;
    }

    /**
     * Where a calculation factors a band matrix (P7 of batch 2026-11-13-codebase-review): the Newton step on the coloured
     * finite-difference Jacobian, the local block Jacobian's step, the final Newton certificate's direct correction and its
     * damped normal-equation corrections, and the damped Gauss-Newton fallback. A count, not a time: it decides how much the
     * factorization itself is worth reusing.
     */
    enum Factorization {
        NEWTON("newton"), LOCAL_BLOCK("local block"), VERIFY_DIRECT("verify direct"), VERIFY_DAMPED("verify damped"),
        DAMPED_FALLBACK("damped fallback");

        private final String label;

        Factorization(String label) {
            this.label = label;
        }
    }

    /** Counts one band factorization at {@code site} in the open calculation; outside one it counts nothing. */
    static void factorized(Factorization site) {
        var pool = OPEN.get();
        if (pool != null) pool.factorizations[site.ordinal()]++;
    }

    /** The open calculation's factorizations so far, by {@link Factorization} ordinal; zeros outside a calculation. */
    static int[] factorizations() {
        var pool = OPEN.get();
        return pool == null ? new int[Factorization.values().length] : pool.factorizations.clone();
    }

    /** The open calculation's factorizations so far, all sites together. */
    static int totalFactorizations() {
        int total = 0;
        for (int count : factorizations()) total += count;
        return total;
    }

    /** The solver event of a calculation's factorizations: each site's count and the total. */
    static String factorizationEvent() {
        int[] counts = factorizations();
        var text = new StringBuilder("band factorizations:");
        int total = 0;
        for (var site : Factorization.values()) {
            text.append(' ').append(site.label).append(' ').append(counts[site.ordinal()]).append(',');
            total += counts[site.ordinal()];
        }
        text.append(" total ").append(total);
        // The pooled workspace's elimination (P8): an attempt nested in another works in a fresh set this leaves out.
        var pool = OPEN.get();
        if (pool != null) text.append("; feed flashes ").append(pool.feedFlashesComputed).append(" computed, ")
                .append(pool.feedFlashesReused).append(" reused");
        if (pool != null && pool.free != null) {
            var linear = pool.free.linear;
            text.append("; elimination updates ").append(linear.updatedEntries()).append(" of ").append(linear.fullWidthEntries())
                    .append(" at full width, full-width solves ").append(linear.fullWidthSolves());
        }
        return text.toString();
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
