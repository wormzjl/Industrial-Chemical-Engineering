package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRuns;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.Reading;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bundled "Small crude unit" (batch 2026-11-12-tutorial-system, P4, owner's choice of candidate C, variation 1;
 * {@code SMALL_COLUMN_CANDIDATES_REVIEW.md}), a slow gate: the crude unit at 1/64 on the 14-tray small tower runs steady with its
 * audit accepted, makes the barrel's shares by mass, and after Size all is short of no limit and of no 20 % spare but the known
 * line S-6. Its "larger than needed" lamps are the catalogue's smallest pump and shell at 10 m³/h, each named here: a catalogue
 * change that greens one, or a new one, fails a line here, never passes silently.
 */
class SmallCrudeUnitTest {
    /** The barrel's shares the tower was aimed at, by mass of hydrocarbon (the crude barrel recording, BarrelData), within a point. */
    private static final double OVERHEAD = .18, MIDDLE = .35, BOTTOMS = .47, POINT = .01;

    /**
     * The known real fail: S-6, H-101's outlet to V-101, two-phase at 2.7 m/s in the 0.1 m bore Size all keeps, under the 3 m/s
     * the two-phase rule asks.
     */
    private static final Set<String> SHORT = Set.of("line S-6 TWO_PHASE_SPEED");

    /** The catalogue's smallest units, larger than 10 m³/h needs: yellow, kept as they are (owner, D4: a tutorial sentence says why). */
    private static final Set<String> LARGER = Set.of(
            "P-101 PUMP_RATED",
            "T-101/condenser EXCHANGER_OVER_NEED",
            "T-101/reflux pump PUMP_RATED",
            "T-101/pa 1 cooler EXCHANGER_OVER_NEED",
            "E-101 EXCHANGER_OVER_NEED",
            "P-102 PUMP_RATED");

    private static Flowsheet sheet;
    private static FlowsheetRun run;
    private static FlowThermo thermo;
    private static SheetSizing.SizeAll all;

    @BeforeAll
    static void runAndSizeTheSmallUnit() {
        sheet = SheetFixture.example("small_crude_unit");
        run = FlowsheetRuns.run(sheet, SheetFixture.CATALOG, () -> {
        });
        thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        if (run.status() == FlowsheetRun.Status.STEADY)
            all = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> FlowsheetRuns.sizeAll(sheet, run, thermo));
    }

    @Test
    void itRunsSteadyWithItsAuditAccepted() {
        assertEquals(FlowsheetRun.Status.STEADY, run.status());
        var column = run.reports().get("T-101");
        assertNotNull(column.display(), "the small tower solved");
        assertTrue(column.display().inspection().orElseThrow().audit().accepted(), "the column's audit accepts the solve");
    }

    @Test
    void itMakesTheBarrelsSharesByMass() {
        double crude = hydrocarbon(run.streams().get("CRUDE"));
        double overhead = (hydrocarbon(run.streams().get("OFFGAS")) + hydrocarbon(run.streams().get("NAPHTHA"))) / crude;
        assertEquals(OVERHEAD, overhead, POINT, "off-gas and naphtha");
        assertEquals(MIDDLE, hydrocarbon(run.streams().get("MIDDLE")) / crude, POINT, "the middle draw");
        assertEquals(BOTTOMS, hydrocarbon(run.streams().get("RESIDUE")) / crude, POINT, "the bottoms");
    }

    @Test
    void afterSizeAllOnlyTheKnownLampsAreNotGreen() {
        assertNotNull(all, "Size all ran on a steady sheet");
        assertEquals(FlowsheetRun.Status.STEADY, all.run().status(), "the sheet stays steady on its sized drops");
        var sized = all.sized();
        var shortOf = new TreeSet<String>();
        var larger = new TreeSet<String>();
        sized.units().forEach((id, u) -> sort(id, u.rating(), shortOf, larger));
        sized.lines().forEach((id, l) -> sort("line " + id, l.rating(), shortOf, larger));
        for (var path : sized.paths())
            if (!quiet(path.rating().lamp())) shortOf.add("path " + path.start() + " -> " + path.end() + " " + path.rating().lamp());
        assertEquals(new TreeSet<>(SHORT), shortOf, "short of a limit or its 20 % spare after Size all");
        assertEquals(new TreeSet<>(LARGER), larger, "larger than needed: the catalogue's smallest size, yellow");
    }

    /**
     * P5 stage B (form A): the unit raises its own steam. BFW, water at 25 °C, leaves H-102 as the tower's steam at 260 °C, all
     * vapour: about 2,875 kJ/kg (440 to warm, 2,170 to boil, 260 to superheat at the tower foot's 2.5 to 5 bar), 0.21 MW on
     * 259 kg/h. Size all gives H-102 a vertical heater.
     */
    @Test
    void theSteamHeaterRaisesTheTowersSteamFromWater() {
        var water = run.streams().get("BFW");
        var steam = run.streams().get("STEAM");
        assertEquals(298.15, water.temperature(), 1e-9);
        assertEquals(533.15, steam.temperature(), .01, "H-102's outlet");
        assertEquals(water.mass(), steam.mass(), 1e-12);
        assertEquals(259, 3600 * steam.mass(), 1, "the tower's 259 kg/h");
        double perKilogram = (steam.enthalpy() - water.enthalpy()) / steam.mass();
        assertEquals(2.875e6, perKilogram, .03 * 2.875e6, "warm, boil and superheat");
        assertNotNull(all, "Size all ran on a steady sheet");
        var size = assertInstanceOf(com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing.Size.class, all.sized().units().get("H-102").size());
        assertEquals(com.wormzjl.icheme.science.flowsheet.sizing.UnitType.VERTICAL_HEATER, size.type());
    }

    /** Each reading off green: "larger than needed" (a pump under its rated window, surplus surface) or short of its limit. */
    private static void sort(String item, Rating rating, Set<String> shortOf, Set<String> larger) {
        for (Reading r : rating.readings()) {
            if (quiet(r.lamp())) continue;
            boolean big = r.limit() == Limit.PUMP_RATED && r.value() < r.low() || r.limit() == Limit.EXCHANGER_OVER_NEED;
            (big ? larger : shortOf).add(item + " " + r.limit().name());
        }
    }

    private static boolean quiet(Lamp lamp) {
        return lamp == Lamp.GREEN || lamp == Lamp.NONE;
    }

    /** A stream's mass of everything but water, kg/s. */
    private static double hydrocarbon(FlowStream stream) {
        double[] a = stream.amountsView();
        double mass = 0;
        for (int i = 0; i < a.length; i++) if (i != thermo.water()) mass += a[i] * SheetFixture.FLUIDS.model().molecularWeight(i);
        return mass;
    }
}
