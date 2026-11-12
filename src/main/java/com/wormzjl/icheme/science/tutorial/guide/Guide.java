package com.wormzjl.icheme.science.tutorial.guide;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The field guide's table of contents (batch 2026-11-12-tutorial-system, TUTORIAL_SYSTEM_PLAN.md section 3.3), read from
 * {@code assets/icheme/tutorial/guide.json} ({@link GuideFile}): its chapters in order, each with its steps, and the
 * glossary's terms by the step that defines them. The language keys are derived, never stored: a chapter's title
 * {@code tutorial.icheme.guide.<chapter>}, a step's sentence {@code tutorial.icheme.guide.<chapter>.<step>}. A step's id
 * across the guide is {@code <chapter>/<step>}, its advancement {@code icheme:tutorial/<chapter>/<step>}. No Minecraft type.
 */
public record Guide(List<Chapter> chapters, Map<String, String> terms) {
    public static final String KEY = "tutorial.icheme.guide.";
    /** An id: lower-case letters, digits and {@code _}, 1 to 32 characters. */
    static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    public Guide {
        chapters = List.copyOf(chapters);
        terms = Map.copyOf(terms);
        var ids = new HashSet<String>();
        for (var chapter : chapters) if (!ids.add(chapter.id())) throw new IllegalArgumentException("Chapter twice: " + chapter.id());
    }

    /** How a step is shown with it: nothing, a film, a card, or the display units' offer. */
    public sealed interface Media permits None, Film, Card, Units {
        Media NONE = new None();
        Media UNITS = new Units();
    }

    public record None() implements Media {
    }

    /** A film {@code Films} knows by {@code name}. */
    public record Film(String name) implements Media {
    }

    /** A card {@code Cards} knows by {@code name}. */
    public record Card(String name) implements Media {
    }

    /** The display units' offer (owner decision D5): °C, bar, per hour and mass, or the player's own. */
    public record Units() implements Media {
    }

    /** Who reports a step done: the client (a film to its last stop, a card worked, a tour finished, a choice made) or the server's engine criterion. */
    public enum Check {
        CLIENT, SERVER;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A step: its id in the chapter, its media, who checks it, and the criterion a SERVER step's advancement listens to (null for CLIENT). */
    public record Step(String id, Media media, Check check, @Nullable String criterion) {
        public Step {
            if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Bad step id: " + id);
            if ((check == Check.SERVER) != (criterion != null)) throw new IllegalArgumentException("A SERVER step, and only one, names its criterion: " + id);
        }
    }

    public record Chapter(String id, List<Step> steps) {
        public Chapter {
            if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Bad chapter id: " + id);
            steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("A chapter needs a step: " + id);
            var ids = new HashSet<String>();
            for (var step : steps) if (!ids.add(step.id())) throw new IllegalArgumentException("Step twice: " + id + "/" + step.id());
        }

        /** The chapter title's language key. */
        public String titleKey() {
            return KEY + id;
        }

        /** {@code step}'s id across the guide: {@code <chapter>/<step>}. */
        public String id(Step step) {
            return id + "/" + step.id();
        }

        /** {@code step}'s sentence's language key. */
        public String key(Step step) {
            return KEY + id + "." + step.id();
        }

        /** The chapter's source line's key ({@code <chapter>.source}): where the player takes what a step asks for (P2b: the still's crude). */
        public String sourceKey() {
            return KEY + id + ".source";
        }

        /** {@code step}'s sentence as {@code tr} gives it, a {@code %s} in it filled with the chapter's source line. */
        public String text(Step step, java.util.function.Function<String, String> tr) {
            String line = tr.apply(key(step));
            return line.contains("%s") ? line.replace("%s", tr.apply(sourceKey())) : line;
        }
    }

    /** The chapter {@code id}, or null. */
    public @Nullable Chapter chapter(String id) {
        for (var chapter : chapters) if (chapter.id().equals(id)) return chapter;
        return null;
    }

    /** The step {@code <chapter>/<step>}, or null. */
    public @Nullable Step step(String id) {
        int slash = id.indexOf('/');
        var chapter = slash < 0 ? null : chapter(id.substring(0, slash));
        if (chapter == null) return null;
        for (var step : chapter.steps()) if (step.id().equals(id.substring(slash + 1))) return step;
        return null;
    }

    /** Every step's id across the guide, in guide order. */
    public List<String> stepIds() {
        return chapters.stream().flatMap(c -> c.steps().stream().map(c::id)).toList();
    }

    /** A step's advancement id's path: {@code tutorial/<chapter>/<step>} in the {@code icheme} namespace. */
    public static String advancement(String stepId) {
        return "tutorial/" + stepId;
    }
}
