package com.wormzjl.icheme.client.gui.tutorial.cards;

import com.wormzjl.icheme.science.tutorial.films.StillRunData;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The card pot_temperature (batch 2026-11-12-tutorial-system, P2b): the recorded run's pot temperature against the share
 * distilled, its parts in boiling order along the run, its two cuts, and done at the slider's end.
 */
class PotTemperatureCardTest {
    @Test
    void theCurveIsTheRecordingsAndTheCutsItsLines() {
        var run = StillRunData.load();
        var card = (PotTemperature) Cards.build(PotTemperature.NAME);
        assertNotNull(card);
        double end = run.series("distilled").value(run.series("distilled").size() - 1);
        assertEquals(end, card.share(1), 1e-12, "the slider reaches the run's end");
        assertEquals(run.line("end", "pot_kelvin"), card.kelvin(card.share(1)), 1e-6, "the end's pot temperature");
        assertEquals(run.series("distilled").at(run.line("naphtha", "time")), card.cuts()[0], 1e-12, "the first cut: the end of the naphtha");
        assertEquals(run.series("distilled").at(run.line("kerosene", "time")), card.cuts()[1], 1e-12, "the second: the fire out at the end of the kerosene");
        assertTrue(card.cuts()[0] > 0 && card.cuts()[0] < card.cuts()[1] && card.cuts()[1] < end, "the curve runs on past the fire out to the run's end");
        assertEquals(run.line("kerosene", "pot_kelvin"), card.kelvin(card.cuts()[1]), 1, "the fire out's temperature, the recording's");
        assertTrue(card.kelvin(card.share(1)) <= PotTemperature.CEILING, "the scale holds the run's end");
        // The first cut's temperature is the one the guide's cut step holds the player to, within a degree.
        assertEquals(run.line("naphtha", "pot_kelvin"), card.kelvin(card.cuts()[0]), 1);
    }

    @Test
    void aHotterPotMeansAHeavierPart() {
        var card = new PotTemperature();
        int lastPart = -1;
        double lastKelvin = 0;
        for (int i = 0; i <= 100; i++) {
            double share = card.share(i / 100.0);
            double k = card.kelvin(share);
            int part = card.part(share);
            assertTrue(k >= lastKelvin - 1e-9, "the pot never cools along the run: " + share);
            if (part >= 0) {
                assertTrue(part >= lastPart, "the parts come over in boiling order: " + share);
                lastPart = part;
            }
            lastKelvin = k;
        }
        assertTrue(card.part(card.cuts()[0] * .9) <= 1, "before the first cut: gas or naphtha");
        assertTrue(card.part(card.cuts()[1]) >= 2, "after it a heavier part");
        assertTrue(card.partsShown().size() >= 2);
    }

    @Test
    void itIsASliderDoneAtTheEndWithItsWords() throws Exception {
        var card = new PotTemperature();
        assertEquals(Card.Control.SLIDER, card.control());
        assertFalse(card.done(.99, Set.of()));
        assertTrue(card.done(1, Set.of()));
        var english = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/icheme/lang/en_us.json")))
                .getAsJsonObject();
        for (var key : card.textKeys()) assertTrue(english.has(key), key);
        assertTrue(english.has(card.captionKey()) && english.has(card.controlKey()));
        assertTrue(english.get(PotTemperature.KEY + ".reading").getAsString().split("%s", -1).length == 3, "the reading's two values");
    }
}
