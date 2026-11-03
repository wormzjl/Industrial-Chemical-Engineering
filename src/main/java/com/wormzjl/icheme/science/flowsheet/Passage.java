package com.wormzjl.icheme.science.flowsheet;

import java.util.Objects;

/**
 * A reversible passage (batch 2026-11-02-process-simulator, PORT_SIDES_REVIEW.md option B, owner 2026-11-04): a coil, a bank, an
 * exchanger's side, a valve's or a filter's body. It has two ends on the sheet and no fixed inlet: the streams on them say which
 * end is the inlet ({@link Wiring}). Its model reads it by two roles, ports of {@link UnitModel#ports}, as before.
 *
 * @param a   the end the sheet draws where an inlet stood, the inlet while no stream decides
 * @param b   the other end
 * @param in  the role the inlet end plays
 * @param out the role the outlet end plays
 */
public record Passage(String a, String b, String in, String out) {
    /** A one-passage unit's: a valve, a filter, a cooler, a heater's coils in series. */
    public static final Passage THROUGH = new Passage("a", "b", "in", "out");

    public Passage {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
    }

    /** A passage whose ends are {@code name}'s: {@code name_a} and {@code name_b}, read as {@code name_in} and {@code name_out}. */
    public static Passage named(String name) {
        return new Passage(name + "_a", name + "_b", name + "_in", name + "_out");
    }

    /** The end across from {@code end}. */
    public String other(String end) {
        return end.equals(a) ? b : a;
    }
}
