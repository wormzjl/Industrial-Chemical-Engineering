package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.Progress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The player's progress: the current step is the first not done, in order; nothing is locked. */
class ProgressTest {
    private static final Guide GUIDE = new Guide(List.of(
            new Guide.Chapter("a", List.of(step("one"), step("two"))),
            new Guide.Chapter("b", List.of(step("three")))), Map.of());

    private static Guide.Step step(String id) {
        return new Guide.Step(id, Guide.Media.NONE, Guide.Check.CLIENT, null);
    }

    @Test
    void theCurrentStepIsTheFirstNotDone() {
        assertEquals("a/one", Progress.NONE.current(GUIDE));
        assertEquals("a/two", new Progress(Set.of("a/one")).current(GUIDE));
        assertEquals("a/one", new Progress(Set.of("a/two", "b/three")).current(GUIDE), "a step done out of order leaves the first open one current");
        assertNull(new Progress(Set.of("a/one", "a/two", "b/three")).current(GUIDE));
    }

    @Test
    void aChapterIsFinishedWhenEveryStepIsDone() {
        var a = GUIDE.chapters().get(0);
        assertFalse(new Progress(Set.of("a/one")).finished(a));
        assertTrue(new Progress(Set.of("a/one", "a/two")).finished(a));
        assertTrue(new Progress(Set.of("b/three")).finished(GUIDE.chapters().get(1)), "the chapters do not wait for each other");
    }

    @Test
    void withAddsAStepAndKeepsTheRest() {
        var p = Progress.NONE.with("a/two");
        assertTrue(p.done("a/two"));
        assertSame(p, p.with("a/two"));
        assertTrue(p.with("x/unknown").done("a/two"), "an id the guide does not know is kept (the server's still/build)");
        assertFalse(Progress.NONE.done("a/two"));
    }
}
