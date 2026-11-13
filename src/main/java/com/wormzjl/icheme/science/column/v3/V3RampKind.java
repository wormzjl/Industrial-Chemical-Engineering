package com.wormzjl.icheme.science.column.v3;

/**
 * The kind of one authored-feature continuation rung of the calculator. The text is the rung's label in the solve
 * path ({@code <ramp path>/<text>-<progress>}) and in the energy-shift note, the same characters as before the kind
 * was typed.
 */
enum V3RampKind {
    /** Steam rungs are labelled by whether a draw ramp follows them; both are the same continuation. */
    STEAM("steam-ramp"),
    WET("wet-ramp"),
    HEAT("heat-ramp"),
    DRAW("draw-ramp"),
    COUPLED("coupled-ramp");

    private final String text;

    V3RampKind(String text) {
        this.text = text;
    }

    /** The rung's label text. */
    String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}
