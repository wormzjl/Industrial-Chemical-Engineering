package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Utility regulation on the sample (batch 2026-11-02-process-simulator, {@code COOLER_MODEL_PLAN.md} section 8, U1), a slow gate: the
 * column's condenser sets its cooling water's flow from the duty and the water's inlet, a kettle its steam's, and crude on a
 * pumparound cooler or stripping steam keeps its own; the air coolers' fans run at the speed their face needs, by the fan laws.
 */
class UtilityRegulationTest {
    private static final String T = "T-101";
    /** The sample's CWS as typed, mol/s: 950.8 kg/s. */
    private static final double TYPED = 190000 / 3.6;

    private static Flowsheet sheet;
    private static FlowsheetRun run;
    private static FlowThermo thermo;

    @BeforeAll
    static void runTheSample() {
        sheet = SheetFixture.sample();
        run = steady(sheet);
        thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
    }

    /**
     * The sample's receiver stands at 59 °C, so its water may leave at 54 °C, but rises its full 10 K: 30 to 40 °C. The flow is the
     * duty over that rise, and the run says T-101 set it.
     */
    @Test
    void theCondenserSetsItsWaterFromTheDuty() {
        var cws = run.streams().get("CWS");
        var cwr = run.streams().get("CWR");
        assertEquals(313.15, cwr.temperature(), 1e-3);
        double duty = cwr.enthalpy() - cws.enthalpy();
        assertEquals(run.reports().get(T).readings().get("condenser_water"), duty, 1e-6 * duty, "the water takes the condenser's duty");
        assertEquals(duty / (molar(313.15, cwr.pressure()) - cws.molarEnthalpy()), cws.total(), 1e-6 * cws.total(), "Q / Δh");
        assertTrue(Math.abs(cws.total() / TYPED - 1) > .01, "the typed flow is only the first guess");
        assertEquals(List.of("CWS"), setBy(run), "only the condenser's water is set; crude through the pumparound coolers and the stripping steam keep their own");
        assertEquals(List.of(T), notes(run, "flow.set_by").getFirst().arguments());
    }

    /** Water at 50 °C may rise only to 54 °C, 5 K under the receiver: more than twice the water. */
    @Test
    void aHotterSupplyTakesMoreWater() {
        var hot = steady(cws(sheet, 323.15));
        assertEquals(327.15, hot.streams().get("CWR").temperature(), 1e-3);
        assertTrue(hot.streams().get("CWS").mass() > 2 * run.streams().get("CWS").mass(), () -> hot.streams().get("CWS").mass() + " kg/s");
    }

    /** Less crude, less condenser duty, and the water follows it at the same rise. */
    @Test
    void aChangedDutyMovesTheWater() {
        var less = steady(crude(sheet, .9));
        double before = run.streams().get("CWR").enthalpy() - run.streams().get("CWS").enthalpy(), after = less.streams().get("CWR").enthalpy() - less.streams().get("CWS").enthalpy();
        assertTrue(after < .95 * before, () -> after + " W after " + before + " W");
        assertEquals(after / before, less.streams().get("CWS").total() / run.streams().get("CWS").total(), 1e-4);
    }

    /** Water at 55 °C cannot stay 5 K under a 59 °C receiver at any flow: it keeps its typed flow, and the run says so. */
    @Test
    void waterAtTheCeilingKeepsItsTypedFlow() {
        var warm = steady(cws(sheet, 328.15));
        assertEquals(TYPED, warm.streams().get("CWS").total(), 1e-9 * TYPED);
        assertEquals(List.of(), setBy(warm));
        assertEquals(List.of("condenser_in"), notes(warm, "column.water_hot").getFirst().arguments());
    }

    /**
     * Steam at 200 bar condenses at 365.7 °C, over the bottoms at 317 °C: it takes the kettle's 5 MW and leaves as saturated
     * condensate. At 100 bar it would condense at 311 °C, under the bottoms: it keeps its typed flow and gives its superheat alone.
     */
    @Test
    void steamLeavesAsSaturatedCondensate() {
        var high = steady(kettle(sheet, 200e5, 700));
        var hps = high.streams().get("HPS");
        var hpc = high.streams().get("HPC");
        assertEquals(5e6, hps.enthalpy() - hpc.enthalpy(), 1);
        assertEquals(0, thermo.vapourShare(hpc));
        assertEquals(hpc.pressure(), SheetFixture.FLUIDS.model().saturationPressure(hpc.temperature()), 1e-3 * hpc.pressure(), "condensate at its saturation");
        assertEquals(List.of("CWS", "HPS"), setBy(high));
        var low = steady(kettle(sheet, 100e5, 800));
        assertEquals(1500, low.streams().get("HPS").total(), 1e-9);
        assertFalse(notes(low, "column.steam_low").isEmpty());
    }

    /**
     * E-102's face, Auto's with its 20 % spare, needs its fans under full speed, whose power follows the cube of the speed; a face
     * with natural draught enough stills them. The sheet's fan power is every air cooler's.
     */
    @Test
    void theFansRunAtTheSpeedTheFaceNeeds() {
        var sized = size(sheet, run);
        var cooler = sized.units().get("E-102");
        double speed = cooler.rating().values().get(Rating.FANS), power = cooler.rating().values().get(Rating.FAN_POWER);
        var size = assertInstanceOf(AirCoolerSizing.Size.class, cooler.size());
        assertTrue(speed > 0 && speed < 1, () -> "speed " + speed);
        assertEquals(HeatSides.fanPower(size.surface(), 1) * speed * speed * speed, power, 1e-9 * power);
        assertEquals(power, sized.fanPower(), 1e-9 * power, "E-102 is the sample's one air cooler");
        var load = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> AirCoolerSizing.Load.of(run.streams().get("S-10"), run.streams().get("RESIDUE"), DesignBasis.DEFAULT, thermo, new MixtureViscosity.Workspace()));
        double[] bay = ProcessUnit.bays().getLast();
        var still = AirCoolerSizing.fans(load, new AirCoolerSizing.Size(bay[0], (int) bay[1], new ProcessUnit.Bank(ProcessUnit.Bank.MAX_PARALLEL, ProcessUnit.Bank.MAX_SERIES), com.wormzjl.icheme.science.equipment.ColumnComponents.MAX_UNITS));
        assertEquals(new AirCoolerSizing.Fans(0, 0), still);
    }

    /** CWS's line is sized on the water the condenser sets, at design flow. */
    @Test
    void lineSizingSeesTheRegulatedFlow() {
        var line = size(sheet, run).lines().get("CWS").line();
        double mass = run.streams().get("CWS").mass() * DesignBasis.DEFAULT.designFlow();
        assertEquals(mass, line.mass(), 1e-9 * mass);
    }

    private static FlowsheetRun steady(Flowsheet sheet) {
        var run = FlowsheetEngine.run(sheet, SheetFixture.CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> run.log().toString());
        return run;
    }

    private static SheetSizing.Sized size(Flowsheet sheet, FlowsheetRun run) {
        return MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(sheet, run, thermo, SheetSizing.Entries.DEFAULT));
    }

    /** Liquid water's enthalpy, J/mol, at {@code temperature} and {@code pressure}. */
    private static double molar(double temperature, double pressure) {
        double[] one = new double[thermo.axis().size()];
        one[thermo.place("Water")] = 1;
        return thermo.atTemperature(temperature, pressure, one, SolidInventory.EMPTY).enthalpy();
    }

    /** The streams whose flow a unit set, in order. */
    private static List<String> setBy(FlowsheetRun run) {
        return notes(run, "flow.set_by").stream().map(Note::subject).sorted().toList();
    }

    private static List<Note> notes(FlowsheetRun run, String key) {
        var found = new ArrayList<Note>();
        for (var report : run.reports().values()) for (var note : report.notes()) if (note.key().equals(key)) found.add(note);
        return found;
    }

    private static Flowsheet cws(Flowsheet sheet, double temperature) {
        return streams(sheet, s -> s.id().equals("CWS") ? withFeed(s, new Flowsheet.Feed(temperature, s.feed().pressure(), s.feed().amounts(), s.feed().solids(), s.feed().unknown())) : s);
    }

    private static Flowsheet crude(Flowsheet sheet, double share) {
        return streams(sheet, s -> {
            if (!s.id().equals("CRUDE")) return s;
            var amounts = new LinkedHashMap<String, Double>();
            s.feed().amounts().forEach((id, amount) -> amounts.put(id, amount * share));
            return withFeed(s, new Flowsheet.Feed(s.feed().temperature(), s.feed().pressure(), amounts, s.feed().solids(), s.feed().unknown()));
        });
    }

    /** The sample's T-101 with a kettle of 5 MW on steam at {@code pressure} Pa and {@code temperature} K, 1500 mol/s as typed. */
    private static Flowsheet kettle(Flowsheet sheet, double pressure, double temperature) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var unit : sheet.units()) {
            if (!unit.id().equals(T)) {
                units.add(unit);
                continue;
            }
            var json = unit.params().json().deepCopy();
            json.addProperty("reboiler_duty", 5e6);
            units.add(new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), new Params(json), unit.unknown()));
        }
        var streams = new ArrayList<>(sheet.streams());
        streams.add(new Flowsheet.Stream("HPS", "HPS", null, new Flowsheet.End(T, "reboiler_in"), new Flowsheet.Feed(temperature, pressure, Map.of("Water", 1500.), List.of())));
        streams.add(new Flowsheet.Stream("HPC", "HPC", new Flowsheet.End(T, "reboiler_out"), null, null));
        return new Flowsheet(sheet.basis(), units, streams, sheet.settings());
    }

    private static Flowsheet streams(Flowsheet sheet, UnaryOperator<Flowsheet.Stream> change) {
        return new Flowsheet(sheet.basis(), sheet.units(), sheet.streams().stream().map(change).toList(), sheet.settings());
    }

    private static Flowsheet.Stream withFeed(Flowsheet.Stream stream, Flowsheet.Feed feed) {
        return new Flowsheet.Stream(stream.id(), stream.name(), stream.from(), stream.to(), feed, stream.route(), stream.unknown());
    }
}
