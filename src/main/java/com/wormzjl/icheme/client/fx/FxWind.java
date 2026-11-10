package com.wormzjl.icheme.client.fx;

import net.minecraft.client.Minecraft;

/**
 * The one presentation wind for every effect (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.1, owner decision X3 A): it bends
 * flames (API 521's u∞/Uj) and carries smoke and plumes. Deterministic from game time and the dimension id, so every
 * client sees the same wind without any network traffic, and it freezes while the game is paused. Visual only: the
 * server's process calculations never read it.
 * <p>The direction turns slowly, as two sines of 30 and about 12 minutes' period sum to a swing of up to ±0.23 turn (83°), at most about 0.4° per second,
 * about a heading fixed per dimension. The speed stays within {@link #MIN_SPEED} to {@link #MAX_SPEED}: a 15-minute
 * and a 5.6-minute sine. All periods are in game time.
 */
public final class FxWind {
    /** Wind speed bounds, m/s: a light to a moderate breeze at stack height. */
    public static final double MIN_SPEED = 2, MAX_SPEED = 8;
    private static final double TURN_A = 1800, TURN_B = 730, GUST_A = 900, GUST_B = 337;

    private FxWind() {
    }

    /** A horizontal wind: the velocity's x and z, m/s (world axes, +x east, +z south). */
    public record Wind(double x, double z) {
        public double speed() {
            return Math.hypot(x, z);
        }
    }

    /** The wind in the client's current dimension at game time {@code gameTime} ticks plus {@code partialTick}. */
    public static Wind vector(long gameTime, float partialTick) {
        var level = Minecraft.getInstance().level;
        String dimension = level == null ? "minecraft:overworld" : level.dimension().location().toString();
        return vector(dimension, FxGlide.seconds(gameTime, partialTick));
    }

    /** The wind in {@code dimension} at {@code seconds} of game time. */
    public static Wind vector(String dimension, double seconds) {
        // String.hashCode is specified by the language, so the phases are the same on every client and JVM.
        int hash = dimension.hashCode();
        double heading = 2 * Math.PI * phase(hash, 0);
        double turn = 2 * Math.PI * (.15 * Math.sin(2 * Math.PI * (seconds / TURN_A + phase(hash, 1)))
                + .08 * Math.sin(2 * Math.PI * (seconds / TURN_B + phase(hash, 2))));
        double gust = .6 * Math.sin(2 * Math.PI * (seconds / GUST_A + phase(hash, 3)))
                + .4 * Math.sin(2 * Math.PI * (seconds / GUST_B + phase(hash, 4)));
        double mid = (MIN_SPEED + MAX_SPEED) / 2, half = (MAX_SPEED - MIN_SPEED) / 2;
        double speed = mid + half * gust;
        double angle = heading + turn;
        return new Wind(speed * Math.cos(angle), speed * Math.sin(angle));
    }

    /** A fixed share in [0, 1) from the dimension's hash and a slot. */
    private static double phase(int hash, int slot) {
        long mixed = hash * 0x9E3779B97F4A7C15L + slot * 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 31;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 29;
        return (mixed >>> 11) * 0x1.0p-53;
    }
}
