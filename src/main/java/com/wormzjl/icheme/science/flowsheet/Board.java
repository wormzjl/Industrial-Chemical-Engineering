package com.wormzjl.icheme.science.flowsheet;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a run knows so far (batch 2026-11-02-process-simulator, plan section 3.1): each stream's state, and each link's value.
 * A link is a number one calculation hands another of the same unit: the duty an exchanger's outlet set on one side, which the
 * other side takes. Laws read what their calculation names and write what it names.
 */
public final class Board {
    private final Map<String, FlowStream> streams = new LinkedHashMap<>();
    private final Map<String, Double> links = new HashMap<>();

    public boolean has(String stream) {
        return streams.containsKey(stream);
    }

    public FlowStream stream(String id) {
        var stream = streams.get(id);
        if (stream == null) throw new IllegalStateException("No state for stream " + id);
        return stream;
    }

    public void stream(String id, FlowStream state) {
        streams.put(id, state);
    }

    public double link(String id) {
        var value = links.get(id);
        if (value == null) throw new IllegalStateException("No value for link " + id);
        return value;
    }

    public void link(String id, double value) {
        links.put(id, value);
    }

    Map<String, FlowStream> streams() {
        return Map.copyOf(streams);
    }
}
