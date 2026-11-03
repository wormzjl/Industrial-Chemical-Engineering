package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.TreeSet;

/**
 * A counterflow exchanger on the world's two heat curves (batch 2026-11-02-process-simulator, sizing plan section 3.11): the
 * surface a duty needs and the duty a surface passes. The curves hold each side's temperature and film along its enthalpy, so a
 * side that boils or condenses partway reads true, zone by zone. The wall is thin and holds no heat, as in {@code
 * ExchangerRelay}: the films stand in series. Pure numbers, no flash.
 */
public final class ExchangerRating {
    /** Bisections of the duty, far past any tolerance of the run's. */
    private static final int STEPS = 60;
    /** Beyond each end of a curve, so the ends of the span are never its last knots (K). */
    private static final double SPAN = 1;
    /** A sink's film: no resistance beside the side's own. */
    private static final double SINK_FILM = 1e30;

    private ExchangerRating() {
    }

    /** One side: its curve, its mass flow (kg/s) and the specific enthalpy it arrives at (J/kg). */
    public record Side(HeatCurve curve, double massFlow, double inlet) {
        /** This side at {@code flow} times its mass flow: the same curve and the same inlet. */
        public Side at(double flow) {
            return new Side(curve, massFlow * flow, inlet);
        }

        /** A side that takes any heat at {@code temperature}, as the air takes it from an air cooler, whose films hold the air's. */
        public static Side sink(double temperature) {
            return new Side(new HeatCurve(new double[]{0, 1}, new double[]{temperature, temperature}, new double[]{SINK_FILM, SINK_FILM}), 1, 0);
        }
    }

    /** The surface (m²), infinite if the curves touch, and the least temperature difference along the exchanger (K). */
    public record Result(double area, double approach) {
    }

    /** {@code of} as a side, its curve across {@code low} to {@code high} K: as its fluid, for a heat curve holds no solids. */
    public static Side side(FlowThermo thermo, FlowStream of, double low, double high) {
        var solids = of.solids().moments();
        double fluid = of.mass() - solids.mass();
        return new Side(thermo.curve(of, of.pressure(), low - SPAN, high + SPAN), fluid, (of.enthalpy() - solids.enthalpy(of.temperature(), of.pressure())) / fluid);
    }

    /** The surface {@code duty} W needs. {@code x} is the heat passed so far, counted from the cold end. */
    public static Result rate(Side hot, Side cold, double duty) {
        double hotOut = hot.inlet - duty / hot.massFlow;
        var marks = new TreeSet<Double>();
        marks.add(0.0);
        marks.add(duty);
        for (int knot = 0; knot < cold.curve.knots(); knot++) mark(marks, cold.massFlow * (cold.curve.enthalpy(knot) - cold.inlet), duty);
        for (int knot = 0; knot < hot.curve.knots(); knot++) mark(marks, hot.massFlow * (hot.curve.enthalpy(knot) - hotOut), duty);
        double area = 0, approach = Double.POSITIVE_INFINITY, lastX = 0, lastDifference = 0, lastResistance = 0;
        boolean first = true;
        for (double x : marks) {
            double h = hotOut + x / hot.massFlow, c = cold.inlet + x / cold.massFlow;
            double difference = hot.curve.temperatureAt(h) - cold.curve.temperatureAt(c), resistance = 1 / hot.curve.filmAt(h) + 1 / cold.curve.filmAt(c);
            approach = Math.min(approach, difference);
            if (!(difference > 0)) return new Result(Double.POSITIVE_INFINITY, approach);
            if (!first) area += (x - lastX) * (resistance + lastResistance) / 2 / meanDifference(lastDifference, difference);
            first = false;
            lastX = x;
            lastDifference = difference;
            lastResistance = resistance;
        }
        return new Result(area, approach);
    }

    private static void mark(TreeSet<Double> marks, double x, double duty) {
        if (x > 0 && x < duty) marks.add(x);
    }

    /** The log mean of two positive differences: both temperatures run straight between marks. */
    private static double meanDifference(double a, double b) {
        return a == b ? a : (a - b) / DeterministicMath.log1p((a - b) / b);
    }

    /** The duty {@code area} m² passes, at most what takes the hot side to the cold inlet or the cold side to the hot inlet. */
    static double duty(Side hot, Side cold, double area, double hotInletTemperature, double coldInletTemperature) {
        double high = Math.min(hot.massFlow * (hot.inlet - hot.curve.lowestEnthalpyAt(coldInletTemperature)), cold.massFlow * (cold.curve.enthalpyAt(hotInletTemperature) - cold.inlet));
        double low = 0;
        for (int step = 0; step < STEPS; step++) {
            double middle = (low + high) / 2;
            if (rate(hot, cold, middle).area() <= area) low = middle;
            else high = middle;
        }
        return low;
    }
}
