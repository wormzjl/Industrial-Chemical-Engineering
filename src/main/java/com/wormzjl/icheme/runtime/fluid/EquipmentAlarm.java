package com.wormzjl.icheme.runtime.fluid;

import java.util.List;

/**
 * An equipment alarm (batches 2026-11-09-furnace-fuel-power and 2026-11-09-flare-stack): a fired heater's, a flare's, a batch still's or a
 * powered unit's, by name; the control system lists the active ones and reads each as a built-in input channel
 * ({@code alarm_<name>}, 1 active, 0 clear). Its text is the status word, kept in English.
 */
public enum EquipmentAlarm {
    NO_POWER("NO POWER"), NO_FUEL("NO FUEL"), LOW_FUEL("LOW FUEL"), NOT_COMBUSTIBLE("FUEL NOT COMBUSTIBLE"),
    FLAME_OUT("FLAME OUT"), OVER_CAPACITY("OVER CAPACITY"), BLOCKED("BLOCKED"),
    // A batch still's (batch 2026-11-09-batch-distiller, W3).
    OVERHEAT("OVERHEAT"), BOIL_DRY("BOIL DRY"), FLOODING("FLOODING"), COOLING_LIMITED("COOLING LIMITED"),
    // A chimney's (batch 2026-11-10-chimney): liquid in the stack, nothing drawn while gas stands at its inlet.
    WET_STACK("WET STACK"), NO_DRAFT("NO DRAFT");

    public final String text;

    EquipmentAlarm(String text) {
        this.text = text;
    }

    /** A fired heater's alarms, and a flare's. */
    public static final List<EquipmentAlarm> HEATER = List.of(NO_POWER, NO_FUEL, LOW_FUEL, NOT_COMBUSTIBLE), FLARE = List.of(FLAME_OUT, OVER_CAPACITY, BLOCKED);
    /** A batch still's alarms (batch 2026-11-09-batch-distiller): NO POWER for an electric one, NO FUEL for a fired one. */
    public static final List<EquipmentAlarm> STILL = List.of(NO_POWER, NO_FUEL, OVERHEAT, BOIL_DRY, FLOODING, COOLING_LIMITED);
    /** A chimney's alarms (batch 2026-11-10-chimney): ABOVE RATING reads as OVER CAPACITY. */
    public static final List<EquipmentAlarm> CHIMNEY = List.of(OVER_CAPACITY, WET_STACK, NO_DRAFT);
}
