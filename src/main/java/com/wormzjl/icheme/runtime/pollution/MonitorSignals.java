package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.Exposure;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * What a fixed pollution monitor shows (batch 2026-11-15-pollution-system, WP10; plan section 11): its lamp and its gas
 * alarm, from the four indices of its cube. Pure apart from {@link #indices}, a read of the installed field.
 */
public final class MonitorSignals {
    private static final Channel[] CHANNELS = Channel.values();

    private MonitorSignals() {
    }

    /** The lamp's colour, a blockstate property ({@code lamp}). Saved by name in the chunk; state, not a fact. */
    public enum Lamp implements StringRepresentable {
        /** Pollution is off or not started. */
        DARK,
        /** Every index below 0.1. */
        GREEN,
        /** Some index at or above 0.1. */
        AMBER,
        /** Some index at or above 1: the horn sounds. */
        RED;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The gas alarm channel: its reading is the ordinal (0 clear, 1 GAS, 2 GAS HIGH); its word on the DCS is
     * {@code gui.icheme.control.alarm.<name>}.
     */
    public enum Alarm {
        CLEAR,
        /** Some index at or above 0.1 ({@link Exposure#LOW}). */
        GAS,
        /** Some index at or above 1 ({@link Exposure#HIGH}). */
        GAS_HIGH
    }

    /** The worst level of the four indices; NaN and negative read as NONE. */
    public static Exposure.Level worst(double[] indices) {
        var worst = Exposure.Level.NONE;
        for (double index : indices) {
            var level = Exposure.level(index);
            if (level.compareTo(worst) > 0) {
                worst = level;
            }
        }
        return worst;
    }

    /** The lamp: dark while pollution is off, else green, amber at 0.1 and red at 1. */
    public static Lamp lamp(boolean enabled, double[] indices) {
        if (!enabled) {
            return Lamp.DARK;
        }
        var worst = worst(indices);
        return worst.atLeast(Exposure.Level.HIGH) ? Lamp.RED : worst.atLeast(Exposure.Level.LOW) ? Lamp.AMBER : Lamp.GREEN;
    }

    /** The gas alarm of the four indices. */
    public static Alarm alarm(double[] indices) {
        var worst = worst(indices);
        return worst.atLeast(Exposure.Level.HIGH) ? Alarm.GAS_HIGH : worst.atLeast(Exposure.Level.LOW) ? Alarm.GAS : Alarm.CLEAR;
    }

    /** Whether the service runs: false when it is missing or turned itself off. */
    public static boolean enabled(PollutionService service) {
        return service != null && service.enabled();
    }

    /**
     * The four indices of the cube that holds {@code position}, in {@link Channel} order: a read of the installed field
     * (nothing steps); 0 each when the service is missing or off.
     */
    public static double[] indices(PollutionService service, ResourceKey<Level> dimension, BlockPos position) {
        var out = new double[CHANNELS.length];
        if (!enabled(service)) {
            return out;
        }
        for (var channel : CHANNELS) {
            double value = service.index(dimension, position, channel);
            out[channel.ordinal()] = Double.isFinite(value) && value > 0 ? value : 0;
        }
        return out;
    }
}
