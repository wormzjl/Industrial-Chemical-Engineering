package com.wormzjl.icheme.client.gui.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.display.DisplayUnit;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleFunction;
import java.util.function.Function;

/**
 * A concept card (batch 2026-11-12-tutorial-system, TUTORIAL_SYSTEM_PLAN.md section 3.2): one animated drawing on the
 * kit's canvas (Minecraft-free, so the render tool draws what the game does), its one-sentence caption, and what the
 * player works on it: a slider (continuous or stepped) or a switch, a row of options (soft keys over the card), and
 * places on the drawing to press. A card is a pure function of its {@link State}: it computes no process state and reads
 * no file while it draws (a recording is read once, when the card is built).
 */
public interface Card {
    /** What the player moves on the card: nothing, a slider (0 to 1) or a switch (0 or 1). */
    enum Control {
        NONE, SLIDER, SWITCH
    }

    /** A place on the drawing the player can press, and its index. */
    record Hit(int index, Rect rect) {
    }

    /**
     * What the card draws: its animation's {@code phase} (s), its {@code control} (0 to 1, already stepped), the chosen
     * {@code option} (0 with none), the last pressed hit ({@code -1}: none), the hits pressed so far, and the client's
     * temperature format (kelvin to text with its unit); P4b: its mass flow format (kg/s to text with its unit, in the
     * client's flow time unit).
     */
    record State(double phase, double control, int option, int hit, Set<Integer> visited, DoubleFunction<String> temperature, DoubleFunction<String> massFlow) {
        public State {
            visited = Set.copyOf(visited);
        }

        /** With mass flows per hour. */
        public State(double phase, double control, int option, int hit, Set<Integer> visited, DoubleFunction<String> temperature) {
            this(phase, control, option, hit, visited, temperature, PER_HOUR);
        }

        /** At rest: phase 0, the control at {@code control}, the first option, nothing pressed, °C. */
        public static State of(double phase, double control) {
            return new State(phase, control, 0, -1, Set.of(), CELSIUS);
        }

        public State withOption(int value) {
            return new State(phase, control, value, hit, visited, temperature, massFlow);
        }

        public State withHit(int value) {
            return new State(phase, control, option, value, visited, temperature, massFlow);
        }
    }

    /** Kelvin in whole degrees of {@code unit}, with its symbol. */
    static DoubleFunction<String> temperature(DisplayUnit.TemperatureUnit unit) {
        return com.wormzjl.icheme.science.tutorial.guide.Guide.temperature(unit);
    }

    /** Whole degrees Celsius: the render tool's format, and the client's default. */
    DoubleFunction<String> CELSIUS = com.wormzjl.icheme.science.tutorial.guide.Guide.CELSIUS;

    /** A mass flow (kg/s) in {@code unit}'s time: three significant figures, thousands apart, and its unit (kg/h, kg/min, kg/s). */
    static DoubleFunction<String> massFlow(DisplayUnit.FlowRateUnit unit) {
        return kgPerSecond -> {
            double v = unit.display(kgPerSecond);
            double step = v <= 0 ? 1 : Math.pow(10, Math.max(0, Math.floor(Math.log10(v)) - 2));
            double shown = Math.round(v / step) * step;
            String number = step >= 1 ? String.format(Locale.ROOT, "%,d", Math.round(shown)).replace(',', ' ')
                    : String.format(Locale.ROOT, "%." + (int) Math.ceil(-Math.log10(step)) + "f", shown);
            return number + " " + unit.unit("kg");
        };
    }

    /** Mass flows per hour: the render tool's format, and the client's default. */
    DoubleFunction<String> PER_HOUR = massFlow(DisplayUnit.FlowRateUnit.PER_HOUR);

    /** Draws the card in {@code area} (on the HMI glass, the screens' dark palette) in {@code state}; {@code tr} gives a language key's line. */
    void draw(Canvas c, Rect area, State state, Skin skin, Function<String, String> tr);

    /** The caption's language key: one sentence. */
    String captionKey();

    Control control();

    /** The control's label's language key; null for {@link Control#NONE}. */
    String controlKey();

    /** Every language key the card draws besides its caption, its control's label and its options. */
    List<String> textKeys();

    /** The options' language keys, drawn as soft keys over the card, the chosen one latched; none by default. */
    default List<String> options() {
        return List.of();
    }

    /** The places on the drawing in {@code area} the player can press with {@code option} chosen; none by default. */
    default List<Hit> hits(Rect area, int option) {
        return List.of();
    }

    /** P5: the equipment page place {@code hit} opens (an item id, {@code pages.json}); null for none, by default. */
    default @org.jetbrains.annotations.Nullable String link(int hit) {
        return null;
    }

    /** The slider's positions (0: continuous); a stepped slider snaps to {@code steps} evenly spaced positions from 0 to 1. */
    default int steps() {
        return 0;
    }

    /** The slider value at {@code control} after its steps. */
    default double stepped(double control) {
        double value = Math.clamp(control, 0, 1);
        return steps() > 1 ? Math.round(value * (steps() - 1)) / (double) (steps() - 1) : value;
    }

    /**
     * Whether the card's step is done, from the stepped control and the hits pressed so far: by default the slider at its
     * end (a card with no control at once); a card whose places are pressed says when every one was.
     */
    default boolean done(double control, Set<Integer> visited) {
        return control() == Control.NONE || control >= 1;
    }
}
