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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every guide, tour, card (with its options) and film text (batch 2026-11-12-tutorial-system, P1, P2a; plan section 4): its
 * English line, at most two sentences and 25 words (a film caption and a tour stop one sentence), no professional word
 * unmarked, and every marked term in the glossary, defined on the step of its first use in guide order.
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
            var plain = Marked.parse(line).text();
            if (TutorialText.words(plain) > 25) faults.add(e.getKey() + ": over 25 words");
            if (TutorialText.sentences(plain) > e.getValue()) faults.add(e.getKey() + ": over " + e.getValue() + " sentence(s)");
            var lower = plain.toLowerCase(Locale.ROOT);
            for (var word : PROFESSIONAL) if (lower.contains(word)) faults.add(e.getKey() + " names " + word);
        }
        assertEquals(List.of(), faults);
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
}
