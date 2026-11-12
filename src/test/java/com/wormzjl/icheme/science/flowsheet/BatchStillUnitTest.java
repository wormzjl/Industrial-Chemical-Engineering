package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The W7 gate of batch 2026-11-09-batch-distiller: the batch still on the sheet integrates one batch of naphtha with the world's law
 * ({@code BatchStill}, {@code StillColumn}). Each cut leaves by its own port and the residue by its own; what entered over the cycle
 * left, in moles and in energy, with the law's ledger as the unit's duty; and the batch's curve rides on the report.
 */
final class BatchStillUnitTest {
    private static final String PACKAGE = "icheme:refinery";
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, PACKAGE).components();
    /** The law's naphtha (BatchStillTest): two pentanes and the three lightest crude cuts. */
    private static final Map<String, Double> NAPHTHA = naphtha();
    /**
     * A heavier naphtha, whose head (about 70 to 110 °C) the worm tub was sized for (plan section 2: a 40 K design LMTD on
     * 25 °C water). The pentane-rich one above heads near 30 °C, which 25 °C water condenses only in part (owner, phase 1:
     * the rest vents, COOLING LIMITED).
     */
    private static final Map<String, Double> HEAVY_NAPHTHA = Map.of("N-hexane", .30, "Cyclohexane", .20, "Benzene", .10, "Toluene", .25, "P-xylene", .15);
    /** The heavier naphtha's cuts: the C6 cut to an 85 °C head, then to 120 °C. */
    private static final String HEAVY_CUTS = "\"cuts\":[{\"id\":\"1\",\"end\":\"head\",\"value\":358.15},{\"id\":\"2\",\"end\":\"head\",\"value\":393.15}]";
    /** 0.3 mol/s over the 2 h cycle: 2,160 mol, about 190 kg and 270 L, a little over half the 500 L pot. */
    private static final double FLOW = .3;

    private static Map<String, Double> naphtha() {
        var z = new LinkedHashMap<String, Double>();
        z.put("Isopentane", .20);
        z.put("N-pentane", .30);
        z.put("crude_pc01", .25);
        z.put("crude_pc02", .15);
        z.put("crude_pc03", .10);
        return z;
    }

    private static Flowsheet.Feed feed(double temperature, double pressure, Map<String, Double> fractions, double flow) {
        var ids = new LinkedHashMap<String, Double>();
        double total = fractions.values().stream().mapToDouble(Double::doubleValue).sum();
        for (var entry : fractions.entrySet()) ids.put(entry.getKey(), entry.getValue() * flow / total);
        return new Flowsheet.Feed(temperature, pressure, ids, List.of());
    }

    /** One still fed naphtha, with every port it has run to a product. */
    private static FlowsheetRun still(String settings, Flowsheet.Feed feed) {
        var params = new Params(JsonParser.parseString(settings).getAsJsonObject());
        var units = List.of(new Flowsheet.Unit("B", "batch_still", "B", 0, 0, params));
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("in", "in", null, new Flowsheet.End("B", "charge"), feed));
        for (var port : new com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit().ports(params))
            if (port.direction() == Port.Direction.OUT) streams.add(new Flowsheet.Stream(port.id(), port.id(), new Flowsheet.End("B", port.id()), null, null));
        var basis = new Flowsheet.Basis(PACKAGE, "test", AXIS);
        return FlowsheetRuns.run(new Flowsheet(basis, units, streams, FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {
        });
    }

    private static FlowsheetRun naphthaStill(String settings) {
        return still(settings, feed(300, 1.5e5, NAPHTHA, FLOW));
    }

    /** The heavier naphtha on its two cuts, with {@code more} settings (a JSON members list, or empty). */
    private static FlowsheetRun heavyStill(String more) {
        return still("{" + HEAVY_CUTS + (more.isEmpty() ? "" : "," + more) + "}", feed(300, 1.5e5, HEAVY_NAPHTHA, FLOW));
    }

    /** Steady, and what entered left: in moles to the flash's closure, in energy with the unit's duty. */
    private static FlowsheetRun closes(FlowsheetRun run) {
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log() + " notes " + run.reports().values().stream().flatMap(r -> r.notes().stream()).toList());
        assertEquals(0, run.massGap(), 1e-9 * Math.max(1, run.massIn()), "mass");
        double scale = Math.max(Math.max(Math.abs(run.heatIn()), Math.abs(run.heatOut())), run.duties());
        assertEquals(0, run.heatGap(), 1e-6 * Math.max(1, scale), "energy");
        double[] in = run.streams().get("in").amounts(), out = new double[in.length];
        for (var entry : run.streams().entrySet()) {
            if (entry.getKey().equals("in")) continue;
            double[] amounts = entry.getValue().amounts();
            for (int i = 0; i < out.length; i++) out[i] += amounts[i];
        }
        for (int i = 0; i < in.length; i++) assertEquals(in[i], out[i], 1e-9 * Math.max(1e-3, in[i]), AXIS.get(i));
        return run;
    }

    private static double reading(FlowsheetRun run, String key) {
        var readings = run.reports().get("B").readings();
        assertTrue(readings.containsKey(key), () -> key + " in " + readings);
        return readings.get(key);
    }

    private static boolean noted(FlowsheetRun run, String key) {
        return run.log().stream().anyMatch(note -> note.key().equals(key)) || run.reports().values().stream().flatMap(r -> r.notes().stream()).anyMatch(note -> note.key().equals(key));
    }

    /** The C6 share (n-hexane, cyclohexane, benzene) of a stream. */
    private static double sixes(FlowsheetRun run, String stream) {
        var s = run.streams().get(stream);
        double[] a = s.amounts();
        return (a[AXIS.indexOf("N-hexane")] + a[AXIS.indexOf("Cyclohexane")] + a[AXIS.indexOf("Benzene")]) / s.total();
    }

    /** The pentanes' mole share of a stream. */
    private static double pentanes(FlowsheetRun run, String stream) {
        var s = run.streams().get(stream);
        double[] a = s.amounts();
        return (a[AXIS.indexOf("Isopentane")] + a[AXIS.indexOf("N-pentane")]) / s.total();
    }

    /**
     * The law's ledger is the unit's duty: the heat into the pot, less the condenser's to its water, and the charge's let-down into the
     * pot. Its residue's enthalpy is the pot's own at the batch's end, so this holds only when the unit books what the law booked.
     */
    private static void ledger(FlowsheetRun run) {
        double duty = run.reports().get("B").duty();
        double ledger = reading(run, "duty") - reading(run, "condenser") + reading(run, "charge_heat");
        assertEquals(duty, ledger, 1e-6 * Math.max(1, Math.abs(reading(run, "duty"))), "the law's ledger over the cycle");
    }

    @Test void aPotStillCutsANaphthaLightFirstAndCloses() {
        var run = closes(naphthaStill("{}"));
        ledger(run);
        assertTrue(run.streams().get("cut/1").total() > 0 && run.streams().get("cut/2").total() > 0, "both cuts take some");
        assertTrue(run.streams().get("residue").total() > 0, "the second cut ends at its head's 120 °C, before the pot is dry");
        double first = pentanes(run, "cut/1"), second = pentanes(run, "cut/2"), residue = pentanes(run, "residue");
        assertTrue(first > second && second > residue, "the lighter boils first: " + first + ", " + second + ", " + residue);
        double batch = reading(run, "batch");
        assertTrue(batch > 0 && batch <= reading(run, "cycle"), "the default pot keeps up with its cycle: " + batch);
        assertFalse(noted(run, "still.cycle"));
        assertTrue(reading(run, "fill") > .4 && reading(run, "fill") < .7, "a charge a little over half the pot: " + reading(run, "fill"));
        assertTrue(reading(run, "condensed") > 0 && reading(run, "condensed") < 1 && noted(run, "still.cooling"),
                "a pentane-rich head on 25 °C water condenses in part, the rest vents: " + reading(run, "condensed"));
        assertTrue(reading(run, "pot_end") > 300, "the residue leaves hotter than the charge came");
        assertTrue(reading(run, "duty") > 0 && reading(run, "condenser") > 0);
    }

    @Test void theBatchsCurveRidesOnTheReport() {
        var run = naphthaStill("{}");
        var series = run.reports().get("B").series().get(com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit.SERIES);
        assertNotNull(series);
        assertTrue(series.size() > 10, "a batch is many steps: " + series.size());
        assertTrue(series.size() <= com.wormzjl.icheme.science.flowsheet.unit.StillBatch.MOST_POINTS + 1);
        double[] time = series.time(), pot = series.channel("pot"), head = series.channel("head"), cut = series.channel("cut");
        assertEquals(0, time[0]);
        assertEquals(reading(run, "batch"), time[time.length - 1], 1e-9);
        assertEquals(reading(run, "pot_end"), pot[pot.length - 1], 1e-9);
        assertTrue(Double.isNaN(head[0]) && Double.isNaN(cut[0]), "no head and no cut while the pot warms");
        for (int k = 1; k < pot.length; k++) assertTrue(pot[k] >= pot[k - 1] - 1e-6, "a pot's boiling point only rises as its lights go");
        assertNotNull(series.channel("rate"));
        assertNotNull(series.channel("x:Isopentane"), "the distillate's main components: " + series.channels().keySet());
        assertTrue(series.channels().keySet().stream().filter(k -> k.startsWith("x:")).count() <= 5);
        assertThrows(IllegalArgumentException.class, () -> new Report.Series(new double[]{0, 1}, Map.of("pot", new double[]{300})));
        assertThrows(IllegalArgumentException.class, () -> new Report.Series(new double[]{1, 0}, Map.of()));
    }

    /** A cut on the clock ends at its time: no step of the first cut ends after it, and the second's begin there. */
    @Test void aTimeCutEndsOnTheClock() {
        double batch = reading(naphthaStill("{}"), "batch"), at = Math.round(.4 * batch);
        var run = closes(naphthaStill("{\"cuts\":[{\"id\":\"1\",\"end\":\"time\",\"value\":" + at + "},{\"id\":\"2\",\"end\":\"head\",\"value\":393.15}]}"));
        var series = run.reports().get("B").series().get("batch");
        double[] time = series.time(), cut = series.channel("cut");
        boolean second = false;
        for (int k = 0; k < time.length; k++) {
            if (cut[k] == 1) assertTrue(time[k] <= at + 1e-6, "cut 1 at " + time[k]);
            if (cut[k] == 2) {
                second = true;
                assertTrue(time[k] > at - 1e-6, "cut 2 at " + time[k]);
            }
        }
        assertTrue(second, "the second cut ran");
    }

    /** A column of four stages at reflux 2 sends a purer first cut than the bare pot, on the same head temperature. */
    /** The head the worm tub was sized for: at its design water it takes most of the boil-up. */
    @Test void aHeavierNaphthaCondensesInTheWorm() {
        var run = closes(heavyStill(""));
        ledger(run);
        assertTrue(reading(run, "condensed") > .5 && reading(run, "condensed") <= 1, "the worm tub at its design water takes most of the boil-up: " + reading(run, "condensed"));
        assertTrue(sixes(run, "cut/1") > sixes(run, "cut/2"), "the C6 cut first");
    }

    @Test void aColumnSharpensTheFirstCut() {
        double pot = sixes(heavyStill(""), "cut/1");
        var run = closes(heavyStill("\"stages\":4,\"reflux\":2"));
        ledger(run);
        double column = sixes(run, "cut/1");
        assertTrue(column > pot, "the column's first cut " + column + " against the pot's " + pot);
        assertTrue(reading(run, "f_factor") > 0, "a column reads its F-factor");
    }

    /** A charge over its boiling point flashes as it enters: let down to the bubble point, the heat it gives off booked to the still. */
    @Test void aHotChargeIsLetDownAndStillCloses() {
        var run = closes(still("{}", feed(390, 6e5, NAPHTHA, FLOW)));
        ledger(run);
        assertTrue(noted(run, "still.hot_charge"));
        assertTrue(reading(run, "charge_heat") < 0, "the let-down gives heat off: " + reading(run, "charge_heat"));
    }

    /**
     * W4: a naphtha holding its dissolved water (1e-4 by mole, under its solubility) is one liquid: the sheet's still boils it on
     * the column's basis, water in it, and refuses nothing. Water's volatility in the oil is high, so nearly all of it leaves with
     * the first cut, and what entered left. (A wash cannot reach a sheet yet: the bundled network package holds no ethanol, and a
     * network preset refuses a package whose water is a cubic component.)
     */
    @Test void aNaphthaWithItsDissolvedWaterIsOneLiquidAndBoils() {
        var wet = new LinkedHashMap<>(NAPHTHA);
        wet.put("Water", 1e-4);
        var run = closes(still("{}", feed(300, 1.5e5, wet, FLOW)));
        ledger(run);
        assertFalse(noted(run, "still.water"));
        assertFalse(noted(run, "still.settles"));
        int w = AXIS.indexOf("Water");
        double fed = run.streams().get("in").amounts()[w], first = run.streams().get("cut/1").amounts()[w], residue = run.streams().get("residue").amounts()[w];
        System.out.printf(java.util.Locale.ROOT, "W4 wet naphtha on the sheet: water fed %.3e, first cut %.4f of it, residue %.2e of it%n", fed, first / fed, residue / fed);
        assertTrue(fed > 0);
        assertTrue(first > .5 * fed, "the dissolved water leaves early, with the light cut: " + first / fed);
        assertTrue(residue < .01 * fed, "the residue keeps almost none of it: " + residue / fed);
    }

    /** What the pot takes no part of (free water under an oil), and what it cannot hold, it refuses whole. */
    @Test void thePotRefusesWaterAndMoreThanItHolds() {
        var wet = new LinkedHashMap<>(NAPHTHA);
        wet.put("Water", .05);
        var water = still("{}", feed(300, 1.5e5, wet, FLOW));
        assertEquals(FlowsheetRun.Status.FAILED, water.status());
        assertTrue(noted(water, "still.water"), () -> "log " + water.log());
        var over = still("{}", feed(300, 1.5e5, NAPHTHA, 10 * FLOW));
        assertEquals(FlowsheetRun.Status.FAILED, over.status());
        assertTrue(noted(over, "still.overfull"), () -> "log " + over.log());
    }

    /** Without its own condenser the still sends its vapour on: a cut is the head's vapour, and there is no vent. */
    @Test void withoutACondenserTheCutsAreVapour() {
        var run = closes(naphthaStill("{\"condenser\":\"none\"}"));
        ledger(run);
        assertFalse(run.streams().containsKey("vent"));
        assertEquals(0, reading(run, "condenser"), 1e-9);
        var cut = run.streams().get("cut/1");
        assertTrue(cut.state() != null && Arrays.stream(TestPhases.vapor(cut.state())).sum() > .99 * cut.total(), "the head's vapour leaves as vapour");
        assertTrue(Arrays.stream(run.streams().get("residue").amounts()).sum() > 0);
    }
}
