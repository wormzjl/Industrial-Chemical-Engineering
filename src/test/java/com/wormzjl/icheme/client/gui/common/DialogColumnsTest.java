package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.equipment.Paint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The small dialogs on the GUI kit (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 15, GUI_REVIEW.md order
 * 2): every text they name has its English line; each column fits its door, at full width and narrowed for its bar,
 * with its boxes and units in line; each mode shows its keys, the current positions latched; the widgets keep their
 * slots while the warning line comes and goes; the palette fills its gap; the datasheet's glass cuts no name.
 */
class DialogColumnsTest {
    private static final Measure M = Measure.fixed(6);
    /** The door columns' widths as the screens place them (GaugeScreen, PipelineToolScreen, VesselScreen's door), and narrowed for a bar. */
    private static final int GAUGE = 226, PIPELINE = 210, VESSEL = 150, BAR = 12;

    private record Case(String name, List<Column.Row> rows, int width) {
    }

    private static DialogColumns.Gauge gauge(boolean pressure, int unit, boolean relative, boolean valid) {
        return new DialogColumns.Gauge(pressure, pressure ? List.of("Pa", "kPa", "bar", "MPa") : List.of("°C", "K"), unit, relative, valid);
    }

    private static final List<DialogColumns.Size> DRUM = List.of(new DialogColumns.Size("diameter", "2.00", "m", true),
            new DialogColumns.Size("length", "6.00", "m", true), new DialogColumns.Size("overall", "7.00", "m", false),
            new DialogColumns.Size("top", "9.50", "m", false), new DialogColumns.Size("volume", "18.8", "m³", false),
            new DialogColumns.Size("area", "44.0", "m²", false));
    private static final List<DialogColumns.Size> PUMP = List.of(new DialogColumns.Size("overall", "3.00", "m", false), new DialogColumns.Size("top", "1.20", "m", false));

    private static List<Case> cases(HeatControlColumn.Texts t) {
        var out = new ArrayList<Case>();
        for (boolean valid : new boolean[]{true, false}) {
            out.add(new Case("pressure gauge " + valid, DialogColumns.gauge(gauge(true, 2, true, valid), t), GAUGE));
            out.add(new Case("absolute gauge " + valid, DialogColumns.gauge(gauge(true, 0, false, valid), t), GAUGE));
            out.add(new Case("thermometer " + valid, DialogColumns.gauge(gauge(false, 0, false, valid), t), GAUGE));
            out.add(new Case("pipeline tool " + valid, DialogColumns.pipelineTool(new DialogColumns.PipelineTool(false, 0, false, valid), t), PIPELINE));
            out.add(new Case("laying tool " + valid, DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, valid), t), PIPELINE));
            out.add(new Case("laying tool bare " + valid, DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 1, true, valid), t), PIPELINE));
        }
        out.add(new Case("drum", DialogColumns.vessel(DRUM, false, false, t), VESSEL));
        out.add(new Case("pump", DialogColumns.vessel(PUMP, true, true, t), VESSEL));
        out.add(new Case("still pump", DialogColumns.vessel(PUMP, true, false, t), VESSEL));
        return out;
    }

    private static DialogColumns.Sheet sheet() {
        return new DialogColumns.Sheet(DRUM, List.of("Vertical drum on a skirt, 2:1 heads", "Skirt 3 m high", "Cells: 120 shell, 40 interior", "Controller cell 10, 64, -3"),
                "4 cells blocked by other blocks: clear them, then reload the world",
                List.of(new DialogColumns.Nozzle("Regeneration gas outlet", "Overflow", "Bottom"), new DialogColumns.Nozzle("Inlet", "Bulk", "Front"),
                        new DialogColumns.Nozzle("Reboiler vapour return", "Vapour", "Right")));
    }

    @Test
    void everyTextTheDialogsNameHasItsEnglishLine() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        cases(texts);
        DialogColumns.glass(sheet(), true, 160, M, texts);
        for (var key : List.of("pipeline_tool.save", "pipeline_tool.paint.bare", "vessel.blocked", "vessel.cells", "vessel.controller", "vessel.skirt", "vessel.saddles",
                "vessel.unsupported", "vessel.kind.skirt", "vessel.kind.saddles", "vessel.kind.bare", "vessel.kind.column", "vessel.port.vapour", "vessel.port.bulk",
                "vessel.port.liquid", "vessel.port.overflow", "equipment_constructor.side.front", "equipment_constructor.side.back", "equipment_constructor.side.left",
                "equipment_constructor.side.right", "equipment_constructor.side.top", "equipment_constructor.side.bottom"))
            texts.get(key, "1", "2");
        assertEquals(Set.of(), texts.missing);
    }

    /** Every plate is one line; nothing is cut; every widget lies inside its row, one per key; boxes and units in line; at full width and narrowed for a bar. */
    @Test
    void eachColumnFitsItsDoorWithItsBoxesInLine() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var c : cases(texts))
            for (int width : new int[]{c.width(), c.width() - BAR}) {
                var column = Column.place(c.rows(), 10, DialogColumns.TOP, width, M, Column.Metrics.COMPACT);
                var bounds = column.bounds();
                String at = c.name() + " at " + width;
                for (var p : column.rows()) {
                    assertTrue(p.rect().x() >= bounds.x() && p.rect().right() <= bounds.right(), at);
                    for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), at + ": a text cut: " + t);
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, at + ": a box " + s);
                    if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), at + ": a plate in one line: " + g.title());
                    if (p.row() instanceof Column.Field f) assertTrue(p.texts().contains(f.unit()), at + ": a unit whole: " + f.unit());
                    if (p.row() instanceof Column.Reading r) assertTrue(p.texts().contains(r.unit()), at + ": a unit whole: " + r.unit());
                }
                column.slots();
                DeviceColumnTest.assertInLine(column, at);
            }
    }

    private static Map<String, Column.Button> buttons(List<Column.Row> rows) {
        var out = new LinkedHashMap<String, Column.Button>();
        for (var row : rows) {
            if (row instanceof Column.Group g) out.putAll(buttons(g.rows()));
            if (row instanceof Column.Buttons b) for (var button : b.buttons()) out.put(button.key(), button);
        }
        return out;
    }

    private static List<String> fields(List<Column.Row> rows) {
        var out = new ArrayList<String>();
        for (var row : rows) {
            if (row instanceof Column.Group g) out.addAll(fields(g.rows()));
            if (row instanceof Column.Field f) out.add(f.key());
        }
        return out;
    }

    /** A selector's position latched where it stands, the others keys that move it; a reading basis only on a pressure gauge. */
    @Test
    void eachModeShowsItsKeysWithTheCurrentPositionsLatched() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var pressure = buttons(DialogColumns.gauge(gauge(true, 2, true, true), t));
        assertEquals(List.of("unit:0", "unit:1", "unit:2", "unit:3", "reading:gauge", "reading:absolute"), List.copyOf(pressure.keySet()));
        assertTrue(pressure.get("unit:2").latched() && !pressure.get("unit:2").active() && pressure.get("unit:0").active());
        assertTrue(pressure.get("reading:gauge").latched() && pressure.get("reading:absolute").active());
        var thermometer = DialogColumns.gauge(gauge(false, 1, false, true), t);
        assertEquals(List.of("unit:0", "unit:1"), List.copyOf(buttons(thermometer).keySet()));
        assertEquals(List.of("minimum", "maximum"), fields(thermometer));
        assertThrows(IllegalArgumentException.class, () -> gauge(false, 2, false, true));

        assertEquals(List.of("bore"), fields(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(false, 0, false, true), t)));
        assertTrue(buttons(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(false, 0, false, true), t)).isEmpty());
        var laying = DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 1, true, true), t);
        assertEquals(List.of("bore", "custom"), fields(laying));
        var keys = buttons(laying);
        assertTrue(keys.get("channel:1").latched() && keys.get("channel:0").active() && keys.get("bare").latched());
        assertTrue(buttons(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, true), t)).get("bare").active());

        var drum = DialogColumns.vessel(DRUM, false, false, t);
        assertEquals(List.of("diameter", "length"), fields(drum), "a drum's chosen sizes are entries; the rest are meters");
        assertTrue(buttons(drum).isEmpty());
        var running = buttons(DialogColumns.vessel(PUMP, true, true, t));
        assertTrue(running.get("motion:running").latched() && running.get("motion:still").active());
    }

    private static Map<String, Rect> widgets(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var p : column.rows())
            if (p.row() instanceof Column.Field || p.row() instanceof Column.Buttons || p.row() instanceof Column.Gap) out.putAll(p.slots().isEmpty() ? Map.of("gap", p.rect()) : p.slots());
        return out;
    }

    /** The warning line comes last: as the drafts become valid or not, no widget (nor the palette) moves, so the screens place the texts again each frame. */
    @Test
    void theWarningMovesNoWidget() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (boolean pressure : new boolean[]{true, false}) {
            var valid = Column.place(DialogColumns.gauge(gauge(pressure, 0, false, true), t), 10, DialogColumns.TOP, GAUGE, M, Column.Metrics.COMPACT);
            var invalid = Column.place(DialogColumns.gauge(gauge(pressure, 0, false, false), t), 10, DialogColumns.TOP, GAUGE, M, Column.Metrics.COMPACT);
            assertEquals(widgets(valid), widgets(invalid));
            assertTrue(invalid.bounds().h() > valid.bounds().h(), "the warning shows");
        }
        var valid = Column.place(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, true), t), 10, DialogColumns.TOP, PIPELINE, M, Column.Metrics.COMPACT);
        var invalid = Column.place(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, false), t), 10, DialogColumns.TOP, PIPELINE, M, Column.Metrics.COMPACT);
        assertEquals(widgets(valid), widgets(invalid));
        assertEquals(DialogColumns.palette(valid, Paint.PALETTE.size()), DialogColumns.palette(invalid, Paint.PALETTE.size()));
    }

    /** The palette's sixteen swatches in two rows of eight inside the Paint group's gap, apart; none without the Paint group. */
    @Test
    void thePaletteFillsItsGap() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (int width : new int[]{PIPELINE, PIPELINE - BAR}) {
            var column = Column.place(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, true), t), 10, DialogColumns.TOP, width, M, Column.Metrics.COMPACT);
            var gap = column.rows().stream().filter(p -> p.row() instanceof Column.Gap).findFirst().orElseThrow().rect();
            var swatches = DialogColumns.palette(column, Paint.PALETTE.size());
            assertEquals(16, swatches.size());
            assertEquals(2, swatches.stream().map(Rect::y).distinct().count());
            for (int i = 0; i < swatches.size(); i++) {
                var s = swatches.get(i);
                assertTrue(s.inside(gap) && s.w() >= 16 && s.h() == DialogColumns.SWATCH, "swatch " + i + " at " + width + ": " + s);
                for (int j = 0; j < i; j++) assertFalse(s.overlaps(swatches.get(j)), "swatches " + j + " and " + i + " at " + width);
            }
        }
        var plain = Column.place(DialogColumns.pipelineTool(new DialogColumns.PipelineTool(false, 0, false, true), t), 10, DialogColumns.TOP, PIPELINE, M, Column.Metrics.COMPACT);
        assertEquals(List.of(), DialogColumns.palette(plain, Paint.PALETTE.size()));
    }

    /** The glass: the sizes only where asked, the notes and warning wrapped, the nozzles under their header with each role wrapped in its column; no line wider than its place. */
    @Test
    void theDatasheetsGlassCutsNoName() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (int width : new int[]{150, 160, 250, 400}) {
            var glass = DialogColumns.glass(sheet(), false, width, M, t);
            var withSizes = DialogColumns.glass(sheet(), true, width, M, t);
            assertEquals(glass.lines().size() + DRUM.size(), withSizes.lines().size());
            assertEquals(Palette.WARN, glass.lines().getFirst().palette(), "the warning first, unscrolled");
            assertEquals(List.of("Diameter", "2.00 m"), withSizes.lines().stream().filter(l -> l.cells().size() == 2).findFirst().orElseThrow().cells());
            int header = 0, roles = 0;
            for (var line : glass.lines()) {
                var cells = line.cells();
                if (cells.size() == 1) assertTrue(M.width(cells.getFirst()) <= width - 6, width + ": a note cut: " + cells);
                if (cells.size() == 3) {
                    assertTrue(M.width(cells.get(0)) <= (glass.stacked() ? width - 6 : width - 10 - glass.port() - glass.side()), width + ": a role cut: " + cells);
                    assertTrue(M.width(cells.get(1)) <= glass.port() - 4 && M.width(cells.get(2)) <= glass.side(), width + ": a port or side cut: " + cells);
                    if (line.header()) header++;
                    else roles++;
                }
            }
            assertEquals(1, header);
            assertTrue(roles >= 3, "every nozzle has a line");
            assertEquals(glass.lines().size() * DialogColumns.PITCH + 4, glass.height());
            // "Regeneration" (72 px here) is wider than the role column left at 150 and 160 px: the table stacks there.
            assertEquals(width < 200, glass.stacked(), "stacked at " + width);
        }
        assertEquals(0, DialogColumns.glass(new DialogColumns.Sheet(DRUM, List.of("A drum"), null, List.of()), false, 160, M, t).port());
    }

    /**
     * Owner: "different lines needs some distinguishing" ("B: bands"): an entry's lines, wrapped or not, follow each other
     * and share one band; neighbouring entries alternate; the blank line and the header lie on none; no word is split.
     */
    @Test
    void everySecondEntryLiesOnABandAndNoWordSplits() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (int width : new int[]{150, 160, 250}) {
            var glass = DialogColumns.glass(sheet(), true, width, M, t);
            int previous = -1;
            boolean band = true;
            for (var line : glass.lines()) {
                if (line.entry() < 0) {
                    assertFalse(DialogColumns.banded(line), width + ": the blank line and the header lie on no band");
                    continue;
                }
                if (line.entry() != previous) {
                    // The next entry: one on from the last (so an entry's lines follow each other), on the other band.
                    assertEquals(previous + 1, line.entry(), width + ": entries count up, their lines together");
                    assertNotEquals(band, DialogColumns.banded(line), width + ": neighbouring entries alternate at entry " + line.entry());
                    previous = line.entry();
                    band = DialogColumns.banded(line);
                } else assertEquals(band, DialogColumns.banded(line), width + ": an entry's wrapped line shares its band");
            }
            assertTrue(glass.lines().stream().anyMatch(DialogColumns::banded) && glass.lines().stream().anyMatch(l -> l.entry() >= 0 && !DialogColumns.banded(l)));
            var words = new java.util.HashSet<String>();
            for (var text : sheet().notes()) words.addAll(List.of(text.split(" ")));
            for (var n : sheet().nozzles()) words.addAll(List.of(n.role().split(" ")));
            for (var line : glass.lines())
                if (line.cells().size() != 2 && !line.header())
                    for (var word : line.cells().getFirst().split(" "))
                        assertTrue(word.isEmpty() || words.contains(word) || sheet().warning().contains(word), width + ": a split word " + word);
        }
    }

    /** The footer: two push buttons share the width; Done stands alone at the right; all inside the panel. */
    @Test
    void theFooterSharesItsWidth() {
        var two = DialogColumns.footer(246, 170, 2);
        assertEquals(2, two.size());
        assertEquals(10, two.get(0).x());
        assertEquals(236, two.get(1).right());
        assertEquals(two.get(0).w(), two.get(1).w());
        var done = DialogColumns.footer(340, 250, 1).getFirst();
        assertEquals(new Rect(250, 223, 80, 18), done);
    }
}
