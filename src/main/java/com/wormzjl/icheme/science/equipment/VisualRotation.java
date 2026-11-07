package com.wormzjl.icheme.science.equipment;

/** A presentation clock, independent of the shaft solver: readable rotation with a continuous response to rpm. */
public final class VisualRotation {
    private VisualRotation() {
    }

    /** Same low-rpm slope, a stronger rise at high rpm: 1500 shows as 2.4 turns/s, 3000 as 4, approaching 6 smoothly. */
    public static double turnsPerSecond(double rpm) {
        if (!Double.isFinite(rpm) || rpm == 0) return 0;
        double speed = Math.abs(rpm) / 1500;
        return Math.copySign(6 / (1 + 3 / (speed * (1 + speed))), rpm);
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
