package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.GroundField.Compound;
import com.wormzjl.icheme.science.pollution.GroundField.Deposit;
import com.wormzjl.icheme.science.pollution.GroundLedger.Total;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The ground field (batch 2026-11-18-ground-pollution, plan G1; one amount per column, owner 2026-11-18): closure of the
 * amount and per compound, the plan's two expected numbers, determinism, unloaded columns, the cap and the draw law (G5).
 */
class GroundFieldTest {
    private static final GroundSettings S = GroundSettings.DEFAULT;
    private static final double REF = GroundField.GROUND_REFERENCE;

    private static long col(int x, int z) {
        return MapColumns.column(x, z);
    }

    private static Deposit toxic(long column, double amount, String compound, double moles) {
        return new Deposit(column, amount, List.of(new Compound(compound, moles)));
    }

    private static void assertCloses(GroundField field, GroundLedger ledger, String... compounds) {
        double scale0 = Math.max(1, ledger.get(Total.DEPOSITED));
        assertEquals(0, ledger.closure(field), 1e-12 * scale0, "the amount closes: " + ledger);
        for (var name : compounds) {
            double scale = Math.max(1, ledger.get(name, Total.DEPOSITED));
            assertEquals(0, ledger.closure(name, field), 1e-12 * scale, name + " closes");
        }
    }

    @Test void aSteadySourceWithoutSpreadStandsAtRateTimesHalfLifeOverLn2() {
        // Plan G1: 1000 eq mol/s in one column, no rain, no spread: A = 1000 × 14 400 / ln 2 = 2.08e7, index 1.24.
        var settings = S.withoutSpread();
        var field = GroundField.EMPTY;
        var ledger = GroundLedger.EMPTY;
        double dt = 100;
        for (int i = 0; i < 2000; i++) {
            var r = GroundField.step(field, ledger, List.of(toxic(col(0, 0), 1000 * dt, "HydrogenSulfide", 10 * dt)), GroundEnvironment.open(false), dt, settings);
            field = r.field();
            ledger = r.ledger();
        }
        double expected = 1000 * 14400 / Math.log(2);
        double a = field.amount(col(0, 0));
        // Sampled just after a 100 s decay, the step-wise steady state D f / (1 - f) sits 0.24 % below the continuous one.
        assertEquals(expected, a, 5e-3 * expected, "steady amount");
        assertEquals(1.24, a / REF, .01, "index");
        assertEquals(1, field.size(), "no spread: one column");
        assertCloses(field, ledger, "HydrogenSulfide");
        System.out.printf(Locale.ROOT, "GROUND steady source: %.4e eq mol, index %.4f (expected %.4e)%n", a, a / REF, expected);
    }

    @Test void withSpreadTheNextColumnStandsNearAQuarterOfTheSource() {
        // Plan G1: decay length sqrt((5e-4 / 20) × 14 400 / ln 2) = 0.72 chunks; the plan's 1-D estimate exp(-1/0.72) = 0.25.
        var field = GroundField.EMPTY;
        var ledger = GroundLedger.EMPTY;
        double dt = 100;
        for (int i = 0; i < 2000; i++) {
            var r = GroundField.step(field, ledger, List.of(toxic(col(0, 0), 1000 * dt, "HydrogenSulfide", 10 * dt)), GroundEnvironment.open(false), dt, S);
            field = r.field();
            ledger = r.ledger();
        }
        double source = field.amount(col(0, 0));
        double next = field.amount(col(1, 0));
        double diagonal = field.amount(col(1, 1));
        double ratio = next / source;
        System.out.printf(Locale.ROOT, "GROUND spread: source index %.4f, next %.4f (ratio %.4f), diagonal %.4f, columns %d%n", source / REF, next / REF, ratio, diagonal / REF, field.size());
        assertTrue(ratio > .15 && ratio < .35, "the next column near a quarter of the source: " + ratio);
        assertEquals(next, field.amount(col(-1, 0)), 1e-9 * next, "symmetric");
        assertEquals(next, field.amount(col(0, 1)), 1e-9 * next, "symmetric");
        assertEquals(field.moles(col(1, 0), "HydrogenSulfide") / field.moles(col(0, 0), "HydrogenSulfide"), ratio, 1e-6, "the compound moves with its column's amount");
        assertCloses(field, ledger, "HydrogenSulfide");
    }

    @Test void rainWaterColumnsAndADrawEachCloseExactly() {
        var field = GroundField.EMPTY;
        var ledger = GroundLedger.EMPTY;
        var water = Set.of(col(2, 0), col(3, 0));
        var random = new Random(7);
        for (int i = 0; i < 300; i++) {
            var deposits = new ArrayList<Deposit>();
            deposits.add(new Deposit(col(random.nextInt(4), random.nextInt(3)), random.nextDouble() * 2e6, List.of(new Compound("crude_pc08", 3), new Compound("Ammonia", 2))));
            deposits.add(Deposit.of(col(1, 1), 5e5));
            var r = GroundField.step(field, ledger, deposits, GroundEnvironment.of(null, water, i % 3 == 0), 20, S);
            field = r.field();
            ledger = r.ledger();
            if (i % 50 == 49) {
                var d = GroundField.draw(field, ledger, col(1, 0), 1e6, 5.68e7, name -> !name.equals("Ammonia"));
                field = d.field();
                ledger = d.ledger();
            }
        }
        assertTrue(ledger.get("crude_pc08", Total.DRAWN) > 0, "the draw took the cut");
        assertEquals(0, ledger.get("Ammonia", Total.DRAWN), "a compound the package does not hold stays in the ground");
        assertTrue(ledger.get(Total.DRAWN) > 0, "the amount goes with the drawn compounds");
        assertCloses(field, ledger, "crude_pc08", "Ammonia");
    }

    @Test void shuffledDepositsGiveTheSameField() {
        var deposits = new ArrayList<Deposit>();
        var random = new Random(11);
        for (int i = 0; i < 40; i++) {
            deposits.add(toxic(col(random.nextInt(5), random.nextInt(5)), random.nextDouble() * 1e7, i % 2 == 0 ? "A" : "B", random.nextDouble()));
        }
        var shuffled = new ArrayList<>(deposits);
        Collections.shuffle(shuffled, new Random(3));
        var one = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, deposits, GroundEnvironment.open(true), 20, S);
        var two = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, shuffled, GroundEnvironment.open(true), 20, S);
        assertArrayEquals(one.field().columns(), two.field().columns());
        assertArrayEquals(one.field().amounts(), two.field().amounts(), 0);
        assertEquals(one.field().entries(), two.field().entries());
        assertEquals(one.ledger(), two.ledger());
    }

    @Test void anUnloadedColumnKeepsItsAmountsAndPassesNothing() {
        var start = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, List.of(toxic(col(0, 0), 2 * REF, "A", 1), toxic(col(5, 5), 2 * REF, "A", 1)), GroundEnvironment.open(false), 20, S.withoutSpread());
        var loaded = Set.of(col(5, 5), col(4, 5), col(6, 5), col(5, 4), col(5, 6), col(1, 0), col(-1, 0), col(0, 1), col(0, -1));
        var before = start.field().amount(col(0, 0));
        var r = GroundField.step(start.field(), start.ledger(), List.of(), GroundEnvironment.of(loaded, Set.of(), false), 20, S);
        assertEquals(before, r.field().amount(col(0, 0)), 0, "unloaded: no decay, no spread");
        assertEquals(0, r.field().amount(col(1, 0)), "an unloaded column passes nothing to a loaded neighbour");
        assertTrue(r.field().amount(col(4, 5)) > 0, "a loaded column spreads");
        assertTrue(r.field().amount(col(5, 5)) < before, "and decays");
        assertCloses(r.field(), r.ledger(), "A");
    }

    @Test void aboveTheCapTheLowestPeakLeavesFirst() {
        var settings = new GroundSettings(REF, 20, 0, 4, 4, 14400, 4, 1e-4, 2);
        var deposits = List.of(toxic(col(0, 0), 3 * REF, "A", 1), toxic(col(1, 0), REF, "B", 1), toxic(col(2, 0), 2 * REF, "C", 1));
        var r = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, deposits, GroundEnvironment.open(false), 20, settings);
        assertEquals(2, r.field().size());
        assertEquals(0, r.field().amount(col(1, 0)), "the lowest peak left");
        assertTrue(r.ledger().get(Total.CAPPED) > .99 * REF);
        assertTrue(r.ledger().get("B", Total.CAPPED) > .99);
        assertCloses(r.field(), r.ledger(), "A", "B", "C");
    }

    @Test void aColumnBelowTheCutoffLeavesWithItsCompounds() {
        var r = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, List.of(toxic(col(0, 0), 1e-5 * REF, "A", 4)), GroundEnvironment.open(false), 20, S);
        assertEquals(0, r.field().size());
        assertEquals(4, r.ledger().get("A", Total.DECAYED), 1e-12);
        assertCloses(r.field(), r.ledger(), "A");
    }

    @Test void theDrawTakesWatersShareOfEveryHeldCompound() {
        // Plan G5: W = 256 m² × 4 m × 55 500 mol/m³ = 5.68e7 mol; drawing n_w takes n_w / W of each compound.
        double w = 256 * 4 * 55500;
        var start = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, List.of(toxic(col(0, 0), 2 * REF, "crude_pc08", 1000)), GroundEnvironment.open(false), 1e-9, S);
        double moles = start.field().moles(col(0, 0), "crude_pc08");
        double amount = start.field().amount(col(0, 0));
        var d = GroundField.draw(start.field(), start.ledger(), col(0, 0), 55.5 * 20, w, name -> true);
        double share = 55.5 * 20 / w;
        assertEquals(1, d.compounds().size());
        assertEquals(moles * share, d.compounds().getFirst().moles(), 1e-15 * moles);
        assertEquals(amount * share, d.amount(), 1e-12 * amount);
        assertEquals(moles - moles * share, d.field().moles(col(0, 0), "crude_pc08"), 1e-12 * moles);
        assertCloses(d.field(), d.ledger(), "crude_pc08");
        var none = GroundField.draw(start.field(), start.ledger(), col(9, 9), 1000, w, name -> true);
        assertSame(start.field(), none.field(), "nothing there: nothing drawn");
    }

    @Test void savedArraysRoundTripAndBadOnesAreRefused() {
        var r = GroundField.step(GroundField.EMPTY, GroundLedger.EMPTY, List.of(toxic(col(0, 0), 2 * REF, "A", 3), toxic(col(-3, 7), 4 * REF, "B", 1)), GroundEnvironment.open(false), 20, S);
        var f = r.field();
        var back = GroundField.of(f.columns(), f.amounts(), f.entries());
        assertArrayEquals(f.amounts(), back.amounts(), 0);
        assertEquals(f.entries(), back.entries());
        assertThrows(IllegalArgumentException.class, () -> GroundField.of(new long[] {2, 1}, new double[2], List.of()));
        assertThrows(IllegalArgumentException.class, () -> GroundField.of(new long[] {1}, new double[] {-1}, List.of()));
        assertThrows(IllegalArgumentException.class, () -> GroundField.of(new long[] {1}, new double[4], List.of()), "one amount per column");
        assertThrows(IllegalArgumentException.class, () -> GroundField.of(new long[] {1}, new double[1], List.of(new GroundField.Entry(2, "A", 1))));
        var ledger = GroundLedger.of(r.ledger().totals(), r.ledger().compounds());
        assertEquals(r.ledger(), ledger);
    }

    @Test void theWaterSharesAreMolesOverTheReferenceWaterLargestFirst() {
        assertEquals(5.6832e7, GroundField.REFERENCE_WATER, 1e-6, "256 m2 x 4 m x 55 500 mol/m3");
        long column = MapColumns.column(2, 7);
        var field = GroundField.of(new long[] {column}, new double[] {15}, List.of(
                new GroundField.Entry(column, "b", 568.32),
                new GroundField.Entry(column, "a", 5683.2),
                new GroundField.Entry(column, "c", 568.32)));
        var shares = field.waterShares(column);
        assertEquals(List.of("a", "b", "c"), shares.stream().map(GroundField.WaterShare::name).toList(), "largest first, then by name");
        assertEquals(1e-4, shares.get(0).fraction(), 1e-16);
        assertEquals(1e-5, shares.get(1).fraction(), 1e-17);
        assertTrue(field.waterShares(MapColumns.column(0, 0)).isEmpty(), "a clean column names nothing");
        assertEquals(5683.2, field.moles(column, "a"), 0.0, "display only: nothing is drawn");
    }
}
