package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialAxis;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;

/** Persistent physical identities and timestamped edits, separate from loaded block-entity bindings. Owned by the server thread. */
public final class WorldTopologyLedger {
    public static final int MAXIMUM_EVENTS=4096;
    public record Registration(PhysicalFluidTopology.Device device,FluidDeviceSpec spec,long revision) {
        public Registration {Objects.requireNonNull(device);Objects.requireNonNull(spec);if(revision<0)throw new IllegalArgumentException("Negative device revision");}
    }
    /** A null replacement removes that identity. Movement uses removal plus a new identity. */
    public record Edit(long id,Registration replacement) {
        public Edit {if(id<=0||replacement!=null&&replacement.device.id()!=id)throw new IllegalArgumentException("Invalid physical edit");}
    }
    public record Recovery(long filterId,UUID player) {public Recovery{if(filterId<=0)throw new IllegalArgumentException("Invalid recovery identity");}}
    public record Event(UUID id,long tick,List<Edit> edits,Set<Long> touched,Recovery recovery) {
        public Event(UUID id,long tick,List<Edit> edits,Set<Long> touched){this(id,tick,edits,touched,null);}
        public Event {Objects.requireNonNull(id);edits=List.copyOf(edits);touched=Set.copyOf(touched);if(tick<0||edits.isEmpty())throw new IllegalArgumentException("Invalid topology event");}
    }
    /** Cumulative explicit construction/destruction, in the common network basis; these are not live stock. */
    public record MaterialTotal(double[] moles,double totalEnergy,Map<String,Double> solidMasses) {
        public MaterialTotal(double[] moles,double totalEnergy){this(moles,totalEnergy,Map.of());}
        public MaterialTotal {
            solidMasses=Map.copyOf(solidMasses);for(var entry:solidMasses.entrySet())if(entry.getKey().isBlank()||entry.getKey().length()>256||!Double.isFinite(entry.getValue())||entry.getValue()<0)throw new IllegalArgumentException("Invalid external solid total");
            moles=moles.clone();if((moles.length<1||moles.length>com.wormzjl.icheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS)||!Double.isFinite(totalEnergy))throw new IllegalArgumentException("Invalid external material total");
            for(double n:moles)if(!Double.isFinite(n)||n<0)throw new IllegalArgumentException("Invalid external component total");
        }
        @Override public double[] moles(){return moles.clone();}
        public static MaterialTotal empty(){return empty(com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.conservedCount());}
        public static MaterialTotal empty(int count){if(count<1||count>com.wormzjl.icheme.science.material.MaterialAxis.MAX_CONSERVED_COMPONENTS)throw new IllegalArgumentException("Invalid external material axis size");return new MaterialTotal(new double[count],0);}
        /**
         * These totals plus what {@code reservoirs} hold, on the network basis {@code components} with their molar masses
         * {@code weights} (kg/mol): component moles, total (internal) energy and custom solid masses by
         * material and size. A crystal (owner decision D23) is its component in another phase, so it is booked as that
         * component's moles with its true energy, its stored sensible part plus its energy offset times its mass: the
         * totals do not depend on the phase the material was in when it was placed or removed.
         */
        public MaterialTotal plus(Collection<PassiveNetwork.Reservoir> reservoirs,List<String> components,double[] weights) {
            var total=moles.clone();double energy=totalEnergy;var solids=new TreeMap<>(solidMasses);
            if(components.size()!=total.length||weights.length!=total.length)throw new IllegalArgumentException("External ledger basis mismatch");
            for(var node:reservoirs) {
                var n=node.inventory().moles();if(n.length!=total.length)throw new IllegalArgumentException("External ledger basis mismatch");
                double internal=node.inventory().internalEnergy();
                for(var p:node.inventory().solids().populations()) {
                    var crystal=p.material().crystal();
                    if(crystal==null){solids.merge(p.material().id()+"@"+p.size().metres(),p.massKg(),Double::sum);continue;}
                    int c=components.indexOf(crystal.component());
                    if(c<0)throw new IllegalArgumentException("Crystal "+p.material().id()+" is made of "+crystal.component()+", which the ledger basis does not carry");
                    total[c]+=p.massKg()/weights[c];internal+=p.massKg()*crystal.energyOffset();
                }
                for(int c=0;c<n.length;c++)total[c]+=n[c];
                energy+=internal;
            }
            return new MaterialTotal(total,energy,solids);
        }
    }
    /**
     * What property data changes did to the world's contents (AGENTS.md, "Saves and data formats"), cumulative: the
     * energy (J) the vessels gained by being restated at their temperature under the new data (negative for a loss),
     * and the moles of each component and the kilograms of each solid (by id, a custom solid by id and size) that the
     * new data no longer has and so left the world. Not live stock.
     */
    public record Restated(double energy,Map<String,Double> removedMoles,Map<String,Double> removedSolids) {
        public static final Restated NONE=new Restated(0,Map.of(),Map.of());
        public Restated {
            if(!Double.isFinite(energy))throw new IllegalArgumentException("Invalid restated energy");
            removedMoles=Map.copyOf(removedMoles);removedSolids=Map.copyOf(removedSolids);
            for(var amounts:List.of(removedMoles,removedSolids))for(var e:amounts.entrySet())
                if(e.getKey().isBlank()||e.getKey().length()>256||!Double.isFinite(e.getValue())||e.getValue()<0)throw new IllegalArgumentException("Invalid removed amount "+e.getKey());
        }
        public Restated plus(Restated other) {
            var moles=new HashMap<>(removedMoles);other.removedMoles.forEach((k,v)->moles.merge(k,v,Double::sum));
            var solids=new HashMap<>(removedSolids);other.removedSolids.forEach((k,v)->solids.merge(k,v,Double::sum));
            return new Restated(energy+other.energy,moles,solids);
        }
    }
    public record Snapshot(long onlineTick,long nextIdentity,Map<Long,Registration> active,List<Event> events,
                           MaterialTotal constructed,MaterialTotal destroyed,FluidBasis basis,Map<UUID,RecoveredSolid> recoveries,Restated restated) {
        public Snapshot(long onlineTick,long nextIdentity,Map<Long,Registration> active,List<Event> events,MaterialTotal constructed,MaterialTotal destroyed,FluidBasis basis,Map<UUID,RecoveredSolid> recoveries){this(onlineTick,nextIdentity,active,events,constructed,destroyed,basis,recoveries,Restated.NONE);}
        public Snapshot(long onlineTick,long nextIdentity,Map<Long,Registration> active,List<Event> events,MaterialTotal constructed,MaterialTotal destroyed,FluidBasis basis){this(onlineTick,nextIdentity,active,events,constructed,destroyed,basis,Map.of());}
        public Snapshot(long onlineTick,long nextIdentity,Map<Long,Registration> active,List<Event> events,MaterialTotal constructed,MaterialTotal destroyed) {
            this(onlineTick,nextIdentity,active,events,constructed,destroyed,FluidBasis.capture(com.wormzjl.icheme.science.material.MaterialRuntime.current()));
        }
        public Snapshot {
            FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.topologySnapshots);
            recoveries=Map.copyOf(recoveries);if(recoveries.size()>MAXIMUM_EVENTS)throw new IllegalArgumentException("Recovery queue is full");
            if(onlineTick<0||nextIdentity<1||events.size()>MAXIMUM_EVENTS)throw new IllegalArgumentException("Invalid world topology snapshot");
            active=Map.copyOf(active);events=List.copyOf(events);Objects.requireNonNull(constructed);Objects.requireNonNull(destroyed);Objects.requireNonNull(restated);
            Objects.requireNonNull(basis);int count=basis.components().size();
            if(constructed.moles().length!=count||destroyed.moles().length!=count)throw new IllegalArgumentException("Topology accounting axis mismatch");
            for(var registration:active.values())if(registration.spec().composition().length!=count)throw new IllegalArgumentException("Topology device axis mismatch");
            for(var event:events)for(var edit:event.edits())if(edit.replacement()!=null&&edit.replacement().spec().composition().length!=count)throw new IllegalArgumentException("Topology event axis mismatch");
            var positions=new HashSet<PhysicalFluidTopology.Position>();
            for(var entry:active.entrySet()){if(entry.getKey()!=entry.getValue().device.id()||entry.getKey()>=nextIdentity)throw new IllegalArgumentException("Invalid active physical identity/position");for(var cell:entry.getValue().device.cells())if(!positions.add(cell))throw new IllegalArgumentException("Invalid active physical identity/position");}
            long last=-1;var ids=new HashSet<UUID>();
            for(var event:events){if(event.tick<last||event.tick>onlineTick||!ids.add(event.id))throw new IllegalArgumentException("Invalid topology event ordering");last=event.tick;}
        }
        public static Snapshot empty(){return empty(com.wormzjl.icheme.science.material.MaterialRuntime.current());}
        public static Snapshot empty(com.wormzjl.icheme.science.material.MaterialCatalog catalog) {
            var basis=FluidBasis.capture(catalog);int count=basis.components().size();
            return new Snapshot(0,1,Map.of(),List.of(),MaterialTotal.empty(count),MaterialTotal.empty(count),basis);
        }
    }
    /**
     * A staged change: one queued event, or a batch of queued events applied together with their accounting. Nothing
     * changes until {@link #commit}, which refuses a preparation made against another state of the ledger or at
     * another online tick.
     */
    public static final class Prepared {
        private final long version,preparedTick,nextIdentity;
        private final Event event;
        private final List<Event> applied;
        private final MaterialTotal constructed,destroyed;
        private final Map<UUID,RecoveredSolid> recoveries;
        private Prepared(long version,long preparedTick,long nextIdentity,Event event,List<Event> applied,MaterialTotal constructed,MaterialTotal destroyed,Map<UUID,RecoveredSolid> recoveries) {
            this.version=version;this.preparedTick=preparedTick;this.nextIdentity=nextIdentity;this.event=event;this.applied=List.copyOf(applied);
            this.constructed=constructed;this.destroyed=destroyed;this.recoveries=Map.copyOf(recoveries);
        }
        /** The queued event, or the first applied one. */
        public Event event(){return event;}
    }
    /** What an event reads and writes, for ordering: the identities it touches and edits, and their positions. */
    public record Footprint(Event event,Set<Long> ids,Set<PhysicalFluidTopology.Position> positions) {
        public boolean meets(Set<Long> otherIds,Set<PhysicalFluidTopology.Position> otherPositions){return !Collections.disjoint(ids,otherIds)||!Collections.disjoint(positions,otherPositions);}
    }
    // The ledger is kept as live, indexed collections, so queuing an event and applying a batch of them cost time in
    // the edits they carry, not in the size of the registry. A Snapshot - what a checkpoint saves - is built from them
    // on demand, validated as a restored one is, and shared (the same immutable parts) until the ledger changes.
    private final Thread owner=Thread.currentThread();
    private long onlineTick,nextIdentity,version;
    private final FluidBasis basis;
    private MaterialTotal constructed,destroyed;
    private final Restated restated;
    private final Map<Long,Registration> active=new HashMap<>(),latest=new HashMap<>();
    private final Map<PhysicalFluidTopology.Position,Long> activeAt=new HashMap<>(),latestAt=new HashMap<>();
    private final ArrayList<Event> events=new ArrayList<>();
    private final LinkedHashMap<UUID,RecoveredSolid> recoveries=new LinkedHashMap<>();
    private final Map<Long,Registration> activeView=Collections.unmodifiableMap(active),latestView=Collections.unmodifiableMap(latest);
    private final List<Event> eventsView=Collections.unmodifiableList(events);
    private final Map<UUID,RecoveredSolid> recoveriesView=Collections.unmodifiableMap(recoveries);
    private Snapshot shared;private long sharedVersion;
    public WorldTopologyLedger(Snapshot restored) {
        Objects.requireNonNull(restored);onlineTick=restored.onlineTick;nextIdentity=restored.nextIdentity;basis=restored.basis;constructed=restored.constructed;destroyed=restored.destroyed;restated=restored.restated;
        for(var r:restored.active.values()){active.put(r.device.id(),r);for(var cell:r.device.cells())activeAt.put(cell,r.device.id());}
        latest.putAll(active);latestAt.putAll(activeAt);
        // Every queued event must apply to the registry before it, in order, as when it was queued.
        for(var event:restored.events){check(latest,latestAt,event.edits);apply(latest,latestAt,event.edits);events.add(event);}
        recoveries.putAll(restored.recoveries);
        shared=restored;sharedVersion=version;
    }
    private void owned(){if(Thread.currentThread()!=owner)throw new IllegalStateException("World topology belongs to the server thread");}
    /** The ledger as a checkpoint saves it. While the ledger is unchanged every snapshot shares the same immutable parts. */
    public Snapshot snapshot() {
        owned();
        if(sharedVersion!=version){shared=new Snapshot(onlineTick,nextIdentity,Map.copyOf(active),List.copyOf(events),constructed,destroyed,basis,Map.copyOf(recoveries),restated);sharedVersion=version;}
        else if(shared.onlineTick!=onlineTick)shared=new Snapshot(onlineTick,shared.nextIdentity,shared.active,shared.events,shared.constructed,shared.destroyed,shared.basis,shared.recoveries,shared.restated);
        return shared;
    }
    public long onlineTick(){owned();return onlineTick;}
    public long nextIdentity(){owned();return nextIdentity;}
    public boolean hasPendingEvents(){owned();return !events.isEmpty();}
    /** Queued events in timestamp order: a live, unmodifiable view. */
    public List<Event> events(){owned();return eventsView;}
    /** Undelivered solid recoveries: a live, unmodifiable view. */
    public Map<UUID,RecoveredSolid> recoveries(){owned();return recoveriesView;}
    public boolean hasRecoveries(){owned();return !recoveries.isEmpty();}
    /** The applied registry: a live, unmodifiable view. */
    public Map<Long,Registration> active(){owned();return activeView;}
    /** The registry with every queued event applied: a live, unmodifiable view. */
    public Map<Long,Registration> latest(){owned();return latestView;}
    /** The device at a position in the applied registry, or null. */
    public Registration activeAt(PhysicalFluidTopology.Position position){owned();Long id=activeAt.get(position);return id==null?null:active.get(id);}
    /** The device at a position once every queued event applies, or null. */
    public Registration latestAt(PhysicalFluidTopology.Position position){owned();Long id=latestAt.get(position);return id==null?null:latest.get(id);}
    /** Whether a queued event places, edits or removes this identity. */
    public boolean pending(long id){owned();return latest.get(id)!=active.get(id);}
    public void tick(){owned();onlineTick=Math.addExact(onlineTick,1);}
    /** The caller allocates from nextIdentity, then installs event fences before publishing this preparation. */
    public Prepared queue(List<Edit> edits,Set<Long> touched,long nextIdentity){return queue(edits,touched,nextIdentity,null);}
    public Prepared queue(List<Edit> edits,Set<Long> touched,long nextIdentity,Recovery recovery) {
        owned();if(events.size()>=MAXIMUM_EVENTS)throw new IllegalStateException("Topology history capacity exhausted");
        if(nextIdentity<this.nextIdentity)throw new IllegalArgumentException("Identity sequence moved backwards");
        for(var edit:edits)if(edit.replacement!=null&&!latest.containsKey(edit.id)&&edit.id<this.nextIdentity)throw new IllegalStateException("Retired physical identity cannot be reused");
        check(latest,latestAt,edits);
        int count=basis.components().size();
        for(var edit:edits)if(edit.replacement!=null) {
            if(edit.id>=nextIdentity)throw new IllegalArgumentException("New identity was not reserved");
            if(edit.replacement.spec().composition().length!=count)throw new IllegalArgumentException("Topology event axis mismatch");
        }
        var event=new Event(UUID.randomUUID(),onlineTick,edits,touched,recovery);
        return new Prepared(version,onlineTick,nextIdentity,event,List.of(),null,null,Map.of());
    }
    /** What every queued event reads and writes, in queue order. A position is the one the last queued edit gives an
     * identity, else its applied one; only the identities the queued events name are looked up, so the cost follows the
     * queue, not the size of the world. */
    public List<Footprint> footprints() {
        owned();var edited=new HashMap<Long,Set<PhysicalFluidTopology.Position>>();
        for(var event:events)for(var edit:event.edits)if(edit.replacement!=null)edited.put(edit.id,edit.replacement.device.cells());
        var result=new ArrayList<Footprint>(events.size());
        for(var event:events) {
            var ids=new HashSet<>(event.touched);for(var edit:event.edits)ids.add(edit.id);
            var locations=new HashSet<PhysicalFluidTopology.Position>();
            for(long id:ids){var cells=edited.get(id);if(cells==null){var record=active.get(id);if(record!=null)cells=record.device.cells();}if(cells!=null)locations.addAll(cells);}
            result.add(new Footprint(event,ids,locations));
        }
        return result;
    }
    /**
     * Stage a batch of queued events applied together, in queue order, with the batch's construction and destruction
     * accounting. Every event of the batch must meet no earlier queued event outside the batch (identities and
     * positions, as {@link Footprint#meets} decides); events of the batch may depend on earlier ones in it. The caller
     * aligns their owners and applies them at one tick.
     */
    public Prepared applyBatch(List<UUID> eventIds,Collection<PassiveNetwork.Reservoir> additions,Collection<PassiveNetwork.Reservoir> removals,double[] weights,long nextIdentity) {
        owned();if(nextIdentity<this.nextIdentity)throw new IllegalStateException("Identity sequence moved backwards");
        var wanted=new HashSet<>(eventIds);if(wanted.isEmpty()||wanted.size()!=eventIds.size())throw new IllegalArgumentException("Empty or repeated topology batch");
        var batch=new ArrayList<Event>();var blockedIds=new HashSet<Long>();var blockedPositions=new HashSet<PhysicalFluidTopology.Position>();
        for(var footprint:footprints()) {
            if(!wanted.contains(footprint.event.id)){blockedIds.addAll(footprint.ids);blockedPositions.addAll(footprint.positions);continue;}
            if(footprint.meets(blockedIds,blockedPositions))throw new IllegalStateException("Topology event has unresolved earlier dependencies");
            batch.add(footprint.event);
        }
        if(batch.size()!=wanted.size())throw new IllegalStateException("Topology event has unresolved earlier dependencies");
        // The batch is validated against the applied registry, each event after the ones before it.
        var overlay=new Overlay(active,activeAt);for(var event:batch)overlay.check(event.edits);
        return new Prepared(version,onlineTick,nextIdentity,batch.getFirst(),batch,constructed.plus(additions,basis.components(),weights),destroyed.plus(removals,basis.components(),weights),Map.of());
    }
    public Prepared withRecovery(Prepared prepared,RecoveredSolid recovery){
        validate(prepared);if(prepared.applied.isEmpty())throw new IllegalArgumentException("Only an application can queue a recovery");
        var pending=new LinkedHashMap<>(prepared.recoveries);if(recoveries.containsKey(prepared.event.id)||pending.putIfAbsent(prepared.event.id,recovery)!=null)throw new IllegalStateException("Duplicate recovery");
        if(recoveries.size()+pending.size()>MAXIMUM_EVENTS)throw new IllegalArgumentException("Recovery queue is full");
        return new Prepared(prepared.version,prepared.preparedTick,prepared.nextIdentity,prepared.event,prepared.applied,prepared.constructed,prepared.destroyed,pending);
    }
    public void deliveredRecovery(UUID identity){owned();if(recoveries.remove(identity)==null)throw new IllegalStateException("Recovery is no longer owned");version++;}
    public void validate(Prepared prepared){owned();if(prepared.version!=version||prepared.preparedTick!=onlineTick)throw new IllegalStateException("Stale topology preparation");}
    public void commit(Prepared prepared) {
        validate(prepared);
        if(prepared.applied.isEmpty()){events.add(prepared.event);apply(latest,latestAt,prepared.event.edits);}
        else {
            var applied=Collections.newSetFromMap(new IdentityHashMap<Event,Boolean>());applied.addAll(prepared.applied);events.removeIf(applied::contains);
            for(var event:prepared.applied)apply(active,activeAt,event.edits);
            constructed=prepared.constructed;destroyed=prepared.destroyed;recoveries.putAll(prepared.recoveries);
        }
        nextIdentity=prepared.nextIdentity;version++;
    }
    /** Applies one event's edits, already checked against this registry, in order. */
    private static void apply(Map<Long,Registration> records,Map<PhysicalFluidTopology.Position,Long> at,List<Edit> edits) {
        for(var edit:edits) {
            var previous=records.get(edit.id);if(previous!=null)for(var cell:previous.device.cells())if(Objects.equals(at.get(cell),edit.id))at.remove(cell);
            if(edit.replacement==null)records.remove(edit.id);else{records.put(edit.id,edit.replacement);for(var cell:edit.replacement.device.cells())at.put(cell,edit.id);}
        }
    }
    private static void check(Map<Long,Registration> records,Map<PhysicalFluidTopology.Position,Long> at,List<Edit> edits){new Overlay(records,at).check(edits);}
    /**
     * A registry with checked, not yet applied edits on top of it, so a whole event (or a batch) is validated without
     * touching the registry: every edit names a live identity or a new one at revision 0, keeps its kind and dimension,
     * advances its revision by one and never re-initialises a finite reservoir (a vessel's edit changes at most its
     * geometry, {@link FluidDeviceSpec#sameCharge}); after each event no two devices share a position. A device may move:
     * a layout refresh rebuilds a vessel or a machine where it now stands, and it keeps its identity and contents (batch
     * 2026-10-26-backward-compat, decision D7). Only the positions an event changes are examined; the rest of the
     * registry is valid already.
     */
    private static final class Overlay {
        private final Map<Long,Registration> base;private final Map<PhysicalFluidTopology.Position,Long> baseAt;
        private final Map<Long,Optional<Registration>> changed=new HashMap<>();
        private final Map<PhysicalFluidTopology.Position,Set<Long>> occupants=new HashMap<>();
        private Overlay(Map<Long,Registration> base,Map<PhysicalFluidTopology.Position,Long> baseAt){this.base=base;this.baseAt=baseAt;}
        private Registration get(long id){var c=changed.get(id);return c!=null?c.orElse(null):base.get(id);}
        private Set<Long> at(PhysicalFluidTopology.Position p){return occupants.computeIfAbsent(p,ignored->{var s=new HashSet<Long>();Long id=baseAt.get(p);if(id!=null)s.add(id);return s;});}
        void check(List<Edit> edits) {
            var seen=new HashSet<Long>();var placed=new ArrayList<PhysicalFluidTopology.Position>();
            for(var edit:edits) {
                if(!seen.add(edit.id))throw new IllegalArgumentException("Duplicate identity in topology event");
                var previous=get(edit.id);var replacement=edit.replacement;
                if(replacement==null) {if(previous==null)throw new IllegalStateException("Missing removed device");for(var cell:previous.device.cells())at(cell).remove(edit.id);changed.put(edit.id,Optional.empty());continue;}
                if(previous!=null&&(!previous.device.position().dimension().equals(replacement.device.position().dimension())||previous.device.kind()!=replacement.device.kind()||replacement.revision!=Math.addExact(previous.revision,1)))throw new IllegalStateException("Stale device edit or a changed kind or dimension");
                if(previous==null&&replacement.revision!=0)throw new IllegalStateException("New device has a stale revision");
                if(previous!=null&&previous.device.kind()==com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.RESERVOIR&&!previous.spec.sameCharge(replacement.spec))throw new IllegalStateException("Finite-reservoir initialization cannot be reapplied");
                if(previous!=null)for(var cell:previous.device.cells())at(cell).remove(edit.id);
                changed.put(edit.id,Optional.of(replacement));for(var cell:replacement.device.cells()){at(cell).add(edit.id);placed.add(cell);}
            }
            for(var position:placed)if(at(position).size()>1)throw new IllegalStateException("Two devices occupy one position");
        }
    }
    /** Owned recovery awaiting delivery to a player or a loaded world chunk. */
    public record RecoveredSolid(PhysicalFluidTopology.Position position,UUID player,SolidInventory solids,double energyJoule) {
        public RecoveredSolid {
            Objects.requireNonNull(position);Objects.requireNonNull(solids);
            if(solids.empty()||!Double.isFinite(energyJoule))throw new IllegalArgumentException("Empty/invalid solid recovery");
        }
    }
    /**
     * Persisted identity for topology/accounting even when a world has no active hydraulic islands. {@code revision} is
     * the network package's data identity, the declared revision of every record it reads
     * ({@link MaterialCatalog#fluidRevision}); a world written before the field existed reads "" and restates once.
     */
    public record FluidBasis(String packageId,List<String> components,String revision) {
        public FluidBasis {
            Objects.requireNonNull(packageId);Objects.requireNonNull(revision);
            components=new MaterialAxis(components).ids();
            if(!packageId.matches("[a-z][a-z0-9_.:-]{0,127}")||revision.length()>MaterialCatalog.MAX_IDENTITY||revision.chars().anyMatch(Character::isISOControl)
                    ||!components.getLast().equals("Water")||!components.contains("Nitrogen"))throw new IllegalArgumentException("Invalid fluid basis contract");
        }
        public static FluidBasis capture(MaterialCatalog catalog) {
            String id=FluidMaterialCatalog.networkPackage(catalog);var ids=new ArrayList<>(catalog.requirePackage(id).components());ids.add("Water");
            return new FluidBasis(id,ids,catalog.fluidRevision(id));
        }
        public void requireCurrent(MaterialCatalog catalog) {
            if(!equals(capture(catalog)))throw new IllegalArgumentException("Incompatible saved fluid topology basis; use a fresh development world");
        }
    }
}
