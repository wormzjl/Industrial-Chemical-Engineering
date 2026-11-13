package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.display.DisplayUnit;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The column's assembly screen's help tours (batch 2026-11-12-tutorial-system, P5 stage B): every stop of the Structure
 * tab's tour and of the Operation tab's stands on its tab, inside the screen, at every width the screen takes (320 to 760
 * in steps of 7) and at the shortest and the design height: a frame place ({@link AssemblyTour#frame}) or a widget of
 * the tab's door (the drawing's door, the Operation page's door on a running column).
 */
class AssemblyTourTest {
    private static final Measure M = Measure.fixed(6);
    private static final int[] HEIGHTS = {220, 254};

    @Test
    void everyStopOfBothToursStandsOnItsTabAtEveryWidth() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var tours = TourFile.load();
        var structure = tours.get(AssemblyTour.page(false));
        var operation = tours.get(AssemblyTour.page(true));
        assertNotNull(structure);
        assertNotNull(operation);
        var units = new OperationColumn.Units(DisplayUnit.TemperatureUnit.CELSIUS, DisplayUnit.PressureUnit.BAR, DisplayUnit.FlowRateUnit.PER_HOUR);
        var running = OperationColumnTest.running(OperationColumnTest.setpoints(1, 1, 1, false), OperationView.Phase.RUNNING);
        for (int width = 320; width <= 760; width += 7)
            for (int height : HEIGHTS) {
                var area = AssemblyColumn.area(width, height);
                for (boolean op : new boolean[]{false, true}) {
                    var rows = op ? OperationColumn.rows(new OperationColumn.Input(running, false, units), t)
                            : AssemblyColumn.rows(AssemblyColumn.input(15, null, false, "Condenser", true), t);
                    var anchors = new HashMap<>(AssemblyTour.frame(width, height, op));
                    anchors.putAll(AssemblyTour.door(Column.place(rows, area.x(), area.y(), area.w(), M, Column.Metrics.COMPACT)));
                    for (var stop : (op ? operation : structure).stops()) {
                        var at = anchors.get(stop.anchor());
                        assertNotNull(at, width + " by " + height + ": " + stop.anchor() + " on the " + (op ? "Operation" : "Structure") + " tab");
                        assertTrue(at.x() >= 0 && at.y() >= 0 && at.right() <= width && at.w() > 0 && at.h() > 0, width + " by " + height + ": " + stop.anchor() + " inside the screen: " + at);
                    }
                }
            }
    }
}
