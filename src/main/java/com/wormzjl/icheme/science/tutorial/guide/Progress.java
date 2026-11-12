package com.wormzjl.icheme.science.tutorial.guide;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * The steps a player has done, by their ids across the guide ({@code <chapter>/<step>}): what the server's tutorial
 * advancements say, plus what this client has just reported. Nothing is locked; only the "current" mark follows the guide's
 * order. Immutable.
 */
public record Progress(Set<String> done) {
    public static final Progress NONE = new Progress(Set.of());

    public Progress {
        done = Set.copyOf(done);
    }

    public boolean done(String stepId) {
        return done.contains(stepId);
    }

    public boolean done(Guide.Chapter chapter, Guide.Step step) {
        return done(chapter.id(step));
    }

    /** This progress with {@code stepId} done too. */
    public Progress with(String stepId) {
        if (done(stepId)) return this;
        var next = new HashSet<>(done);
        next.add(stepId);
        return new Progress(next);
    }

    /** The first step of {@code guide}, in order, not done: its id, or null when every step is done. */
    public @Nullable String current(Guide guide) {
        for (var id : guide.stepIds()) if (!done(id)) return id;
        return null;
    }

    /** Whether every step of {@code chapter} is done. */
    public boolean finished(Guide.Chapter chapter) {
        for (var step : chapter.steps()) if (!done(chapter, step)) return false;
        return true;
    }
}
