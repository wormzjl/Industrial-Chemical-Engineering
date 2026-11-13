package com.wormzjl.icheme.science.tutorial;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.tutorial.cards.BarrelData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recorded runs of chapter 3 (batch 2026-11-12-tutorial-system, P4b): chapter 2's still ({@link StillRunRecorder}: the
 * same charge, pot, firing and water) as a pot still and with a column of {@link #STAGES} trays at each reflux ratio of
 * {@link #REFLUX}. For each form: how the column answered on its boiling steps, and the two cuts, the first to the charge's
 * gas and naphtha taken overhead (distilled and vented), the second on to its kerosene. Written by
 * {@code tools/tutorial-recordings/StillTrays} and held to the law by {@code StillTraysRecordingTest}.
 */
public final class StillTraysRecorder {
    /** The column's trays: one of the Equipment Constructor's still options (2 to 8). */
    public static final int STAGES = 4;
    /** The reflux ratios of the column forms. */
    public static final double[] REFLUX = {1, 2};
    /** The share of the final vent that marks the light gas gone. */
    static final double GAS_GONE = .99;

    private StillTraysRecorder() {
    }

    /**
     * A cut: the overhead share it ends at, the process seconds, pot K and play minutes there, the spout's liquid in it (kg) and
     * that liquid's parts' mass shares.
     */
    public record Cut(double overhead, double time, double pot, double liquidKg, double[] parts) {
        public double playMinutes() {
            return time / StillRunRecorder.TIME_FACTOR / 60;
        }
    }

    /**
     * How the column answered over a run's boiling steps: solved itself (its own split of the design's trays), dry (its own
     * answer as the pot's single stage: the trays run dry), fallback (its solve refused, the law kept its last answer or one
     * stage); each with its pot temperature range (K, null if none); the pot K where the light gas is gone and the counts
     * after it; the failures' and notices' words.
     */
    public record ColumnCount(int boiling, int solved, int dry, int fallback, double[] solvedRange, double[] dryRange, double[] fallbackRange, double gasGone,
                              int boilingAfterGas, int solvedAfterGas, Map<String, Integer> failures, Map<String, Integer> notices) {
    }

    public record Form(String name, int stages, double reflux, StillRunRecorder.Run run, ColumnCount column, List<Cut> cuts) {
    }

    public static String bandName(int band) {
        return BarrelData.BANDS.get(band);
    }

    public static List<Form> record() {
        var forms = new ArrayList<Form>();
        forms.add(form("pot", 0, 0));
        for (double r : REFLUX) forms.add(form("trays_r" + (int) r, STAGES, r));
        return forms;
    }

    public static Form form(String name, int stages, double reflux) {
        var run = StillRunRecorder.run(stages, reflux);
        double[] shares = run.parts();
        var first = cut(run.points(), shares[0] + shares[1], null);
        var second = first == null ? null : cut(run.points(), shares[0] + shares[1] + shares[2], first);
        var cuts = new ArrayList<Cut>();
        cuts.add(first);
        cuts.add(second);
        return new Form(name, stages, reflux, run, count(run, stages), cuts);
    }

    /** Where the overhead share first reaches {@code share}, linear between steps; the liquid since {@code from} (null: since the start). */
    static Cut cut(List<StillRunRecorder.Point> points, double share, Cut from) {
        double[] start = new double[BarrelData.BANDS.size()];
        if (from != null) {
            double total = from.liquidKg();
            for (int b = 0; b < start.length; b++) start[b] = from.parts()[b] * total;
            // the start's cumulative liquid is the first cut's own (it began at zero)
        }
        for (int k = 1; k < points.size(); k++) {
            var a = points.get(k - 1);
            var b = points.get(k);
            double sa = a.distilled() + a.vented(), sb = b.distilled() + b.vented();
            if (sb >= share && sb > sa) {
                double f = (share - sa) / (sb - sa);
                double[] ka = a.column().spoutKg().length > 0 ? a.column().spoutKg() : new double[start.length], kb = b.column().spoutKg();
                double[] parts = new double[start.length];
                double liquid = 0;
                for (int i = 0; i < parts.length; i++) {
                    parts[i] = ka[i] + f * (kb[i] - ka[i]) - start[i];
                    liquid += parts[i];
                }
                for (int i = 0; i < parts.length; i++) parts[i] = liquid > 0 ? parts[i] / liquid : 0;
                return new Cut(share, a.time() + f * (b.time() - a.time()), a.pot() + f * (b.pot() - a.pot()), liquid, parts);
            }
        }
        return null;
    }

    static ColumnCount count(StillRunRecorder.Run run, int stages) {
        var points = run.points();
        double finalVent = points.getLast().vented();
        double gasGone = Double.NaN;
        int boiling = 0, solved = 0, dry = 0, fallback = 0, after = 0, solvedAfter = 0;
        double[] rs = null, rd = null, rf = null;
        var failures = new LinkedHashMap<String, Integer>();
        var notices = new LinkedHashMap<String, Integer>();
        boolean gone = false;
        for (var p : points) {
            if (!gone && finalVent > 0 && p.vented() >= GAS_GONE * finalVent) {
                gone = true;
                gasGone = p.pot();
            }
            var c = p.column();
            if (!c.boiled()) continue;
            boiling++;
            if (gone) after++;
            if (c.failure() != null) {
                fallback++;
                rf = widen(rf, p.pot());
                failures.merge(c.failure(), 1, Integer::sum);
            } else if (c.stages() == stages) {
                solved++;
                rs = widen(rs, p.pot());
                if (gone) solvedAfter++;
            } else {
                dry++;
                rd = widen(rd, p.pot());
            }
            if (c.notice() != null) notices.merge(c.notice(), 1, Integer::sum);
        }
        return new ColumnCount(boiling, solved, dry, fallback, rs, rd, rf, gasGone, after, solvedAfter, failures, notices);
    }

    private static double[] widen(double[] range, double t) {
        return range == null ? new double[]{t, t} : new double[]{Math.min(range[0], t), Math.max(range[1], t)};
    }

    /** The recording's JSON: the set-up shared by every form, then each form's column counts and cuts. */
    public static String toJson(List<Form> forms) {
        var root = new JsonObject();
        var setup = new JsonObject();
        setup.addProperty("package", StillRunRecorder.PACKAGE);
        setup.addProperty("crude", StillRunRecorder.CRUDE);
        setup.addProperty("volume_litres", 1000 * StillRunRecorder.VOLUME);
        setup.addProperty("heater", "solid");
        setup.addProperty("fill", StillRunRecorder.FILL);
        setup.addProperty("firing", StillRunRecorder.FIRING);
        setup.addProperty("water_kelvin", StillRunRecorder.WATER_KELVIN);
        setup.addProperty("time_factor", StillRunRecorder.TIME_FACTOR);
        setup.addProperty("charge_kg", forms.getFirst().run().chargeKg());
        root.add("setup", setup);
        var names = new JsonArray();
        for (var b : BarrelData.BANDS) names.add(b);
        root.add("part_names", names);
        var all = new JsonArray();
        for (var form : forms) {
            var o = new JsonObject();
            var run = form.run();
            o.addProperty("name", form.name());
            o.addProperty("stages", form.stages());
            o.addProperty("reflux", form.reflux());
            o.addProperty("finish", run.finish());
            o.addProperty("steps", run.steps());
            o.addProperty("distilled_kg", run.distilledKg());
            o.addProperty("vented_kg", run.ventedKg());
            o.addProperty("left_kg", run.leftKg());
            var c = form.column();
            var column = new JsonObject();
            column.addProperty("boiling", c.boiling());
            column.addProperty("solved", c.solved());
            column.addProperty("dry", c.dry());
            column.addProperty("fallback", c.fallback());
            column.addProperty("boiling_after_gas", c.boilingAfterGas());
            column.addProperty("solved_after_gas", c.solvedAfterGas());
            o.add("column", column);
            var cuts = new JsonArray();
            for (var cut : form.cuts()) {
                var k = new JsonObject();
                k.addProperty("overhead", cut.overhead());
                k.addProperty("time", cut.time());
                k.addProperty("pot_kelvin", cut.pot());
                k.addProperty("play_minutes", cut.playMinutes());
                k.addProperty("liquid_kg", cut.liquidKg());
                var parts = new JsonArray();
                for (double p : cut.parts()) parts.add(p);
                k.add("parts", parts);
                cuts.add(k);
            }
            o.add("cuts", cuts);
            all.add(o);
        }
        root.add("forms", all);
        return new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(root) + "\n";
    }
}
