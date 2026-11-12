package com.wormzjl.icheme.science.tutorial.guide;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a guide step asks of a still the player watches (batch 2026-11-12-tutorial-system, P2b; criterion
 * {@code icheme:still_state}): optional statuses (the still law's status names), a least filled share of the pot, a
 * least pot temperature (K), and liquid at the spout (the view's distillate flow above zero: a head that only vents its
 * gas gives none). A condition left out holds. The rule reads only what the published view carries; no
 * Minecraft type, so it is tested on its own.
 */
public record StillCondition(Optional<List<String>> status, Optional<Double> minFilled, Optional<Double> minPotTemperature, Optional<Boolean> liquid) {
    /** The statuses of a still that is firing: heating, boiling, or taking product over (a limited condenser still distils). */
    public static final Set<String> FIRING = Set.of("HEATING", "BOILING", "DISTILLING", "COOLING_LIMITED");

    public StillCondition {
        status = status.map(List::copyOf);
        if (minFilled.isPresent() && !(minFilled.get() >= 0 && minFilled.get() <= 1)) throw new IllegalArgumentException("A filled share lies between 0 and 1");
        if (minPotTemperature.isPresent() && !(minPotTemperature.get() > 0)) throw new IllegalArgumentException("A pot temperature is above 0 K");
    }

    /**
     * Whether a still's published view meets the condition: its {@code status}, its {@code filled} share of the pot, its
     * {@code potTemperature} (K; null before the first answer, which meets no temperature condition) and its {@code
     * distillate} flow to the spout (kg/s).
     */
    public boolean matches(String status, double filled, @Nullable Double potTemperature, double distillate) {
        if (this.status.isPresent() && !this.status.get().contains(status)) return false;
        if (minFilled.isPresent() && !(filled >= minFilled.get())) return false;
        if (liquid.isPresent() && liquid.get() != distillate > 0) return false;
        return minPotTemperature.isEmpty() || potTemperature != null && potTemperature >= minPotTemperature.get();
    }

    /** Whether a still whose last published status is {@code status} was firing: the stop step's test when its fire is set off. */
    public static boolean firing(@Nullable String status) {
        return status != null && FIRING.contains(status);
    }
}
