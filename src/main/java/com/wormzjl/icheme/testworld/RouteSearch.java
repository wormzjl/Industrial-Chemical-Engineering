package com.wormzjl.icheme.testworld;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One route search of a {@link PlantBuilder} ({@link PlantBuilder#route(BlockPos, BlockPos, Set)}) on a flat grid over the
 * plant's box and one cell round it: each cell's state ({@link PlantBuilder#free}) read from the level once per search, the
 * search's states packed in arrays. It visits the cells in the same order as a search over block positions with a
 * {@link java.util.ArrayDeque} (the shortest line) or a {@link java.util.PriorityQueue} ordered by cost (the least-cost line),
 * so it lays the same line, block for block; the heap below sifts as {@link java.util.PriorityQueue} does.
 */
final class RouteSearch {
    private static final Direction[] DIRECTIONS = Direction.values();
    /** A cell's flags: read; a pipe may stand there (in the box, air, no device); a nozzle's pipe block; a device or a nozzle's pipe block. */
    private static final byte READ = 1, OPEN = 2, RESERVED = 4, TAKEN = 8;
    /** States per cell: the first pipe's (no heading) and one per direction. */
    private static final int HEADINGS = 7;

    private final PlantBuilder plant;
    private final BlockPos from, to;
    private final Set<BlockPos> allowed;
    private final int x0, y0, z0, nx, ny, nz, cells;
    private final int[] step = new int[6];
    private final byte[] flags;

    RouteSearch(PlantBuilder plant, BlockPos from, BlockPos to, Set<BlockPos> allowed) {
        this.plant = plant;
        this.from = from;
        this.to = to;
        this.allowed = allowed;
        // A pipe stands only in the box; the cells next to it, one further out, are read for what it would join.
        x0 = plant.low.getX() - 1;
        y0 = plant.low.getY() - 1;
        z0 = plant.low.getZ() - 1;
        nx = plant.high.getX() - plant.low.getX() + 3;
        ny = plant.high.getY() - plant.low.getY() + 3;
        nz = plant.high.getZ() - plant.low.getZ() + 3;
        long n = (long) nx * ny * nz;
        if (nx <= 0 || ny <= 0 || nz <= 0 || n * HEADINGS > Integer.MAX_VALUE) throw new IllegalStateException("The plant's box is too large to route in: " + nx + "x" + ny + "x" + nz);
        cells = (int) n;
        for (int d = 0; d < 6; d++) step[d] = (DIRECTIONS[d].getStepX() * ny + DIRECTIONS[d].getStepY()) * nz + DIRECTIONS[d].getStepZ();
        flags = new byte[cells];
    }

    /** The shortest line, as a breadth-first search over block positions in {@link Direction} order finds it. */
    List<BlockPos> shortest() {
        int f = index(from), t = index(to);
        if (f < 0) return unreachable();
        var previous = new int[cells];
        Arrays.fill(previous, -1);
        var queue = new int[cells];
        int head = 0, tail = 0;
        previous[f] = f;
        queue[tail++] = f;
        while (head < tail && (t < 0 || previous[t] < 0)) {
            int at = queue[head++];
            for (int d = 0; d < 6; d++) {
                int n = neighbour(at, d);
                if (n < 0 || previous[n] >= 0 || !free(n)) continue;
                previous[n] = at;
                queue[tail++] = n;
            }
        }
        if (t < 0 || previous[t] < 0) throw noRoute();
        var path = new ArrayList<BlockPos>();
        for (int at = t; ; at = previous[at]) {
            path.add(position(at));
            if (at == f) break;
        }
        Collections.reverse(path);
        return path;
    }

    /** The least-cost line, a pipe 1 and a bend {@code bendCost}, over (block, heading), the first pipe free to leave any way. */
    List<BlockPos> leastCost(int bendCost) {
        int f = index(from), t = index(to);
        if (f < 0) return unreachable();
        int states = cells * HEADINGS;
        var best = new int[states];
        Arrays.fill(best, Integer.MAX_VALUE);
        var previous = new int[states];
        var heap = new Heap();
        int first = f * HEADINGS;
        best[first] = 0;
        heap.offer(0, first);
        int end = -1;
        while (heap.size > 0) {
            long node = heap.poll();
            int s = (int) node, cost = (int) (node >>> 32);
            if (best[s] < cost) continue;
            int cell = s / HEADINGS, heading = s % HEADINGS - 1;
            if (cell == t) {
                end = s;
                break;
            }
            for (int d = 0; d < 6; d++) {
                int n = neighbour(cell, d);
                if (n < 0 || !free(n)) continue;
                int c = cost + 1 + (heading < 0 || heading == d ? 0 : bendCost);
                int next = n * HEADINGS + d + 1;
                if (c < best[next]) {
                    best[next] = c;
                    previous[next] = s;
                    heap.offer(c, next);
                }
            }
        }
        if (end < 0) throw noRoute();
        var path = new ArrayList<BlockPos>();
        for (int s = end; ; s = previous[s]) {
            path.add(position(s / HEADINGS));
            if (s == first) break;
        }
        Collections.reverse(path);
        return path;
    }

    /** A start more than a cell outside the box: no pipe beside it may stand, so only a line of itself to itself. */
    private List<BlockPos> unreachable() {
        if (from.equals(to)) return new ArrayList<>(List.of(from));
        throw noRoute();
    }

    private IllegalStateException noRoute() {
        return new IllegalStateException("No route from " + from.toShortString() + " to " + to.toShortString());
    }

    /** {@link PlantBuilder#free(BlockPos, Set)} of a cell of the grid. */
    private boolean free(int cell) {
        int f = flags(cell);
        if ((f & OPEN) == 0) return false;
        if ((f & RESERVED) != 0 && !allowed.contains(position(cell))) return false;
        // An open cell is in the box: its neighbours are on the grid.
        for (int d = 0; d < 6; d++) {
            int n = cell + step[d];
            if ((flags(n) & TAKEN) != 0 && !allowed.contains(position(n))) return false;
        }
        return true;
    }

    private int flags(int cell) {
        int f = flags[cell];
        if (f != 0) return f;
        var at = position(cell);
        boolean device = plant.devices.contains(at), reserved = plant.reserved.contains(at);
        f = READ;
        if (plant.inside(at) && !device && plant.level.getBlockState(at).isAir()) f |= OPEN;
        if (reserved) f |= RESERVED;
        if (device || reserved) f |= TAKEN;
        flags[cell] = (byte) f;
        return f;
    }

    /** The cell next to {@code cell} along direction {@code d}, or -1 off the grid. */
    private int neighbour(int cell, int d) {
        if ((flags(cell) & OPEN) != 0) return cell + step[d];
        // The start (every other cell searched from is open): it may lie on the grid's edge.
        return index(position(cell).relative(DIRECTIONS[d]));
    }

    private int index(BlockPos at) {
        int x = at.getX() - x0, y = at.getY() - y0, z = at.getZ() - z0;
        if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) return -1;
        return (x * ny + y) * nz + z;
    }

    private BlockPos position(int cell) {
        int z = cell % nz, rest = cell / nz;
        return new BlockPos(rest / ny + x0, rest % ny + y0, z + z0);
    }

    /**
     * A binary min-heap of (cost, state) by cost alone, its sifts those of {@link java.util.PriorityQueue} with a comparator,
     * so states of equal cost leave in the same order.
     */
    private static final class Heap {
        private long[] queue = new long[1024];
        private int size;

        void offer(int cost, int state) {
            long x = (long) cost << 32 | state;
            if (size == queue.length) queue = Arrays.copyOf(queue, size * 2);
            int k = size++;
            while (k > 0) {
                int parent = (k - 1) >>> 1;
                long e = queue[parent];
                if (cost(x) >= cost(e)) break;
                queue[k] = e;
                k = parent;
            }
            queue[k] = x;
        }

        long poll() {
            long result = queue[0];
            int n = --size;
            long x = queue[n];
            if (n > 0) {
                int k = 0, half = n >>> 1;
                while (k < half) {
                    int child = (k << 1) + 1;
                    long c = queue[child];
                    int right = child + 1;
                    if (right < n && cost(c) > cost(queue[right])) c = queue[child = right];
                    if (cost(x) <= cost(c)) break;
                    queue[k] = c;
                    k = child;
                }
                queue[k] = x;
            }
            return result;
        }

        private static int cost(long node) {
            return (int) (node >>> 32);
        }
    }
}
