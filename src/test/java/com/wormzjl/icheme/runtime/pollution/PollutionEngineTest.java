package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.MapEnvironment;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionLedger;
import com.wormzjl.icheme.science.pollution.PollutionSettings;
import com.wormzjl.icheme.science.pollution.Release;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pollution engine between passes (batch 2026-11-15-pollution-system, WP3): releases wait for the next pass, a failed
 * or refused pass loses nothing, only failures count toward the disable, and the environment is asked for each cube and
 * its six faces.
 */
class PollutionEngineTest {
    private static final Environment OPEN = MapEnvironment.uniform(new Environment.Cell(1, true, true), false, -4, 19);
    private static final PollutionSettings SETTINGS = PollutionSettings.DEFAULT;
    private static final long A = CubeKey.of(0, 4, 0), B = CubeKey.of(3, 4, -2);

    private static double pendingSum(PollutionEngine engine) {
        return engine.pending().stream().mapToDouble(Release::amount).sum();
    }

    @Test
    void aReleaseDuringAPassLandsInTheNextPass() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.TOXIC, 100.0);
        var first = engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        assertTrue(engine.inFlight());
        assertTrue(engine.begin(OPEN, 20, SETTINGS).isEmpty(), "one pass at a time");
        engine.release(B, Channel.COMBUSTIBLE, 50.0);
        assertEquals(List.of(new Release(A, Channel.TOXIC, 100.0)), first.releases());
        engine.complete(first.run());
        assertFalse(engine.inFlight());
        assertEquals(100.0, engine.ledger().get(Channel.TOXIC, PollutionLedger.Total.RELEASED));
        var second = engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        assertEquals(List.of(new Release(B, Channel.COMBUSTIBLE, 50.0)), second.releases());
        engine.complete(second.run());
        assertEquals(50.0, engine.ledger().get(Channel.COMBUSTIBLE, PollutionLedger.Total.RELEASED));
    }

    @Test
    void aFailedPassGivesItsReleasesBackAndKeepsTheField() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.TOXIC, 1000.0);
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        var field = engine.field();
        var ledger = engine.ledger();
        engine.release(A, Channel.TOXIC, 1.0);
        engine.release(B, Channel.PARTICULATE, 2.0);
        engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        engine.release(B, Channel.PARTICULATE, 4.0);
        engine.failed("worker threw");
        assertFalse(engine.inFlight());
        assertEquals(1, engine.failures());
        assertEquals("worker threw", engine.lastFailure());
        assertEquals(7.0, pendingSum(engine), "nothing lost");
        assertEquals(List.of(new Release(A, Channel.TOXIC, 1.0), new Release(B, Channel.PARTICULATE, 6.0)), engine.pending());
        assertEquals(field, engine.field());
        assertEquals(ledger, engine.ledger());
    }

    @Test
    void aRefusedPassDoesNotCountAsAFailure() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.CORROSIVE, 3.0);
        engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        engine.refused();
        assertEquals(0, engine.failures());
        assertFalse(engine.inFlight());
        assertEquals(List.of(new Release(A, Channel.CORROSIVE, 3.0)), engine.pending());
    }

    @Test
    void threeFailuresInARowCountThreeAndAnInstalledPassResetsThem() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.TOXIC, 10.0);
        for (int i = 0; i < 3; i++) {
            engine.begin(OPEN, 20, SETTINGS).orElseThrow();
            engine.failed("failure " + i);
        }
        assertEquals(3, engine.failures());
        assertEquals(10.0, pendingSum(engine));
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        assertEquals(0, engine.failures());
    }

    @Test
    void nothingStoredAndNothingPendingBeginsNothing() {
        var engine = new PollutionEngine();
        assertTrue(engine.idle());
        assertTrue(engine.begin(OPEN, 20, SETTINGS).isEmpty());
        engine.release(A, Channel.TOXIC, Double.NaN);
        engine.release(A, Channel.TOXIC, -1.0);
        engine.release(A, Channel.TOXIC, Double.POSITIVE_INFINITY);
        assertEquals(3, engine.ignored());
        assertTrue(engine.begin(OPEN, 20, SETTINGS).isEmpty());
    }

    @Test
    void wantedHoldsEachCubeAndItsSixFaces() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.TOXIC, 1.0);
        var wanted = engine.wanted();
        assertEquals(7, wanted.size());
        for (long key : Set.of(A, CubeKey.of(-1, 4, 0), CubeKey.of(1, 4, 0), CubeKey.of(0, 3, 0), CubeKey.of(0, 5, 0),
                CubeKey.of(0, 4, -1), CubeKey.of(0, 4, 1))) {
            assertTrue(wanted.contains(key), "cube " + CubeKey.x(key) + " " + CubeKey.y(key) + " " + CubeKey.z(key));
        }
        assertEquals(Set.of(A), engine.core());
    }

    @Test
    void thePendingMapMergesEqualCubeAndChannel() {
        var engine = new PollutionEngine();
        engine.release(B, Channel.TOXIC, 1.0);
        engine.release(A, Channel.TOXIC, 2.0);
        engine.release(B, Channel.TOXIC, 3.0);
        engine.release(A, Channel.CORROSIVE, 5.0);
        var expected = A < B
                ? List.of(new Release(A, Channel.TOXIC, 2.0), new Release(A, Channel.CORROSIVE, 5.0), new Release(B, Channel.TOXIC, 4.0))
                : List.of(new Release(B, Channel.TOXIC, 4.0), new Release(A, Channel.TOXIC, 2.0), new Release(A, Channel.CORROSIVE, 5.0));
        assertEquals(expected, engine.pending());
    }

    @Test
    void aSaveDuringAPassWritesThePassReleasesAsPending() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.TOXIC, 8.0);
        engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        engine.release(A, Channel.TOXIC, 1.0);
        assertEquals(List.of(new Release(A, Channel.TOXIC, 9.0)), engine.pendingForSave());
        assertEquals(PollutionField.EMPTY, engine.field());
    }

    @Test
    void aClearKeepsTheLedgerClosedAndDropsThePassInFlight() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.TOXIC, 1000.0);
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        engine.release(A, Channel.TOXIC, 500.0);
        var pass = engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        engine.clear();
        engine.complete(pass.run());
        assertEquals(0, engine.field().size());
        assertTrue(engine.pending().isEmpty());
        assertFalse(engine.inFlight());
        assertEquals(0.0, engine.ledger().closure(Channel.TOXIC, engine.field()), 1e-9);
        assertEquals(1000.0, engine.ledger().get(Channel.TOXIC, PollutionLedger.Total.RELEASED));
    }

    // WP6a: a blast burns the installed field; a pass in flight keeps the burn.

    private static void assertClosed(PollutionEngine engine) {
        for (var channel : Channel.values()) {
            double released = engine.ledger().get(channel, PollutionLedger.Total.RELEASED);
            assertEquals(0.0, engine.ledger().closure(channel, engine.field()), 1e-9 * Math.max(1, released),
                    channel + ": released = stored + decayed + dissipated + burnt + ground");
        }
    }

    @Test
    void aBurnDuringAPassInFlightSurvivesThePass() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.COMBUSTIBLE, 400_000.0);
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        double before = engine.field().amount(A, Channel.COMBUSTIBLE);
        assertTrue(before > 10_000, "the cube holds combustible after one pass: " + before);
        engine.release(B, Channel.TOXIC, 10.0);
        var pass = engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        double burnt = engine.burn(A, 5_000.0, Blasts.SOOT_SHARE);
        assertEquals(5_000.0, burnt);
        assertEquals(before - 5_000.0, engine.field().amount(A, Channel.COMBUSTIBLE), 1e-9,
                "the installed field shows the burn at once, so the cube cannot ignite twice");
        // The pass was begun from the field before the burn; its result is the same whenever it runs (pure).
        var result = pass.run();
        var expected = PollutionField.burn(result.air().field(), result.air().ledger(), A, burnt, Blasts.SOOT_SHARE);
        engine.complete(result);
        assertArrayEquals(expected.field().keys(), engine.field().keys());
        assertArrayEquals(expected.field().amounts(), engine.field().amounts());
        assertEquals(expected.ledger(), engine.ledger());
        assertEquals(burnt, engine.ledger().get(Channel.COMBUSTIBLE, PollutionLedger.Total.BURNT), 1e-9, "burnt once");
        assertEquals(burnt * Blasts.SOOT_SHARE, engine.ledger().get(Channel.PARTICULATE, PollutionLedger.Total.RELEASED), 1e-9,
                "its soot");
        assertTrue(engine.field().amount(A, Channel.COMBUSTIBLE) < result.air().field().amount(A, Channel.COMBUSTIBLE), "the pass did not undo the burn");
        assertClosed(engine);
        // The next pass carries nothing of it again.
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        assertEquals(burnt, engine.ledger().get(Channel.COMBUSTIBLE, PollutionLedger.Total.BURNT), 1e-9);
        assertClosed(engine);
    }

    @Test
    void aBurnIsCappedByWhatTheCubeHolds() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.COMBUSTIBLE, 50_000.0);
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        double held = engine.field().amount(A, Channel.COMBUSTIBLE);
        assertEquals(held, engine.burn(A, 1e9, Blasts.SOOT_SHARE), 1e-9);
        assertEquals(0.0, engine.field().amount(A, Channel.COMBUSTIBLE));
        assertEquals(0.0, engine.burn(A, 10.0, Blasts.SOOT_SHARE), "nothing left to burn");
        assertEquals(0.0, engine.burn(CubeKey.of(9, 9, 9), 10.0, Blasts.SOOT_SHARE), "an absent cube burns nothing");
        assertEquals(0.0, engine.burn(A, Double.NaN, Blasts.SOOT_SHARE));
        assertClosed(engine);
    }

    @Test
    void aBurnDuringAFailedPassStaysOnce() {
        var engine = new PollutionEngine();
        engine.release(A, Channel.COMBUSTIBLE, 400_000.0);
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        engine.release(A, Channel.COMBUSTIBLE, 1.0);
        engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        double burnt = engine.burn(A, 2_000.0, Blasts.SOOT_SHARE);
        engine.failed("worker threw");
        var field = engine.field();
        assertEquals(burnt, engine.ledger().get(Channel.COMBUSTIBLE, PollutionLedger.Total.BURNT), 1e-9);
        assertClosed(engine);
        // The next pass begins from the burnt field and replays no burn.
        var pass = engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        assertArrayEquals(field.amounts(), pass.field().amounts());
        engine.complete(pass.run());
        assertEquals(burnt, engine.ledger().get(Channel.COMBUSTIBLE, PollutionLedger.Total.BURNT), 1e-9);
        assertClosed(engine);
    }

    private static double[] amounts(Channel channel, double amount) {
        var a = new double[Channel.values().length];
        a[channel.ordinal()] = amount;
        return a;
    }

    /**
     * What the spill law leaves on the ground (batches 2026-11-18-water-intake-outfall and -ground-pollution): the air
     * ledger's GROUND at once, never the air field, kept across a pass in flight; the next pass lands it in the ground
     * field, whose ledger closes per channel and per compound.
     */
    @Test
    void whatGoesToTheGroundIsBookedAndNeverEntersTheField() {
        var engine = new PollutionEngine();
        long column = com.wormzjl.icheme.science.pollution.MapColumns.column(0, 0);
        engine.deposit(column, amounts(Channel.TOXIC, 40_000), List.of(new com.wormzjl.icheme.science.pollution.GroundField.Compound("crude_pc08", Channel.TOXIC, 0.044)));
        assertEquals(40_000.0, engine.ledger().get(Channel.TOXIC, PollutionLedger.Total.RELEASED));
        assertEquals(40_000.0, engine.ledger().get(Channel.TOXIC, PollutionLedger.Total.GROUND));
        assertEquals(0, engine.field().size(), "nothing in the air");
        engine.release(A, Channel.TOXIC, 100);
        var pass = engine.begin(OPEN, 20, SETTINGS).orElseThrow();
        engine.deposit(column, amounts(Channel.PARTICULATE, 7_000), List.of());
        engine.deposit(column, amounts(Channel.TOXIC, Double.NaN), List.of());
        engine.complete(pass.run());
        var ledger = engine.ledger();
        assertEquals(40_100.0, ledger.get(Channel.TOXIC, PollutionLedger.Total.RELEASED), 1e-9);
        assertEquals(7_000.0, ledger.get(Channel.PARTICULATE, PollutionLedger.Total.GROUND), 0, "booked during the pass, kept on its result");
        assertEquals(7_000.0, ledger.get(Channel.PARTICULATE, PollutionLedger.Total.RELEASED), 0);
        for (var channel : Channel.values()) assertEquals(0, ledger.closure(channel, engine.field()), 1e-9, "the ledger closes: " + channel);
        // The pass landed the first deposit; the one made during it waits for the next.
        assertEquals(0.044 * Math.pow(2, -20 / 14400.0), engine.ground().moles(column, "crude_pc08"), 1e-12, "landed, then a pass of decay");
        assertEquals(1, engine.pendingDeposits().size(), "the particulate made during the pass waits");
        engine.complete(engine.begin(OPEN, 20, SETTINGS).orElseThrow().run());
        assertTrue(engine.ground().amount(column, Channel.PARTICULATE) > 0, "and lands at the next");
        var ground = engine.groundLedger();
        for (var channel : Channel.values()) assertEquals(0, ground.closure(channel, engine.ground()), 1e-9, "the ground ledger closes: " + channel);
        assertEquals(0, ground.closure("crude_pc08", engine.ground()), 1e-15);
        assertEquals(40_000.0, ground.get(Channel.TOXIC, com.wormzjl.icheme.science.pollution.GroundLedger.Total.DEPOSITED), 0, "deposited is what the air ledger booked ground");
    }
}
