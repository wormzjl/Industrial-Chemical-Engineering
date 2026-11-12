package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.fluid.network.BatchStill;
import com.wormzjl.icheme.science.fluid.network.StillColumn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One batch of a still, start to end, on the world's law (batch 2026-11-09-batch-distiller, W7; {@code BATCH_STILL_SIMULATOR.md}):
 * {@link BatchStill#step} from the charge at its start temperature until the last cut ends, the pot boils dry or overheats, or the
 * steps run out. Minecraft-free; the simulator's {@link BatchStillUnit} calls it, on the client, inside its calculation.
 *
 * <p>Each step is the law's: the pot's energy balance closes exactly (its own warming takes its share of the heat), each component is
 * conserved, and the distillate's composition and the head's temperature are the step start's. So a cut's end is decided at a step's
 * start: the step whose head reads the cut's temperature goes to the next cut, or ends the batch after the last. A cut by time ends a
 * step exactly at its time. The steps adapt: about {@value #STEP_SHARE} of the charge distilled a step, at most {@value #STEP_KELVIN} K
 * of heating and {@value #HEAD_KELVIN} K of head rise; never more than {@value #MOST_STEPS} steps, so a batch that does not finish in
 * them ends {@link Finish#UNFINISHED} and the unit says so.</p>
 */
public final class StillBatch {
    /** The most steps one batch takes. */
    public static final int MOST_STEPS = 2000;
    /** The most points its curve keeps; a longer batch keeps evenly spaced ones, its first and last among them. */
    public static final int MOST_POINTS = 240;
    /** The most cuts a still's door takes. */
    public static final int MOST_CUTS = 6;
    /** A boiling step distils about this share of the charge's moles. */
    static final double STEP_SHARE = .01;
    /** A heating step warms the pot about this much, K. */
    static final double STEP_KELVIN = 5;
    /** A step moves the head about this much at most, K. */
    static final double HEAD_KELVIN = 2;

    private StillBatch() {
    }

    /** How a cut ends: when the head's vapour reaches a temperature, or at a time from the batch's start. */
    public enum End {
        HEAD, TIME
    }

    /** A cut: its row's id (its port {@code cut/<id>}), how it ends and where: K for {@link End#HEAD}, s for {@link End#TIME}. */
    public record Cut(String id, End end, double value) {
        public Cut {
            if (id == null || end == null || !(value > 0) || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid cut");
        }
    }

    /**
     * The still as run: its design, the firing (0 to 1 of the rating, never 0), the valve's reflux ratio (finite), the condenser's
     * water (null without the built-in condenser) and the cuts, in order.
     */
    public record Settings(BatchStill.Design design, double firing, double reflux, BatchStill.@Nullable Cooling cooling, List<Cut> cuts) {
        public Settings {
            cuts = List.copyOf(cuts);
            if (!(firing > 0 && firing <= 1) || !(reflux >= 0) || !Double.isFinite(reflux) || cuts.isEmpty() || cuts.size() > MOST_CUTS)
                throw new IllegalArgumentException("Invalid still settings");
        }
    }

    /** How the batch ended. */
    public enum Finish {
        /** The last cut ended. */
        DONE,
        /** The pot boiled dry first: its liquid fell to {@link BatchStill#DRY_SHARE} of its volume. */
        DRY,
        /** The pot's bubble point reached {@link BatchStill#OVERHEAT} first: the firing is cut. */
        OVERHEAT,
        /** The steps ran out first. */
        UNFINISHED,
        /** The pot's liquid settled into two liquids first (W4): the batch stops there and the rest is residue. */
        SETTLED
    }

    /**
     * What one cut took: its moles per component, their enthalpy (J; condensate at its bubble point, or without a condenser the head's
     * vapour), and the batch's seconds when it began and ended.
     */
    public record Taken(String id, double[] moles, double enthalpy, double from, double to) {
        public Taken {
            moles = moles.clone();
        }

        @Override public double[] moles() {
            return moles.clone();
        }
    }

    /**
     * A point of the curve, at the end of a step from the batch's start (s): the pot's temperature (K), the head's (K, NaN with no
     * vapour at the head), the distillate's rate to the spout (mol/s), its composition (mole fractions, null with none) and its cut's
     * place (from 0; -1 while heating).
     */
    public record Point(double time, double pot, double head, double rate, double @Nullable [] composition, int cut) {
    }

    /**
     * A finished batch.
     *
     * @param seconds    how long it took
     * @param end        the pot at its end: the residue
     * @param cuts       what each cut took, in the door's order; a cut never reached took nothing
     * @param vent       what the vent took (moles; the condenser's share of the head it could not condense), and its enthalpy (J)
     * @param heat       the heat into the pot (J), its warming (J) and the fuel's heat or the energy drawn for it (J)
     * @param condenser  the condenser's duty (J)
     * @param condensed  the least share of the head's vapour the condenser took while it distilled (1 with no shortfall)
     * @param flooded    the seconds the column flooded, its firing held at its flood boil-up
     * @param fFactor    the column's highest F-factor (Pa^0.5)
     * @param boiled     the seconds from the start to the first vapour at the head (NaN if it never boiled)
     * @param failure    the first reason a column solve was refused, null if none was
     */
    public record Batch(Finish finish, int steps, double seconds, BatchStill.Pot end, List<Taken> cuts, double[] vent, double ventEnthalpy, double heat,
                        double warming, double fuel, double condenser, double condensed, double flooded, double fFactor, double boiled, List<Point> points,
                        @Nullable String failure) {
        public Batch {
            cuts = List.copyOf(cuts);
            points = List.copyOf(points);
            vent = vent.clone();
        }

        @Override public double[] vent() {
            return vent.clone();
        }
    }

    /**
     * Runs one batch of the charge {@code charge} (moles per component of {@code column}'s basis) from {@code temperature} K, its
     * liquid at or under its bubble point, on {@code settings}. {@code checkpoint} runs before every step, so a run's Stop ends it.
     */
    public static Batch run(StillColumn column, Settings settings, double[] charge, double temperature, Runnable checkpoint) {
        int n = column.componentCount();
        if (charge.length != n) throw new IllegalArgumentException("Still basis mismatch");
        var design = settings.design();
        double rating = settings.firing() * design.rating(), total = 0;
        for (double e : charge) total += e;
        if (!(total > 0) || !(rating > 0)) throw new IllegalArgumentException("A batch needs a charge and heat");
        var inputs = new BatchStill.Inputs(settings.firing(), 1, settings.reflux(), design.condenser() ? settings.cooling() : null);
        boolean condenses = inputs.cooling() != null;
        var cuts = settings.cuts();
        double[][] taken = new double[cuts.size()][n];
        double[] enthalpies = new double[cuts.size()], from = new double[cuts.size()], to = new double[cuts.size()], vent = new double[n];
        var points = new ArrayList<Point>();
        var pot = new BatchStill.Pot(charge, temperature, null);
        points.add(new Point(0, temperature, Double.NaN, 0, null, -1));
        double t = 0, heat = 0, warming = 0, fuel = 0, condenser = 0, ventEnthalpy = 0, condensed = 1, flooded = 0, fFactor = 0, boiled = Double.NaN;
        double target = STEP_SHARE * total, lastHead = Double.NaN;
        // The first step warms the pot about STEP_KELVIN: N dh over the heat rate.
        double[] x0 = fractions(charge, total);
        double dt = Math.max(1, total * (column.liquidEnthalpy(temperature + STEP_KELVIN, x0) - column.liquidEnthalpy(temperature, x0)) / rating);
        int cut = 0, steps = 0;
        String failure = null;
        var finish = Finish.UNFINISHED;
        while (steps < MOST_STEPS) {
            checkpoint.run();
            // A cut by time ends at its time; the batch ends with its last cut.
            while (cut < cuts.size() && cuts.get(cut).end() == End.TIME && t >= cuts.get(cut).value() * (1 - 1e-12)) {
                to[cut] = t;
                cut++;
                if (cut < cuts.size()) from[cut] = t;
            }
            if (cut >= cuts.size()) {
                finish = Finish.DONE;
                break;
            }
            double step = cuts.get(cut).end() == End.TIME ? Math.min(dt, cuts.get(cut).value() - t) : dt;
            var s = BatchStill.step(column, design, pot, inputs, step);
            steps++;
            double removed = 0;
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                out[i] = condenses ? s.liquid()[i] : s.vapour()[i];
                removed += s.liquid()[i] + s.vapour()[i];
            }
            if (removed > 0) {
                // The head reads the step start's vapour: a step whose head has reached its cut's temperature goes to the next cut.
                while (cut < cuts.size() && cuts.get(cut).end() == End.HEAD && s.headTemperature() >= cuts.get(cut).value()) {
                    to[cut] = t;
                    cut++;
                    if (cut < cuts.size()) from[cut] = t;
                }
                if (cut >= cuts.size()) {
                    finish = Finish.DONE;
                    break;
                }
            }
            if (failure == null && s.columnFailure() != null) failure = s.columnFailure();
            pot = s.pot();
            t += step;
            heat += s.heat();
            warming += s.warming();
            fuel += s.fuel();
            condenser += s.condenserDuty();
            for (int i = 0; i < n; i++) taken[cut][i] += out[i];
            enthalpies[cut] += condenses ? s.liquidEnthalpy() : s.vapourEnthalpy();
            if (condenses) {
                for (int i = 0; i < n; i++) vent[i] += s.vapour()[i];
                ventEnthalpy += s.vapourEnthalpy();
                if (removed > 0) condensed = Math.min(condensed, s.condensed());
            }
            if (s.alarm(BatchStill.Status.FLOODING)) flooded += step;
            fFactor = Math.max(fFactor, s.fFactor());
            if (removed > 0 && Double.isNaN(boiled)) boiled = t - step;
            double rate = 0;
            for (double e : out) rate += e;
            points.add(new Point(t, pot.temperature(), s.headTemperature(), rate / step, removed > 0 ? s.split().distillate() : null, removed > 0 ? cut : -1));
            if (s.alarm(BatchStill.Status.BOIL_DRY)) {
                finish = Finish.DRY;
                break;
            }
            if (s.alarm(BatchStill.Status.OVERHEAT) && !(s.heat() > 0)) {
                finish = Finish.OVERHEAT;
                break;
            }
            // The column's pot split states the pot (no extra flash): a pot that settles is past what the sheet's still models.
            if (removed > 0 && s.split() != null && s.split().twoLiquidPot()) {
                finish = Finish.SETTLED;
                break;
            }
            // The next step: about the target share distilled, or STEP_KELVIN of heating; the head never jumps more than HEAD_KELVIN.
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
            dt = step * Math.clamp(factor, .5, 2);
        }
        for (int c = cut; c < cuts.size(); c++) {
            if (c > cut) from[c] = t;
            to[c] = t;
        }
        var out = new ArrayList<Taken>();
        for (int c = 0; c < cuts.size(); c++) out.add(new Taken(cuts.get(c).id(), taken[c], enthalpies[c], from[c], to[c]));
        return new Batch(finish, steps, t, pot, out, vent, ventEnthalpy, heat, warming, fuel, condenser, condensed, flooded, fFactor, boiled, thin(points), failure);
    }

    /** At most {@link #MOST_POINTS} of {@code points}, evenly spaced, the first and last kept. */
    static List<Point> thin(List<Point> points) {
        if (points.size() <= MOST_POINTS) return points;
        var out = new ArrayList<Point>(MOST_POINTS);
        for (int k = 0; k < MOST_POINTS; k++) out.add(points.get((int) Math.round((double) k * (points.size() - 1) / (MOST_POINTS - 1))));
        return out;
    }

    private static double[] fractions(double[] moles, double total) {
        double[] x = new double[moles.length];
        for (int i = 0; i < x.length; i++) x[i] = moles[i] / total;
        return x;
    }
}
