package com.wormzjl.icheme.client.gui.tutorial;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.common.TitleStrip;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.Progress;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The field guide's page (batch 2026-11-12-tutorial-system, P1, P2a) at the design size, 464 by 254, and at the smallest
 * GUI window's panel, 312 by 232 (320 by 240 less 8), with both chapters open in turn: rows inside their areas, no text
 * wider than its row, marks and keys in line, the current step the first not done, each card's parts inside the glass.
 */
class GuidePageTest {
    static final Measure M = Measure.fixed(6);
    static final int[][] SIZES = {{GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT}, {312, 232}};

    static Function<String, String> english() {
        try (var in = GuidePageTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            var json = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> lang = new HashMap<>();
            for (var e : json.entrySet()) lang.put(e.getKey(), e.getValue().getAsString());
            return key -> {
                assertTrue(lang.containsKey(key), "an English line for " + key);
                return lang.get(key);
            };
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final List<String> CHAPTERS = List.of("welcome", "crude");

    /** The three states the renders show: new, step 3 current, every step done. */
    static List<Progress> states() {
        var all = new java.util.HashSet<String>();
        GuideFile.load().stepIds().forEach(all::add);
        return List.of(Progress.NONE, new Progress(Set.of("welcome/guide", "welcome/units")), new Progress(all));
    }

    /** The page with {@code card}'s chapter open (the first where none). */
    static GuidePage.Page page(Progress progress, int w, int h, String card) {
        return page(progress, w, h, card == null ? "welcome" : card.substring(0, card.indexOf('/')), card);
    }

    static GuidePage.Page page(Progress progress, int w, int h, String open, String card) {
        return GuidePage.place(new GuidePage.Input(GuideFile.load(), progress, open, card), w, h, M, english(), "Light panel");
    }

    @Test
    void theStepsStandInsideTheGlassInLineAndUncut() {
        for (var size : SIZES)
            for (var open : CHAPTERS)
            for (var progress : states()) {
                var p = page(progress, size[0], size[1], open, null);
                String at = size[0] + " by " + size[1] + ", " + open + ", " + progress.done().size() + " done";
                var panel = new Rect(0, 0, size[0], size[1]);
                assertTrue(p.well().inside(panel) && p.view().inside(p.well()), at + ": the glass in the panel");
                assertTrue(p.strip().plate().right() + TitleStrip.GAP <= p.help().x() && p.help().right() < p.theme().x(), at + ": the title, then help, then theme");
                assertEquals(new Rect(size[0] - 28, 5, 18, 18), p.theme(), at + ": the theme key where every screen has it");
                assertEquals(CHAPTERS.stream().map(c -> "chapter:" + c).toList(), List.copyOf(p.chapters().keySet()), at + ": both chapters on the door");
                Rect previous = null;
                for (var key : p.chapters().values()) {
                    assertTrue(key.rect().inside(new Rect(10, GuidePage.TOP, GuidePage.doorWidth(size[0]), size[1])), at + ": a chapter key in the door");
                    assertTrue(M.width(key.text()) <= key.rect().w() - 6, at + ": the chapter's word whole: " + key.text());
                    if (previous != null) assertFalse(key.rect().overlaps(previous), at + ": chapter keys apart");
                    previous = key.rect();
                    assertEquals(key.key().equals("chapter:" + open), key.latched(), at + ": the open chapter latched");
                }
                int right = p.bar() ? p.barTrack().x() : p.view().right();
                Integer keysRight = null;
                Rect last = null;
                assertEquals(GuideFile.load().chapter(open).steps().size(), p.rows().size(), at);
                for (var row : p.rows()) {
                    String r = at + ", " + row.step();
                    assertTrue(row.rect().x() >= p.view().x() && row.rect().right() <= right, r + ": the row in the glass " + row.rect());
                    if (last != null) assertTrue(row.rect().y() >= last.bottom(), r + ": below the step before");
                    last = row.rect();
                    var first = row.lines().getFirst();
                    assertEquals(first.y(), row.markRect().y(), r + ": the mark on the first line");
                    assertTrue(row.markRect().right() < first.x(), r + ": the mark before the text");
                    for (var line : row.lines()) {
                        assertTrue(M.width(line.text()) <= row.textWidth(), r + ": a line within its width: " + line.text());
                        assertTrue(line.x() + row.textWidth() <= right, r + ": its width in the glass");
                        for (var key : row.keys()) assertFalse(new Rect(line.x(), line.y(), M.width(line.text()), 9).overlaps(key.rect()), r + ": text off its key");
                    }
                    for (var key : row.keys()) {
                        assertTrue(key.rect().inside(row.rect()), r + ": its key in its row");
                        assertTrue(M.width(key.text()) <= key.rect().w() - 6, r + ": its key's word whole");
                        boolean beside = key.rect().x() > first.x() + row.textWidth();
                        if (beside) {
                            if (keysRight == null) keysRight = key.rect().right();
                            assertEquals(keysRight, key.rect().right(), r + ": the keys beside in one column");
                        } else assertTrue(key.rect().x() == first.x() || key.rect().x() > first.x(), r + ": a key under its text from its left");
                    }
                }
                assertTrue(p.content() > 0);
            }
    }

    @Test
    void theCurrentStepIsTheFirstNotDone() {
        var guide = GuideFile.load();
        for (var open : CHAPTERS)
            for (var progress : states()) {
                var p = page(progress, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, open, null);
                String current = progress.current(guide);
                for (var row : p.rows()) {
                    var expected = progress.done(row.step()) ? GuidePage.Mark.DONE : row.step().equals(current) ? GuidePage.Mark.CURRENT : GuidePage.Mark.OPEN;
                    assertEquals(expected, row.mark(), row.step());
                }
                for (var chapter : guide.chapters())
                    assertEquals(chapter.steps().stream().allMatch(s -> progress.done(chapter.id(s))), p.finished().get("chapter:" + chapter.id()), chapter.id() + ": its done mark");
            }
        assertEquals("welcome/help", states().get(1).current(guide));
        assertEquals("crude/what", new Progress(Set.of("welcome/guide", "welcome/units", "welcome/help")).current(guide), "chapter 1 follows chapter 0");
    }

    @Test
    void theMediaKeysAndTheTermStandWhereTheStepsAsk() {
        var p = page(Progress.NONE, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, null);
        var anchors = p.anchors();
        for (var key : List.of("title", "help", "theme", "chapters", "chapter:welcome", "chapter:crude", "step", "media", "units:use", "units:keep"))
            assertTrue(anchors.containsKey(key), key);
        assertEquals("units:use", p.action(anchors.get("units:use").x() + 2, anchors.get("units:use").y() + 2, 0));
        assertEquals("help", p.action(p.help().x() + 2, p.help().y() + 2, 0));
        assertNull(p.action(anchors.get("chapter:welcome").x() + 2, anchors.get("chapter:welcome").y() + 2, 0), "the open chapter's key is latched");
        var crude = page(Progress.NONE, GuidePage.DESIGN_WIDTH, GuidePage.DESIGN_HEIGHT, "crude", null);
        for (var step : GuideFile.load().chapter("crude").steps()) assertTrue(crude.anchors().containsKey("media:crude/" + step.id()), step.id());
        var terms = new java.util.ArrayList<String>();
        for (var row : crude.rows()) for (var l : row.lines()) for (var u : l.underlines()) if (!terms.contains(u.term())) terms.add(u.term());
        assertEquals(List.of("hydrocarbon", "gas", "naphtha", "kerosene", "diesel", "gas_oil", "residue", "still"), terms, "every term underlined, in order");
        var first = crude.rows().getFirst();
        var underline = first.lines().stream().flatMap(l -> l.underlines().stream()).findFirst().orElseThrow();
        var line = first.lines().stream().filter(l -> !l.underlines().isEmpty()).findFirst().orElseThrow();
        assertTrue(crude.hover(underline.x0() + 1, line.y() + 2, 0).startsWith("hydrocarbon: A molecule"), "the term's hover is its definition");
    }

    @Test
    void anOpenCardStandsInsideTheGlass() {
        for (var size : SIZES)
            for (var step : List.of("crude/what", "crude/barrel", "crude/why", "crude/road", "crude/parts")) {
            var p = page(Progress.NONE, size[0], size[1], step);
            var c = p.card();
            assertNotNull(c, step);
            assertTrue(p.rows().isEmpty(), "the card stands in place of the steps");
            for (var r : List.of(c.drawing(), c.slider(), c.back().rect())) assertTrue(r.inside(p.view()), size[0] + " " + step + ": " + r + " in " + p.view());
            assertTrue(c.slider().right() < c.back().rect().x(), "the slider before Back");
            assertEquals(c.card().options().size(), c.options().size(), step);
            for (int i = 0; i < c.options().size(); i++) {
                var key = c.options().get(i);
                assertTrue(key.rect().inside(p.view()) && key.rect().bottom() <= c.drawing().y(), step + ": option " + i + " over the drawing");
                assertTrue(M.width(key.text()) <= key.rect().w() - 6, size[0] + " " + step + ": the option's word whole: " + key.text());
                if (i > 0) assertFalse(key.rect().overlaps(c.options().get(i - 1).rect()));
                assertEquals(GuidePage.CardPlace.OPTION + i, p.action(key.rect().x() + 2, key.rect().y() + 2, 0));
            }
            for (var hit : c.card().hits(c.drawing(), 0)) assertTrue(hit.rect().inside(c.drawing()), size[0] + " " + step + ": a place to press in the drawing " + hit.rect() + " in " + c.drawing());
            assertTrue(c.drawing().bottom() <= c.captionY(), "the caption under the drawing");
            for (var line : c.caption()) assertTrue(M.width(line) <= p.view().w() - 8, "the caption wraps: " + line);
            assertTrue(c.captionY() + c.caption().size() * Measure.LINE <= c.back().rect().y(), "the caption over the slider row");
            assertEquals(0, c.control(c.slider().x()), 1e-9);
            assertEquals(1, c.control(c.slider().right()), 1e-9);
            assertTrue(c.knob(1).inside(new Rect(c.slider().x(), c.slider().y() - 5, c.slider().w(), 18)));
            assertEquals("card:back", p.action(c.back().rect().x() + 2, c.back().rect().y() + 2, 0));
        }
    }

    @Test
    void theHudLineIsCutWithAnEllipsis() {
        assertEquals("Short line.", GuideHud.line("Short line.", 300, M));
        var cut = GuideHud.line("This guide teaches you one small step at a time. A step's mark fills when you have done it.", 120, M);
        assertTrue(cut.endsWith(GuideHud.ELLIPSIS) && M.width(cut) <= 120, cut);
    }
}
