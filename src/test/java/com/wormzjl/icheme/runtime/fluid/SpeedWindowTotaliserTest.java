package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The transport window's totaliser (LEVEL_LOOP_REVIEW.md option A2): each pipe's gross volume over the accepted intervals
 * since its origin. Contiguous intervals add; an interval after a gap starts it again, so a level loop never takes a
 * difference across volume it did not see.
 */
class SpeedWindowTotaliserTest {
    private static PipeTransfer transfer(long pipe, double forward, double reverse) {
        return new PipeTransfer(pipe, new PipeTransfer.Stream(forward * 1000, new double[3][1], new double[] {forward, 0, 0}),
                new PipeTransfer.Stream(reverse * 1000, new double[3][1], new double[] {reverse, 0, 0}));
    }

    @Test
    void contiguousIntervalsAddAndAGapStartsAgain() {
        var window = new PipePresentation.SpeedWindow();
        assertEquals(-1, window.origin());
        assertEquals(-1, window.through());
        window.accepted(100, 160, List.of(transfer(7, 1.5, .25)));
        window.accepted(160, 227, List.of(transfer(7, 2, 0), transfer(8, 1, 0)));
        assertEquals(100, window.origin());
        assertEquals(227, window.through());
        assertEquals(3.75, window.total(7), 1e-15);
        assertEquals(1, window.total(8), 1e-15);
        assertEquals(0, window.total(9));
        window.accepted(300, 350, List.of(transfer(7, .5, 0)));
        assertEquals(300, window.origin());
        assertEquals(350, window.through());
        assertEquals(.5, window.total(7), 1e-15);
        assertEquals(0, window.total(8));
    }
}
