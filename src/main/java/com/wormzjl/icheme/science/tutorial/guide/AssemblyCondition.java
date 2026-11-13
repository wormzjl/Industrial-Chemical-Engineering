package com.wormzjl.icheme.science.tutorial.guide;

import java.util.List;
import java.util.Optional;

/**
 * What a guide step asks of a column's assembly as its screen was sent to the player (batch 2026-11-12-tutorial-system, P5
 * stage B; criterion {@code icheme:assembly_state}): optionally one of the statuses named ({@code AssemblyView.Status}
 * names: NOT_VALIDATED, CHECKED, COMPLETE). A read of the view the server sends anyway; no Minecraft type.
 */
public record AssemblyCondition(Optional<List<String>> status) {
    public AssemblyCondition {
        status = status.map(List::copyOf);
    }

    /** Whether an assembly at {@code status} meets the condition. */
    public boolean matches(String status) {
        return this.status.map(s -> s.contains(status)).orElse(true);
    }
}
