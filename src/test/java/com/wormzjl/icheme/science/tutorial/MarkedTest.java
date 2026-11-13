package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.science.tutorial.guide.Marked;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A text's marked terms: {id:shown words}. */
class MarkedTest {
    @Test
    void aMarkBecomesItsWordsAndASpan() {
        var m = Marked.parse("A {still:still} boils a {mix:liquid mix}.");
        assertEquals("A still boils a liquid mix.", m.text());
        assertEquals(List.of(new Marked.Span(2, 7, "still"), new Marked.Span(16, 26, "mix")), m.spans());
        assertEquals("still", m.termAt(2));
        assertEquals("mix", m.termAt(25));
        assertNull(m.termAt(7));
    }

    @Test
    void anOpenOrOddBraceStaysAsWritten() {
        for (var text : List.of("No marks here.", "A {brace", "An {empty:} one", "A {:word} one", "A {Bad Id:word} one", "{x}"))
            assertEquals(text, Marked.parse(text).text(), text);
        assertTrue(Marked.parse("A {brace").spans().isEmpty());
    }
}
