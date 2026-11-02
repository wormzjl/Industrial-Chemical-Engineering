package com.wormzjl.icheme.science.flowsheet;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A unit on its sheet (batch 2026-11-02-process-simulator, plan section 3.2): its settings, the stream on each wired port and
 * the run's thermo. A free outlet writes a stream of its own, which leaves the sheet, so mass closes and the run says so.
 *
 * @param wires the stream on each wired port
 */
public record WiredUnit(String id, Params params, Map<String, String> wires, FlowThermo thermo) {
    /** The stream on {@code port}, null if the port is free. */
    public @Nullable String wire(String port) {
        return wires.get(port);
    }

    /** The stream an outlet writes. */
    public String out(String port) {
        var wired = wires.get(port);
        return wired == null ? id + ":" + port : wired;
    }

    /** Writes an outlet; a free one that carries flow leaves a note. */
    public void write(Board board, Report report, String port, FlowStream stream) {
        if (wire(port) == null && !stream.empty()) report.note(Note.warning(id, "port.free_outlet", port));
        board.stream(out(port), stream);
    }

    /** The calculation of a unit with one law. */
    public Calculation calculation(List<String> reads, List<String> writes, Calculation.Law law) {
        return new Calculation(id, id, reads, writes, List.of(), List.of(), Calculation.Kind.STEP, law);
    }

    /** A law that reads one stream and passes no flow on as no flow. */
    public Calculation through(String in, String out, Calculation.Law law) {
        return calculation(List.of(wires.get(in)), List.of(out(out)), (board, report) -> {
            var stream = board.stream(wires.get(in));
            if (stream.empty()) board.stream(out(out), stream);
            else law.solve(board, report);
        });
    }

    /** {@code id/name}, for a link or a part of this unit. */
    public String part(String name) {
        return id + "/" + name;
    }
}
