package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import org.jetbrains.annotations.Nullable;

/**
 * What the screen asks of the outside (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2): starting and stopping a run, which W7 fills in, and storing the sheet on
 * its block, which W8 does. The screen keeps no thread and no packet of its own. {@link #NONE} runs nothing and stores nothing, as the screen stands until then.
 */
public interface SimulatorHost {
    /** A run that ended: the sheet it ran on, what it left, how long it took. */
    record Finished(Flowsheet sheet, FlowsheetRun run, double seconds) {
    }

    SimulatorHost NONE = new SimulatorHost() {
    };

    /** Whether Run can start. */
    default boolean canRun() {
        return false;
    }

    /** Starts a run of {@code sheet} in the background. */
    default void run(Flowsheet sheet) {
    }

    /** Stops the run in hand. */
    default void stop() {
    }

    /** The run in hand, or null. */
    default SheetState.@Nullable Running running() {
        return null;
    }

    /** The last run, or null. */
    default @Nullable Finished last() {
        return null;
    }

    /** Whether the sheet is stored on a block, so Save has somewhere to go. */
    default boolean stores() {
        return false;
    }

    /** Stores the sheet on its block. */
    default void store(Flowsheet sheet) {
    }

    /** Whether this player only reads: a second player at a block another edits. */
    default boolean reader() {
        return false;
    }

    /** Who edits the sheet, while this player only reads it. */
    default @Nullable String holder() {
        return null;
    }
}
