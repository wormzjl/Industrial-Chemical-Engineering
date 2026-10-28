package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A running column's solve on V3 (batch 2026-10-28-column-operation, C5, plan sections 5, 6 and 11): the network's default
 * column design solves at its start, the table it gives closes a cycle at its reference exactly on the network's basis,
 * and a feed 2 K warmer solves warm from the seed.
 */
class ColumnSolveTest {
    private static final double SECONDS = 5;

    @Test void aStartSolvesTheDesignAndItsTableClosesACycleOnTheNetworksBasis() {
        var catalog = MaterialCatalog.bundled();
        V3ColumnInput design = catalog.presets().column("tia_juana_nitrogen").input(catalog);
        var model = FluidThermodynamics.forNetwork(catalog, design.packageId());
        var axis = model.components();
        double[] feed = new double[axis.size()];
        for (int c = 0; c < design.componentBasis().componentCount(); c++) {
            int i = axis.indexOf(design.componentBasis().componentId(c));
            if (i >= 0) feed[i] += design.feedComponentMolarFlowsMolPerSecond()[c];
        }
        var setpoints = Setpoints.of(ColumnOperatingPoint.of(design));
        var steam = design.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin(), Double.NaN)).toList();
        double screen = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(feed, Double.NaN, steam), null),
                model, catalog, screen, () -> {});
        var solved = assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString);
        var table = solved.table();
        assertEquals(axis, table.axis());
        assertTrue(table.product(Port.BOTTOMS, 0) >= 0 && table.product(Port.DISTILLATE, 0) >= 0, "products " + table.products());
        assertNotNull(solved.seed(), "a seed for the warm route");

        // A cycle at the reference: 5 s of the feed and steam taken, no outlet full.
        int water = axis.indexOf(SplitTable.WATER);
        double rate = Arrays.stream(feed).sum();
        var feedAccount = new Account(scale(feed, SECONDS), table.reference().feedEnthalpy() * rate * SECONDS);
        var steamAccounts = new ArrayList<Account>();
        for (int j = 0; j < steam.size(); j++) {
            double[] n = new double[axis.size()];
            n[water] = steam.get(j).rate();
            double p = design.topPressurePascal() + design.steamFeeds().get(j).stageNumber() * design.stagePressureDropPascal();
            steamAccounts.add(new Account(scale(n, SECONDS), model.flashTP(steam.get(j).temperature(), p, n.clone(), () -> {}).enthalpy() * SECONDS));
        }
        double[] room = new double[table.products().size()];
        Arrays.fill(room, Double.MAX_VALUE);
        double[] h = solved.enthalpy();
        var step = ColumnCycle.step(table, setpoints, SECONDS, feedAccount, steamAccounts, room, (k, n) -> Arrays.stream(n).sum() * h[k], false);
        assertNull(step.trip());
        assertEquals(1, step.alpha(), 1e-12);
        assertEquals(0, step.departure(), 1e-9, "the table's condenser duty is the network's closure at its reference");
        double v3 = solved.solvedCondenser(), network = table.condenserPerFeed() * rate;
        assertTrue(network < 0 && Math.abs(network - v3) < .1 * Math.abs(v3), "the network's closure " + network + " W beside V3's " + v3 + " W");
        assertTrue(DriftBands.check(table, step, setpoints.designFeedRate() * SECONDS).isEmpty(), "the reference is inside its own bands");

        // The feed 2 K warmer: a band crossed, solved warm from the seed.
        double warmer = table.reference().feedEnthalpy() + 2 * table.reference().enthalpySlope();
        var again = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.ENTHALPY, design, setpoints, new ColumnSolve.Taken(feed, warmer, steam), solved.seed()),
                model, catalog, screen, () -> {});
        var resolved = assertInstanceOf(ColumnSolve.Solved.class, again, again::toString);
        assertTrue(resolved.route().contains("warm"), "the warm route: " + resolved.route());
        assertEquals(warmer, resolved.table().reference().feedEnthalpy(), 0);
    }

    private static double[] scale(double[] v, double s) {
        double[] out = v.clone();
        for (int i = 0; i < out.length; i++) out[i] *= s;
        return out;
    }
}
