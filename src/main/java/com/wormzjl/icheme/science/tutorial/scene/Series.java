package com.wormzjl.icheme.science.tutorial.scene;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Recorded numbers over time (batch 2026-11-12-tutorial-system, TUTORIAL_SYSTEM_PLAN.md section 3.1): one channel of a
 * recording ({@code assets/icheme/tutorial/recordings/*.json}), its times ascending, a value at a time by linear
 * interpolation between its points and held at its ends. Next to a missing value (null in the file, NaN here) a step
 * takes its end's value (NaN where that is the missing one). Read once, when a film or card is built; no Minecraft type.
 */
public final class Series implements Value {
    private final double[] time, values;

    public Series(double[] time, double[] values) {
        if (time.length == 0 || time.length != values.length) throw new IllegalArgumentException("A series needs as many values as times");
        for (int i = 1; i < time.length; i++) if (!(time[i] >= time[i - 1])) throw new IllegalArgumentException("Times out of order");
        this.time = time.clone();
        this.values = values.clone();
    }

    public int size() {
        return time.length;
    }

    public double time(int i) {
        return time[i];
    }

    public double value(int i) {
        return values[i];
    }

    public double start() {
        return time[0];
    }

    public double end() {
        return time[time.length - 1];
    }

    /** The point whose step holds {@code t}: the first point at or after it, the last after the end. */
    public int index(double t) {
        int lo = 0, hi = time.length - 1;
        if (t <= time[0]) return 0;
        if (t >= time[hi]) return hi;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (time[mid] >= t) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    /** The value at {@code t}: linear between points, held before the first and after the last; next to a missing point, the step's end value. */
    @Override
    public double at(double t) {
        int k = index(t);
        if (k == 0 || t >= time[k]) return values[k];
        double a = values[k - 1], b = values[k];
        if (Double.isNaN(a) || Double.isNaN(b)) return b;
        double span = time[k] - time[k - 1];
        return span > 0 ? a + (b - a) * (t - time[k - 1]) / span : b;
    }

    /** The first time the series reaches {@code level} rising, linear between points; NaN if it never does. */
    public double reaches(double level) {
        if (values[0] >= level) return time[0];
        for (int k = 1; k < time.length; k++) {
            double a = values[k - 1], b = values[k];
            if (b >= level && b > a && !Double.isNaN(a)) return time[k - 1] + (level - a) / (b - a) * (time[k] - time[k - 1]);
        }
        return Double.NaN;
    }

    /**
     * A recording: its number channels, its word channels (a word a point, as {@code status}), its derived lines (each a
     * map of its numbers) and its set-up's numbers.
     */
    public record Recording(Map<String, Series> channels, Map<String, List<String>> words, Map<String, Map<String, Double>> lines, Map<String, Double> setup,
                            Map<String, String> setupWords) {
        public Recording {
            channels = Map.copyOf(channels);
            words = Map.copyOf(words);
            lines = Map.copyOf(lines);
            setup = Map.copyOf(setup);
            setupWords = Map.copyOf(setupWords);
        }

        public Series series(String name) {
            var s = channels.get(name);
            if (s == null) throw new IllegalArgumentException("No channel " + name);
            return s;
        }

        /** The word of channel {@code name} at {@code t}: the point whose step holds it. */
        public String word(String name, double t) {
            var w = words.get(name);
            if (w == null) throw new IllegalArgumentException("No word channel " + name);
            return w.get(time().index(t));
        }

        /** The recording's time axis, as a series of itself. */
        public Series time() {
            return channels.values().iterator().next().timeAxis();
        }

        /** Derived line {@code name}'s value {@code key}; NaN for a line the run never reached. */
        public double line(String name, String key) {
            var line = lines.get(name);
            if (line == null) return Double.NaN;
            var v = line.get(key);
            return v == null ? Double.NaN : v;
        }
    }

    private Series timeAxis() {
        return new Series(time, time);
    }

    /**
     * The recording read from {@code in}: a {@code series} object of equal arrays with a {@code time} array (numbers, or
     * null for none; arrays of strings are word channels), a {@code lines} object of objects of numbers and a {@code setup}
     * object of numbers and words.
     */
    public static Recording read(Supplier<InputStream> in) {
        try (var stream = in.get()) {
            if (stream == null) throw new IllegalStateException("No recording");
            var root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            var series = root.getAsJsonObject("series");
            var timeArray = series.getAsJsonArray("time");
            double[] time = new double[timeArray.size()];
            for (int i = 0; i < time.length; i++) time[i] = timeArray.get(i).getAsDouble();
            var channels = new LinkedHashMap<String, Series>();
            var words = new LinkedHashMap<String, List<String>>();
            for (var entry : series.entrySet()) {
                var a = entry.getValue().getAsJsonArray();
                if (a.size() != time.length) throw new IllegalStateException("Channel " + entry.getKey() + " has " + a.size() + " points, time " + time.length);
                if (!a.isEmpty() && a.get(0).isJsonPrimitive() && a.get(0).getAsJsonPrimitive().isString()) {
                    var list = new ArrayList<String>();
                    for (var e : a) list.add(e.getAsString());
                    words.put(entry.getKey(), List.copyOf(list));
                } else {
                    double[] v = new double[a.size()];
                    for (int i = 0; i < v.length; i++) v[i] = number(a.get(i));
                    channels.put(entry.getKey(), new Series(time, v));
                }
            }
            var lines = new LinkedHashMap<String, Map<String, Double>>();
            if (root.has("lines"))
                for (var entry : root.getAsJsonObject("lines").entrySet()) lines.put(entry.getKey(), numbers(entry.getValue().getAsJsonObject(), null));
            var setupWords = new LinkedHashMap<String, String>();
            var setup = root.has("setup") ? numbers(root.getAsJsonObject("setup"), setupWords) : Map.<String, Double>of();
            return new Recording(channels, words, lines, setup, setupWords);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable recording", e);
        }
    }

    private static double number(JsonElement e) {
        return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
    }

    private static Map<String, Double> numbers(JsonObject o, Map<String, String> words) {
        var out = new LinkedHashMap<String, Double>();
        for (var entry : o.entrySet()) {
            var v = entry.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) out.put(entry.getKey(), v.getAsDouble());
            else if (words != null && v.isJsonPrimitive()) words.put(entry.getKey(), v.getAsString());
        }
        return out;
    }
}
