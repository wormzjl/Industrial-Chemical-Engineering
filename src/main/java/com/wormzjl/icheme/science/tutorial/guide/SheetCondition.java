package com.wormzjl.icheme.science.tutorial.guide;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;

import java.util.Optional;

/**
 * What a guide step asks of a sheet a player stored with its run's results (batch 2026-11-12-tutorial-system, P4b; criterion
 * {@code icheme:sheet_run}): results that ran on the stored sheet itself, and optionally a fired heater set to an outlet
 * temperature of at most {@code maxHeaterOutlet} K: any fired heater, or (P5 stage B) only the unit {@code heater} names, since
 * the small unit's steam heater H-102 stands at 533 K whatever H-101 does. It reads the stored sheet's settings only; it
 * solves nothing. No Minecraft type, so it is tested on its own.
 */
public record SheetCondition(Optional<Double> maxHeaterOutlet, Optional<String> heater) {
    public SheetCondition(Optional<Double> maxHeaterOutlet) {
        this(maxHeaterOutlet, Optional.empty());
    }

    /** The simulator's fired heater's type string. */
    public static final String FIRED_HEATER = "fired_heater";

    public SheetCondition {
        if (maxHeaterOutlet.isPresent() && !(maxHeaterOutlet.get() > 0)) throw new IllegalArgumentException("A heater outlet is above 0 K");
    }

    /** Whether {@code sheet}, stored with results that {@code ran} on it, meets the condition. */
    public boolean matches(Flowsheet sheet, boolean ran) {
        if (!ran) return false;
        if (maxHeaterOutlet.isEmpty()) return true;
        for (var unit : sheet.units()) {
            if (!unit.type().equals(FIRED_HEATER) || heater.isPresent() && !heater.get().equals(unit.id())) continue;
            var target = unit.params().json().get("target");
            if (target == null || !target.isJsonPrimitive() || !target.getAsString().equals("outlet")) continue;
            double outlet = unit.params().number("temperature", Double.NaN);
            if (outlet <= maxHeaterOutlet.get()) return true;
        }
        return false;
    }
}
