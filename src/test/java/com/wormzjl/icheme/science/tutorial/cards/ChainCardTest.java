package com.wormzjl.icheme.science.tutorial.cards;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The card chain (batch 2026-11-12-tutorial-system, P2a): its table agrees with the mod's property records for C1 to
 * C6 within 1 K, boils higher with every carbon, and names the state at 20 °C by the brief's rule.
 */
class ChainCardTest {
    private static final String[] RECORDS = {"tjl20_methane", "tjl19_ethane", "tjl19_propane", "tjl19_n_butane", "tjl19_n_pentane", "chemsep_n_hexane"};

    @Test
    void theTableAgreesWithTheModsRecordsForC1ToC6() throws Exception {
        for (int i = 0; i < RECORDS.length; i++) {
            var path = Path.of("src/main/resources/data/icheme/materials/properties/" + RECORDS[i] + ".json");
            var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            double record = json.get("normal_boiling_point_kelvin").getAsDouble();
            assertEquals(i + 1, Chain.CARBONS[i]);
            assertEquals(record, Chain.BOILS[i] + 273.15, 1, RECORDS[i]);
        }
    }

    @Test
    void longerChainsBoilHigherAndTheStateFollowsTheRule() {
        assertEquals(13, Chain.CARBONS.length);
        assertEquals(Chain.CARBONS.length, Chain.BOILS.length);
        assertEquals(Chain.CARBONS.length, Chain.MELTS.length);
        for (int i = 1; i < Chain.CARBONS.length; i++) {
            assertTrue(Chain.CARBONS[i] > Chain.CARBONS[i - 1]);
            assertTrue(Chain.BOILS[i] > Chain.BOILS[i - 1], "C" + Chain.CARBONS[i]);
        }
        for (int i = 0; i < Chain.CARBONS.length; i++) {
            int n = Chain.CARBONS[i];
            var expected = n <= 4 ? Chain.Phase.GAS : n <= 16 ? Chain.Phase.LIQUID : Chain.Phase.WAX;
            assertEquals(expected, Chain.phase(i), "C" + n);
        }
    }

    @Test
    void theSliderStepsThroughTheThirteenChains() {
        var card = new Chain();
        assertEquals(13, card.steps());
        assertEquals(0, Chain.position(card.stepped(0)));
        assertEquals(12, Chain.position(card.stepped(1)));
        assertEquals(9, Chain.position(card.stepped(9 / 12.0 + .01)));
        assertFalse(card.done(card.stepped(.9), java.util.Set.of()), "not at 40 yet");
        assertTrue(card.done(card.stepped(1), java.util.Set.of()));
        assertEquals("natural_gas", Chain.NAMES[0]);
        assertNull(Chain.NAMES[1], "ethane has no everyday name in the brief");
        assertEquals("jet_diesel", Chain.NAMES[9]);
        assertEquals("wax_bitumen", Chain.NAMES[12]);
        assertEquals("At 20 °C it is", Chain.fill("At %s it is", "20 °C"));
    }
}
