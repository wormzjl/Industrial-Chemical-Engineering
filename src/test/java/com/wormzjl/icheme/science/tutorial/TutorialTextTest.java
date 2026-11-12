package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.cards.Cards;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.guide.Glossary;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.Marked;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every guide, tour, card (with its options) and film text (batch 2026-11-12-tutorial-system, P1, P2a; plan section 4): its
 * English line, at most two sentences and 25 words (a film caption and a tour stop one sentence), no professional word
 * unmarked, and every marked term in the glossary, defined on the step of its first use in guide order. P2b: a
 * professional word stands where it is a glossary term: marked, in the term's own entry, or in the caption of a film
 * a step shows once an earlier step (or the same) has marked it.
 */
class TutorialTextTest {
    private static final Map<String, String> LANG = TutorialText.english();
    /** Words a new player does not know: each must come as a marked term once the glossary holds it. */
    private static final List<String> PROFESSIONAL = List.of("distillate", "reflux", "fraction", "condenser", "feedstock", "volatile", "vaporise");

    /** The texts by key, with how many sentences each may hold. */
    private static Map<String, Integer> texts() {
        var texts = new LinkedHashMap<String, Integer>();
        var guide = GuideFile.load();
        for (var chapter : guide.chapters()) {
            texts.put(chapter.titleKey(), 1);
            for (var step : chapter.steps()) texts.put(chapter.key(step), 2);
        }
        for (var tour : TourFile.load().values())
            for (int i = 0; i < tour.stops().size(); i++) texts.put(tour.key(i), 1);
        for (var name : Cards.names()) {
            var card = Cards.build(name);
            texts.put(card.captionKey(), 1);
            if (card.controlKey() != null) texts.put(card.controlKey(), 1);
            for (var key : card.textKeys()) texts.put(key, 1);
            for (var key : card.options()) texts.put(key, 1);
        }
        for (var name : Films.names())
            for (var key : Films.build(name).captionKeys()) texts.put(key, 1);
        for (var term : guide.terms().keySet()) {
            texts.put(Glossary.wordKey(term), 1);
            texts.put(Glossary.definitionKey(term), 2);
        }
        return texts;
    }

    @Test
    void everyTextHasItsEnglishLineWithinTheLimits() {
        var texts = texts();
        assertTrue(texts.size() > 20, "the guide, tours, card and film are read");
        var faults = new ArrayList<String>();
        for (var e : texts.entrySet()) {
            var line = LANG.get(e.getKey());
            if (line == null) {
                faults.add(e.getKey() + ": no English line");
                continue;
            }
            var marked = Marked.parse(line);
            var plain = marked.text();
            if (TutorialText.words(plain) > 25) faults.add(e.getKey() + ": over 25 words");
            if (TutorialText.sentences(plain) > e.getValue()) faults.add(e.getKey() + ": over " + e.getValue() + " sentence(s)");
            var lower = plain.toLowerCase(Locale.ROOT);
            var known = introduced.getOrDefault(e.getKey(), Set.of());
            for (var word : PROFESSIONAL)
                for (int at = lower.indexOf(word); at >= 0; at = lower.indexOf(word, at + 1))
                    if (!known.contains(word) && marked.termAt(at) == null) faults.add(e.getKey() + " names " + word);
        }
        assertEquals(List.of(), faults);
    }

    /** P2b: the professional words a text may use as glossary terms, by key: a term's entry its own; a film's captions those marked by its step or before. */
    private static final Map<String, Set<String>> introduced = introduced();

    private static Map<String, Set<String>> introduced() {
        var guide = GuideFile.load();
        var out = new LinkedHashMap<String, Set<String>>();
        java.util.function.Function<String, Set<String>> words = term -> {
            var word = LANG.getOrDefault(Glossary.wordKey(term), "").toLowerCase(Locale.ROOT);
            var set = new java.util.HashSet<String>();
            for (var p : PROFESSIONAL) if (word.contains(p)) set.add(p);
            return set;
        };
        for (var term : guide.terms().keySet()) {
            out.put(Glossary.wordKey(term), words.apply(term));
            out.put(Glossary.definitionKey(term), words.apply(term));
        }
        var known = new java.util.HashSet<String>();
        for (var chapter : guide.chapters())
            for (var step : chapter.steps()) {
                var text = LANG.get(chapter.key(step));
                if (text != null) for (var span : Marked.parse(text).spans()) known.addAll(words.apply(span.term()));
                if (step.media() instanceof com.wormzjl.icheme.science.tutorial.guide.Guide.Film film && Films.build(film.name()) != null)
                    for (var key : Films.build(film.name()).captionKeys()) out.computeIfAbsent(key, k -> new java.util.HashSet<>()).addAll(known);
            }
        return out;
    }

    /**
     * P3: the equipment items' and the JEI pages' lines ({@code tutorial.icheme.equipment.*}, {@code .equipment_info.*},
     * {@code .item.*}): one sentence of at most 14 words each, and no professional word unless the line marks it.
     */
    @Test
    void theEquipmentAndItemLinesAreShortAndPlain() {
        var faults = new ArrayList<String>();
        int read = 0;
        for (var e : LANG.entrySet()) {
            var key = e.getKey();
            if (!key.startsWith("tutorial.icheme.equipment.") && !key.startsWith("tutorial.icheme.equipment_info.") && !key.startsWith("tutorial.icheme.item.")) continue;
            read++;
            var marked = Marked.parse(e.getValue());
            var plain = marked.text();
            if (TutorialText.words(plain) > 14) faults.add(key + ": over 14 words");
            if (TutorialText.sentences(plain) != 1) faults.add(key + ": not one sentence");
            var lower = plain.toLowerCase(Locale.ROOT);
            for (var word : PROFESSIONAL)
                for (int at = lower.indexOf(word); at >= 0; at = lower.indexOf(word, at + 1))
                    if (marked.termAt(at) == null) faults.add(key + " names " + word);
        }
        assertTrue(read >= 3 * 38, "the 38 kinds' lines are read: " + read);
        assertEquals(List.of(), faults);
    }

    /** P5: an equipment page's key words and every film's part and unit labels are short names: at most four words, no sentence. */
    @Test
    void thePageKeysAndFilmLabelsAreShortNames() {
        int read = 0;
        for (var e : LANG.entrySet()) {
            var key = e.getKey();
            boolean page = key.startsWith("tutorial.icheme.page.") && !key.endsWith(".hint");
            boolean label = key.startsWith("tutorial.icheme.film.part.") || key.startsWith("tutorial.icheme.film.crude_unit.") && !key.matches(".*\\.s\\d");
            if (!page && !label) continue;
            read++;
            assertTrue(TutorialText.words(e.getValue()) <= 4, key + ": at most four words");
            assertFalse(e.getValue().endsWith("."), key + ": a name, not a sentence");
        }
        assertTrue(read >= 30, "the page keys and the labels are read: " + read);
    }

    @Test
    void theCountsReadAsAPersonWould() {
        assertEquals(2, TutorialText.sentences("Every screen has a ? key at its top. Press it."));
        assertEquals(8, TutorialText.words("Every screen has a ? key at its top."));
        assertEquals(1, TutorialText.sentences("As the heat rises, the light part boils off first."));
    }

    @Test
    void everyMarkedTermIsDefinedOnTheStepOfItsFirstUse() {
        var guide = GuideFile.load();
        var first = Glossary.firstUses(guide, LANG::get);
        assertEquals(guide.terms().keySet(), first.keySet(), "every term is marked in a step, and every mark names a term");
        for (var e : guide.terms().entrySet()) {
            assertEquals(e.getValue(), first.get(e.getKey()), e.getKey() + ": its definition's step is its first use");
            assertNotNull(LANG.get(Glossary.wordKey(e.getKey())), e.getKey());
            assertNotNull(LANG.get(Glossary.definitionKey(e.getKey())), e.getKey());
        }
        for (var key : texts().keySet()) {
            var line = LANG.get(key);
            if (line == null) continue;
            for (var span : Marked.parse(line).spans()) assertTrue(guide.terms().containsKey(span.term()), key + " marks " + span.term());
        }
    }

    /**
     * P4b (chapter 2's rule, gated): no tutorial line names a temperature, pressure or flow in a fixed unit; a step writes a
     * temperature as {@code {<kelvin> K}}, which the guide shows in the player's unit. The units step names the units it offers.
     */
    @Test
    void noLineNamesAValueInAFixedUnit() {
        var fixed = java.util.regex.Pattern.compile("\\d\\s*(°C|°F|K\\b|bar\\b|kPa|kg/)");
        var faults = new ArrayList<String>();
        for (var e : LANG.entrySet()) {
            if (!e.getKey().startsWith("tutorial.icheme.") || e.getKey().equals("tutorial.icheme.guide.welcome.units")) continue;
            String line = com.wormzjl.icheme.science.tutorial.guide.Guide.KELVIN.matcher(e.getValue()).replaceAll("");
            if (fixed.matcher(line).find()) faults.add(e.getKey() + ": " + e.getValue());
        }
        assertEquals(List.of(), faults);
        assertEquals("Set it to 370 °C.", com.wormzjl.icheme.science.tutorial.guide.Guide.temperatures("Set it to {643.15 K}.", com.wormzjl.icheme.science.tutorial.cards.Card.CELSIUS));
    }
}
