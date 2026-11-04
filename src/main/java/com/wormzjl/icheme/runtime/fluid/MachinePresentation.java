package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.PresentationMailbox;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * The machine pages' sessions (batch 2026-11-03-rotating-machinery, W7, readings A85 and A87; AGENTS.md "Engine schedule
 * and player-facing updates"). Opening a page replays its part's view at once, a read of what the trains last decided;
 * before the trains know the part there is nothing to replay and the page says it waits. The session then gets the view on
 * the engine's presentation buckets ({@link PresentationMailbox#PERIOD} online ticks), when it changed or answers an edit.
 * An edit is checked by the caller and queued as the shaft event {@code /icheme shaft} queues, at once; its session's next
 * bucket answers it with the view that follows it, and with its refusal where it was refused. A session holds at most one
 * unanswered edit; a later one before the bucket replaces the sequence it answers. Nothing is sent without a session.
 *
 * @param <V> a session's key: a player's open menu on the server, any value in tests
 */
public final class MachinePresentation<V> {
    /** The world a presentation reads and the viewers it answers. */
    public interface Host<V> {
        /** Whether the session is still open. */
        boolean valid(V viewer);

        /** The session's part as its page shows it: a read; null when the trains do not hold it. */
        @Nullable MachineView view(V viewer);

        /** Sends {@code view} with the last edit it answers ({@code answered}) and that edit's refusal (empty for none). */
        void send(V viewer, MachineView view, int answered, String refusal);
    }

    private final Host<V> host;
    private final PresentationMailbox<V> mailbox = new PresentationMailbox<>();
    private final Map<V, MachineView> sent = new HashMap<>();
    /** Each session's last edit received, its last edit answered, and the refusal of the edit received. */
    private final Map<V, Integer> received = new HashMap<>(), answered = new HashMap<>();
    private final Map<V, String> refusals = new HashMap<>();

    public MachinePresentation(Host<V> host) {
        this.host = host;
    }

    /** A page opened: the session subscribes and gets its part's view now, if the trains hold it. */
    public void open(V viewer, long now) {
        mailbox.subscribe(viewer, now);
        var view = host.view(viewer);
        if (view == null) return;
        host.send(viewer, view, 0, "");
        sent.put(viewer, view);
        sentAnswer.put(viewer, 0);
    }

    /**
     * An edit numbered {@code sequence}: {@code queue} checks it and queues its shaft event, returning the refusal's reason or
     * null; the session's next bucket answers it.
     */
    public void edit(V viewer, long now, int sequence, Supplier<String> queue) {
        String refusal = queue.get();
        received.put(viewer, sequence);
        refusals.put(viewer, refusal == null ? "" : refusal);
        mailbox.submit(viewer, now, () -> answered.put(viewer, received.getOrDefault(viewer, 0)));
    }

    /** A page closed: the session ends; an edit it queued still applies. */
    public void close(V viewer) {
        mailbox.remove(viewer);
        sent.remove(viewer);
        received.remove(viewer);
        answered.remove(viewer);
        refusals.remove(viewer);
        sentAnswer.remove(viewer);
    }

    /** The engine's tick: on a bucket, answers the edits, then sends each session its view if it changed or answers one. */
    public void tick(long now) {
        if (now < mailbox.nextTick()) return;
        mailbox.tick(now, viewer -> {
            if (host.valid(viewer)) return true;
            close(viewer);
            return false;
        }, viewer -> {
            var view = host.view(viewer);
            int answer = answered.getOrDefault(viewer, 0);
            boolean answers = answer != sentAnswer.getOrDefault(viewer, 0);
            if (view == null || !answers && view.equals(sent.get(viewer))) return;
            // The refusal of the edit answered stays with every later view, until the next edit's answer.
            host.send(viewer, view, answer, answer == 0 ? "" : refusals.getOrDefault(viewer, ""));
            sent.put(viewer, view);
            sentAnswer.put(viewer, answer);
        });
    }

    /** The answer each session was last sent. */
    private final Map<V, Integer> sentAnswer = new HashMap<>();

    /** Open sessions. */
    public int sessions() {
        return mailbox.size();
    }
}
