package com.wormzjl.icheme.science.pollution;

import java.util.Objects;

/**
 * An amount added to one cube's channel at the start of a step.
 *
 * @param key the cube ({@link CubeKey})
 * @param amount in air-mole equivalents (released moles over the compound's reference mole fraction), finite, at least 0
 */
public record Release(long key, Channel channel, double amount) {
    public Release {
        Objects.requireNonNull(channel, "channel");
        if (!(amount >= 0.0) || !Double.isFinite(amount)) {
            throw new IllegalArgumentException("release amount must be finite and not negative: " + amount);
        }
    }
}
