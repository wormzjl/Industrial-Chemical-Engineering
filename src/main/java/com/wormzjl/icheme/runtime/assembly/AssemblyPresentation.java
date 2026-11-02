package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.runtime.PresentationMailbox;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * The column GUIs' sessions (batch 2026-10-26-multi-machine-framework, plan section 6; AGENTS.md "Engine schedule and
 * player-facing updates"). Opening a GUI replays its master's stored view at once, a cached read that walks nothing; the
 * session then gets the view on the engine's presentation buckets, when it changed. A "Validate structure" press queues
 * one command in its session; the bucket runs it, at most one validation per master per bucket, and answers every open
 * GUI of that master. Nothing is sent to a viewer without an open session.
 *
 * @param <V> a session's key: a player's open menu on the server, any value in tests
 */
public final class AssemblyPresentation<V> {
    /** The world a presentation reads and the viewers it answers. */
    public interface Host<V> {
        /** Whether the session is still open. */
        boolean valid(V viewer);

        /** The master the session shows. */
        long master(V viewer);

        /** The stored view of the session's master: a cached read that walks nothing; null when the master is gone. */
        @Nullable AssemblyView view(V viewer);

        /** Validates the session's master: walks its pipes and stores the report. */
        void validate(V viewer);

        void send(V viewer, AssemblyView view);
    }

    private final Host<V> host;
    private final PresentationMailbox<V> mailbox = new PresentationMailbox<>();
    private final Map<V, AssemblyView> sent = new HashMap<>();
    private final Set<Long> validated = new HashSet<>(), answered = new HashSet<>();

    public AssemblyPresentation(Host<V> host) {
        this.host = host;
    }

    /** A GUI opened: the session subscribes and gets its master's stored view now, if there is one. */
    public void open(V viewer, long now) {
        mailbox.subscribe(viewer, now);
        var view = host.view(viewer);
        if (view == null) return;
        host.send(viewer, view);
        sent.put(viewer, view);
    }

    /** A "Validate structure" press: queued for the next bucket; false when one already awaits it. */
    public boolean validate(V viewer, long now) {
        return mailbox.submit(viewer, now, () -> {
            answered.add(host.master(viewer));
            if (validated.add(host.master(viewer))) host.validate(viewer);
        });
    }

    /**
     * An Operation tab command (batch 2026-10-28-column-operation, C7: Start, Stop, Apply): queued for the next bucket, which
     * runs {@code action} and answers every open GUI of its master; false while the session's last command awaits its bucket.
     */
    public boolean act(V viewer, long now, java.util.function.Consumer<V> action) {
        return mailbox.submit(viewer, now, () -> {
            answered.add(host.master(viewer));
            action.accept(viewer);
        });
    }

    /** A GUI closed: the session ends; a press it queued still runs on its bucket (PresentationMailbox). */
    public void close(V viewer) {
        mailbox.remove(viewer);
        sent.remove(viewer);
    }

    /** The engine's tick: on a bucket, runs the queued commands, then sends each session its view if it changed. */
    public void tick(long now) {
        if (now < mailbox.nextTick()) return;
        validated.clear();
        answered.clear();
        mailbox.tick(now, viewer -> {
            if (host.valid(viewer)) return true;
            sent.remove(viewer);
            return false;
        }, viewer -> {
            var view = host.view(viewer);
            // Every open GUI of a master a command reached in this bucket gets the answer, changed or not.
            if (view == null || !answered.contains(host.master(viewer)) && view.equals(sent.get(viewer))) return;
            host.send(viewer, view);
            sent.put(viewer, view);
        });
    }

    /** Open sessions. */
    public int sessions() {
        return mailbox.size();
    }
}
