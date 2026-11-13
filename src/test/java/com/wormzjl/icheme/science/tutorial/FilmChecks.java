package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.tutorial.FilmLayout;
import com.wormzjl.icheme.client.gui.tutorial.FilmOverlay;
import com.wormzjl.icheme.science.tutorial.scene.Frame;
import com.wormzjl.icheme.science.tutorial.scene.Projection;
import com.wormzjl.icheme.science.tutorial.scene.Scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What every film's stops must give (batch 2026-11-12-tutorial-system, P2b): one caption a stop, of one sentence of at
 * most 25 words and no number; an English line for every key it shows; and every label placed on the glass at the
 * design size and at 320 by 240.
 */
final class FilmChecks {
    /** A fixed-width font wider than the game font's mean, so a label that fits here fits in the game. */
    static final Measure M = Measure.fixed(6);

    private FilmChecks() {
    }

    static void everyStopReads(Scene scene) {
        var lang = TutorialText.english();
        assertTrue(lang.containsKey(scene.title()), scene.title());
        for (var key : scene.captionKeys()) {
            var text = lang.get(key);
            assertNotNull(text, key);
            assertTrue(text.endsWith("."), key);
            assertEquals(1, TutorialText.sentences(text), key + ": one sentence");
            assertTrue(TutorialText.words(text) <= 25, key + ": at most 25 words");
            assertFalse(text.matches(".*\\d.*"), key + ": no number");
        }
        for (double stop : scene.stops()) {
            var frame = scene.at(stop);
            assertEquals(1, frame.captions().size(), scene.title() + ": one caption at " + stop);
            var keys = new ArrayList<String>();
            frame.labels().forEach(l -> keys.add(l.key()));
            frame.readouts().forEach(r -> keys.add(r.key()));
            for (var key : keys) assertTrue(lang.containsKey(key), key);
            Function<String, String> tr = k -> lang.getOrDefault(k, k);
            for (var size : List.of(new int[]{FilmLayout.DESIGN_WIDTH, FilmLayout.DESIGN_HEIGHT}, new int[]{320, 240})) {
                var placed = layout(scene, frame, size[0], size[1], tr);
                for (int i = 0; i < placed.labels().size(); i++)
                    assertNotNull(placed.labels().get(i), scene.title() + " at " + stop + ", " + size[0] + " by " + size[1] + ": label " + frame.labels().get(i).key() + " placed");
            }
        }
    }

    /** The overlays of {@code frame} as the film screen lays them out on a panel {@code w} by {@code h}. */
    static FilmOverlay.Layout layout(Scene scene, Frame frame, int w, int h, Function<String, String> tr) {
        var captions = new ArrayList<String>();
        for (var key : scene.captionKeys()) captions.add(tr.apply(key));
        var layout = FilmLayout.of(w, h, scene.stops().size(), FilmLayout.captionLines(captions, w, M));
        var glass = FilmLayout.glass(PanelSkin.LIGHT, layout.scene());
        return FilmOverlay.layout(frame, projection(frame, glass), glass, M, tr, k -> String.format(Locale.ROOT, "%.0f °C", k - 273.15));
    }

    /** The GUI-px projection of {@code frame} on {@code glass}. */
    static Projection projection(Frame frame, Rect glass) {
        return Projection.of(frame.camera(), 0, 0, 1, FilmLayout.fit(glass), glass.x() + glass.w() / 2.0, glass.y() + glass.h() / 2.0);
    }
}
