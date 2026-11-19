package com.wormzjl.icheme.science.pollution;

import java.util.Map;
import java.util.Objects;

/** An immutable {@link Environment}: a map of known cells, one cell for every other key, a rain flag and a y range. */
public final class MapEnvironment implements Environment {
    private final Map<Long, Cell> cells;
    private final Cell fallback;
    private final boolean raining;
    private final int minimumY;
    private final int maximumY;

    public MapEnvironment(Map<Long, Cell> cells, Cell fallback, boolean raining, int minimumY, int maximumY) {
        this.cells = Map.copyOf(Objects.requireNonNull(cells, "cells"));
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        if (minimumY > maximumY) {
            throw new IllegalArgumentException("minimumY " + minimumY + " above maximumY " + maximumY);
        }
        this.raining = raining;
        this.minimumY = minimumY;
        this.maximumY = maximumY;
    }

    /** Every cube has the same cell. */
    public static MapEnvironment uniform(Cell cell, boolean raining, int minimumY, int maximumY) {
        return new MapEnvironment(Map.of(), cell, raining, minimumY, maximumY);
    }

    @Override
    public Cell cell(long key) {
        Cell cell = cells.get(key);
        return cell != null ? cell : fallback;
    }

    @Override
    public boolean raining() {
        return raining;
    }

    @Override
    public int minimumY() {
        return minimumY;
    }

    @Override
    public int maximumY() {
        return maximumY;
    }
}
