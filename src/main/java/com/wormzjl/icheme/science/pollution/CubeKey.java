package com.wormzjl.icheme.science.pollution;

/**
 * The key of a pollution cube: a 16 m cube, one chunk section. The packing equals Minecraft 1.21.1
 * {@code SectionPos.asLong(x, y, z)}: 22 bits of x at bit 42, 22 bits of z at bit 20, 20 bits of y at bit 0, each in
 * two's complement; the unpacking sign-extends ({@code SectionPos.x/y/z}). The runtime may therefore pass section keys
 * straight through. Coordinates are section coordinates (block coordinate {@code >> 4}).
 */
public final class CubeKey {
    /** The blocks of one cube (a 16 × 16 × 16 section). */
    public static final int SECTION_BLOCKS = 4096;

    private CubeKey() {
    }

    public static long of(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42 | ((long) y & 0xFFFFFL) | ((long) z & 0x3FFFFFL) << 20;
    }

    public static int x(long key) {
        return (int) (key >> 42);
    }

    public static int y(long key) {
        return (int) (key << 44 >> 44);
    }

    public static int z(long key) {
        return (int) (key << 22 >> 42);
    }

    /** Whether the cube's y lies in {@code minimumY..maximumY}, the dimension's lowest and highest cube. */
    public static boolean inside(long key, int minimumY, int maximumY) {
        int y = y(key);
        return y >= minimumY && y <= maximumY;
    }

    public static long offset(long key, int dx, int dy, int dz) {
        return of(x(key) + dx, y(key) + dy, z(key) + dz);
    }
}
