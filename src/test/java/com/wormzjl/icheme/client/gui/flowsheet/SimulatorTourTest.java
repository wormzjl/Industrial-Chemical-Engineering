package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The simulator's help tours (batch 2026-11-12-tutorial-system, P4b): each of the four tabs has its tour; every stop's anchor
 * stands on its tab at every frame size, on the bundled small crude unit with and without a run, resolved as the screen
 * resolves it (a frame key or named area, else a door row's widget); the ? key stands beside the theme key on the title row
 * and covers no other key; the steps the screen reports are the guide's reading steps.
 */
class SimulatorTourTest {
    static final int[][] SIZES = {{464, 254}, {624, 344}, {790, 524}, {944, 524}, {1280, 720}};

    /** The anchors the screen resolves on {@code tab}: the frame's, then the door's rows placed as the door places them. */
    static Map<String, Rect> anchors(SheetContext x, SimulatorFrame frame, SimulatorFrame.Tab tab, int w) {
        var out = new HashMap<>(SimulatorTour.frame(frame, tab, w, SimulatorPage.frameKeys(x, tab, frame, x.host(), x.editor())));
        List<? extends Column.Row> rows = switch (tab) {
            case RESULTS -> ResultsPage.rows(x, new ResultsPage.State());
            case SIZING -> SizingPage.rows(x, new SizingPage.State());
            case BASIS -> BasisPage.rows(x);
            case FLOWSHEET -> List.of();
        };
        if (!rows.isEmpty())
            SimulatorTour.door(Column.place(rows, 10, SimulatorFrame.DOOR_TOP, SimulatorTour.door(tab) - 10, SheetFixture.M, Column.Metrics.COMPACT)).forEach(out::putIfAbsent);
        return out;
    }

    static List<SheetContext> contexts() {
        var texts = new SheetFixture.Texts();
        var sheet = SheetFixture.example(SimulatorTour.EXAMPLE);
        var editor = SheetFixture.editor(sheet);
        var none = new SheetContext(editor, SheetState.of(editor.sheet(), null), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, List.of());
        var ran = new SheetContext(editor, SheetState.of(editor.sheet(), new SimulatorHost.Finished(editor.sheet(), com.wormzjl.icheme.science.flowsheet.FlowsheetRun.none(), 1)),
                SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, List.of());
        return List.of(none, ran);
    }

    @Test
    void everyStopStandsOnItsTabAtEveryFrameSize() {
        var tours = TourFile.load();
        for (var tab : SimulatorFrame.Tab.values()) {
            var tour = tours.get(SimulatorTour.page(tab));
            assertNotNull(tour, tab + " has its tour");
            for (var size : SIZES)
                for (var x : contexts()) {
                    var frame = new SimulatorFrame(size[0], size[1]);
                    var anchors = anchors(x, frame, tab, size[0]);
                    for (var stop : tour.stops()) {
                        var r = anchors.get(stop.anchor());
                        assertNotNull(r, size[0] + " by " + size[1] + ", " + tab + ": " + stop.anchor());
                        assertTrue(r.w() > 0 && r.h() > 0 && r.x() >= 0 && r.y() >= 0 && r.right() <= size[0] && r.bottom() <= size[1], tab + ": " + stop.anchor() + " on the panel: " + r);
                    }
                }
        }
    }

    @Test
    void theHelpKeyStandsBesideTheThemeKeyAndCoversNoOtherKey() {
        for (var size : SIZES) {
            var frame = new SimulatorFrame(size[0], size[1]);
            var help = frame.help();
            assertFalse(help.overlaps(frame.theme()), size[0] + ": ? and theme apart");
            assertEquals(frame.theme().y(), help.y(), "on the title row");
            assertTrue(help.right() <= frame.theme().x(), "left of the theme key");
            for (var tab : SimulatorFrame.Tab.values())
                for (var slot : SimulatorPage.frameKeys(contexts().getFirst(), tab, frame, SimulatorHost.NONE, contexts().getFirst().editor()))
                    assertFalse(help.overlaps(slot.rect()), size[0] + ", " + tab + ": ? over " + slot.button().key());
        }
    }

    @Test
    void theStepsTheScreenReportsAreTheGuidesReadingSteps() {
        var guide = GuideFile.load();
        for (var id : List.of(SimulatorTour.SHEET_STEP, SimulatorTour.OPEN_STEP, SimulatorTour.SIZE_STEP)) {
            var step = guide.step(id);
            assertNotNull(step, id);
            assertEquals(Guide.Check.CLIENT, step.check(), id + ": the client reports it");
        }
        assertEquals(SimulatorTour.SHOWN_STEP, TourFile.load().get(SimulatorTour.page(SimulatorFrame.Tab.FLOWSHEET)).show(), "the sheet tour's last stop shows chapter 4's card");
        assertInstanceOf(Guide.Card.class, guide.step(SimulatorTour.SHOWN_STEP).media());
        assertNotNull(SheetFixture.example(SimulatorTour.EXAMPLE), "the example the open step names is bundled");
    }
}
