package com.wormzjl.icheme.science.tutorial.films;

import com.wormzjl.icheme.science.tutorial.scene.Series;

import java.util.List;

/**
 * The recorded still run of chapter 2 (batch 2026-11-12-tutorial-system, P2b), {@code still_run.json}: written offline
 * from the still law by {@code tools/tutorial-recordings/StillRun}, held to the law by {@code StillRunRecordingTest}. Its
 * channels: {@code time} (process s), {@code pot} and {@code head} (K), {@code level} (share of the pot), {@code
 * distilled} and {@code vented} (shares of the charge's mass), {@code rate} (kg/s to the spout), {@code part} (the part
 * coming over, 0 gas to 5 residue, -1 none) and {@code status} (the law's status names). Its lines: {@code first},
 * {@code naphtha}, {@code kerosene} (absent when the run never got there) and {@code end}, each with {@code time},
 * {@code pot_kelvin} and {@code play_minutes}. Read once; no Minecraft type.
 */
public final class StillRunData {
    public static final String PATH = "/assets/icheme/tutorial/recordings/still_run.json";
    /** The six parts, as the barrel card names them. */
    public static final List<String> PARTS = List.of("gas", "naphtha", "kerosene", "diesel", "gas_oil", "residue");

    private static Series.Recording loaded;

    private StillRunData() {
    }

    /** The recording on the class path (the mod's jar, the tests' and the render tool's), read once. */
    public static synchronized Series.Recording load() {
        if (loaded == null) loaded = Series.read(() -> StillRunData.class.getResourceAsStream(PATH));
        return loaded;
    }

    /** The charge's mass share of part {@code part} (gas, naphtha, kerosene, diesel, gas_oil or residue), from the set-up. */
    public static double share(Series.Recording run, String part) {
        var v = run.setup().get("share_" + part);
        if (v == null) throw new IllegalArgumentException("No share of " + part);
        return v;
    }

    /** The share of the charge taken overhead (distilled and vented) at process time {@code t}. */
    public static double overhead(Series.Recording run, double t) {
        return run.series("distilled").at(t) + run.series("vented").at(t);
    }

    /** The part coming over at process time {@code t} (0 gas to 5 residue), -1 for none. */
    public static int part(Series.Recording run, double t) {
        var p = run.series("part");
        return (int) Math.round(p.value(p.index(t)));
    }
}
