package com.wormzjl.icheme.science.tutorial.cards;

import com.wormzjl.icheme.science.column.operation.ColumnStatus;
import com.wormzjl.icheme.science.column.operation.Condition;
import com.wormzjl.icheme.science.column.operation.OperationView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The card "limit" (batch 2026-11-12-tutorial-system, P5 stage B): its three cases are the column's own conditions (the
 * feed short, the steam short, a product full), each a LIMIT that makes the phase LIMITED, and each status line is the one
 * the Operation tab writes, its word the condition's.
 */
class LimitCardTest {
    @Test
    void eachCaseIsTheColumnsOwnConditionAndLine() {
        assertEquals(List.of(Condition.FEED_SHORT, Condition.STEAM_SHORT, Condition.PRODUCT_FULL), Limit.CASES.stream().map(Limit.Case::condition).toList());
        var card = new Limit();
        assertEquals(Limit.CASES.size(), card.options().size(), "a soft key a case");
        for (var k : Limit.CASES) {
            assertEquals(Condition.Rank.LIMIT, k.condition().rank, k.word());
            var active = List.of(new Condition.Active(k.condition(), k.alpha()));
            assertEquals(OperationView.Phase.LIMITED, ColumnStatus.phase(true, active), k.word());
            assertEquals(ColumnStatus.line(true, active, k.alpha(), Double.NaN, 0), k.line(), k.word());
            assertTrue(k.line().startsWith("LIMITED: " + k.condition().word + ", "), k.line());
            assertTrue(card.textKeys().contains(Limit.KEY + ".do." + k.word()), "what to do: " + k.word());
        }
        assertEquals("LIMITED: feed, 50 %", Limit.of(0).line());
        assertEquals("LIMITED: outlet, 0 %", Limit.of(2).line());
    }
}
