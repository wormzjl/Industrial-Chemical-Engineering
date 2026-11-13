package com.wormzjl.icheme.science.plant.port;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.plant.column.ColumnCycle.Account;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The port arithmetic the running column and the batch still share (codebase review 2026-11-13, C6; wave-c/C4C_REPORT.md),
 * on hand-computed cases: the lists, the mass, an account as of the fence, the debit with its overdraw report and its
 * energy share, and the dated logs' window. {@code idle} needs a port node's fluid state and is covered by ColumnRunTest and
 * StillRuntimeTest.
 */
class PortAccountsTest {
    static final List<String> WORLD = List.of("A", "B", "C");

    @Test
    void anIslandOnTheWorldListTakesTheSameArray() {
        double[] moles = {1, 2, 3};
        assertSame(moles, PortAccounts.onIsland(List.of("A", "B", "C"), WORLD, moles));
        assertSame(moles, PortAccounts.onWorld(7, List.of("A", "B", "C"), WORLD, moles));
    }

    @Test
    void anIslandListTakesEachCompoundByIdAndSkipsTheOnesWithoutMoles() {
        // B holds nothing: the island need not carry it.
        assertArrayEquals(new double[] {3, 1}, PortAccounts.onIsland(List.of("C", "A"), WORLD, new double[] {1, 0, 3}));
        // B holds 2 mol and the island does not carry it: the port waits for its island to grow.
        assertNull(PortAccounts.onIsland(List.of("C", "A"), WORLD, new double[] {1, 2, 3}));
    }

    @Test
    void theWorldListTakesAnIslandsCompoundsAndRefusesOneItLacks() {
        assertArrayEquals(new double[] {1, 0, 3}, PortAccounts.onWorld(7, List.of("C", "A"), WORLD, new double[] {3, 1}));
        assertArrayEquals(new double[] {0, 0, 3}, PortAccounts.onWorld(7, List.of("C", "D"), WORLD, new double[] {3, 0}));
        var e = assertThrows(IllegalStateException.class, () -> PortAccounts.onWorld(7, List.of("C", "D"), WORLD, new double[] {3, 1}));
        assertEquals("Island 7 carries D, which the world list does not", e.getMessage());
    }

    @Test
    void theMassSumsMolesTimesMolarMass() {
        // 2 mol at 2 g/mol and 3 mol at 18 g/mol: 0.004 + 0.054 kg.
        assertEquals(0.058, PortAccounts.mass(new double[] {0.002, 0.018}, new double[] {2, 3}), 1e-15);
        assertEquals(0, PortAccounts.mass(new double[] {0.002}, new double[] {0}));
    }

    @Test
    void anAccountAsOfTheFenceIsWhatItHoldsLessLaterEntriesNeverBelowEmpty() {
        double[] held = {1, 2, 3};
        PortAccounts.less(held, new double[] {0.5, 3, 0});
        assertArrayEquals(new double[] {0.5, 0, 3}, held);
    }

    @Test
    void aDebitTakesWhatWasGivenAndTheEnergyByTheSameShare() {
        var overdrawn = new ArrayList<double[]>();
        // 1.5 of 3 mol given: half the moles, so half of 30 J.
        var d = PortAccounts.debit(new Account(new double[] {2, 1}, 30), new double[] {1, 0.5}, (i, h, g) -> overdrawn.add(new double[] {i, h, g}));
        assertArrayEquals(new double[] {1, 0.5}, d.account().moles());
        assertEquals(15, d.account().energy());
        assertEquals(1.5, d.moles());
        assertEquals(15, d.energy());
        assertTrue(overdrawn.isEmpty());
    }

    @Test
    void anOverdrawIsReportedAndTheAccountStopsAtEmpty() {
        var overdrawn = new ArrayList<double[]>();
        // 2.5 mol of A against 2 held: reported, A empties; B's negative entry gives nothing. 2.5 of 3 mol: 25 of 30 J.
        var d = PortAccounts.debit(new Account(new double[] {2, 1}, 30), new double[] {2.5, -1}, (i, h, g) -> overdrawn.add(new double[] {i, h, g}));
        assertEquals(1, overdrawn.size());
        assertArrayEquals(new double[] {0, 2, 2.5}, overdrawn.get(0));
        assertArrayEquals(new double[] {0, 1}, d.account().moles());
        assertEquals(2.5, d.moles());
        assertEquals(25, d.energy(), 1e-12);
        assertEquals(5, d.account().energy(), 1e-12);
    }

    @Test
    void aGiftWithinTheToleranceIsNoOverdrawAndTheEnergyShareStopsAtAll() {
        var overdrawn = new ArrayList<double[]>();
        // 1 + 1e-10 mol against 1 held: inside held*(1+1e-9)+1e-12, not reported; the share is capped at 1.
        var d = PortAccounts.debit(new Account(new double[] {1}, 10), new double[] {1 + 1e-10}, (i, h, g) -> overdrawn.add(new double[] {i, h, g}));
        assertTrue(overdrawn.isEmpty());
        assertArrayEquals(new double[] {0}, d.account().moles());
        assertEquals(10, d.energy());
        assertEquals(0, d.account().energy());
        // An empty account gives no energy.
        var empty = PortAccounts.debit(Account.empty(2), new double[] {0, 0}, (i, h, g) -> fail("nothing is overdrawn"));
        assertEquals(0, empty.energy());
        assertEquals(0, empty.moles());
    }

    record Dated(long tick) {}

    @Test
    void aDatedLogDropsEntriesBeforeItsWindowAndKeepsItsNewest() {
        var log = new ArrayDeque<>(List.of(new Dated(10), new Dated(20), new Dated(30)));
        PortAccounts.keep(log, Dated::tick, 15);
        assertEquals(List.of(new Dated(20), new Dated(30)), List.copyOf(log));
        PortAccounts.keep(log, Dated::tick, 100);
        assertEquals(List.of(new Dated(30)), List.copyOf(log));
        // An entry at the window's start stays.
        var edge = new ArrayDeque<>(List.of(new Dated(10), new Dated(20)));
        PortAccounts.keep(edge, Dated::tick, 10);
        assertEquals(2, edge.size());
    }

    @Test
    void theCycleAndTheFenceAreTheEnginesFiveSecondsAndTwentyTwoSeconds() {
        assertEquals(100, PortAccounts.CYCLE_TICKS);
        assertEquals(440, PortAccounts.FENCE_TICKS);
    }
}
