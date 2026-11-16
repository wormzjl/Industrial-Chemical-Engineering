package com.wormzjl.icheme.science.pollution;

/**
 * What the pollution step reads of the world, as an immutable snapshot the runtime builds on the server thread.
 * Implementations must be safe to read from a worker thread and must return the same answer for the same key during
 * one step.
 */
public interface Environment {
    /** The cell of a cube; never {@code null}. */
    Cell cell(long key);

    boolean raining();

    /** The lowest cube y of the dimension (inclusive); below it is a sink. */
    int minimumY();

    /** The highest cube y of the dimension (inclusive); above it is a sink. */
    int maximumY();

    /**
     * One cube as the step sees it.
     *
     * @param airShare the share of the cube's volume that holds air, in [0, 1]
     * @param open the cube is at or above the surface (ventilated)
     * @param loaded the cube's chunk is loaded; an unloaded cube keeps its amounts and passes nothing
     */
    record Cell(double airShare, boolean open, boolean loaded) {
        public static final Cell UNLOADED = new Cell(0, false, false);

        public Cell {
            if (!(airShare >= 0.0 && airShare <= 1.0)) {
                throw new IllegalArgumentException("airShare must be in [0, 1]: " + airShare);
            }
        }
    }
}
