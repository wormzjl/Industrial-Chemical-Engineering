package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.Condition;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * An equipment alarm (batches 2026-11-09-furnace-fuel-power and 2026-11-09-flare-stack): a fired heater's, a flare's, a batch still's or a
 * powered unit's or a running column's, by name; the control system lists the active ones and reads each as a built-in input channel
 * ({@code alarm_<name>}, 1 active, 0 clear). Its text is the status word, kept in English.
 */
public enum EquipmentAlarm {
    NO_POWER("NO POWER"), NO_FUEL("NO FUEL"), LOW_FUEL("LOW FUEL"), NOT_COMBUSTIBLE("FUEL NOT COMBUSTIBLE"),
    FLAME_OUT("FLAME OUT"), OVER_CAPACITY("OVER CAPACITY"), BLOCKED("BLOCKED"),
    // A batch still's (batch 2026-11-09-batch-distiller, W3).
    OVERHEAT("OVERHEAT"), BOIL_DRY("BOIL DRY"), FLOODING("FLOODING"), COOLING_LIMITED("COOLING LIMITED"),
    // A running column's (batch 2026-11-10-column-failure-modes, W6.1).
    LOW_FEED("LOW FEED"), LOW_STEAM("LOW STEAM"), HEATING_LIMITED("HEATING LIMITED"), OFF_TABLE("OFF TABLE"), UPSET("UPSET");

    public final String text;

    EquipmentAlarm(String text) {
        this.text = text;
    }

    /** A fired heater's alarms, and a flare's. */
    public static final List<EquipmentAlarm> HEATER = List.of(NO_POWER, NO_FUEL, LOW_FUEL, NOT_COMBUSTIBLE), FLARE = List.of(FLAME_OUT, OVER_CAPACITY, BLOCKED);
    /** A batch still's alarms (batch 2026-11-09-batch-distiller): NO POWER for an electric one, NO FUEL for a fired one. */
    public static final List<EquipmentAlarm> STILL = List.of(NO_POWER, NO_FUEL, OVERHEAT, BOIL_DRY, FLOODING, COOLING_LIMITED);
    /** A running column's alarms (batch 2026-11-10-column-failure-modes, W6.1). */
    public static final List<EquipmentAlarm> COLUMN = List.of(UPSET, OFF_TABLE, LOW_FEED, LOW_STEAM, BLOCKED, COOLING_LIMITED, HEATING_LIMITED, FLOODING, NO_POWER);

    /** The alarm a column's condition raises, or null: a pending solve, a refused set point and the notices raise none. */
    public static EquipmentAlarm of(Condition condition) {
        return switch (condition) {
            case FEED_SHORT -> LOW_FEED;
            case STEAM_SHORT -> LOW_STEAM;
            case PRODUCT_FULL -> BLOCKED;
            case COOLING_SHORT, PUMPAROUND_SHORT -> COOLING_LIMITED;
            case HEATING_SHORT -> HEATING_LIMITED;
            case FLOODING -> FLOODING;
            case NO_POWER -> NO_POWER;
            case SOLVE_REFUSED -> OFF_TABLE;
            case CLOSURE -> UPSET;
            case OFF_TABLE, SETPOINT_REFUSED, ADVISORY, FEED_WATER_ROUTED, UNMETERED -> null;
        };
    }

    /** The alarms a column's conditions raise. */
    public static Set<EquipmentAlarm> column(List<Condition.Active> conditions) {
        var out = EnumSet.noneOf(EquipmentAlarm.class);
        for (var c : conditions) {
            var alarm = of(c.condition());
            if (alarm != null) out.add(alarm);
        }
        return out;
    }
}
