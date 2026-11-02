package com.wormzjl.icheme.science.flowsheet;

import java.util.List;
import java.util.Map;

/**
 * What a run leaves (batch 2026-11-02-process-simulator, plan section 3.1): results, which are state. What was solved stays,
 * so a failed or stopped run shows its part, and a loop that did not close shows its last pass.
 *
 * @param streams   each solved stream
 * @param outcomes  how each calculation ended
 * @param reports   each solved calculation's last report
 * @param loops     each loop, in run order
 * @param log       the run's notes, in order
 * @param massIn    what entered the sheet (kg/s)
 * @param massOut   what left it (kg/s)
 * @param heatIn    enthalpy that entered and the duties (W)
 * @param heatOut   enthalpy that left (W)
 * @param duties    the size of the duties, the heat gap's scale (W)
 */
public record FlowsheetRun(Status status, Map<String, FlowStream> streams, Map<String, Outcome> outcomes, Map<String, Report> reports, List<Loop> loops, List<Note> log,
                           double massIn, double massOut, double heatIn, double heatOut, double duties) {
    public enum Status {
        /** Every tear stopped moving and mass closes. */
        STEADY,
        /** A loop did not close, or mass does not: the last pass stays on show. */
        NOT_STEADY,
        /** A unit failed: what lies after it was not solved. */
        FAILED,
        /** The player pressed Stop. */
        STOPPED
    }

    public enum Outcome {
        SOLVED,
        FAILED,
        /** A unit upstream failed, or the run stopped first. */
        NOT_SOLVED
    }

    /** A loop's tears, the passes it took and whether it closed. */
    public record Loop(List<String> tears, int passes, boolean closed) {
        public Loop {
            tears = List.copyOf(tears);
        }
    }

    public FlowsheetRun {
        streams = Map.copyOf(streams);
        outcomes = Map.copyOf(outcomes);
        reports = Map.copyOf(reports);
        loops = List.copyOf(loops);
        log = List.copyOf(log);
    }

    /** A run that never started: {@code why} says what the sheet asks of the player first. */
    static FlowsheetRun refused(Note why) {
        return new FlowsheetRun(Status.FAILED, Map.of(), Map.of(), Map.of(), List.of(), List.of(why), 0, 0, 0, 0, 0);
    }

    public double massGap() {
        return massIn - massOut;
    }

    public double heatGap() {
        return heatIn - heatOut;
    }
}
