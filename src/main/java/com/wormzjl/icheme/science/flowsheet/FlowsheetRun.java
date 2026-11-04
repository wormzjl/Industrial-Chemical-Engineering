package com.wormzjl.icheme.science.flowsheet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

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
 * @param order     the run's order: what the solver planned, ratings left out
 * @param passes    what each pass of each loop did, in run order
 */
public record FlowsheetRun(Status status, Map<String, FlowStream> streams, Map<String, Outcome> outcomes, Map<String, Report> reports, List<Loop> loops, List<Note> log,
                           double massIn, double massOut, double heatIn, double heatOut, double duties, List<Stage> order, List<Pass> passes) {
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

    /** One step of the order: one calculation, or a loop's in pass order with its tears. */
    public record Stage(List<String> calculations, List<String> tears) {
        public Stage {
            calculations = List.copyOf(calculations);
            tears = List.copyOf(tears);
        }
    }

    /**
     * What one calculation did in a pass.
     *
     * @param duty    its duty (W), NaN if it has none
     * @param start   where a block's solve set out from, or null
     * @param seconds how long it took
     */
    public record Step(String calculation, String unit, double duty, Report.@Nullable Start start, double seconds, boolean failed) {
    }

    /**
     * One pass of a loop.
     *
     * @param tears       the loop's tears, which say whose pass this is
     * @param pass        from 1
     * @param steps       what ran, up to what failed or to Stop
     * @param seconds     how long the pass took
     * @param tear        the tear that moved most, or null if the pass did not come round
     * @param moved       the largest component's move as a share of the flow, NaN with no two states to compare
     * @param temperature that tear's temperature as it came out (K), NaN with no flow
     */
    public record Pass(List<String> tears, int pass, List<Step> steps, double seconds, @Nullable String tear, double moved, double temperature) {
        public Pass {
            tears = List.copyOf(tears);
            steps = List.copyOf(steps);
        }
    }

    public FlowsheetRun {
        streams = Map.copyOf(streams);
        outcomes = Map.copyOf(outcomes);
        reports = Map.copyOf(reports);
        loops = List.copyOf(loops);
        log = List.copyOf(log);
        order = List.copyOf(order);
        passes = List.copyOf(passes);
    }

    /** A run that never started: {@code why} says what the sheet asks of the player first. */
    public static FlowsheetRun refused(Note why) {
        return new FlowsheetRun(Status.FAILED, Map.of(), Map.of(), Map.of(), List.of(), List.of(why), 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** A run that Stop met before its first calculation. */
    static FlowsheetRun stopped() {
        return new FlowsheetRun(Status.STOPPED, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** No run yet, with nothing to its name: what a unit solved alone lies over on a sheet that never ran. */
    public static FlowsheetRun none() {
        return new FlowsheetRun(Status.NOT_STEADY, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** This run with {@code alone}, one unit's solve on its own, laid over it. The sheet's status, loops and gaps are a run's to say, so they stand. */
    public FlowsheetRun with(FlowsheetRun alone) {
        var streams = new HashMap<>(this.streams);
        streams.putAll(alone.streams);
        var outcomes = new HashMap<>(this.outcomes);
        outcomes.putAll(alone.outcomes);
        var reports = new HashMap<>(this.reports);
        reports.putAll(alone.reports);
        return new FlowsheetRun(status, streams, outcomes, reports, loops, log, massIn, massOut, heatIn, heatOut, duties, order, passes);
    }

    /** This run without its columns' warm starts ({@link Report.Warm}): a run that starts from it starts its loops here, its columns from the learned start. */
    public FlowsheetRun cold() {
        if (this.reports.values().stream().allMatch(report -> report.warm() == null)) return this;
        var reports = new HashMap<String, Report>();
        this.reports.forEach((id, report) -> reports.put(id, report.cold()));
        return new FlowsheetRun(status, streams, outcomes, reports, loops, log, massIn, massOut, heatIn, heatOut, duties, order, passes);
    }

    public double massGap() {
        return massIn - massOut;
    }

    public double heatGap() {
        return heatIn - heatOut;
    }
}
