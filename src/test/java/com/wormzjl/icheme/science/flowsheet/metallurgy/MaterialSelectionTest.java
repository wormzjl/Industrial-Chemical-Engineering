package com.wormzjl.icheme.science.flowsheet.metallurgy;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.integrity.CorrosionDigest;
import com.wormzjl.icheme.science.integrity.CorrosionLaw;
import com.wormzjl.icheme.science.integrity.CorrosionStandard;
import com.wormzjl.icheme.science.integrity.CorrosionStandards;
import com.wormzjl.icheme.science.integrity.Mechanism;
import com.wormzjl.icheme.science.integrity.Metallurgy;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Materials tab's science (batch 2026-11-16-fouling-corrosion, WP6): each material rated on a unit's streams by the world's laws,
 * the worst stream governing; Auto the cheapest green material, else the least rate; a control valve's trim life at its drop; the
 * player's choice kept in the sheet file by name; Send to block's designs of the chosen material; and a solved example sheet rated.
 */
class MaterialSelectionTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final FluidThermodynamics MODEL = MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK));
    private static final CorrosionStandard STANDARD = CorrosionStandards.of(CATALOG, NETWORK, MODEL.components(), MODEL.molecularWeights()).apply(MODEL.components());

    /** The network's sulfur shares on the model's list, as the standard reads them. */
    private static int cut(double from, double to) {
        for (int i = 0; i < STANDARD.size(); i++) if (STANDARD.sulfur(i) >= from && STANDARD.sulfur(i) <= to) return i;
        throw new AssertionError("no compound with sulfur " + from + " to " + to + " wt%");
    }

    /** One mole of compound {@code i}, all liquid oil, at {@code celsius} and 5 bar. */
    private static CorrosionDigest oil(int i, double celsius) {
        var moles = new double[STANDARD.size()];
        moles[i] = 1;
        return STANDARD.digest(moles, celsius + 273.15, 5e5, moles, null);
    }

    private static SheetMetallurgy.StreamService stream(CorrosionDigest d) {
        return new SheetMetallurgy.StreamService("s", d, 0, 0);
    }

    /** The cheapest material whose inside rate the law gives at or below the green bound, by the law itself. */
    private static Metallurgy cheapestGreen(CorrosionDigest d) {
        Metallurgy best = null;
        for (var m : Metallurgy.values())
            if (CorrosionLaw.inside(m, d).rate() <= SheetMetallurgy.GREEN && (best == null || m.cost() < best.cost())) best = m;
        return best;
    }

    @Test
    void aHotSourCrudeRatesCarbonSteelRedBySulfidationAndAutoTakesTheCheapestGreen() {
        int heavy = cut(2, 5);
        var cool = oil(heavy, 240);
        var hot = oil(heavy, 370);
        assertTrue(hot.sulfur() >= 2, "a heavy cut's sulfur from the catalog: " + hot.sulfur());
        var steel = SheetMetallurgy.rate(Metallurgy.CARBON_STEEL, List.of(stream(hot)), false);
        assertEquals(Mechanism.SULFIDATION, steel.mechanism());
        assertEquals(CorrosionLaw.inside(Metallurgy.CARBON_STEEL, hot).rate(), steel.rate(), 1e-12, "the law's own rate");
        assertEquals(Lamp.RED, steel.lamp());
        var ratings = SheetMetallurgy.ratings(List.of(stream(hot)), false);
        assertEquals(cheapestGreen(hot), SheetMetallurgy.auto(ratings));
        assertNotEquals(Metallurgy.CARBON_STEEL, SheetMetallurgy.auto(ratings));
        // Below the onset nothing acts on the same oil.
        assertEquals(Metallurgy.CARBON_STEEL, SheetMetallurgy.auto(SheetMetallurgy.ratings(List.of(stream(cool)), false)));
        // Red from about 260 °C upwards on the heavy cut: 300 °C is not green, 225 °C is.
        assertNotEquals(Lamp.GREEN, SheetMetallurgy.rate(Metallurgy.CARBON_STEEL, List.of(stream(oil(heavy, 300))), false).lamp());
        assertEquals(Lamp.GREEN, SheetMetallurgy.rate(Metallurgy.CARBON_STEEL, List.of(stream(oil(heavy, 225))), false).lamp());
    }

    @Test
    void aCleanLightStreamKeepsCarbonSteel() {
        var light = oil(cut(0, .05), 120);
        var ratings = SheetMetallurgy.ratings(List.of(stream(light)), false);
        assertEquals(Lamp.GREEN, ratings.get(Metallurgy.CARBON_STEEL.ordinal()).lamp());
        assertEquals(Metallurgy.CARBON_STEEL, SheetMetallurgy.auto(ratings));
        assertEquals(Double.POSITIVE_INFINITY, ratings.get(0).life(), "no rate: no end");
    }

    @Test
    void theWorstStreamOfAUnitGoverns() {
        var light = oil(cut(0, .05), 120);
        var hot = oil(cut(2, 5), 370);
        for (var m : Metallurgy.values()) {
            var both = SheetMetallurgy.rate(m, List.of(stream(light), stream(hot)), false);
            assertEquals(CorrosionLaw.inside(m, hot).rate(), both.rate(), 1e-12, m.name());
            assertEquals(CorrosionLaw.inside(m, hot).mechanism(), both.mechanism(), m.name());
            assertEquals(both.rate(), SheetMetallurgy.rate(m, List.of(stream(hot), stream(light)), false).rate(), 0, "order does not matter");
        }
        // A life is the allowance over the rate, at 20 process hours a year.
        var steel = SheetMetallurgy.rate(Metallurgy.CARBON_STEEL, List.of(stream(hot)), false);
        assertEquals(3 / steel.rate() * 20, steel.life(), 1e-9);
    }

    @Test
    void hotHydrogenAboveTheCarbonSteelLimitMovesAutoOffCarbonSteel() {
        var hydrogen = new CorrosionDigest(300 + 273.15, false, false, 0, 0, 0, 0, 0, 0, 0, 30, 0);
        var ratings = SheetMetallurgy.ratings(List.of(stream(hydrogen)), false);
        assertEquals(Mechanism.HYDROGEN_ATTACK, ratings.get(Metallurgy.CARBON_STEEL.ordinal()).mechanism());
        assertEquals(CorrosionLaw.hydrogenAttack(Metallurgy.CARBON_STEEL, hydrogen.temperature(), 30), ratings.get(0).rate(), 1e-12);
        var auto = SheetMetallurgy.auto(ratings);
        assertNotEquals(Metallurgy.CARBON_STEEL, auto);
        assertEquals(cheapestGreen(hydrogen), auto);
    }

    @Test
    void whereNoMaterialIsGreenAutoTakesTheLeastRateTheCheaperOnATie() {
        var low = new SheetMetallurgy.Rated(Metallurgy.NICKEL_ALLOY, Mechanism.EROSION, .3, 1, Lamp.YELLOW);
        var same = new SheetMetallurgy.Rated(Metallurgy.STAINLESS_STEEL, Mechanism.EROSION, .3, 1, Lamp.YELLOW);
        var high = new SheetMetallurgy.Rated(Metallurgy.CARBON_STEEL, Mechanism.EROSION, 2, 1, Lamp.RED);
        assertEquals(Metallurgy.STAINLESS_STEEL, SheetMetallurgy.auto(List.of(high, low, same)));
        assertEquals(Lamp.GREEN, SheetMetallurgy.lamp(.1));
        assertEquals(Lamp.YELLOW, SheetMetallurgy.lamp(.5));
        assertEquals(Lamp.RED, SheetMetallurgy.lamp(.51));
    }

    @Test
    void aHeatSideErodesAtItsLineSpeedWithSolids() {
        var clean = oil(cut(0, .05), 60);
        var slurry = new SheetMetallurgy.StreamService("s", clean, .01, 5);
        var eroded = SheetMetallurgy.rate(Metallurgy.CARBON_STEEL, List.of(slurry), true);
        assertEquals(Mechanism.EROSION, eroded.mechanism());
        assertEquals(.16, eroded.rate(), .01, "plan section 8: 1 wt% at 5 m/s");
        assertEquals(0, SheetMetallurgy.rate(Metallurgy.CARBON_STEEL, List.of(slurry), false).rate(), "a vessel does not erode");
    }

    @Test
    void aSlurryThroughAControlValveGivesThePlansTrimLives() {
        assertEquals(105, SheetMetallurgy.trim(1e5, 800, .01).life(), 105 * .05);
        assertEquals(13, SheetMetallurgy.trim(5e5, 800, .01).life(), 13 * .05);
        assertEquals(16, SheetMetallurgy.trim(1e5, 800, .01).speed(), .5);
        assertEquals(Double.POSITIVE_INFINITY, SheetMetallurgy.trim(1e5, 800, 0).life(), "no solids: no wear");
    }

    @Test
    void theYearIsTheServersDefault() {
        assertEquals(com.wormzjl.icheme.runtime.fluid.Integrity.Settings.DEFAULT.yearProcessHours(), SheetMetallurgy.YEAR_PROCESS_HOURS);
    }

    // ---- The sheet ----

    private static Flowsheet example() throws IOException {
        try (var in = MaterialSelectionTest.class.getResourceAsStream("/assets/icheme/flowsheet_examples/small_crude_unit.json")) {
            return FlowsheetFile.read(in.readAllBytes());
        }
    }

    @Test
    void aManualChoiceIsKeptSavedAndReadBack() throws IOException {
        var sheet = example();
        String id = sheet.units().getFirst().id();
        var facts = sheet.sizing().with(id, sheet.sizing().unit(id).withMaterial(Metallurgy.STAINLESS_STEEL));
        var read = FlowsheetFile.read(FlowsheetFile.write(sheet.with(facts)));
        assertEquals(Metallurgy.STAINLESS_STEEL, read.sizing().unit(id).material());
        assertEquals(SizingFacts.Mode.AUTO, read.sizing().unit(id).mode(), "the sizing mode stays apart");
        // Auto again removes the row: nothing is kept.
        var auto = read.sizing().with(id, read.sizing().unit(id).withMaterial(null));
        assertFalse(auto.units().containsKey(id));
        assertEquals(2, FlowsheetFile.FORMAT, "a new field needs no new number: an older reader keeps it as a later version's");
    }

    @Test
    void anUnknownMaterialNameReadsAsAutoAndStaysInTheFile() throws IOException {
        var sheet = example();
        String id = sheet.units().getFirst().id();
        var facts = sheet.sizing().with(id, sheet.sizing().unit(id).withMaterial(Metallurgy.NICKEL_ALLOY));
        String text = new String(FlowsheetFile.write(sheet.with(facts)), StandardCharsets.UTF_8).replace("\"NICKEL_ALLOY\"", "\"UNOBTAINIUM\"");
        var read = FlowsheetFile.read(text.getBytes(StandardCharsets.UTF_8));
        assertNull(read.sizing().unit(id).material(), "an unknown name is Auto");
        String again = new String(FlowsheetFile.write(read), StandardCharsets.UTF_8);
        assertTrue(again.contains("\"UNOBTAINIUM\""), "kept as a later version's");
        // A choice made over it replaces it.
        var chosen = read.sizing().with(id, read.sizing().unit(id).withMaterial(Metallurgy.CHROME_STEEL));
        String replaced = new String(FlowsheetFile.write(read.with(chosen)), StandardCharsets.UTF_8);
        assertFalse(replaced.contains("UNOBTAINIUM"));
        assertEquals(Metallurgy.CHROME_STEEL, FlowsheetFile.read(replaced.getBytes(StandardCharsets.UTF_8)).sizing().unit(id).material());
        // A value that is no name is Auto too, and does not refuse the file.
        String number = text.replace("\"UNOBTAINIUM\"", "7");
        assertNull(FlowsheetFile.read(number.getBytes(StandardCharsets.UTF_8)).sizing().unit(id).material());
        assertNotNull(JsonParser.parseString(again));
    }

    @Test
    void sendToBlockWritesTheMaterialIntoTheDesign() throws IOException {
        var sheet = example();
        String id = sheet.units().getFirst().id();
        var drum = new DrumSizing.Size(UnitType.STANDING_DRUM, 2, 4, false);
        var sized = new SheetSizing.Sized(Map.of(), Map.of(id, new SheetSizing.SizedUnit(drum, Rating.NONE)), List.of());
        var sent = SheetSizing.designs(sheet, sized, unit -> Metallurgy.STAINLESS_STEEL);
        assertEquals(1, sent.size());
        assertEquals(Metallurgy.STAINLESS_STEEL, sent.getFirst().shape().material());
        // Without a choice the two-argument form builds the player's Manual choice, else carbon steel.
        assertEquals(Metallurgy.CARBON_STEEL, SheetSizing.designs(sheet, sized).getFirst().shape().material());
        var manual = sheet.with(sheet.sizing().with(id, SizingFacts.UnitFacts.AUTO.withMaterial(Metallurgy.CHROME_STEEL)));
        assertEquals(Metallurgy.CHROME_STEEL, SheetSizing.designs(manual, sized).getFirst().shape().material());
    }

    @Test
    void aSolvedExampleSheetRatesEveryWalledUnit() throws IOException {
        var sheet = example();
        var run = FlowsheetEngine.run(sheet, CATALOG, () -> {
        }, null, FlowsheetSolver.Progress.NONE);
        assertEquals(FlowsheetRun.Status.STEADY, run.status());
        var rows = MaterialRuntime.with(CATALOG, NETWORK, () -> SheetMetallurgy.rate(sheet, run, new FlowThermo(MODEL, CATALOG, NETWORK, () -> {
        }), null));
        assertFalse(rows.rows().isEmpty());
        for (var unit : sheet.units()) {
            var row = rows.rows().get(unit.id());
            if (SheetMetallurgy.WALLED.contains(unit.type())) {
                assertNotNull(row, unit.id() + " " + unit.type() + " has a row");
                assertEquals(Metallurgy.values().length, row.ratings().size());
                var auto = row.rated(row.auto());
                for (var r : row.ratings())
                    assertTrue(auto.lamp() == Lamp.GREEN ? r.lamp() != Lamp.GREEN || r.material().cost() >= auto.material().cost() : r.rate() >= auto.rate(),
                            unit.id() + ": Auto is the cheapest green, else the least rate");
            } else if (!unit.type().equals(SheetMetallurgy.VALVE)) assertNull(row, unit.id() + " " + unit.type() + " has no wall");
            else if (row != null) assertFalse(row.wall(), "a valve rates its trim");
        }
        assertEquals(SheetMetallurgy.Rows.NONE, SheetMetallurgy.rate(sheet, FlowsheetRun.none(), new FlowThermo(MODEL, CATALOG, NETWORK, () -> {
        }), null), "no steady run, no rows");
    }
}
