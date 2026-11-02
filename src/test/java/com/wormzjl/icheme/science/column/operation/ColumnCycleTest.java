package com.wormzjl.icheme.science.column.operation;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The column cycle's conservation and short-input rules (batch 2026-10-28-column-operation, C4 and C6): every component
 * and the energy balance close exactly over a long run of drifting feed, an account is never drawn below empty, rule B
 * takes a short steam as it is, rule A cuts every input together, a full buffer cuts the throughput, and a closure that
 * would heat the overhead trips the column.
 */
class ColumnCycleTest {
    /** A light, a middle and a heavy component and water; products: off-gas, sour water, distillate, a draw, bottoms. */
    static final List<String> AXIS = List.of("Light", "Middle", "Heavy", SplitTable.WATER);

    static SplitTable table() {
        var products = List.of(
                new SplitTable.Product(Port.OFF_GAS, 0, new double[] {.6, .01, 0, .05}, 332, 250_000),
                new SplitTable.Product(Port.SOUR_WATER, 0, new double[] {0, 0, 0, .94}, 332, 260_000),
                new SplitTable.Product(Port.DISTILLATE, 0, new double[] {.39, .3, .001, .01}, 332, 400_000),
                new SplitTable.Product(Port.DRAW, 0, new double[] {.01, .6, .099, 0}, 480, 270_000),
                new SplitTable.Product(Port.BOTTOMS, 0, new double[] {0, .09, .9, 0}, 600, 280_000));
        var reference = new SplitTable.Reference(new double[] {.3, .4, .3, 0}, -1.4e5, 300, new double[] {.4}, 1, setpoints(), 330, new double[] {400});
        var provisional = new SplitTable(AXIS, products, 0, new double[] {-20_000}, -1, reference, new boolean[] {true, true, true, true});
        // The solved condenser duty: the one that closes the reference state's balance, as the solve's own does.
        double[][] split = provisional.split(new double[] {150, 200, 150, 200});
        double out = 0;
        for (int k = 0; k < split.length; k++) out += energy(provisional, k, split[k]);
        double condenser = (out - (-1.4e5 * 500 - 2.3e5 * 200) - (-20_000 * 500.0)) / 500;
        assertTrue(condenser < 0, "the test's condenser removes heat: " + condenser);
        return new SplitTable(AXIS, products, 0, new double[] {-20_000}, condenser, reference, new boolean[] {true, true, true, true});
    }

    static Setpoints setpoints() {
        return new Setpoints(100, 100, 332, 4, 0, new double[] {.4}, new double[] {.1}, new double[] {-20_000});
    }

    /** A product's energy: a linear model of its temperature, the same in every cycle. */
    static double energy(SplitTable table, int k, double[] moles) {
        double t = table.products().get(k).temperature();
        double e = 0;
        double[] cp = {60, 150, 400, 75};
        double[] h0 = {-1e5, -2e5, -4e5, -2.4e5};
        for (int i = 0; i < moles.length; i++) e += moles[i] * (h0[i] + cp[i] * (t - 298));
        return e;
    }

    @Test
    void everyComponentAndTheEnergyCloseOverAThousandCyclesOfDriftingFeed() {
        var table = table();
        var random = new Random(20261029);
        var feed = Account.empty(4);
        var steam = Account.empty(4);
        double[] in = new double[4], out = new double[4];
        double energyIn = 0, energyOut = 0, duties = 0;
        for (int cycle = 0; cycle < 1000; cycle++) {
            // What the inlet islands delivered since the last cycle: a feed of drifting composition and enthalpy, and steam.
            double[] arrived = new double[4];
            for (int i = 0; i < 3; i++) arrived[i] = 500 * (.35 + .05 * random.nextDouble());
            arrived[3] = random.nextDouble() < .1 ? 5 : 0;
            double arrivedEnergy = 0;
            for (double n : arrived) arrivedEnergy += n * (-1.4e5 + 1e3 * random.nextGaussian());
            feed = feed.plus(new Account(arrived, arrivedEnergy));
            double s = 150 + 100 * random.nextDouble();
            steam = steam.plus(new Account(new double[] {0, 0, 0, s}, -2.3e5 * s));
            double[] room = new double[table.products().size()];
            java.util.Arrays.fill(room, cycle % 97 == 0 ? 50 : 1e9);
            var step = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), room, (k, n) -> energy(table, k, n), cycle % 3 == 0);
            assertNull(step.trip(), "cycle " + cycle);
            for (int i = 0; i < 4; i++) in[i] += step.feed().moles()[i] + step.steam().getFirst().moles()[i];
            energyIn += step.feed().energy() + step.steam().getFirst().energy();
            for (var p : step.products()) {
                for (int i = 0; i < 4; i++) out[i] += p.moles()[i];
                energyOut += p.energy();
            }
            duties += step.reboiler() + step.condenser() + java.util.Arrays.stream(step.pumparounds()).sum();
            for (int k = 0; k < room.length; k++) assertTrue(step.products().get(k).total() <= room[k] * (1 + 1e-12), "a full buffer is not overfilled");
            feed = feed.less(step.feed());
            steam = steam.less(step.steam().getFirst());
            for (double n : feed.moles()) assertTrue(n >= 0);
        }
        for (int i = 0; i < 4; i++) assertEquals(in[i], out[i], Math.max(in[i], 1) * 1e-12, AXIS.get(i) + " is conserved");
        assertEquals(energyIn + duties, energyOut, Math.abs(energyOut) * 1e-12, "the energy closes on the condenser");
    }

    @Test
    void ruleBTakesAShortSteamAsItIsAndRuleACutsEveryInputTogether() {
        var table = table();
        var feed = new Account(new double[] {300, 400, 300, 0}, -1.4e8);
        var steam = new Account(new double[] {0, 0, 0, 100}, -2.3e7); // 100 of the 200 the full rate wants
        double[] room = {1e9, 1e9, 1e9, 1e9, 1e9};
        var b = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), room, (k, n) -> energy(table, k, n), false);
        assertEquals(500, b.feedMoles(), 1e-9, "rule B keeps the crude rate");
        assertEquals(100, b.steam().getFirst().total(), 1e-9, "and strips with what steam there is");
        assertEquals(ColumnCycle.Limit.STEAM, b.limit());
        var a = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), room, (k, n) -> energy(table, k, n), true);
        assertEquals(.5, a.alpha(), 1e-12, "rule A runs everything at the steam's share");
        assertEquals(250, a.feedMoles(), 1e-9);
        assertEquals(100, a.steam().getFirst().total(), 1e-9);
    }

    @Test
    void aFullBufferCutsTheFeedAndAnEmptyFeedTakesNothing() {
        var table = table();
        var feed = new Account(new double[] {300, 400, 300, 0}, -1.4e8);
        var steam = new Account(new double[] {0, 0, 0, 1000}, -2.3e8);
        double[] room = {1e9, 1e9, 1e9, 1e9, 10};
        var step = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), room, (k, n) -> energy(table, k, n), false);
        assertEquals(ColumnCycle.Limit.OUTLET, step.limit());
        assertEquals(10, step.products().get(4).total(), 1e-9, "the bottoms buffer is filled to its room");
        var none = ColumnCycle.step(table, setpoints(), 5, Account.empty(4), List.of(steam), room, (k, n) -> energy(table, k, n), false);
        assertEquals(0, none.feedMoles());
        assertEquals(0, none.steam().getFirst().total(), "no feed, no steam: steam is held per mole of feed");
    }

    @Test
    void aClosureThatWouldHeatTheOverheadTripsTheColumnAndTakesNothing() {
        var table = table();
        // A feed far colder than the table's: the products would carry more energy than the feed brings and the duties add.
        var feed = new Account(new double[] {300, 400, 300, 0}, -1e10);
        var step = ColumnCycle.step(table, setpoints(), 5, feed, List.of(Account.empty(4)), new double[] {1e9, 1e9, 1e9, 1e9, 1e9},
                (k, n) -> energy(table, k, n), false);
        assertNotNull(step.trip());
        assertEquals(0, step.feedMoles(), "a tripped cycle takes nothing");
    }

    @Test
    void theDriftBandsAskForASolveOnlyOutsideThem() {
        var table = table();
        var ref = table.reference();
        double[] room = {1e9, 1e9, 1e9, 1e9, 1e9};
        var steam = new Account(new double[] {0, 0, 0, 1000}, -2.3e8);
        // At the reference: inside every band.
        var inside = ColumnCycle.step(table, setpoints(), 5, new Account(new double[] {150, 200, 150, 0}, ref.feedEnthalpy() * 500), List.of(steam), room,
                (k, n) -> energy(table, k, n), false);
        var crossing = DriftBands.check(table, inside, 500);
        assertTrue(crossing.isEmpty() || crossing.get().trigger() == DriftBands.Trigger.CLOSURE, String.valueOf(crossing));
        // 2 K of feed enthalpy at 300 J/mol K.
        var warm = ColumnCycle.step(table, setpoints(), 5, new Account(new double[] {150, 200, 150, 0}, (ref.feedEnthalpy() + 600) * 500), List.of(steam), room,
                (k, n) -> energy(table, k, n), false);
        assertEquals(DriftBands.Trigger.ENTHALPY, DriftBands.check(table, warm, 500).orElseThrow().trigger());
        var lighter = ColumnCycle.step(table, setpoints(), 5, new Account(new double[] {160, 200, 140, 0}, ref.feedEnthalpy() * 500), List.of(steam), room,
                (k, n) -> energy(table, k, n), false);
        assertEquals(DriftBands.Trigger.COMPOSITION, DriftBands.check(table, lighter, 500).orElseThrow().trigger());
    }

    @Test
    void theSplitAddsUpToTheTakenMolesExactly() {
        var table = table();
        var random = new Random(7);
        var rows = new ArrayList<double[]>();
        for (int n = 0; n < 200; n++) {
            double[] taken = new double[4];
            for (int i = 0; i < 4; i++) taken[i] = Math.exp(20 * random.nextDouble() - 10);
            double[][] split = table.split(taken);
            for (int i = 0; i < 4; i++) {
                double sum = 0;
                for (double[] p : split) sum += p[i];
                assertEquals(taken[i], sum, Math.ulp(taken[i]) * 8);
            }
            rows.add(taken);
        }
        assertEquals(200, rows.size());
    }
}
