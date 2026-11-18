package com.wormzjl.icheme.runtime.fluid;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;

/**
 * The water body rule of a water intake (batch 2026-11-18-water-intake-outfall, owner: "A real water body"): the intake
 * gives water only while one of its faces touches world water that belongs to a connected body of at least
 * {@link #WATER_BODY} water blocks. The count is a bounded flood fill through face-adjacent water blocks that stops as soon
 * as it has found {@link #WATER_BODY}; it removes no block and reads no biome. What counts as water is the probe's: in the
 * world, a block whose fluid is in the {@code minecraft:water} tag (a source, a flowing block or a waterlogged block) in a
 * loaded chunk; a block in a chunk that is not loaded is no water.
 * <p>
 * Each face's water is its own body: two pools that meet only at the intake are two bodies (the intake is no water). Bound:
 * the intake's six faces are read first; each face's fill takes fewer than {@link #WATER_BODY} blocks before it either
 * stops or ends, and reads each one's six neighbours once: at most {@link #MAX_READS} = 6 + 6 × 6 × 100 = 3 606 block reads
 * per count (606 for one body), whatever the world holds. A fact about
 * the world, counted on the server thread by the block (at placement, on a neighbour's change and on a long recheck), never
 * on the engine's schedule or inside a solve. Pure: no Minecraft types.
 */
public final class WaterBody {
    /** The least connected water blocks that make a water body (owner, 2026-11-18). */
    public static final int WATER_BODY = 100;
    /** The most block reads one count makes: the intake's six faces, then six per water block found, for at most six bodies. */
    public static final int MAX_READS = 6 + 36 * WATER_BODY;
    private static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private WaterBody() {}

    /** Whether the block at a position holds water that counts. */
    @FunctionalInterface
    public interface Probe {
        boolean water(int x, int y, int z);
    }

    /** One count: whether it found a body, the most water blocks it found in one body (at most {@link #WATER_BODY}) and how many blocks it read. */
    public record Count(boolean body, int blocks, int reads) {}

    /**
     * Whether the intake at {@code (x, y, z)} touches a water body: a flood fill from the water on its faces (every face:
     * a joined pipe is no water), through face-adjacent water, stopping at {@link #WATER_BODY} blocks.
     */
    public static Count count(int x, int y, int z, Probe probe) {
        Objects.requireNonNull(probe);
        var seen = new HashSet<Long>();
        seen.add(key(x, y, z));
        var seeds = new java.util.ArrayList<int[]>(6);
        int reads = 0, largest = 0;
        for (var f : FACES) {
            int a = x + f[0], b = y + f[1], c = z + f[2];
            reads++;
            if (probe.water(a, b, c)) seeds.add(new int[]{a, b, c});
        }
        var queue = new ArrayDeque<int[]>();
        for (var seed : seeds) {
            // A seed a fill before already reached belongs to that body.
            if (!seen.add(key(seed[0], seed[1], seed[2]))) continue;
            int found = 1;
            if (found >= WATER_BODY) return new Count(true, found, reads);
            queue.clear();
            queue.add(seed);
            while (!queue.isEmpty()) {
                var p = queue.poll();
                for (var f : FACES) {
                    int a = p[0] + f[0], b = p[1] + f[1], c = p[2] + f[2];
                    if (!seen.add(key(a, b, c))) continue;
                    reads++;
                    if (probe.water(a, b, c)) {
                        queue.add(new int[]{a, b, c});
                        if (++found >= WATER_BODY) return new Count(true, found, reads);
                    }
                }
            }
            largest = Math.max(largest, found);
        }
        return new Count(false, largest, reads);
    }

    /** A position as one long: 26 bits of x and z, 12 of y, as {@code BlockPos.asLong} packs it. */
    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (long) y & 0xFFFL;
    }
}
