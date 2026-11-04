package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnDesign;
import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
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
        var catalog = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
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
                model,screen, () -> {});
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
        double[][] h = solved.enthalpy();
        var step = ColumnCycle.step(table, setpoints, SECONDS, feedAccount, steamAccounts, room, (k, n) -> dot(h[k], n), false);
        assertNull(step.trip());
        assertEquals(1, step.alpha(), 1e-12);
        assertEquals(0, step.departure(), 1e-9, "the table's condenser duty is the network's closure at its reference");
        double v3 = solved.solvedCondenser(), network = table.condenserPerFeed() * rate;
        assertTrue(network < 0 && Math.abs(network - v3) < .1 * Math.abs(v3), "the network's closure " + network + " W beside V3's " + v3 + " W");
        assertEquals(table.condenserPerFeed(), table.condenserWaterPerFeed(), "a water-cooled condenser's water takes the whole duty");
        assertEquals(SplitTable.WATER_ALWAYS, table.waterBelow(), "and every booking difference");
        assertTrue(DriftBands.check(table, step, setpoints.designFeedRate() * SECONDS).isEmpty(), "the reference is inside its own bands");

        // The feed 2 K warmer: a band crossed, solved warm from the seed.
        double warmer = table.reference().feedEnthalpy() + 2 * table.reference().enthalpySlope();
        var again = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.ENTHALPY, design, setpoints, new ColumnSolve.Taken(feed, warmer, steam), solved.seed()),
                model,screen, () -> {});
        var resolved = assertInstanceOf(ColumnSolve.Solved.class, again, again::toString);
        assertTrue(resolved.route().contains("warm"), "the warm route: " + resolved.route());
        assertEquals(warmer, resolved.table().reference().feedEnthalpy(), 0);
    }

    /**
     * The column's public basis is its feed island's list (batch 2026-11-04-refinery-compounds, V4): a network package of more
     * than 64 compounds (the bundled one extended by clones of the crude fractions' records under new ids) runs the default column
     * on an island that carries the feed's 20, and the table, on the wide world list, equals the bundled package's on each of its
     * compounds bit for bit; each clone, which the feed does not hold, takes a solved compound's row.
     */
    @Test void aNetworkPackageAbove64CompoundsSolvesOnItsFeedIslandsList() {
        var bundled = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        V3ColumnInput design = bundled.presets().column("tia_juana_nitrogen").input(bundled);
        var wide = MaterialCatalog.parse(widened(4));
        var narrow = FluidThermodynamics.forNetwork(bundled, design.packageId());
        var whole = FluidThermodynamics.forNetwork(wide, com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(wide));
        assertTrue(whole.compounds().size() > com.wormzjl.icheme.science.column.v3.V3ComponentBasis.MAX_COMPONENTS, "a wide network package: " + whole.compounds().size());
        var island = whole.on(narrow.compounds());
        assertEquals(narrow.components(), island.components(), "the island carries the feed's compounds and water");
        var setpoints = Setpoints.of(ColumnOperatingPoint.of(design));
        var steam = design.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin(), Double.NaN)).toList();
        double screen = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
        java.util.function.BiFunction<FluidThermodynamics, List<String>, ColumnSolve.Solved> solve = (model, world) -> {
            double[] feed = new double[world.size()];
            for (int c = 0; c < design.componentBasis().componentCount(); c++) feed[world.indexOf(design.componentBasis().componentId(c))] += design.feedComponentMolarFlowsMolPerSecond()[c];
            var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(feed, Double.NaN, steam), null), model, screen, () -> {});
            return assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString);
        };
        var reference = solve.apply(narrow, narrow.components());
        var solved = solve.apply(island, whole.components());
        var a = reference.table();
        var b = solved.table();
        assertEquals(whole.components(), b.axis(), "the table is on the world list");
        assertEquals(narrow.compounds(), solved.seed().input().componentBasis().componentIds(), "V3's public basis is the island's list");
        assertEquals(a.condenserPerFeed(), b.condenserPerFeed(), 0);
        assertEquals(a.reboilerPerFeed(), b.reboilerPerFeed(), 0);
        assertEquals(a.products().size(), b.products().size());
        double[][] ha = reference.enthalpy(), hb = solved.enthalpy();
        for (int k = 0; k < a.products().size(); k++) {
            var p = a.products().get(k);
            var q = b.products().get(k);
            assertEquals(p.port(), q.port());
            assertEquals(p.temperature(), q.temperature(), 0, p.port() + " temperature");
            for (int i = 0; i < a.axis().size(); i++) {
                int j = b.axis().indexOf(a.axis().get(i));
                assertEquals(p.recovery()[i], q.recovery()[j], 0, p.port() + " " + a.axis().get(i));
                assertEquals(ha[k][i], hb[k][j], 0, p.port() + " enthalpy of " + a.axis().get(i));
            }
        }
        // A clone takes the row of its own fraction, the solved compound nearest in boiling point.
        int clone = b.axis().indexOf("crude_pc05_x1"), original = b.axis().indexOf("crude_pc05");
        assertFalse(b.held()[clone]);
        for (var q : b.products()) assertEquals(q.recovery()[original], q.recovery()[clone], 0);
    }

    /**
     * The bundled resources with {@code copies} clones of each crude fraction's component and property records under new ids
     * ({@code crude_pc05_x1}, ...) and the network on {@code test:wide}, the bundled network package extended by the clones
     * (the column presets stay on their own packages, which the calculator keeps at 64).
     */
    private static java.util.Map<String, String> widened(int copies) {
        String root = "data/icheme/materials/";
        var resources = new java.util.HashMap<>(MaterialCatalog.bundled().resources());
        var network = com.google.gson.JsonParser.parseString(resources.get(root + "networks/default.json")).getAsJsonObject();
        var pack = new com.google.gson.JsonObject();
        pack.addProperty("schema_version", 1);
        pack.addProperty("id", "test:wide");
        pack.addProperty("revision", "r1");
        pack.addProperty("method", "icheme:crude_pr78");
        pack.addProperty("extends", network.get("package").getAsString());
        pack.add("components", new com.google.gson.JsonArray());
        network.addProperty("package", "test:wide");
        resources.put(root + "networks/default.json", network.toString());
        String path = root + "packages/test_wide.json";
        for (int k = 1; k <= copies; k++)
            for (int f = 1; f <= 12; f++) {
                String id = String.format(java.util.Locale.ROOT, "crude_pc%02d", f), copy = id + "_x" + k;
                var component = com.google.gson.JsonParser.parseString(resources.get(root + "components/" + id + ".json")).getAsJsonObject();
                component.addProperty("id", copy);
                component.addProperty("properties", "icheme:" + copy);
                resources.put(root + "components/" + copy + ".json", component.toString());
                var property = com.google.gson.JsonParser.parseString(resources.get(root + "properties/" + id + ".json")).getAsJsonObject();
                property.addProperty("id", "icheme:" + copy);
                property.addProperty("component", copy);
                resources.put(root + "properties/" + copy + ".json", property.toString());
                pack.get("components").getAsJsonArray().add(copy);
            }
        resources.put(path, pack.toString());
        return resources;
    }

    /**
     * Fact 1 (C1 review, section 1): at a prescribed tray drop the column's equations are homogeneous in its extensive inputs,
     * so half the feed, with the steam, draws and duties held per feed, solves to the table of the full feed: a cycle scales
     * the table instead of solving.
     */
    @Test void atAPrescribedDropHalfTheFeedSolvesToTheSameTable() {
        var catalog = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        var d = catalog.presets().column("tia_juana_nitrogen").input(catalog);
        var design = new V3ColumnInput(d.schemaVersion(), d.packageId(), d.assayId(), d.componentBasis(), d.feedComponentMolarFlowsMolPerSecond(), d.feedTemperatureKelvin(),
                d.stageCount(), d.feedStageNumber(), d.topPressurePascal(), 700.0, d.specifications(), d.sideDraws(), d.steamFeeds(), d.pumparounds(), 0.0);
        var model = FluidThermodynamics.forNetwork(catalog, design.packageId());
        var axis = model.components();
        double[] feed = new double[axis.size()];
        for (int c = 0; c < design.componentBasis().componentCount(); c++) {
            int i = axis.indexOf(design.componentBasis().componentId(c));
            if (i >= 0) feed[i] += design.feedComponentMolarFlowsMolPerSecond()[c];
        }
        var setpoints = Setpoints.of(ColumnOperatingPoint.of(design));
        double screen = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
        var full = (ColumnSolve.Solved) ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(feed, Double.NaN,
                design.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin(), Double.NaN)).toList()), null), model,screen, () -> {});
        var half = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.THROUGHPUT, design, setpoints, new ColumnSolve.Taken(scale(feed, .5), full.table().reference().feedEnthalpy(),
                design.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond() * .5, s.temperatureKelvin(), Double.NaN)).toList()), full.seed()), model,screen, () -> {});
        var scaled = assertInstanceOf(ColumnSolve.Solved.class, half, half::toString).table();
        var table = full.table();
        assertEquals(table.products().size(), scaled.products().size());
        for (int k = 0; k < table.products().size(); k++) {
            var a = table.products().get(k);
            var b = scaled.products().get(scaled.product(a.port(), a.index()));
            for (int i = 0; i < axis.size(); i++) assertEquals(a.recovery()[i], b.recovery()[i], 1e-6, a.port() + " " + axis.get(i));
            assertEquals(a.temperature(), b.temperature(), 1e-4, a.port() + " temperature");
        }
        assertEquals(table.condenserPerFeed(), scaled.condenserPerFeed(), 1e-4 * Math.abs(table.condenserPerFeed()), "the condenser duty per feed");
        assertEquals(.5, scaled.reference().alpha(), 1e-12);
    }

    /**
     * A column built on a crude package that is gone (batch 2026-11-02-process-simulator, W5): the package and assay a saved
     * point names are plain names, so the point starts, and the solve stands on the network's own package.
     */
    @Test void aSavedPointOnARemovedPackageStarts() {
        var catalog = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        V3ColumnInput design = catalog.presets().column("tia_juana_nitrogen").input(catalog);
        assertNull(catalog.packages().get("icheme:retired_crude_tjl20"), "the package this point names is gone");
        var built = ColumnDesign.of(design, ColumnComponents.Choices.DEFAULT.fit(design.pumparounds().size()));
        var p = ColumnOperatingPoint.of(design);
        var saved = new ColumnOperatingPoint("icheme:retired_crude_tjl20", "icheme:retired_crude", p.components(), p.feed(), p.feedTemperature(), p.topPressure(), p.stageDrop(),
                p.condenserTemperature(), p.refluxRatio(), p.reboilerDuty(), p.steam(), p.draws(), p.pumparounds());
        var trays = built.trays().withOperating(saved);
        var start = trays.operating().input(trays, built.diameter());
        assertEquals("icheme:retired_crude_tjl20", start.packageId());
        var model = FluidThermodynamics.forNetwork(catalog, design.packageId());
        var axis = model.components();
        double[] feed = new double[axis.size()];
        for (int c = 0; c < start.componentBasis().componentCount(); c++) {
            int i = axis.indexOf(start.componentBasis().componentId(c));
            if (i >= 0) feed[i] += start.feedComponentMolarFlowsMolPerSecond()[c];
        }
        var steam = start.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin(), Double.NaN)).toList();
        var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, start, Setpoints.of(saved), new ColumnSolve.Taken(feed, Double.NaN, steam), null),
                model,V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
        assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString);
    }

    /**
     * The water share of an air-then-water condenser (COOLER_MODEL_PLAN.md section 6), the rule alone: none for a receiver at
     * or above the 40 °C split, the whole duty for a top vapour at or below it, and between them the overhead's enthalpy from
     * the split to the receiver, never beyond the whole.
     */
    @Test void theTrimTakesNoneAboveTheSplitTheWholeBelowItAndTheOverheadBetween() {
        double split = com.wormzjl.icheme.science.equipment.HeatSides.AIR_SPLIT_TEMPERATURE, whole = -5e6;
        java.util.function.DoubleUnaryOperator linear = t -> 1e5 * t;
        assertEquals(313.15, split, 1e-12, "40 °C: the air plus 15 K");
        assertEquals(0, ColumnSolve.trim(390, split, whole, linear), "a receiver at the split: no trim duty");
        assertEquals(0, ColumnSolve.trim(390, 318.15, whole, linear), "a receiver above it");
        assertEquals(whole, ColumnSolve.trim(split, 300, whole, linear), "a top vapour at the split: the whole duty");
        assertEquals(whole, ColumnSolve.trim(310, 300, whole, linear), "a top vapour below it");
        assertEquals(-1e5 * (split - 303.15), ColumnSolve.trim(390, 303.15, whole, linear), 1e-6, "between: the overhead from the split to the receiver");
        assertEquals(whole, ColumnSolve.trim(390, 303.15, whole, t -> 1e7 * t), "never beyond the whole");
        assertEquals(0, ColumnSolve.trim(390, 303.15, 0, linear), "no duty, no share");
    }

    /**
     * The crude column on the network's basis (Tia Juana light) with an air-then-water condenser, at receivers of 45, 38 and 30 °C: the table keeps the whole
     * duty and gives its water the trim's share (none at 45 °C, more as the receiver falls); a water-cooled condenser's water
     * takes the whole duty and an air cooler's none. Prints the split and the air's and the water's MW for the batch.
     */
    @Test void anAirThenWaterCondensersWaterTakesTheTrimsShare() {
        var catalog = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        V3ColumnInput design = catalog.presets().column("tia_juana_nitrogen").input(catalog);
        var model = FluidThermodynamics.forNetwork(catalog, design.packageId());
        var axis = model.components();
        double[] feed = new double[axis.size()];
        for (int c = 0; c < design.componentBasis().componentCount(); c++) {
            int i = axis.indexOf(design.componentBasis().componentId(c));
            if (i >= 0) feed[i] += design.feedComponentMolarFlowsMolPerSecond()[c];
        }
        double rate = Arrays.stream(feed).sum();
        var steam = design.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin(), Double.NaN)).toList();
        var s = Setpoints.of(ColumnOperatingPoint.of(design));
        double before = 0;
        for (double celsius : new double[] {45, 38, 30}) {
            var setpoints = new Setpoints(s.designFeedRate(), s.feedRate(), 273.15 + celsius, s.refluxRatio(), s.reboilerPerFeed(), s.steamPerFeed(), s.drawsPerFeed(),
                    s.pumparoundsPerFeed());
            var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(feed, Double.NaN, steam), null,
                    ColumnComponents.Condenser.AIR_THEN_WATER), model,V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
            var solved = assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString);
            var table = solved.table();
            double whole = table.condenserPerFeed() * rate, water = table.condenserWaterPerFeed() * rate;
            // The seed's node 1 is the top tray.
            double top = solved.seed().temperatures()[1];
            System.out.printf(java.util.Locale.ROOT, "C1_AIR_THEN_WATER receiver=%.0f C top=%.1f C split=%.1f C condenser=%.3f MW air=%.3f MW water=%.3f MW share=%.3f%n",
                    celsius, top - 273.15, com.wormzjl.icheme.science.equipment.HeatSides.airSplit(top, 273.15 + celsius) - 273.15,
                    -whole / 1e6, -(whole - water) / 1e6, -water / 1e6, table.waterShare());
            assertTrue(whole < 0, "the condenser removes heat");
            assertEquals(com.wormzjl.icheme.science.equipment.HeatSides.AIR_SPLIT_TEMPERATURE, table.waterBelow(), "the trim takes the booking differences below the split");
            if (celsius >= 40) assertEquals(0, water, "a receiver at or above the split leaves the trim no duty");
            else {
                assertTrue(water < 0 && water > whole, "the trim takes part: " + water + " of " + whole + " W");
                assertTrue(water < before, "a colder receiver gives the trim more: " + water + " after " + before + " W");
            }
            before = water;
        }
    }

    /**
     * U2's numbers on the crude column on the network's basis (Tia Juana light, its design's receiver): an exchanger's water valve
     * with water at 25, 30 and 40 °C, its flow Q / (h(T_out) - h(T_in)) and its outlet, 10 K up but 5 K under the receiver; an air
     * cooler's fans on Auto's banks (the fewest banks, then the least face, that hold the need at full fans with the 20 % spare),
     * their speed and power; and air then water at a 30 °C receiver, its air's fans and its trim's water. Prints them for the batch.
     */
    @Test void theCondensersUtilitiesOnTheDefaultColumn() {
        var catalog = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        V3ColumnInput design = catalog.presets().column("tia_juana_nitrogen").input(catalog);
        var model = FluidThermodynamics.forNetwork(catalog, design.packageId());
        var axis = model.components();
        double[] feed = new double[axis.size()];
        for (int c = 0; c < design.componentBasis().componentCount(); c++) {
            int i = axis.indexOf(design.componentBasis().componentId(c));
            if (i >= 0) feed[i] += design.feedComponentMolarFlowsMolPerSecond()[c];
        }
        double rate = Arrays.stream(feed).sum();
        var steam = design.steamFeeds().stream().map(s -> new ColumnSolve.Steam(s.molarFlowMolPerSecond(), s.temperatureKelvin(), Double.NaN)).toList();
        var s = Setpoints.of(ColumnOperatingPoint.of(design));
        java.util.function.BiFunction<ColumnComponents.Condenser, Double, SplitTable> solve = (condenser, receiver) -> {
            var setpoints = new Setpoints(s.designFeedRate(), s.feedRate(), receiver, s.refluxRatio(), s.reboilerPerFeed(), s.steamPerFeed(), s.drawsPerFeed(), s.pumparoundsPerFeed());
            var outcome = ColumnSolve.run(new ColumnSolve.Job(DriftBands.Trigger.START, design, setpoints, new ColumnSolve.Taken(feed, Double.NaN, steam), null, condenser),
                    model,V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, () -> {});
            return assertInstanceOf(ColumnSolve.Solved.class, outcome, outcome::toString).table();
        };
        double receiver = s.condenserTemperature();
        // The exchanger: its water valve at three water temperatures.
        var exchanger = solve.apply(ColumnComponents.Condenser.EXCHANGER, receiver);
        assertEquals(0, exchanger.airArea().length, "a water-cooled condenser has no air");
        double q = -exchanger.condenserWaterPerFeed() * rate, before = 0;
        for (double celsius : new double[] {25, 30, 40}) {
            double in = 273.15 + celsius, out = HeatSides.coolingWaterOutlet(in, receiver), rise = water(model, out) - water(model, in);
            double flow = rise > 0 ? q / rise : Double.NaN;
            System.out.printf(java.util.Locale.ROOT, "U2_EXCHANGER receiver=%.1f C water=%.0f C out=%.1f C duty=%.3f MW flow=%.1f kg/s%n", receiver - 273.15, celsius, out - 273.15, q / 1e6, flow);
            if (rise > 0) {
                assertTrue(flow > before, "hotter water takes more: " + flow + " after " + before);
                before = flow;
            } else assertTrue(in >= receiver - HeatSides.WATER_APPROACH, "only water at or over the ceiling takes nothing");
        }
        // The air cooler: Auto's banks, then their fans at the set feed.
        var air = solve.apply(ColumnComponents.Condenser.AIR_COOLER, receiver);
        fans("U2_AIR_COOLER receiver=" + String.format(java.util.Locale.ROOT, "%.1f C", receiver - 273.15), air, rate, -air.condenserPerFeed() * rate);
        // Air then water at a 30 °C receiver: the air's fans, and the trim's water on 25 °C tower water.
        var trimmed = solve.apply(ColumnComponents.Condenser.AIR_THEN_WATER, 303.15);
        fans("U2_AIR_THEN_WATER receiver=30.0 C", trimmed, rate, -(trimmed.condenserPerFeed() - trimmed.condenserWaterPerFeed()) * rate);
        double trim = -trimmed.condenserWaterPerFeed() * rate;
        for (double celsius : new double[] {25, 20}) {
            double in = 273.15 + celsius, out = HeatSides.coolingWaterOutlet(in, 303.15), rise = water(model, out) - water(model, in);
            System.out.printf(java.util.Locale.ROOT, "U2_TRIM receiver=30.0 C water=%.0f C out=%.1f C duty=%.3f MW flow=%s%n", celsius, out - 273.15, trim / 1e6,
                    rise > 0 ? String.format(java.util.Locale.ROOT, "%.1f kg/s", trim / rise) : "none: the water stands at the ceiling");
        }
    }

    /** Auto's air cooler for {@code table}'s air at {@code rate} mol/s of feed and its fans there; printed after {@code label}. */
    private static void fans(String label, SplitTable table, double rate, double duty) {
        double[] area = table.airArea();
        assertEquals(SplitTable.FAN_STEPS + 1, area.length);
        for (int k = 1; k < area.length; k++) assertTrue(area[k] <= area[k - 1], "faster fans need less surface: " + Arrays.toString(area));
        double need = area[SplitTable.FAN_STEPS] * rate, held = need * 1.2;
        double[] best = null;
        for (int banks = 1; banks <= ColumnComponents.MAX_UNITS && best == null; banks++) {
            double[] found = null;
            for (var bay : ProcessUnit.bays())
                for (int parallel = 1; parallel <= ProcessUnit.Bank.MAX_PARALLEL; parallel++)
                    for (int series = 1; series <= ProcessUnit.Bank.MAX_SERIES; series++) {
                        var bank = new ProcessUnit.Bank(parallel, series);
                        double surface = banks * HeatSides.airCoolerSurface(bay[0], (int) bay[1], bank), face = bank.bays() * ProcessUnit.bundleFace(bay[0], (int) bay[1]);
                        if (surface >= held && (found == null || face < found[1] || face == found[1] && bank.bays() < found[2]))
                            found = new double[] {banks, face, bank.bays(), surface, bay[0], bay[1], parallel, series};
                    }
            best = found;
        }
        assertNotNull(best, "Auto holds the need: " + need + " m²");
        double surface = best[3], speed = table.fans(surface, rate), power = HeatSides.fanPower(surface, speed);
        System.out.printf(java.util.Locale.ROOT, "%s air=%.3f MW need=%.0f m² at full fans (%.0f at natural draught); Auto %d bank(s) of %d x %d bays of %.2f x %.0f m, %.0f m²; fans %.1f %%, %.1f kW (%.1f kW at full); full fans hold %.2f of the set feed%n",
                label, duty / 1e6, need, area[0] * rate, (int) best[0], (int) best[6], (int) best[7], best[4], best[5], surface, 100 * speed, power / 1e3, HeatSides.fanPower(surface, 1) / 1e3,
                table.airFeed(surface) / rate);
        assertTrue(speed < 1, "Auto's spare leaves the fans under full speed: " + speed);
        assertEquals(HeatSides.fanPower(surface, 1) * speed * speed * speed, power, 1e-9 * power, "the power by the cube of the speed");
    }

    /** Water's specific enthalpy at {@code t} K and 3 bar on {@code model}, J/kg. */
    private static double water(FluidThermodynamics model, double t) {
        var n = new double[model.componentCount()];
        n[model.components().indexOf(SplitTable.WATER)] = 1;
        var state = model.flashTP(t, 300_000, n, () -> {});
        return state.enthalpy() / state.mass();
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double[] scale(double[] v, double s) {
        double[] out = v.clone();
        for (int i = 0; i < out.length; i++) out[i] *= s;
        return out;
    }
}
