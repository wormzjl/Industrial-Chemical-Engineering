package com.wormzjl.icheme.fluid.gametest;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator;
import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FiredCoil;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.plant.column.Port;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Integral control volume from the two radiant outlet faces to the column feed account. */
final class JunctionLagTrace {
    static final String HEADER="start_tick,end_tick,endpoint_full_firing,accepted,rejected,coil_kg,feed_kg,coil_j_per_kg,feed_j_per_kg,coil_at_feed_pressure_K,feed_K,feed_id";
    private record Stock(long island, long tick, long revision, Set<Long> nodes, double energy, double[] amounts) {}
    private Stock previous;

    void sample(FluidWorldAuthority world, PhysicalFluidTopology.Position master, List<IslandCoordinator.Snapshot> snapshots, PrintWriter out) {
        var column = world.column(master);
        if (column == null) return;
        var feed = column.station(Port.FEED, 0);
        if (feed == null) return;
        for (var snapshot : snapshots) {
            var result = snapshot.lastResult().orElse(null);
            if (result == null || previous != null && previous.island == snapshot.id() && previous.tick == snapshot.clock().committedTick()
                    && previous.revision == snapshot.revision() && previous.nodes.contains(feed.device())) continue;
            var graph = result.graph();
            for (int node = 0; node < graph.reservoirs().size(); node++) {
                if (graph.reservoirs().get(node).id() == feed.device()) {
                    observe(world, snapshot, graph, node, out);
                    return;
                }
            }
        }
    }

    private void observe(FluidWorldAuthority world, IslandCoordinator.Snapshot snapshot, PassiveNetwork graph, int feed, PrintWriter out) {
        var result = snapshot.lastResult().orElseThrow();
        var queue = new ArrayDeque<Integer>();
        var visited = new HashSet<Integer>();
        var coils = new HashSet<Long>();
        queue.add(feed);
        visited.add(feed);
        boolean full = true;
        double discharged = 0;
        while (!queue.isEmpty()) {
            int at = queue.remove();
            for (int e = 0; e < graph.pipes().size(); e++) {
                var pipe = graph.pipes().get(e);
                if (pipe.first() != at && pipe.second() != at) continue;
                if (pipe.heat() instanceof FiredCoil coil) {
                    var moved = result.pipeTransfers().get(e);
                    if (pipe.second() != at || moved.reverse().massKg() != 0) {
                        previous = null;
                        return;
                    }
                    discharged += moved.forward().massKg();
                    full &= coil.firing() == 1;
                    coils.add(pipe.id());
                    continue;
                }
                if (pipe.heat() != null || pipe.filter() != null || pipe.control() instanceof FlowControl.Mover
                        || pipe.control() instanceof FlowControl.Turbine) { previous=null; return; }
                int next = pipe.first() == at ? pipe.second() : pipe.first();
                if (visited.add(next)) {
                    if (!graph.reservoirs().get(next).junction()) { previous=null; return; }
                    queue.add(next);
                }
            }
        }
        if (coils.isEmpty()) { previous=null; return; }
        var model = world.diagnosticModel(snapshot.id());
        var amounts = new double[model.componentCount()];
        var ids = new HashSet<Long>();
        double energy = 0;
        for (int i : visited) {
            var n = graph.reservoirs().get(i);
            ids.add(n.id());
            if (i == feed) continue;
            var inv = n.inventory();
            if (!inv.solids().empty()) { previous=null; return; }
            energy += inv.internalEnergy();
            var mol = inv.moles();
            for (int c=0;c<mol.length;c++) amounts[c]+=mol[c];
        }
        var now = new Stock(snapshot.id(), snapshot.clock().committedTick(), snapshot.revision(), ids, energy, amounts);
        var before=previous;
        previous=now;
        if (before==null || before.island!=now.island || before.tick>=now.tick || before.revision!=now.revision || !before.nodes.equals(ids)
                || Math.abs(model.time().seconds(now.tick-before.tick)-result.advancedSeconds())>1e-8) return;
        var taken = new double[amounts.length];
        double received = 0;
        for (var transfer : result.boundaries()) if (transfer.nodeId()==graph.reservoirs().get(feed).id()
                && transfer.kind()==ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE) {
            if (!transfer.solids().empty()) return;
            received-=transfer.totalEnergyJoule();
            var n=transfer.moles();
            for (int c=0;c<n.length;c++) taken[c]-=n[c];
        }
        var passed=taken.clone();
        for(int c=0;c<passed.length;c++) passed[c]+=amounts[c]-before.amounts[c];
        double kg=mass(model,taken), inKg=mass(model,passed);
        if (!(kg > 0 && inKg > 0 && discharged > 0)) return;
        double inletEnergy=received+(energy-before.energy);
        double p=graph.reservoirs().get(feed).state().pressure();
        double seed = graph.reservoirs().get(feed).state().temperature();
        var coil = model.fluidPH(seed, p, inletEnergy, passed, () -> {});
        var column = model.fluidPH(seed, p, received, taken, () -> {});
        if (coil == null || column == null) return;
        out.printf(Locale.ROOT,"%d,%d,%s,%d,%d,%.9g,%.9g,%.9g,%.9g,%.6f,%.6f,%d%n",before.tick,now.tick,full,
                result.acceptedSubsteps(),result.rejectedSubsteps(),inKg,kg,inletEnergy/inKg,received/kg,coil.temperature(),column.temperature(),graph.reservoirs().get(feed).id());
        out.flush();
    }

    private static double mass(FluidThermodynamics model,double[] moles) {
        double kg=0;
        for(int c=0;c<moles.length;c++) kg+=model.molecularWeight(c)*moles[c];
        return kg;
    }
}
