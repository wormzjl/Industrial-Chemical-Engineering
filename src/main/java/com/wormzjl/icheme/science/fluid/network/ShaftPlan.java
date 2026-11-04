package com.wormzjl.icheme.science.fluid.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A shaft train's slice input to one connection (batch 2026-11-03-rotating-machinery, W3, build plan section 2): read on
 * the server at dispatch, as a heat side's read is, and applied to the graph the slice solves. Keyed by the actuator's
 * device identity, the id of the node its connection leaves. {@code SPEED}: a machine's share of its rated speed;
 * {@code DISPLACEMENT}: a reciprocating frame's displacement at its train's speed, m³/s (reading A32); {@code GOVERNOR}: a
 * governor valve's mass target, kg/s (owner decision D27); {@code FANS} (W5): the most of full speed an air cooler's or
 * an induced-draft tower's fans may turn, 0 when its electric store ran empty, 1 otherwise (reading A61: the player's
 * fan setting and control stay as they were).
 */
public record ShaftPlan(Kind kind, double value) {
    public enum Kind { SPEED, DISPLACEMENT, GOVERNOR, FANS }

    public ShaftPlan {
        java.util.Objects.requireNonNull(kind);
        if (!(value >= 0) || !Double.isFinite(value) || kind == Kind.FANS && value > 1) throw new IllegalArgumentException("Invalid shaft plan " + value);
    }

    /**
     * {@code graph} with each planned connection at its plan: a machine at its speed, a frame at its displacement (within
     * {@link FlowControl.Mover#MAXIMUM_VOLUME_FLOW}), a governor valve at its mass target. A governor target inside
     * {@link PassiveStepSolver#VALVE_DEADBAND} of the valve's own keeps the valve's (a steady island stays certified,
     * reading A26). An air cooler's passage and a tower's basin take a {@code FANS} plan as their fan limit (W5). Pure: no
     * solve, no state.
     */
    public static PassiveNetwork apply(PassiveNetwork graph, Map<Long, ShaftPlan> plans) {
        if (plans.isEmpty()) return graph;
        List<PassiveNetwork.Pipe> pipes = null;
        List<PassiveNetwork.Reservoir> nodes = null;
        for (int n = 0; n < graph.reservoirs().size(); n++) {
            var node = graph.reservoirs().get(n);
            var plan = plans.get(node.id());
            var tower = node.inventory().tower();
            if (plan == null || plan.kind != Kind.FANS || tower == null || tower.fanLimit() == plan.value) continue;
            if (nodes == null) nodes = new ArrayList<>(graph.reservoirs());
            nodes.set(n, new PassiveNetwork.Reservoir(node.id(), node.elevation(), node.state(), node.kind(), node.inventory().withTower(tower.withFanLimit(plan.value))));
        }
        for (int e = 0; e < graph.pipes().size(); e++) {
            var pipe = graph.pipes().get(e);
            var plan = plans.get(graph.reservoirs().get(pipe.first()).id());
            if (plan == null) continue;
            if (plan.kind == Kind.FANS) {
                if (pipe.heat() instanceof AirCooled cooled && cooled.fanLimit() != plan.value) {
                    if (pipes == null) pipes = new ArrayList<>(graph.pipes());
                    pipes.set(e, pipe.withHeat(cooled.withFanLimit(plan.value)));
                }
                continue;
            }
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
        if (pipes == null && nodes == null) return graph;
        return new PassiveNetwork(nodes == null ? graph.reservoirs() : nodes, pipes == null ? graph.pipes() : pipes, graph.scheduledTransfers());
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
