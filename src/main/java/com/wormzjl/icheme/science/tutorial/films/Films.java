package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.tutorial.scene.Scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** The films by name (batch 2026-11-12-tutorial-system): the guide, the render tool and the P0 command open them by it. */
public final class Films {
    private static final Map<String, Supplier<Scene>> ALL = new LinkedHashMap<>();

    static {
        ALL.put(StillInside.NAME, StillInside::build);
        ALL.put(StillBuild.NAME, StillBuild::build);
        ALL.put(StillRun.NAME, StillRun::build);
        ALL.put(StillTray.NAME, StillTray::build);
        ALL.put(SmallTower.NAME, SmallTower::build);
    }

    private Films() {
    }

    /** Every film's name, in order. */
    public static Iterable<String> names() {
        return ALL.keySet();
    }

    /** The film named {@code name}, built anew; null for none. */
    public static Scene build(String name) {
        var film = ALL.get(name);
        return film == null ? null : film.get();
    }
}
