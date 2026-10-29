package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The tool's physical nozzle identity must resolve to the actual GUI square's colour, even after connection. */
class AssemblyPortColoursTest {
    private static ColumnTrays design(ColumnComponents.Condenser condenser, ColumnComponents.Pump pump, boolean ejector, boolean kettle) {
        return new ColumnTrays(20, 10, List.of(3, 6, 9, 12), List.of(18, 21),
                List.of(new ColumnTrays.Pumparound(4, 2), new ColumnTrays.Pumparound(8, 6),
                        new ColumnTrays.Pumparound(12, 10), new ColumnTrays.Pumparound(16, 14)),
                new ColumnComponents(condenser, pump, List.of(pump, pump, pump, pump), ejector, kettle));
    }

    @Test
    void physicalNozzleIdentitiesMatchDistinctDrawnColoursBeforeAndAfterConnection() {
        for (var condenser : ColumnComponents.Condenser.values())
            for (var pump : ColumnComponents.Pump.values())
                for (boolean ejector : new boolean[]{false, true})
                    for (boolean kettle : new boolean[]{false, true}) {
                        var diagram = AssemblyDiagram.of(design(condenser, pump, ejector, kettle));
                        var free = draw(diagram, null);
                        var slots = diagram.parts().stream().map(p -> p.slot()).toList();
                        var reports = slots.stream().map(s -> new AssemblyReport.SlotReport(s, AssemblyReport.State.OK,
                                slots.indexOf(s) + 1L, AssemblyReport.NONE, "")).toList();
                        var ports = diagram.ports().stream().map(m -> new AssemblyReport.PortReport(slots.indexOf(m.slot()) + 1L,
                                m.role(), Math.max(0, m.ordinal()), AssemblyReport.PortState.CONNECTED)).toList();
                        var connected = draw(diagram, new AssemblyReport(reports, ports, Set.of(), reports.stream().map(AssemblyReport.SlotReport::member).toList()));
                        var colours = new HashSet<Integer>();
                        for (var mark : diagram.ports()) {
                            // A receiver SIDE square accepts any physical nozzle ordinal; other roles match exactly.
                            int colour = PortColours.assembly(diagram, mark.slot(), mark.role(), mark.ordinal() < 0 ? 3 : mark.ordinal());
                            assertTrue(colour >= 0, mark::toString);
                            assertTrue(colours.add(colour), () -> "Repeated square colour: " + mark);
                            assertEquals(0xFF000000 | colour, free.at(mark.x(), mark.y()), mark::toString);
                            assertEquals(free.at(mark.x(), mark.y()), connected.at(mark.x(), mark.y()), mark::toString);
                            assertEquals(0xFF000000 | PortColours.CONNECTED, connected.at(mark.x() - 5, mark.y()), mark::toString);
                        }
                    }
    }

    @Test
    void portsOutsideTheDrawingHaveNoAssemblyColour() {
        var diagram = AssemblyDiagram.of(design(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, false, false));
        assertEquals(-1, PortColours.assembly(diagram, ColumnBlueprint.CONDENSER, Role.TUBE_INLET, 0));
        assertEquals(-1, PortColours.assembly(diagram, ColumnBlueprint.COLUMN, Role.DRAW, ColumnTrays.MAX_DRAWS));
        assertEquals(-1, PortColours.assembly(diagram, ColumnBlueprint.pumparoundCooler(5), Role.SHELL_INLET, 0));
    }

    private static Recording draw(AssemblyDiagram diagram, AssemblyReport report) {
        var canvas = new Recording();
        var sheet = new AssemblyFlowsheet(diagram);
        sheet.fit(new Rect(0, 0, AssemblyDiagram.WIDTH, AssemblyDiagram.HEIGHT));
        sheet.draw(canvas, report, null, text -> "");
        return canvas;
    }

    private record Fill(int x0, int y0, int x1, int y1, int argb) {
    }

    private static final class Recording implements Canvas {
        private final List<Fill> fills = new ArrayList<>();

        int at(int x, int y) {
            for (var fill : fills.reversed())
                if (x >= fill.x0() && x < fill.x1() && y >= fill.y0() && y < fill.y1()) return fill.argb();
            throw new AssertionError("No fill at " + x + ", " + y);
        }

        @Override public Measure measure() { return Measure.fixed(6); }
        @Override public void fill(int x0, int y0, int x1, int y1, int argb) { fills.add(new Fill(x0, y0, x1, y1, argb)); }
        @Override public void text(String text, int x, int y, int argb) { }
        @Override public void text(String text, float x, float y, float scale, int argb) { }
        @Override public void clip(Rect area) { }
        @Override public void unclip() { }
    }
}
