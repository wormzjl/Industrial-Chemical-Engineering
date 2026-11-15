package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.wormzjl.icheme.science.pollution.Reading.Word;
import org.junit.jupiter.api.Test;

/** The pollution meter's reading (WP8, plan section 11): the words, the worst level and the displayed number. */
class ReadingTest {
    private static final Channel[] NOT_COMBUSTIBLE = {Channel.TOXIC, Channel.CORROSIVE, Channel.PARTICULATE};

    @Test
    void wordsAtAndAroundEachThreshold() {
        for (var channel : NOT_COMBUSTIBLE) {
            assertEquals(Word.SAFE, Reading.word(channel, 0), channel + " at 0");
            assertEquals(Word.SAFE, Reading.word(channel, 0.0999), channel + " under 0.1");
            assertEquals(Word.WARNING, Reading.word(channel, 0.1), channel + " at 0.1");
            assertEquals(Word.WARNING, Reading.word(channel, 0.2999), channel + " under 0.3");
            assertEquals(Word.DANGER, Reading.word(channel, 0.3), channel + " at 0.3");
            assertEquals(Word.DANGER, Reading.word(channel, 0.9999), channel + " under 1");
            assertEquals(Word.LETHAL, Reading.word(channel, 1.0), channel + " at 1");
            assertEquals(Word.LETHAL, Reading.word(channel, 2.9999), channel + " under 3");
            assertEquals(Word.LETHAL, Reading.word(channel, 3.0), channel + " at 3");
            assertEquals(Word.LETHAL, Reading.word(channel, 1e6), channel + " far above");
        }
    }

    @Test
    void combustibleIsFlammableFromTheReference() {
        assertEquals(Word.SAFE, Reading.word(Channel.COMBUSTIBLE, 0.05));
        assertEquals(Word.WARNING, Reading.word(Channel.COMBUSTIBLE, 0.1));
        assertEquals(Word.DANGER, Reading.word(Channel.COMBUSTIBLE, 0.9999));
        assertEquals(Word.FLAMMABLE, Reading.word(Channel.COMBUSTIBLE, 1.0));
        assertEquals(Word.FLAMMABLE, Reading.word(Channel.COMBUSTIBLE, 3.0));
        assertEquals(Word.FLAMMABLE, Reading.word(Channel.COMBUSTIBLE, 50));
    }

    @Test
    void nanAndNegativeAreSafe() {
        for (var channel : Channel.values()) {
            assertEquals(Word.SAFE, Reading.word(channel, Double.NaN), channel + " NaN");
            assertEquals(Word.SAFE, Reading.word(channel, -1), channel + " negative");
        }
    }

    @Test
    void worstIsTheHighestLevelOfTheFour() {
        assertEquals(Exposure.Level.NONE, Reading.worst(0, 0, 0, 0));
        assertEquals(Exposure.Level.LOW, Reading.worst(0.15, 0, 0, 0));
        assertEquals(Exposure.Level.MEDIUM, Reading.worst(0.15, 0.4, 0, 0));
        assertEquals(Exposure.Level.HIGH, Reading.worst(0, 0, 1.2, 0.05));
        assertEquals(Exposure.Level.EXTREME, Reading.worst(0, 0, 0, 3.5));
        // Nothing is added together: four values just under 0.1 stay NONE.
        assertEquals(Exposure.Level.NONE, Reading.worst(0.09, 0.09, 0.09, 0.09));
        assertEquals(Exposure.Level.LOW, Reading.worst(Double.NaN, -2, 0.1, Double.NaN));
    }

    @Test
    void particulateDoesNotChangeTheToxicWord() {
        assertEquals(Word.SAFE, Reading.word(Channel.TOXIC, 0.05));
        assertEquals(Word.LETHAL, Reading.word(Channel.PARTICULATE, 2.0));
        // The toxic word reads the toxic index alone, whatever the particulate index is.
        assertEquals(Word.SAFE, Reading.word(Channel.TOXIC, 0.05));
        assertEquals(Exposure.Level.HIGH, Reading.worst(0.05, 0, 0, 2.0));
    }

    @Test
    void numberFormatting() {
        assertEquals("0.00", Reading.number(0));
        assertEquals("0.03", Reading.number(0.03));
        assertEquals("1.20", Reading.number(1.2));
        assertEquals("9.99", Reading.number(9.99));
        assertEquals("10.0", Reading.number(9.996));
        assertEquals("10.0", Reading.number(10));
        assertEquals("12.3", Reading.number(12.34));
        assertEquals("150.0", Reading.number(150));
        assertEquals("0.00", Reading.number(Double.NaN));
        assertEquals("0.00", Reading.number(-0.5));
        assertEquals("999.9", Reading.number(Double.POSITIVE_INFINITY));
    }
}
