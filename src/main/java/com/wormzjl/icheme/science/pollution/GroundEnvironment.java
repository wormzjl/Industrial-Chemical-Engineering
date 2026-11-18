package com.wormzjl.icheme.science.pollution;

import java.util.Set;

/**
 * What the ground step reads of the world, as an immutable snapshot the runtime builds on the server thread (batch
 * 2026-11-18-ground-pollution). Columns are {@link MapColumns#column} keys. Safe to read from a worker; the same answer
 * for the same column during one step.
 */
public interface GroundEnvironment {
    /** The column's chunk is loaded; an unloaded column keeps its amounts and passes nothing. */
    boolean loaded(long column);

    /** The column's surface is mostly water: the ground spreads faster across its faces. */
    boolean water(long column);

    boolean raining();

    /** Every column loaded, none water. */
    static GroundEnvironment open(boolean raining) {
        return of(null, Set.of(), raining);
    }

    /**
     * {@code loaded} the loaded columns ({@code null}: every column), {@code water} the water columns.
     */
    static GroundEnvironment of(Set<Long> loaded, Set<Long> water, boolean raining) {
        var loadedCopy = loaded == null ? null : Set.copyOf(loaded);
        var waterCopy = Set.copyOf(water);
        return new GroundEnvironment() {
            @Override public boolean loaded(long column) {
                return loadedCopy == null || loadedCopy.contains(column);
            }

            @Override public boolean water(long column) {
                return waterCopy.contains(column);
            }

            @Override public boolean raining() {
                return raining;
            }
        };
    }
}
