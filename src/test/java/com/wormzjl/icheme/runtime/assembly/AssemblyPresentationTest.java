package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.runtime.PresentationMailbox;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The column GUIs' sessions on the engine's buckets (batch 2026-10-26-multi-machine-framework, plan section 6; AGENTS.md
 * "Engine schedule and player-facing updates"): opening replays the stored view and walks nothing; a press waits for
 * the bucket; one validation per master per bucket; nothing is sent without a session.
 */
class AssemblyPresentationTest {
    private record Viewer(String name, long master) {
    }

    /** Masters' stored views, the validations run and the views sent. */
    private static final class Host implements AssemblyPresentation.Host<Viewer> {
        final Map<Long, AssemblyView> views = new HashMap<>();
        final List<Long> validations = new ArrayList<>();
        final List<Map.Entry<Viewer, AssemblyView>> sent = new ArrayList<>();
        final Set<Viewer> closed = new HashSet<>();

        @Override
        public boolean valid(Viewer viewer) {
            return !closed.contains(viewer);
        }

        @Override
        public long master(Viewer viewer) {
            return viewer.master();
        }

        @Override
        public @Nullable AssemblyView view(Viewer viewer) {
            return views.get(viewer.master());
        }

        @Override
        public void validate(Viewer viewer) {
            // Every validation finds the same plant.
            validations.add(viewer.master());
            views.put(viewer.master(), checked(1));
        }

        @Override
        public void send(Viewer viewer, AssemblyView view) {
            sent.add(Map.entry(viewer, view));
        }

        List<Viewer> receivers() {
            return sent.stream().map(Map.Entry::getKey).toList();
        }
    }

    private static final long COLUMN = 10, OTHER = 20, BUCKET = PresentationMailbox.PERIOD;

    /** A checked view whose detail tells validations apart. */
    private static AssemblyView checked(int n) {
        var report = new AssemblyReport(List.of(new SlotReport(ColumnBlueprint.COLUMN, State.OK, COLUMN, AssemblyReport.NONE, ""),
                new SlotReport(ColumnBlueprint.CONDENSER, State.MISSING, AssemblyReport.NONE, AssemblyReport.NONE, "run " + n)), List.of(), Set.of(), List.of(COLUMN));
        return new AssemblyView(AssemblyView.Status.CHECKED, report, AssemblyReport.NONE);
    }

    @Test
    void openingReplaysTheStoredViewAtOnceAndWalksNothing() {
        var host = new Host();
        var presentation = new AssemblyPresentation<>(host);
        var a = new Viewer("a", COLUMN);
        host.views.put(COLUMN, AssemblyView.NOT_VALIDATED);
        presentation.open(a, 5);
        assertEquals(List.of(Map.entry(a, AssemblyView.NOT_VALIDATED)), host.sent);
        assertTrue(host.validations.isEmpty());
        // The bucket sends nothing new: the view did not change.
        presentation.tick(BUCKET);
        assertEquals(1, host.sent.size());
        // A column gone: nothing to replay.
        var gone = new Viewer("gone", OTHER);
        presentation.open(gone, 6);
        assertEquals(1, host.sent.size());
    }

    @Test
    void aPressWaitsForTheBucketAndEachMasterIsValidatedOncePerBucket() {
        var host = new Host();
        var presentation = new AssemblyPresentation<>(host);
        var a = new Viewer("a", COLUMN);
        var b = new Viewer("b", COLUMN);
        var c = new Viewer("c", OTHER);
        host.views.put(COLUMN, AssemblyView.NOT_VALIDATED);
        host.views.put(OTHER, AssemblyView.NOT_VALIDATED);
        presentation.open(a, 1);
        presentation.open(b, 1);
        presentation.open(c, 1);
        host.sent.clear();
        assertTrue(presentation.validate(a, 2));
        assertFalse(presentation.validate(a, 3), "one press awaits the engine");
        assertTrue(presentation.validate(b, 3));
        presentation.tick(BUCKET - 1);
        assertTrue(host.validations.isEmpty(), "nothing runs before the bucket");
        presentation.tick(BUCKET);
        assertEquals(List.of(COLUMN), host.validations, "two presses on one master: one walk");
        // Both GUIs of the master get the answer; the other master's GUI, unchanged, gets nothing.
        assertEquals(List.of(a, b), host.receivers());
        assertEquals(checked(1), host.sent.get(0).getValue());
        // Pressed again in the next bucket: the view is equal, the answer still comes.
        host.sent.clear();
        host.views.put(COLUMN, checked(1));
        presentation.validate(a, BUCKET + 1);
        presentation.tick(2 * BUCKET);
        assertEquals(List.of(COLUMN, COLUMN), host.validations);
        assertTrue(host.receivers().containsAll(List.of(a, b)));
    }

    @Test
    void aChangedViewReachesOpenSessionsOnlyAndClosingEndsTheSession() {
        var host = new Host();
        var presentation = new AssemblyPresentation<>(host);
        var a = new Viewer("a", COLUMN);
        var b = new Viewer("b", COLUMN);
        host.views.put(COLUMN, checked(7));
        presentation.open(a, 1);
        presentation.open(b, 1);
        host.sent.clear();
        presentation.close(b);
        assertEquals(1, presentation.sessions());
        // A revert changes the stored view: the open session gets it on the bucket, not before.
        host.views.put(COLUMN, AssemblyView.NOT_VALIDATED);
        assertTrue(host.sent.isEmpty());
        presentation.tick(BUCKET);
        assertEquals(List.of(Map.entry(a, AssemblyView.NOT_VALIDATED)), host.sent);
        // A session no longer valid (the player walked away) is dropped at its bucket; the press it queued still runs
        // (owner decision 2026-10-28: closing ends the subscription, not the input), and nothing is sent to it.
        host.sent.clear();
        presentation.validate(a, BUCKET + 1);
        host.closed.add(a);
        presentation.tick(2 * BUCKET);
        assertTrue(host.sent.isEmpty());
        assertEquals(List.of(COLUMN), host.validations);
        assertEquals(0, presentation.sessions());
        // With no session, nothing is sent, whatever changes.
        host.views.put(COLUMN, checked(9));
        presentation.tick(3 * BUCKET);
        assertTrue(host.sent.isEmpty());
    }

    /**
     * An Operation tab command (C7: Start, Stop, Apply) is a queued engine event: it runs on the bucket, not at the press;
     * one awaits its answer per session; every open GUI of its master gets the answer, changed or not; walks nothing.
     */
    @Test
    void anOperationCommandRunsOnTheBucketAndAnswersEveryGuiOfItsColumn() {
        var host = new Host();
        var presentation = new AssemblyPresentation<>(host);
        var a = new Viewer("a", COLUMN);
        var b = new Viewer("b", COLUMN);
        var c = new Viewer("c", OTHER);
        host.views.put(COLUMN, checked(1));
        host.views.put(OTHER, checked(1));
        for (var v : List.of(a, b, c)) presentation.open(v, 1);
        host.sent.clear();
        var ran = new ArrayList<Viewer>();
        assertTrue(presentation.act(a, 2, ran::add));
        assertFalse(presentation.act(a, 3, ran::add), "one command awaits the engine");
        presentation.tick(BUCKET - 1);
        assertTrue(ran.isEmpty(), "nothing runs before the bucket");
        presentation.tick(BUCKET);
        assertEquals(List.of(a), ran);
        assertEquals(List.of(a, b), host.receivers(), "the column's GUIs get the answer, the other column's none");
        assertTrue(host.validations.isEmpty(), "a command walks nothing");
    }
}
