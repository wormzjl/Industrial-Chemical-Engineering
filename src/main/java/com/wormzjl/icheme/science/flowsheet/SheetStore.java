package com.wormzjl.icheme.science.flowsheet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * What a process simulator block holds of its sheet (batch 2026-11-02-process-simulator, plan section 3.4): the last body it took, what it could
 * not read, and who edits. The first player to open edits; another reads. The holder is state and is not saved. The body is a fact, but a store never
 * refuses a world: one it cannot read opens empty, says so, and is kept for a later version, even under a new sheet. It stores and never solves:
 * nothing here reaches a solver, and reading a body is parsing it. Minecraft-free; the block entity keeps it in its tag.
 */
public final class SheetStore {
    private static final byte[] NOTHING = new byte[0];
    private byte[] body = NOTHING;
    private final List<byte[]> kept = new ArrayList<>();
    private @Nullable UUID holder;
    /** Whether {@link #body} has been parsed since it was loaded: a store reads it once, and only when someone opens the block. */
    private boolean read;

    /** The body as saved, or empty. */
    public byte[] body() {
        return body;
    }

    /** Bodies no version so far could read, oldest first. */
    public List<byte[]> kept() {
        return List.copyOf(kept);
    }

    /** Takes what a block saved. Nothing is parsed: a load does no work and refuses nothing. */
    public void load(byte[] saved, List<byte[]> keptSaved) {
        body = saved;
        kept.clear();
        kept.addAll(keptSaved);
        read = false;
    }

    /** Whether this player edits, once they open the block: they do if no one still holds it, and {@code present} says who still does. */
    public boolean open(UUID player, Predicate<UUID> present) {
        if (holder == null || !present.test(holder)) holder = player;
        return holder.equals(player);
    }

    public @Nullable UUID holder() {
        return holder;
    }

    /** {@code player} left. A reader holds nothing, so only the holder frees the sheet. */
    public void release(UUID player) {
        if (player.equals(holder)) holder = null;
    }

    /** The block is gone. */
    public void free() {
        holder = null;
    }

    /** The body an opening replays: empty if a body this version cannot read has just been set aside. A read: it solves nothing. */
    public byte[] replay() {
        if (read || body.length == 0) return body;
        read = true;
        if (!readable(body)) setAside();
        return body;
    }

    /** Whether the block stands empty over a body it could not read. */
    public boolean damaged() {
        return body.length == 0 && !kept.isEmpty();
    }

    /** Takes a body from {@code player}, or refuses it and leaves the store as it stands. */
    public void accept(UUID player, byte[] packed, String readOnly) throws SheetBody.Refused {
        if (!player.equals(holder)) throw new SheetBody.Refused(readOnly);
        var contents = SheetBody.unpack(packed);
        try {
            FlowsheetFile.read(contents.sheet());
        } catch (FlowsheetFailure refused) {
            throw new SheetBody.Refused(refused.key());
        }
        if (!read && body.length > 0 && !readable(body)) setAside();
        body = packed;
        read = true;
    }

    private void setAside() {
        kept.add(body);
        body = NOTHING;
    }

    private static boolean readable(byte[] stored) {
        try {
            FlowsheetFile.read(SheetBody.unpack(stored).sheet());
            return true;
        } catch (SheetBody.Refused | FlowsheetFailure unreadable) {
            return false;
        }
    }
}
