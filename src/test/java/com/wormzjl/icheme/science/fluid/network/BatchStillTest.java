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
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
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
        return new StillColumn(view, P);
    }

    /** {@code x} on the column's basis: water last at zero where the column carries it (W4 D1). */
    private static double[] on(StillColumn column, double[] x) {
        return java.util.Arrays.copyOf(x, column.componentCount());
    }

    private static double[] moles(StillColumn column, double[] given, double kilograms) {
        double[] fractions = on(column, given);
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
        double target = Math.log(pot.total() / w0), x = .5, lnW = 0, y = column.bubble(on(column, new double[]{x, 1 - x}), tb).vapour()[0];
        double dx = -1e-5;
        while (lnW > target) {
            double next = x + dx, yn = column.bubble(on(column, new double[]{next, 1 - next}), tb).vapour()[0];
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
    private static StillColumn.Split proxy(StillColumn column, double[] given, int stages, double reflux) {
        double feed = 20;
        double[] z = on(column, given);
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
        var reference = new StillColumn(FluidTestSupport.model(MaterialCatalog.bundled(), "icheme:tjl20_methane_nitrogen").on(NAPHTHA), P);
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
        double[] naphtha = on(refinery, NAPHTHA_Z);
        var split = refinery.solve(naphtha, 4, 2, null);
        assertTrue(split.converged(), split.failure());
        assertEquals(0, split.flashes(), "no engine split on a pot without water (D7)");
        assertTrue(split.distillate()[0] + split.distillate()[1] > .8, "pentanes at the head");
        assertEquals(1, split.distillateRate(), "the rates are per mole of distillate");
        assertEquals(3, split.topVapourRate(), 1e-12, "(R + 1) D into the condenser");
        assertEquals(split.vapourRate() / 3, split.distillateRate(), .15 * split.vapourRate() / 3, "the energy balance moves D off V / (R + 1)");
        assertEquals(3 * split.headEnthalpy() - 2 * split.refluxEnthalpy(), split.energyPerDistillate(), 1e-9 * Math.abs(split.headEnthalpy()),
                "e = (R + 1) H1 - R hR");
        assertEquals(split.condensateEnthalpy(), split.refluxEnthalpy(), 0, "one liquid: hR = hC = h0");
        assertTrue(split.headTemperature() < split.potTemperature() && split.condensateTemperature() <= split.headTemperature() + 1e-9);
        double[] up = split.vapourFlows(), back = split.liquidFlows(), out = split.distillateFlows();
        for (int k = 0; k < up.length; k++) assertEquals(up[k], back[k] + out[k], 1e-12 * up[k] + 1e-15, "the column conserves each component");
        var warm = refinery.solve(naphtha, 4, 2, split.profile());
        assertTrue(warm.converged(), warm.failure());
        assertEquals(split.distillate()[0], warm.distillate()[0], 1e-4);
        var closed = refinery.solve(naphtha, 4, Double.POSITIVE_INFINITY, split.profile());
        assertTrue(closed.converged() && closed.closed());
        assertEquals(0, closed.distillateRate());
        assertEquals(1, closed.topVapourRate(), 1e-12, "a closed valve's rates are per mole of top vapour");
        assertEquals(closed.headEnthalpy() - closed.condensateEnthalpy(), closed.condenserDuty(), 1e-12 * Math.abs(closed.condenserDuty()), "its heat: H1 - hC");
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
        var crude = column(compounds);
        double[] z = on(crude, amounts.stream().mapToDouble(Double::doubleValue).toArray());
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
        var vacuum = new StillColumn(column.thermo(), 30e3);
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
        var column = new StillColumn(REFINERY, P);
        double[] z = on(column, preset("medium_crude", REFINERY.compounds()));
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
            assertEquals(charge[i], left[i] + distilled[i] + vented[i], 1e-6 * total,
                    (i < REFINERY.compounds().size() ? REFINERY.compounds().get(i) : "water") + " conserved");
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

    // ---------------- W4: water on the column's basis (batch 2026-11-11-aqueous-solvents, W4_STILL_PLAN.md section 6) ----------------

    private static final FluidThermodynamics PILOT = FluidThermodynamics.forNetwork(PilotAqueousTestCatalog.catalog(), PilotAqueousTestCatalog.PACKAGE_ID);
    /** A wash of 10 % ethanol by volume: 0.0332 mole fraction (plan section 1). */
    private static final double WASH = .0332;

    private static StillColumn wet(FluidThermodynamics thermo, List<String> compounds) {
        var column = new StillColumn(thermo.on(compounds), P);
        assertEquals(column.componentCount() - 1, column.water(), "water last on the column's basis");
        return column;
    }

    private static int index(StillColumn column, String id) {
        int i = column.model().components().indexOf(id);
        assertTrue(i >= 0, id);
        return i;
    }

    private static double[] wash(StillColumn column) {
        double[] z = new double[column.componentCount()];
        z[index(column, "Ethanol")] = WASH;
        z[column.water()] = 1 - WASH;
        return z;
    }

    /** Ethanol's share of the bubble vapour of an ethanol and water liquid of ethanol fraction {@code x}. */
    private static double vapour(StillColumn column, double x, double guess) {
        double[] liquid = new double[column.componentCount()];
        liquid[index(column, "Ethanol")] = x;
        liquid[column.water()] = 1 - x;
        return column.bubble(liquid, guess).vapour()[index(column, "Ethanol")];
    }

    private static void assertConserves(StillColumn.Split split) {
        double[] up = split.vapourFlows(), back = split.liquidFlows(), out = split.distillateFlows();
        for (int i = 0; i < up.length; i++) assertEquals(up[i], back[i] + out[i], 1e-12 * up[i] + 1e-15, "the column conserves " + i);
        assertRising(split);
    }

    /** No converged answer has a tray colder than the stage above it by more than a kelvin (W4 plan section 12). */
    private static void assertRising(StillColumn.Split split) {
        if (!split.converged() || split.stages() < 2) return;
        double[] t = split.profile().temperatures();
        for (int j = 2; j <= split.stages(); j++) assertTrue(t[j] >= t[j - 1] - 1, "tray " + j + " " + t[j] + " under tray " + (j - 1) + " " + t[j - 1]);
    }

    private static String trays(StillColumn.Split split) {
        if (split.profile() == null) return "[]";
        var out = new StringBuilder("[");
        for (double t : split.profile().temperatures()) out.append(String.format(java.util.Locale.ROOT, " %.2f", t - 273.15));
        return out.append(" ]").toString();
    }

    /** The plan's W4 row: the wash starts to boil at 92.5 C, its first vapour 0.26 ethanol; one liquid, no layers. */
    @Test
    void aWashBoilsNear92CWithAQuarterEthanolInItsVapour() {
        var column = wet(PILOT, List.of("Ethanol"));
        var split = column.solve(wash(column), 0, 0, null);
        assertTrue(split.converged(), split.failure());
        double y = split.distillate()[index(column, "Ethanol")];
        System.out.printf(java.util.Locale.ROOT, "W4 wash: bubble %.3f C, vapour %.4f ethanol, %d flashes%n", split.potTemperature() - 273.15, y, split.flashes());
        assertEquals(92.5 + 273.15, split.potTemperature(), .5, "the wash's bubble point");
        assertEquals(.26, y, .01, "its first vapour");
        assertFalse(split.twoLiquidPot() || split.twoLiquidCondensate(), "ethanol and water: one liquid");
        assertTrue(split.flashes() >= 1 && split.flashes() <= 2, "the pot and the condensate, one split each: " + split.flashes());
        assertEquals(split.condensateEnthalpy(), split.distillateEnthalpy(), "one liquid: hD = hC");
    }

    /**
     * The rectifier on the wash against a McCabe-Thiele on the column's own bubble points (constant molar overflow: the two
     * latent heats are near): within 0.02, never above the model's azeotrope, and more trays or reflux never lower it.
     */
    @Test
    void aRectifierOnTheWashFollowsMcCabeThieleAndStopsShortOfTheAzeotrope() {
        var column = wet(PILOT, List.of("Ethanol"));
        double[] z = wash(column);
        int e = index(column, "Ethanol");
        // The azeotrope: y(x) = x near 0.9.
        double low = .7, high = .995;
        for (int k = 0; k < 50; k++) {
            double mid = (low + high) / 2;
            if (vapour(column, mid, 351) > mid) low = mid;
            else high = mid;
        }
        double azeotrope = (low + high) / 2;
        System.out.printf(java.util.Locale.ROOT, "W4 azeotrope on the model: %.4f%n", azeotrope);
        assertEquals(.8965, azeotrope, .002, "the engine's azeotrope (plan section 1's 0.906 was the offline prototype's)");
        int[][] rows = {{4, 1}, {4, 3}, {8, 1}, {8, 3}};
        double[] distillate = new double[rows.length];
        for (int r = 0; r < rows.length; r++) {
            int stages = rows[r][0];
            double reflux = rows[r][1];
            var split = column.solve(z, stages, reflux, null);
            assertTrue(split.converged(), split.failure());
            assertNull(split.notice(), "a miscible wash rectifies");
            distillate[r] = split.distillate()[e];
            double mt = mcCabeThiele(column, z[e], stages, reflux, azeotrope);
            System.out.printf(java.util.Locale.ROOT, "W4 rectifier N %d R %.0f: distillate %.4f, McCabe-Thiele %.4f, head %.2f C, %d iterations, %d flashes%n",
                    stages, reflux, distillate[r], mt, split.headTemperature() - 273.15, split.iterations(), split.flashes());
            assertEquals(mt, distillate[r], .02, "N " + stages + ", R " + reflux);
            assertTrue(distillate[r] < azeotrope, "never past the azeotrope");
            assertTrue(split.flashes() <= StillColumn.LAYER_ROUNDS + split.iterations() + 1 + stages, "D7: " + split.flashes());
            if (stages == 8 && reflux == 3) assertTrue(split.iterations() <= 40, "the wash pays nothing for the gap: " + split.iterations());
            assertConserves(split);
        }
        assertTrue(distillate[0] <= distillate[1] && distillate[1] <= distillate[3], "more reflux never lowers it");
        assertTrue(distillate[0] <= distillate[2] && distillate[2] <= distillate[3], "more trays never lower it");
    }

    /** The pot's distillate in pot mode by McCabe-Thiele: the pot a stage, N trays on the rectifying line, a total condenser. */
    private static double mcCabeThiele(StillColumn column, double pot, int stages, double reflux, double azeotrope) {
        double low = pot, high = azeotrope, potVapour = vapour(column, pot, 365);
        for (int k = 0; k < 40; k++) {
            double xd = (low + high) / 2, y = potVapour;
            for (int j = 0; j < stages; j++) {
                double x = Math.clamp(((reflux + 1) * y - xd) / reflux, 1e-9, azeotrope);
                y = vapour(column, x, 360);
            }
            if (y > xd) low = xd;
            else high = xd;
        }
        return (low + high) / 2;
    }

    /** A pot still's batch on the wash: each component and the energy close over the batch; the pot ends near water's boiling point. */
    @Test
    void aBatchOnTheWashConservesAndEndsNearWater() {
        var column = wet(PILOT, List.of("Ethanol"));
        int e = index(column, "Ethanol");
        var design = new Design(.1, Heater.SOLID, true, 0);
        double[] n = moles(column, wash(column), 70);
        double w0 = sum(n);
        var start = new Pot(n, 293.15, null);
        var pot = start;
        var inputs = new Inputs(1, 1, 0, new Cooling(288.15, Double.POSITIVE_INFINITY));
        double heat = 0, out = 0, first = Double.NaN;
        double[] collected = new double[n.length];
        int steps = 0;
        while (pot.total() > .6 * w0 && steps < 2000) {
            var step = BatchStill.step(column, design, pot, inputs, 60);
            assertLedger(column, pot, step);
            heat += step.heat();
            out += step.liquidEnthalpy() + step.vapourEnthalpy() + step.condenserDuty();
            if (Double.isNaN(first) && sum(step.liquid()) > 0) first = step.liquid()[e] / sum(step.liquid());
            for (int i = 0; i < n.length; i++) collected[i] += step.liquid()[i] + step.vapour()[i];
            pot = step.pot();
            steps++;
        }
        double hStart = start.total() * column.liquidEnthalpy(start.temperature(), start.moles());
        double hEnd = pot.total() * column.liquidEnthalpy(pot.temperature(), pot.moles());
        double residual = heat - (hEnd - hStart + out);
        double[] water = new double[n.length];
        water[column.water()] = 1;
        double boiling = column.bubble(water, 373).temperature();
        System.out.printf(java.util.Locale.ROOT, "W4 wash batch: %d steps, first distillate %.4f ethanol, pot ends %.3f C (water boils %.3f C) at %.2e ethanol, energy residual %.2e of the charge's %.3e J%n",
                steps, first, pot.temperature() - 273.15, boiling - 273.15, pot.moles()[e] / pot.total(), residual / Math.abs(hStart), hStart);
        assertTrue(pot.total() <= .6 * w0, "the batch distils 40 % of the charge");
        assertEquals(.26, first, .01, "the first distillate is the wash's first vapour");
        for (int i = 0; i < n.length; i++) assertEquals(n[i], pot.moles()[i] + collected[i], 1e-12 * w0, "component " + i + " over the batch");
        assertEquals(0, residual, 1e-9 * Math.abs(hStart) + 1e-9 * heat, "the batch's energy");
        assertEquals(boiling, pot.temperature(), 1, "the pot ends near water's boiling point");
        assertTrue(pot.temperature() < boiling, "a trace of ethanol left");
    }

    /**
     * Water under n-hexane boils below both boiling points (61.14 C, a vapour of 0.794 hexane on the engine, W3a; the plan's
     * 61.4 C and 0.791); the condensate settles into two layers. The column over it starts its trays on the pot's oil layer
     * (W4 plan section 12): every tray stands at the pot's 61.14 C under the pot's vapour, its liquid the oil layer at
     * saturation; the reflux is the oil layer (D3), the distillate what the reflux leaves (about 0.18 hexane). At R 5 the
     * reflux draws on the water layer.
     */
    @Test
    void waterUnderHexaneBoilsBelowBothAndRefluxesItsOilLayer() {
        var column = wet(PILOT, List.of("N-hexane"));
        int h = index(column, "N-hexane"), w = column.water();
        double[] z = new double[column.componentCount()];
        z[h] = .5;
        z[w] = .5;
        var single = column.solve(z, 0, 0, null);
        assertTrue(single.converged(), single.failure());
        System.out.printf(java.util.Locale.ROOT, "W4 water and n-hexane: %.3f C, vapour %.4f hexane, condensate %.3f C, %d flashes%n",
                single.potTemperature() - 273.15, single.distillate()[h], single.condensateTemperature() - 273.15, single.flashes());
        assertTrue(single.twoLiquidPot() && single.twoLiquidCondensate(), "two liquids in the pot and the condensate");
        assertEquals(61.14 + 273.15, single.potTemperature(), .05, "the heteroazeotrope's temperature, the engine's (W3a)");
        assertEquals(.7936, single.distillate()[h], .002, "its vapour");
        assertTrue(single.flashes() <= 2 * StillColumn.LAYER_ROUNDS, "D7: " + single.flashes());
        var column4 = column.solve(z, 4, 3, null);
        double[] reflux = column4.converged() && column4.stages() > 0 ? column4.profile().liquids()[0] : new double[z.length];
        double topVapour = (3 * reflux[h] + column4.distillate()[h]) / 4;
        System.out.printf(java.util.Locale.ROOT, "W4 water and n-hexane, 4 trays R 3: %s, notice %s, reflux %.2e water, top vapour %.4f, distillate %.4f hexane, trays %s, settling %s, %d iterations, %d flashes%n",
                column4.converged() ? "converged" : column4.failure(), column4.notice(), reflux[w], topVapour, column4.distillate()[h], trays(column4),
                java.util.Arrays.toString(column4.settlingTrays()), column4.iterations(), column4.flashes());
        assertTrue(column4.converged(), column4.failure());
        assertNull(column4.notice(), "the column rectifies from the oil layer");
        assertTrue(column4.iterations() <= 10, "iterations " + column4.iterations());
        // 21 flashes (accepted as measured): the pot's and the condensate's search 10, the condensate one an iteration 7, the
        // four trays split once at the end (D4's notice) 4.
        assertTrue(column4.flashes() <= 21, "flashes " + column4.flashes());
        for (int j = 1; j <= 4; j++) assertEquals(single.potTemperature(), column4.profile().temperatures()[j], .1, "tray " + j + " at the pot's temperature");
        assertEquals(.7936, topVapour, .002, "the pot's vapour reaches the condenser");
        assertEquals(.18, column4.distillate()[h], .03, "the distillate is what the reflux leaves");
        assertTrue(column4.twoLiquidCondensate(), "the condensate settles");
        assertTrue(reflux[w] < .01, "the reflux is the oil layer: " + reflux[w]);
        assertEquals(column4.condensateEnthalpy() * 4, 3 * column4.refluxEnthalpy() + column4.distillateEnthalpy(),
                1e-9 * Math.abs(column4.condensateEnthalpy()) * 4, "(R + 1) hC = R hR + hD");
        assertConserves(column4);
        var column5 = column.solve(z, 4, 5, null);
        System.out.printf(java.util.Locale.ROOT, "W4 water and n-hexane, 4 trays R 5: %s, notice %s, stages %d, distillate %.4f hexane, trays %s, settling %s, %d iterations, %d flashes%n",
                column5.converged() ? "converged" : column5.failure(), column5.notice(), column5.stages(), column5.distillate()[h], trays(column5),
                java.util.Arrays.toString(column5.settlingTrays()), column5.iterations(), column5.flashes());
        assertTrue(column5.converged(), "R 5 converges, with its notice or as the pot's single stage: " + column5.failure());
        assertTrue(column5.notice() == null || column5.stages() == 0, "the marker comes with the single stage");
        assertConserves(column5);
    }

    /**
     * The wash with n-hexane from 1e-9 to 1e-2 of the charge, across the condensate's second layer's onset (D3). In pot mode
     * all of the pot vapour's hexane leaves with the distillate, so the head fills with hexane as the trace grows and the
     * condensate settles from about 1e-7. Under the onset the column rectifies. From 1e-7 to 3e-6 the trays would hold two
     * liquids and the iteration stalls: the answer is the pot's single stage with its marker (W4 plan section 12), in 60
     * iterations or fewer. From 1e-5 the pot settles too and the column converges on one-liquid trays that would settle: D4's
     * notice (every tray reported), a profile rising downward, the reflux returning the hexane. The head stands under its
     * condensate's bubble point there: the known error of one-liquid trays, printed. Every solve converges; the distillate
     * moves by less than 0.01 between neighbours of one regime (the pot's own vapour aside, which follows the trace).
     */
    @Test
    void aTraceOfHexaneMovesTheDistillateContinuously() {
        var column = wet(PILOT, List.of("Ethanol", "N-hexane"));
        int e = index(column, "Ethanol"), h = index(column, "N-hexane");
        double[] last = null;
        int lastRegime = 0;
        var broken = new java.util.ArrayList<String>();
        StillColumn.Profile warm = null;
        for (double share : new double[]{1e-9, 1e-8, 3e-8, 1e-7, 3e-7, 1e-6, 3e-6, 1e-5, 3e-5, 1e-4, 3e-4, 1e-3, 3e-3, 1e-2}) {
            double[] z = new double[column.componentCount()];
            z[e] = WASH * (1 - share);
            z[column.water()] = (1 - WASH) * (1 - share);
            z[h] = share;
            var split = column.solve(z, 4, 3, warm);
            double[] x = split.distillate();
            System.out.printf(java.util.Locale.ROOT, "W4 wash with hexane %.0e: %s, notice %s, stages %d, distillate ethanol %.4f hexane %.4f water %.4f, layers %b, pot two %b, trays %s, settling %s, %d iterations, %d flashes%n",
                    share, split.converged() ? "converged" : split.failure(), split.notice(), split.stages(), x[e], x[h], x[column.water()], split.twoLiquidCondensate(),
                    split.twoLiquidPot(), trays(split), java.util.Arrays.toString(split.settlingTrays()), split.iterations(), split.flashes());
            assertTrue(split.converged(), "at " + share + ": " + split.failure());
            assertConserves(split);
            boolean potStill = split.notice() != null;
            if (potStill) {
                assertEquals(StillColumn.POT_STILL, split.notice());
                assertEquals(0, split.stages(), "the marker's answer is the pot's single stage");
                assertTrue(split.iterations() <= 60, "a stall ends early: " + split.iterations());
                assertArrayEquals(column.solve(z, 0, 0, null).distillate(), x, 1e-12, "the pot's own single stage");
            }
            // 0 rectified, 1 the single stage with its marker (the pot's own vapour, pinned above), 2 one-liquid trays that would
            // settle over a pot of one liquid, 3 the same over a pot that settles too (from 3e-5).
            int regime = potStill ? 1 : split.twoLiquidPot() ? 3 : split.twoLiquidCondensate() ? 2 : 0;
            assertEquals(share < 1e-7 ? 0 : share < 1e-5 ? 1 : 2, Math.min(regime, 2), "the regime at " + share);
            if (regime >= 2) {
                assertTrue(split.settlingTrays().length > 0, "the trays that would settle are reported");
                assertTrue(x[h] <= 1e-3, "the reflux returns the hexane: " + x[h]);
                System.out.printf(java.util.Locale.ROOT, "W4 wash with hexane %.0e: the head %.2f K under its condensate's bubble point%n", share,
                        split.condensateTemperature() - split.headTemperature());
            }
            if (last != null && regime == lastRegime && regime != 1) for (int i = 0; i < x.length; i++) if (Math.abs(last[i] - x[i]) > .01) broken.add("moved at " + share + ", component " + i);
            if (last != null && regime != lastRegime) System.out.printf(java.util.Locale.ROOT, "W4 wash with hexane: the step at %.0e, ethanol %.4f to %.4f%n", share, last[e], x[e]);
            lastRegime = regime;
            warm = split.profile();
            last = x;
        }
        assertTrue(broken.isEmpty(), "the distillate moves by less than 0.01 within a regime: " + broken);
    }

    /**
     * A wet naphtha: the condensate's water layer leaves with the distillate and the reflux carries only the oil layer's
     * dissolved water; what water returns to the pot is the trays' (their one liquid holds it). A tray that would hold free
     * water is a notice, and the solve stands (D4). The plan's "water's share at the head 1 within 1e-6" holds only for
     * trays without water: the trays return a little of the steam with their oil, the share at least 0.98 (orchestrator).
     */
    @Test
    void aWetNaphthaSendsItsWaterOverheadAndFlagsAColdTray() {
        var column = wet(REFINERY, NAPHTHA);
        int w = column.water();
        double[] z = on(column, NAPHTHA_Z);
        for (int i = 0; i < z.length; i++) z[i] *= .98;
        z[w] = .02;
        long started = System.nanoTime();
        var split = column.solve(z, 4, 2, null);
        double ms = (System.nanoTime() - started) / 1e6;
        double share = split.distillateFlows()[w] / split.vapourFlows()[w];
        System.out.printf(java.util.Locale.ROOT, "W4 wet naphtha: the head %.2f K under its condensate's bubble point, %.1f ms (cold, not a timing run)%n",
                split.condensateTemperature() - split.headTemperature(), ms);
        System.out.printf(java.util.Locale.ROOT, "W4 wet naphtha: %s, notice %s, water's share at the head 1 - %.2e, pot two %b, layers %b, trays %s, settling %s, %d iterations, %d flashes%n",
                split.converged() ? "converged" : split.failure(), split.notice(), 1 - share, split.twoLiquidPot(), split.twoLiquidCondensate(), trays(split),
                java.util.Arrays.toString(split.settlingTrays()), split.iterations(), split.flashes());
        assertTrue(split.converged(), split.failure());
        assertNull(split.notice(), "the wet naphtha rectifies");
        assertTrue(split.twoLiquidCondensate(), "the condensate settles a water layer");
        assertTrue(split.profile().liquids()[0][w] < .01, "the reflux is the oil layer: " + split.profile().liquids()[0][w]);
        assertTrue(share >= .98 && share < 1, "most of the steam leaves with the distillate: " + share);
        assertEquals(split.vapourFlows()[w] - split.distillateFlows()[w], split.liquidFlows()[w], 1e-9 * split.vapourFlows()[w], "the rest returns with the trays' liquid");
        assertTrue(split.iterations() <= 11, "iterations " + split.iterations());
        // 38 flashes (accepted as measured): the pot's two layers and the condensate's search from the oil-layer start, the
        // condensate one an iteration, the four trays split once at the end (D4's notice).
        assertTrue(split.flashes() <= 38, "flashes " + split.flashes());
        assertConserves(split);
    }

    /**
     * A wet crude on the refinery package's whole list (2 % water) at 8 trays, R 3: the hot pot's vapour is mostly steam, so the
     * condensate's oil layer is under the reflux, which draws on the water layer: trays holding free water for real. The
     * iteration fails, and the answer is the pot's single stage with the marker (W4 plan section 12). Solved cold, then again
     * from the answer's own profile (two stages, so cold too: pitfall a). Within a few hundred flashes a solve.
     */
    @Test
    void aWetCrudeOnTheWholeListCostsAFewHundredFlashes() {
        var column = new StillColumn(REFINERY, P);
        int w = column.water(), n = column.componentCount();
        double[] z = new double[n];
        // A crude falling off with its boiling cuts: each compound 0.85 of the last.
        double total = 0;
        double[] critical = StillHead.critical(REFINERY);
        // The light gases (critical under 400 K) left out: a pot at 1 atm holds none.
        for (int i = 0, k = 0; i < n - 1; i++) if (critical[i] >= 400) total += z[i] = Math.pow(.85, k++);
        for (int i = 0; i < n - 1; i++) z[i] *= .98 / total;
        z[w] = .02;
        StillColumn.Profile warm = null;
        for (int pass = 0; pass < 2; pass++) {
            long start = System.nanoTime();
            var split = column.solve(z, 8, 3, warm);
            double ms = (System.nanoTime() - start) / 1e6;
            System.out.printf(java.util.Locale.ROOT, "W4 wet crude, %d components, 8 trays R 3, pass %d: %s, notice %s, stages %d, trays %s, %d iterations, %d flashes, %.0f ms%n",
                    n, pass, split.converged() ? "converged" : split.failure(), split.notice(), split.stages(), trays(split), split.iterations(), split.flashes(), ms);
            assertTrue(split.converged(), split.failure());
            assertEquals(StillColumn.POT_STILL, split.notice());
            assertEquals(0, split.stages());
            assertTrue(split.flashes() <= 400, "flashes " + split.flashes());
            if (pass == 0) {
                // The first decanter's O against R: the pot vapour's condensate, its oil layer's share of R + 1 = 4 moles.
                var model = column.thermo();
                var condensate = model.flashTP(split.condensateTemperature() - .5, P, split.distillate(), () -> {
                });
                double oil = java.util.Arrays.stream(FluidThermodynamics.phaseMoles(condensate, FluidThermodynamics.OIL)).sum() / java.util.Arrays.stream(split.distillate()).sum();
                System.out.printf(java.util.Locale.ROOT, "W4 wet crude: condensate's oil layer O = %.3f against R = 3 (water %.3f of the vapour)%n", 4 * oil, split.distillate()[w]);
            }
            assertConserves(split);
            warm = split.profile();
        }
    }
}
