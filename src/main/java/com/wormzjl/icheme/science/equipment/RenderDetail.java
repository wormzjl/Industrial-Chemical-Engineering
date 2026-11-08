package com.wormzjl.icheme.science.equipment;

/** Drawing only. Never selects placement, collision, connections or process geometry. */
public enum RenderDetail {
    NORMAL,
    LOW;

    /**
     * Idempotent cap keeps mating primitive edges on the same angular grid. Small pipe profiles
     * stay unchanged.
     */
    public int sides(int normal) {
        return this == LOW ? Math.min(normal, 24) : normal;
    }

    public int bands(int normal) {
        return this == LOW ? Math.min(normal, 6) : normal;
    }
}
