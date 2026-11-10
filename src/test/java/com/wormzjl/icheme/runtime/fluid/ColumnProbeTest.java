package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.operation.*;
import com.wormzjl.icheme.science.column.v3.*;

import org.junit.jupiter.api.Test;

import java.util.List;

class ColumnProbeTest {
    @Test
    void internalMassReadingsUseTheCyclesTotalFeedBasisIncludingWater() {
        var world = new EventPathWorld();
        var axis = List.of("hc", "Water");
        var ops =
                new ColumnOperation(
                        world.ledger,
                        world.registry,
                        world.coordinator,
                        axis,
                        new double[] {.1, .018});
        var input =
                new V3ColumnInput(
                        1,
                        "test:probe",
                        "test:probe",
                        new V3ComponentBasis(List.of("hc")),
                        new double[] {90},
                        450,
                        0,
                        1,
                        300000,
                        0,
                        List.of(
                                new V3ColumnSpecification.CondenserOutletTemperature(330),
                                new V3ColumnSpecification.OrganicRefluxRatio(1),
                                new V3ColumnSpecification.ReboilerDuty(1000)),
                        List.of());
        var condenser =
                new V3ColumnInspection.Node(330, 300000, 10, 0, 0, 0, List.of(1.), List.of(0.));
        var sump = new V3ColumnInspection.Node(450, 300000, 50, 20, 3, 0, List.of(1.), List.of(1.));
        var accepted =
                new V3ColumnInspection(
                        input,
                        List.of(condenser, sump),
                        new V3AcceptanceAudit(
                                List.of(
                                        V3AcceptanceAudit.Check.pass(
                                                "fixture",
                                                0,
                                                0,
                                                "bounded accepted reading fixture"))));
        var settings =
                new Setpoints(200, 100, 330, 1, 10, new double[0], new double[0], new double[0]);
        var ref =
                new SplitTable.Reference(
                        new double[] {.9, .1},
                        0,
                        30,
                        new double[0],
                        .5,
                        settings,
                        450,
                        new double[0]);
        var table =
                new SplitTable(
                        axis,
                        List.of(
                                new SplitTable.Product(
                                        Port.BOTTOMS, 0, new double[] {1, 1}, 450, 300000)),
                        10,
                        new double[0],
                        -10,
                        ref,
                        new boolean[] {true, true});
        var readings = ops.probes(accepted, table);
        assertNotNull(readings);
        assertEquals(1. / 100, readings.refluxPerFeed(), 1e-12);
        assertEquals(
                (2 + 3 * .018) / 100,
                readings.vapourPerFeed(),
                1e-12,
                "water vapor contributes mass; feed water remains in the cycle denominator");
        assertEquals(330, readings.refluxTemperature());
        assertEquals(450, readings.bottomTemperature());
    }

    /**
     * T6 (batch 2026-11-10-column-failure-modes, W3.3): the live pressure drop equals the solved one at the table's own
     * throughput, and at half that feed its dry part falls by the square while the liquid head stands; a table without
     * hydraulics has no reading.
     */
    @Test
    void theLiveDropIsTheSolvedOneAtTheTablesFeedAndItsDryPartGoesWithTheSquare() {
        var axis = List.of("hc", "Water");
        var settings = new Setpoints(200, 100, 330, 1, 10, new double[0], new double[0], new double[0]);
        // The table solved at half the design rate: 100 mol/s.
        var ref = new SplitTable.Reference(new double[] {.9, .1}, 0, 30, new double[0], .5, settings, 450, new double[0]);
        var bare = new SplitTable(axis, List.of(new SplitTable.Product(Port.BOTTOMS, 0, new double[] {1, 1}, 450, 300000)), 10, new double[0], -10, ref,
                new boolean[] {true, true});
        var table = bare.withFlood(new SplitTable.Flood(.006, 30, false, 6_400, 15_600));
        assertEquals(22_000, ColumnOperation.drop(table, 100), 1e-9, "the solved drop at the table's feed");
        assertEquals(1_600 + 15_600, ColumnOperation.drop(table, 50), 1e-9, "half the feed: a quarter of the dry drop, the liquid head as solved");
        assertEquals(0, ColumnOperation.drop(table, 0), "no vapour, no drop");
        assertTrue(Double.isNaN(ColumnOperation.drop(bare, 100)), "no hydraulics, no reading");
    }
}
