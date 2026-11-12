package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.flowsheet.EjectorLaw;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PassiveStepSolver;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.SteamEjector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * The steam ejectors' slice inputs (batch 2026-11-10-steam-ejector-flow, EJECTOR_FLOW_PLAN.md "Correlation form"): once per
 * slice each set's motive valve gets the steam the sheet's law ({@link EjectorLaw#steam}) asks for the load its curve holds
 * at its suction's state, and its curve the share of the steam its nozzle got over the last booked slice. Steam that is not
 * there (a shut motive line, condensate, a header at or below the discharge) leaves the set no capacity. A read of the
 * graph and the accepted rows, no solve; it creates nothing and is not saved (a restored island's graph carries its last
 * slice inputs).
 */
final class Ejectors implements IslandCoordinator.Ports {
    /** Below this density the motive is steam, kg/m³ ({@link Noise#GAS_DENSITY}): above it, condensate, no motive. */
    static final double STEAM_DENSITY = Noise.GAS_DENSITY;
    /** By island: its own model (its compound list, its water's vapour pressure). */
    private final LongFunction<FluidThermodynamics> models;
    /** Each ejector's last booked slice, by its physical identity: the steam its nozzle got and the steam asked, kg/s. */
    private final Map<Long, double[]> booked = new HashMap<>();

    Ejectors(LongFunction<FluidThermodynamics> models) {
        this.models = Objects.requireNonNull(models);
    }

    @Override public boolean ready(long island, PassiveNetwork graph, long start) {
        return true;
    }

    @Override public Map<Long, com.wormzjl.icheme.science.fluid.network.PortPlan> plans(long island, PassiveNetwork graph, IslandClock.Slice slice) {
        return Map.of();
    }

    /** The steam each ejector of the booked island asked, and what its nozzle passed (none backwards, none of a liquid). */
    @Override public void booked(long island, PassiveNetwork graph, long start, long end, PassiveIntervalSolver.Result result) {
        Map<Long, PassiveStepSolver.PipeObservation> rows = null;
        for (var pipe : graph.pipes()) {
            if (!(pipe.control() instanceof FlowControl.ControlValve valve) || valve.source() != FlowControl.ValveSource.EJECTOR) continue;
            if (rows == null) {
                rows = new HashMap<>();
                for (var o : result.observations()) rows.put(o.pipe(), o);
            }
            var row = rows.get(pipe.id());
            double steam = row != null && row.flowing() && row.massFlow() > 0 && row.density() > 0 && row.density() < STEAM_DENSITY ? row.massFlow() : 0;
            booked.put(motivePhysical(pipe.id()), new double[]{steam, valve.massTarget()});
        }
    }

    /** Each ejector's steam and share for the slice, kept where they moved less than {@link PassiveStepSolver#VALVE_DEADBAND}. */
    @Override public PassiveNetwork dispatch(long island, PassiveNetwork graph, IslandClock.Slice slice) {
        List<PassiveNetwork.Pipe> pipes = null;
        Map<Long, Integer> index = null;
        FluidThermodynamics model = null;
        for (int e = 0; e < graph.pipes().size(); e++) {
            var pipe = graph.pipes().get(e);
            if (!(pipe.control() instanceof FlowControl.Ejector ejector)) continue;
            if (index == null) {
                index = new HashMap<>();
                for (int k = 0; k < graph.pipes().size(); k++) index.put(graph.pipes().get(k).id(), k);
            }
            long id = physical(pipe.id());
            Integer m = index.get(PhysicalFluidTopology.motiveIdentity(id));
            if (m == null) continue;
            var nozzle = graph.pipes().get(m);
            if (!(nozzle.control() instanceof FlowControl.ControlValve valve)) continue;
            if (model == null) model = models.apply(island);
            var motive = graph.reservoirs().get(nozzle.first()).state();
            var suction = graph.reservoirs().get(pipe.first()).state();
            double discharge = graph.reservoirs().get(pipe.second()).state().pressure(), pm = motive.pressure();
            boolean steam = motive.volume() > 0 && motive.mass() / motive.volume() < STEAM_DENSITY && pm > discharge;
            var last = booked.get(id);
            double share = !steam ? 0 : last == null || !(last[1] > 0) ? 1 : Math.min(1, last[0] / last[1]);
            double target = steam ? steam(model, ejector.design(), suction, pm, discharge) : 0;
            if (near(share, ejector.delivered()) && near(pm, ejector.motivePressure()) && near(target, valve.massTarget())) continue;
            if (pipes == null) pipes = new ArrayList<>(graph.pipes());
            pipes.set(e, pipe.withControl(ejector.withMotive(pm, share)));
            pipes.set(m, nozzle.withControl(valve.withMassTarget(target)));
        }
        return pipes == null ? graph : new PassiveNetwork(graph.reservoirs(), pipes, graph.scheduledTransfers());
    }

    /**
     * The steam, kg/s, the sheet's law asks for the load the set's full curve holds at its suction's state: the gas as air
     * component by component, the water vapour on its own, condensing at twice its vapour pressure (as the sheet's
     * {@code EjectorUnit} does).
     */
    static double steam(FluidThermodynamics model, SteamEjector design, FluidThermodynamics.State suction, double motive, double discharge) {
        double ps = suction.pressure(), q = design.capacity(ps, motive, 1);
        double volume = suction.vaporVolume() > 0 ? suction.vaporVolume() : suction.volume();
        if (!(q > 0) || !(volume > 0)) return 0;
        var weights = model.molecularWeights();
        double gas = 0;
        var vapor = suction.vaporAmountsView();int w = vapor.length - 1;
        for (int i = 0; i < w; i++) gas += EjectorLaw.asAir(vapor[i] * weights[i] / volume * q, 1e3 * weights[i]);
        double water = vapor[w] * model.waterMolecularWeight / volume * q;
        double condensing = EjectorLaw.CONDENSING * model.saturationPressure(suction.temperature());
        return EjectorLaw.steam(gas, water, motive, ps, Math.max(discharge, ps), condensing);
    }

    private static boolean near(double a, double b) {
        return (a > 0) == (b > 0) && Math.abs(a - b) <= PassiveStepSolver.VALVE_DEADBAND * Math.max(a, b);
    }

    /** The physical identity of an ejector's compiled mover ({@link PhysicalFluidTopology#ejectorIdentity}). */
    static long physical(long pipe) {
        return pipe - PhysicalFluidTopology.ejectorIdentity(0);
    }

    /** The physical identity of an ejector's compiled motive valve ({@link PhysicalFluidTopology#motiveIdentity}). */
    static long motivePhysical(long pipe) {
        return pipe - PhysicalFluidTopology.motiveIdentity(0);
    }
}
