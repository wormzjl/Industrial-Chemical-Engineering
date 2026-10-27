package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import net.minecraft.nbt.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Function;

/**
 * The fluid checkpoint's envelope and its state, format {@link #VERSION} (AGENTS.md, "Saves and data formats"). A
 * checkpoint is a core record (one NBT compound, the world's saved data) and units in packs ({@link FluidCheckpointStore}
 * says where). The envelope is frozen ({@link #ENVELOPE}): the core record's frame and every unit's header, then the
 * unit's two sections, each with its own schema or format and its length. The facts section ({@link FluidFacts}) holds
 * what the player built and put in, in an encoding every later build reads; the state section holds what the engine can
 * compute again, in this build's state format only. A build that cannot use a checkpoint's state rebuilds its islands
 * from the facts; nothing it holds is migrated. Rebuilds properties through the immutable model; it never deserializes
 * implementation caches or creates a nitrogen charge.
 *
 * <p>Core record: {@code Envelope = 1}, {@code StateFormat} and {@code FactsSchema}; the world {@code Epoch};
 * {@code Strings}, an append-only table of the long texts state sections refer to; {@code Packages}, one entry per
 * property package with the thermodynamic revision and energy reference the units were written under; the pack files and
 * their sizes; the topology unit's reference; and {@code Islands}, the island index in columns: identity, revision and
 * unit generation, the clock ({@code Online}, {@code Committed}, {@code Retry}, {@code Cadence}), the certificate summary
 * ({@code Base} or {@link #AWAKE}, {@code Since}, {@code Start}, {@code Horizon}) and where the island's unit lies with
 * its SHA-256. {@code SHA256} covers every field. A core record of state format 19 or older has no envelope: its world
 * is reset ({@link FluidSavedData#open}, owner decision D4 of batch 2026-10-26-backward-compat).</p>
 *
 * <p>Unit: magic, {@link #UNIT_ENVELOPE}, kind, identity, revision and generation (all four must match the index), then
 * the facts section (its schema and length) and the state section (its format and length). The topology unit's facts are
 * the registry, totals, recoveries and identity counter ({@link FluidFacts#world}); its state is the queued events. An
 * island unit's facts are its vessels' contents and filters' cakes, and a certified island's drift ({@link FluidFacts#island});
 * its state is its dimension and package, status and fences, the graph's topology once (node identities, elevations,
 * kinds, volumes, level heights and open flags, pipe ends, sections, controls as tag 0 passive, 1 pump, 2 valve,
 * 3 compressor, the two ends' phase ports and the forbidden-direction mask) and its state; the last solved interval with
 * its end flows, which the island's next job starts from; and, for a certified island, its certificate signature and the
 * graph its certified interval started from. A certified island's unit records its certificate base, so while it stays
 * certified its unit never changes. Solids (every stock, stream, cake and pending recovery): each population as its
 * material's id, revision, density and heat capacity, then its size and mass; the id names a crystal the package admits
 * or a custom solid (owner decision D23), and the state reader refuses an id that names no record of the package or
 * saved constants that are not the record's (the facts path then reads the current record). An exact load reads both
 * sections and checks that they agree ({@link FluidFacts#disagreement}).</p>
 *
 * <p>State formats: 23 (batch 2026-10-27-heat-exchanger-design) gives each pipe section its channel count. 20 (batch 2026-10-26-backward-compat) put the envelope and the facts around state format 19's
 * units; the registry and the topology unit's totals left its state for its facts. Format 19, unit format 12, added
 * validated pipe-filter registrations (active and pending), batch 2026-10-25-accessory-refinements; 18, unit 11, CHECK
 * cutoff registrations and permanent per-connection direction masks, batch 2026-10-24-pipeline-check-valve; 17, unit 10,
 * the pipe-cutoff code, batch 2026-10-23-pipeline-shutoff-valve; 16, unit 9, the generator-enabled flag, batch
 * 2026-10-23-fluid-device-controls-display; 15, unit 8, requested mover flow and the enabled flag, batch
 * 2026-10-23-pump-compressor-switch-sound; 14, unit 7, directed multi-cell mover ports, batch
 * 2026-10-23-functional-pumps-compressors; 13, unit 6, the open vessels of batch 2026-10-21-floating-roof-tanks; 12,
 * unit 5, vessel ports and level heights with the drums of batch 2026-10-15-custom-equipment-rendering; 11 dropped the
 * velocity-limit modes among the endpoint modes, 2026-10-18, and unit format 4 states their eight ordinals, 2026-10-19;
 * 10, unit 3, dropped the movers' efficiency, the approximate fallback and the saved interval's acceptance.</p>
 *
 * <p>24 (batch 2026-10-27-heat-exchanger-design, W5): a pipe's heat side (its surface, films, wall, bypass and give-back)
 * after its actuator area.</p>
 *
 * <p>25 (batch 2026-10-27-heat-exchanger-design, W6): the heat law is tagged (0 none, 1 heat side, 2 fired coil: its set
 * temperature, reach, duty and firing).</p>
 *
 * <p>26 (batch 2026-10-27-heat-exchanger-design, W3): a heat side also carries its partner's connection id and its active
 * share (the sit-out), after its give-back.</p>
 *
 * <p>27 (batch 2026-10-27-heat-exchanger-design, W4): a heat side also carries its controller (a presence flag, then its
 * target by ordinal, value, low limit and manual effort) and its planned limit by ordinal; tag 3 is an air cooler's side
 * (its area, its air's temperature, bare tube, film, capacity and natural draught, its fans, controller and limit).</p>
 */
public final class FluidCheckpointCodec {
    /** The state format: the core record's {@code StateFormat} and every unit's state section. */
    public static final int VERSION=27;
    /** The most wall cells a saved heat side holds (an exchanger's 8; an air cooler's flat wall 1). */
    private static final int MAXIMUM_WALL_CELLS=64;
    /** The envelope: the core record's frame, the unit header and the section layout. Frozen. */
    public static final int ENVELOPE=1;
    /** The {@code Base} of an awake island: it carries no certificate. */
    public static final long AWAKE=-1;
    /** The unit header's envelope byte, frozen; a unit of state format 19 or older held its unit format (12 at most) here. */
    static final int UNIT_MAGIC=0x43434655,UNIT_ENVELOPE=13;
    static final byte ISLAND=1,TOPOLOGY=2;
    /** Magic, envelope, kind, identity, revision, generation. */
    static final int UNIT_HEADER=4+1+1+8+8+8;
    private static final int MAXIMUM_NODES=10000,MAXIMUM_PIPES=100000,MAXIMUM_SECTIONS=100000,MAXIMUM_ARRAY=4096,MAXIMUM_TEXT=1<<20,
            MAXIMUM_FENCES=1<<16,MAXIMUM_BOUNDARIES=1<<20,MAXIMUM_REASONS=1<<16,MAXIMUM_STRINGS=1<<20;
    /** Tests and GameTests (the scheduler's self-verification switch) also re-encode every reused unit and fail if
     * the stored one differs, and fail a load whose facts and state disagree. */
    private static final boolean VERIFY=Boolean.getBoolean("icheme.fluid.scheduler.verify");
    private static final FlowControl.Mode[] MODES=FlowControl.Mode.values();
    private static final PassiveNetwork.NodeKind[] NODE_KINDS=PassiveNetwork.NodeKind.values();
    private static final ConservativeTransport.BoundaryTransfer.Kind[] BOUNDARY_KINDS=ConservativeTransport.BoundaryTransfer.Kind.values();
    private static final PassiveNetwork.PhasePort[] PHASE_PORTS=PassiveNetwork.PhasePort.values();
    private FluidCheckpointCodec() {}

    public record IslandEntry(String dimension,String packageId,IslandCoordinator.Snapshot snapshot) {
        public IslandEntry {Objects.requireNonNull(dimension);Objects.requireNonNull(packageId);Objects.requireNonNull(snapshot);if(dimension.isBlank()||packageId.isBlank())throw new IllegalArgumentException("Invalid saved package");}
        /** The model key this island's package resolves to. */
        public PackageKey key(){return new PackageKey(packageId);}
    }
    /** The islands a save captures, each owning its reservoirs' inventory in its dimension. */
    public record Checkpoint(List<IslandEntry> islands) {
        public Checkpoint {
            islands=List.copyOf(islands);
            var ids=new HashSet<Long>();var stock=new HashSet<String>();
            for(var entry:islands) {
                if(!ids.add(entry.snapshot.id()))throw new IllegalArgumentException("Duplicate saved island identity");
                for(var node:entry.snapshot.graph().reservoirs())if(node.kind()==PassiveNetwork.NodeKind.RESERVOIR&&!stock.add(entry.dimension+":"+node.id()))throw new IllegalArgumentException("Duplicate saved inventory owner");
            }
        }
    }
    public record PackageKey(String packageId) {
        public PackageKey {Objects.requireNonNull(packageId);if(packageId.isBlank())throw new IllegalArgumentException("Invalid saved package");}
    }


    // ---------------- units: the envelope's sections ----------------

    /** Where a unit's two sections lie: the facts with their schema, the state with its format. */
    record Sections(int factsSchema,int factsOffset,int factsLength,int stateFormat,int stateOffset,int stateLength) {}
    /** A unit's sections: the facts with this build's schema, the state with this build's format. */
    private static byte[] sections(byte[] facts,byte[] state) {
        var w=new Writer();w.varint(FluidFacts.SCHEMA);w.varint(facts.length);w.raw(facts);w.varint(VERSION);w.varint(state.length);w.raw(state);return w.toByteArray();
    }
    /** Reads the section layout that follows a unit's header, to the unit's end. Facts of a later schema refuse the unit. */
    private static Sections sections(Reader r,String what) {
        try {
            int schema=r.varint(Integer.MAX_VALUE);
            if(schema<1||schema>FluidFacts.SCHEMA)throw r.invalid("facts schema "+schema+": this build reads schemas 1 to "+FluidFacts.SCHEMA+", so a newer build wrote it");
            int factsLength=r.varint(Integer.MAX_VALUE);int factsOffset=r.position();r.skip(factsLength);
            int format=r.varint(Integer.MAX_VALUE);int stateLength=r.varint(Integer.MAX_VALUE);int stateOffset=r.position();r.skip(stateLength);r.end();
            return new Sections(schema,factsOffset,factsLength,format,stateOffset,stateLength);
        } catch(Unreadable unreadable){throw unreadable;}
        catch(IllegalArgumentException invalid){throw new Unreadable("The unit of "+what+" cannot be read: "+invalid.getMessage(),invalid);}
    }

    // ---------------- the world topology ledger (its own unit) ----------------

    /**
     * The world topology ledger without its online tick, which a checkpoint keeps as its epoch: its facts (the registry,
     * totals, recoveries and identity counter) and its state (the queued events), as a unit's two sections. It changes
     * only when the ledger commits, so a save keeps the unit of an unchanged ledger.
     */
    public static byte[] encodeTopology(WorldTopologyLedger.Snapshot world){return sections(FluidFacts.world(world),FluidFacts.events(world.events()));}
    /**
     * A topology {@link #encodeTopology} wrote, at {@code onlineTick}, validated as the ledger itself validates it. Its
     * pending recoveries' solids are read as records of its basis package, whose model {@code models} gives (asked only
     * when a recovery is pending), with their saved constants.
     */
    public static WorldTopologyLedger.Snapshot decodeTopology(byte[] bytes,long onlineTick,Function<PackageKey,FluidThermodynamics> models) {
        var r=new Reader(bytes,0,bytes.length,"topology unit");return topology(bytes,sections(r,"the topology"),onlineTick,models);
    }
    static WorldTopologyLedger.Snapshot topology(byte[] data,Sections s,long onlineTick,Function<PackageKey,FluidThermodynamics> models) {
        var world=worldFacts(data,s);
        if(s.stateFormat()!=VERSION)throw new IllegalArgumentException("The topology's state is of format "+s.stateFormat()+"; this build reads "+VERSION);
        var events=FluidFacts.events(data,s.stateOffset(),s.stateLength());
        var model=new FluidThermodynamics[1];
        return world.snapshot(onlineTick,events,saved->{
            if(model[0]==null)model[0]=Objects.requireNonNull(models.apply(new PackageKey(world.basis().packageId())));
            return record(new SolidMaterial(saved.id(),saved.revision(),saved.density(),saved.heatCapacity()),model[0]);
        });
    }
    /** The facts of a topology unit; facts this build cannot read refuse the world. */
    static FluidFacts.World worldFacts(byte[] data,Sections s) {
        try{return FluidFacts.world(data,s.factsOffset(),s.factsLength());}
        catch(IllegalArgumentException invalid){throw new Unreadable("The topology's facts cannot be read: "+invalid.getMessage(),invalid);}
    }
    /** Whether two ledger snapshots hold the same ledger, whatever their online ticks: a snapshot shares the ledger's
     * immutable parts with the state it was taken from, so an unchanged ledger is the same objects. */
    public static boolean sameWorldBody(WorldTopologyLedger.Snapshot a,WorldTopologyLedger.Snapshot b) {
        return a.nextIdentity()==b.nextIdentity()&&a.active()==b.active()&&a.events()==b.events()&&a.constructed()==b.constructed()
                &&a.destroyed()==b.destroyed()&&a.basis()==b.basis()&&a.recoveries()==b.recoveries()&&a.restated()==b.restated();
    }
    static byte[] topologyUnit(WorldTopologyLedger.Snapshot world,long generation) {
        var w=new Writer();header(w,TOPOLOGY,0,0,generation);w.raw(encodeTopology(world));return w.toByteArray();
    }
    /** The topology unit at {@code unit}: its header checked against the index, its sections located. */
    static Sections topologySections(byte[] data,int offset,int length,long generation) {
        var r=new Reader(data,offset,length,"topology unit");requireHeader(r,TOPOLOGY,0,0,generation,"the topology");return sections(r,"the topology");
    }
    static boolean verifying(){return VERIFY;}

    // ---------------- the string table ----------------

    /**
     * The core record's append-only table of the long texts units refer to by index. An index, once given, names the
     * same text for as long as any unit refers to it, so a unit written at one save still reads at a later one; only a
     * save that writes every unit afresh starts a new table.
     */
    static final class Strings {
        private final List<String> values=new ArrayList<>();private final Map<String,Integer> index=new HashMap<>();
        int ref(String value) {
            Objects.requireNonNull(value);Integer known=index.get(value);if(known!=null)return known;
            if(values.size()>=MAXIMUM_STRINGS)throw new IllegalStateException("Checkpoint string table is full");
            index.put(value,values.size());values.add(value);return values.size()-1;
        }
        String get(int i,String what){if(i<0||i>=values.size())throw new IllegalArgumentException("Invalid saved "+what+": string "+i+" is not in the table of "+values.size());return values.get(i);}
        ListTag tag(){var list=new ListTag();for(var value:values)list.add(StringTag.valueOf(value));return list;}
        static Strings read(ListTag list) {
            var strings=new Strings();
            for(int i=0;i<list.size();i++) {
                var value=list.getString(i);
                if(value.length()>MAXIMUM_TEXT||strings.index.putIfAbsent(value,i)!=null)throw new IllegalArgumentException("Invalid saved string table entry "+i);
                strings.values.add(value);
            }
            return strings;
        }
    }

    // ---------------- island units ----------------

    private static void header(Writer w,byte kind,long id,long revision,long generation) {
        w.i32(UNIT_MAGIC);w.u8(UNIT_ENVELOPE);w.u8(kind);w.i64(id);w.i64(revision);w.i64(generation);
    }
    /** The frozen header: a unit that is not this envelope's, or not the one the index names, refuses the world. */
    private static void requireHeader(Reader r,byte kind,long id,long revision,long generation,String what) {
        try {
            if(r.i32()!=UNIT_MAGIC)throw new IllegalArgumentException("Invalid saved unit of "+what+": not a fluid storage unit");
            int envelope=r.u8();if(envelope!=UNIT_ENVELOPE)throw new IllegalArgumentException("The stored unit of "+what+" has envelope "+envelope+"; this build reads envelope "+UNIT_ENVELOPE);
            int k=r.u8();long i=r.i64(),v=r.i64(),g=r.i64();
            if(k!=kind||i!=id||v!=revision||g!=generation)throw new IllegalArgumentException("The stored unit of "+what+" holds "+(k==ISLAND?"island "+i:"kind "+k)+" revision "+v+" generation "+g
                    +" but the index expects "+(kind==ISLAND?"island "+id:"the topology")+" revision "+revision+" generation "+generation+": the world's fluid data is inconsistent");
        } catch(IllegalArgumentException invalid){throw new Unreadable(invalid.getMessage(),invalid);}
    }
    /** Where a graph's topology and state were written, so a second graph can be compared with the first. */
    private record Written(int topologyStart,int stateStart,int end) {}

    /**
     * One island's unit: its facts on the package's component axis {@code components} ({@link FluidFacts#island}), then
     * its state. A certified island records its certificate base (the solved interval's end state, with the interval
     * itself and the graph it started from), not its materialised state. The certificate's starting graph is written as
     * "the same graph" or as a state over the same topology whenever it is.
     */
    static byte[] islandUnit(IslandEntry entry,long generation,Strings strings,List<String> components) {
        var s=entry.snapshot;var w=new Writer();header(w,ISLAND,s.id(),s.revision(),generation);
        w.raw(sections(FluidFacts.island(entry,components),islandState(entry,strings)));return w.toByteArray();
    }
    private static byte[] islandState(IslandEntry entry,Strings strings) {
        var s=entry.snapshot;var persisted=s.certificate().flatMap(IslandCoordinator.Certified::saved);
        var graph=persisted.map(saved->saved.interval().result().graph()).orElse(s.graph());
        var result=persisted.isPresent()?Optional.of(persisted.orElseThrow().interval().result()):s.lastResult();
        if(!graph.scheduledTransfers().isEmpty())throw new IllegalArgumentException("Request-scoped transfers must be resolved into the committed ledger before saving");
        var w=new Writer();
        w.varint(strings.ref(entry.dimension));w.varint(strings.ref(entry.packageId));
        w.text(s.status());
        var fences=new TreeMap<>(s.fences());w.varint(fences.size());
        for(var fence:fences.entrySet()){w.i64(fence.getKey().getMostSignificantBits());w.i64(fence.getKey().getLeastSignificantBits());w.i64(fence.getValue());}
        int topologyStart=w.size();topology(w,graph);int stateStart=w.size();state(w,graph);
        var main=new Written(topologyStart,stateStart,w.size());
        if(result.isEmpty())w.u8(0);
        else {
            var r=result.orElseThrow();w.u8(1);
            w.f64(r.advancedSeconds());w.sparse(r.averageMassFlows());w.varint(r.acceptedSubsteps());w.varint(r.rejectedSubsteps());w.f64(r.pumpWorkJoule());
            w.varint(r.boundaries().size());
            for(var b:r.boundaries()){w.i64(b.nodeId());w.sparse(b.moles());w.f64(b.totalEnergyJoule());solids(w,b.solids());w.u8(b.solidDirection()+1);w.u8(b.kind().ordinal());}
            var reasons=new TreeMap<>(r.rejectionReasons());w.varint(reasons.size());for(var reason:reasons.entrySet()){w.text(reason.getKey());w.i32(reason.getValue());}
            modes(w,r.endpointModes());w.sparse(r.endpointHeads());w.sparse(r.endpointFlows());
            w.varint(r.pipeTransfers().size());
            for(var p:r.pipeTransfers()) {
                w.i64(p.pipeId());int count=p.forward().phaseMoles()[0].length;
                if(p.reverse().phaseMoles()[0].length!=count)throw new IllegalArgumentException("Pipe history streams of different component counts");
                w.varint(count);stream(w,p.forward());stream(w,p.reverse());
            }
        }
        if(persisted.isEmpty())w.u8(0);
        else {
            var saved=persisted.orElseThrow();var signature=saved.signature();w.u8(1);
            w.varint(strings.ref(signature.propertyRevision()));w.varint(strings.ref(signature.policy()));w.raw(HexFormat.of().parseHex(signature.graph()));
            related(w,saved.interval().before(),graph,main);
        }
        return w.toByteArray();
    }
    /** A graph beside the island's main one: 1 when it is the same graph (same topology and state bytes), 2 when it
     * shares the topology (then its state follows), 3 otherwise (its topology and state follow). */
    private static void related(Writer w,PassiveNetwork other,PassiveNetwork main,Written written) {
        if(other==main){w.u8(1);return;}
        int flag=w.size();w.u8(0);int topologyStart=w.size();topology(w,other);int stateStart=w.size();state(w,other);int end=w.size();
        boolean sameTopology=w.sameBytes(topologyStart,stateStart,written.topologyStart,written.stateStart);
        if(sameTopology&&w.sameBytes(stateStart,end,written.stateStart,written.end)){w.truncate(flag);w.u8(1);return;}
        if(!sameTopology){w.truncate(flag);w.u8(3);topology(w,other);state(w,other);return;}
        var state=new Writer();state.append(w,stateStart,end);w.truncate(flag);w.u8(2);w.append(state,0,state.size());
    }
    private static void geometry(Writer w,com.wormzjl.icheme.science.fluid.network.VesselGeometry g){w.text(g.form().name());w.f64(g.capacity());w.f64(g.height());w.f64(g.diameter());w.f64(g.length());w.f64(g.bootDiameter());w.f64(g.bootLength());}
    private static com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry(Reader r){return new com.wormzjl.icheme.science.fluid.network.VesselGeometry(com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.valueOf(r.text()),r.f64(),r.f64(),r.f64(),r.f64(),r.f64(),r.f64());}
    private static void topology(Writer w,PassiveNetwork graph) {
        w.varint(graph.reservoirs().size());
        for(var n:graph.reservoirs()){w.i64(n.id());w.f64(n.elevation());w.u8(n.kind().ordinal());w.f64(n.inventory().volume());w.f64(n.inventory().levelHeight());w.bool(n.inventory().open());geometry(w,n.inventory().geometry());}
        w.varint(graph.pipes().size());
        for(var p:graph.pipes()) {
            w.i64(p.id());w.varint(p.first());w.varint(p.second());w.varint(p.sections().size());
            for(var g:p.sections()){w.f64(g.length());w.f64(g.diameter());w.f64(g.roughness());w.f64(g.minorLoss());w.varint(g.channels());}
            switch(p.control()) {
                case FlowControl.Passive ignored->w.u8(0);
                case FlowControl.Pump pump->{w.u8(1);w.f64(pump.requestedVolumeFlow());w.f64(pump.maximumAddedPressure());w.bool(pump.enabled());}
                case FlowControl.PressureValve valve->{w.u8(2);w.f64(valve.targetPressure());}
                case FlowControl.ControlValve valve->{w.u8(4);w.text(valve.source().name());w.f64(valve.targetPressure());w.f64(valve.massTarget());w.f64(valve.volumeTarget());w.bool(valve.sensor()!=null);if(valve.sensor()!=null){w.i64(valve.sensor().getMostSignificantBits());w.i64(valve.sensor().getLeastSignificantBits());}w.i64(valve.revision());}
                case FlowControl.Compressor compressor->{w.u8(3);w.f64(compressor.requestedVolumeFlow());w.f64(compressor.maximumPressureRatio());w.bool(compressor.enabled());}
            }
            // Format 6: the port of each end (PassiveNetwork.PhasePort ordinal), after the control.
            w.u8(p.firstPort().ordinal());w.u8(p.secondPort().ordinal());w.varint(p.forbiddenDirections());w.f64(p.actuatorArea());
            // Format 24: the heat side, facts and slice inputs alike, so a restored island moves the heat its graph did.
            // Format 25: a tag for the heat law (0 none, 1 heat side, 2 fired coil); format 27: 3 an air cooler's side.
            switch(p.heat()){
                case null->w.u8(0);
                case com.wormzjl.icheme.science.fluid.network.HeatSide heat->{var films=heat.films();var wall=heat.wall();w.u8(1);
                    w.f64(heat.area());w.f64(films.liquid());w.f64(films.twoPhase());w.f64(films.gas());
                    w.varint(wall.length);for(double t:wall)w.f64(t);w.f64(heat.bypass());w.f64(heat.giveBack());w.i64(heat.partner());w.f64(heat.active());
                    control(w,heat.control());w.u8(heat.limit().ordinal());}
                case com.wormzjl.icheme.science.fluid.network.AirCooled cooled->{var air=cooled.air();w.u8(3);w.f64(cooled.area());
                    w.f64(air.temperature());w.f64(air.barePerFace());w.f64(air.film());w.f64(air.capacity());w.f64(air.natural());
                    w.f64(cooled.fans());control(w,cooled.control());w.u8(cooled.limit().ordinal());}
                case com.wormzjl.icheme.science.fluid.network.FiredCoil coil->{w.u8(2);w.f64(coil.setTemperature());w.f64(coil.reach());w.f64(coil.duty());w.f64(coil.firing());}
            }
        }
    }
    private static void control(Writer w,com.wormzjl.icheme.science.fluid.network.HeatControl control) {
        w.bool(control!=null);if(control==null)return;
        w.u8(control.target().ordinal());w.f64(control.value());w.f64(control.lowLimit());w.f64(control.manual());
    }
    private static com.wormzjl.icheme.science.fluid.network.HeatControl control(Reader r) {
        if(!r.bool())return null;
        int target=r.u8();if(target>=HEAT_TARGETS.length)throw r.invalid("unknown heat control target "+target);
        return new com.wormzjl.icheme.science.fluid.network.HeatControl(HEAT_TARGETS[target],r.f64(),r.f64(),r.f64());
    }
    private static com.wormzjl.icheme.science.fluid.network.HeatControl.Limit limit(Reader r){int limit=r.u8();if(limit>=HEAT_LIMITS.length)throw r.invalid("unknown heat control limit "+limit);return HEAT_LIMITS[limit];}
    private static final com.wormzjl.icheme.science.fluid.network.HeatControl.Target[] HEAT_TARGETS=com.wormzjl.icheme.science.fluid.network.HeatControl.Target.values();
    private static final com.wormzjl.icheme.science.fluid.network.HeatControl.Limit[] HEAT_LIMITS=com.wormzjl.icheme.science.fluid.network.HeatControl.Limit.values();
    private static void state(Writer w,PassiveNetwork graph) {
        for(var n:graph.reservoirs()) {
            var inventory=n.inventory();var s=n.state();
            w.sparse(inventory.moles());w.f64(inventory.internalEnergy());solids(w,inventory.solids());
            w.f64(s.temperature());w.f64(s.pressure());w.sparse(s.liquid());w.sparse(s.vapor());w.f64(s.waterLiquid());w.f64(s.waterVapor());w.f64(s.hydrocarbonPartialPressure());
        }
        for(var p:graph.pipes()) {
            w.varint(p.blockedDirections());var filter=p.filter();
            // The clean resistance is the configured one at load (as in format 3), so it is not stored.
            if(filter==null)w.u8(0);else{w.u8(1);w.f64(filter.capacity());solids(w,filter.captured());w.f64(filter.energyJoule());w.bool(filter.stoppedAtCapacity());}
        }
    }
    private static void solids(Writer w,SolidInventory solids) {
        w.varint(solids.populations().size());
        for(var p:solids.populations()){var m=p.material();w.text(m.id());w.text(m.revision());w.f64(m.density());w.f64(m.heatCapacity());w.text(p.size().metres());w.f64(p.massKg());}
    }
    private static void modes(Writer w,List<FlowControl.Mode> modes){w.varint(modes.size());for(var mode:modes)w.u8(mode.ordinal());}
    private static void stream(Writer w,PipeTransfer.Stream stream) {
        var phases=stream.phaseMoles();var volumes=stream.phaseVolumes();
        boolean zero=Double.doubleToRawLongBits(stream.massKg())==0&&stream.solids().empty();
        for(var phase:phases)for(double n:phase)zero&=Double.doubleToRawLongBits(n)==0;
        for(double v:volumes)zero&=Double.doubleToRawLongBits(v)==0;
        if(zero){w.u8(0);return;}
        w.u8(1);w.f64(stream.massKg());for(var phase:phases)w.sparse(phase);w.sparse(volumes);solids(w,stream.solids());
    }

    /** What the index says about one island: identity, revision and unit generation, clock, certificate summary. */
    record IndexRow(long id,long revision,long generation,long online,long committed,long retry,int cadence,long base,long since,long start,long horizon,UnitRef unit) {}
    /** Where a unit lies: its pack, offset, length and SHA-256. */
    record UnitRef(long pack,long offset,int length,byte[] sha256) {}
    private record Topology(long[] ids,double[] elevations,PassiveNetwork.NodeKind[] kinds,double[] volumes,double[] heights,boolean[] opens,com.wormzjl.icheme.science.fluid.network.VesselGeometry[] geometries,long[] pipeIds,int[] first,int[] second,
                            List<List<PipeResistance.Geometry>> sections,List<FlowControl> controls,PassiveNetwork.PhasePort[] firstPorts,PassiveNetwork.PhasePort[] secondPorts,int[] forbiddenDirections,double[] actuatorAreas,
                            com.wormzjl.icheme.science.fluid.network.HeatLaw[] heats) {}
    private static com.wormzjl.icheme.science.fluid.network.HeatLaw heat(Reader r) {
        int tag=r.u8();
        if(tag==0)return null;
        if(tag==2)return new com.wormzjl.icheme.science.fluid.network.FiredCoil(r.f64(),r.f64(),r.f64(),r.f64());
        if(tag==3) {
            double area=r.f64();var air=new com.wormzjl.icheme.science.fluid.network.AirCooled.Air(r.f64(),r.f64(),r.f64(),r.f64(),r.f64());double fans=r.f64();
            var control=control(r);return new com.wormzjl.icheme.science.fluid.network.AirCooled(area,air,fans,control,limit(r));
        }
        if(tag!=1)throw r.invalid("unknown heat law "+tag);
        double area=r.f64();var films=new com.wormzjl.icheme.science.fluid.network.HeatCurve.Films(r.f64(),r.f64(),r.f64());
        var wall=new double[r.varint(MAXIMUM_WALL_CELLS)];for(int k=0;k<wall.length;k++)wall[k]=r.f64();
        double bypass=r.f64(),giveBack=r.f64();long partner=r.i64();double active=r.f64();var control=control(r);
        return new com.wormzjl.icheme.science.fluid.network.HeatSide(area,films,wall,bypass,giveBack,partner,active,control,limit(r));
    }
    private static PassiveNetwork.PhasePort port(Reader r){int port=r.u8();if(port>=PHASE_PORTS.length)throw r.invalid("unknown pipe port "+port);return PHASE_PORTS[port];}

    /** The sections of an island's unit, its header checked against its index row. */
    static Sections islandSections(byte[] data,IndexRow row) {
        var r=new Reader(data,(int)row.unit.offset,row.unit.length,"unit of island "+row.id);
        requireHeader(r,ISLAND,row.id,row.revision,row.generation,"island "+row.id);return sections(r,"island "+row.id);
    }
    /** The facts of an island's unit; facts this build cannot read refuse the world. */
    static FluidFacts.Island islandFacts(byte[] data,Sections s,long id) {
        try{return FluidFacts.island(data,s.factsOffset(),s.factsLength(),"island "+id);}
        catch(IllegalArgumentException invalid){throw new Unreadable("The facts of island "+id+" cannot be read: "+invalid.getMessage(),invalid);}
    }
    /**
     * Reads one island unit in full and validates it against its index row: its facts, then its state, then that the two
     * agree (a disagreement fails a verifying run and is logged otherwise, the state kept). The state must be this
     * build's format: a unit whose identity, revision or generation is not the index's refuses the world; a malformed
     * state field, a fence before the committed tick, an island ahead of the epoch or an invalid certificate field is an
     * {@link IllegalArgumentException} the store answers by rebuilding from facts. A certified island is materialised
     * from its base to its committed tick without a solve; whether its certificate still holds is decided when it is
     * registered.
     */
    static IslandEntry island(byte[] data,IndexRow row,long epoch,Strings strings,Map<PackageKey,FluidThermodynamics> models) {
        var s=islandSections(data,row);return island(data,row,s,islandFacts(data,s,row.id),epoch,strings,models);
    }
    /** {@link #island} with the unit's sections and facts already read. */
    static IslandEntry island(byte[] data,IndexRow row,Sections s,FluidFacts.Island facts,long epoch,Strings strings,Map<PackageKey,FluidThermodynamics> models) {
        if(s.stateFormat()!=VERSION)throw new IllegalArgumentException("The state of island "+row.id+" is of format "+s.stateFormat()+"; this build reads "+VERSION);
        var entry=islandState(data,s,row,epoch,strings,models);
        String difference=!facts.dimension().equals(entry.dimension)||!facts.packageId().equals(entry.packageId)?"its dimension or package"
                :!facts.components().equals(models.get(entry.key()).components())?"its component axis":FluidFacts.disagreement(facts,entry.snapshot.graph(),row.committed);
        if(difference!=null) {
            if(VERIFY)throw new IllegalStateException("Island "+row.id+": its facts and its state disagree: "+difference);
            com.wormzjl.icheme.IChemE.LOGGER.error("fluid_checkpoint status=FACTS_DISAGREE island={} detail={}; the state is kept",row.id,difference);
        }
        return entry;
    }
    private static IslandEntry islandState(byte[] data,Sections s,IndexRow row,long epoch,Strings strings,Map<PackageKey,FluidThermodynamics> models) {
        long id=row.id;var r=new Reader(data,s.stateOffset(),s.stateLength(),"state of island "+id);
        String dimension=strings.get(r.varint(Integer.MAX_VALUE),"unit of island "+id),packageId=strings.get(r.varint(Integer.MAX_VALUE),"unit of island "+id);
        var model=models.get(new PackageKey(packageId));
        if(model==null)throw new IllegalArgumentException("Island "+id+": its package "+packageId+" is not in the checkpoint's package table");
        String status=r.text();
        int fenceCount=r.varint(MAXIMUM_FENCES);var fences=new LinkedHashMap<UUID,Long>();
        for(int i=0;i<fenceCount;i++) {
            var event=new UUID(r.i64(),r.i64());long tick=r.i64();
            if(fences.put(event,tick)!=null)throw r.invalid("duplicate fence "+event);
            if(tick<row.committed)throw new IllegalArgumentException("Island "+id+": fence "+event+" at tick "+tick+" precedes the committed tick "+row.committed);
        }
        var topology=topology(r);var graph=state(r,topology,model);
        Optional<PassiveIntervalSolver.Result> result=Optional.empty();
        if(r.bool()) {
            int pipes=graph.pipes().size();
            double seconds=r.f64();var flows=r.sparse(MAXIMUM_PIPES);int accepted=r.varint(Integer.MAX_VALUE),rejected=r.varint(Integer.MAX_VALUE);double pumpWork=r.f64();
            int boundaryCount=r.varint(MAXIMUM_BOUNDARIES);var boundaries=new ArrayList<ConservativeTransport.BoundaryTransfer>(boundaryCount);
            for(int i=0;i<boundaryCount;i++){long node=r.i64();var moles=r.sparse(MAXIMUM_ARRAY);double energy=r.f64();var solids=solids(r,model);int direction=r.u8()-1;int kind=r.u8();if(kind>=BOUNDARY_KINDS.length)throw r.invalid("unknown boundary kind "+kind);boundaries.add(new ConservativeTransport.BoundaryTransfer(node,moles,energy,solids,direction,BOUNDARY_KINDS[kind]));}
            int reasonCount=r.varint(MAXIMUM_REASONS);var reasons=new LinkedHashMap<String,Integer>();
            for(int i=0;i<reasonCount;i++)if(reasons.put(r.text(),r.i32())!=null)throw r.invalid("duplicate rejection reason");
            var endpointModes=modes(r,MAXIMUM_PIPES);var heads=r.sparse(MAXIMUM_PIPES);var endFlows=r.sparse(MAXIMUM_PIPES);
            int transferCount=r.varint(MAXIMUM_PIPES);var transfers=new ArrayList<PipeTransfer>(transferCount);
            for(int i=0;i<transferCount;i++){long pipe=r.i64();int count=r.varint(MAXIMUM_ARRAY);transfers.add(new PipeTransfer(pipe,stream(r,count,model),stream(r,count,model)));}
            if(seconds<=0||accepted<1||rejected<0||flows.length!=pipes||endpointModes.size()!=pipes||heads.length!=pipes||endFlows.length!=pipes||transfers.size()!=pipes)throw new IllegalArgumentException("Invalid saved interval history");
            // A closed or refused connection's end flow is exactly zero (the step solver records it so).
            for(int i=0;i<pipes;i++)if(endFlows[i]!=0&&(endpointModes.get(i)==FlowControl.Mode.CLOSED||endpointModes.get(i)==FlowControl.Mode.INLET_WRONG_PHASE))
                throw new IllegalArgumentException("Invalid saved interval history: a closed connection with an end flow");
            result=Optional.of(new PassiveIntervalSolver.Result(graph,seconds,flows,accepted,rejected,pumpWork,boundaries,reasons,endpointModes,heads,endFlows,transfers));
        }
        IslandCertificate.Signature signature=null;PassiveNetwork certifiedFrom=null;
        if(r.bool()) {
            String revision=strings.get(r.varint(Integer.MAX_VALUE),"certificate of island "+id),policy=strings.get(r.varint(Integer.MAX_VALUE),"certificate of island "+id);
            String digest=HexFormat.of().formatHex(r.raw(32));
            try{signature=new IslandCertificate.Signature(revision,policy,digest);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Island "+id+": "+invalid.getMessage());}
            certifiedFrom=related(r,r.u8(),graph,topology,model);
        }
        r.end();
        if(row.online>epoch)throw new IllegalArgumentException("Island "+id+" at online tick "+row.online+" is ahead of the checkpoint's world epoch "+epoch);
        var clock=new IslandClock.Snapshot(row.online,row.committed,row.retry,row.cadence);
        long generation=IslandCoordinator.freshPayloadGeneration();
        if(row.base==AWAKE) {
            if(row.since!=0||row.start!=0||row.horizon!=0||signature!=null)throw new IllegalArgumentException("Awake island "+id+" carries a certificate");
            return new IslandEntry(dimension,packageId,new IslandCoordinator.Snapshot(id,row.revision,graph,clock,result,status,fences,Optional.empty(),generation));
        }
        if(row.base<0)throw new IllegalArgumentException("Island "+id+": invalid certificate base tick "+row.base);
        if(signature==null||result.isEmpty())throw new IllegalArgumentException("Certified island "+id+" lacks its certificate record");
        if(row.base>row.committed)throw new IllegalArgumentException("Certified island "+id+": base tick "+row.base+" is later than its committed tick "+row.committed);
        if(row.horizon<row.committed)throw new IllegalArgumentException("Certified island "+id+": horizon "+row.horizon+" is earlier than its committed tick "+row.committed);
        if(row.start<0||row.start>=row.base)throw new IllegalArgumentException("Certified island "+id+": interval ["+row.start+", "+row.base+"] is empty or negative");
        var persisted=new IslandCertificate.Saved(row.since,row.horizon,new IslandCertificate.Interval(row.start,row.base,certifiedFrom,result.orElseThrow()),signature);
        // The island at its saved committed tick: its base inventory plus the replayed fraction, never a solve.
        var rebuilt=IslandCertificate.rebuild(persisted,model.molecularWeights());
        var certified=new IslandCoordinator.Certified(rebuilt.drift(),row.since,row.base,row.horizon,rebuilt.largestFlow(),Optional.of(persisted));
        return new IslandEntry(dimension,packageId,new IslandCoordinator.Snapshot(id,row.revision,rebuilt.graphAt(row.committed),clock,result,status,fences,Optional.of(certified),generation));
    }
    private static PassiveNetwork related(Reader r,int flag,PassiveNetwork main,Topology topology,FluidThermodynamics model) {
        return switch(flag) {
            case 1->main;
            case 2->state(r,topology,model);
            case 3->state(r,topology(r),model);
            default->throw r.invalid("unknown graph reference "+flag);
        };
    }
    private static Topology topology(Reader r) {
        int nodes=r.varint(MAXIMUM_NODES);if(nodes==0)throw new IllegalArgumentException("Invalid saved graph size");
        var ids=new long[nodes];var elevations=new double[nodes];var kinds=new PassiveNetwork.NodeKind[nodes];var volumes=new double[nodes];var heights=new double[nodes];var opens=new boolean[nodes];var geometries=new com.wormzjl.icheme.science.fluid.network.VesselGeometry[nodes];
        for(int n=0;n<nodes;n++) {
            ids[n]=r.i64();elevations[n]=r.f64();int kind=r.u8();if(kind>=NODE_KINDS.length)throw r.invalid("unknown node kind "+kind);
            kinds[n]=NODE_KINDS[kind];if(kinds[n]==PassiveNetwork.NodeKind.PORT)throw new IllegalArgumentException("Internal port cannot be persisted");volumes[n]=r.f64();heights[n]=r.f64();opens[n]=r.bool();geometries[n]=geometry(r);
        }
        int pipes=r.varint(MAXIMUM_PIPES);var pipeIds=new long[pipes];var first=new int[pipes];var second=new int[pipes];
        var sections=new ArrayList<List<PipeResistance.Geometry>>(pipes);var controls=new ArrayList<FlowControl>(pipes);
        var firstPorts=new PassiveNetwork.PhasePort[pipes];var secondPorts=new PassiveNetwork.PhasePort[pipes];
        var forbiddenDirections=new int[pipes];var actuatorAreas=new double[pipes];var heats=new com.wormzjl.icheme.science.fluid.network.HeatLaw[pipes];
        for(int p=0;p<pipes;p++) {
            pipeIds[p]=r.i64();first[p]=r.varint(nodes);second[p]=r.varint(nodes);int count=r.varint(MAXIMUM_SECTIONS);
            var list=new ArrayList<PipeResistance.Geometry>(count);for(int s=0;s<count;s++)list.add(new PipeResistance.Geometry(r.f64(),r.f64(),r.f64(),r.f64(),r.varint(1<<20)));
            sections.add(List.copyOf(list));
            controls.add(switch(r.u8()){case 0->new FlowControl.Passive();case 1->new FlowControl.Pump(r.f64(),r.f64(),r.bool());case 2->new FlowControl.PressureValve(r.f64());
                case 3->new FlowControl.Compressor(r.f64(),r.f64(),r.bool());case 4->new FlowControl.ControlValve(FlowControl.ValveSource.valueOf(r.text()),r.f64(),r.f64(),r.f64(),r.bool()?new java.util.UUID(r.i64(),r.i64()):null,r.i64());default->throw new IllegalArgumentException("Unknown saved control");});
            firstPorts[p]=port(r);secondPorts[p]=port(r);forbiddenDirections[p]=r.varint(3);actuatorAreas[p]=r.f64();heats[p]=heat(r);
        }
        return new Topology(ids,elevations,kinds,volumes,heights,opens,geometries,pipeIds,first,second,sections,controls,firstPorts,secondPorts,forbiddenDirections,actuatorAreas,heats);
    }
    private static PassiveNetwork state(Reader r,Topology t,FluidThermodynamics model) {
        var nodes=new ArrayList<PassiveNetwork.Reservoir>(t.ids.length);
        for(int n=0;n<t.ids.length;n++) {
            var moles=r.sparse(MAXIMUM_ARRAY);double energy=r.f64();var solids=solids(r,model);
            double temperature=r.f64(),pressure=r.f64();var liquid=r.sparse(MAXIMUM_ARRAY);var vapor=r.sparse(MAXIMUM_ARRAY);double waterLiquid=r.f64(),waterVapor=r.f64(),hydrocarbonPressure=r.f64();
            var inventory=new PassiveNetwork.Inventory(t.volumes[n],moles,energy,solids,t.heights[n],t.opens[n],t.geometries[n]);
            var state=Arrays.stream(moles).sum()==0&&!solids.empty()?model.solidState(temperature,pressure,solids):model.state(temperature,pressure,liquid,vapor,waterLiquid,waterVapor,hydrocarbonPressure).withSolids(solids);
            nodes.add(new PassiveNetwork.Reservoir(t.ids[n],t.elevations[n],state,t.kinds[n],inventory));
        }
        var pipes=new ArrayList<PassiveNetwork.Pipe>(t.pipeIds.length);
        for(int p=0;p<t.pipeIds.length;p++) {
            int blocked=r.varint(3);InlineFilter filter=null;
            if(r.bool()){double capacity=r.f64();var captured=solids(r,model);double energy=r.f64();boolean stopped=r.bool();filter=currentFilter(capacity,captured,energy,stopped,model);}
            pipes.add(new PassiveNetwork.Pipe(t.pipeIds[p],t.first[p],t.second[p],t.sections.get(p),t.controls.get(p),blocked,filter,t.firstPorts[p],t.secondPorts[p],t.forbiddenDirections[p]).withActuatorArea(t.actuatorAreas[p]).withHeat(t.heats[p]));
        }
        return new PassiveNetwork(nodes,pipes);
    }
    /** A saved filter under the current solid settings: capacity and clean resistance are the configured ones, and a
     * filter stopped at capacity stays stopped only if the capacity did not change (as in format 3). */
    static InlineFilter currentFilter(double capacity,SolidInventory captured,double energy,boolean stopped,FluidThermodynamics model) {
        var settings=model.solidSettings;return new InlineFilter(settings.filterCapacity(),settings.filterResistance(),captured,energy,stopped&&capacity==settings.filterCapacity());
    }
    private static SolidInventory solids(Reader r,FluidThermodynamics model) {
        int count=r.varint(SolidInventory.MAXIMUM_POPULATIONS);if(count==0)return SolidInventory.EMPTY;
        var populations=new ArrayList<SolidInventory.Population>(count);
        for(int i=0;i<count;i++) {
            var saved=new SolidMaterial(r.text(),r.text(),r.f64(),r.f64());
            populations.add(new SolidInventory.Population(record(saved,model),new ParticleSize(r.text()),r.f64()));
        }
        var solids=new SolidInventory(populations);
        if(solids.populations().size()!=count)throw r.invalid("solid populations not in canonical form");
        return solids;
    }
    /** The record a saved population's id names in the model's package ({@link FluidThermodynamics#solid}), whose
     * constants the saved ones must equal exactly: a population is read back as its record, crystal or custom solid. */
    static SolidMaterial record(SolidMaterial saved,FluidThermodynamics model) {
        SolidMaterial record;
        try{record=model.solid(saved.id());}
        catch(IllegalArgumentException unknown){throw new IllegalArgumentException(unknown.getMessage()+": the saved state names a solid this data does not have",unknown);}
        if(!saved.revision().equals(record.revision())||Double.compare(saved.density(),record.density())!=0||Double.compare(saved.heatCapacity(),record.heatCapacity())!=0)
            throw new IllegalArgumentException("Saved solid properties differ from the loaded data, so the saved state cannot be used: "+saved.id());
        return record;
    }
    private static List<FlowControl.Mode> modes(Reader r,int maximum) {
        int count=r.varint(maximum);var modes=new ArrayList<FlowControl.Mode>(count);
        for(int i=0;i<count;i++){int mode=r.u8();if(mode>=MODES.length)throw r.invalid("unknown mode "+mode);modes.add(MODES[mode]);}
        return modes;
    }
    private static PipeTransfer.Stream stream(Reader r,int count,FluidThermodynamics model) {
        if(!r.bool())return new PipeTransfer.Stream(0,new double[3][count],new double[3]);
        double mass=r.f64();var phases=new double[3][];for(int p=0;p<3;p++){phases[p]=r.sparse(MAXIMUM_ARRAY);if(phases[p].length!=count)throw r.invalid("pipe history phase of "+phases[p].length+" components, "+count+" expected");}
        var volumes=r.sparse(3);if(volumes.length!=3)throw r.invalid("pipe history needs three phase volumes");
        return new PipeTransfer.Stream(mass,phases,volumes,solids(r,model));
    }

    // ---------------- the core record ----------------

    /** The property identity every unit of one package was written under: its thermodynamic revision and energy reference. */
    record PackageRecord(String packageId,String propertyRevision,String energyRevision,List<String> energyComponents) {
        PackageKey key(){return new PackageKey(packageId);}
        static PackageRecord of(PackageKey key,FluidThermodynamics model) {
            var reference=EnergyReference.sensible(model.components());
            return new PackageRecord(key.packageId(),model.thermodynamicRevision(),reference.revision(),List.copyOf(reference.components()));
        }
    }
    record CoreRecord(int stateFormat,int factsSchema,long epoch,Strings strings,List<PackageRecord> packages,long[] packs,long[] packBytes,long nextPack,long nextGeneration,
                      long topologyGeneration,UnitRef topology,List<IndexRow> islands) {}

    /** Builds and seals a core record. */
    static CompoundTag coreTag(CoreRecord core) {
        var tag=new CompoundTag();
        tag.putInt("Envelope",ENVELOPE);tag.putInt("StateFormat",VERSION);tag.putInt("FactsSchema",FluidFacts.SCHEMA);tag.putLong("Epoch",core.epoch);
        tag.put("Strings",core.strings.tag());
        var packages=new ListTag();
        for(var p:core.packages) {
            var entry=new CompoundTag();entry.putString("Package",p.packageId);entry.putString("PropertyRevision",p.propertyRevision);
            entry.putString("EnergyRevision",p.energyRevision);var components=new ListTag();for(var c:p.energyComponents)components.add(StringTag.valueOf(c));entry.put("EnergyComponents",components);
            packages.add(entry);
        }
        tag.put("Packages",packages);
        tag.putLongArray("Packs",core.packs.clone());tag.putLongArray("PackBytes",core.packBytes.clone());tag.putLong("NextPack",core.nextPack);tag.putLong("NextGeneration",core.nextGeneration);
        if(core.topology!=null) {
            var topology=new CompoundTag();topology.putLong("Generation",core.topologyGeneration);topology.putLong("Pack",core.topology.pack);topology.putLong("Offset",core.topology.offset);
            topology.putInt("Length",core.topology.length);topology.putByteArray("SHA256",core.topology.sha256.clone());tag.put("Topology",topology);
        }
        int n=core.islands.size();
        long[] ids=new long[n],revisions=new long[n],generations=new long[n],online=new long[n],committed=new long[n],retry=new long[n],base=new long[n],since=new long[n],start=new long[n],horizon=new long[n],pack=new long[n],offset=new long[n];
        int[] cadence=new int[n],length=new int[n];byte[] digests=new byte[32*n];
        for(int i=0;i<n;i++) {
            var row=core.islands.get(i);ids[i]=row.id;revisions[i]=row.revision;generations[i]=row.generation;online[i]=row.online;committed[i]=row.committed;retry[i]=row.retry;cadence[i]=row.cadence;
            base[i]=row.base;since[i]=row.since;start[i]=row.start;horizon[i]=row.horizon;pack[i]=row.unit.pack;offset[i]=row.unit.offset;length[i]=row.unit.length;
            System.arraycopy(row.unit.sha256,0,digests,32*i,32);
        }
        var islands=new CompoundTag();
        islands.putLongArray("Id",ids);islands.putLongArray("Revision",revisions);islands.putLongArray("Generation",generations);
        islands.putLongArray("Online",online);islands.putLongArray("Committed",committed);islands.putLongArray("Retry",retry);islands.putIntArray("Cadence",cadence);
        islands.putLongArray("Base",base);islands.putLongArray("Since",since);islands.putLongArray("Start",start);islands.putLongArray("Horizon",horizon);
        islands.putLongArray("Pack",pack);islands.putLongArray("Offset",offset);islands.putIntArray("Length",length);islands.putByteArray("SHA256",digests);
        tag.put("Islands",islands);
        tag.putByteArray("SHA256",manifest(tag));
        return tag;
    }
    private static final List<String> LONG_COLUMNS=List.of("Id","Revision","Generation","Online","Committed","Retry","Base","Since","Start","Horizon","Pack","Offset");
    /**
     * Reads and validates a core record: the envelope, every field present and typed, the envelope digest, the string
     * and package tables, the pack list, and every index row's unit reference lying inside a listed pack. The units
     * themselves are read by {@link #island} and {@link #topology}. A core record of state format 19 or older (no
     * envelope) is a {@link FormatMismatchException}; a later envelope or facts schema, which a newer build wrote, is
     * {@link Unreadable}. Any state format reads: the store rebuilds what another format holds from the facts.
     */
    static CoreRecord readCore(CompoundTag tag) {
        Objects.requireNonNull(tag);
        if(!tag.contains("Envelope",Tag.TAG_INT)) {
            if(tag.contains("FluidFormat",Tag.TAG_INT))throw new FormatMismatchException("Fluid checkpoint format "+tag.getInt("FluidFormat")+" has no envelope: this build keeps the facts of"
                    +" checkpoints written from state format "+VERSION+" on (batch 2026-10-26-backward-compat), and the world's fluid network was saved before them.");
            throw new IllegalArgumentException("Not a fluid checkpoint: no Envelope");
        }
        int envelope=tag.getInt("Envelope");
        if(envelope!=ENVELOPE)throw new Unreadable("Fluid checkpoint envelope "+envelope+" cannot be read: this build reads envelope "+ENVELOPE+", so a newer build wrote it");
        int stateFormat=requireInt(tag,"StateFormat","checkpoint"),factsSchema=requireInt(tag,"FactsSchema","checkpoint");
        if(stateFormat<20)throw new Unreadable("Fluid checkpoint state format "+stateFormat+" in an envelope: envelopes carry state formats from 20 on");
        if(factsSchema<1||factsSchema>FluidFacts.SCHEMA)throw new Unreadable("Fluid checkpoint facts schema "+factsSchema+" cannot be read: this build reads schemas 1 to "+FluidFacts.SCHEMA+", so a newer build wrote it");
        long epoch=requireLong(tag,"Epoch","checkpoint");if(epoch<0)throw new IllegalArgumentException("Negative world epoch");
        if(!(tag.get("Strings") instanceof ListTag stringList)||!stringList.isEmpty()&&stringList.getElementType()!=Tag.TAG_STRING)throw new IllegalArgumentException("Missing or mistyped fluid checkpoint field Strings");
        if(!(tag.get("Packages") instanceof ListTag packageList)||!packageList.isEmpty()&&packageList.getElementType()!=Tag.TAG_COMPOUND)throw new IllegalArgumentException("Missing or mistyped fluid checkpoint field Packages");
        long[] packs=requireLongs(tag,"Packs","checkpoint"),packBytes=requireLongs(tag,"PackBytes","checkpoint");
        long nextPack=requireLong(tag,"NextPack","checkpoint"),nextGeneration=requireLong(tag,"NextGeneration","checkpoint");
        if(!(tag.get("Islands") instanceof CompoundTag islands))throw new IllegalArgumentException("Missing or mistyped fluid checkpoint field Islands");
        var columns=new HashMap<String,long[]>();for(var key:LONG_COLUMNS)columns.put(key,requireLongs(islands,key,"island"));
        int[] cadence=requireInts(islands,"Cadence","island"),length=requireInts(islands,"Length","island");byte[] digests=requireBytes(islands,"SHA256","island");
        int n=columns.get("Id").length;
        for(var column:columns.entrySet())if(column.getValue().length!=n)throw new IllegalArgumentException("Fluid island column "+column.getKey()+" holds "+column.getValue().length+" entries, "+n+" expected");
        if(cadence.length!=n||length.length!=n||digests.length!=32*n)throw new IllegalArgumentException("Fluid island columns disagree in length");
        CompoundTag topologyTag=null;
        if(tag.contains("Topology")){if(!(tag.get("Topology") instanceof CompoundTag t))throw new IllegalArgumentException("Missing or mistyped fluid checkpoint field Topology");topologyTag=t;}
        if(topologyTag!=null)for(var key:List.of("Generation","Pack","Offset"))requireLong(topologyTag,key,"topology");
        if(topologyTag!=null){requireInt(topologyTag,"Length","topology");requireBytes(topologyTag,"SHA256","topology");}
        if(!MessageDigest.isEqual(manifest(tag),requireBytes(tag,"SHA256","checkpoint")))throw new IllegalArgumentException("Fluid checkpoint checksum mismatch");
        var strings=Strings.read(stringList);
        var packages=new ArrayList<PackageRecord>();var keys=new HashSet<PackageKey>();
        for(int i=0;i<packageList.size();i++) {
            var p=packageList.getCompound(i);
            if(!(p.get("EnergyComponents") instanceof ListTag components)||!components.isEmpty()&&components.getElementType()!=Tag.TAG_STRING)throw new IllegalArgumentException("Missing or mistyped fluid package field EnergyComponents");
            var record=new PackageRecord(requireString(p,"Package","package"),requireString(p,"PropertyRevision","package"),requireString(p,"EnergyRevision","package"),
                    components.stream().map(Tag::getAsString).toList());
            if(!keys.add(record.key()))throw new IllegalArgumentException("Duplicate saved package "+record.packageId);
            packages.add(record);
        }
        if(packs.length!=packBytes.length)throw new IllegalArgumentException("Fluid pack list and pack sizes disagree");
        var sizes=new HashMap<Long,Long>();long previous=0;
        for(int i=0;i<packs.length;i++) {
            if(packs[i]<=previous)throw new IllegalArgumentException("Fluid pack list is not strictly increasing and positive");previous=packs[i];
            if(packBytes[i]<FluidCheckpointStore.PACK_HEADER)throw new IllegalArgumentException("Fluid pack "+packs[i]+" is shorter than its header");
            sizes.put(packs[i],packBytes[i]);
        }
        if(nextPack<=previous)throw new IllegalArgumentException("Fluid checkpoint's next pack "+nextPack+" is not after its last pack "+previous);
        var generations=new HashSet<Long>();var rows=new ArrayList<IndexRow>(n);
        UnitRef topology=null;long topologyGeneration=0;
        if(topologyTag!=null) {
            topologyGeneration=topologyTag.getLong("Generation");
            topology=unit(sizes,topologyTag.getLong("Pack"),topologyTag.getLong("Offset"),topologyTag.getInt("Length"),topologyTag.getByteArray("SHA256"),"the topology");
            generation(generations,topologyGeneration,nextGeneration,"the topology");
        }
        var ids=columns.get("Id");var digest=new byte[32];
        for(int i=0;i<n;i++) {
            System.arraycopy(digests,32*i,digest,0,32);
            var row=new IndexRow(ids[i],columns.get("Revision")[i],columns.get("Generation")[i],columns.get("Online")[i],columns.get("Committed")[i],columns.get("Retry")[i],cadence[i],
                    columns.get("Base")[i],columns.get("Since")[i],columns.get("Start")[i],columns.get("Horizon")[i],
                    unit(sizes,columns.get("Pack")[i],columns.get("Offset")[i],length[i],digest.clone(),"island "+ids[i]));
            if(row.id<=0||row.revision<0)throw new IllegalArgumentException("Invalid island identity "+row.id+" revision "+row.revision);
            generation(generations,row.generation,nextGeneration,"island "+row.id);
            rows.add(row);
        }
        return new CoreRecord(stateFormat,factsSchema,epoch,strings,packages,packs,packBytes,nextPack,nextGeneration,topologyGeneration,topology,rows);
    }
    private static UnitRef unit(Map<Long,Long> sizes,long pack,long offset,int length,byte[] sha256,String what) {
        Long size=sizes.get(pack);
        if(size==null)throw new IllegalArgumentException("The unit of "+what+" lies in pack "+pack+", which the checkpoint does not list");
        if(sha256.length!=32||length<UNIT_HEADER||offset<FluidCheckpointStore.PACK_HEADER||offset>size-length)
            throw new IllegalArgumentException("The unit of "+what+" at ["+offset+", +"+length+") does not lie inside pack "+pack+" of "+size+" bytes");
        return new UnitRef(pack,offset,length,sha256);
    }
    private static void generation(Set<Long> seen,long generation,long next,String what) {
        if(generation<1||generation>=next||!seen.add(generation))throw new IllegalArgumentException("Invalid or duplicate unit generation "+generation+" of "+what+" (next "+next+")");
    }
    /** The world epoch of a core record that {@link #readCore} accepted. */
    public static long epoch(CompoundTag tag){return requireLong(tag,"Epoch","checkpoint");}

    // ---------------- integrity ----------------

    /** The envelope digest: every field of the core record in a fixed order, every text with its length. */
    static byte[] manifest(CompoundTag tag) {
        var d=new Digest();d.text("icheme-fluid-checkpoint-envelope-1");
        d.number(requireInt(tag,"Envelope","checkpoint"));d.number(requireInt(tag,"StateFormat","checkpoint"));d.number(requireInt(tag,"FactsSchema","checkpoint"));d.number(requireLong(tag,"Epoch","checkpoint"));
        var strings=(ListTag)tag.get("Strings");d.number(strings.size());for(int i=0;i<strings.size();i++)d.text(strings.getString(i));
        var packages=(ListTag)tag.get("Packages");d.number(packages.size());
        for(int i=0;i<packages.size();i++) {
            var p=packages.getCompound(i);
            d.text(requireString(p,"Package","package"));d.text(requireString(p,"PropertyRevision","package"));d.text(requireString(p,"EnergyRevision","package"));
            if(!(p.get("EnergyComponents") instanceof ListTag components))throw new IllegalArgumentException("Missing or mistyped fluid package field EnergyComponents");
            d.number(components.size());for(int c=0;c<components.size();c++)d.text(components.getString(c));
        }
        d.longs(requireLongs(tag,"Packs","checkpoint"));d.longs(requireLongs(tag,"PackBytes","checkpoint"));d.number(requireLong(tag,"NextPack","checkpoint"));d.number(requireLong(tag,"NextGeneration","checkpoint"));
        if(tag.get("Topology") instanceof CompoundTag topology) {
            d.text("topology");for(var key:List.of("Generation","Pack","Offset"))d.number(requireLong(topology,key,"topology"));d.number(requireInt(topology,"Length","topology"));d.bytes(requireBytes(topology,"SHA256","topology"));
        } else if(tag.contains("Topology"))throw new IllegalArgumentException("Missing or mistyped fluid checkpoint field Topology");
        else d.text("no topology");
        var islands=(CompoundTag)tag.get("Islands");
        for(var key:LONG_COLUMNS){d.text(key);d.longs(requireLongs(islands,key,"island"));}
        d.text("Cadence");d.ints(requireInts(islands,"Cadence","island"));d.text("Length");d.ints(requireInts(islands,"Length","island"));
        d.text("SHA256");d.bytes(requireBytes(islands,"SHA256","island"));
        return d.digest();
    }
    private static final class Digest {
        private final MessageDigest sha;private final ByteBuffer buffer=ByteBuffer.allocate(8);
        Digest(){try{sha=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
        void number(long value){buffer.clear();buffer.putLong(value);sha.update(buffer.array(),0,8);}
        void bytes(byte[] value){number(value.length);sha.update(value);}
        void text(String value){bytes(value.getBytes(StandardCharsets.UTF_8));}
        void longs(long[] values){number(values.length);var b=ByteBuffer.allocate(8*values.length);b.asLongBuffer().put(values);sha.update(b.array());}
        void ints(int[] values){number(values.length);var b=ByteBuffer.allocate(4*values.length);b.asIntBuffer().put(values);sha.update(b.array());}
        byte[] digest(){return sha.digest();}
    }
    static byte[] sha256(byte[] bytes){return sha256(bytes,0,bytes.length);}
    static byte[] sha256(byte[] bytes,int offset,int length) {
        try{var sha=MessageDigest.getInstance("SHA-256");sha.update(bytes,offset,length);return sha.digest();}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static long requireLong(CompoundTag tag,String key,String where){if(!tag.contains(key,Tag.TAG_LONG))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return tag.getLong(key);}
    private static int requireInt(CompoundTag tag,String key,String where){if(!tag.contains(key,Tag.TAG_INT))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return tag.getInt(key);}
    private static String requireString(CompoundTag tag,String key,String where){if(!tag.contains(key,Tag.TAG_STRING))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return tag.getString(key);}
    private static byte[] requireBytes(CompoundTag tag,String key,String where) {
        if(!(tag.get(key) instanceof ByteArrayTag bytes))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return bytes.getAsByteArray();
    }
    private static long[] requireLongs(CompoundTag tag,String key,String where) {
        if(!(tag.get(key) instanceof LongArrayTag values))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return values.getAsLongArray();
    }
    private static int[] requireInts(CompoundTag tag,String key,String where) {
        if(!(tag.get(key) instanceof IntArrayTag values))throw new IllegalArgumentException("Missing or mistyped fluid "+where+" field "+key);return values.getAsIntArray();
    }

    /** A checkpoint saved before the envelope (state format 19 or older): its world is reset once (owner decision D4). Malformed data is a separate failure and must not trigger a reset. */
    static final class FormatMismatchException extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        FormatMismatchException(String message){super(message);}
    }
    /** A checkpoint whose envelope or facts this build cannot read: the world is refused and its files stay as they are. */
    static final class Unreadable extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        Unreadable(String message){super(message);}
        Unreadable(String message,Throwable cause){super(message,cause);}
    }

    // ---------------- in-memory checkpoints, for tests and tools (edits: test CheckpointImages) ----------------

    /** A checkpoint as its core record and its packs, held in memory: what a world's storage holds, without files. */
    public record Image(CompoundTag core,Map<Long,byte[]> packs) {
        public Image {Objects.requireNonNull(core);packs=Map.copyOf(packs);}
    }
    /** A self-contained checkpoint of {@code checkpoint} alone, at the epoch of its most advanced island. */
    public static Image encode(Checkpoint checkpoint,Function<PackageKey,FluidThermodynamics> models) {
        return encode(checkpoint,checkpoint.islands.stream().mapToLong(i->i.snapshot.clock().onlineTick()).max().orElse(0),models);
    }
    public static Image encode(Checkpoint checkpoint,long epoch,Function<PackageKey,FluidThermodynamics> models) {
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.empty(),epoch,models));return store.image();
    }
    /** Reads an in-memory checkpoint with every validation a world load makes. */
    public static Checkpoint decode(Image image,Function<PackageKey,FluidThermodynamics> models) {
        return FluidCheckpointStore.memory(image).load(image.core(),models).checkpoint();
    }
    // ---------------- unit binary primitives ----------------

    /**
     * The binary primitives of a checkpoint storage unit (format 4 onwards): big-endian fixed-width integers and doubles, unsigned
     * LEB128 variable-length integers, UTF-8 texts with their byte length, and sparse double arrays - a bit mask of the
     * entries whose raw bits are not zero, then those entries' raw bits. Every double is written and read with its exact
     * bits and must be finite, as every number of format 3 had to be; a sparse array never stores an entry whose raw bits
     * are zero, so one value has one encoding and a unit re-encodes to the same bytes. The reader refuses a truncated unit,
     * an overlong or out-of-range integer, malformed UTF-8, a non-canonical sparse entry and trailing bytes.
     */
    static final class Writer {
        private byte[] buffer=new byte[4096];private int size;
        int size(){return size;}
        byte[] toByteArray(){return Arrays.copyOf(buffer,size);}
        /** Whether the bytes written in [from, to) equal those in [otherFrom, otherTo). */
        boolean sameBytes(int from,int to,int otherFrom,int otherTo){return Arrays.equals(buffer,from,to,buffer,otherFrom,otherTo);}
        /** Copies bytes [from, to) of {@code other}'s buffer here. */
        void append(Writer other,int from,int to){ensure(to-from);System.arraycopy(other.buffer,from,buffer,size,to-from);size+=to-from;}
        void truncate(int length){if(length<0||length>size)throw new IllegalArgumentException();size=length;}
        private void ensure(int more){if(size+more>buffer.length)buffer=Arrays.copyOf(buffer,Math.max(buffer.length*2,size+more));}
        void u8(int value){ensure(1);buffer[size++]=(byte)value;}
        void bool(boolean value){u8(value?1:0);}
        void i32(int value){ensure(4);buffer[size++]=(byte)(value>>>24);buffer[size++]=(byte)(value>>>16);buffer[size++]=(byte)(value>>>8);buffer[size++]=(byte)value;}
        void i64(long value){ensure(8);for(int shift=56;shift>=0;shift-=8)buffer[size++]=(byte)(value>>>shift);}
        /** A finite double with its exact bits. */
        void f64(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite checkpoint number");i64(Double.doubleToRawLongBits(value));}
        void varint(int value){if(value<0)throw new IllegalArgumentException("Negative checkpoint count");varlong(value);}
        void varlong(long value) {
            if(value<0)throw new IllegalArgumentException("Negative checkpoint count");
            ensure(10);while((value&~0x7FL)!=0){buffer[size++]=(byte)((value&0x7F)|0x80);value>>>=7;}buffer[size++]=(byte)value;
        }
        void text(String value){var bytes=value.getBytes(StandardCharsets.UTF_8);varint(bytes.length);raw(bytes);}
        void raw(byte[] bytes){ensure(bytes.length);System.arraycopy(bytes,0,buffer,size,bytes.length);size+=bytes.length;}
        /** A double array: its length, a mask of the entries whose raw bits are not zero, then those entries. */
        void sparse(double[] values) {
            varint(values.length);int mask=size;int maskBytes=(values.length+7)/8;ensure(maskBytes);Arrays.fill(buffer,size,size+maskBytes,(byte)0);size+=maskBytes;
            for(int i=0;i<values.length;i++) {
                long bits=Double.doubleToRawLongBits(values[i]);if(bits==0)continue;
                if(!Double.isFinite(values[i]))throw new IllegalArgumentException("Nonfinite checkpoint number");
                buffer[mask+i/8]|=(byte)(1<<(i%8));i64(bits);
            }
        }
    }

    static final class Reader {
        private final byte[] data;private final int end;private int position;private final String what;
        Reader(byte[] data,int offset,int length,String what) {
            if(offset<0||length<0||offset+length>data.length)throw new IllegalArgumentException("Invalid saved "+what+": out of bounds");
            this.data=data;this.position=offset;this.end=offset+length;this.what=what;
        }
        IllegalArgumentException invalid(String reason){return new IllegalArgumentException("Invalid saved "+what+": "+reason);}
        private void need(int bytes){if(end-position<bytes)throw invalid("truncated");}
        int u8(){need(1);return data[position++]&0xFF;}
        boolean bool(){int value=u8();if(value>1)throw invalid("invalid flag "+value);return value==1;}
        int i32(){need(4);int value=ByteBuffer.wrap(data,position,4).getInt();position+=4;return value;}
        long i64(){need(8);long value=ByteBuffer.wrap(data,position,8).getLong();position+=8;return value;}
        double f64(){double value=Double.longBitsToDouble(i64());if(!Double.isFinite(value))throw invalid("nonfinite checkpoint number");return value;}
        long varlong() {
            long value=0;
            for(int shift=0;;shift+=7) {
                int b=u8();if(shift==63&&(b&0x7E)!=0)throw invalid("integer overflow");
                value|=(long)(b&0x7F)<<shift;
                if((b&0x80)==0){if(b==0&&shift>0)throw invalid("overlong integer");return value;}
                if(shift>=63)throw invalid("integer overflow");
            }
        }
        /** A count at most {@code maximum}. */
        int varint(int maximum){long value=varlong();if(value>maximum)throw invalid("count "+value+" exceeds "+maximum);return (int)value;}
        String text() {
            int length=varint(MAXIMUM_TEXT);need(length);
            try{var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,position,length)).toString();position+=length;return text;}
            catch(CharacterCodingException malformed){throw invalid("malformed text");}
        }
        byte[] raw(int length){need(length);var bytes=Arrays.copyOfRange(data,position,position+length);position+=length;return bytes;}
        int position(){return position;}
        /** Passes over {@code length} bytes, which must lie inside the unit. */
        void skip(int length){need(length);position+=length;}
        double[] sparse(int maximumLength) {
            int length=varint(maximumLength);int maskBytes=(length+7)/8;need(maskBytes);int mask=position;position+=maskBytes;
            if(length%8!=0&&(data[mask+maskBytes-1]&0xFF)>>>(length%8)!=0)throw invalid("mask bits beyond the array");
            var values=new double[length];
            for(int i=0;i<length;i++) {
                if((data[mask+i/8]&(1<<(i%8)))==0)continue;
                long bits=i64();if(bits==0)throw invalid("zero entry stored in a sparse array");
                values[i]=Double.longBitsToDouble(bits);if(!Double.isFinite(values[i]))throw invalid("nonfinite checkpoint number");
            }
            return values;
        }
        /** Refuses anything left after the last field. */
        void end(){if(position!=end)throw invalid((end-position)+" trailing bytes");}
    }
}
