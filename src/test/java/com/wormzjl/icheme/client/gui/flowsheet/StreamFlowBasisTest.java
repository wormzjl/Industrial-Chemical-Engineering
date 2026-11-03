package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stream's flow follows the Mole, Mass and Volume display key (the owner, 2026-11-04: "The flowrate definition in stream
 * should follow the mass/mole button, and add volume to that button as well"; Volume is the actual volume, mass over the
 * density at the stream's own temperature and pressure). The panel and the feed door show and take the flow on the key's
 * basis through one path, and a feed keeps its density while only its flow changed since the run.
 */
class StreamFlowBasisTest {
    private static final Flowsheet SHEET = SheetFixture.water();
    private static final FlowsheetRun RUN = FlowsheetEngine.run(SHEET, SheetFixture.CATALOG, () -> {
    });
    private static final SheetGestures.Selection FEED = new SheetGestures.Selection(false, "FEED"), OUT = new SheetGestures.Selection(false, "OUT");

    private static SheetUnits units(SheetUnits.Basis basis) {
        return new SheetUnits(DisplayUnit.TemperatureUnit.CELSIUS, DisplayUnit.PressureUnit.BAR, DisplayUnit.FlowRateUnit.PER_HOUR, basis);
    }

    /** The sheet as the editor holds it now, on the run of {@link #SHEET} (or on none), shown on {@code basis}. */
    private static SheetContext context(SheetEditor editor, SheetUnits.Basis basis, boolean ran) {
        var texts = new SheetFixture.Texts();
        return new SheetContext(editor, new SheetState(editor.sheet(), ran ? SHEET : null, ran ? RUN : null, 1.2, null), units(basis), texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
    }

    private static @Nullable Column.Field field(List<Column.Row> rows, String key) {
        for (var row : rows) {
            if (row instanceof Column.Field f && f.key().equals(key)) return f;
            if (row instanceof Column.Group g && field(g.rows(), key) instanceof Column.Field f) return f;
        }
        return null;
    }

    private static @Nullable Column.Reading meter(List<Column.Row> rows, String key) {
        for (var row : rows) {
            if (row instanceof Column.Reading r && r.key().equals(key)) return r;
            if (row instanceof Column.Group g && meter(g.rows(), key) instanceof Column.Reading r) return r;
        }
        return null;
    }

    private static Door door(SheetContext x) {
        return StreamDoors.door(x, "FEED", new Door.View());
    }

    private static Column.Field doorFlow(SheetContext x) {
        return field(door(x).columns().getFirst(), "flow");
    }

    /** The feed's kg/mol and its density at its own temperature and pressure, as the run solved it. */
    private static double weight(SheetContext x) {
        var feed = x.editor().stream("FEED").feed();
        return x.mass(feed) / SheetContext.total(feed);
    }

    private static double density(SheetContext x) {
        return x.props(x.editor().stream("FEED")).density();
    }

    @Test
    void thePanelAndTheDoorShowEachBasisInItsUnit() {
        var editor = SheetFixture.editor(SHEET);
        var mole = context(editor, SheetUnits.Basis.MOLE, true);
        double moles = SheetContext.total(editor.stream("FEED").feed()), kg = moles * weight(mole), rho = density(mole);
        assertTrue(rho > 900 && rho < 1100, "liquid water at 30 °C and 8 bar: " + rho);
        String[] expected = {SheetUnits.number(1, moles * 3600 / 1000), SheetUnits.number(2, kg * 3600 / 1000), SheetUnits.number(1, kg / rho * 3600)};
        String[] unit = {"kmol/h", "t/h", "m³/h"};
        var out = RUN.streams().get("OUT");
        for (var basis : SheetUnits.Basis.values()) {
            var x = context(editor, basis, true);
            int i = basis.ordinal();
            var panel = SelectionColumn.of(x, FEED);
            assertEquals(expected[i], panel.values().get("flow"), basis + ": the panel's entry");
            var entry = field(panel.rows(), "flow");
            assertTrue((entry.label() + " " + entry.unit()).contains(unit[i]), basis + ": the panel's unit, " + entry);
            assertNull(entry.hint(), basis + ": a known flow has no hover");
            var door = door(x);
            assertEquals(expected[i], door.values().get("flow"), basis + ": the door's entry");
            assertTrue((doorFlow(x).label() + " " + doorFlow(x).unit()).contains(unit[i]), basis + ": the door's unit");
            assertTrue(door.status().contains(expected[i] + " " + unit[i]), basis + ": the status line, " + door.status());
            var flow = meter(SelectionColumn.of(x, OUT).rows(), "meter:flow");
            var p = x.props(editor.stream("OUT"));
            assertEquals(x.units().flow(p.moles(), p.mass(), p.density()), flow.value(), basis + ": a product's Flow meter");
            assertTrue((flow.label() + " " + flow.unit()).contains(unit[i]), basis + ": a product's unit");
            assertNull(meter(SelectionColumn.of(x, OUT).rows(), "meter:mass flow"), "no separate mass meter in the panel");
            assertTrue(out.mass() > 0);
        }
    }

    @Test
    void aTypedMassOrVolumeWritesTheMolesItMeans() {
        var editor = SheetFixture.editor(SHEET);
        var x = context(editor, SheetUnits.Basis.MASS, true);
        double weight = weight(x), rho = density(x);
        door(x).actions().entry("flow", "7.2");
        assertEquals(7.2 * 1000 / 3600 / weight, SheetContext.total(editor.stream("FEED").feed()), 1e-9, "t/h over the molar mass");
        x = context(editor, SheetUnits.Basis.VOLUME, true);
        door(x).actions().entry("flow", "3.6");
        assertEquals(3.6 / 3600 * rho / weight, SheetContext.total(editor.stream("FEED").feed()), 1e-9, "m³/h times the density over the molar mass");
        x = context(editor, SheetUnits.Basis.MOLE, true);
        StreamDoors.writeFeed(x, "FEED", "flow", "180");
        assertEquals(50, SheetContext.total(editor.stream("FEED").feed()), 1e-9, "kmol/h as typed in the panel");
    }

    @Test
    void aFlowEditKeepsTheVolumeAndATemperatureEditBlanksIt() {
        var editor = SheetFixture.editor(SHEET);
        var x = context(editor, SheetUnits.Basis.VOLUME, true);
        double rho = density(x), weight = weight(x);
        door(x).actions().entry("flow", "5.4");
        x = context(editor, SheetUnits.Basis.VOLUME, true);
        assertTrue(x.state().stale("FEED"), "the flow edit greys the feed");
        assertEquals("5.4", door(x).values().get("flow"), "the density stays the run's: it does not hang on the flow");
        assertEquals("5.4", SelectionColumn.of(x, FEED).values().get("flow"));
        assertNull(doorFlow(x).hint());
        assertEquals(5.4 / 3600 * rho / weight, SheetContext.total(editor.stream("FEED").feed()), 1e-9);

        door(x).actions().entry("temperature", "80");
        x = context(editor, SheetUnits.Basis.VOLUME, true);
        assertEquals("", door(x).values().get("flow"), "a new temperature: no density until the sheet runs");
        assertEquals("", SelectionColumn.of(x, FEED).values().get("flow"));
        String why = x.text("door.volume_unknown");
        assertEquals(why, doorFlow(x).hint(), "the hover says why");
        assertEquals(why, field(SelectionColumn.of(x, FEED).rows(), "flow").hint());
        assertTrue(door(x).status().contains("— m³/h"), door(x).status());
        double before = SheetContext.total(editor.stream("FEED").feed());
        door(x).actions().entry("flow", "9.0");
        assertEquals(before, SheetContext.total(editor.stream("FEED").feed()), "typing a volume with no density changes nothing");
        assertFalse(context(editor, SheetUnits.Basis.MASS, true).units().flow(1, 1, Double.NaN).isEmpty(), "Mass needs no density");

        var fresh = SheetFixture.editor(SHEET);
        assertEquals("", door(context(fresh, SheetUnits.Basis.VOLUME, false)).values().get("flow"), "no run, no density");
    }

    @Test
    void theKeyCyclesMoleMassVolume() {
        assertEquals(List.of(SheetUnits.Basis.MASS, SheetUnits.Basis.VOLUME, SheetUnits.Basis.MOLE), List.of(SheetUnits.Basis.MOLE.next(), SheetUnits.Basis.MASS.next(), SheetUnits.Basis.VOLUME.next()));
        var t = new SheetFixture.Texts();
        for (var basis : SheetUnits.Basis.values()) {
            // Its width in the game font is width.js's (Volume 33 px, es "Vol." 17, in the key's 34): the tests' font is wider.
            String label = SimulatorPage.softKeys(units(basis), t).getFirst().text();
            assertEquals(t.get("fluid." + basis.name().toLowerCase(java.util.Locale.ROOT) + "_basis_short"), label);
        }
    }
}
