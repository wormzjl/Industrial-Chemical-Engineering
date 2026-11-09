package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.science.column.v3.V3ColumnInspection;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In the simulator's doors every flow and pressure entry follows the display keys (D1 and D2, 2026-11-04): the number it shows, its unit and how it
 * reads what is typed. A unit's own flow stays molar, for a draw's or an outlet's makeup is known only after a run; only its time follows the key.
 * D2 was the column's top pressure shown in kPa under a fixed "bar"; D1 a splitter's typed flow read as kmol/h whatever the time key.
 */
class DoorUnitsTest {
    private static final SheetUnits KPA_PER_MINUTE = new SheetUnits(DisplayUnit.TemperatureUnit.CELSIUS, DisplayUnit.PressureUnit.KILOPASCAL, DisplayUnit.FlowRateUnit.PER_MINUTE,
            SheetUnits.Basis.MASS);

    private static SheetContext context(SheetEditor editor, SheetUnits units) {
        var texts = new SheetFixture.Texts();
        return new SheetContext(editor, new SheetState(editor.sheet(), null, null, 1.2, null), units, texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
    }

    private static @Nullable Column.Field field(List<Column.Row> rows, String key) {
        for (var row : rows) {
            if (row instanceof Column.Field f && f.key().equals(key)) return f;
            if (row instanceof Column.Group g && field(g.rows(), key) instanceof Column.Field f) return f;
        }
        return null;
    }

    private static Column.Field field(Door door, String key) {
        for (var column : door.columns()) if (field(column, key) instanceof Column.Field f) return f;
        throw new AssertionError("no entry " + key);
    }

    @Test
    void theColumnDoorsPressureAndFlowsFollowTheKeys() {
        var editor = SheetFixture.editor(SheetFixture.sample());
        var view = new Door.View();
        view.connection = CalculatorColumn.Connection.DRAW;
        for (var units : List.of(SheetUnits.DEFAULT, KPA_PER_MINUTE)) {
            var x = context(editor, units);
            var door = ColumnDoors.door(x, "T-101", view);
            var column = ColumnDoor.of(editor.unit("T-101").params());
            assertEquals(units.pressure().symbol(), field(door, "s7").unit(), "top pressure's unit");
            assertEquals(units.p(column.topPressure()), door.values().get("s7"), "top pressure's number in that unit");
            assertEquals(units.molesUnit(), field(door, "d0rate").unit(), "a draw's flow: molar, on the time key");
            assertEquals(units.moles(column.draws().getFirst().flow()), door.values().get("d0rate"));
        }
        var x = context(editor, KPA_PER_MINUTE);
        var door = ColumnDoors.door(x, "T-101", view);
        door.actions().entry("s7", "300");
        door.actions().entry("d0rate", "6");
        var column = ColumnDoor.of(editor.unit("T-101").params());
        assertEquals(3e5, column.topPressure(), 1e-6, "300 kPa typed");
        assertEquals(6 * 1000 / 60.0, column.draws().getFirst().flow(), 1e-9, "6 kmol/min typed");
    }

    /** The column door's Results glass reads on the keys as its entries do (1b, 2026-11-04): Products and Tray, flows molar on the time key. */
    @Test
    void theColumnsResultsGlassReadsOnTheKeys() {
        var editor = SheetFixture.editor(SheetFixture.sample());
        var x = context(editor, KPA_PER_MINUTE);
        assertEquals(List.of("6.0 kmol/min", "120.0 kg/min", "100.0 °C", "250.0 kPa"), values(ColumnGlass.streamPairs(x, 100, 2, 373.15, 250_000)), "Products: 100 mol/s, 2 kg/s, 373.15 K, 250 kPa");
        var heads = ColumnGlass.productHeader(x);
        assertEquals("kmol/min", heads.get(2));
        assertEquals("kg/min", heads.get(4));
        var node = new V3ColumnInspection.Node(373.15, 250_000, 100, 50, 10, 0, List.of(1.0), List.of(1.0));
        assertEquals(List.of("100.0 °C", "250.0 kPa", "0.700 kPa", "6.0 kmol/min", "3.0 kmol/min", "0.600 kmol/min", "0.0 kmol/min"), values(ColumnGlass.nodePairs(x, node, 700)), "Tray: a flow under 1 to three significant figures");
        assertEquals(List.of("100.0 °C", "2.50 bar", "0.0070 bar", "360.0 kmol/h", "180.0 kmol/h", "36.0 kmol/h", "0.0 kmol/h"), values(ColumnGlass.nodePairs(context(editor, SheetUnits.DEFAULT), node, 700)),
                "the default keys; a tray's drop two digits finer than a pressure");
        assertEquals("—", ColumnGlass.nodePairs(x, node, Double.NaN).get(2).get(1), "the condenser and the reboiler have no tray drop");
        // 1c: the Profiles view's drops and the status line's column drop go the same way.
        assertEquals("21.500 kPa", ColumnGlass.dropText(x, 21_500));
        assertEquals("0.2150 bar", ColumnGlass.dropText(context(editor, SheetUnits.DEFAULT), 21_500));
    }

    private static List<String> values(List<List<String>> pairs) {
        return pairs.stream().map(pair -> pair.get(1)).toList();
    }

    @Test
    void aSplittersTypedFlowReadsOnTheTimeKey() {
        var params = SheetEditor.defaults("splitter", SheetFixture::column);
        var sheet = new Flowsheet(SheetFixture.basis(), List.of(new Flowsheet.Unit("SP", "splitter", "SP-1", 0, 0, params)), List.of(), FlowsheetSolver.Settings.DEFAULT);
        var editor = SheetFixture.editor(sheet);
        var door = UnitDoors.door(context(editor, KPA_PER_MINUTE), "SP", new Door.View());
        door.actions().entry("flow/1", "6");
        var outlet = editor.unit("SP").params().rows("outlets").getFirst();
        assertEquals(6 * 1000 / 60.0, outlet.number("flow", Double.NaN), 1e-9, "6 kmol/min typed, not 6 kmol/h");
    }
}
