package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.DeviceName;

import net.minecraft.nbt.CompoundTag;

import java.util.*;

/**
 * An alarm beacon's settings (batch 2026-11-15-fluid-interfaces, W9, decisions A2 and A6): a fact of the control store. Its
 * condition is an input on its data bus compared {@link Comparison#ABOVE} or {@link Comparison#BELOW} a threshold, in
 * {@code unit} (an alarm channel needs neither: active while raised), or {@link Source#ANY_ALARM}, any equipment alarm a
 * host on its bus raises. Enums are saved by name.
 */
public record AlarmSettings(
        String name,
        boolean enabled,
        Source source,
        UUID sensor,
        Sensor.Unit unit,
        Comparison comparison,
        double threshold) {
    /** What the beacon watches. */
    public enum Source {
        INPUT,
        ANY_ALARM
    }

    /** Which side of its threshold an input raises the alarm on. */
    public enum Comparison {
        ABOVE,
        BELOW
    }

    public static final AlarmSettings EMPTY =
            new AlarmSettings("", false, Source.INPUT, null, Sensor.Unit.METRE, Comparison.ABOVE, 0);

    private static final Set<String> FIELDS =
            Set.of("name", "enabled", "source", "unit", "comparison", "threshold");

    public AlarmSettings {
        name = DeviceName.clean(Objects.requireNonNull(name));
        Objects.requireNonNull(source);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(comparison);
        if (!Double.isFinite(threshold)
                || source == Source.ANY_ALARM && sensor != null
                || enabled && source == Source.INPUT && sensor == null)
            throw new IllegalArgumentException("Invalid alarm settings");
    }

    public CompoundTag save() {
        var t = new CompoundTag();
        t.putString("name", name);
        t.putBoolean("enabled", enabled);
        t.putString("source", source.name());
        if (sensor != null) t.putUUID("sensor", sensor);
        t.putString("unit", unit.name());
        t.putString("comparison", comparison.name());
        t.putDouble("threshold", threshold);
        return t;
    }

    public static AlarmSettings load(CompoundTag t) {
        var keys = new HashSet<>(t.getAllKeys());
        keys.remove("sensor");
        if (!keys.equals(FIELDS)
                || !t.contains("name", 8)
                || !t.contains("enabled", 1)
                || !t.contains("source", 8)
                || !t.contains("unit", 8)
                || !t.contains("comparison", 8)
                || !t.contains("threshold", 6)
                || t.contains("sensor") && !t.hasUUID("sensor"))
            throw new IllegalArgumentException("Damaged alarm settings");
        if (!DeviceName.clean(t.getString("name")).equals(t.getString("name")))
            throw new IllegalArgumentException("Damaged alarm name");
        return new AlarmSettings(
                t.getString("name"),
                t.getBoolean("enabled"),
                Source.valueOf(t.getString("source")),
                t.hasUUID("sensor") ? t.getUUID("sensor") : null,
                Sensor.Unit.valueOf(t.getString("unit")),
                Comparison.valueOf(t.getString("comparison")),
                t.getDouble("threshold"));
    }
}
