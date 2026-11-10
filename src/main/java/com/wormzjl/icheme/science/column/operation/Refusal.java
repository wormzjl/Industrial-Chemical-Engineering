package com.wormzjl.icheme.science.column.operation;

import java.util.Locale;

/**
 * Why a column does not start (batch 2026-11-10-column-failure-modes, W1.6): every text of the Start's checks
 * ({@code ColumnStarts.build}, {@code FluidWorldAuthority.startColumn}) and of the running column before its first table
 * ({@code ColumnOperation}), said after {@code NOT READY: }. The methods that refuse keep returning the text.
 */
public enum Refusal {
    // ColumnStarts.build: the design and the assembly.
    NO_DESIGN("no designed column here"),
    NO_SAVED_POINT("the design has no operating point: save it from the calculator again"),
    VACUUM("a vacuum column cannot run yet"),
    INCOMPLETE("the assembly is not complete"),
    VALVE_UNREGISTERED("a boundary valve is not registered"),
    // FluidWorldAuthority.startColumn: the plant inside the boundary valves.
    CLOSED("the fluid world is closed"),
    ALREADY_RUNNING("already running"),
    NOZZLE_UNREGISTERED("a nozzle's pipe block is not registered"),
    FIXED_INSIDE("a generator or a void stands inside the column"),
    HELD_INSIDE("a device inside the column is held or being edited"),
    TOO_MANY_DEVICES("the column holds more than %d devices"),
    SHARED_PLACE("two ports share a place"),
    VALVE_MISSING("a boundary valve is missing or inside the column"),
    PLACE_TAKEN("a port's place is taken"),
    TOO_MANY_SHELLS("the condenser has more than %d shells"),
    TOO_MANY_UTILITY_UNITS("the %s has more than %d units"),
    PORTS_DO_NOT_FIT("the column's ports do not fit its lines: %s"),
    /** A member pump with no driver at all while machines need power (W5, EXECUTION_PLAN.md stage 4 step 4: the owner may veto it). */
    NO_DRIVER("the %s has no driver"),
    // ColumnOperation: the first solve.
    NO_OPERATING_POINT("the design has no operating point"),
    NO_NOZZLE("the solve gives a %s the column has no nozzle for"),
    FIRST_SOLVE("%s");

    /** The text, a format where the refusal has a parameter. */
    public final String format;

    Refusal(String format) {
        this.format = format;
    }

    /** The text with its parameters. */
    public String text(Object... parameters) {
        return parameters.length == 0 ? format : String.format(Locale.ROOT, format, parameters);
    }
}
