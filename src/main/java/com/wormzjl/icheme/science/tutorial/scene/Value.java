package com.wormzjl.icheme.science.tutorial.scene;

/**
 * A number over a film's time (batch 2026-11-12-tutorial-system, P2b): a constant, a {@link Series} on its own time, or
 * a series replayed through a {@link Timeline} (a film's stops mapped onto stretches of a recording). A pure function.
 */
@FunctionalInterface
public interface Value {
    double at(double t);

    static Value of(double constant) {
        return t -> constant;
    }

    /**
     * A film's time mapped onto a recording's: ascending film times {@code film} and their recording times
     * {@code recording}, linear between keys and held at the ends. A stop's stretch of film maps onto a stretch of the
     * recording, so a film compresses a long run.
     */
    record Timeline(double[] film, double[] recording) {
        public Timeline {
            if (film.length == 0 || film.length != recording.length) throw new IllegalArgumentException("A timeline needs as many recording keys as film keys");
            for (int i = 1; i < film.length; i++)
                if (!(film[i] > film[i - 1]) || !(recording[i] >= recording[i - 1])) throw new IllegalArgumentException("Timeline keys out of order");
            film = film.clone();
            recording = recording.clone();
        }

        /** The recording's time at film time {@code t}. */
        public double recording(double t) {
            if (t <= film[0]) return recording[0];
            for (int i = 1; i < film.length; i++)
                if (t <= film[i]) return recording[i - 1] + (recording[i] - recording[i - 1]) * (t - film[i - 1]) / (film[i] - film[i - 1]);
            return recording[recording.length - 1];
        }

        /** {@code series} replayed on the film's time. */
        public Value replay(Value series) {
            return t -> series.at(recording(t));
        }
    }
}
