package com.wormzjl.icheme.science.flowsheet;

import java.util.List;
import java.util.Objects;

/**
 * A line of a run's Log (batch 2026-11-02-process-simulator, plan section 3.1). {@code key} is a translation key suffix,
 * {@code gui.icheme.flowsheet.<key>}, and {@code arguments} fill it, so the science stays free of text a player reads.
 *
 * @param subject the unit, stream or loop it is about
 */
public record Note(Severity severity, String subject, String key, List<String> arguments) {
    public enum Severity {
        /** A fact of the run: nothing to fix. */
        INFO,
        /** The run goes on, and the player should look: a data range left, a heat cross. */
        WARNING,
        /** A unit or a loop stopped here. */
        ERROR
    }

    public Note {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(key, "key");
        arguments = List.copyOf(arguments);
    }

    public static Note info(String subject, String key, String... arguments) {
        return new Note(Severity.INFO, subject, key, List.of(arguments));
    }

    public static Note warning(String subject, String key, String... arguments) {
        return new Note(Severity.WARNING, subject, key, List.of(arguments));
    }

    public static Note error(String subject, String key, String... arguments) {
        return new Note(Severity.ERROR, subject, key, List.of(arguments));
    }
}
