package com.wormzjl.icheme.science.column.operation;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * What a running column's cycle found (batch 2026-11-10-column-failure-modes, plan sections 1 and 2, W1): one constant per
 * failure, each in one {@link Rank} with one consequence. The status line, the Operation tab and the column's alarm
 * channels all read the list a cycle gives. Engine state: saved nowhere.
 */
public enum Condition {
    /** The feed account holds less than the set rate. */
    FEED_SHORT(Rank.LIMIT, "feed"),
    /** A steam account holds less than its ratio to the feed (rule B: as it comes; rule A: every input cut together). */
    STEAM_SHORT(Rank.LIMIT, "steam"),
    /** A product's buffer has less room than its make. */
    PRODUCT_FULL(Rank.LIMIT, "outlet"),
    /** The condenser's cooling water or its air face holds less than the duty. */
    COOLING_SHORT(Rank.LIMIT, "condenser"),
    /** The reboiler's utility holds less than its duty. */
    HEATING_SHORT(Rank.LIMIT, "reboiler"),
    /** A pumparound cooler's utility holds less than its duty; the index is the pumparound's. */
    PUMPAROUND_SHORT(Rank.LIMIT, "pumparound"),
    /** The worst tray would pass its flood limit at the set feed. */
    FLOODING(Rank.LIMIT, "flooding"),
    /** A member's electric store is empty; the index is the member's. */
    NO_POWER(Rank.LIMIT, "power"),
    /** The state taken left a drift band: a solve is pending. The text is its trigger. */
    OFF_TABLE(Rank.OFF_TABLE, "off table"),
    /** The solve of the state taken failed; the old table runs. */
    SOLVE_REFUSED(Rank.OFF_TABLE, "solve refused"),
    /** The closure departs from the solved one, or the condenser would heat: the cycle takes nothing. */
    CLOSURE(Rank.TRIP, "upset"),
    /** The solve of the applied set points failed; the accepted ones stay. */
    SETPOINT_REFUSED(Rank.NOTICE, "set points refused"),
    /** What the accepted solve advised (V3's bounded texts). */
    ADVISORY(Rank.NOTICE, "advisory"),
    /** The feed's water did not enter as steam and follows the distillate. */
    FEED_WATER_ROUTED(Rank.NOTICE, "feed water"),
    /** An inlet has no molar mass to meter by and takes nothing. */
    UNMETERED(Rank.NOTICE, "unmetered"),
    /**
     * The worst tray stands at {@link #HIGH_LOAD_FRACTION} of its flood limit or more at the feed taken (W3.2); the value is
     * that fraction, the index the tray. A sign before {@link #FLOODING} binds, with no alarm.
     */
    HIGH_LOAD(Rank.NOTICE, "high load");

    /** The flood fraction from which a cycle reports {@link #HIGH_LOAD}. */
    public static final double HIGH_LOAD_FRACTION = .85;

    /** The classes, in rising order: the phase is the highest present. */
    public enum Rank {
        NOT_READY, NOTICE, LIMIT, OFF_TABLE, TRIP
    }

    /** A free text's most characters. */
    public static final int MAX_TEXT = 256;
    /** A STEAM_SHORT's text under rule A: the steam cuts every input together. */
    public static final String RULE_A = "inputs cut together (rule A)";

    public final Rank rank;
    /** The status word, in English: a LIMIT's is the one the status line names ({@code LIMITED: <word>, N %}). */
    public final String word;

    Condition(Rank rank, String word) {
        this.rank = rank;
        this.word = word;
    }

    /**
     * A condition that holds.
     *
     * @param value a LIMIT's share of the set feed it allows (0 to 1), a TRIP's departure; 0 where none
     * @param index the port's or member's index, -1 for none
     * @param text  a bounded free text, empty where the constant says it all
     */
    public record Active(Condition condition, double value, int index, String text) {
        public Active {
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(text, "text");
            if (index < -1) throw new IllegalArgumentException("An index of -1 or more");
            if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        }

        public Active(Condition condition, double value) {
            this(condition, value, -1, "");
        }

        public Active(Condition condition, String text) {
            this(condition, 0, -1, text);
        }
    }

    /**
     * The condition that binds in {@code conditions}: a TRIP, else the LIMIT with the lowest share, the first of equal ones
     * (the cycle lists them in the order it weighs them); null where neither holds.
     */
    public static @Nullable Active binding(List<Active> conditions) {
        Active out = null;
        for (var a : conditions) {
            if (a.condition().rank == Rank.TRIP) return a;
            if (a.condition().rank == Rank.LIMIT && (out == null || a.value() < out.value())) out = a;
        }
        return out;
    }

    /** The first condition {@code c} in {@code conditions}, or null. */
    public static @Nullable Active find(List<Active> conditions, Condition c) {
        for (var a : conditions) if (a.condition() == c) return a;
        return null;
    }
}
