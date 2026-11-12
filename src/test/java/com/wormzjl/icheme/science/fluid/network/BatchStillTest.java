package com.wormzjl.icheme.science.fluid.network;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.BatchStill.Cooling;
import com.wormzjl.icheme.science.fluid.network.BatchStill.Design;
import com.wormzjl.icheme.science.fluid.network.BatchStill.Heater;
import com.wormzjl.icheme.science.fluid.network.BatchStill.Inputs;
import com.wormzjl.icheme.science.fluid.network.BatchStill.Pot;
import com.wormzjl.icheme.science.fluid.network.BatchStill.Status;
import com.wormzjl.icheme.science.fluid.network.BatchStill.Step;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.SolidFuelCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The batch still's law and column (batch 2026-11-09-batch-distiller, BATCH_DISTILLER_PLAN.md sections 2, 5 and 7;
 * BATCH_STILL_LAW.md): sizes, the heater, the fuel records, the pot's heat-up and energy balance, simple distillation against
 * Rayleigh, the column against V3 on a naphtha pot, the crude pot's warming share and the statuses.
 */
class BatchStillTest {
    private static final double P = BatchStill.ATMOSPHERIC;
    private static final List<String> NAPHTHA = List.of("Isopentane", "N-pentane", "crude_pc01", "crude_pc02", "crude_pc03");
    private static final double[] NAPHTHA_Z = {.20, .30, .25, .15, .10};
    private static final FluidThermodynamics REFINERY = FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), "icheme:refinery");

    private static StillColumn column(List<String> compounds) {
        var view = REFINERY.on(compounds);
        assertEquals(compounds, view.compounds(), "the view keeps the package's order");
        return new StillColumn(view.hydrocarbon, P);
    }

    private static double[] moles(StillColumn column, double[] fractions, double kilograms) {
        double m = column.molarMass(fractions), total = kilograms / m;
        double[] n = new double[fractions.length];
        for (int i = 0; i < n.length; i++) n[i] = fractions[i] * total;
        return n;
    }

    private static double sum(double[] v) {
        double s = 0;
        for (double e : v) s += e;
        return s;
    }

    /** heat = the pot's enthalpy change + the distillate's + the vapour's + the condenser's duty. */
    private static void assertLedger(StillColumn column, Pot before, Step step) {
        double start = before.total() * column.liquidEnthalpy(before.temperature(), before.moles());
        double end = step.pot().total() > 0 ? step.pot().total() * column.liquidEnthalpy(step.pot().temperature(), step.pot().moles()) : 0;
        double out = end - start + step.liquidEnthalpy() + step.vapourEnthalpy() + step.condenserDuty();
        assertEquals(step.heat(), out, 1e-7 * Math.max(1, Math.abs(step.heat())) + 1e-9 * Math.abs(start), "energy ledger");
        double[] a = before.moles(), b = step.pot().moles();
        for (int i = 0; i < a.length; i++)
            assertEquals(a[i], b[i] + step.liquid()[i] + step.vapour()[i], 1e-12 * sum(a), "component " + i + " conserved");
        assertEquals(step.heat() / BatchStill.Heater.SOLID.efficiency(), step.fuel(), 1e-9 * Math.max(1, step.fuel()), "fuel at the efficiency");
    }

    @Test
    void sizesFollowThePot() {
        // Plan section 2's table: 100 L, 500 L and 2000 L pots.
        double[][] rows = {{.1, .503, 10.94e3, .66, .1245}, {.5, .860, 31.99e3, 1.92, .2128}, {2, 1.366, 80.66e3, 4.84, .3380}};
        for (double[] row : rows) {
            var design = new Design(row[0], Heater.SOLID, true, 4);
            assertEquals(row[1], design.diameter(), .002, "diameter");
            assertEquals(row[2], design.rating(), .005 * row[2], "rating: 25 kW/m² on 1.73 D²");
            assertEquals(row[3], design.condenserArea(), .01, "condenser: full boil-up + 20 % at 40 K and 500 W/m²K");
            assertEquals(row[4], design.columnDiameter(), .002, "column: F 1.5 at the full boil-up");
        }
        var electric = new Design(.5, Heater.ELECTRIC, true, 0);
        assertEquals(1.2 * electric.rating() / (500 * 40), electric.condenserArea(), 1e-12);
        assertEquals(0, new Design(.5, Heater.ELECTRIC, false, 0).condenserArea());
        assertEquals(0, new Design(.5, Heater.ELECTRIC, true, 0).columnDiameter());
        assertThrows(IllegalArgumentException.class, () -> new Design(.5, Heater.SOLID, true, 1), "a column has 2 to 8 trays");
        assertThrows(IllegalArgumentException.class, () -> new Design(.5, Heater.SOLID, true, 9));
    }

    @Test
    void heatersDrawAtTheirEfficiency() {
        assertEquals(.45, Heater.SOLID.efficiency());
        assertEquals(.55, Heater.LIQUID.efficiency());
        assertEquals(.90, Heater.ELECTRIC.efficiency());
        double q = new Design(.5, Heater.SOLID, true, 0).rating();
        assertEquals(71.1e3, Heater.SOLID.drawn(q), .2e3, "500 L at full firing: 71 kW of fuel");
        assertEquals(58.2e3, Heater.LIQUID.drawn(q), .2e3);
        assertEquals(711, BatchStill.electricPerTick(q, 2.5), 1, "35.5 kW at 2.5 J/FE");
    }

    @Test
    void theCondenserTakesTheFullBoilUpAtItsDesignWater() {
        var design = new Design(.5, Heater.SOLID, true, 0);
        double q = design.rating(), area = design.condenserArea();
        double water = BatchStill.designWaterFlow(q) * BatchStill.WATER_HEAT_CAPACITY;
        double capacity = BatchStill.condenserCapacity(area, 348.15, new Cooling(298.15, water));
        assertTrue(capacity >= BatchStill.EXCESS * q, "vapour at 75 °C, water from 25 °C: " + capacity);
        assertTrue(capacity / water <= 11.5, "the water rises about 10 K: " + capacity / water);
        assertEquals(500 * area * 50, BatchStill.condenserCapacity(area, 348.15, new Cooling(298.15, Double.POSITIVE_INFINITY)), 1e-6);
        assertEquals(0, BatchStill.condenserCapacity(area, 298.15, new Cooling(298.15, water)), "no drive");
        assertEquals(0, BatchStill.condenserCapacity(area, 348.15, new Cooling(298.15, 0)), "no water");
        assertTrue(BatchStill.condenserCapacity(area, 320, new Cooling(298.15, water)) < q, "a light cut at a low LMTD: the condenser limits");
    }

    @Test
    void solidFuelsBurnAtMassTimesHeatingValue() {
        var fuels = MaterialCatalog.bundled().fuels();
        double tick = BatchStill.JOULES_PER_BURN_TICK;
        assertEquals(2.314e9, fuels.energy("minecraft:coal", Set.of(), 1600, tick), .002e9, "coal: 833 / 9 kg at 25 MJ/kg");
        assertEquals(20.83e9, fuels.energy("minecraft:coal_block", Set.of("c:storage_blocks/coal"), 16000, tick), .01e9, "a block of coal: 1 m³");
        assertEquals(1.450e9, fuels.energy("immersiveengineering:coal_coke", Set.of("c:coal_coke"), 3200, tick), .002e9, "coke: 450 / 9 kg at 29 MJ/kg");
        assertEquals(.709e9, fuels.energy("minecraft:charcoal", Set.of(), 1600, tick), .002e9, "charcoal: 220 / 9 kg at 29 MJ/kg");
        assertEquals(8.25e9, fuels.energy("minecraft:oak_log", Set.of("minecraft:logs", "minecraft:logs_that_burn"), 300, tick), .001e9,
                "a log: one block at 550 kg/m³ and 15 MJ/kg");
        assertEquals(3.48e9, fuels.energy("minecraft:blaze_rod", Set.of(), 2400, tick), 1e3, "no record: burn ticks x 1.45 MJ");
        assertEquals(2400 * 2e6, fuels.energy("minecraft:blaze_rod", Set.of(), 2400, 2e6), 1e-3, "the configured joules per tick");
        assertEquals(0, fuels.energy("minecraft:stone", Set.of(), 0, tick), "not a fuel");
        for (var fuel : fuels.fuels().values()) assertFalse(fuel.revision().isBlank(), fuel.id() + " declares its revision");
        assertEquals(1600 * tick, fuels.energy("minecraft:coal", Set.of(), 1600, tick), .01 * 1600 * tick, "the anchor: coal at 1.45 MJ a tick");
    }

    @Test
    void solidFuelRecordsAreValidated() {
        String record = "{\"schema_version\":1,\"id\":\"icheme:a\",\"revision\":\"r1\",%s,\"bulk_density_kg_per_m3\":833,\"items_per_m3\":9,\"lower_heating_value_j_per_kg\":25e6}";
        var both = assertThrows(IllegalArgumentException.class, () -> SolidFuelCatalog.parse(Map.of("data/icheme/materials/fuels/a.json",
                String.format(record, "\"item\":\"minecraft:coal\",\"tag\":\"c:coal\""))));
        assertTrue(both.getMessage().startsWith("data/icheme/materials/fuels/a.json: item, tag"), both.getMessage());
        var missing = assertThrows(IllegalArgumentException.class, () -> SolidFuelCatalog.parse(Map.of("data/icheme/materials/fuels/a.json",
                String.format(record, "\"item\":\"minecraft:coal\"").replace(",\"items_per_m3\":9", ""))));
        assertTrue(missing.getMessage().contains("items_per_m3"), missing.getMessage());
        var twice = new LinkedHashMap<String, String>();
        twice.put("data/icheme/materials/fuels/a.json", String.format(record, "\"item\":\"minecraft:coal\""));
        twice.put("data/icheme/materials/fuels/b.json", String.format(record, "\"item\":\"minecraft:coal\"").replace("icheme:a", "icheme:b"));
        assertThrows(IllegalArgumentException.class, () -> SolidFuelCatalog.parse(twice), "one record per item");
        assertThrows(IllegalArgumentException.class, () -> SolidFuelCatalog.parse(Map.of("data/icheme/materials/fuels/a.json",
                String.format(record, "\"item\":\"minecraft:coal\"").replace("\"r1\"", "\"r 1\""))), "a revision is a token");
    }

    /** Plan section 2: 500 L of naphtha, 245 kg, rises about 0.06 K/s at 32 kW. */
    @Test
    void aNaphthaPotHeatsAtQOverMcp() {
        var column = column(NAPHTHA);
        var design = new Design(.5, Heater.SOLID, true, 0);
        var pot = new Pot(moles(column, NAPHTHA_Z, 245), 293.15, null);
        var step = BatchStill.step(column, design, pot, new Inputs(1, 1, 0, new Cooling(298.15, Double.POSITIVE_INFINITY)), 10);
        assertEquals(Status.HEATING, step.status());
        assertEquals(design.rating() * 10, step.heat(), 1e-6 * step.heat(), "all the firing heats the pot");
        double rate = (step.pot().temperature() - pot.temperature()) / 10;
        assertEquals(.06, rate, .01, "dT/dt = Q / (m cp): " + rate);
        assertEquals(step.heat(), step.warming(), 1e-9 * step.heat());
        assertTrue(Double.isNaN(step.headTemperature()), "no vapour at the head");
        assertLedger(column, pot, step);
        var half = BatchStill.step(column, design, pot, new Inputs(.5, 1, 0, null), 10);
        assertEquals(step.heat() / 2, half.heat(), 1e-6 * step.heat(), "firing is a share of the rating");
    }

    /** Simple distillation of isopentane and n-pentane follows the Rayleigh equation on the same equilibrium. */
    @Test
    void simpleDistillationFollowsRayleigh() {
        var column = column(List.of("Isopentane", "N-pentane"));
        var design = new Design(.1, Heater.SOLID, true, 0);
        double[] n = moles(column, new double[]{.5, .5}, 42);
        double w0 = sum(n);
        var tb = column.bubble(n, Double.NaN).temperature();
        var pot = new Pot(n, tb, null);
        // Pentanes condense near 26 °C: against water at 5 °C the condenser takes the boil-up at 40 % firing.
        var inputs = new Inputs(.4, 1, 0, new Cooling(278.15, Double.POSITIVE_INFINITY));
        double[] collected = new double[2];
        while (pot.total() > .5 * w0) {
            var step = BatchStill.step(column, design, pot, inputs, 5);
            assertEquals(Status.DISTILLING, step.status());
            assertEquals(0, sum(step.vapour()), 1e-12, "the condenser takes it all: " + step.condensed());
            assertLedger(column, pot, step);
            for (int i = 0; i < 2; i++) collected[i] += step.liquid()[i];
            pot = step.pot();
        }
        assertTrue(pot.temperature() > tb, "the pot's bubble point rises as it strips");
        // Rayleigh: ln(W / W0) = ∫ dx / (y - x) from x0, on the column's own bubble vapour.
        double target = Math.log(pot.total() / w0), x = .5, lnW = 0, y = column.bubble(new double[]{x, 1 - x}, tb).vapour()[0];
        double dx = -1e-5;
        while (lnW > target) {
            double next = x + dx, yn = column.bubble(new double[]{next, 1 - next}, tb).vapour()[0];
            double add = dx * (1 / (y - x) + 1 / (yn - next)) / 2;
            if (lnW + add < target) {
                next = x + dx * (target - lnW) / add;
                lnW = target;
            } else lnW += add;
            x = next;
            y = yn;
        }
        double potIso = pot.moles()[0] / pot.total();
        assertEquals(x, potIso, .002, "the pot on the Rayleigh curve: " + potIso + " against " + x);
        assertTrue(collected[0] / sum(collected) > .5, "the distillate is isopentane-rich");
    }

    /**
     * V3's reboiler in the B3 proxy is fed 20 times the boil-up of the pot liquid at its bubble point; its composition is
     * (F z - D xD) / (F - D). Pot mode held at that composition is the proxy's column.
     */
    private static StillColumn.Split proxy(StillColumn column, double[] z, int stages, double reflux) {
        double feed = 20;
        // The proxy's reboiler duty and its distillate, per mole of its own vapour: V3's reboiler as the B3 proxy defined it.
        var one = column.solve(z, 0, 0, null);
        double duty = one.energyPerDistillate() - one.potEnthalpy(), hFeed = one.potEnthalpy();
        double[] xr = z.clone();
        StillColumn.Split split = null;
        for (int pass = 0; pass < 8; pass++) {
            split = column.solve(xr, stages, reflux, split == null ? null : split.profile());
            assertTrue(split.converged(), split.failure());
            double d = (duty - feed * (split.potEnthalpy() - hFeed)) / (split.energyPerDistillate() - split.potEnthalpy());
            for (int i = 0; i < z.length; i++) xr[i] = (feed * z[i] - d * split.distillate()[i]) / (feed - d);
        }
        return split;
    }

    /** The distillate against V3 in the B3 proxy (research/2026-11-09-batch-distiller/b3-column/snapshots.csv, 0 % distilled). */
    @Test
    void theColumnMatchesV3OnANaphthaPot() {
        // stages, reflux, component, V3's distillate mole fraction.
        Object[][] rows = {{2, .5, 2, .1034}, {2, 2., 0, .5283}, {2, 8., 0, .5741}, {4, .5, 2, .0923}, {4, 2., 0, .5762}, {4, 8., 0, .6354},
                {8, .5, 2, .0891}, {8, 2., 0, .6135}, {8, 8., 0, .6728}};
        var reference = new StillColumn(FluidTestSupport.model(MaterialCatalog.bundled(), "icheme:tjl20_methane_nitrogen").on(NAPHTHA).hydrocarbon, P);
        var refinery = column(NAPHTHA);
        for (var row : rows) {
            int stages = (int) row[0], i = (int) row[2];
            double reflux = (double) row[1], v3 = (double) row[3];
            for (var model : List.of(reference, refinery)) {
                var split = proxy(model, NAPHTHA_Z, stages, reflux);
                assertEquals(v3, split.distillate()[i], .004, "N " + stages + ", R " + reflux + ", " + NAPHTHA.get(i));
            }
        }
        // In the still's own mode the pot is held at the charge: a pentane-rich distillate, and D = V / (R + 1) near.
        var split = refinery.solve(NAPHTHA_Z, 4, 2, null);
        assertTrue(split.converged(), split.failure());
        assertTrue(split.distillate()[0] + split.distillate()[1] > .8, "pentanes at the head");
        assertEquals(1, split.distillateRate(), "the rates are per mole of distillate");
        assertEquals(3, split.topVapourRate(), 1e-12, "(R + 1) D into the condenser");
        assertEquals(split.vapourRate() / 3, split.distillateRate(), .15 * split.vapourRate() / 3, "the energy balance moves D off V / (R + 1)");
        assertEquals(3 * split.headEnthalpy() - 2 * split.condensateEnthalpy(), split.energyPerDistillate(), 1e-9 * Math.abs(split.headEnthalpy()),
                "e = (R + 1) H1 - R h0");
        assertTrue(split.headTemperature() < split.potTemperature() && split.condensateTemperature() <= split.headTemperature() + 1e-9);
        double[] up = split.vapourFlows(), back = split.liquidFlows(), out = split.distillateFlows();
        for (int k = 0; k < up.length; k++) assertEquals(up[k], back[k] + out[k], 1e-12 * up[k] + 1e-15, "the column conserves each component");
        var warm = refinery.solve(NAPHTHA_Z, 4, 2, split.profile());
        assertTrue(warm.converged(), warm.failure());
        assertEquals(split.distillate()[0], warm.distillate()[0], 1e-4);
        var closed = refinery.solve(NAPHTHA_Z, 4, Double.POSITIVE_INFINITY, split.profile());
        assertTrue(closed.converged() && closed.closed());
        assertEquals(0, closed.distillateRate());
        assertEquals(1, closed.topVapourRate(), 1e-12, "a closed valve's rates are per mole of top vapour");
        assertEquals(closed.headEnthalpy() - closed.condensateEnthalpy(), closed.condenserDuty(), 0, "its heat: H1 - h0");
        assertTrue(closed.condenserDuty() > 0);
        assertTrue(closed.distillate()[0] > split.distillate()[0], "total reflux: the sharpest head");
        assertEquals(closed.vapourRate(), closed.liquidRate(), 1e-12 * closed.vapourRate());
    }

    /** Plan section 7: a crude pot's warming takes about twice the boil-up heat; a naphtha's 12 to 45 %; a pentane pair's none. */
    @Test
    void thePotsWarmingIsInTheRun() {
        var assay = REFINERY.hydrocarbon.propertyPackage().assays().get("icheme:light_crude");
        assertNotNull(assay);
        assertEquals("mass", assay.basis());
        var light = Set.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Nitrogen");
        var compounds = new ArrayList<String>();
        var amounts = new ArrayList<Double>();
        for (String id : REFINERY.compounds()) {
            int k = assay.components().indexOf(id);
            if (k < 0 || light.contains(id) || !(assay.amounts().get(k) > 0)) continue;
            compounds.add(id);
            amounts.add(assay.amounts().get(k) / REFINERY.hydrocarbon.molecularWeight(REFINERY.compounds().indexOf(id)));
        }
        double[] z = amounts.stream().mapToDouble(Double::doubleValue).toArray();
        var crude = column(compounds);
        double share = warming(crude, z, 300);
        assertEquals(2.1, share, .5, "crude: warming over boil-up " + share);
        // The column converges on the wide crude in pot mode, cold and warm (plan section 7, B3).
        for (int stages : new int[]{2, 8}) {
            var split = crude.solve(z, stages, .5, null);
            assertTrue(split.converged(), split.failure());
            assertTrue(crude.solve(z, stages, .5, split.profile()).converged());
        }
        double naphtha = warming(column(NAPHTHA), NAPHTHA_Z, 245);
        assertTrue(naphtha > .07 && naphtha < .25, "naphtha at its start: " + naphtha);
        double pair = warming(column(List.of("Isopentane", "N-pentane")), new double[]{.5, .5}, 245);
        assertTrue(Math.abs(pair) < .03, "a close pair barely warms: " + pair);
    }

    /** The pot's warming over the boil-up heat in a step that distils about 1 % of a charge of {@code kilograms} at its bubble point. */
    private static double warming(StillColumn column, double[] z, double kilograms) {
        var design = new Design(.5, Heater.SOLID, true, 0);
        double[] n = moles(column, z, kilograms);
        var pot = new Pot(n, column.bubble(n, Double.NaN).temperature(), null);
        var one = column.solve(n, 0, 0, null);
        // About 1 % of the charge: its vapour's enthalpy over the pot liquid's, twice over for the warming, at full firing (a step
        // size only: at these charges' bubble points that difference is positive).
        double dt = .01 * pot.total() * (one.energyPerDistillate() - one.potEnthalpy()) * 2 / design.rating();
        var step = BatchStill.step(column, design, pot, new Inputs(1, 1, 0, new Cooling(288.15, Double.POSITIVE_INFINITY)), dt);
        double distilled = 1 - step.pot().total() / pot.total();
        assertTrue(distilled > .002 && distilled < .03, "a small step: " + distilled);
        assertLedger(column, pot, step);
        return step.warming() / step.boilUp();
    }

    @Test
    void theStatusesFollowThePot() {
        var column = column(NAPHTHA);
        var design = new Design(.5, Heater.SOLID, true, 4);
        double[] n = moles(column, NAPHTHA_Z, 245);
        double tb = column.bubble(n, Double.NaN).temperature();
        var boiling = new Pot(n, tb, null);
        var water = new Cooling(278.15, Double.POSITIVE_INFINITY);
        // A pentane-rich head condenses at a small LMTD: against 15 °C water at full firing the condenser limits (plan
        // section 2); against 5 °C water at 40 % it does not.
        var full = BatchStill.step(column, design, boiling, new Inputs(1, 1, 2, new Cooling(288.15, Double.POSITIVE_INFINITY)), 5);
        assertEquals(Status.COOLING_LIMITED, full.status(), "a light cut at full firing");
        assertLedger(column, boiling, full);

        assertEquals(Status.IDLE, BatchStill.step(column, design, boiling, new Inputs(0, 1, 2, water), 5).status());
        var empty = BatchStill.step(column, design, boiling, new Inputs(1, 0, 2, water), 5);
        assertEquals(Status.NO_FUEL, empty.status());
        assertEquals(0, empty.heat());

        var distilling = BatchStill.step(column, design, boiling, new Inputs(.4, 1, 2, water), 5);
        assertEquals(Status.DISTILLING, distilling.status());
        assertTrue(distilling.headTemperature() < tb, "the head is cooler than the pot");
        assertLedger(column, boiling, distilling);

        var closed = BatchStill.step(column, design, boiling, new Inputs(.4, 1, Double.POSITIVE_INFINITY, water), 5);
        assertEquals(Status.BOILING, closed.status(), "a closed valve: total reflux");
        assertEquals(0, sum(closed.liquid()) + sum(closed.vapour()));
        assertEquals(closed.heat(), closed.condenserDuty(), 1e-9 * closed.heat(), "all the heat to the condenser");
        assertLedger(column, boiling, closed);

        var starved = BatchStill.step(column, design, boiling, new Inputs(1, 1, 2, new Cooling(288.15, 300)), 5);
        assertTrue(starved.alarm(Status.COOLING_LIMITED), "little water");
        assertEquals(Status.COOLING_LIMITED, starved.status());
        assertTrue(starved.condensed() < 1 && sum(starved.vapour()) > 0, "the excess vents");
        assertLedger(column, boiling, starved);

        var noCondenser = BatchStill.step(column, new Design(.5, Heater.SOLID, false, 0), boiling, new Inputs(1, 1, 2, water), 5);
        assertEquals(Status.DISTILLING, noCondenser.status());
        assertEquals(0, sum(noCondenser.liquid()), "without a condenser the vapour leaves by its nozzle");
        assertLedger(column, boiling, noCondenser);

        var hot = new Pot(n, BatchStill.OVERHEAT + 1, null);
        var cut = BatchStill.step(column, design, hot, new Inputs(1, 1, 2, water), 5);
        assertEquals(Status.OVERHEAT, cut.status());
        assertEquals(0, cut.heat(), "the firing cut at 350 °C");

        double[] little = n.clone();
        for (int i = 0; i < little.length; i++) little[i] *= .02;
        var dry = BatchStill.step(column, design, new Pot(little, tb, null), new Inputs(1, 1, 2, water), 5);
        assertEquals(Status.BOIL_DRY, dry.status(), "2 % of the pot's volume: the heated surface uncovers");
        assertEquals(0, dry.heat());

        // At 30 kPa the vapour is a third as dense: the column floods at full firing (a chilled condenser takes the vapour).
        var vacuum = new StillColumn(column.model(), 30e3);
        var low = new Pot(n, vacuum.bubble(n, Double.NaN).temperature(), null);
        var flooded = BatchStill.step(vacuum, design, low, new Inputs(1, 1, 2, new Cooling(213.15, Double.POSITIVE_INFINITY)), 5);
        assertFalse(flooded.alarm(Status.COOLING_LIMITED), "chilled: " + flooded.condensed());
        assertTrue(flooded.alarm(Status.FLOODING), "F " + flooded.fFactor());
        assertEquals(BatchStill.FLOOD_F_FACTOR, flooded.fFactor(), .01 * BatchStill.FLOOD_F_FACTOR, "held at the flood boil-up");
        assertTrue(flooded.heat() < design.rating() * 5, "the firing held back");
        assertLedger(vacuum, low, flooded);
    }

    /** A mixture preset's mole fractions on {@code compounds}, the package's order. */
    private static double[] preset(String name, List<String> compounds) {
        try (var input = new java.io.InputStreamReader(java.util.Objects.requireNonNull(BatchStillTest.class.getResourceAsStream(
                "/assets/icheme/mixturepreset/" + name + ".json")), java.nio.charset.StandardCharsets.UTF_8)) {
            var json = com.google.gson.JsonParser.parseReader(input).getAsJsonObject();
            assertEquals("mole", json.get("basis").getAsString());
            double[] z = new double[compounds.size()];
            for (var element : json.getAsJsonArray("components")) {
                var row = element.getAsJsonObject();
                int i = compounds.indexOf(row.get("id").getAsString());
                assertTrue(i >= 0, row.get("id").getAsString() + " in the package");
                z[i] += row.get("amount").getAsDouble();
            }
            double s = sum(z);
            for (int i = 0; i < z.length; i++) z[i] /= s;
            return z;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * A wide-boiling charge distils to its limit (batch 2026-11-12-still-heat-per-distillate, STILL_REFUSAL_REVIEW.md): a 500 L
     * solid-fired pot still with its built-in condenser and no column, 60 % full of medium crude at 25 °C, at full firing, run as
     * the simulator runs a batch. About half of medium crude boils below 360 °C, so the batch ends at OVERHEAT or BOIL DRY, with
     * every component and the energy ledger closed and the pot never cooling.
     */
    @Test
    void aMediumCrudePotStillRunsToItsLimit() {
        var column = new StillColumn(REFINERY.hydrocarbon, P);
        double[] z = preset("medium_crude", REFINERY.compounds());
        var design = new Design(.5, Heater.SOLID, true, 0);
        double t0 = 298.15, total = .6 * design.volume() / column.liquidVolume(t0, z);
        double[] charge = new double[z.length];
        for (int i = 0; i < z.length; i++) charge[i] = z[i] * total;
        double kilograms = total * column.molarMass(z);
        assertEquals(259.5, kilograms, 1, "60 % of 500 L of medium crude");
        var cooling = new Cooling(298.15, BatchStill.designWaterFlow(design.rating()) * BatchStill.WATER_HEAT_CAPACITY);
        var settings = new com.wormzjl.icheme.science.flowsheet.unit.StillBatch.Settings(design, 1, 2, cooling,
                List.of(new com.wormzjl.icheme.science.flowsheet.unit.StillBatch.Cut("1", com.wormzjl.icheme.science.flowsheet.unit.StillBatch.End.HEAD, 900)));
        var batch = com.wormzjl.icheme.science.flowsheet.unit.StillBatch.run(column, settings, charge, t0, () -> {
        });
        var end = batch.end();
        double[] distilled = batch.cuts().getFirst().moles(), vented = batch.vent(), left = end.moles();
        for (int i = 0; i < z.length; i++)
            assertEquals(charge[i], left[i] + distilled[i] + vented[i], 1e-6 * total, REFINERY.compounds().get(i) + " conserved");
        double start = total * column.liquidEnthalpy(t0, charge);
        double finish = end.total() > 0 ? end.total() * column.liquidEnthalpy(end.temperature(), left) : 0;
        double out = finish - start + batch.cuts().getFirst().enthalpy() + batch.ventEnthalpy() + batch.condenser();
        assertEquals(batch.heat(), out, 1e-6 * batch.heat(), "heat used = the pot's change + what left");
        double hot = 0;
        for (var point : batch.points()) {
            assertTrue(point.pot() >= hot - 1e-9, "the pot never cools while it distils: " + point.pot() + " after " + hot);
            assertTrue(point.rate() >= 0, "the distillate never runs back");
            hot = point.pot();
        }
        assertEquals(0, batch.flooded(), "a pot still has no column to flood");
        double share = sum(distilled) / total, ventShare = sum(vented) / total;
        double mass = column.molarMass(distilled) * sum(distilled) / kilograms;
        double ratio = batch.warming() / (batch.heat() - batch.warming());
        System.out.printf("CRUDE RUN finish %s steps %d pot %.2f C distilled %.4f (mass %.4f) vented %.4f warming/vapour %.3f failure %s%n",
                batch.finish(), batch.steps(), end.temperature() - 273.15, share, mass, ventShare, ratio, batch.failure());
        assertTrue(batch.finish() == com.wormzjl.icheme.science.flowsheet.unit.StillBatch.Finish.OVERHEAT
                || batch.finish() == com.wormzjl.icheme.science.flowsheet.unit.StillBatch.Finish.DRY, "ends at its limit: " + batch.finish());
        assertNull(batch.failure(), batch.failure());
        assertTrue(mass > .3 && mass < .5, "about half of medium crude boils below 360 °C; the pot reaches 350 °C first: " + mass);
        assertTrue(ratio > 2, "over a crude batch the pot's own warming takes most of the heat: " + ratio);
    }

    /** A whole short run on the column: every step conserves each component and closes its energy ledger; the cut sharpens with reflux. */
    @Test
    void aColumnRunConservesAndRectifies() {
        var column = column(NAPHTHA);
        var design = new Design(.5, Heater.SOLID, true, 4);
        double[] n = moles(column, NAPHTHA_Z, 245);
        var start = new Pot(n, 293.15, null);
        double[] pentanes = new double[2];
        for (double reflux : new double[]{.5, 4}) {
            var pot = start;
            double[] cut = new double[n.length];
            int warm = 0;
            for (int s = 0; s < 240 && sum(cut) < .1 * start.total(); s++) {
                var step = BatchStill.step(column, design, pot, new Inputs(.4, 1, reflux, new Cooling(278.15, Double.POSITIVE_INFINITY)), 30);
                assertLedger(column, pot, step);
                assertFalse(step.alarm(Status.COOLING_LIMITED), "40 % firing, 5 °C water: the condenser takes it");
                assertNull(step.columnFailure(), step.columnFailure());
                if (step.status() == Status.HEATING) warm++;
                for (int i = 0; i < n.length; i++) cut[i] += step.liquid()[i];
                pot = step.pot();
            }
            assertTrue(warm > 0, "it heats first");
            assertTrue(sum(cut) >= .1 * start.total(), "it distils a tenth of the charge");
            pentanes[reflux == .5 ? 0 : 1] = (cut[0] + cut[1]) / sum(cut);
        }
        assertTrue(pentanes[1] > pentanes[0], "more reflux, a sharper first cut: " + pentanes[0] + " -> " + pentanes[1]);
    }
}
