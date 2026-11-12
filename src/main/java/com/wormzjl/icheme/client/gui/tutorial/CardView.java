package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.science.tutorial.cards.Card;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleFunction;

/**
 * An open card's state on a screen (batch 2026-11-12-tutorial-system): its phase, which runs with time; its control,
 * which the player drags on the slider (snapped to the card's steps) or flips on the switch; the chosen option; the last
 * place pressed and every place pressed so far. {@link GuidePage} places and draws the card; this keeps what moves, with
 * no Minecraft class. {@code onEnd} runs once, the first time the card says its step is done ({@link Card#done}).
 */
public final class CardView {
    /** The longest step the phase takes between two frames, s. */
    private static final double MAX_STEP = .1;
    private final Card card;
    private final Runnable onEnd;
    private double phase, control;
    private int option, hit = -1;
    private final Set<Integer> visited = new TreeSet<>();
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

    public int option() {
        return option;
    }

    public int hit() {
        return hit;
    }

    public Set<Integer> visited() {
        return Set.copyOf(visited);
    }

    /** What the card draws now, temperatures in {@code temperature}'s format. */
    public Card.State state(DoubleFunction<String> temperature) {
        return new Card.State(phase, control, option, hit, visited, temperature);
    }

    /** Advances the phase to now ({@code nanos}, as {@code System.nanoTime}). */
    public void advance(long nanos) {
        if (last >= 0) phase += Math.min(MAX_STEP, (nanos - last) / 1e9);
        last = nanos;
    }

    /** Chooses option {@code index} (an option key pressed). */
    public void option(int index) {
        if (index >= 0 && index < card.options().size()) option = index;
    }

    /** A press at panel point ({@code x}, {@code y}) on the card at {@code place}: a place on the drawing, the knob or the slider. */
    public boolean press(GuidePage.CardPlace place, double x, double y) {
        var hits = card.hits(place.drawing(), option);
        for (var h : hits)
            if (h.rect().contains(x, y)) {
                visit(h.index());
                return true;
            }
        if (card.control() == Card.Control.NONE) return false;
        var s = place.slider();
        boolean on = place.knob(control).contains(x, y) || x >= s.x() && x < s.right() && y >= s.y() - 5 && y < s.bottom() + 5;
        if (!on) return false;
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

    /**
     * Left ({@code -1}) or Right ({@code +1}): the next place to press where the card has places, else the slider's next
     * step (a tenth where it is continuous).
     */
    public void step(int delta, GuidePage.CardPlace place) {
        int count = card.hits(place.drawing(), option).size();
        if (count > 0) {
            visit(Math.clamp(hit < 0 ? (delta > 0 ? 0 : count - 1) : hit + delta, 0, count - 1));
            return;
        }
        if (card.control() == Card.Control.NONE) return;
        double step = card.steps() > 1 ? 1.0 / (card.steps() - 1) : .1;
        set(control + delta * step);
    }

    private void visit(int index) {
        hit = index;
        visited.add(index);
        check();
    }

    private void set(double value) {
        control = card.stepped(value);
        check();
    }

    private void check() {
        if (!ended && card.done(control, visited)) {
            ended = true;
            onEnd.run();
        }
    }
}
