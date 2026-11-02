package com.wormzjl.icheme.science.flowsheet;

import java.util.List;

/**
 * A law's refusal, in words a player can act on (batch 2026-11-02-process-simulator, plan section 3.1). The solver turns it
 * into the unit's {@link Note}: {@code key} is a translation key suffix, as in a note.
 */
public final class FlowsheetFailure extends RuntimeException {
    private final String key;
    private final List<String> arguments;

    public FlowsheetFailure(String key, String... arguments) {
        super(key + " " + List.of(arguments));
        this.key = key;
        this.arguments = List.of(arguments);
    }

    public String key() {
        return key;
    }

    public List<String> arguments() {
        return arguments;
    }
}
