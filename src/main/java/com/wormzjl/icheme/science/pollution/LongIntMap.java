package com.wormzjl.icheme.science.pollution;

/** A map from a long key to an int, open addressing, no boxing; -1 when absent. Nothing iterates it. */
final class LongIntMap {
    long[] keys;
    int[] values;
    boolean[] used;
    int size;

    /** Room for {@code expected} keys before it grows. */
    LongIntMap(int expected) {
        int capacity = Integer.highestOneBit(Math.max(32, 2 * expected - 1)) << 1;
        keys = new long[capacity];
        values = new int[capacity];
        used = new boolean[capacity];
    }

    int get(long key) {
        int mask = keys.length - 1;
        for (int h = mix(key) & mask; used[h]; h = (h + 1) & mask) {
            if (keys[h] == key) {
                return values[h];
            }
        }
        return -1;
    }

    void put(long key, int value) {
        if (2 * (size + 1) > keys.length) {
            grow();
        }
        int mask = keys.length - 1;
        int h = mix(key) & mask;
        while (used[h]) {
            if (keys[h] == key) {
                values[h] = value;
                return;
            }
            h = (h + 1) & mask;
        }
        used[h] = true;
        keys[h] = key;
        values[h] = value;
        size++;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        boolean[] oldUsed = used;
        keys = new long[oldKeys.length * 2];
        values = new int[oldKeys.length * 2];
        used = new boolean[oldKeys.length * 2];
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
