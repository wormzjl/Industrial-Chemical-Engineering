package com.wormzjl.icheme.science.flowsheet;

import org.jetbrains.annotations.Nullable;

/**
 * A unit's passages once it has a size (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.11, D19): what each
 * loses at the run's flow, by the world's own law. A passage is named by the door's field its law replaces ({@code tube_drop},
 * {@code drop}); a unit with no size has none, and its door's typed drop stands as the estimate it is.
 */
@FunctionalInterface
public interface Passages {
    Passages TYPED = name -> null;

    /** The law of the passage named, null where the unit has no size for it. */
    @Nullable Law passage(String name);
    default com.wormzjl.icheme.science.equipment.ExchangerType exchangerType(){return com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD;}

    /** One passage's law. */
    interface Law {
        /** What {@code in} loses on its way through, Pa, with {@code out} what leaves: null where the law has no outlet yet. */
        double drop(FlowStream in, @Nullable FlowStream out);

        /** Whether {@link #drop} reads the outlet, as a heater's coils and an air cooler's bank do; a side read at its inlet does not. */
        default boolean readsOutlet() {
            return false;
        }
    }
}
