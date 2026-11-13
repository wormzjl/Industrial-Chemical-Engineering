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
        // P5: the crude unit, and its equipment pages' films (each built only when its page's Watch is pressed).
        ALL.put(CrudeUnit.NAME, CrudeUnit::build);
        ALL.put(CrudeUnit.BUILD_NAME, CrudeUnit::buildOrder);
        for (var name : EquipmentFilms.KINDS.keySet()) ALL.put(name, () -> EquipmentFilms.build(name));
        // P6: every other equipment page's generic film (PageFilms), built only when its Watch is pressed.
        for (var name : PageFilms.KINDS.keySet()) ALL.put(name, () -> PageFilms.build(name));
    }

    private Films() {
    }

    /** Every film's name, in order. */
    public static Iterable<String> names() {
        return ALL.keySet();
    }

    /** Whether a film is named {@code name}: a name's check, nothing built. */
    public static boolean has(String name) {
        return ALL.containsKey(name);
    }

    /** The film named {@code name}, built anew; null for none. */
    public static Scene build(String name) {
        var film = ALL.get(name);
        return film == null ? null : film.get();
    }
}
