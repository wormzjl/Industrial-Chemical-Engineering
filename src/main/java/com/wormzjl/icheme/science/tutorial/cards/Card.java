package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;

import java.util.List;
import java.util.function.Function;

/**
 * A concept card (batch 2026-11-12-tutorial-system, TUTORIAL_SYSTEM_PLAN.md section 3.2): one animated drawing on the
 * kit's canvas (Minecraft-free, so the render tool draws what the game does), its one-sentence caption, and a slider or a
 * switch where the idea has one. A card is a pure function of its phase and its control: it computes no process state.
 */
public interface Card {
    /** What the player moves on the card: nothing, a slider (0 to 1) or a switch (0 or 1). */
    enum Control {
        NONE, SLIDER, SWITCH
    }

    /**
     * Draws the card in {@code area} (on the HMI glass, the screens' dark palette) at {@code phase} (seconds of its
     * animation) with its control at {@code control} (0 to 1); {@code tr} gives a language key's line.
     */
    void draw(Canvas c, Rect area, double phase, double control, Skin skin, Function<String, String> tr);

    /** The caption's language key: one sentence. */
    String captionKey();

    Control control();

    /** The control's label's language key; null for {@link Control#NONE}. */
    String controlKey();

    /** Every language key the card draws besides its caption and its control's label. */
    List<String> textKeys();
}
