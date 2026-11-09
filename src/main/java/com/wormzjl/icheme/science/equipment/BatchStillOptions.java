package com.wormzjl.icheme.science.equipment;

import java.util.Locale;
import java.util.Objects;

/**
 * A batch still's options (batch 2026-11-09-batch-distiller, W2; shape fields 11), facts of its shape: how its pot is
 * heated, whether it has its own water-cooled condenser, and its column's stages (0: none, a plain pot still). Every
 * other kind carries {@link #DEFAULT}. Enums are saved by name.
 *
 * <p>A column needs the built-in condenser: its reflux runs back from that condenser by gravity (owner question O1 of
 * {@code BATCH_STILL_STRUCTURE.md}); a still without a condenser sends its vapour out of its {@code VAPOUR_OUTLET} to the
 * player's condenser.
 */
public record BatchStillOptions(Heater heater, Condenser condenser, int stages) {
    /** The fewest and most column stages (owner, BATCH_DISTILLER_PLAN.md section 4). */
    public static final int MIN_STAGES = 2, MAX_STAGES = 8;

    /** A solid-fuel firebox, a built-in worm-tub condenser, no column: the constructor's default, and every other kind's value. */
    public static final BatchStillOptions DEFAULT = new BatchStillOptions(Heater.SOLID, Condenser.BUILT_IN, 0);

    /** How the pot is heated (no jacket): a brick firebox fired by hand or by a burner, or an electric heater under a steel skirt. */
    public enum Heater {
        SOLID, LIQUID, ELECTRIC;

        /** Fired: a brick setting with a firing door and a brick flue. */
        public boolean fired() {
            return this != ELECTRIC;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The still's own condenser, or none (its vapour leaves to the player's). */
    public enum Condenser {
        BUILT_IN, NONE;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public BatchStillOptions {
        Objects.requireNonNull(heater, "heater");
        Objects.requireNonNull(condenser, "condenser");
        if (stages != 0 && (stages < MIN_STAGES || stages > MAX_STAGES))
            throw new IllegalArgumentException("A still's column has " + MIN_STAGES + " to " + MAX_STAGES + " stages, or none, not " + stages);
        if (stages > 0 && condenser != Condenser.BUILT_IN)
            throw new IllegalArgumentException("A still's column takes its reflux from the built-in condenser");
    }

    /** Whether the still has a column (with its reflux pot and valve). */
    public boolean column() {
        return stages > 0;
    }

    /** Whether the still condenses its own vapour. */
    public boolean condenses() {
        return condenser == Condenser.BUILT_IN;
    }

    public BatchStillOptions withHeater(Heater value) {
        return new BatchStillOptions(value, condenser, stages);
    }

    /** These options with condenser {@code value}; a still without one loses its column. */
    public BatchStillOptions withCondenser(Condenser value) {
        return new BatchStillOptions(heater, value, value == Condenser.BUILT_IN ? stages : 0);
    }

    public BatchStillOptions withStages(int value) {
        return new BatchStillOptions(heater, condenser, value);
    }

    /**
     * Saved options read onto the current rules: each field by name, a missing one ({@code null}) at its default, an
     * unknown or out-of-range one at its default with a line in {@code repairs}; a column without the built-in condenser
     * loses its column. Never throws.
     */
    public static BatchStillOptions read(@org.jetbrains.annotations.Nullable String heater, @org.jetbrains.annotations.Nullable String condenser,
                                         @org.jetbrains.annotations.Nullable Integer stages, java.util.List<String> repairs) {
        var h = DEFAULT.heater;
        if (heater != null) try {
            h = Heater.valueOf(heater.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            repairs.add("still heater " + heater + " -> " + h.key());
        }
        var c = DEFAULT.condenser;
        if (condenser != null) try {
            c = Condenser.valueOf(condenser.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            repairs.add("still condenser " + condenser + " -> " + c.key());
        }
        int n = DEFAULT.stages;
        if (stages != null) {
            if (stages == 0 || stages >= MIN_STAGES && stages <= MAX_STAGES) n = stages;
            else repairs.add("still stages " + stages + " -> " + n);
        }
        if (n > 0 && c != Condenser.BUILT_IN) {
            repairs.add("still column without its condenser -> no column");
            n = 0;
        }
        return new BatchStillOptions(h, c, n);
    }
}
