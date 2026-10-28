package com.wormzjl.icheme.client.gui.screens.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;

class V3ColumnInputDraftTest {
    private static final List<String> SCALARS =
            List.of("360", "365", "24", "12", "29", "8", "2", "1.5", "0.75", "8");

    @Test
    void assembledInputCarriesTheAuthoredCoolersWithTheirSplits() {
        V3ColumnInput assembled = V3EditorDraft.ColumnInputDraft.assemble(base(), SCALARS, blankDraws(), blankSteam(), blankSteam(),
                List.of(
                        new V3EditorDraft.PumparoundDraft.Row("12", "9", "2.5", V3PumparoundSpec.Split.RETURN_TRAY),
                        new V3EditorDraft.PumparoundDraft.Row("20", "16", "5", V3PumparoundSpec.Split.UNIFORM),
                        new V3EditorDraft.PumparoundDraft.Row("", "", "", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals(List.of(
                new V3PumparoundSpec(9, 12, -2.5e6, V3PumparoundSpec.Split.RETURN_TRAY),
                new V3PumparoundSpec(16, 20, -5.0e6, V3PumparoundSpec.Split.UNIFORM)), assembled.pumparounds());
        assertEquals(24, assembled.stageCount());
        assertEquals(12, assembled.feedStageNumber());
        assertEquals(100.0, totalFeed(assembled), 1.0e-9);
        assertEquals(8.0, assembled.columnDiameterMetres(), 1.0e-12);
    }

    @Test
    void emptyCoolerRowsKeepTheDryPumparoundContract() {
        V3ColumnInput assembled = V3EditorDraft.ColumnInputDraft.assemble(base(), SCALARS, blankDraws(), blankSteam(), blankSteam(),
                List.of(new V3EditorDraft.PumparoundDraft.Row("", "", "", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals(List.of(), assembled.pumparounds());
    }

    @Test
    void coolerRowsAreAssembledAlongsideSideDrawsAndReportTheirOwnDiagnostics() {
        V3ColumnInput assembled = V3EditorDraft.ColumnInputDraft.assemble(base(), SCALARS,
                List.of(new V3EditorDraft.SideDrawDraft.Row("18", "36"), new V3EditorDraft.SideDrawDraft.Row("", ""),
                        new V3EditorDraft.SideDrawDraft.Row("", "")),
                blankSteam(), blankSteam(),
                List.of(new V3EditorDraft.PumparoundDraft.Row("20", "16", "5", V3PumparoundSpec.Split.UNIFORM)));
        assertEquals(List.of(new V3SideDrawSpec(18, 10.0)), assembled.sideDraws());
        assertEquals(1, assembled.pumparounds().size());
        assertEquals("Heat: Cooler 1 cooling must be a positive number of MW",
                assertThrows(IllegalArgumentException.class, () -> V3EditorDraft.ColumnInputDraft.assemble(base(), SCALARS,
                        blankDraws(), blankSteam(), blankSteam(),
                        List.of(new V3EditorDraft.PumparoundDraft.Row("20", "16", "-5", V3PumparoundSpec.Split.UNIFORM))))
                        .getMessage());
    }

    private static double totalFeed(V3ColumnInput input) {
        double total = 0.0;
        for (double flow : input.feedComponentMolarFlowsMolPerSecond()) total += flow;
        return total;
    }

    private static List<V3EditorDraft.SideDrawDraft.Row> blankDraws() {
        return List.of(new V3EditorDraft.SideDrawDraft.Row("", ""), new V3EditorDraft.SideDrawDraft.Row("", ""), new V3EditorDraft.SideDrawDraft.Row("", ""));
    }

    private static V3EditorDraft.SteamFeedDraft.Row blankSteam() {
        return new V3EditorDraft.SteamFeedDraft.Row("", "", "");
    }

    private static V3ColumnInput base() {
        return com.wormzjl.icheme.science.column.v3.V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, "test:gui", "test:draft",
                new V3ComponentBasis(List.of("a", "b")), new double[] {4.0, 6.0}, 450.0, 24, 12, 250_000.0, 750.0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }

    // Merged from V3SideDrawDraftTest.
    @Test
    void blankAndZeroRatesDisableRowsWithoutRequiringATray() {
        assertEquals(List.of(), V3EditorDraft.SideDrawDraft.parse(List.of(row("", ""), row("invalid", "0"), row("-1", "-0")), 4, 100));
        assertEquals(List.of(new V3SideDrawSpec(2, 10)), V3EditorDraft.SideDrawDraft.parse(List.of(row(" 2 ", " 36 ")), 4, 100));
    }

    @Test
    void rejectsInvalidOrDuplicateTraysNonfiniteRatesAndExcessiveTotal() {
        for (String tray : List.of("0", "5", "2.5", "")) {
            assertThrows(IllegalArgumentException.class, () -> V3EditorDraft.SideDrawDraft.parse(List.of(row(tray, "1")), 4, 100));
        }
        for (String rate : List.of("NaN", "Infinity", "-1", "360")) {
            assertThrows(IllegalArgumentException.class, () -> V3EditorDraft.SideDrawDraft.parse(List.of(row("1", rate)), 4, 100));
        }
        assertThrows(IllegalArgumentException.class, () -> V3EditorDraft.SideDrawDraft.parse(List.of(row("2", "1"), row("2", "2")), 4, 100));
    }

    private static V3EditorDraft.SideDrawDraft.Row row(String tray, String rate) { return new V3EditorDraft.SideDrawDraft.Row(tray, rate); }

    // The drawing's tray layout moved to CalculatorColumn (batch 2026-10-28-gui-toolkit): its gate is
    // CalculatorColumnTest.theDrawingFitsLargeGlassesAndScrollsSmallOnesWithTraysClickable.

    // Merged from V3ColumnScalarDraftTest.
    private static final List<String> VALID = List.of(
            "2610.7", "365", "30", "24", "126.85", "8", "2", "2.5", "0.75", "8");

    @Test
    void parsesUnitsIndependentlyOfSideDrawRows() {
        V3EditorDraft.ColumnScalarDraft.Values values = V3EditorDraft.ColumnScalarDraft.parse(VALID);
        assertEquals(2610.7 / 3.6, values.feedMolPerSecond(), 1e-12);
        assertEquals(638.15, values.feedTemperatureKelvin(), 1e-12);
        assertEquals(30, values.stageCount());
        assertEquals(24, values.feedStage());
        assertEquals(400.0, values.condenserTemperatureKelvin(), 1e-12);
        assertEquals(8_000_000, values.reboilerDutyWatts());
        assertEquals(250_000, values.topPressurePascal());
        assertEquals(750, values.pressureDropPascal());
        assertEquals(8.0, values.columnDiameterMetres(), 1e-12);
    }

    @Test
    void identifiesTheInvalidScalarInsteadOfBlamingDraws() {
        assertInvalid(1, "-274", "Feed temperature");
        assertInvalid(2, "30.5", "Stage count");
        assertInvalid(3, "32", "Feed stage");
        assertInvalid(6, "NaN", "Reflux ratio");
        assertInvalid(6, "-0.01", "Reflux ratio");
        assertInvalid(7, "0", "Top pressure");
        assertInvalid(9, "-1", "Column diameter");
        assertInvalid(9, "0", "Column diameter");
        assertInvalid(9, "16", "Column diameter");
    }

    @Test
    void acceptsTrainingRangeEndpointsIncludingExactZeroRefluxAndPressureDrop() {
        for (int stages : List.of(2, 64)) {
            for (double reflux : List.of(0.0, 10.0)) {
                List<String> fields = new ArrayList<>(VALID);
                fields.set(2, Integer.toString(stages));
                fields.set(3, Integer.toString(stages));
                fields.set(6, Double.toString(reflux));
                fields.set(7, stages == 2 ? "1" : "3");
                fields.set(8, "0");
                fields.set(9, stages == 2 ? "0.1" : "15");
                V3EditorDraft.ColumnScalarDraft.Values values = V3EditorDraft.ColumnScalarDraft.parse(fields);
                assertEquals(stages, values.stageCount());
                assertEquals(reflux, values.refluxRatio());
                assertEquals(stages == 2 ? 100_000.0 : 300_000.0, values.topPressurePascal());
                assertEquals(0.0, values.pressureDropPascal());
                assertEquals(stages == 2 ? 0.1 : 15.0, values.columnDiameterMetres());
            }
        }
    }

    private static void assertInvalid(int index, String value, String expectedField) {
        List<String> fields = new ArrayList<>(VALID);
        fields.set(index, value);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> V3EditorDraft.ColumnScalarDraft.parse(fields));
        assertTrue(failure.getMessage().contains(expectedField), failure::getMessage);
        assertFalse(failure.getMessage().contains("draw"), failure::getMessage);
    }

    // Merged from V3SteamFeedDraftTest.
    @Test
    void blankAndZeroRatesDisableSteamGroups() {
        assertEquals(List.of(), V3EditorDraft.SteamFeedDraft.parse(steamRow("", "", ""), steamRow("invalid", "0", ""), 4, 100));
        assertEquals(List.of(new V3SteamFeedSpec(5, 10.0, 450.0)),
                V3EditorDraft.SteamFeedDraft.parse(steamRow("", "36", "176.85"), steamRow("", "", ""), 4, 100));
    }

    @Test
    void rejectsIncompleteAndOutOfRangeTraySteam() {
        assertThrows(IllegalArgumentException.class,
                () -> V3EditorDraft.SteamFeedDraft.parse(steamRow("", "1", ""), steamRow("", "", ""), 4, 100));
        assertThrows(IllegalArgumentException.class,
                () -> V3EditorDraft.SteamFeedDraft.parse(steamRow("", "", ""), steamRow("5", "1", "200"), 4, 100));
        assertThrows(IllegalArgumentException.class,
                () -> V3EditorDraft.SteamFeedDraft.parse(steamRow("", "", ""), steamRow("2", "Infinity", "200"), 4, 100));
    }

    private static V3EditorDraft.SteamFeedDraft.Row steamRow(String stage, String rate, String temperature) {
        return new V3EditorDraft.SteamFeedDraft.Row(stage, rate, temperature);
    }
}
