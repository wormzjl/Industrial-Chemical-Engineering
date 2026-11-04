package com.wormzjl.icheme.science.fluid.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A shaft train's slice input to one connection (batch 2026-11-03-rotating-machinery, W3, build plan section 2): read on
 * the server at dispatch, as a heat side's read is, and applied to the graph the slice solves. Keyed by the actuator's
 * device identity, the id of the node its connection leaves. {@code SPEED}: a machine's share of its rated speed;
 * {@code DISPLACEMENT}: a reciprocating frame's displacement at its train's speed, m³/s (reading A32); {@code GOVERNOR}: a
 * governor valve's mass target, kg/s (owner decision D27).
 */
public record ShaftPlan(Kind kind, double value) {
    public enum Kind { SPEED, DISPLACEMENT, GOVERNOR }

    public ShaftPlan {
        java.util.Objects.requireNonNull(kind);
        if (!(value >= 0) || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid shaft plan " + value);
    }

    /**
     * {@code graph} with each planned connection at its plan: a machine at its speed, a frame at its displacement (within
     * {@link FlowControl.Mover#MAXIMUM_VOLUME_FLOW}), a governor valve at its mass target. A governor target inside
     * {@link PassiveStepSolver#VALVE_DEADBAND} of the valve's own keeps the valve's (a steady island stays certified,
     * reading A26). Pure: no solve, no state.
     */
    public static PassiveNetwork apply(PassiveNetwork graph, Map<Long, ShaftPlan> plans) {
        if (plans.isEmpty()) return graph;
        List<PassiveNetwork.Pipe> pipes = null;
        for (int e = 0; e < graph.pipes().size(); e++) {
            var pipe = graph.pipes().get(e);
            var plan = plans.get(graph.reservoirs().get(pipe.first()).id());
            if (plan == null) continue;
            FlowControl next = switch (pipe.control()) {
                case FlowControl.Machine machine when plan.kind == Kind.SPEED -> machine.withSpeed(plan.value);
                case FlowControl.Compressor compressor when plan.kind == Kind.DISPLACEMENT -> {
                    double flow = Math.min(FlowControl.Mover.MAXIMUM_VOLUME_FLOW, plan.value);
                    yield flow == compressor.requestedVolumeFlow() ? compressor : new FlowControl.Compressor(flow, compressor.maximumPressureRatio(), compressor.enabled());
                }
                case FlowControl.ControlValve valve when plan.kind == Kind.GOVERNOR && valve.source() == FlowControl.ValveSource.SHAFT_SPEED ->
                        Math.abs(plan.value - valve.massTarget()) <= PassiveStepSolver.VALVE_DEADBAND * Math.max(plan.value, valve.massTarget()) + 1e-9
                                ? valve : valve.withMassTarget(plan.value);
                default -> pipe.control();
            };
            if (next == pipe.control()) continue;
            if (pipes == null) pipes = new ArrayList<>(graph.pipes());
            pipes.set(e, pipe.withControl(next));
        }
        return pipes == null ? graph : new PassiveNetwork(graph.reservoirs(), pipes, graph.scheduledTransfers());
    }

    /** Whether {@code solved} is {@code dispatched} with only a shaft plan changed: a machine's speed, a frame's displacement or a
     * governor valve's mass target. */
    public static boolean planned(FlowControl dispatched, FlowControl solved) {
        return switch (dispatched) {
            case FlowControl.Machine a when solved instanceof FlowControl.Machine b -> a.sameButSpeed(b);
            case FlowControl.Compressor a when solved instanceof FlowControl.Compressor b -> a.maximumPressureRatio() == b.maximumPressureRatio() && a.enabled() == b.enabled();
            case FlowControl.ControlValve a when solved instanceof FlowControl.ControlValve b -> a.source() == FlowControl.ValveSource.SHAFT_SPEED && b.source() == a.source()
                    && a.targetPressure() == b.targetPressure() && a.volumeTarget() == b.volumeTarget() && java.util.Objects.equals(a.sensor(), b.sensor()) && a.revision() == b.revision();
            default -> false;
        };
    }
}
