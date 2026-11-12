package com.wormzjl.icheme.science.tutorial.cards;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cards of chapters 3 and 4 (batch 2026-11-12-tutorial-system, P4b): reflux steps from the pot still through the
 * recording's column forms in ratio order, each purer and slower; compare sets the pot still against the shown column form;
 * balance holds the small unit's crude and products, which weigh the same. Each card's lines are in English.
 */
class TowerCardsTest {
    @Test
    void theRefluxCardStepsFromThePotToTheHighestRatioEachPurerAndSlower() {
        var card = (RefluxCut) Cards.build(RefluxCut.NAME);
        assertNotNull(card);
        assertEquals(Card.Control.SLIDER, card.control());
        assertEquals(StillTraysData.pot(), card.form(0), "the slider's start: the pot still");
        assertEquals(StillTraysData.columns().getLast(), card.form(1), "its end: the highest ratio");
        StillTraysData.Form last = null;
        for (int i = 0; i <= 20; i++) {
            var form = card.form(i / 20.0);
            if (last != null && form != last) {
                assertTrue(form.reflux() >= last.reflux(), "in ratio order");
                assertTrue(form.first().part(1) > last.first().part(1), form.name() + ": more naphtha in the first cut");
                assertTrue(form.first().playMinutes() > last.first().playMinutes(), form.name() + ": and slower");
            }
            last = form;
        }
        // The claim the chapter rests on (P4b part 1): at least 10 points more naphtha, within 45 minutes of play.
        var shown = StillTraysData.columns().getLast();
        assertTrue(shown.first().part(1) - StillTraysData.pot().first().part(1) >= .10, "10 points more naphtha");
        assertTrue(shown.first().playMinutes() <= 45, "the first cut within 45 minutes of play");
        assertFalse(card.done(.99, Set.of()));
        assertTrue(card.done(1, Set.of()));
    }

    @Test
    void theCompareAndBalanceCardsReadTheRecordings() {
        var compare = (CompareCut) Cards.build(CompareCut.NAME);
        assertNotNull(compare);
        assertEquals(Card.Control.NONE, compare.control());
        var balance = (Balance) Cards.build(Balance.NAME);
        assertNotNull(balance);
        assertEquals(Card.Control.NONE, balance.control());
        var run = SmallUnitData.bundled();
        double products = 0, shares = 0;
        for (var p : SmallUnitData.PRODUCTS) {
            products += run.productKgH().get(p);
            shares += run.share(p);
        }
        assertEquals(1, shares, 1e-5, "the shares of the crude in, products by mass, make the whole");
        assertEquals(run.crudeKgH(), products, .005 * run.crudeKgH(), "in equals out, within half a percent (the water and the steam aside)");
    }

    @Test
    void everyLineIsInEnglish() throws Exception {
        var english = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/icheme/lang/en_us.json"))).getAsJsonObject();
        for (var name : java.util.List.of(RefluxCut.NAME, CompareCut.NAME, Balance.NAME)) {
            var card = Cards.build(name);
            assertTrue(english.has(card.captionKey()), name + ": " + card.captionKey());
            if (card.controlKey() != null) assertTrue(english.has(card.controlKey()), name + ": " + card.controlKey());
            for (var key : card.textKeys()) assertTrue(english.has(key), name + ": " + key);
        }
    }
}
