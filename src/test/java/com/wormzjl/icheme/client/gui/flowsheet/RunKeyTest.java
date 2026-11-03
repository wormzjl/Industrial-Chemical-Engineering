package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Run is lit while the results are out of date (the owner, 2026-11-04, option C: "lit when out of date"): after an edit, and
 * before the first run of a sheet with something on it; not right after a run, and off while a run is going. It reads the
 * state the status line's "Changed since the last run" reads, so the two never disagree.
 */
class RunKeyTest {
    private static final Flowsheet SHEET = SheetFixture.water();
    private static final FlowsheetRun RUN = FlowsheetEngine.run(SHEET, SheetFixture.CATALOG, () -> {
    });
    private static final SimulatorFrame FRAME = new SimulatorFrame(944, 524);

    /** A host that can run, is running when {@code running} is not null, and reads only when {@code reader}. */
    private static SimulatorHost host(SheetState.@Nullable Running running, boolean reader) {
        return new SimulatorHost() {
            @Override public boolean canRun() {
                return true;
            }

            @Override public SheetState.@Nullable Running running() {
                return running;
            }

            @Override public boolean reader() {
                return reader;
            }
        };
    }

    private static SheetContext context(SheetEditor editor, boolean ran, SheetState.@Nullable Running running) {
        var texts = new SheetFixture.Texts();
        return new SheetContext(editor, new SheetState(editor.sheet(), ran ? SHEET : null, ran ? RUN : null, 1.2, running), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
    }

    private static boolean edited(SheetContext x) {
        return x.state().status(x.t(), x.units(), false).text().startsWith(x.text("status.edited"));
    }

    private static com.wormzjl.icheme.client.gui.common.kit.Column.Button run(SheetContext x, SimulatorHost host, SheetEditor editor) {
        return SimulatorPage.frameKeys(x, SimulatorFrame.Tab.FLOWSHEET, FRAME, host, editor).stream().map(SimulatorPage.Slot::button).filter(b -> b.key().equals("run")).findFirst().orElseThrow();
    }

    @Test
    void runIsLitAfterAnEditNotRightAfterARunAndOffWhileRunning() {
        var editor = SheetFixture.editor(SHEET);
        var idle = host(null, false);
        var x = context(editor, true, null);
        assertFalse(SimulatorPage.runDue(x, idle), "right after a run");
        assertFalse(edited(x));
        assertNull(run(x, idle, editor).hint(), "a key that is not lit says nothing");

        StreamDoors.writeFeed(x, "FEED", "temperature", "80");
        x = context(editor, true, null);
        assertTrue(SimulatorPage.runDue(x, idle), "after an edit");
        assertTrue(edited(x), "the status line says the same");
        assertEquals(x.text("hint.run_due"), run(x, idle, editor).hint(), "its hover says why");
        assertTrue(run(x, idle, editor).active());

        var running = new SheetState.Running(1.0, 1, 30, null, null, SheetState.Task.RUN);
        var busy = host(running, false);
        x = context(editor, true, running);
        assertFalse(SimulatorPage.runDue(x, busy), "while a run is going Run is off");
        assertFalse(run(x, busy, editor).active());
        assertTrue(SimulatorPage.frameKeys(x, SimulatorFrame.Tab.FLOWSHEET, FRAME, busy, editor).stream().anyMatch(s -> s.button().key().equals("stop") && s.button().active()), "and Stop on");

        assertFalse(SimulatorPage.runDue(context(editor, true, null), host(null, true)), "a reader cannot run");
    }

    @Test
    void aSheetWithNoRunYetIsDueAnEmptyOneIsNot() {
        var editor = SheetFixture.editor(SHEET);
        assertTrue(SimulatorPage.runDue(context(editor, false, null), host(null, false)), "no results yet");
        var empty = SheetFixture.editor(new Flowsheet(SHEET.basis(), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT));
        assertFalse(SimulatorPage.runDue(context(empty, false, null), host(null, false)), "nothing to run");
    }
}
