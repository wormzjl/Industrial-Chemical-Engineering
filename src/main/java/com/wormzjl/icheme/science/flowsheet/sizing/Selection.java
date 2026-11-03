package com.wormzjl.icheme.science.flowsheet.sizing;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Auto (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.5): a search over sizes that calls the rate function
 * a Fixed size is rated by. A failed trial is a rating with a red reading, so nothing here ends the search but a stop.
 */
public final class Selection {
    private Selection() {
    }

    /**
     * What Auto took, and how it rates.
     *
     * @param size null with nothing to size
     */
    public record Choice<S>(@Nullable S size, Rating rating) {
    }

    /**
     * The first of {@code sizes}, smallest first, that meets every limit. Where none does, the first that meets the Musts and comes
     * nearest the Shoulds; where no Must can be met, the one that comes nearest them, and its lamp is red.
     */
    public static <S> Choice<S> smallest(Iterable<S> sizes, Function<S, Rating> rate) {
        S best = null;
        var rated = Rating.NONE;
        for (var size : sizes) {
            var rating = rate.apply(size);
            if (rating.settled()) return new Choice<>(size, rating);
            double must = rating.mustMiss(), bestMust = rated.mustMiss();
            if (best == null || must < bestMust || must == bestMust && rating.shouldMiss() < rated.shouldMiss()) {
                best = size;
                rated = rating;
            }
        }
        return new Choice<>(best, rated);
    }
}
