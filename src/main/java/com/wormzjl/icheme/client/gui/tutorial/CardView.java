package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.science.tutorial.cards.Card;

/**
 * An open card's state on a screen (batch 2026-11-12-tutorial-system, P1): its phase, which runs with time, and its
 * control, which the player drags on the slider or flips on the switch. {@link GuidePage} places and draws the card; this
 * keeps what moves. {@code onEnd} runs once, the first time the control reaches its end.
 */
public final class CardView {
    /** The longest step the phase takes between two frames, s. */
    private static final double MAX_STEP = .1;
    private final Card card;
    private final Runnable onEnd;
    private double phase, control;
    private long last = -1;
    private boolean dragging, ended;

    public CardView(Card card, Runnable onEnd) {
        this.card = card;
        this.onEnd = onEnd;
    }

    public Card card() {
        return card;
    }

    public double phase() {
        return phase;
    }

    public double control() {
        return control;
    }

    /** Advances the phase to now ({@code nanos}, as {@code System.nanoTime}). */
    public void advance(long nanos) {
        if (last >= 0) phase += Math.min(MAX_STEP, (nanos - last) / 1e9);
        last = nanos;
    }

    /** A press at panel point ({@code x}, {@code y}) on the card at {@code place}: takes the knob or the slider. */
    public boolean press(GuidePage.CardPlace place, double x, double y) {
        if (card.control() == Card.Control.NONE) return false;
        var s = place.slider();
        boolean hit = place.knob(control).contains(x, y) || x >= s.x() && x < s.right() && y >= s.y() - 5 && y < s.bottom() + 5;
        if (!hit) return false;
        if (card.control() == Card.Control.SWITCH) set(control < .5 ? 1 : 0);
        else {
            dragging = true;
            set(place.control(x));
        }
        return true;
    }

    public boolean drag(GuidePage.CardPlace place, double x) {
        if (!dragging) return false;
        set(place.control(x));
        return true;
    }

    public void release() {
        dragging = false;
    }

    public boolean dragging() {
        return dragging;
    }

    private void set(double value) {
        control = Math.clamp(value, 0, 1);
        if (control >= 1 && !ended) {
            ended = true;
            onEnd.run();
        }
    }
}
