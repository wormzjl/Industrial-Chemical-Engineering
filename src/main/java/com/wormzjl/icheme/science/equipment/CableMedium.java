package com.wormzjl.icheme.science.equipment;

/**
 * Saved by name. Both metallic cable enclosures use the same fixed outside diameter; the colour is
 * the medium's identification (blue data, orange FE).
 */
public enum CableMedium {
    DATA(0x69b9db),
    POWER(0xd89455);

    /** The outside diameter of either conduit, m. */
    public static final double OUTSIDE = 1 / 8.0;

    private final int colour;

    CableMedium(int colour) {
        this.colour = colour;
    }

    public double outside() {
        return OUTSIDE;
    }

    public int colour() {
        return colour;
    }
}
