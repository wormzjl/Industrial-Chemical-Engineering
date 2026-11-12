package com.wormzjl.icheme.science.tutorial.guide;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * A screen page's help tour (TUTORIAL_SYSTEM_PLAN.md section 3.2): the page's name ({@code guide},
 * {@code fluid_device.still}), its stops in order (at most {@link #MAX_STOPS}), and the film its last stop offers ("Show
 * me"; null for none). A stop names its control by an anchor, never by pixels:
 * <ul>
 * <li>a widget key of the page's builder (a {@code Column.Placed.slots} key, as {@code still_firing:on} or
 * {@code stillFiring});</li>
 * <li>{@code reading:<meter key>}: a device column's meter by its key ({@code reading:still_level}), whose slot is the
 * meter's index;</li>
 * <li>{@code tab:<id>}, {@code status}, {@code title} or {@code group:<n>} (the page's n-th function group, from 1).</li>
 * </ul>
 * A stop that {@code waits} steps only when the player presses its lit control. A stop's sentence is
 * {@code tutorial.icheme.tour.<page>.<n>}, n from 1. P4b: instead of a film, the last stop can show a guide step's own
 * media ({@code show}, a step id {@code <chapter>/<step>}: chapter 4's card from the simulator's sheet page). No Minecraft type.
 */
public record Tour(String page, List<Stop> stops, @Nullable String film, @Nullable String show) {
    public static final String KEY = "tutorial.icheme.tour.";
    public static final int MAX_STOPS = 7;
    static final Pattern PAGE = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)*");

    public record Stop(String anchor, boolean waits) {
        public Stop {
            if (anchor.isBlank()) throw new IllegalArgumentException("A stop names its anchor");
        }
    }

    public Tour {
        if (!PAGE.matcher(page).matches()) throw new IllegalArgumentException("Bad tour page: " + page);
        stops = List.copyOf(stops);
        if (stops.isEmpty() || stops.size() > MAX_STOPS) throw new IllegalArgumentException("A tour has 1 to " + MAX_STOPS + " stops: " + page);
        if (film != null && show != null) throw new IllegalArgumentException("A tour shows a film or a step, not both: " + page);
    }

    public Tour(String page, List<Stop> stops, @Nullable String film) {
        this(page, stops, film, null);
    }

    /** Whether its last stop offers something to see: a film or a step's media. */
    public boolean shows() {
        return film != null || show != null;
    }

    /** Stop {@code index}'s sentence's language key (index from 0, the key's number from 1). */
    public String key(int index) {
        return KEY + page + "." + (index + 1);
    }
}
