package com.wormzjl.icheme.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Render thread only: uploaded meshes by key (batch 2026-11-09-lod-rendering, A4). Past its soft size it closes only
 * meshes no frame has drawn for a second, least recently drawn first, so a view that holds more meshes than the soft size
 * never closes and rebuilds a mesh every frame. Past the hard size it closes the least recently drawn regardless.
 */
final class VertexBufferCache<K> {
    private static final long STALE_NANOS = 1_000_000_000L;

    private static final class Entry {
        final VertexBuffer buffer;
        long drawn;

        Entry(VertexBuffer buffer, long drawn) {
            this.buffer = buffer;
            this.drawn = drawn;
        }
    }

    private final Map<K, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final int soft, hard;

    VertexBufferCache(int soft, int hard) {
        if (soft < 1 || hard < soft) throw new IllegalArgumentException("Sizes");
        this.soft = soft;
        this.hard = hard;
    }

    /** The mesh of {@code key}, marked drawn now; null when none is kept. */
    @Nullable VertexBuffer get(K key) {
        var entry = entries.get(key);
        if (entry == null) return null;
        entry.drawn = System.nanoTime();
        return entry.buffer;
    }

    /** Keeps {@code buffer} for {@code key}, after closing what the sizes allow. */
    void put(K key, VertexBuffer buffer) {
        long now = System.nanoTime();
        if (entries.size() >= soft) {
            var oldest = entries.values().iterator();
            while (oldest.hasNext() && entries.size() >= soft) {
                var entry = oldest.next();
                if (now - entry.drawn < STALE_NANOS) break;
                entry.buffer.close();
                oldest.remove();
            }
        }
        var oldest = entries.values().iterator();
        while (entries.size() >= hard && oldest.hasNext()) {
            oldest.next().buffer.close();
            oldest.remove();
        }
        var replaced = entries.put(key, new Entry(buffer, now));
        if (replaced != null && replaced.buffer != buffer) replaced.buffer.close();
    }

    int size() {
        return entries.size();
    }

    void clear() {
        entries.values().forEach(e -> e.buffer.close());
        entries.clear();
    }
}
