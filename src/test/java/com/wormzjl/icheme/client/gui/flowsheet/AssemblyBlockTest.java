package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.AssemblyColumn;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ColumnTrays.Pumparound;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assembly screen's drawing as the process simulator draws a column (owner 2026-11-03, option A of "looks more like the
 * process simulator"): {@link AssemblyBlock} lays out the built column's component design with the sheet's {@link ColumnBlock},
 * so every slot the assembly checks is one drawn part, a click finds it, and each part reads its check.
 */
class AssemblyBlockTest {
    /** Every condenser, both reflux pumps, with and without an ejector and a kettle, on three layouts: bare, every row, rows on near trays. */
    private static List<ColumnTrays> designs() {
        var out = new ArrayList<ColumnTrays>();
        for (var condenser : Condenser.values())
            for (var pump : Pump.values())
                for (boolean ejector : new boolean[]{false, true})
                    for (boolean kettle : new boolean[]{false, true}) {
                        var units = condenser == Condenser.AIR_THEN_WATER ? new ColumnComponents.Units(2, kettle ? 2 : 1, List.of(1, 3, 1, 1), 3) : ColumnComponents.Units.ONE;
                        out.add(new ColumnTrays(20, 10, List.of(), List.of(), List.of(), new ColumnComponents(condenser, pump, List.of(), ejector, kettle, ColumnComponents.Units.ONE)));
                        out.add(new ColumnTrays(20, 10, List.of(3, 6, 9, 12), List.of(18, 21), List.of(new Pumparound(4, 2), new Pumparound(8, 6), new Pumparound(12, 10), new Pumparound(16, 14)),
                                new ColumnComponents(condenser, pump, List.of(Pump.STANDARD, Pump.IN_LINE, Pump.STANDARD, Pump.IN_LINE), ejector, kettle, units)));
                        out.add(new ColumnTrays(14, 7, List.of(10, 11, 12), List.of(15), List.of(new Pumparound(10, 8), new Pumparound(11, 9)),
                                new ColumnComponents(condenser, pump, List.of(Pump.STANDARD, Pump.STANDARD), ejector, kettle, ColumnComponents.Units.ONE)));
                    }
        return out;
    }

    private static String label(ColumnTrays t) {
        var c = t.components();
        return c.condenser() + " " + c.refluxPump() + (c.ejector() ? " ejector" : "") + (c.kettle() ? " kettle" : "") + " trays " + t.trays() + " draws " + t.draws() + " steam " + t.steam() + " PAs " + t.pumparounds();
    }

    /** One drawn part for each slot of the assembly's blueprint, no more: the block reads the design by the same numbers. */
    @Test
    void everySlotTheAssemblyChecksIsOneDrawnPart() {
        var seen = new HashSet<String>();
        for (var trays : designs()) {
            var drawn = new AssemblyBlock(trays).block.parts().stream().map(ColumnBlock.Part::slot).toList();
            var slots = ColumnBlueprint.of(trays).slots();
            assertEquals(new HashSet<Slot>(slots), new HashSet<>(drawn), label(trays));
            assertEquals(slots.size(), drawn.size(), "one part each: " + label(trays));
            for (var slot : slots) seen.add(slot.part());
        }
        assertTrue(seen.containsAll(List.of("ejector", "reboiler", "pa_cooler", "pa_pump", "steam_valve", "draw_valve", "trim_condenser")), "every optional slot: " + seen);
    }

    /**
     * Fitted to the drawing's side of the glass at the screen's sizes, the block stands inside it, and a click on a part's middle
     * finds a part there (a valve on a unit's line before the unit), the tower's middle the column. Prints the scale at the
     * design size and at a 1920 by 1080 window (BLOCK_SCALE); labels draw from 0.5.
     */
    @Test
    void theBlockFitsTheGlassAndAClickFindsItsPart() {
        for (int[] size : new int[][]{{448, 238}, {464, 254}, {760, 524}, {760, 400}})
            for (var trays : designs()) {
                var box = AssemblyColumn.split(AssemblyColumn.glass(size[0], size[1], true)).drawing();
                var drawing = new AssemblyBlock(trays);
                drawing.fit(box);
                var f = drawing.block.frame();
                String at = size[0] + " by " + size[1] + " " + label(trays);
                double s = drawing.scale();
                assertTrue(s > 0, at);
                int ox = (int) Math.round(box.x() + (box.w() - f.w() * s) / 2 - f.x() * s), oy = (int) Math.round(box.y() + (box.h() - f.h() * s) / 2 - f.y() * s);
                assertTrue(ox + f.x() * s >= box.x() - 1 && ox + f.right() * s <= box.right() + 1 && oy + f.y() * s >= box.y() - 1 && oy + f.bottom() * s <= box.bottom() + 1, at + ": the frame in the glass");
                for (var part : drawing.block.parts()) {
                    var r = part.shape() == ColumnBlock.Shape.TOWER ? drawing.block.tower() : part.rect();
                    double cx = ox + (r.x() + r.w() / 2.0) * s, cy = oy + (r.y() + r.h() / 2.0) * s;
                    var hit = drawing.part(cx, cy);
                    assertNotNull(hit, at + ": a click on " + part.slot());
                    if (part.shape() == ColumnBlock.Shape.TOWER) assertEquals(ColumnBlueprint.COLUMN, hit, at + ": the tower's middle");
                }
                if (trays.equals(designs().get(1))) System.out.printf(java.util.Locale.ROOT, "BLOCK_SCALE %d by %d: %.2f in %d by %d px%n", size[0], size[1], s, box.w(), box.h());
            }
    }

    /** The tower neutral while it is in place or before a check; every other part red before a check, then by its check's colour. */
    @Test
    void eachPartReadsItsCheck() {
        var trays = designs().get(1);
        var slots = ColumnBlueprint.of(trays).slots();
        assertEquals(SheetPainter.NONE, AssemblyBlock.look(ColumnBlueprint.COLUMN, null));
        assertEquals(Look.RED, AssemblyBlock.look(ColumnBlueprint.REFLUX_PUMP, null), "red before a check");
        var reports = new ArrayList<AssemblyReport.SlotReport>();
        for (var slot : slots)
            reports.add(new AssemblyReport.SlotReport(slot, slot.equals(ColumnBlueprint.REFLUX_PUMP) ? AssemblyReport.State.WRONG_KIND : slot.equals(ColumnBlueprint.FEED_VALVE) ? AssemblyReport.State.MISSING : AssemblyReport.State.OK, 1, AssemblyReport.NONE, ""));
        var report = new AssemblyReport(reports, List.of(), Set.of(), List.of());
        assertEquals(SheetPainter.NONE, AssemblyBlock.look(ColumnBlueprint.COLUMN, report), "the tower in place stays neutral");
        assertEquals(Look.TEAL, AssemblyBlock.look(ColumnBlueprint.CONDENSER, report));
        assertEquals(Look.of(AssemblyReport.State.WRONG_KIND.colour), AssemblyBlock.look(ColumnBlueprint.REFLUX_PUMP, report));
        assertEquals(Look.of(AssemblyReport.State.MISSING.colour), AssemblyBlock.look(ColumnBlueprint.FEED_VALVE, report));
    }
}
