package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Island;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Replacement;
import com.wormzjl.icheme.runtime.fluid.IslandCoordinator.Snapshot;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/**
 * What a topology event does to the islands (codebase review R13; moved from {@link IslandCoordinator} unchanged):
 * the event fences it installs on the islands it touches, their alignment at its tick (a certified island by
 * materialisation, a held one by accepting the event at its fence), the release of a fence, and the replacement of
 * the aligned islands by the compiled ones for a compile, a join, a split or a removal, with every vessel's contents,
 * every filter's captured solids and the warm carry of an unchanged structure checked and kept. The islands, the
 * scheduler's queue and the indices stay the coordinator's. Runs on the coordinator's thread only, from its public
 * methods, which forward here.
 */
final class TopologyChange {
    private final IslandCoordinator coordinator;
    TopologyChange(IslandCoordinator coordinator){this.coordinator=coordinator;}

    void fence(UUID event,long tick,Collection<Long> affected) {
        coordinator.owned();Objects.requireNonNull(event);
        for(long id:affected) {
            var island=coordinator.require(id);
            if(tick<island.clock.committedTick()||coordinator.pending.values().stream().anyMatch(p->p.attempt.islandId()==id&&!p.closed&&p.attempt.slice().endTick()>tick))
                throw new IllegalStateException("Event would modify an admitted or committed interval");
            if(island.fences.containsKey(event))throw new IllegalStateException("Duplicate event fence");
        }
        for(long id:affected){var island=coordinator.require(id);island.putFence(event,tick);coordinator.fenceOwners.computeIfAbsent(event,ignored->new HashSet<>()).add(id);IslandCoordinator.inputsChanged(island);island.touch();coordinator.reconsider(island);}
    }
    boolean aligned(UUID event,Collection<Long> affected) {
        coordinator.owned();for(long id:affected){var island=coordinator.require(id);coordinator.certificates.materialise(island);acceptAtFence(island);}
        return affected.stream().allMatch(id->{var i=coordinator.require(id);var tick=i.fences.get(event);return tick!=null&&i.clock.committedTick()==tick&&i.pendingEntries==0;});
    }
    /** A batch's owners: each affected island holds a fence of at least one of the events, every one of them at its
     * committed tick, and runs no attempt; certified ones are materialised first, as for one event. */
    private boolean aligned(Set<UUID> events,Collection<Long> affected) {
        for(long id:affected){var island=coordinator.require(id);coordinator.certificates.materialise(island);acceptAtFence(island);}
        for(long id:affected) {
            var island=coordinator.require(id);if(island.pendingEntries!=0)return false;boolean fenced=false;
            for(var event:events){var tick=island.fences.get(event);if(tick==null)continue;if(tick!=island.clock.committedTick())return false;fenced=true;}
            if(!fenced)return false;
        }
        return true;
    }
    /**
     * A held owner accepts the event fenced on it at its committed tick (owner decision 2026-10-15): a held island
     * cannot solve its way to the fence, and a world change - a broken pipe, a new valve setting - is often the very
     * thing that frees it. Its inventory stands still over the unsolved span (no flow, so mass and energy are exact)
     * and the clock moves to the fence without a solve, as a certified island's materialisation does; the topology
     * change then replaces the island with a fresh one that starts its own full solve. Only an island whose last
     * attempt was held, with no attempt in flight, outside a suspension; anything else aligns by solving.
     */
    private void acceptAtFence(Island island) {
        if(!island.held||island.certificate!=null||island.pendingEntries!=0||island.clock.busy()||coordinator.suspension!=null)return;
        long committed=island.clock.committedTick(),fence=island.fence();
        if(fence==Long.MAX_VALUE||fence<=committed||fence>island.clock.onlineTick())return;
        island.clock.rest(fence,fence);island.held=false;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.heldEventAcceptances);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.droppedHeldTicks,fence-committed);
        island.touch();coordinator.reconsider(island);
    }
    void releaseFence(UUID event,Collection<Long> affected) {
        coordinator.owned();if(!aligned(event,affected))throw new IllegalStateException("Event owners have not aligned");
        for(long id:affected){var i=coordinator.require(id);coordinator.removeFence(i,event);IslandCoordinator.inputsChanged(i);i.touch();coordinator.reconsider(i);}
    }
    void topology(Set<UUID> events,Set<Long> affected,List<Replacement> replacements,FluidThermodynamics model,long committed,long online,Map<Long,PassiveNetwork.Reservoir> additions,Set<Long> removals,Map<Long,com.wormzjl.icheme.science.fluid.network.InlineFilter> releasedFilters,Runnable metadataCommit) {
        coordinator.owned();coordinator.timed(model);Objects.requireNonNull(metadataCommit);if(events.isEmpty())throw new IllegalArgumentException("A topology change needs its events");
        if(coordinator.stopped||committed<0||online<committed||!aligned(events,affected))throw new IllegalStateException("Topology event is not ready");
        var originals=affected.stream().map(coordinator::require).toList();
        long revision=0;int cadence=originals.isEmpty()?coordinator.settings.initialCadenceTicks():20;
        // Each original's stock with the model it is held on: islands carry their own compound lists (V2), so stock is compared by id.
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var stockModels=new HashMap<Long,FluidThermodynamics>();var fences=new HashMap<UUID,Long>();
        for(var old:originals) {
            if(old.clock.committedTick()!=committed||old.clock.snapshot().onlineTick()!=online||!old.model.whole().revision().equals(model.whole().revision()))throw new IllegalStateException("Incompatible clocks or property packages");
            revision=Math.max(revision,old.revision);cadence=Math.max(cadence,old.clock.snapshot().cadenceTicks());
            for(var node:old.graph.reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR){if(stock.putIfAbsent(node.id(),node)!=null)throw new IllegalStateException("Duplicate stock ownership");stockModels.put(node.id(),old.model);}
            for(var fence:old.fences.entrySet())if(!events.contains(fence.getKey())) {
                Long prior=fences.putIfAbsent(fence.getKey(),fence.getValue());if(prior!=null&&!prior.equals(fence.getValue()))throw new IllegalStateException("Conflicting event timestamps");
            }
        }
        for(long removed:removals)if(stock.remove(removed)==null)throw new IllegalStateException("Destruction did not name an owned reservoir");
        for(var entry:additions.entrySet()) {
            var node=entry.getValue();
            if(node.id()!=entry.getKey()||node.kind()!=PassiveNetwork.NodeKind.RESERVOIR||stock.putIfAbsent(node.id(),node)!=null||removals.contains(node.id()))throw new IllegalStateException("Invalid constructed stock identity");
            stockModels.put(node.id(),model);
            Long holder=coordinator.stockOwners.get(node.id());if(holder!=null&&!affected.contains(holder))throw new IllegalStateException("Constructed stock already exists");
        }
        revision=Math.addExact(revision,1);var seen=new HashSet<Long>();var ids=new HashSet<Long>();var staged=new ArrayList<Island>();
        for(var replacement:replacements) {
            if(!ids.add(replacement.id())||coordinator.islands.containsKey(replacement.id())&&!affected.contains(replacement.id()))throw new IllegalStateException("Replacement island identity collision");
            var own=replacement.model()==null?model:replacement.model();
            if(!own.whole().revision().equals(model.whole().revision()))throw new IllegalStateException("A replacement island runs on another property package");
            for(var node:replacement.graph().reservoirs()) {
                if(node.kind()==PassiveNetwork.NodeKind.PORT)throw new IllegalArgumentException("Internal port in topology");
                if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
                // What a vessel holds is conserved; where it stands and its geometry follow the registry (a layout refresh
                // rebuilds it, decision D7), and no ledger carries potential energy (D37, S1).
                var old=stock.get(node.id());
                if(!seen.add(node.id())||old==null||!sameContents(old.inventory(),stockModels.get(node.id()),node.inventory(),own))throw new IllegalStateException("Topology event changed stock");
            }
            // Owner decision of 2026-10-19 (t46's trace of bundled 483): a replacement whose graph is one original's (the same pipe
            // identities and node ids) carries that island's committed junction states and interval endpoint, the warm carry
            // across the replacement, so nothing of it is balanced anew: a plant does not notice a filter placed on a dead-end
            // line beyond its tank. The mint's states are discarded; each carried junction's buffer is sized on its carried
            // state by the mint's rule (t48's trace of bundled 145).
            PassiveNetwork graph=replacement.graph();Optional<PassiveIntervalSolver.Result> carried=Optional.empty();
            var same=originals.stream().filter(old->old.lastResult.isPresent()&&PassiveNetwork.sameLines(old.graph,replacement.graph())).toList();
            // The carry moves to the replacement's compound list by id (V2): its list holds the original's, so nothing is cut.
            if(same.size()==1){var old=same.get(0);graph=PassiveNetwork.withJunctionStates(replacement.graph(),IslandSets.graph(old.graph,old.model,own),own);carried=old.lastResult.map(r->IslandSets.result(r,old.model,own));}
            var island=new Island(new Snapshot(replacement.id(),revision,graph,new IslandClock.Snapshot(online,committed,0,cadence),carried,
                    replacement.held()==null?"WAITING: full solve after topology change":"HELD: "+replacement.held(),fences),own,coordinator.epoch);
            if(replacement.held()!=null){island.capability=replacement.held();island.held=true;}
            // A topology change starts the island cold: its first slice is one tick and each accepted full interval
            // doubles it back to the cadence (as after a budget hold). Whatever the player just built - a pump
            // filling dry tanks, a valve opening - starts its transient here, and its first milliseconds can cost
            // more than the wall budget: measured in game, 100 pumped fills placed at once spent their first 100 s
            // being cut at 100, 50, 25, 12, 6 and 3 ticks before a slice fitted, 2 s of work thrown away each time.
            // A quiet island pays six short solves instead. Islands registered from a save start at their cadence.
            island.maximumSliceTicks=1;
            staged.add(island);
        }
        if(!seen.equals(stock.keySet()))throw new IllegalStateException("Topology event lost reservoir ownership");
        var oldFilters=new HashMap<Long,com.wormzjl.icheme.science.fluid.network.InlineFilter>();for(var original:originals)for(var pipe:original.graph.pipes())if(pipe.filter()!=null)oldFilters.put(pipe.id(),pipe.filter());
        for(var release:releasedFilters.entrySet())if(!release.getValue().equals(oldFilters.get(release.getKey())))throw new IllegalStateException("Stale filter recovery");
        var seenFilters=new HashSet<Long>();for(var replacement:replacements)for(var pipe:replacement.graph().pipes())if(pipe.filter()!=null){
            if(!seenFilters.add(pipe.id()))throw new IllegalStateException("Duplicate filter ownership");var old=oldFilters.remove(pipe.id());
            if(old==null||releasedFilters.containsKey(pipe.id())){if(!pipe.filter().captured().empty()||pipe.filter().energyJoule()!=0)throw new IllegalStateException("Undeclared filter inventory creation");}
            else if(!old.captured().equals(pipe.filter().captured())||old.energyJoule()!=pipe.filter().energyJoule())throw new IllegalStateException("Topology changed filter inventory");
        }
        for(var removed:oldFilters.entrySet())if(!removed.getValue().captured().empty()&&!releasedFilters.containsKey(removed.getKey()))throw new IllegalStateException("Topology lost captured solids");
        metadataCommit.run();
        for(long id:affected){var old=coordinator.islands.remove(id);coordinator.ready.remove(id);coordinator.unindex(old);coordinator.unindexStock(old);old.deadline=Long.MAX_VALUE;old.generation=++coordinator.generations;}
        for(var island:staged){coordinator.islands.put(island.id,island);coordinator.ready.register(island.id,false);coordinator.index(island);coordinator.indexStock(island);coordinator.reconsider(island);}
        // A side no island holds any more keeps no relay state; its unit's heat in transit stays until the unit is removed.
        coordinator.coupled.keySet().removeIf(pipe->!coordinator.sideOwners.containsKey(pipe));
        coordinator.pairSides.values().removeIf(sides->{sides.removeIf(side->coordinator.coupled.get(side.pipe)!=side);return sides.isEmpty();});
        coordinator.publisher.published(staged.stream().map(Island::snapshot).toList());
    }
    /** Whether vessel inventory {@code b} on {@code to} holds exactly what {@code a} on {@code from} holds, compared by compound id. */
    private static boolean sameContents(PassiveNetwork.Inventory a,FluidThermodynamics from,PassiveNetwork.Inventory b,FluidThermodynamics to) {
        if(from==to||from.compounds().equals(to.compounds()))return a.sameContents(b);
        double[] moles;
        try{moles=IslandSets.amounts(a.moles(),from,to);}catch(IllegalStateException lost){return false;}
        return Double.doubleToLongBits(a.internalEnergy())==Double.doubleToLongBits(b.internalEnergy())&&Arrays.equals(moles,b.moles())&&a.solids().equals(b.solids());
    }
}
