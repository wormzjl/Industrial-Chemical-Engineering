package com.wormzjl.icheme.science.flowsheet;

import java.util.List;
import java.util.Objects;

/**
 * One node of a run's graph (batch 2026-11-02-process-simulator, plan section 3.1). The graph holds calculations, not tiles: a
 * block gives one for its own solve and one for each heat side whose duty is known, so the crude's pass through a pumparound
 * cooler reads the door's duty and is no loop. An exchanger set by one side's outlet splits the same way, with the duty as a
 * link from that side to the other.
 *
 * @param id     unique in the run, such as {@code T-101/PA 1}
 * @param unit   the unit it belongs to, which a failure names
 * @param reads  the streams it reads, which a loop may tear
 * @param writes the streams it writes
 * @param needs  the links it reads, which no loop tears
 * @param gives  the links it writes
 * @param heavy  a block's own solve: a loop tears at its outlet, so each pass feeds it a stream the pass has worked out
 */
public record Calculation(String id, String unit, List<String> reads, List<String> writes, List<String> needs, List<String> gives, boolean heavy, Law law) {
    /** The law of a calculation: it reads and writes through {@code board} and says what it found in {@code report}. */
    @FunctionalInterface
    public interface Law {
        void solve(Board board, Report report);
    }

    public Calculation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(law, "law");
        reads = List.copyOf(reads);
        writes = List.copyOf(writes);
        needs = List.copyOf(needs);
        gives = List.copyOf(gives);
    }

    /** A calculation of streams alone. */
    public static Calculation of(String id, List<String> reads, List<String> writes, Law law) {
        return new Calculation(id, id, reads, writes, List.of(), List.of(), false, law);
    }

    /** This calculation as a block's own solve. */
    public Calculation asHeavy() {
        return new Calculation(id, unit, reads, writes, needs, gives, true, law);
    }
}
