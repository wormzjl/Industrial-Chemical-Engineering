package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.flowsheet.sizing.Proposal;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4 to 3.6 and 4),
 * a slow gate: T-101 by real solves at its trial diameters, its ten members and ten member lines against the probe's
 * {@code sizing-sample.tsv}, P-102's NPSH from the draw tray's height, Stop inside a trial, and the re-run at a new diameter.
 *
 * <p>Floods carry a tolerance of 0.5 % of flood: the column's learned start and hydraulic correction run on wall-clock budgets, which can
 * move the last digits on a loaded machine, and no gate here measures time.
 */
class ColumnSizingSampleTest {
    private static final double FLOOD = .5, ONE = .5, HUNDREDTH = .005, TENTH = .05;
    private static final String T = "T-101";

    private static Flowsheet sheet;
    private static FlowsheetRun run;
    private static FlowThermo thermo;
    private static SheetSizing.Sized sized;

    @BeforeAll
    static void sizeTheSample() {
        sheet = SheetFixture.sample();
        run = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(sheet, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status());
        thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        sized = size(sheet, run, thermo, SheetSizing.Entries.DEFAULT);
        for (var e : sized.units().entrySet()) System.out.println("UNIT\t" + e.getKey() + "\t" + e.getValue().size() + "\t" + text(e.getValue().rating()));
        for (var e : sized.lines().entrySet()) System.out.println("LINE\t" + e.getKey() + "\t" + e.getValue().line().service() + "\t" + e.getValue().size() + "\t" + text(e.getValue().rating()));
    }

    private static SheetSizing.Sized size(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, SheetSizing.Entries entries) {
        return MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(sheet, run, thermo, entries));
    }

    private static String text(Rating rating) {
        var out = new StringBuilder(rating.lamp().toString());
        for (var r : rating.readings()) out.append(String.format(Locale.ROOT, " %s=%.4g[%.4g,%.4g]", r.limit(), r.value(), r.low(), r.high()));
        return out.toString();
    }

    private static SheetSizing.SizedUnit unit(String member) {
        return sized.units().get(SheetSizing.member(T, member));
    }

    private static SheetSizing.SizedLine line(String member) {
        return sized.lines().get(SheetSizing.member(T, member));
    }

    private static double read(Rating rating, Limit limit) {
        return rating.reading(limit).value();
    }

    private static void closest(Rating rating, Limit limit) {
        assertEquals(Lamp.GREEN, rating.lamp(), () -> text(rating));
        assertSame(limit, rating.closest().limit(), () -> text(rating));
    }

    @Test
    void theColumnTakesEightMetres() {
        var column = sized.units().get(T);
        assertEquals(8.0, ((ColumnSizing.Size) column.size()).diameter());
        closest(column.rating(), Limit.COLUMN_FLOOD);
        assertEquals(71.3, read(column.rating(), Limit.COLUMN_FLOOD), FLOOD);
        assertEquals(40, read(column.rating(), Limit.COLUMN_TRAYS));
    }

    @Test
    void sevenAndAHalfMetresFloodsPastTheShould() {
        var size = ((ColumnSizing.Size) sized.units().get(T).size());
        var kept = size(sheet, run, thermo, new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(T, new SheetSizing.UnitEntry(new ColumnSizing.Size(7.5, size.layout()), null, null, false))));
        var rating = kept.units().get(T).rating();
        assertEquals(Lamp.YELLOW, rating.lamp(), () -> text(rating));
        assertEquals(80.9, read(rating, Limit.COLUMN_FLOOD), FLOOD);
        assertEquals(1, read(rating, Limit.COLUMN_SOLVES));
        // At one traffic flood goes as one over the area: 71.3 × (8 / 7.5)² is 81.1.
        assertEquals(read(sized.units().get(T).rating(), Limit.COLUMN_FLOOD) * 64 / 56.25, read(rating, Limit.COLUMN_FLOOD), 1);
    }

    /**
     * The probe's 3 × 1.25 × 4 m rested on the top tray's vapour flashed by the world at V3's temperature, 19 % of it liquid and so
     * denser; that inlet holds less heat than V3's condenser takes out. Heated back by that duty, the inlet is the gas the column
     * sends, faster through the shell. Auto enlarges shells before adding them, retaining excess surface as an advisory.
     * A kept size stands at its door's count (S3b): the probe's
     * shells on a door of three, and the same size on a door of one, which is short of surface.
     */
    @Test
    void theCondenserIsSetByItsShellDrop() {
        var condenser = unit("condenser");
        assertEquals(new ExchangerSizing.Size(false, 1, 2.25, 7, com.wormzjl.icheme.science.equipment.ExchangerType.CROSSFLOW), condenser.size(), "qualify scale-up before scaling out");
        assertEquals(Lamp.YELLOW, condenser.rating().lamp());
        assertTrue(condenser.rating().reading(Limit.EXCHANGER_DROP_GAS).met());
        assertTrue(condenser.rating().reading(Limit.EXCHANGER_DROP_LIQUID).met());
        assertTrue(condenser.rating().reading(Limit.EXCHANGER_SURFACE).met());
        assertFalse(condenser.rating().reading(Limit.EXCHANGER_OVER_NEED).met());
        assertEquals(781, condenser.rating().reading(Limit.EXCHANGER_SURFACE).low(), 8);
        assertTrue(condenser.rating().reading(Limit.EXCHANGER_SURFACE).value() >= 1.2*condenser.rating().reading(Limit.EXCHANGER_SURFACE).low());
        var single = new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(SheetSizing.member(T, "condenser"), new SheetSizing.UnitEntry(new ExchangerSizing.Size(false, 1, 2.5, 8), null, null, false)));
        var singleRating = size(sheet, run, thermo, single).units().get(SheetSizing.member(T, "condenser")).rating();
        assertFalse(singleRating.reading(Limit.EXCHANGER_DROP_GAS).met(), "the widest single shell still loses too much pressure");
        var probe = new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(SheetSizing.member(T, "condenser"), new SheetSizing.UnitEntry(new ExchangerSizing.Size(false, 3, 1.25, 4), null, null, false)));
        var three = size(door(sheet, json -> json.addProperty("condenser_units", 3)), run, thermo, probe).units().get(SheetSizing.member(T, "condenser"));
        assertEquals(new ExchangerSizing.Size(false, 3, 1.25, 4), three.size());
        var kept = three.rating();
        assertEquals(Lamp.YELLOW, kept.lamp(), () -> text(kept));
        assertTrue(read(kept, Limit.EXCHANGER_DROP_GAS) > .2);
        var one = size(sheet, run, thermo, probe).units().get(SheetSizing.member(T, "condenser"));
        assertEquals(new ExchangerSizing.Size(false, 1, 1.25, 4), one.size(), "the door's one shell");
        assertEquals(Lamp.RED, one.rating().lamp(), () -> text(one.rating()));
    }

    /** The probe held the boot as a cylinder, 7.0 min; the world's boot has its head too, and holds 7.7. The drum is the same. */
    @Test
    void theReceiversBootSetsIt() {
        var receiver = unit("receiver");
        assertEquals(new DrumSizing.Size(UnitType.LYING_DRUM, 4, 10, true), receiver.size());
        closest(receiver.rating(), Limit.DRUM_BOOT_HOLD);
        assertEquals(7.7, read(receiver.rating(), Limit.DRUM_BOOT_HOLD), TENTH);
    }

    /** What the world's flash makes of the top tray's vapour at V3's own temperature: printed for the batch's report. */
    @Test
    void theTopTraysVapourAsTheWorldFlashesIt() {
        var inspection = run.reports().get(T).display().inspection().orElseThrow();
        var top = inspection.nodes().get(1);
        var ids = inspection.input().componentBasis().componentIds();
        var axis = thermo.axis();
        double[] amounts = new double[axis.size()];
        for (int i = 0; i < ids.size(); i++) if (axis.indexOf(ids.get(i)) >= 0) amounts[axis.indexOf(ids.get(i))] += top.vaporFractions().get(i) * top.vaporMolPerSecond();
        amounts[axis.size() - 1] += top.waterVaporMolPerSecond();
        double[] flash = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            var state = thermo.atTemperature(top.temperatureKelvin(), top.pressurePascal(), amounts, com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY);
            return new double[]{state.mass(), thermo.model().vaporMass(state.state()), state.state().waterLiquid(), state.temperature()};
        });
        var overhead = line("overhead");
        System.out.printf(Locale.ROOT, "TOP\t%.2f K\tvapour %.4f of %.3f kg/s\twater liquid %.4f mol/s\toverhead line %s at %.4g kg/m3%n", flash[3], flash[1], flash[0], flash[2], overhead.line().service(), overhead.line().density());
        assertSame(Service.GAS, overhead.line().service(), "heated back by the condenser's duty, the overhead is a gas in the world too");
    }

    @Test
    void theRefluxPumpRunsNearItsTop() {
        var pump = unit("reflux pump");
        assertEquals(new PumpSizing.Size(.2), pump.size());
        closest(pump.rating(), Limit.PUMP_RATED);
        assertEquals(119, read(pump.rating(), Limit.PUMP_RATED), ONE);
    }

    /**
     * S4c: Auto holds 1.2 × the need where a size can. PA 1 and PA 2 need 222 and 223 m², so 267 m² with the spare: 4 m tubes,
     * 300 m², where the least that held the bare need was 3 m, 225 m². PA 3's 225 m² already holds 1.2 × its 117.
     */
    @Test
    void eachPumparoundTakesItsCoolerAndPump() {
        double[] needs = {224, 223, 117}, rated = {110, 93, 78}, pumps = {.25, .3, .25}, surfaces = {300, 300, 225};
        int[] tubes = {4, 4, 3};
        for (int i = 0; i < 3; i++) {
            var cooler = unit("pa " + (i + 1) + " cooler");
            assertEquals(new ExchangerSizing.Size(false, 1, 1, tubes[i]), cooler.size(), "PA " + (i + 1));
            assertEquals(surfaces[i], read(cooler.rating(), Limit.EXCHANGER_SURFACE), ONE);
            assertEquals(needs[i], cooler.rating().reading(Limit.EXCHANGER_SURFACE).low(), needs[i] * .02, "PA " + (i + 1) + ": the curves' need, within 2 % of the probe's one LMTD");
            var pump = unit("pa " + (i + 1) + " pump");
            assertEquals(new PumpSizing.Size(pumps[i]), pump.size(), "PA " + (i + 1));
            closest(pump.rating(), Limit.PUMP_RATED);
            assertEquals(rated[i], read(pump.rating(), Limit.PUMP_RATED), ONE);
        }
    }

    @Test
    void theMemberLinesTakeTheProbesBores() {
        Object[][] probe = {{"overhead", Service.GAS, .8}, {"receiver out", Service.SUCTION_BOILING, .35}, {"pump out", Service.LIQUID, .25}, {"reflux", Service.LIQUID, .2},
                {"pa 1 draw", Service.DRAW, .5}, {"pa 1 return", Service.LIQUID, .3}, {"pa 2 draw", Service.DRAW, .6}, {"pa 2 return", Service.LIQUID, .3},
                {"pa 3 draw", Service.DRAW, .45}, {"pa 3 return", Service.LIQUID, .25}};
        var wrong = new ArrayList<String>();
        for (var row : probe) {
            var line = line((String) row[0]);
            if (line.line().service() != row[1] || line.size().bore() != (double) row[2] || line.size().runs() != 1 || line.rating().lamp() != Lamp.GREEN)
                wrong.add(row[0] + ": " + line.line().service() + " " + line.size() + " " + text(line.rating()));
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
        assertEquals(31, sized.lines().size(), "21 sheet lines and 10 members");
    }

    @Test
    void p102ReadsItsDrawTraysHeight() {
        var pump = sized.units().get("P-102").rating();
        assertEquals(31.1, read(pump, Limit.PUMP_NPSH), TENTH);
        closest(pump, Limit.PUMP_RATED);
    }

    @Test
    void theBlocksMembersStandUnderIt() {
        var ids = List.copyOf(sized.units().keySet());
        int at = ids.indexOf(T);
        assertEquals(List.of("condenser", "receiver", "reflux pump", "pa 1 cooler", "pa 1 pump", "pa 2 cooler", "pa 2 pump", "pa 3 cooler", "pa 3 pump"),
                ids.subList(at + 1, at + 10).stream().map(id -> id.substring(T.length() + 1)).toList());
    }

    /** Stop reaches a trial: the sticky stop the run thread sets throws at the first checkpoint inside the column solver. */
    @Test
    void stopReachesATrial() {
        var inside = new boolean[1];
        var stopping = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
            if (StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().contains("V3ColumnCalculator")))) {
                inside[0] = true;
                throw new CancellationException();
            }
        });
        var size = (ColumnSizing.Size) sized.units().get(T).size();
        var entries = new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(T, new SheetSizing.UnitEntry(new ColumnSizing.Size(8.5, size.layout()), null, null, false)));
        assertThrows(CancellationException.class, () -> size(sheet, run, stopping, entries));
        assertTrue(inside[0]);
    }

    /**
     * No process number moves but a column's: Size all from a door at 9.0 m takes the same sizes as from 8.0 m, and its one re-run,
     * on 8.0 m and those sizes' drops, is the 8.0 m sheet's Size all run.
     */
    @Test
    void aNewDiameterMovesTheProcessOnlyThroughTheColumn() {
        var wide = door(sheet, 9);
        var wideRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(wide, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, wideRun.status());
        var resized = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.sizeAll(wide, wideRun, thermo));
        var plain = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.sizeAll(sheet, run, thermo));
        assertEquals(8.0, ((ColumnSizing.Size) resized.sized().units().get(T).size()).diameter());
        assertEquals(8.0, resized.sheet().units().stream().filter(u -> u.id().equals(T)).findFirst().orElseThrow().params().number("diameter"));
        assertEquals(plain.sheet().sizing(), resized.sheet().sizing(), "the same sizes from either door");
        assertEquals(FlowsheetRun.Status.STEADY, resized.run().status());
        for (var stream : plain.run().streams().entrySet()) {
            var again = resized.run().streams().get(stream.getKey());
            assertEquals(stream.getValue().temperature(), again.temperature(), .05, stream.getKey());
            assertEquals(stream.getValue().total(), again.total(), 1e-4 * Math.max(1, stream.getValue().total()), stream.getKey());
        }
        closest(resized.sized().units().get(T).rating(), Limit.COLUMN_FLOOD);
        assertEquals(read(plain.sized().units().get(T).rating(), Limit.COLUMN_FLOOD), read(resized.sized().units().get(T).rating(), Limit.COLUMN_FLOOD), FLOOD);
    }

    /**
     * An air-cooled condenser is S2's air cooler on the overhead; the cooling water's lines go with the exchanger. The sample's
     * overhead needs 1949 m² of bare surface at air 25 °C, more than the world's largest bank holds, 1468: one bank is red on its
     * surface, and Auto takes the fewest banks in parallel that condense it (S3b), two, each carrying half the overhead.
     */
    @Test
    void anAirCooledCondenserTakesAnAirCooler() {
        var air = door(sheet, json -> json.addProperty("condenser", "AIR_COOLER"), "CWS", "CWR");
        var airRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(air, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, airRun.status(), () -> airRun.log().toString());
        var airSized = size(air, airRun, thermo, SheetSizing.Entries.DEFAULT);
        var condenser = airSized.units().get(SheetSizing.member(T, "condenser"));
        System.out.println("AIR\t" + condenser.size() + "\t" + text(condenser.rating()));
        var banks = assertInstanceOf(AirCoolerSizing.Size.class, condenser.size());
        assertEquals(2, banks.banks());
        assertNotEquals(Lamp.RED, condenser.rating().lamp(), () -> text(condenser.rating()));
        assertEquals(1949, condenser.rating().reading(Limit.AIR_COOLER_SURFACE).low(), 10);
        var kept = size(air, airRun, thermo, new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(SheetSizing.member(T, "condenser"), new SheetSizing.UnitEntry(banks, null, null, false))))
                .units().get(SheetSizing.member(T, "condenser"));
        assertEquals(banks.withBanks(1), kept.size(), "kept on a door of one bank");
        assertEquals(Lamp.RED, kept.rating().lamp(), () -> text(kept.rating()));
        assertSame(Limit.AIR_COOLER_SURFACE, kept.rating().closest().limit(), () -> text(kept.rating()));
        // Size all writes the count to the door, which the design then asks the assembly for.
        var applied = Proposal.applied(air, airSized);
        var door = com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(applied.units().stream().filter(u -> u.id().equals(T)).findFirst().orElseThrow().params());
        assertEquals(2, door.units().condenser());
        assertNotNull(airSized.units().get(SheetSizing.member(T, "reflux pump")).size());
    }

    /**
     * The owner (C2 addendum): "it's pointless to use twin cooler config if you don't need to cool below 40C". The sample's T-101
     * held on air then water at its own 59 °C receiver gives its trim no duty in the run, so sizing proposes Air, gives the trim the
     * smallest exchanger and reads it yellow on the one Should row, never red.
     */
    @Test
    void aTwinCondenserAboveTheSplitLeavesItsTrimIdle() {
        var twin = door(sheet, json -> json.addProperty("condenser", "AIR_THEN_WATER"), "CWS", "CWR");
        var twinRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(twin, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, twinRun.status(), () -> twinRun.log().toString());
        var readings = twinRun.reports().get(T).readings();
        assertEquals(0, readings.get("condenser_water"), 1e-6 * readings.get("condenser"), "no trim duty above the split");
        var twinSized = size(twin, twinRun, thermo, SheetSizing.Entries.DEFAULT);
        assertEquals(com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser.AIR_COOLER, twinSized.condensers().get(T));
        var trim = twinSized.units().get(SheetSizing.member(T, SheetSizing.TRIM));
        System.out.println("TWIN IDLE\t" + trim.size() + "\t" + text(trim.rating()));
        assertEquals(ExchangerSizing.smallest(), trim.size());
        assertEquals(Lamp.YELLOW, trim.rating().reading(Limit.TWIN_IDLE).lamp(), () -> text(trim.rating()));
        assertEquals(Lamp.YELLOW, trim.rating().lamp(), () -> text(trim.rating()));
        var air = twinSized.units().get(SheetSizing.member(T, "condenser"));
        assertInstanceOf(AirCoolerSizing.Size.class, air.size());
        assertNull(air.rating().reading(Limit.TWIN_IDLE));
        assertNotEquals(Lamp.RED, air.rating().lamp(), () -> text(air.rating()));
    }

    /**
     * A column with reboiler duty boils its bottom tray's liquid in S2's kettle, on what its reboiler pair brings: water at 100 bar
     * and 800 K, hot enough over a crude bottom near 350 °C, as steam at 20 bar is not.
     */
    @Test
    void aReboiledColumnTakesAKettle() {
        var reboiled = door(sheet, json -> json.addProperty("reboiler_duty", 5e6));
        var streams = new ArrayList<>(reboiled.streams());
        var amounts = new java.util.LinkedHashMap<String, Double>();
        amounts.put("Water", 1500.);
        streams.add(new Flowsheet.Stream("HPS", "HPS", null, new Flowsheet.End(T, "reboiler_a"), new Flowsheet.Feed(800, 100e5, amounts, List.of())));
        streams.add(new Flowsheet.Stream("HPC", "HPC", new Flowsheet.End(T, "reboiler_b"), null, null));
        var kettled = new Flowsheet(reboiled.basis(), reboiled.units(), streams, reboiled.settings(), reboiled.sizing(), reboiled.unknown());
        var kettleRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(kettled, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, kettleRun.status(), () -> kettleRun.log().toString());
        var reboiler = size(kettled, kettleRun, thermo, SheetSizing.Entries.DEFAULT).units().get(SheetSizing.member(T, "reboiler"));
        assertInstanceOf(ExchangerSizing.Size.class, reboiler.size());
        assertTrue(((ExchangerSizing.Size) reboiler.size()).kettle());
        assertNotNull(reboiler.rating().reading(Limit.KETTLE_FLUX), () -> text(reboiler.rating()));
        System.out.println("KETTLE\t" + reboiler.size() + "\t" + text(reboiler.rating()));
        assertNotEquals(Lamp.RED, reboiler.rating().lamp(), () -> text(reboiler.rating()));
    }

    /** {@code sheet} with T-101's door at {@code diameter}. */
    private static Flowsheet door(Flowsheet sheet, double diameter) {
        return door(sheet, json -> json.addProperty("diameter", diameter));
    }

    /** {@code sheet} with T-101's door changed by {@code change}, and without the streams {@code dropped}. */
    private static Flowsheet door(Flowsheet sheet, java.util.function.Consumer<com.google.gson.JsonObject> change, String... dropped) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) {
            if (!unit.id().equals(T)) {
                units.add(unit);
                continue;
            }
            var json = unit.params().json().deepCopy();
            change.accept(json);
            units.add(new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), new Params(json), unit.unknown()));
        }
        var streams = sheet.streams().stream().filter(stream -> !List.of(dropped).contains(stream.id())).toList();
        return new Flowsheet(sheet.basis(), units, streams, sheet.settings(), sheet.sizing(), sheet.unknown());
    }
}
