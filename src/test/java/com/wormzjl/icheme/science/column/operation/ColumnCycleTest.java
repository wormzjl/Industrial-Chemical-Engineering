package com.wormzjl.icheme.science.column.operation;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.equipment.HeatSides;
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
            assertFalse(step.tripped(), "cycle " + cycle);
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
        assertEquals(Condition.STEAM_SHORT, bound(b));
        assertEquals("", b.binding().text(), "rule B: no rule A on the line");
        var a = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), room, (k, n) -> energy(table, k, n), true);
        assertEquals(.5, a.alpha(), 1e-12, "rule A runs everything at the steam's share");
        assertEquals(Condition.STEAM_SHORT, bound(a));
        assertEquals(.5, a.binding().value(), 1e-12);
        assertEquals(Condition.RULE_A, a.binding().text(), "rule A on the line");
        assertEquals(250, a.feedMoles(), 1e-9);
        assertEquals(100, a.steam().getFirst().total(), 1e-9);
    }

    /** The condition that binds {@code step}, or null. */
    static Condition bound(ColumnCycle.Step step) {
        var b = step.binding();
        return b == null ? null : b.condition();
    }

    /**
     * The list holds every LIMIT that holds with the share of the set feed it allows, in the order the cycle weighs them;
     * the lowest binds, and alpha is its share (W1). A rule B steam shortage is listed and binds only where nothing else does.
     */
    @Test
    void theListHoldsEveryLimitAndTheLowestBinds() {
        var table = table();
        // Half the set feed; the bottoms with room for 0.8 of what that feed makes, so 0.4 of what the set feed makes (every
        // share is of the set demand, a finding of stage 1); the steam short of its ratio.
        var feed = new Account(new double[] {75, 100, 75, 0}, -.35e8);
        var steam = new Account(new double[] {0, 0, 0, 40}, -.92e7);
        double[] open = {1e9, 1e9, 1e9, 1e9, 1e9};
        double bottoms = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), open, (k, n) -> energy(table, k, n), false).products().get(4).total();
        var full = ColumnCycle.step(table, setpoints(), 5, new Account(new double[] {300, 400, 300, 0}, -1.4e8), List.of(new Account(new double[] {0, 0, 0, 1000}, -2.3e8)),
                open, (k, n) -> energy(table, k, n), false);
        double[] room = {1e9, 1e9, 1e9, 1e9, .8 * bottoms};
        var step = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), room, (k, n) -> energy(table, k, n), false);
        var kinds = step.conditions().stream().map(Condition.Active::condition).toList();
        assertEquals(List.of(Condition.FEED_SHORT, Condition.PRODUCT_FULL, Condition.STEAM_SHORT), kinds, step.conditions().toString());
        assertEquals(.5, step.conditions().get(0).value(), 1e-12, "the feed allows half");
        assertEquals(.4, step.conditions().get(1).value(), 1e-9, "the bottoms allows 0.4 of the set feed");
        assertEquals(4, step.conditions().get(1).index());
        assertEquals(1, step.conditions().get(2).value(), "rule B: the short steam cuts nothing");
        assertEquals(0, step.conditions().get(2).index());
        assertEquals(Condition.PRODUCT_FULL, bound(step), "the lowest binds");
        assertEquals(.4, step.alpha(), 1e-9);
        assertTrue(step.products().get(4).total() <= room[4] * (1 + 1e-12), "the bottoms is filled no further than its room");
        assertFalse(step.tripped());
        // A full take with nothing short lists nothing.
        assertEquals(List.of(), full.conditions());
        assertNull(full.binding());
    }

    @Test
    void aFullBufferCutsTheFeedAndAnEmptyFeedTakesNothing() {
        var table = table();
        var feed = new Account(new double[] {300, 400, 300, 0}, -1.4e8);
        var steam = new Account(new double[] {0, 0, 0, 1000}, -2.3e8);
        double[] room = {1e9, 1e9, 1e9, 1e9, 10};
        var step = ColumnCycle.step(table, setpoints(), 5, feed, List.of(steam), room, (k, n) -> energy(table, k, n), false);
        assertEquals(Condition.PRODUCT_FULL, bound(step));
        assertEquals(4, step.binding().index(), "the bottoms is the full product");
        assertEquals(10, step.products().get(4).total(), 1e-9, "the bottoms buffer is filled to its room");
        var none = ColumnCycle.step(table, setpoints(), 5, Account.empty(4), List.of(steam), room, (k, n) -> energy(table, k, n), false);
        assertEquals(0, none.feedMoles());
        assertEquals(0, none.steam().getFirst().total(), "no feed, no steam: steam is held per mole of feed");
    }

    /**
     * The condenser's room is its cooling water's (C6b): a water-cooled condenser's room bounds its whole duty, an air-then-water
     * condenser's only its trim's share (COOLER_MODEL_PLAN.md section 6), and an air cooler's water takes nothing to bound.
     */
    @Test
    void theCondensersRoomBoundsItsWatersShareOfTheDuty() {
        var whole = table();
        var trimmed = new SplitTable(whole.axis(), whole.products(), whole.reboilerPerFeed(), whole.pumparoundPerFeed(), whole.condenserPerFeed(), whole.reference(),
                whole.held(), .4 * whole.condenserPerFeed());
        var air = new SplitTable(whole.axis(), whole.products(), whole.reboilerPerFeed(), whole.pumparoundPerFeed(), whole.condenserPerFeed(), whole.reference(),
                whole.held(), 0);
        assertEquals(.4, trimmed.waterShare(), 1e-12);
        var feed = new Account(new double[] {300, 400, 300, 0}, -1.4e8);
        var steam = new Account(new double[] {0, 0, 0, 1000}, -2.3e8);
        double[] room = {1e9, 1e9, 1e9, 1e9, 1e9};
        // Room for a fifth of the cycle's whole duty.
        double condenserRoom = -.2 * whole.condenserPerFeed() * 500;
        var a = ColumnCycle.step(whole, setpoints(), 5, feed, List.of(steam), room, condenserRoom, (k, n) -> energy(whole, k, n), false);
        assertEquals(Condition.COOLING_SHORT, bound(a));
        assertEquals(.2, a.alpha(), 1e-12, "the whole duty in the water: a fifth of the feed");
        var b = ColumnCycle.step(trimmed, setpoints(), 5, feed, List.of(steam), room, condenserRoom, (k, n) -> energy(trimmed, k, n), false);
        assertEquals(Condition.COOLING_SHORT, bound(b));
        assertEquals(.5, b.alpha(), 1e-12, "the trim's 40 %: half the feed");
        var c = ColumnCycle.step(air, setpoints(), 5, feed, List.of(steam), room, condenserRoom, (k, n) -> energy(air, k, n), false);
        assertNull(bound(c));
        assertEquals(1, c.alpha(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> new SplitTable(whole.axis(), whole.products(), 0, whole.pumparoundPerFeed(), whole.condenserPerFeed(),
                whole.reference(), whole.held(), 2 * whole.condenserPerFeed()), "the water takes no more than the whole duty");
    }

    /**
     * W4, decision D1: each duty's utility room bounds that duty alone. The reboiler's heating room cuts the feed to its share
     * (HEATING_SHORT), a pumparound cooler's room to its share (PUMPAROUND_SHORT, with its index), the lowest share binds, and a
     * duty with no utility side (an unbounded room) takes the cycle exactly as before W4.
     */
    @Test
    void eachUtilitysRoomBoundsItsOwnDuty() {
        var whole = table();
        double reboiler = 30_000;
        // The same closure with a reboiler: the solved condenser removes the reboiler's heat too.
        var heated = new SplitTable(AXIS, whole.products(), reboiler, whole.pumparoundPerFeed(), whole.condenserPerFeed() - reboiler, whole.reference(), whole.held());
        var feed = new Account(new double[] {300, 400, 300, 0}, -1.4e8);
        var steam = new Account(new double[] {0, 0, 0, 1000}, -2.3e8);
        double[] room = {1e9, 1e9, 1e9, 1e9, 1e9};
        double demand = 100 * 5;
        ColumnCycle.ProductEnergy energy = (k, n) -> energy(heated, k, n);
        var a = ColumnCycle.step(heated, setpoints(), 5, feed, List.of(steam), room, ColumnCycle.Rooms.NONE.withReboiler(.25 * reboiler * demand), energy, false);
        assertEquals(Condition.HEATING_SHORT, bound(a));
        assertEquals(.25, a.alpha(), 1e-12, "a quarter of the reboiler's duty: a quarter of the feed");
        var b = ColumnCycle.step(heated, setpoints(), 5, feed, List.of(steam), room, ColumnCycle.Rooms.NONE.withPumparounds(new double[] {.4 * 20_000 * demand}), energy, false);
        assertEquals(Condition.PUMPAROUND_SHORT, bound(b));
        assertEquals(0, b.binding().index(), "the first pumparound");
        assertEquals(.4, b.alpha(), 1e-12);
        var both = ColumnCycle.Rooms.NONE.withReboiler(.6 * reboiler * demand).withPumparounds(new double[] {.4 * 20_000 * demand}).withCondenser(-.7 * heated.condenserPerFeed() * demand);
        var c = ColumnCycle.step(heated, setpoints(), 5, feed, List.of(steam), room, both, energy, false);
        assertEquals(Condition.PUMPAROUND_SHORT, bound(c), "the lowest share binds");
        assertEquals(.4, c.alpha(), 1e-12);
        assertEquals(3, c.conditions().stream().filter(x -> x.condition().rank == Condition.Rank.LIMIT).count(), "each short utility is listed: " + c.conditions());
        var free = ColumnCycle.step(heated, setpoints(), 5, feed, List.of(steam), room, ColumnCycle.Rooms.NONE, energy, false);
        var before = ColumnCycle.step(heated, setpoints(), 5, feed, List.of(steam), room, energy, false);
        assertEquals(1, free.alpha());
        assertEquals(before.conditions(), free.conditions());
        assertArrayEquals(before.feed().moles(), free.feed().moles(), 0);
        assertEquals(before.condenser(), free.condenser(), 0, "no utility side: the cycle as before");
        // A table with no reboiler duty and no pumparounds: their rooms bound nothing.
        var plain = new SplitTable(AXIS, whole.products(), 0, new double[0], whole.condenserPerFeed() - 20_000, whole.reference(), whole.held());
        var d = ColumnCycle.step(plain, new Setpoints(100, 100, 332, 4, 0, new double[] {.4}, new double[] {.1}, new double[0]), 5, feed, List.of(steam), room,
                ColumnCycle.Rooms.NONE.withReboiler(0).withPumparounds(new double[] {0}), (k, n) -> energy(plain, k, n), false);
        assertNull(bound(d), "no duty, no limit: " + d.conditions());
    }

    /**
     * An air condenser (U2, COOLER_MODEL_PLAN.md section 8): the table's surface per feed at each fan step gives the least fan
     * speed a built face needs, still fans where natural draught holds, and the most feed full fans hold; a duty past full fans
     * limits the cycle as a cooling water shortage does. A table with no air, or a face not known, limits nothing.
     */
    @Test
    void anAirCondensersFaceBoundsItsFeedAndSetsItsFans() {
        var whole = table();
        double[] area = {100, 93.75, 87.5, 81.25, 75, 68.75, 62.5, 56.25, 50};
        var air = new SplitTable(whole.axis(), whole.products(), whole.reboilerPerFeed(), whole.pumparoundPerFeed(), whole.condenserPerFeed(), whole.reference(),
                whole.held(), 0, SplitTable.WATER_NEVER, area);
        assertEquals(0, air.fans(60, .5), "natural draught holds 120 m² per mol/s");
        assertEquals(.4, air.fans(40, .5), 1e-12);
        assertEquals(.45, air.fans(38.75, .5), 1e-12, "between the steps, linear in the surface");
        assertEquals(1, air.fans(20, .5), "short at full fans");
        assertEquals(.5, air.airFeed(25), 1e-12, "25 m² hold 0.5 mol/s at full fans");
        assertTrue(Double.isNaN(whole.fans(40, .5)) && whole.airFeed(40) == Double.POSITIVE_INFINITY, "no air, no fans and no bound");
        assertTrue(Double.isNaN(air.fans(0, .5)) && air.airFeed(0) == Double.POSITIVE_INFINITY, "a face not known bounds nothing");
        var feed = new Account(new double[] {300, 400, 300, 0}, -1.4e8);
        var steam = new Account(new double[] {0, 0, 0, 1000}, -2.3e8);
        double[] room = {1e9, 1e9, 1e9, 1e9, 1e9};
        // 100 mol/s set; a face of 2500 m² holds 50 mol/s at full fans.
        var step = ColumnCycle.step(air, setpoints(), 5, feed, List.of(steam), room, Double.POSITIVE_INFINITY, air.airFeed(2500), (k, n) -> energy(air, k, n), false);
        assertEquals(Condition.COOLING_SHORT, bound(step));
        assertEquals(.5, step.alpha(), 1e-12, "half the feed");
        var free = ColumnCycle.step(air, setpoints(), 5, feed, List.of(steam), room, Double.POSITIVE_INFINITY, air.airFeed(6000), (k, n) -> energy(air, k, n), false);
        assertNull(bound(free));
        assertEquals(1, free.alpha(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> new SplitTable(whole.axis(), whole.products(), 0, whole.pumparoundPerFeed(), whole.condenserPerFeed(),
                whole.reference(), whole.held(), 0, SplitTable.WATER_NEVER, new double[] {1, 2}), "one area a fan step");
        assertEquals(HeatSides.fanPower(40, 1) * .064, HeatSides.fanPower(40, .4), 1e-9, "the power by the cube of the speed");
    }

    /**
     * Where an outlet's booking difference goes (C1b, the owner's "divide by temperature"): a water-cooled condenser's water
     * takes every one, an air cooler's none, an air-then-water condenser's trim those offered below the 40 °C split.
     */
    @Test
    void aBookingDifferenceGoesToTheWaterBelowTheSplit() {
        var whole = table();
        var trimmed = new SplitTable(whole.axis(), whole.products(), whole.reboilerPerFeed(), whole.pumparoundPerFeed(), whole.condenserPerFeed(), whole.reference(),
                whole.held(), .4 * whole.condenserPerFeed(), HeatSides.AIR_SPLIT_TEMPERATURE);
        var air = new SplitTable(whole.axis(), whole.products(), whole.reboilerPerFeed(), whole.pumparoundPerFeed(), whole.condenserPerFeed(), whole.reference(),
                whole.held(), 0);
        double split = HeatSides.AIR_SPLIT_TEMPERATURE;
        for (double t : new double[] {280, split - .01, split, 573.15, Double.NaN}) {
            assertTrue(whole.waterTakes(t), "a water-cooled condenser's water takes every difference: " + t);
            assertFalse(air.waterTakes(t), "an air cooler's takes none: " + t);
            assertEquals(t < split, trimmed.waterTakes(t), "the trim takes those below the split: " + t);
        }
        assertEquals(SplitTable.WATER_ALWAYS, whole.waterBelow());
        assertEquals(SplitTable.WATER_NEVER, air.waterBelow());
        assertEquals(HeatSides.AIR_SPLIT_TEMPERATURE, new SplitTable(whole.axis(), whole.products(), 0, whole.pumparoundPerFeed(), whole.condenserPerFeed(),
                whole.reference(), whole.held(), .4 * whole.condenserPerFeed()).waterBelow(), "a share's table, before the temperature: the split");
        assertThrows(IllegalArgumentException.class, () -> new SplitTable(whole.axis(), whole.products(), 0, whole.pumparoundPerFeed(), whole.condenserPerFeed(),
                whole.reference(), whole.held(), whole.condenserPerFeed(), Double.POSITIVE_INFINITY));
    }

    @Test
    void aClosureThatWouldHeatTheOverheadTripsTheColumnAndTakesNothing() {
        var table = table();
        // A feed far colder than the table's: the products would carry more energy than the feed brings and the duties add.
        var feed = new Account(new double[] {300, 400, 300, 0}, -1e10);
        var step = ColumnCycle.step(table, setpoints(), 5, feed, List.of(Account.empty(4)), new double[] {1e9, 1e9, 1e9, 1e9, 1e9},
                (k, n) -> energy(table, k, n), false);
        assertTrue(step.tripped());
        assertEquals(Condition.CLOSURE, bound(step));
        assertEquals("the condenser would heat the overhead", step.binding().text());
        // The trip binds; beside it stands only rule B's short steam (none here at all), which rule A and the steam's solve read.
        assertEquals(List.of(step.binding(), new Condition.Active(Condition.STEAM_SHORT, 1, 0, "")), step.conditions(), "the trip and the short steam");
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

    /** The test table with its worst tray at {@code fraction} of flood at the design feed (100 mol/s), a liquid-limited tray 12. */
    static SplitTable flooding(double fraction) {
        return table().withFlood(new SplitTable.Flood(fraction / 100, 12, false, 2_000, 6_000));
    }

    static final Account FEED = new Account(new double[] {300, 400, 300, 0}, -1.4e8);
    static final Account STEAM = new Account(new double[] {0, 0, 0, 1000}, -2.3e8);
    static final double[] OPEN = {1e9, 1e9, 1e9, 1e9, 1e9};

    /** T1 (batch 2026-11-10-column-failure-modes, W3.1): a table at 80 % of flood at the design feed takes all of it and raises nothing. */
    @Test
    void aTableBelowFloodTakesItsSetFeed() {
        var table = flooding(.8);
        var step = ColumnCycle.step(table, setpoints(), 5, FEED, List.of(STEAM), OPEN, (k, n) -> energy(table, k, n), false);
        assertEquals(1, step.alpha(), 1e-12);
        assertEquals(List.of(), step.conditions(), "below 85 % of flood nothing is said");
    }

    /**
     * T2 (W3.1): the table's flood per feed raised by 1.34 (reflux 2 to 3) puts the worst tray at 107 % at the set feed: the
     * cycle takes 1/1.072 of it, FLOODING binds with its tray, the worst tray stands at its limit (HIGH_LOAD at 100 %), and
     * components and energy close. T9: this test fails with the flood cap removed.
     */
    @Test
    void aTablePastFloodCutsTheFeedToTheLimitAndCloses() {
        var table = flooding(.8 * 1.34);
        var step = ColumnCycle.step(table, setpoints(), 5, FEED, List.of(STEAM), OPEN, (k, n) -> energy(table, k, n), false);
        assertEquals(Condition.FLOODING, bound(step));
        assertEquals(1 / 1.072, step.alpha(), 1e-12);
        assertEquals(12, step.binding().index(), "the worst tray");
        assertEquals(1, table.flood().fraction(step.feedMoles() / 5), 1e-12, "the worst tray at its limit");
        var high = Condition.find(step.conditions(), Condition.HIGH_LOAD);
        assertNotNull(high, step.conditions().toString());
        assertEquals("tray 13 at 100 % of flood", high.text());
        double[] in = new double[4], out = new double[4];
        double energyOut = 0;
        for (int i = 0; i < 4; i++) in[i] = step.feed().moles()[i] + step.steam().getFirst().moles()[i];
        for (var p : step.products()) {
            for (int i = 0; i < 4; i++) out[i] += p.moles()[i];
            energyOut += p.energy();
        }
        for (int i = 0; i < 4; i++) assertEquals(in[i], out[i], Math.max(in[i], 1) * 1e-12, AXIS.get(i) + " is conserved");
        double energyIn = step.feed().energy() + step.steam().getFirst().energy() + step.reboiler() + step.condenser() + java.util.Arrays.stream(step.pumparounds()).sum();
        assertEquals(energyIn, energyOut, Math.abs(energyOut) * 1e-12, "the energy closes on the condenser");
    }

    /** T3 (W3.1): flooding and a short feed together: the lower share binds and names its condition; both are listed. */
    @Test
    void floodingAndAShortFeedTheLowerBinds() {
        var table = flooding(.8 * 1.34);
        // 90 % of the set feed: the feed's 0.9 is below the flood's 0.933.
        var short90 = ColumnCycle.step(table, setpoints(), 5, FEED.part(.9 * 500 / 1000), List.of(STEAM), OPEN, (k, n) -> energy(table, k, n), false);
        assertEquals(Condition.FEED_SHORT, bound(short90));
        assertEquals(.9, short90.alpha(), 1e-12);
        assertNotNull(Condition.find(short90.conditions(), Condition.FLOODING), "the flood is listed though it does not bind");
        // 97 %: the flood binds.
        var short97 = ColumnCycle.step(table, setpoints(), 5, FEED.part(.97 * 500 / 1000), List.of(STEAM), OPEN, (k, n) -> energy(table, k, n), false);
        assertEquals(Condition.FLOODING, bound(short97));
        assertEquals(1 / 1.072, short97.alpha(), 1e-12);
    }

    /**
     * A finding of stage 1 (W3 step 7a): an outlet's room is weighed against a full take at the set demand, not against the
     * feed the accounts hold. A short feed with a tight outlet cut by the outlet fills its buffer to its room and no further.
     */
    @Test
    void aShortFeedWithATightOutletFillsTheBufferToItsRoom() {
        var table = table();
        var half = FEED.part(.25);
        double made = ColumnCycle.step(table, setpoints(), 5, FEED, List.of(STEAM), OPEN, (k, n) -> energy(table, k, n), false).products().get(4).total();
        // The feed holds half the demand; the bottoms has room for 0.3 of what the set feed makes: the outlet binds...
        double[] room = {1e9, 1e9, 1e9, 1e9, .3 * made};
        var step = ColumnCycle.step(table, setpoints(), 5, half, List.of(STEAM), room, (k, n) -> energy(table, k, n), false);
        assertEquals(Condition.PRODUCT_FULL, bound(step), step.conditions().toString());
        assertEquals(.3, step.alpha(), 1e-9);
        assertEquals(room[4], step.products().get(4).total(), 1e-9 * room[4], "filled to its room");
        // ...and with room for 0.6, the feed's half binds and the buffer keeps its spare.
        room[4] = .6 * made;
        var fed = ColumnCycle.step(table, setpoints(), 5, half, List.of(STEAM), room, (k, n) -> energy(table, k, n), false);
        assertEquals(Condition.FEED_SHORT, bound(fed));
        assertTrue(fed.products().get(4).total() < room[4]);
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
