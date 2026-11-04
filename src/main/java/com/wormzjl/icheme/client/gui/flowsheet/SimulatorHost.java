package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Note;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * What the screen asks of the outside (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2): starting and stopping a run, which {@link SimulatorRunner} does, and storing the sheet on
 * its block, which W8 does. The screen keeps no thread and no packet of its own. {@link #NONE} runs nothing and stores nothing, as the screen stands until then.
 */
public interface SimulatorHost {
    /**
     * What Solve alone last did, until the next run.
     *
     * @param sheet   the sheet it solved on
     * @param status  how it ended
     * @param why     why it failed, or null
     * @param seconds how long it took
     */
    record Alone(String unit, Flowsheet sheet, FlowsheetRun.Status status, @Nullable Note why, double seconds) {
    }

    /** A run that ended: the sheet it ran on, what it left, how long it took, what a unit solved alone has done since, and the revision tokens of what it ran on. */
    record Finished(Flowsheet sheet, FlowsheetRun run, double seconds, @Nullable Alone alone, Map<String, String> ranOn) {
        public Finished(Flowsheet sheet, FlowsheetRun run, double seconds, @Nullable Alone alone) {
            this(sheet, run, seconds, alone, Map.of());
        }

        public Finished(Flowsheet sheet, FlowsheetRun run, double seconds) {
            this(sheet, run, seconds, null);
        }
    }

    /** What the block or the host has to tell the player: a translation key suffix, its arguments, and whether it warns. */
    record Said(String key, List<String> arguments, boolean warn) {
    }

    SimulatorHost NONE = new SimulatorHost() {
    };

    /** The run half; null where nothing runs. */
    default @Nullable SimulatorRunner runner() {
        return null;
    }

    /** Whether Run can start. */
    default boolean canRun() {
        return runner() != null;
    }

    /** Starts a run of {@code sheet} in the background. */
    default void run(Flowsheet sheet) {
        var runner = runner();
        if (runner != null) runner.run(sheet);
    }

    /** Solves {@code unit} alone in the background, on the streams it has now. */
    default void alone(Flowsheet sheet, String unit) {
        var runner = runner();
        if (runner != null) runner.alone(sheet, unit);
    }

    /** Starts Size all of {@code sheet} on the last run in the background (plan section 3.1). */
    default void size(Flowsheet sheet) {
        var runner = runner();
        if (runner != null) runner.size(sheet);
    }

    /** Rates {@code sheet}'s items at their sizes on the last run, quietly in the background. */
    default void rate(Flowsheet sheet) {
        var runner = runner();
        if (runner != null) runner.rate(sheet);
    }

    /** The last rating, or null. */
    default SimulatorRunner.@Nullable Rated rated() {
        var runner = runner();
        return runner == null ? null : runner.rated();
    }

    /** Whether a quiet rating is in hand. */
    default boolean rating() {
        var runner = runner();
        return runner != null && runner.rating();
    }

    /** Why the last Size all or rating broke, or null. */
    default @Nullable String broken() {
        var runner = runner();
        return runner == null ? null : runner.broken();
    }

    /** Stops the run in hand. */
    default void stop() {
        var runner = runner();
        if (runner != null) runner.stop();
    }

    /** The screen is gone: the run in hand stops, leaves no thread and drops its result. */
    default void close() {
        var runner = runner();
        if (runner != null) runner.close();
    }

    /** The run in hand, or null. */
    default SheetState.@Nullable Running running() {
        var runner = runner();
        return runner == null ? null : runner.running();
    }

    /** The last run, or null. */
    default @Nullable Finished last() {
        var runner = runner();
        return runner == null ? null : runner.last();
    }

    /** A run or a reader takes no edit; the end of a run lifts the lock, unless this player only reads. */
    default void lock(SheetEditor editor) {
        boolean reader = reader();
        editor.lock(reader || running() != null, reader ? SheetEditor.LOCKED : SheetEditor.RUNNING);
    }

    /** The server's revision tokens ({@code RevisionNotice}), or null with no word from one. */
    default @Nullable Map<String, String> serverRevisions() {
        return null;
    }

    /** Joules per FE, for the FE/t a proposed motor draws (batch 2026-11-03-rotating-machinery, W8): the common config's {@code joulesPerFE} as this client loaded it, 2.5 J with no config. */
    default double joulesPerFE() {
        return 2.5;
    }

    /** Whether the sheet is stored on a block, so Save has somewhere to go. */
    default boolean stores() {
        return false;
    }

    /** Stores the sheet on its block. */
    default void store(Flowsheet sheet) {
    }

    /** What stands on the status line until something else is said, or null. */
    default @Nullable Said said() {
        return null;
    }

    default void say(@Nullable Said what) {
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
