package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.tutorial.cards.Card;
import com.wormzjl.icheme.science.tutorial.cards.Cards;
import com.wormzjl.icheme.science.tutorial.cards.ThreeParts;
import com.wormzjl.icheme.science.tutorial.guide.Progress;
import com.wormzjl.icheme.science.tutorial.scene.Track;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The card three_parts and its view (batch 2026-11-12-tutorial-system, P1): the light dots leave first, then the middle
 * ones, the heavy ones stay; the drawing stays inside its area with its fills in batches; the slider runs 0 to 1 and its
 * end completes the step once.
 */
class CardViewTest {
    /** A canvas that counts draws outside a batch and checks every fill lies in {@code area}. */
    static final class Counting implements Canvas {
        final Rect area;
        int depth, loose, fills;
        Rect outside;

        Counting(Rect area) {
            this.area = area;
        }

        @Override
        public Measure measure() {
            return Measure.fixed(6);
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills++;
            if (depth == 0) loose++;
            if (x1 > x0 && y1 > y0 && !new Rect(x0, y0, x1 - x0, y1 - y0).inside(area)) outside = new Rect(x0, y0, x1 - x0, y1 - y0);
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            if (depth == 0) loose++;
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
        }

        @Override
        public void batch(Runnable body) {
            depth++;
            body.run();
            depth--;
        }

        @Override
        public void clip(Rect area) {
        }

        @Override
        public void unclip() {
        }
    }

    @Test
    void theLightPartLeavesFirstThenTheMiddleAndTheHeavyStays() {
        int n = ThreeParts.COUNT;
        assertEquals(n, ThreeParts.inPot(Track.Kind.LIGHT, 0));
        assertEquals(n, ThreeParts.inPot(Track.Kind.MIDDLE, 0));
        for (int i = 0; i <= 100; i++) {
            double heat = i / 100.0;
            if (ThreeParts.inPot(Track.Kind.MIDDLE, heat) < n) assertEquals(0, ThreeParts.inPot(Track.Kind.LIGHT, heat), heat + ": the middle leaves after the light");
            assertEquals(n, ThreeParts.inPot(Track.Kind.HEAVY, heat), heat + ": the heavy stays");
        }
        assertEquals(0, ThreeParts.inPot(Track.Kind.LIGHT, 1));
        assertEquals(0, ThreeParts.inPot(Track.Kind.MIDDLE, 1));
        assertTrue(ThreeParts.inPot(Track.Kind.LIGHT, .5) < n && ThreeParts.inPot(Track.Kind.MIDDLE, .5) == n, "half heat: the light part on its way, the middle at home");
    }

    @Test
    void atHeatZeroTheThreeKindsAreMixedThroughTheLiquid() {
        double[] mean = new double[3];
        for (var kind : Track.Kind.values()) {
            for (int i = 0; i < ThreeParts.COUNT; i++) {
                var at = ThreeParts.place(kind, i);
                assertTrue(at[0] >= 0 && at[0] < 1 && at[1] >= 0 && at[1] < 1);
                mean[kind.ordinal()] += at[1] / ThreeParts.COUNT;
            }
        }
        double spread = Math.max(Math.max(mean[0], mean[1]), mean[2]) - Math.min(Math.min(mean[0], mean[1]), mean[2]);
        assertTrue(spread < .1, "the kinds' mean depths differ by " + spread + " of the liquid's depth: one mixed liquid");
    }

    @Test
    void theDrawingStaysInItsAreaInBatches() {
        var card = Cards.build(ThreeParts.NAME);
        assertNotNull(card);
        assertEquals(Card.Control.SLIDER, card.control());
        for (var area : new Rect[]{new Rect(100, 30, 330, 140), new Rect(70, 30, 176, 120)})
            for (double heat : new double[]{0, .3, .5, .75, 1})
                for (double phase : new double[]{0, 1.3, 7.7}) {
                    var c = new Counting(area);
                    card.draw(c, area, Card.State.of(phase, heat), PanelSkin.LIGHT, k -> "word");
                    assertNull(c.outside, area + " heat " + heat + ": a fill outside " + c.outside);
                    assertTrue(c.fills > 20);
                    assertEquals(3, c.loose, "only the legend's three words outside a batch");
                }
    }

    /** A card with two options, a five-step slider and three places to press, done when all three were pressed. */
    static final class Probe implements Card {
        @Override
        public void draw(Canvas c, Rect area, State state, com.wormzjl.icheme.client.gui.common.kit.Skin skin, java.util.function.Function<String, String> tr) {
        }

        @Override
        public String captionKey() {
            return "caption";
        }

        @Override
        public Control control() {
            return Control.SLIDER;
        }

        @Override
        public String controlKey() {
            return "slider";
        }

        @Override
        public java.util.List<String> textKeys() {
            return java.util.List.of();
        }

        @Override
        public java.util.List<String> options() {
            return java.util.List.of("one", "two");
        }

        @Override
        public int steps() {
            return 5;
        }

        @Override
        public java.util.List<Hit> hits(Rect area, int option) {
            return java.util.List.of(new Hit(0, new Rect(area.x(), area.y(), 10, 10)), new Hit(1, new Rect(area.x() + 20, area.y(), 10, 10)),
                    new Hit(2, new Rect(area.x() + 40, area.y(), 10, 10)));
        }

        @Override
        public boolean done(double control, java.util.Set<Integer> visited) {
            return visited.size() == 3;
        }
    }

    @Test
    void optionsHitsStepsAndDoneReachTheView() {
        var view0 = new CardView(new Probe(), () -> {
        });
        var area = new Rect(100, 50, 300, 120);
        var place = new GuidePage.CardPlace("x/y", view0.card(), java.util.List.of(), area, java.util.List.of(), 0, 0, "", 0, new Rect(120, 200, 200, 8),
                new GuidePage.Key("card:back", new Rect(340, 196, 48, 16), "Back", null, false));
        int[] ended = {0};
        var view = new CardView(place.card(), () -> ended[0]++);
        view.option(1);
        assertEquals(1, view.option());
        view.option(7);
        assertEquals(1, view.option(), "no such option");
        assertEquals(.25, place.card().stepped(.3), 1e-12, "five steps snap to quarters");
        assertTrue(view.press(place, area.x() + 22, area.y() + 2));
        assertEquals(1, view.hit());
        view.step(1, place);
        assertEquals(2, view.hit(), "Right steps to the next place");
        assertEquals(0, ended[0]);
        view.step(-1, place);
        view.step(-1, place);
        assertEquals(0, view.hit());
        assertEquals(java.util.Set.of(0, 1, 2), view.visited());
        assertEquals(1, ended[0], "every place pressed: done once");
        var state = view.state(Card.CELSIUS);
        assertEquals(1, state.option());
        assertEquals(0, state.hit());
        assertEquals("100 °C", state.temperature().apply(373.15));
        assertEquals("373 K", Card.temperature(com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit.KELVIN).apply(373.15));
        // The slider snaps to its steps.
        var s = place.slider();
        assertTrue(view.press(place, s.x() + s.w() * .3, s.y() + 4));
        assertEquals(.25, view.control(), 1e-12);
        view.release();
    }

    @Test
    void anOptionsCardPlacesItsKeysOverTheDrawing() {
        var page = GuidePageTest.page(Progress.NONE, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, "welcome/dots");
        assertTrue(page.card().options().isEmpty(), "three_parts has no options");
        assertEquals(0, Card.State.of(0, 0).option());
    }

    @Test
    void theSliderRunsToItsEndAndCompletesTheStepOnce() {
        var page = GuidePageTest.page(Progress.NONE, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, "welcome/dots");
        var place = page.card();
        int[] ended = {0};
        var view = new CardView(place.card(), () -> ended[0]++);
        var s = place.slider();
        assertTrue(view.press(place, s.x() + 1, s.y() + 4));
        assertEquals(0, view.control(), .05);
        assertTrue(view.drag(place, s.x() + s.w() / 2.0));
        assertEquals(.5, view.control(), .05);
        assertEquals(0, ended[0]);
        view.drag(place, s.right() + 50);
        assertEquals(1, view.control(), 1e-9);
        view.drag(place, s.x());
        view.drag(place, s.right());
        view.release();
        assertEquals(1, ended[0], "the end completes the step once");
        assertFalse(view.drag(place, s.x()), "released");
        assertFalse(view.press(place, place.back().rect().x() + 2, place.back().rect().y() + 2), "Back is no slider");
        view.advance(1_000_000_000L);
        view.advance(1_050_000_000L);
        assertEquals(.05, view.phase(), 1e-9);
    }
}
