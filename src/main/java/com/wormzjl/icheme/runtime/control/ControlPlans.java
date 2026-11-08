package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.fluid.network.*;

import java.util.*;

/**
 * The setpoints the engine reads: each controlled valve's law and each heat unit's outlet target,
 * from the last cadence. A slice's graph takes them as immutable inputs; a device without a plan
 * keeps its own settings.
 */
final class ControlPlans {
    private final FluidWorldAuthority world;
    private Map<Long, FlowControl.ControlValve> valvePlans = Map.of();
    private Map<Long, Double> heatPlans = Map.of();

    ControlPlans(FluidWorldAuthority world) {
        this.world = world;
    }

    boolean isEmpty() {
        return valvePlans.isEmpty() && heatPlans.isEmpty();
    }

    /** The valve law the last cadence planned for {@code output}, or null. */
    FlowControl.ControlValve valve(long output) {
        return valvePlans.get(output);
    }

    /** The heat outlet target the last cadence planned for {@code output}, or null. */
    Double heat(long output) {
        return heatPlans.get(output);
    }

    /** Takes this cadence's setpoints and returns the outputs whose setpoint changed. */
    Set<Long> commit(Map<Long, FlowControl.ControlValve> nextValves, Map<Long, Double> nextHeat) {
        var changed = new HashSet<Long>(valvePlans.keySet());
        changed.addAll(nextValves.keySet());
        changed.removeIf(id -> Objects.equals(valvePlans.get(id), nextValves.get(id)));
        var thermalChanged = new HashSet<Long>(heatPlans.keySet());
        thermalChanged.addAll(nextHeat.keySet());
        thermalChanged.removeIf(id -> Objects.equals(heatPlans.get(id), nextHeat.get(id)));
        changed.addAll(thermalChanged);
        valvePlans = Map.copyOf(nextValves);
        heatPlans = Map.copyOf(nextHeat);
        return changed;
    }

    /**
     * Immutable slice inputs. The original device settings remain facts and resume when the
     * controller is off.
     */
    PassiveNetwork apply(PassiveNetwork graph) {
        List<PassiveNetwork.Pipe> changed = null;
        var registered = world.registrations();
        for (int e = 0; e < graph.pipes().size(); e++) {
            var pipe = graph.pipes().get(e);
            long host = graph.reservoirs().get(pipe.first()).id();
            var record = registered.get(host);
            var next = pipe;
            if (record != null
                    && record.device().control() instanceof FlowControl.ControlValve base
                    && pipe.control() instanceof FlowControl.ControlValve) {
                var law = valvePlans.getOrDefault(host, base);
                if (!pipe.control().equals(law)) next = next.withControl(law);
            }
            if (pipe.heat() != null) {
                long physical = pipe.id() - Long.MIN_VALUE / 2;
                var heatRecord = registered.get(physical);
                if (heatRecord != null && heatRecord.device().heat() != null) {
                    var base = heatRecord.device().heat();
                    var set = heatPlans.get(physical);
                    HeatControl control =
                            set == null
                                    ? base.controller()
                                    : new HeatControl(
                                            HeatControl.Target.OUTLET_TEMPERATURE,
                                            set,
                                            base.control().lowLimit(),
                                            base.fans()
                                                    ? base.control().share()
                                                    : 1 - base.control().share());
                    var law = pipe.heat();
                    if (law instanceof HeatSide side && !Objects.equals(side.control(), control))
                        next =
                                next.withHeat(
                                        new HeatSide(
                                                side.area(),
                                                side.films(),
                                                side.wall(),
                                                control == null && side.control() != null
                                                        ? base.control().share()
                                                        : side.bypass(),
                                                side.giveBack(),
                                                side.partner(),
                                                side.active(),
                                                control,
                                                side.limit(),
                                                side.routing()));
                    else if (law instanceof AirCooled air
                            && !Objects.equals(air.control(), control))
                        next =
                                next.withHeat(
                                        new AirCooled(
                                                air.area(),
                                                air.air(),
                                                control == null && air.control() != null
                                                        ? base.control().share()
                                                        : air.draught(),
                                                control,
                                                air.limit(),
                                                air.fanLimit()));
                }
            }
            if (next != pipe) {
                if (changed == null) changed = new ArrayList<>(graph.pipes());
                changed.set(e, next);
            }
        }
        return changed == null
                ? graph
                : new PassiveNetwork(graph.reservoirs(), changed, graph.scheduledTransfers());
    }
}
