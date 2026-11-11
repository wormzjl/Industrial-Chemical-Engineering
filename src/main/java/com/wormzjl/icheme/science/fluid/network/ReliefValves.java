package com.wormzjl.icheme.science.fluid.network;

import java.util.ArrayList;
import java.util.List;

/**
 * A pressure safety valve before a flow regulator (batch 2026-11-11-gas-blanketing, PSV_REVIEW.md section 4, decision D6,
 * form A2): the run from the valve to a flare's tip, a firebox's fuel valve or a pad, split at a middle junction. The valve
 * regulates its guarded pressure at its set pressure with its flow free; while the far end's limit binds it stands wide open
 * and the guarded pressure rises above its set pressure by the back pressure. Two regulators active at once would leave the
 * middle junction's pressure undetermined, so the interval solver plans the valve's state at each step, before the far end
 * can bind:
 * <ul>
 * <li>it lifts where its guarded node's inflow (every connection but the relief, over the step before) or its own flow reaches
 * the far end's limit less {@link #MARGIN}, with the guarded pressure within {@link #MARGIN} of the set pressure;</li>
 * <li>it reseats only below its set pressure, with both under the limit less the margin, or below the set pressure less the
 * margin (where a seated valve is shut).</li>
 * </ul>
 * A lifted valve's state is the graph's, carried across intervals as the graph is. Pure: no solve, no state of its own.
 */
public final class ReliefValves {
    /** The margin of the lift: a share of the far end's limit, and of the set pressure. */
    public static final double MARGIN = .02;

    private ReliefValves() {}

    /** {@code graph} with each such safety valve lifted or seated for the next step, from the step before's {@code flows} (null: as it stands). */
    public static PassiveNetwork plan(PassiveNetwork graph, double[] flows) {
        if (flows == null || flows.length != graph.pipes().size()) return graph;
        List<PassiveNetwork.Pipe> pipes = null;
        for (int e = 0; e < graph.pipes().size(); e++) {
            var pipe = graph.pipes().get(e);
            if (!(pipe.control() instanceof FlowControl.PressureValve valve) || !valve.planned()) continue;
            double limit = limit(graph, e);
            if (Double.isNaN(limit)) continue;
            int node = pipe.first();
            var guarded = graph.reservoirs().get(node);
            double inflow = 0;
            for (int f = 0; f < graph.pipes().size(); f++) {
                if (f == e) continue;
                var other = graph.pipes().get(f);
                if (other.second() == node) inflow += flows[f];
                else if (other.first() == node) inflow -= flows[f];
            }
            double pressure = guarded.empty() ? 0 : guarded.state().pressure(), set = valve.targetPressure();
            boolean binds = inflow > 0 && inflow >= (1 - MARGIN) * limit;
            // The far end binds, or is about to: the inflow, or the valve's own flow (a run rebuilt after an edit comes back
            // regulating), within the margin of its limit.
            boolean relieving = flows[e] >= (1 - MARGIN) * limit;
            boolean lifted = valve.lifted() ? binds || relieving || pressure >= set : binds && pressure >= (1 - MARGIN) * set;
            if (lifted == valve.lifted()) continue;
            if (pipes == null) pipes = new ArrayList<>(graph.pipes());
            pipes.set(e, pipe.withControl(valve.withLifted(lifted)));
        }
        return pipes == null ? graph : new PassiveNetwork(graph.reservoirs(), pipes, graph.scheduledTransfers());
    }

    /** The mass flow limit of the regulator at the far end of the relief run {@code e} (out of its middle junction); NaN without one. */
    private static double limit(PassiveNetwork graph, int e) {
        int middle = graph.pipes().get(e).second();
        for (int f = 0; f < graph.pipes().size(); f++) {
            if (f == e) continue;
            var other = graph.pipes().get(f);
            if (other.first() == middle && other.control() instanceof FlowControl.Valve far && !far.bare() && far.massControl()) return far.targetMassFlow(1);
        }
        return Double.NaN;
    }
}
