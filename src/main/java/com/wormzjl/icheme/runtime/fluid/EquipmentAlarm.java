package com.wormzjl.icheme.runtime.fluid;

import java.util.List;

/**
 * An equipment alarm (batches 2026-11-09-furnace-fuel-power and 2026-11-09-flare-stack): a fired heater's, a flare's or a
 * powered unit's, by name; the control system lists the active ones and reads each as a built-in input channel
 * ({@code alarm_<name>}, 1 active, 0 clear). Its text is the status word, kept in English.
 */
public enum EquipmentAlarm {
    NO_POWER("NO POWER"), NO_FUEL("NO FUEL"), LOW_FUEL("LOW FUEL"), NOT_COMBUSTIBLE("FUEL NOT COMBUSTIBLE"),
    FLAME_OUT("FLAME OUT"), OVER_CAPACITY("OVER CAPACITY"), BLOCKED("BLOCKED");

    public final String text;

    EquipmentAlarm(String text) {
        this.text = text;
    }

    /** A fired heater's alarms, and a flare's. */
    public static final List<EquipmentAlarm> HEATER = List.of(NO_POWER, NO_FUEL, LOW_FUEL, NOT_COMBUSTIBLE), FLARE = List.of(FLAME_OUT, OVER_CAPACITY, BLOCKED);
}
