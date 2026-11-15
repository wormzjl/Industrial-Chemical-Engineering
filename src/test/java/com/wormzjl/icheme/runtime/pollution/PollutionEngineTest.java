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
}
