package com.wormzjl.icheme.runtime.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The level law ({@link InventoryControl}) closed on a drum that is a pure integrator (code base review,
 * LEVEL_LOOP_REVIEW.md, options A1 and A2). The drum is at its setpoint on the level loop GameTest's design feed,
 * 0.1988 m³/s, when the feed steps +50 %. The loop decides every n cadences of process time (T = 20 s). Each decision
 * acts f cadences after the sample it read, as an output that waits for the island's next slice. The valve passes its
 * target at once, and the balance's mean flow is the volume it passed between the two samples. The simulation is exact:
 * the flows are constant between events, so the volume is integrated without a step size.
 *
 * <p>The poles of the linear loop are those of e_{j+1} = e_j - a[(1 - p) e_{j-m} + p e_{j-m-1}]. Here a = min(n, 1) is
 * the share of the error one decision corrects, f/n = m + p is the dead time in decisions, and the step disturbance
 * cancels in the exact balance. At n = 1 this is z² - f z + f, |z| = √f. Radii per decision, computed from the
 * polynomials:
 * <pre>
 *   f:        0       0.3     0.6     0.9
 *   n = 0.5   0.5000  0.5477  0.7720  0.9070
 *   n = 1     0       0.5477  0.7746  0.9487   (√f)
 *   n = 2     0       0.3873  0.5477  0.6708   (√(f/2))
 *   n = 3     0       0.3162  0.4472  0.5477   (√(f/3))
 * </pre>
 * The error does not halve every cadence at every point. The radius per cadence is the radius per decision to the
 * power 1/n, and it is 0.5 or less only for f = 0, and for n = 0.5 with f = 0.3; at n = 1 and f = 0.6 it is 0.77. So the
 * test asserts each case's own pole bound: the largest error over decisions 32 to 41 after the step is at most the
 * largest over decisions 2 to 11, times (radius + 0.03)^30, with 1e-9 m³ of floating-point floor. This leaves at least
 * a factor 1.8 of margin for the phase of the oscillation. The peak lies between two exact bounds. The lower bound is
 * the feed step times the time to the first post-step decision's action: until then the outflow is the design feed.
 * The upper bound runs to the second decision's action: from there the balance is exact and the outflow exceeds the
 * feed while the error is positive.
 *
 * <p>The law before this change balanced on the flow at the sample's end. Its poles at n = 1 are those of
 * z³ - 2f z² + 3f z - f, stable only for f < (3 - √5)/2 = 0.382. At f = 0.6 its linear radius is 1.2156 per cadence, so
 * the error grows 22 % a cadence. In this simulation it rings at about ±8 m³ on the 0.1 m³/s step, with the valve
 * shut in 11 of 40 decisions. At f = 0.4 its windowed error grows 16 % in 10 cadences.
 */
class InventoryControlLoopTest {
    private static final double T = 20, FEED = .1988, STEP = 1.5 * FEED, SETPOINT = 40;
    private static final int DECISIONS = 60;

    @ParameterizedTest(name = "n = {0} cadences, f = {1} cadence")
    @CsvSource({
        "0.5, 0,   0.5000", "0.5, 0.3, 0.5477", "0.5, 0.6, 0.7720", "0.5, 0.9, 0.9070",
        "1,   0,   0",      "1,   0.3, 0.5477", "1,   0.6, 0.7746", "1,   0.9, 0.9487",
        "2,   0,   0",      "2,   0.3, 0.3873", "2,   0.6, 0.5477", "2,   0.9, 0.6708",
        "3,   0,   0",      "3,   0.3, 0.3162", "3,   0.6, 0.4472", "3,   0.9, 0.5477"})
    void aFeedStepDecaysAtTheLoopsPoles(double n, double f, double radius) {
        double tau = n * T, dead = f * T, stepAt = 5.37 * tau;
        var loop = new Drum(stepAt);
        // The controller's earlier sample, one decision back at steady state: the volume at its setpoint, the valve's
        // totaliser one decision's design feed behind.
        double priorVolume = SETPOINT, priorPassed = -FEED * tau;
        var pending = new ArrayDeque<double[]>();
        var errors = new ArrayList<Double>();
        for (int k = 0; k <= DECISIONS; k++) {
            double t = k * tau;
            loop.advance(t, pending);
            double mean = Math.max(0, (loop.passed - priorPassed) / tau);
            double horizon = InventoryControl.horizon(T, tau, Double.NaN);
            double target = InventoryControl.target(loop.volume, SETPOINT, mean, priorVolume, tau, horizon, true);
            // The pole bound is the linear loop's: the valve never shuts.
            assertTrue(target > 0, "The valve shut at decision " + k);
            pending.addLast(new double[] {t + dead, target});
            priorVolume = loop.volume;
            priorPassed = loop.passed;
            if (t > stepAt) errors.add(loop.volume - SETPOINT);
        }
        int first = (int) Math.floor(stepAt / tau) + 1;
        double lower = (STEP - FEED) * (first * tau + dead - stepAt), upper = (STEP - FEED) * ((first + 1) * tau + dead - stepAt);
        assertTrue(loop.peak >= lower - 1e-9 && loop.peak <= upper + 1e-9,
                "Peak " + loop.peak + " m³ outside [" + lower + ", " + upper + "]");
        double early = largest(errors, 2, 12), late = largest(errors, 32, 42);
        double bound = Math.max(early * Math.pow(radius + .03, 30), 1e-9);
        assertTrue(late <= bound, "Decisions 32 to 41 hold " + late + " m³, over " + bound + " (from " + early + " m³)");
    }

    private static double largest(java.util.List<Double> errors, int from, int to) {
        double m = 0;
        for (int i = from; i < to; i++) m = Math.max(m, Math.abs(errors.get(i)));
        return m;
    }

    /** The drum: its volume and the valve's totaliser, integrated exactly between the feed step and the valve's actions. */
    private static final class Drum {
        final double stepAt;
        double time, volume = SETPOINT, passed, outflow = FEED, feed = FEED, peak;

        Drum(double stepAt) {
            this.stepAt = stepAt;
        }

        void advance(double to, ArrayDeque<double[]> pending) {
            while (true) {
                double next = to;
                int kind = 0;
                if (feed == FEED && stepAt < next) {next = stepAt;kind = 1;}
                if (!pending.isEmpty() && pending.peekFirst()[0] < next) {next = pending.peekFirst()[0];kind = 2;}
                volume += (feed - outflow) * (next - time);
                passed += outflow * (next - time);
                time = next;
                peak = Math.max(peak, volume - SETPOINT);
                if (kind == 0) return;
                if (kind == 1) feed = STEP;
                else outflow = pending.removeFirst()[1];
            }
        }
    }
}
