package com.wormzjl.icheme.science.flowsheet;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A unit on its sheet (batch 2026-11-02-process-simulator, plan section 3.2): its settings, the stream on each wired port and
 * the run's thermo. A free outlet writes a stream of its own, which leaves the sheet, so mass closes and the run says so.
 *
 * @param wires     the stream on each wired port
 * @param passages  its sized passages' laws (D19); {@link Passages#TYPED} while it has no size
 * @param utilities the feeds whose flow it sets, and what it reads to set them
 * @param last      what its block's solve left in the steady run this one starts from, or null: a column starts where it ended
 */
public record WiredUnit(String id, Params params, Map<String, String> wires, FlowThermo thermo, Passages passages, Utilities utilities, @Nullable Report last) {
    /** A sized passage's drop is found again while it moves by more than this, Pa: a thousandth of a bar. */
    private static final double SAME_DROP = 100;
    /** At most this often: the coils' drop rises with the vapour a lower outlet pressure gives, and settles in a few. */
    private static final int MOST_TRIES = 20;

    /**
     * The sheet feeds of water alone on the unit's {@linkplain Port#utility utility} ports, by port, whose flow the unit sets
     * ({@code COOLER_MODEL_PLAN.md} section 8): the run gives them no source of their own. A pumparound's return is its draw less
     * {@code pumparoundFall} (K), the sheet's design basis, as sizing reads it: the column model has no circulation.
     */
    public record Utilities(Map<String, Flowsheet.Feed> feeds, double pumparoundFall) {
        public static final Utilities NONE = new Utilities(Map.of(), com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis.DEFAULT.paFall());

        public Utilities {
            feeds = Map.copyOf(feeds);
        }
    }

    /**
     * What {@code in} loses through {@code passage}, Pa: its sized law at the run's flow, read at the inlet, else the door's
     * {@code typed} estimate. A sized drop is a reading of the report, under the passage's name.
     */
    public double drop(String passage, FlowStream in, double typed, Report report) {
        var law = passages.passage(passage);
        if (law == null || in.empty()) return typed;
        double drop = law.drop(in, null);
        report.reading(passage, drop);
        return drop;
    }

    /**
     * What leaves {@code passage}: {@code outlet} at the pressure {@code in} keeps after it. A sized law that reads its outlet is
     * found from the door's estimate, the outlet at each drop giving the next, until it settles.
     */
    public FlowStream leaving(String passage, FlowStream in, double typed, java.util.function.DoubleFunction<FlowStream> outlet, Report report) {
        var law = passages.passage(passage);
        if (law == null || !law.readsOutlet() || in.empty()) return outlet.apply(after(in, drop(passage, in, typed, report)));
        double drop = typed;
        var out = outlet.apply(after(in, drop));
        for (int tries = 0; tries < MOST_TRIES; tries++) {
            double next = law.drop(in, out);
            boolean settled = Math.abs(next - drop) <= SAME_DROP;
            drop = next;
            out = outlet.apply(after(in, drop));
            if (settled) break;
        }
        report.reading(passage, drop);
        return out;
    }

    /** The pressure {@code in} keeps after losing {@code drop} Pa; none left is no state. */
    public static double after(FlowStream in, double drop) {
        double pressure = in.pressure() - drop;
        if (!(pressure > 0)) throw new FlowsheetFailure("unit.pressure");
        return pressure;
    }

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
