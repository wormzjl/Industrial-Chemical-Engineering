package com.wormzjl.icheme.science.tutorial;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.science.fluid.network.BatchStill;
import com.wormzjl.icheme.science.fluid.network.StillColumn;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.tutorial.cards.BarrelData;

import java.util.ArrayList;
import java.util.List;

/**
 * The recorded run of chapter 2 (batch 2026-11-12-tutorial-system, P2b): one batch of the world's still law
 * ({@link BatchStill#step}, {@link StillColumn}) on the bundled catalog's network package, the way the simulator's
 * {@code StillBatch} runs one (its step rule), but keeping every step's status, level and vent: a 500 L solid-fired pot
 * with its built-in condenser and no column, charged {@link #FILL} full with the Fluid Generator's medium crude at
 * {@link #CHARGE_KELVIN}, firing 1, the condenser on the simulator's default water at its design flow, until the law
 * says BOIL DRY, cuts the firing at OVERHEAT, or nothing more distils. Written by {@code tools/tutorial-recordings/StillRun}
 * and held to the law by {@code StillRunRecordingTest}.
 */
public final class StillRunRecorder {
    public static final String PACKAGE = FluidPresetCatalog.NETWORK_PACKAGE, CRUDE = "icheme:medium_crude", ASSAY = "medium_crude";
    public static final double VOLUME = .5, FILL = .6, CHARGE_KELVIN = 298.15, FIRING = 1, WATER_KELVIN = 298.15, TIME_FACTOR = 4;
    /** StillBatch's step rule: about this share of the charge's moles a boiling step, this much heating a warming step, this head rise at most. */
    static final double STEP_SHARE = .01, STEP_KELVIN = 5, HEAD_KELVIN = 2;
    public static final int MOST_STEPS = 2000, MOST_POINTS = 240;
    /**
     * The share of the charge's mass the spout has given where a player sees a steady trickle: the first drop is a trace (the
     * partial condenser sends most of the light end to the vent), and this much (0.26 kg in the drum) is the guide's first
     * distillate.
     */
    public static final double TRICKLE_SHARE = .001;

    private StillRunRecorder() {
    }

    /** A step's end: process seconds, the pot's and head's temperatures (K; head NaN with none), the level (share of the pot), the distilled and vented shares of the charge's mass, the rate to the spout (kg/s), the law's status and the part coming over (the band with the most mass in what left the pot over the step, spout liquid and vented gas together; -1 none). */
    public record Point(double time, double pot, double head, double level, double distilled, double vented, double rate, BatchStill.Status status, int part,
                        Column column) {
        Point(double time, double pot, double head, double level, double distilled, double vented, double rate, BatchStill.Status status, int part) {
            this(time, pot, head, level, distilled, vented, rate, status, part, Column.NONE);
        }
    }

    /**
     * What the column answered on a step (chapter 3's measurement, P4b): whether the step boiled, the stages of the split the
     * law used (the design's trays where the column solved itself, 0 where it answered as the pot's single stage), the reason
     * the column's own solve failed (null if none: the law then kept its last answer or fell back to one stage), the split's
     * notice, and the spout's liquid by part to the step's end (kg, cumulative).
     */
    public record Column(boolean boiled, int stages, String failure, String notice, double[] spoutKg) {
        static final Column NONE = new Column(false, 0, null, null, new double[0]);
    }

    /** A derived line: process seconds and the pot's temperature (K) there. */
    public record Line(double time, double pot) {
        public double playMinutes() {
            return time / TIME_FACTOR / 60;
        }
    }

    /**
     * The run: the charge (kg, its start K), the charge's six parts' mass shares (the barrel card's medium crude), how it
     * ended, the law's steps, every step's point, and the derived lines.
     */
    public record Run(double chargeKg, double flashedKg, double startKelvin, double[] parts, String finish, String refusal, int steps, List<Point> points, Line first, Line trickle,
                      Line naphtha, Line kerosene, Line end, double leftKg, double distilledKg, double ventedKg) {
    }

    public static Run run() {
        return run(0, 0);
    }

    /** The run on a still with {@code stages} column trays (0: none) at the valve's reflux ratio {@code reflux} (0 with no column). */
    public static Run run(int stages, double reflux) {
        var catalog = MaterialCatalog.bundled();
        var thermo = FluidThermodynamics.forNetwork(catalog, PACKAGE);
        var column = new StillColumn(thermo, BatchStill.ATMOSPHERIC);
        int n = column.componentCount();
        var preset = FluidPresetCatalog.resolve(catalog).stream().filter(p -> p.id().equals(CRUDE)).findFirst().orElseThrow();
        double[] axis = preset.moleFractions();
        // The preset is on the conserved basis, water last; the column's basis is the same where the model carries water (W4 D1).
        if (axis.length != thermo.hydrocarbon.componentCount() + 1 || n > axis.length) throw new IllegalStateException("The preset is not on the network's basis");
        if (axis[axis.length - 1] != 0) throw new IllegalStateException("The still's charge holds no water");
        // The crude as it comes boils at 1 atm below 25 °C: it flashes as it enters the open pot. The pot holds the flash's liquid; its gas leaves by the vent.
        var flash = thermo.flashTP(CHARGE_KELVIN, BatchStill.ATMOSPHERIC, axis, () -> {
        });
        double[] liquid = new double[n], gas = new double[n];
        for (int i = 0; i < n; i++) {
            liquid[i] = flash.labelAmountsView(FluidThermodynamics.OIL)[i] + flash.labelAmountsView(FluidThermodynamics.AQUEOUS)[i];
            gas[i] = flash.labelAmountsView(FluidThermodynamics.GAS)[i];
        }
        double liquidMoles = 0, gasKg = 0, liquidKg = 0;
        for (int i = 0; i < n; i++) {
            liquidMoles += liquid[i];
            liquidKg += liquid[i] * column.molarMass(i);
            gasKg += gas[i] * column.molarMass(i);
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = liquid[i] / liquidMoles;
        var design = new BatchStill.Design(VOLUME, BatchStill.Heater.SOLID, true, stages);
        var bubble = column.bubble(x, Double.NaN);
        if (bubble == null) throw new IllegalStateException("No bubble point for the charge");
        double moles = FILL * VOLUME / column.liquidVolume(CHARGE_KELVIN, x);
        double[] charge = new double[n], mass = new double[n];
        double chargeKg = 0;
        for (int i = 0; i < n; i++) {
            charge[i] = x[i] * moles;
            mass[i] = column.molarMass(i);
            chargeKg += charge[i] * mass[i];
        }
        double flashedKg = gasKg * chargeKg / liquidKg;
        // Each component's share of each of the six parts, from the barrel card's medium crude (its segments by component).
        var grade = BarrelData.load().stream().filter(g -> g.id().equals(ASSAY)).findFirst().orElseThrow();
        var components = thermo.compounds();
        double[][] in = new double[n][BarrelData.BANDS.size()];
        for (var segment : grade.segments()) {
            int i = components.indexOf(segment.component());
            if (i < 0) throw new IllegalStateException("No component " + segment.component());
            for (int b = 0; b < BarrelData.BANDS.size(); b++) in[i][b] = segment.in(b);
        }
        var cooling = new BatchStill.Cooling(WATER_KELVIN, BatchStill.designWaterFlow(design.rating()) * BatchStill.WATER_HEAT_CAPACITY);
        var inputs = new BatchStill.Inputs(FIRING, 1, reflux, cooling);
        double rating = FIRING * design.rating();
        var points = new ArrayList<Point>();
        // The flash's liquid is at its bubble point: the pot starts there (a hair under 25 °C), the law's start for a boiling charge.
        double start = Math.min(CHARGE_KELVIN, bubble.temperature());
        var pot = new BatchStill.Pot(charge, start, null);
        points.add(new Point(0, start, Double.NaN, FILL, 0, 0, 0, BatchStill.Status.HEATING, -1));
        double t = 0, distilled = 0, vented = 0, lastHead = Double.NaN, target = STEP_SHARE * moles;
        double dt = Math.max(1, moles * (column.liquidEnthalpy(start + STEP_KELVIN, x) - column.liquidEnthalpy(start, x)) / rating);
        int steps = 0;
        double[] spoutKg = new double[BarrelData.BANDS.size()];
        String finish = "UNFINISHED", refusal = null;
        while (steps < MOST_STEPS) {
            BatchStill.Step s;
            try {
                s = BatchStill.step(column, design, pot, inputs, dt);
            } catch (IllegalArgumentException | IllegalStateException e) {
                // The law refuses the next step: the run ends at the last step it took, with the column's reason.
                finish = "REFUSED";
                var split = column.solve(pot.moles(), 0, 0, BatchStill.outlet(cooling), null);
                refusal = split.converged() ? e.getMessage() : split.failure();
                break;
            }
            steps++;
            double removed = 0, out = 0, gone = 0;
            double[] parts = new double[BarrelData.BANDS.size()];
            for (int i = 0; i < n; i++) {
                removed += s.liquid()[i] + s.vapour()[i];
                out += s.liquid()[i] * mass[i];
                gone += s.vapour()[i] * mass[i];
                for (int b = 0; b < parts.length; b++) {
                    parts[b] += (s.liquid()[i] + s.vapour()[i]) * mass[i] * in[i][b];
                    spoutKg[b] += s.liquid()[i] * mass[i] * in[i][b];
                }
            }
            pot = s.pot();
            t += dt;
            distilled += out;
            vented += gone;
            int part = -1;
            if (removed > 0) {
                part = 0;
                for (int b = 1; b < parts.length; b++) if (parts[b] > parts[part]) part = b;
            }
            double[] xp = pot.total() > 0 ? fractions(pot.moles(), pot.total()) : x;
            double level = pot.total() * column.liquidVolume(pot.temperature(), xp) / VOLUME;
            boolean boiled = removed > 0 || s.status() == BatchStill.Status.BOILING;
            var answer = new Column(boiled, s.split() != null ? s.split().stages() : 0, s.columnFailure(), s.split() != null ? s.split().notice() : null, spoutKg.clone());
            points.add(new Point(t, pot.temperature(), s.headTemperature(), level, distilled / chargeKg, vented / chargeKg, out / dt, s.status(), part, answer));
            if (s.alarm(BatchStill.Status.BOIL_DRY)) {
                finish = "BOIL_DRY";
                break;
            }
            if (s.alarm(BatchStill.Status.OVERHEAT) && !(s.heat() > 0)) {
                finish = "OVERHEAT";
                break;
            }
            double factor;
            if (removed > 0) {
                factor = target / removed;
                double head = s.headTemperature();
                if (Double.isFinite(lastHead) && Double.isFinite(head) && Math.abs(head - lastHead) > 0) factor = Math.min(factor, HEAD_KELVIN / Math.abs(head - lastHead));
                lastHead = head;
            } else {
                double warmed = s.pot().temperature() - points.get(points.size() - 2).pot();
                factor = warmed > 0 ? STEP_KELVIN / warmed : 2;
            }
            dt = dt * Math.clamp(factor, .5, 2);
        }
        double left = 0;
        for (int i = 0; i < n; i++) left += pot.moles()[i] * mass[i];
        double[] shares = grade.bands();
        // The first drop: the first step that distils, liquid to the spout; a step that only vents gas (BOILING) is none.
        Point firstDrop = points.stream().filter(p -> p.status() == BatchStill.Status.DISTILLING && p.rate() > 0).findFirst().orElseThrow();
        return new Run(chargeKg, flashedKg, start, shares, finish, refusal, steps, points, new Line(firstDrop.time(), firstDrop.pot()), trickle(points, TRICKLE_SHARE), reach(points, shares[0] + shares[1]),
                reach(points, shares[0] + shares[1] + shares[2]), new Line(points.getLast().time(), points.getLast().pot()), left, distilled, vented);
    }

    private static double[] fractions(double[] moles, double total) {
        double[] x = new double[moles.length];
        for (int i = 0; i < x.length; i++) x[i] = moles[i] / total;
        return x;
    }

    /** Where the share taken overhead (distilled and vented) first reaches {@code share}, linear between steps; null if never. */
    static Line reach(List<Point> points, double share) {
        for (int k = 1; k < points.size(); k++) {
            var a = points.get(k - 1);
            var b = points.get(k);
            double sa = a.distilled() + a.vented(), sb = b.distilled() + b.vented();
            if (sb >= share && sb > sa) {
                double f = (share - sa) / (sb - sa);
                return new Line(a.time() + f * (b.time() - a.time()), a.pot() + f * (b.pot() - a.pot()));
            }
        }
        return null;
    }

    /** Where the spout's liquid (the distilled share alone) first reaches {@code share}, linear between steps; null if never. */
    static Line trickle(List<Point> points, double share) {
        for (int k = 1; k < points.size(); k++) {
            var a = points.get(k - 1);
            var b = points.get(k);
            if (b.distilled() >= share && b.distilled() > a.distilled()) {
                double f = (share - a.distilled()) / (b.distilled() - a.distilled());
                return new Line(a.time() + f * (b.time() - a.time()), a.pot() + f * (b.pot() - a.pot()));
            }
        }
        return null;
    }

    /** At most {@link #MOST_POINTS} of {@code points}, evenly spaced by index, the first and last kept (StillBatch.thin). */
    public static List<Point> thin(List<Point> points) {
        if (points.size() <= MOST_POINTS) return points;
        var out = new ArrayList<Point>(MOST_POINTS);
        for (int k = 0; k < MOST_POINTS; k++) out.add(points.get((int) Math.round((double) k * (points.size() - 1) / (MOST_POINTS - 1))));
        return out;
    }

    /** The recording's JSON: the set-up, the series (thinned) and the derived lines. */
    public static String toJson(Run run) {
        var root = new JsonObject();
        var setup = new JsonObject();
        setup.addProperty("package", PACKAGE);
        setup.addProperty("crude", CRUDE);
        setup.addProperty("volume_litres", 1000 * VOLUME);
        setup.addProperty("heater", "solid");
        setup.addProperty("condenser", "built_in");
        setup.addProperty("stages", 0);
        setup.addProperty("fill", FILL);
        setup.addProperty("charge_kelvin", CHARGE_KELVIN);
        setup.addProperty("charge_kg", run.chargeKg());
        setup.addProperty("flashed_kg", run.flashedKg());
        setup.addProperty("start_kelvin", run.startKelvin());
        setup.addProperty("firing", FIRING);
        setup.addProperty("water_kelvin", WATER_KELVIN);
        setup.addProperty("time_factor", TIME_FACTOR);
        setup.addProperty("finish", run.finish());
        // What ended the run: the law's overheat cut, boiling dry, or the law's refusal (the recorder's answer to any refusal).
        setup.addProperty("ended_at", switch (run.finish()) {
            case "REFUSED" -> "law_refusal";
            case "BOIL_DRY" -> "boil_dry";
            case "OVERHEAT" -> "overheat";
            default -> "unfinished";
        });
        if (run.refusal() != null) setup.addProperty("refusal", run.refusal());
        setup.addProperty("steps", run.steps());
        setup.addProperty("left_kg", run.leftKg());
        setup.addProperty("distilled_kg", run.distilledKg());
        setup.addProperty("vented_kg", run.ventedKg());
        var parts = new JsonArray();
        for (double p : run.parts()) parts.add(p);
        setup.add("parts", parts);
        for (int i = 0; i < run.parts().length; i++) setup.addProperty("share_" + BarrelData.BANDS.get(i), run.parts()[i]);
        root.add("setup", setup);
        var names = new JsonArray();
        for (var b : BarrelData.BANDS) names.add(b);
        root.add("part_names", names);
        var series = new JsonObject();
        var thinned = thin(run.points());
        series.add("time", column(thinned, Point::time));
        series.add("pot", column(thinned, Point::pot));
        series.add("head", column(thinned, Point::head));
        series.add("level", column(thinned, Point::level));
        series.add("distilled", column(thinned, Point::distilled));
        series.add("vented", column(thinned, Point::vented));
        series.add("rate", column(thinned, Point::rate));
        series.add("part", column(thinned, p -> p.part()));
        var status = new JsonArray();
        for (var p : thinned) status.add(p.status().name());
        series.add("status", status);
        root.add("series", series);
        var lines = new JsonObject();
        lines.add("first", line(run.first()));
        lines.add("trickle", line(run.trickle()));
        lines.add("naphtha", line(run.naphtha()));
        if (run.kerosene() != null) lines.add("kerosene", line(run.kerosene()));
        lines.add("end", line(run.end()));
        root.add("lines", lines);
        return new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(root) + "\n";
    }

    private static JsonArray column(List<Point> points, java.util.function.ToDoubleFunction<Point> value) {
        var a = new JsonArray();
        for (var p : points) {
            double v = value.applyAsDouble(p);
            if (Double.isNaN(v)) a.add(JsonNull.INSTANCE);
            else a.add(v);
        }
        return a;
    }

    private static JsonObject line(Line line) {
        var o = new JsonObject();
        o.addProperty("time", line.time());
        o.addProperty("pot_kelvin", line.pot());
        o.addProperty("play_minutes", line.playMinutes());
        return o;
    }
}
