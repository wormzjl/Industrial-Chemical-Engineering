package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidCheckpointCodec.Reader;
import com.wormzjl.icheme.runtime.fluid.FluidCheckpointCodec.Writer;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.InlineFilter;
import com.wormzjl.icheme.science.fluid.network.NetworkReduction;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The facts of the fluid checkpoint (AGENTS.md, "Saves and data formats"): what the player built and put in, in an
 * encoding every later build reads. The topology unit's facts are the device registry with every setting, the
 * ledger's totals, the pending solid recoveries and the identity counter; an island unit's facts are its vessels'
 * contents, its filters' cakes and, for a certified island, the drift its certificate replays. Everything else a unit
 * holds is state, which the engine can compute again. A build that cannot use a checkpoint's state (another state
 * format, changed property data, a state that does not validate) rebuilds the islands from these facts alone
 * ({@link #rebuild}); a build that can still reads every facts section and checks it against the state
 * ({@link #disagreement}), so the facts reader runs at every load.
 *
 * <p>Facts schema {@link #SCHEMA}. A section is its table of texts, then one record. A record is its field count, then
 * its fields in a fixed order. A field is only ever appended to its record and never changes meaning or unit, so a
 * record an older build wrote lacks trailing fields only, and each reads the default written beside its read: the
 * behaviour before the field existed. A record with more fields than this build reads was written by a newer build and
 * is refused. Enum constants are written by name; {@link #ALIASES} maps a renamed or removed one. An optional value is a
 * list of at most one.</p>
 */
final class FluidFacts {
    /** 1: the 0.13.0 records. 2 (batch 2026-10-26-pipe-laying-system): a registration's {@code joints}, a rack lane's width and face points, a position's rack lane.
     *  3 (batch 2026-10-26-process-control): a device spec's vessel geometry, the {@code control_valve} control.
     *  4 (batch 2026-10-26-multi-machine-framework): the world's basis retires its hash of the package data (written empty)
     *  and appends the data's identity, the declared revision of every record it reads (owner decisions 2026-10-26).
     *  5 (batch 2026-10-27-heat-exchanger-design): a geometry's channel count, a heat side's unit on its registration.
     *  6 (batch 2026-10-28-column-operation): a registration's column unit (the running column that holds it), the column units. */
    static final int SCHEMA=6;
    /** Renamed or removed enum constants, per enum: the saved name to the current one. None at schema 6. */
    private static final Map<Class<?>,Map<String,String>> ALIASES=Map.of();
    private static final int MAXIMUM_FIELDS=256,MAXIMUM_ITEMS=1<<22,MAXIMUM_TEXTS=1<<20,MAXIMUM_ARRAY=4096,MAXIMUM_CHANNELS=1<<20;
    /** The cadence an island rebuilt from facts starts at (ticks): the shortest the clock allows, as after a merge. */
    static final int REBUILT_CADENCE=20;
    private FluidFacts() {}

    // ---------------- the record encoding ----------------

    /** One section: its text table, then the root record {@code root} writes. */
    static byte[] section(Consumer<Fields> root) {
        var texts=new LinkedHashMap<String,Integer>();var body=new Fields(texts);root.accept(body);
        var w=new Writer();w.varint(texts.size());for(var text:texts.keySet())w.text(text);body.writeTo(w);return w.toByteArray();
    }
    /** The fields of one record, written in order. */
    static final class Fields {
        private final Map<String,Integer> texts;private final Writer w=new Writer();private int count;
        private Fields(Map<String,Integer> texts){this.texts=texts;}
        Fields f64(double value){w.f64(value);count++;return this;}
        /** A non-negative integer. */
        Fields count(long value){w.varlong(value);count++;return this;}
        Fields i64(long value){w.i64(value);count++;return this;}
        Fields i32(int value){w.i32(value);count++;return this;}
        Fields bool(boolean value){w.bool(value);count++;return this;}
        Fields text(String value){w.varint(texts.computeIfAbsent(Objects.requireNonNull(value),ignored->texts.size()));count++;return this;}
        Fields name(Enum<?> value){return text(value.name());}
        Fields doubles(double[] values){w.sparse(values);count++;return this;}
        Fields record(Consumer<Fields> fields){var nested=new Fields(texts);fields.accept(nested);nested.writeTo(w);count++;return this;}
        <T> Fields list(Collection<T> items,BiConsumer<Fields,T> each) {
            w.varint(items.size());for(var item:items){var nested=new Fields(texts);each.accept(nested,item);nested.writeTo(w);}count++;return this;
        }
        private void writeTo(Writer target){target.varint(count);target.append(w,0,w.size());}
    }
    /** Reads one section {@link #section} wrote, with nothing after it. */
    static <T> T read(byte[] data,int offset,int length,String what,Function<Record,T> root) {
        var r=new Reader(data,offset,length,what);int n=r.varint(MAXIMUM_TEXTS);var texts=new String[n];var seen=new HashSet<String>();
        for(int i=0;i<n;i++){texts[i]=r.text();if(!seen.add(texts[i]))throw r.invalid("duplicate text "+texts[i]);}
        var record=new Record(r,texts);var value=root.apply(record);record.end();r.end();return value;
    }
    /** One record being read: each read takes the next field, or gives its default when the record ends before it. */
    static final class Record {
        private final Reader r;private final String[] texts;private final int count;private int next;
        private Record(Reader r,String[] texts){this.r=r;this.texts=texts;count=r.varint(MAXIMUM_FIELDS);}
        private boolean present(){return next++<count;}
        IllegalArgumentException invalid(String reason){return r.invalid(reason);}
        double f64(double absent){return present()?r.f64():absent;}
        long count(long absent){return present()?r.varlong():absent;}
        long i64(long absent){return present()?r.i64():absent;}
        int i32(int absent){return present()?r.i32():absent;}
        boolean bool(boolean absent){return present()?r.bool():absent;}
        String text(String absent){return present()?texts[r.varint(texts.length-1)]:absent;}
        <E extends Enum<E>> E name(Class<E> type,E absent) {
            if(!present())return absent;
            String saved=texts[r.varint(texts.length-1)];String name=ALIASES.getOrDefault(type,Map.of()).getOrDefault(saved,saved);
            try{return Enum.valueOf(type,name);}catch(IllegalArgumentException unknown){throw invalid("unknown "+type.getSimpleName()+" "+saved);}
        }
        double[] doubles(double[] absent){return present()?r.sparse(MAXIMUM_ARRAY):absent;}
        <T> T record(T absent,Function<Record,T> read){if(!present())return absent;var nested=new Record(r,texts);var value=read.apply(nested);nested.end();return value;}
        <T> List<T> list(List<T> absent,Function<Record,T> read) {
            if(!present())return absent;int n=r.varint(MAXIMUM_ITEMS);var items=new ArrayList<T>(Math.min(n,1024));
            for(int i=0;i<n;i++){var nested=new Record(r,texts);items.add(read.apply(nested));nested.end();}
            return items;
        }
        /** Refuses fields this build does not read: a newer build wrote them. */
        private void end(){if(next<count)throw invalid("a record of "+count+" fields, of which this build reads "+next+": written by a newer build");}
    }

    // ---------------- shared records ----------------

    /** A saved solid population, before a model names its material. */
    record Solid(String id,String revision,double density,double heatCapacity,String size,double mass) {
        static List<Solid> of(SolidInventory inventory) {
            var list=new ArrayList<Solid>(inventory.populations().size());
            for(var p:inventory.populations()){var m=p.material();list.add(new Solid(m.id(),m.revision(),m.density(),m.heatCapacity(),p.size().metres(),p.massKg()));}
            return list;
        }
        boolean same(SolidInventory.Population p) {
            var m=p.material();return id.equals(m.id())&&revision.equals(m.revision())&&density==m.density()&&heatCapacity==m.heatCapacity()&&size.equals(p.size().metres())&&mass==p.massKg();
        }
        /** A custom solid's ledger key, as {@link WorldTopologyLedger.MaterialTotal#plus} books it. */
        String key(){return id+"@"+size;}
    }
    private static void solids(Fields f,List<Solid> solids) {
        f.list(solids,(s,p)->s.text(p.id).text(p.revision).f64(p.density).f64(p.heatCapacity).text(p.size).f64(p.mass));
    }
    private static List<Solid> solids(Record r) {
        return r.list(List.of(),s->new Solid(s.text(""),s.text(""),s.f64(0),s.f64(0),s.text(""),s.f64(0)));
    }
    /** The saved populations as the records {@code record} names; a population it names no record for (null) is left out. */
    static SolidInventory inventory(List<Solid> saved,Function<Solid,SolidMaterial> record) {
        if(saved.isEmpty())return SolidInventory.EMPTY;
        var populations=new ArrayList<SolidInventory.Population>(saved.size());
        for(var s:saved){var material=record.apply(s);if(material!=null)populations.add(new SolidInventory.Population(material,new ParticleSize(s.size),s.mass));}
        if(populations.isEmpty())return SolidInventory.EMPTY;
        var inventory=new SolidInventory(populations);
        if(inventory.populations().size()!=populations.size())throw new IllegalArgumentException("Saved solid populations not in canonical form");
        return inventory;
    }
    private static void uuid(Fields f,UUID id){f.record(u->u.i64(id.getMostSignificantBits()).i64(id.getLeastSignificantBits()));}
    private static UUID uuid(Record r){return r.record(null,u->new UUID(u.i64(0),u.i64(0)));}
    /** A rack lane is written only when there is one: a block's position keeps its schema-1 record. */
    private static void position(Fields f,PhysicalFluidTopology.Position p){f.record(q->{q.text(p.dimension()).i32(p.x()).i32(p.y()).i32(p.z());if(p.lane()!=0)q.i32(p.lane());});}
    private static PhysicalFluidTopology.Position position(Record r) {
        return r.record(null,q->{var dimension=q.text("");int x=q.i32(0),y=q.i32(0),z=q.i32(0),lane=q.i32(0);
            try{return new PhysicalFluidTopology.Position(dimension,x,y,z,lane);}catch(IllegalArgumentException invalid){throw q.invalid(invalid.getMessage());}});
    }
    private static <T> T required(T value,Record r,String what){if(value==null)throw r.invalid("missing "+what);return value;}
    private static <T> T valid(Record r,String what,java.util.function.Supplier<T> build) {
        try{return build.get();}catch(IllegalArgumentException|NullPointerException invalid){throw r.invalid(what+": "+invalid.getMessage());}
    }

    // ---------------- the world (the topology unit) ----------------

    /** A pending solid recovery as saved. */
    record Recovery(UUID id,PhysicalFluidTopology.Position position,UUID player,List<Solid> solids,double energy) {}
    /**
     * The topology unit's facts as saved: compositions and totals lie on {@code basis}'s axis. {@code removedMovers}: the
     * saved one-block pumps and compressors this build no longer has (owner decision 2026-10-28, batch
     * 2026-10-28-old-mover-removal), left out of {@code registrations}. A mover holds no stock, so nothing is booked; the
     * islands are rebuilt from the other facts.
     */
    record World(long nextIdentity,WorldTopologyLedger.FluidBasis basis,List<WorldTopologyLedger.Registration> registrations,
                 WorldTopologyLedger.MaterialTotal constructed,WorldTopologyLedger.MaterialTotal destroyed,List<Recovery> recoveries,WorldTopologyLedger.Restated restated,
                 List<String> removedMovers,List<ColumnUnit> columns) {
        /** The ledger these facts and {@code events} make at {@code onlineTick}, its recoveries' solids named by {@code record}. */
        WorldTopologyLedger.Snapshot snapshot(long onlineTick,List<WorldTopologyLedger.Event> events,Function<Solid,SolidMaterial> record) {            var active=new HashMap<Long,WorldTopologyLedger.Registration>();
            for(var r:registrations)if(active.put(r.device().id(),r)!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate device "+r.device().id());
            var pending=new HashMap<UUID,WorldTopologyLedger.RecoveredSolid>();
            for(var r:recoveries)if(pending.put(r.id,new WorldTopologyLedger.RecoveredSolid(r.position,r.player,inventory(r.solids,record),r.energy))!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate recovery "+r.id);
            var units=new HashMap<Long,ColumnUnit>();for(var u:columns)if(units.put(u.id(),u)!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate column unit "+u.id());
            var snapshot=new WorldTopologyLedger.Snapshot(onlineTick,nextIdentity,active,events,constructed,destroyed,basis,pending,restated,units);
            new WorldTopologyLedger(snapshot);return snapshot;
        }
    }
    static byte[] world(WorldTopologyLedger.Snapshot s) {
        return section(f->{
            var b=s.basis();f.count(s.nextIdentity()).record(q->q.text(b.packageId()).list(b.components(),(c,id)->c.text(id)).text("").text(b.revision()));
            var active=new ArrayList<>(s.active().values());active.sort(Comparator.comparingLong(r->r.device().id()));
            f.list(active,FluidFacts::registration);
            f.record(t->total(t,s.constructed())).record(t->total(t,s.destroyed()));
            var recoveries=new TreeMap<>(s.recoveries());
            f.list(recoveries.entrySet(),(q,e)->{var r=e.getValue();uuid(q,e.getKey());position(q,r.position());q.list(r.player()==null?List.<UUID>of():List.of(r.player()),FluidFacts::uuid);
                solids(q,Solid.of(r.solids()));q.f64(r.energyJoule());});
            var restated=s.restated();
            f.record(q->q.f64(restated.energy()).list(new TreeMap<>(restated.removedMoles()).entrySet(),(e,m)->e.text(m.getKey()).f64(m.getValue()))
                    .list(new TreeMap<>(restated.removedSolids()).entrySet(),(e,m)->e.text(m.getKey()).f64(m.getValue())));
            // Schema 6: the column units, by identity.
            f.list(new TreeMap<>(s.columns()).values(),FluidFacts::column);
        });
    }
    static World world(byte[] data,int offset,int length) {
        return read(data,offset,length,"facts of the topology unit",f->{
            long next=f.count(0);
            // The third field is retired (schema 1's hash of the package data, owner decision 2026-10-26): written empty,
            // read as empty or as that hash, never used. A schema 1 basis has no revision: "" restates the world once.
            var basis=f.record(null,q->{var id=q.text("");var components=q.list(List.of(),c->c.text(""));var retired=q.text("");var revision=q.text("");
                if(!retired.isEmpty()&&!retired.matches("[0-9a-f]{64}:[0-9a-f]{64}"))throw q.invalid("basis: invalid retired fingerprint");
                return valid(q,"basis",()->new WorldTopologyLedger.FluidBasis(id,components,revision));});
            var removedMovers=new ArrayList<String>();
            var registrations=new ArrayList<>(f.list(List.of(),q->registration(q,removedMovers)));registrations.removeIf(Objects::isNull);
            if(!removedMovers.isEmpty())warn("one-block movers removed (batch 2026-10-28-old-mover-removal): "+removedMovers);
            var constructed=f.record(null,FluidFacts::total);var destroyed=f.record(null,FluidFacts::total);
            var recoveries=f.list(List.of(),q->{var id=required(uuid(q),q,"recovery identity");var position=required(position(q),q,"recovery position");
                var player=q.list(List.of(),FluidFacts::uuid);var solids=solids(q);double energy=q.f64(0);
                return new Recovery(id,position,player.isEmpty()?null:player.getFirst(),solids,energy);});
            var restated=f.record(WorldTopologyLedger.Restated.NONE,q->{double energy=q.f64(0);
                var moles=new HashMap<String,Double>();for(var e:q.list(List.of(),e->Map.entry(e.text(""),e.f64(0))))moles.put(e.getKey(),e.getValue());
                var solids=new HashMap<String,Double>();for(var e:q.list(List.of(),e->Map.entry(e.text(""),e.f64(0))))solids.put(e.getKey(),e.getValue());
                return valid(q,"restated totals",()->new WorldTopologyLedger.Restated(energy,moles,solids));});
            var columns=f.list(List.<ColumnUnit>of(),FluidFacts::column);
            required(basis,f,"basis");required(constructed,f,"constructed total");required(destroyed,f,"destroyed total");
            return new World(next,basis,registrations,constructed,destroyed,recoveries,restated,List.copyOf(removedMovers),columns);
        });
    }
    private static void total(Fields f,WorldTopologyLedger.MaterialTotal t) {
        f.doubles(t.moles()).f64(t.totalEnergy()).list(new TreeMap<>(t.solidMasses()).entrySet(),(e,m)->e.text(m.getKey()).f64(m.getValue()));
    }
    private static WorldTopologyLedger.MaterialTotal total(Record f) {
        var moles=f.doubles(null);double energy=f.f64(0);var solids=new HashMap<String,Double>();
        for(var e:f.list(List.of(),e->Map.entry(e.text(""),e.f64(0))))if(solids.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate solid total");
        required(moles,f,"total moles");return valid(f,"total",()->new WorldTopologyLedger.MaterialTotal(moles,energy,solids));
    }
    private static void registration(Fields f,WorldTopologyLedger.Registration r) {
        var d=r.device();var g=d.geometry();var s=r.spec();
        f.count(d.id());position(f,d.position());f.name(d.kind()).name(d.facing())
                .record(q->q.f64(g.length()).f64(g.diameter()).f64(g.roughness()).f64(g.minorLoss()).count(g.channels()))
                .record(q->control(q,d.control())).bool(d.generatorEnabled()).name(d.cutoff()).bool(d.inlineFilter())
                .list(d.ports(),(q,p)->{position(q,p.cell());q.name(p.face()).name(p.port()).name(p.role());})
                .record(q->q.f64(s.volume()).f64(s.temperature()).f64(s.pressure()).doubles(s.composition())
                        .record(feed->feed.f64(s.solids().volumeFraction()).list(s.solids().grades(),(e,grade)->e.text(grade.material()).text(grade.size().metres()).f64(grade.massShare())))
                        .f64(s.levelHeight()).bool(s.open()).record(shape->geometry(shape,s.geometry())))
                .count(r.revision()).count(d.joints()).count(d.slots()).count(d.points())
                .list(d.heat()==null?List.<HeatUnit>of():List.of(d.heat()),FluidFacts::heatUnit).count(d.suspendedBy());
    }
    private static WorldTopologyLedger.Registration registration(Record f){return registration(f,null);}
    /**
     * A saved registration. A one-block pump or compressor (portless) is gone: with {@code removed} it is read whole, named
     * there and returned as null; without (a queued event's replacement, which is state) it does not read.
     */
    private static WorldTopologyLedger.Registration registration(Record f,List<String> removed) {
        long id=f.count(0);var position=position(f);
        var kind=f.name(TopologyCompiler.Kind.class,null);var facing=f.name(PhysicalFluidTopology.Direction.class,null);
        var geometry=f.record(null,q->{double length=q.f64(0),diameter=q.f64(0),roughness=q.f64(0),minorLoss=q.f64(0);
            // Schema 5: a geometry written before channels existed is one channel, as every run was then.
            long channels=q.count(1);if(channels<1||channels>MAXIMUM_CHANNELS)throw q.invalid("channels "+channels);
            return valid(q,"geometry",()->new PipeResistance.Geometry(length,diameter,roughness,minorLoss,(int)channels));});
        var control=f.record((FlowControl)new FlowControl.Passive(),FluidFacts::control);
        boolean generatorEnabled=f.bool(true);var cutoff=f.name(PhysicalFluidTopology.Cutoff.class,PhysicalFluidTopology.Cutoff.NONE);boolean filter=f.bool(false);
        var ports=f.list(List.of(),q->{var cell=required(position(q),q,"port cell");var face=q.name(PhysicalFluidTopology.Direction.class,null);
            var port=q.name(PassiveNetwork.PhasePort.class,PassiveNetwork.PhasePort.BULK);var role=q.name(PhysicalFluidTopology.ConnectionRole.class,PhysicalFluidTopology.ConnectionRole.VESSEL);
            return valid(q,"port",()->new PhysicalFluidTopology.Port(cell,face,port,role));});
        var spec=f.record(null,q->{double volume=q.f64(0),temperature=q.f64(0),pressure=q.f64(0);var composition=q.doubles(null);
            var feed=q.record(SlurryFeed.NONE,e->{double fraction=e.f64(0);var grades=e.list(List.of(),g->{var material=g.text("");var size=g.text("");double share=g.f64(0);
                return valid(g,"slurry grade",()->new SlurryFeed.Grade(material,new ParticleSize(size),share));});return valid(e,"slurry feed",()->new SlurryFeed(fraction,grades));});
            double levelHeight=q.f64(com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.LEVEL_HEAD_HEIGHT);boolean open=q.bool(false);
            var shape=q.record(com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(volume,levelHeight),FluidFacts::geometry);
            required(composition,q,"composition");return valid(q,"device spec",()->new FluidDeviceSpec(volume,temperature,pressure,composition,feed,levelHeight,open,shape));});
        long revision=f.count(0);
        // Schema 2: a record written before joints existed connects through every face, as every pipe did then.
        long joints=f.count(PhysicalFluidTopology.ALL_FACES);if(joints>PhysicalFluidTopology.ALL_FACES)throw f.invalid("joints "+joints);
        long slots=f.count(0);if(slots>PhysicalFluidTopology.RACK_WIDTH)throw f.invalid("lane width "+slots);
        long points=f.count(0);if(points>>>60!=0)throw f.invalid("lane points "+points);
        // Schema 5: a heat side's unit, an optional value; a record written before it has none.
        var heat=f.list(List.<HeatUnit>of(),FluidFacts::heatUnit);if(heat.size()>1)throw f.invalid("heat units "+heat.size());
        // Schema 6: the column unit that holds the device (its identity); a record written before it is held by none.
        long suspendedBy=f.count(0);
        required(position,f,"device position");required(kind,f,"device kind");required(facing,f,"device facing");required(geometry,f,"device geometry");required(spec,f,"device spec");
        if((kind==TopologyCompiler.Kind.PUMP||kind==TopologyCompiler.Kind.COMPRESSOR)&&ports.isEmpty()){
            if(removed==null)throw f.invalid("registration "+id+": a one-block "+kind.name().toLowerCase(Locale.ROOT)+", which this build no longer has");
            removed.add(kind.name().toLowerCase(Locale.ROOT)+" "+id+" at "+position.x()+", "+position.y()+", "+position.z());return null;
        }
        return valid(f,"registration "+id,()->new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(id,position,kind,facing,geometry,control,ports,generatorEnabled,cutoff,filter,(int)joints,(int)slots,points,heat.isEmpty()?null:heat.getFirst(),suspendedBy),spec,revision));
    }
    /**
     * A column unit (schema 6, batch 2026-10-28-column-operation): its identity, origin, run switch, setpoints, stations
     * (each a port by name, which one, its port device, its valve and its account) and totals.
     */
    private static void column(Fields f,ColumnUnit u) {
        var s=u.setpoints();var t=u.totals();
        f.count(u.id());position(f,u.master());f.bool(u.running())
                .record(q->q.f64(s.designFeedRate()).f64(s.feedRate()).f64(s.condenserTemperature()).f64(s.refluxRatio()).f64(s.reboilerPerFeed())
                        .doubles(s.steamPerFeed()).doubles(s.drawsPerFeed()).doubles(s.pumparoundsPerFeed()))
                .list(u.stations(),(q,st)->q.name(st.port()).count(st.index()).count(st.device()).count(st.valve()).record(a->account(a,st.account())))
                .record(q->q.record(a->account(a,t.taken())).record(a->account(a,t.produced())).f64(t.reboiler()).f64(t.condenser()).f64(t.pumparounds()));
    }
    private static ColumnUnit column(Record f) {
        long id=f.count(0);var master=position(f);boolean running=f.bool(false);
        var setpoints=f.record(null,q->{double design=q.f64(0),rate=q.f64(0),condenser=q.f64(0),reflux=q.f64(0),reboiler=q.f64(0);
            var steam=q.doubles(new double[0]);var draws=q.doubles(new double[0]);var pumparounds=q.doubles(new double[0]);
            return valid(q,"column setpoints",()->new com.wormzjl.icheme.science.column.operation.Setpoints(design,rate,condenser,reflux,reboiler,steam,draws,pumparounds));});
        var stations=f.list(List.of(),q->{var port=q.name(com.wormzjl.icheme.science.column.operation.Port.class,null);long index=q.count(0),device=q.count(0),valve=q.count(0);
            var account=q.record(null,FluidFacts::account);required(port,q,"station port");required(account,q,"station account");
            if(index>=ColumnUnit.MAXIMUM_STATIONS)throw q.invalid("station index "+index);
            return valid(q,"column station",()->new ColumnUnit.Station(port,(int)index,device,valve,account));});
        var totals=f.record(null,q->{var taken=q.record(null,FluidFacts::account);var produced=q.record(null,FluidFacts::account);double reboiler=q.f64(0),condenser=q.f64(0),pumparounds=q.f64(0);
            required(taken,q,"taken total");required(produced,q,"produced total");
            return valid(q,"column totals",()->new ColumnUnit.Totals(taken,produced,reboiler,condenser,pumparounds));});
        required(master,f,"column origin");required(setpoints,f,"column setpoints");required(totals,f,"column totals");
        return valid(f,"column unit "+id,()->new ColumnUnit(id,master,running,setpoints,stations,totals));
    }
    private static void account(Fields f,com.wormzjl.icheme.science.column.operation.ColumnCycle.Account a){f.doubles(a.moles()).f64(a.energy());}
    private static com.wormzjl.icheme.science.column.operation.ColumnCycle.Account account(Record f) {
        var moles=f.doubles(null);double energy=f.f64(0);required(moles,f,"account moles");
        return valid(f,"column account",()->new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(moles,energy));
    }
    /** A heat side's unit (schema 5): its unit and side by name, area, partner, control and heat in transit. */
    private static void heatUnit(Fields f,HeatUnit h) {
        var c=h.control();
        f.name(h.unit()).name(h.side()).f64(h.area()).count(h.partner()).record(q->q.name(c.mode()).f64(c.target()).f64(c.lowLimit()).f64(c.share())).f64(h.transitJoule());
    }
    private static HeatUnit heatUnit(Record f) {
        var unit=f.name(com.wormzjl.icheme.science.equipment.HeatSides.Unit.class,null);var side=f.name(com.wormzjl.icheme.science.equipment.HeatSides.Side.class,null);double area=f.f64(0);long partner=f.count(0);
        var control=f.record(HeatUnit.Control.OFF,q->{var mode=q.name(HeatUnit.Mode.class,HeatUnit.Mode.OFF);double target=q.f64(0),lowLimit=q.f64(0),share=q.f64(0);
            return valid(q,"heat unit control",()->new HeatUnit.Control(mode,target,lowLimit,share));});
        double transit=f.f64(0);
        required(unit,f,"heat unit");required(side,f,"heat unit side");
        return valid(f,"heat unit",()->new HeatUnit(unit,side,area,partner,control,transit));
    }
    private static void geometry(Fields f,com.wormzjl.icheme.science.fluid.network.VesselGeometry g){f.name(g.form()).f64(g.capacity()).f64(g.height()).f64(g.diameter()).f64(g.length()).f64(g.bootDiameter()).f64(g.bootLength());}
    private static com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry(Record f){var form=f.name(com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.class,null);double capacity=f.f64(0),height=f.f64(0),d=f.f64(0),l=f.f64(0),bd=f.f64(0),bl=f.f64(0);return valid(f,"vessel geometry",()->new com.wormzjl.icheme.science.fluid.network.VesselGeometry(form,capacity,height,d,l,bd,bl));}
    private static void control(Fields f,FlowControl control) {
        switch(control) {
            case FlowControl.Passive ignored->f.text("passive").f64(0).f64(0).bool(false).f64(0);
            case FlowControl.Pump pump->f.text("pump").f64(pump.requestedVolumeFlow()).f64(pump.maximumAddedPressure()).bool(pump.enabled()).f64(0);
            case FlowControl.Compressor compressor->f.text("compressor").f64(compressor.requestedVolumeFlow()).f64(compressor.maximumPressureRatio()).bool(compressor.enabled()).f64(0);
            case FlowControl.PressureValve valve->f.text("pressure_valve").f64(0).f64(0).bool(false).f64(valve.targetPressure());
            case FlowControl.ControlValve valve->{f.text("control_valve").f64(0).f64(0).bool(false).f64(valve.targetPressure()).name(valve.source());f.list(valve.sensor()==null?List.<java.util.UUID>of():List.of(valve.sensor()),FluidFacts::uuid).i64(valve.revision()).f64(valve.massTarget()).f64(valve.volumeTarget());}
        }
    }
    private static FlowControl control(Record f) {
        String kind=f.text("passive");double flow=f.f64(0),limit=f.f64(0);boolean enabled=f.bool(true);double target=f.f64(0);
        return valid(f,"control",()->switch(kind) {
            case "passive"->new FlowControl.Passive();
            case "pump"->new FlowControl.Pump(flow,limit,enabled);
            case "compressor"->new FlowControl.Compressor(flow,limit,enabled);
            case "pressure_valve"->new FlowControl.PressureValve(target);
            case "control_valve"->{var source=f.name(FlowControl.ValveSource.class,null);var sensor=f.list(List.of(),FluidFacts::uuid);long revision=f.i64(0);if(sensor.size()>1)throw f.invalid("multiple control sources");double mass=f.f64(1),volume=f.f64(.01);yield new FlowControl.ControlValve(source,target,mass,volume,sensor.isEmpty()?null:sensor.getFirst(),revision);}
            default->throw new IllegalArgumentException("unknown control "+kind);
        });
    }
    /** The topology unit's state: its queued events, each with the registrations it would install. */
    static byte[] events(List<WorldTopologyLedger.Event> events) {
        return section(f->f.list(events,(q,event)->{uuid(q,event.id());q.count(event.tick());
            q.list(event.edits(),(e,edit)->e.count(edit.id()).list(edit.replacement()==null?List.<WorldTopologyLedger.Registration>of():List.of(edit.replacement()),FluidFacts::registration));
            var touched=new ArrayList<>(event.touched());Collections.sort(touched);q.list(touched,(t,id)->t.count(id));
            var recovery=event.recovery();
            q.list(recovery==null?List.<WorldTopologyLedger.Recovery>of():List.of(recovery),(r,v)->r.count(v.filterId()).list(v.player()==null?List.<UUID>of():List.of(v.player()),FluidFacts::uuid));
        }));
    }
    static List<WorldTopologyLedger.Event> events(byte[] data,int offset,int length) {
        return read(data,offset,length,"state of the topology unit",f->f.list(List.of(),q->{
            var id=required(uuid(q),q,"event identity");long tick=q.count(0);
            var edits=q.list(List.of(),e->{long device=e.count(0);var replacement=e.list(List.of(),FluidFacts::registration);if(replacement.size()>1)throw e.invalid("two replacements");
                return valid(e,"edit",()->new WorldTopologyLedger.Edit(device,replacement.isEmpty()?null:replacement.getFirst()));});
            var touched=new HashSet<Long>();for(long t:q.list(List.<Long>of(),t->t.count(0)))if(!touched.add(t))throw q.invalid("duplicate touched device");
            var recovery=q.list(List.of(),r->{long filter=r.count(0);var player=r.list(List.of(),FluidFacts::uuid);return valid(r,"recovery",()->new WorldTopologyLedger.Recovery(filter,player.isEmpty()?null:player.getFirst()));});
            return valid(q,"event",()->new WorldTopologyLedger.Event(id,tick,edits,touched,recovery.isEmpty()?null:recovery.getFirst()));
        }));
    }

    // ---------------- an island ----------------

    /** A vessel's contents as saved: its inventory and the phase split of its state, on the island's component axis. */
    record Vessel(long id,double volume,double levelHeight,boolean open,double[] moles,double energy,List<Solid> solids,
                  double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure) {}
    /** A filter's cake as saved, by the filter's device identity. */
    record Filter(long device,double capacity,List<Solid> captured,double energy,boolean stopped) {}
    /** The change a certified island's certificate replays per {@code duration} ticks from {@code baseTick}, per vessel. */
    record Drift(long baseTick,int duration,Map<Long,double[]> moles,Map<Long,Double> energy) {}
    /** An island unit's facts as saved. */
    record Island(String dimension,String packageId,List<String> components,List<Vessel> vessels,List<Filter> filters,Drift drift) {
        /** The vessels' moles and energy at {@code committed}: the saved ones plus the drift's replayed fraction, the arithmetic of {@link IslandCertificate}'s replay. */
        List<Vessel> at(long committed) {
            if(drift==null||committed==drift.baseTick)return vessels;
            if(committed<drift.baseTick)throw new IllegalArgumentException("Island committed at tick "+committed+" before its certificate base "+drift.baseTick);
            double f=(committed-drift.baseTick)/(double)drift.duration;var result=new ArrayList<Vessel>(vessels.size());
            for(var v:vessels) {
                var delta=drift.moles.get(v.id);if(delta==null){result.add(v);continue;}
                var moles=v.moles.clone();for(int c=0;c<moles.length;c++)moles[c]+=f*delta[c];
                result.add(new Vessel(v.id,v.volume,v.levelHeight,v.open,moles,v.energy+f*drift.energy.get(v.id),v.solids,v.temperature,v.pressure,v.liquid,v.vapor,v.waterLiquid,v.waterVapor,v.hydrocarbonPressure));
            }
            return result;
        }
    }
    /** The facts of {@code entry} on the component axis {@code components}: a certified island's certificate base and drift, an awake one's state. */
    static byte[] island(FluidCheckpointCodec.IslandEntry entry,List<String> components) {
        var s=entry.snapshot();var saved=s.certificate().flatMap(IslandCoordinator.Certified::saved);
        var graph=saved.map(c->c.interval().result().graph()).orElse(s.graph());
        return section(f->{
            f.text(entry.dimension()).text(entry.packageId()).list(components,(c,id)->c.text(id));
            var vessels=graph.reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).toList();
            f.list(vessels,(q,n)->{var i=n.inventory();var t=n.state();
                q.i64(n.id()).f64(i.volume()).f64(i.levelHeight()).bool(i.open()).doubles(i.moles()).f64(i.internalEnergy());solids(q,Solid.of(i.solids()));
                q.f64(t.temperature()).f64(t.pressure()).doubles(t.liquid()).doubles(t.vapor()).f64(t.waterLiquid()).f64(t.waterVapor()).f64(t.hydrocarbonPartialPressure());});
            var filters=graph.pipes().stream().filter(p->p.filter()!=null).toList();
            f.list(filters,(q,p)->{var cake=p.filter();q.i64(device(p.id())).f64(cake.capacity());solids(q,Solid.of(cake.captured()));q.f64(cake.energyJoule()).bool(cake.stoppedAtCapacity());});
            var drift=new ArrayList<Consumer<Fields>>();
            saved.ifPresent(c->{
                var interval=c.interval();var before=interval.before().reservoirs();var after=interval.result().graph().reservoirs();
                var nodes=new ArrayList<Consumer<Fields>>();
                for(int n=0;n<after.size();n++) {
                    var b=after.get(n);if(b.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
                    var m0=before.get(n).inventory().moles();var m1=b.inventory().moles();var delta=new double[m1.length];boolean zero=true;
                    for(int k=0;k<m1.length;k++){delta[k]=m1[k]-m0[k];zero&=delta[k]==0;}
                    double energy=b.inventory().internalEnergy()-before.get(n).inventory().internalEnergy();
                    for(double d:delta)if(!Double.isFinite(d))throw new IllegalArgumentException("Island "+s.id()+": a certificate with a nonfinite moles delta at node "+b.id());
                    if(!Double.isFinite(energy))throw new IllegalArgumentException("Island "+s.id()+": a certificate with a nonfinite energy delta at node "+b.id());
                    if(!zero||energy!=0)nodes.add(q->q.i64(b.id()).doubles(delta).f64(energy));
                }
                if(!nodes.isEmpty())drift.add(q->q.count(interval.endTick()).count(interval.endTick()-interval.startTick()).list(nodes,(e,node)->node.accept(e)));
            });
            f.list(drift,(q,d)->d.accept(q));
        });
    }
    static Island island(byte[] data,int offset,int length,String what) {
        return read(data,offset,length,"facts of "+what,f->{
            var dimension=f.text(null);var packageId=f.text(null);var components=f.list(List.of(),c->c.text(""));
            var vessels=f.list(List.of(),q->new Vessel(q.i64(0),q.f64(0),q.f64(com.wormzjl.icheme.science.fluid.network.PassiveStepSolver.LEVEL_HEAD_HEIGHT),q.bool(false),
                    required(q.doubles(null),q,"vessel moles"),q.f64(0),solids(q),q.f64(0),q.f64(0),required(q.doubles(null),q,"vessel liquid"),required(q.doubles(null),q,"vessel vapour"),q.f64(0),q.f64(0),q.f64(0)));
            var filters=f.list(List.of(),q->new Filter(q.i64(0),q.f64(0),solids(q),q.f64(0),q.bool(false)));
            var drifts=f.list(List.of(),q->{long base=q.count(0);long duration=q.count(0);var moles=new HashMap<Long,double[]>();var energy=new HashMap<Long,Double>();
                for(var node:q.list(List.of(),e->Map.entry(e.i64(0),Map.entry(required(e.doubles(null),e,"drift moles"),e.f64(0))))){
                    if(moles.put(node.getKey(),node.getValue().getKey())!=null)throw q.invalid("duplicate drift node");energy.put(node.getKey(),node.getValue().getValue());}
                if(duration<1||duration>Integer.MAX_VALUE)throw q.invalid("drift over "+duration+" ticks");
                return new Drift(base,(int)duration,moles,energy);});
            required(dimension,f,"dimension");required(packageId,f,"package");if(drifts.size()>1)throw f.invalid("two drifts");
            var ids=new HashSet<Long>();for(var v:vessels){if(!ids.add(v.id))throw f.invalid("duplicate vessel "+v.id);if(v.moles.length!=components.size())throw f.invalid("vessel "+v.id+" off the island's axis");}
            return new Island(dimension,packageId,components,vessels,filters,drifts.isEmpty()?null:drifts.getFirst());
        });
    }
    /**
     * Where the facts of an island and the island its state gives at {@code committed} disagree, or null: every vessel's
     * inventory and saved phase split, every filter's cake (equality, so a zero of either sign agrees). The facts path
     * rebuilds exactly what these facts say, so this is what it would get wrong.
     */
    static String disagreement(Island facts,PassiveNetwork graph,long committed) {
        var vessels=new HashMap<Long,Vessel>();for(var v:facts.at(committed))vessels.put(v.id,v);
        for(var n:graph.reservoirs()) {
            if(n.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
            var v=vessels.remove(n.id());if(v==null)return "vessel "+n.id()+" has no facts";
            var i=n.inventory();var t=n.state();
            if(v.volume!=i.volume()||v.levelHeight!=i.levelHeight()||v.open!=i.open()||!same(v.moles,i.moles())||v.energy!=i.internalEnergy())return "vessel "+n.id()+" inventory";
            if(!same(v.solids,i.solids()))return "vessel "+n.id()+" solids";
            if(v.temperature!=t.temperature()||v.pressure!=t.pressure()||!same(v.liquid,t.liquid())||!same(v.vapor,t.vapor())||v.waterLiquid!=t.waterLiquid()||v.waterVapor!=t.waterVapor()||v.hydrocarbonPressure!=t.hydrocarbonPartialPressure())
                return "vessel "+n.id()+" state";
        }
        if(!vessels.isEmpty())return "vessels "+vessels.keySet()+" are in no node";
        var cakes=new HashMap<Long,Filter>();for(var c:facts.filters)cakes.put(c.device,c);
        for(var p:graph.pipes()) {
            if(p.filter()==null)continue;var c=cakes.remove(device(p.id()));
            if(c==null||c.energy!=p.filter().energyJoule()||!same(c.captured,p.filter().captured()))return "filter "+device(p.id())+" cake";
        }
        return cakes.isEmpty()?null:"filters "+cakes.keySet()+" are in no pipe";
    }
    /** The device a filter pipe belongs to: the inverse of {@link PhysicalFluidTopology#filterIdentity}. */
    private static long device(long filterPipe){return filterPipe-Long.MIN_VALUE;}
    private static boolean same(double[] a,double[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(a[i]!=b[i])return false;return true;}
    private static boolean same(List<Solid> saved,SolidInventory inventory) {
        var p=inventory.populations();if(saved.size()!=p.size())return false;
        for(int i=0;i<saved.size();i++)if(!saved.get(i).same(p.get(i)))return false;return true;
    }

    // ---------------- the facts path ----------------

    /** An island's facts and the committed tick its index row gives. */
    record Placed(Island island,long committed) {}
    /** What a rebuild made: the islands, the world ledger, and what it restated. */
    record Rebuilt(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world,int vessels,int restated) {}

    /**
     * Rebuilds a world from its facts alone: the registry and ledger of {@code world}, the vessels' contents and the
     * filters' cakes of {@code islands}, compiled into islands as a topology change compiles them (their junctions
     * minted, decision D37), awake at {@code epoch} with no solved interval. Generators and voids are initialised from
     * their specs, as the registry holds them.
     *
     * <p>With {@code restate} the property data changed (AGENTS.md, "Saves and data formats"): every amount is mapped
     * onto the current axis by component id (through the package's aliases); a vessel keeps its moles, its volume, its
     * solids and its temperature, and the new data gives its energy, pressure and phase split (a TP flash at its saved
     * temperature and pressure). The energy difference and every component and solid the data no longer has are booked
     * in the ledger's {@link WorldTopologyLedger.Restated}, and the islands' status names them.</p>
     */
    static Rebuilt rebuild(World world,List<Placed> islands,long epoch,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,
                           MaterialCatalog catalog,boolean restate,String reason) {
        var basis=restate?WorldTopologyLedger.FluidBasis.capture(catalog):world.basis;
        var key=new FluidCheckpointCodec.PackageKey(basis.packageId());var model=Objects.requireNonNull(models.apply(key));
        if(!model.components().equals(basis.components()))throw new IllegalStateException("The network model's components "+model.components()+" are not the basis "+basis.components());
        var removed=new Removed();
        var axis=new Axis(world.basis.components(),basis.components(),restate?catalog.requirePackage(basis.packageId()):null);
        Function<Solid,SolidMaterial> solid=s->current(s,model,removed);
        // The registry, its settings on the current axis.
        var registrations=new HashMap<Long,WorldTopologyLedger.Registration>();
        for(var r:world.registrations) {
            var spec=r.spec();var composition=axis.map(spec.composition(),null);
            if(Arrays.stream(composition).sum()<=0){composition=FluidDeviceSpec.nitrogen(catalog).composition();warn("device "+r.device().id()+": its spec holds only components the data no longer has; it now holds nitrogen");}
            if(restate)spec=new FluidDeviceSpec(spec.volume(),spec.temperature(),spec.pressure(),composition,spec.solids(),spec.levelHeight(),spec.open(),spec.geometry());
            registrations.put(r.device().id(),new WorldTopologyLedger.Registration(r.device(),spec,r.revision()));
        }
        // Vessel contents and filter cakes, by device identity.
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var cakes=new HashMap<Long,InlineFilter>();int restated=0,vessels=0;
        double energy=0;
        for(var placed:islands) {
            var island=placed.island;var from=new Axis(island.components,basis.components(),restate?catalog.requirePackage(basis.packageId()):null);
            for(var v:island.at(placed.committed)) {
                var r=registrations.get(v.id);
                if(r==null||r.device().kind()!=TopologyCompiler.Kind.RESERVOIR){warn("vessel "+v.id+" is not a registered reservoir; its contents are dropped");removed.add(v,from);continue;}
                double y=r.device().position().y();var solids=inventory(v.solids,solid);
                PassiveNetwork.Reservoir node;
                if(!restate&&from.identity()) {
                    var state=Arrays.stream(v.moles).sum()==0&&!solids.empty()?model.solidState(v.temperature,v.pressure,solids):model.state(v.temperature,v.pressure,v.liquid,v.vapor,v.waterLiquid,v.waterVapor,v.hydrocarbonPressure).withSolids(solids);
                    node=new PassiveNetwork.Reservoir(v.id,y,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(v.volume,v.moles,v.energy,solids,v.levelHeight,v.open,r.spec().geometry()));
                } else {
                    var moles=from.map(v.moles,removed);restated++;
                    if(Arrays.stream(moles).sum()==0&&solids.empty())node=r.spec().initialize(r.device(),model,()->{});
                    else {
                        var state=Arrays.stream(moles).sum()==0?model.solidState(v.temperature,v.pressure,solids):model.flashTP(v.temperature,v.pressure,moles,()->{}).withSolids(solids);
                        node=new PassiveNetwork.Reservoir(v.id,y,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(v.volume,moles,state.internalEnergy(),solids,v.levelHeight,v.open,r.spec().geometry()));
                    }
                    energy+=node.inventory().internalEnergy()-v.energy;
                }
                if(stock.put(v.id,node)!=null)throw new IllegalArgumentException("Vessel "+v.id+" is saved in two islands");
                vessels++;
            }
            for(var c:island.filters)cakes.put(c.device,FluidCheckpointCodec.currentFilter(c.capacity,inventory(c.captured,solid),c.energy,c.stopped,model));
        }
        var devices=new ArrayList<PhysicalFluidTopology.Device>();
        for(var r:registrations.values()) {
            var d=r.device();devices.add(d);
            if(d.filter())cakes.putIfAbsent(d.id(),FluidCheckpointCodec.currentFilter(model.solidSettings.filterCapacity(),SolidInventory.EMPTY,0,false,model));
            if(!d.boundary()||stock.containsKey(d.id()))continue;
            if(d.kind()==TopologyCompiler.Kind.RESERVOIR)warn("vessel "+d.id()+" has no saved contents; it starts empty");
            stock.put(d.id(),initialised(r,model,catalog));
        }
        var compiled=PhysicalFluidTopology.compile(devices,stock,cakes,NetworkReduction.ambient(model));
        long next=world.nextIdentity;var entries=new ArrayList<FluidCheckpointCodec.IslandEntry>();
        String status="WAITING: rebuilt from saved facts ("+reason+")"+(removed.empty()?"":"; removed by the property data: "+removed.summary());
        for(var island:compiled.islands()) {
            var graph=PassiveNetwork.mintJunctions(island.graph(),model);long id=next++;
            var dimension=registrations.get(island.physicalIds().iterator().next()).device().position().dimension();
            entries.add(new FluidCheckpointCodec.IslandEntry(dimension,key.packageId(),new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(epoch,epoch,0,REBUILT_CADENCE),
                    Optional.empty(),status,Map.of(),Optional.empty(),0)));
        }
        var recoveries=new HashMap<UUID,WorldTopologyLedger.RecoveredSolid>();
        for(var r:world.recoveries) {
            var solids=inventory(r.solids,solid);
            if(solids.empty()){warn("recovery "+r.id+" holds only solids the data no longer has; it is dropped");continue;}
            recoveries.put(r.id,new WorldTopologyLedger.RecoveredSolid(r.position,r.player,solids,r.energy));
        }
        var restatedTotal=world.restated.plus(new WorldTopologyLedger.Restated(energy,removed.moles,removed.solids));
        // The column units, their accounts on the current axis (a component the data no longer has leaves, booked as removed).
        var columns=new HashMap<Long,ColumnUnit>();
        for(var u:world.columns)columns.put(u.id(),axis.identity()?u:u.withStations(u.running(),u.stations().stream().map(s->s.withAccount(new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(axis.map(s.account().moles(),removed),s.account().energy()))).toList())
                .withTotals(new ColumnUnit.Totals(new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(axis.map(u.totals().taken().moles(),null),u.totals().taken().energy()),
                        new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(axis.map(u.totals().produced().moles(),null),u.totals().produced().energy()),u.totals().reboiler(),u.totals().condenser(),u.totals().pumparounds())));
        var snapshot=new WorldTopologyLedger.Snapshot(epoch,next,registrations,List.of(),axis.total(world.constructed),axis.total(world.destroyed),basis,recoveries,restatedTotal,columns);
        new WorldTopologyLedger(snapshot);
        if(restate)IChemE.LOGGER.warn("fluid_checkpoint status=RESTATED vessels={} energy_delta_J={} removed={}",restated,energy,removed.empty()?"none":removed.summary());
        return new Rebuilt(new FluidCheckpointCodec.Checkpoint(entries),snapshot,vessels,restated);
    }
    /** A generator's or void's state from its spec; a spec outside the package's input box is clamped into it first (logged). */
    private static PassiveNetwork.Reservoir initialised(WorldTopologyLedger.Registration r,FluidThermodynamics model,MaterialCatalog catalog) {
        var spec=r.spec();var d=r.device();
        if(d.kind()!=TopologyCompiler.Kind.RESERVOIR) {
            var box=model.domain().envelope();
            double t=Math.min(box.maximumTemperature(),Math.max(box.minimumTemperature(),spec.temperature())),p=Math.min(box.maximumPressure(),Math.max(box.minimumPressure(),spec.pressure()));
            if(t!=spec.temperature()||p!=spec.pressure()) {
                warn("device "+d.id()+": its spec "+spec.temperature()+" K, "+spec.pressure()+" Pa lies outside the package's input box; it now reads "+t+" K, "+p+" Pa");
                spec=new FluidDeviceSpec(spec.volume(),t,p,spec.composition(),spec.solids(),spec.levelHeight(),spec.open(),spec.geometry());
            }
        }
        return spec.initialize(d,model,()->{});
    }
    /**
     * A saved solid as the model's current record of its id (solid data lies outside the property basis, so it can change
     * without a restatement; a changed record is logged). One the data no longer has is booked as removed and left out.
     */
    private static SolidMaterial current(Solid s,FluidThermodynamics model,Removed removed) {
        SolidMaterial record;
        try{record=model.solid(s.id);}catch(IllegalArgumentException unknown){removed.solid(s);return null;}
        if(!s.revision.equals(record.revision())||s.density!=record.density()||s.heatCapacity!=record.heatCapacity())warn("solid "+s.id+" is read as its current record "+record.revision());
        return record;
    }
    private static void warn(String detail){IChemE.LOGGER.warn("fluid_checkpoint status=FACTS detail={}",detail);}
    /** What the new data no longer has, booked: component moles and solid masses by id. */
    private static final class Removed {
        final Map<String,Double> moles=new TreeMap<>(),solids=new TreeMap<>();
        void component(String id,double amount){if(amount>0)moles.merge(id,amount,Double::sum);}
        void solid(Solid s){solids.merge(s.key(),s.mass,Double::sum);}
        void add(Vessel v,Axis from){for(int c=0;c<v.moles.length;c++)component(from.saved.get(c),v.moles[c]);for(var s:v.solids)solid(s);}
        boolean empty(){return moles.isEmpty()&&solids.isEmpty();}
        String summary(){var parts=new ArrayList<String>();moles.forEach((k,v)->parts.add(k+" "+v+" mol"));solids.forEach((k,v)->parts.add(k+" "+v+" kg"));return String.join(", ",parts);}
    }
    /** A saved component axis mapped onto the current one by id, through the package's aliases. */
    private static final class Axis {
        final List<String> saved;final int[] to;final int size;
        Axis(List<String> saved,List<String> current,MaterialCatalog.Package aliases) {
            this.saved=saved;size=current.size();to=new int[saved.size()];
            for(int c=0;c<saved.size();c++){String id=aliases==null?saved.get(c):aliases.canonicalId(saved.get(c));to[c]=current.indexOf(id);}
        }
        boolean identity(){if(to.length!=size)return false;for(int c=0;c<to.length;c++)if(to[c]!=c)return false;return true;}
        /** {@code amounts} on the current axis; what has no place there goes to {@code removed} (dropped when null). */
        double[] map(double[] amounts,Removed removed) {
            if(amounts.length!=to.length)throw new IllegalArgumentException("Saved amounts of "+amounts.length+" components on an axis of "+to.length);
            var result=new double[size];
            for(int c=0;c<to.length;c++){if(to[c]>=0)result[to[c]]+=amounts[c];else if(removed!=null)removed.component(saved.get(c),amounts[c]);}
            return result;
        }
        WorldTopologyLedger.MaterialTotal total(WorldTopologyLedger.MaterialTotal t){return identity()?t:new WorldTopologyLedger.MaterialTotal(map(t.moles(),null),t.totalEnergy(),t.solidMasses());}
    }
}
