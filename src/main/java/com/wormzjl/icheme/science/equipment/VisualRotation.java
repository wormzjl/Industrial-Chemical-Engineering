package com.wormzjl.icheme.science.equipment;

/** A presentation clock, independent of the shaft solver: readable rotation with a continuous response to rpm. */
public final class VisualRotation {
    private VisualRotation() {
    }

    /** 1500 rpm shows as one turn/s, 3000 as 1.33; the speed approaches two turns/s without a hard cutoff. */
    public static double turnsPerSecond(double rpm) {
        if (!Double.isFinite(rpm)) return 0;
        return 2 * rpm / (1500 + Math.abs(rpm));
    }

    /** Client-thread-owned accumulated angle. Changing or stopping the speed keeps the current angle. */
    public static final class Clock {
        private double turns, previous = Double.NaN;

        /** A disconnected line keeps its current angle and then advances independently. Client-thread only. */
        public Clock copy() {
            var copy = new Clock();
            copy.turns = turns;
            copy.previous = previous;
            return copy;
        }

        public double at(double seconds, double rpm) {
            return advance(seconds, turnsPerSecond(rpm));
        }

        /** The same angle accumulator for authored slow machinery and speed-controlled fans. */
        public double advance(double seconds, double turnsPerSecond) {
            if (Double.isFinite(previous) && seconds < previous) return turns;
            if (Double.isFinite(previous) && seconds >= previous) {
                turns += (seconds - previous) * turnsPerSecond;
                turns -= Math.floor(turns);
            }
            previous = seconds;
            return turns;
        }
    }
}
