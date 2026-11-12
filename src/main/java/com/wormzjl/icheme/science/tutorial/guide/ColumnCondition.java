package com.wormzjl.icheme.science.tutorial.guide;

import com.wormzjl.icheme.science.column.operation.OperationView;

import java.util.List;
import java.util.Optional;

/**
 * What a guide step asks of a running column's Operation view as the server sent it to the player (batch
 * 2026-11-12-tutorial-system, P5 stage B; criterion {@code icheme:column_state}): optionally one of the phases named
 * ({@link OperationView.Phase} names), and optionally a product leaving (an outlet port's rate above 0). A read of the view
 * the server sends anyway; no Minecraft type.
 */
public record ColumnCondition(Optional<List<String>> phase, Optional<Boolean> product) {
    /** Past idle: what a Start that was taken shows. */
    public static final List<String> STARTED = List.of("STARTING", "RUNNING", "LIMITED", "UPSET");

    public ColumnCondition {
        phase = phase.map(List::copyOf);
    }

    /** Whether {@code view} (null: no Operation view, a column that never started) meets the condition. */
    public boolean matches(OperationView view) {
        if (view == null) return false;
        if (phase.isPresent() && !phase.get().contains(view.phase().name())) return false;
        return product.map(want -> want == leaving(view)).orElse(true);
    }

    /** Whether a product leaves: an outlet port's rate above 0. */
    static boolean leaving(OperationView view) {
        for (var s : view.streams()) if (!s.port().inlet() && s.rate() > 0) return true;
        return false;
    }
}
