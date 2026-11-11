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
     *  6 (batch 2026-10-28-column-operation): a registration's column unit (the running column that holds it), the column units.
     *  7 (batch 2026-10-30-water-cooling-tower): a device spec's cooling tower.
     *  8 (batch 2026-11-02-process-simulator, S3b): a column unit's further condenser water sides, one a shell in parallel.
     *  9 (batch 2026-11-02-process-simulator, U2): a column unit's air condenser surface; a heat side's {@code COLUMN} mode.
     *  10 (batch 2026-11-03-rotating-machinery, W3): the {@code machine} and {@code turbine} controls, the device kind
     *  {@code TURBINE}, the control valve source {@code SHAFT_SPEED}, and the world's shaft units (after the column units).
     *  11 (batch 2026-11-03-rotating-machinery, W5): the world's electric stores (after the shaft units), an air cooler side's
     *  fans, the shaft trip {@code NO_POWER} (never written: it is not latched).
     *  12 (batch 2026-11-03-rotating-machinery, W10b): the pumps' health (after the electric stores), only pumps below 1.
     *  13 (batch 2026-11-06-simulator-sizing): a heat unit's exchanger configuration, absent means Standard.
     *  14 (the same batch): a shaft unit's explicit VFD base speed, absent means its legacy pole-derived speed.
     *  15 (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md 3 and step 5): the registry's build facts, after the
     *  pumps' health: each painted line's paints, each equipment structure's shape and paint, and each rack lane's rack
     *  frame (the facing and shape of the cell it lies in), so that the registry can place their blocks again.
     *  16 (batch 2026-11-09-batch-distiller, W3 and W4): the world's batch stills (after the build facts); the control
     *  valve source {@code STILL}; the {@code diverter} control.
     *  17 (batch 2026-11-10-steam-ejector-flow): the device kind {@code EJECTOR} and the {@code ejector} control, its suction's bore.
     *  18 (batch 2026-11-10-rigid-reservoir): a vessel's {@code rigid}, after its contents: whether its wall has once carried
     *  them; absent means a bag (decision D54).
     *  19 (batch 2026-11-10-vacuum-pump): the {@code machine} control's law {@code vacuum_pump}: its capacity (m³/s), rated
     *  speed (rpm) and type by name, after the four fields every law carries.
     *  20 (batch 2026-11-10-prime-movers): the shaft unit kinds {@code COMBUSTION_ENGINE} and {@code GAS_TURBINE} (each naming its
     *  fuel void), the control valve source {@code ENGINE} (a prime mover's fuel valve), the heat unit {@code PRIME_MOVER} and
     *  its side {@code EXHAUST} (a gas turbine's steam coil), generators past 20 MW and of 12 poles.
     *  21 (batch 2026-11-10-column-failure-modes, W4 and W5): a column unit's utility sides, its reboiler's and each
     *  pumparound cooler's; a kettle's tube side in the {@code COLUMN} mode; a column unit's powered members, its air
     *  condenser's fan sides and its pumps' movers.
     *  22 (batch 2026-11-11-gas-blanketing): a device spec's gas blanket set pressure, after its cooling tower (absent: 0,
     *  no valve); the port role {@code BLANKET}, a vessel's blanket gas port.
     *  23 (same batch, the pressure safety valve): a device spec's relief set pressure and a pipe's relief outlet face, after
     *  its blanket (absent: 0 and none, no valve); the port role {@code RELIEF}, a vessel's relief port. */
    static final int SCHEMA=23;
    /** Renamed or removed enum constants, per enum: the saved name to the current one. None at schema 21. */
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
                 List<String> removedMovers,List<ColumnUnit> columns,List<ShaftUnit> shafts,Map<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts> stores,Map<Long,Double> health,WorldTopologyLedger.Builds builds,List<StillUnit> stills) {
        /** Facts without batch stills (a world before schema 16). */
        World(long nextIdentity,WorldTopologyLedger.FluidBasis basis,List<WorldTopologyLedger.Registration> registrations,WorldTopologyLedger.MaterialTotal constructed,
              WorldTopologyLedger.MaterialTotal destroyed,List<Recovery> recoveries,WorldTopologyLedger.Restated restated,List<String> removedMovers,List<ColumnUnit> columns,
              List<ShaftUnit> shafts,Map<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts> stores,Map<Long,Double> health,WorldTopologyLedger.Builds builds) {
            this(nextIdentity,basis,registrations,constructed,destroyed,recoveries,restated,removedMovers,columns,shafts,stores,health,builds,List.of());
        }
        /** Facts without the build facts (a world before schema 15): the blocks teach them at their next bind. */
        World(long nextIdentity,WorldTopologyLedger.FluidBasis basis,List<WorldTopologyLedger.Registration> registrations,WorldTopologyLedger.MaterialTotal constructed,
              WorldTopologyLedger.MaterialTotal destroyed,List<Recovery> recoveries,WorldTopologyLedger.Restated restated,List<String> removedMovers,List<ColumnUnit> columns,
              List<ShaftUnit> shafts,Map<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts> stores,Map<Long,Double> health) {
            this(nextIdentity,basis,registrations,constructed,destroyed,recoveries,restated,removedMovers,columns,shafts,stores,health,WorldTopologyLedger.Builds.NONE,List.of());
        }
        /** Facts without pump health (a world before schema 12): every pump new. */
        World(long nextIdentity,WorldTopologyLedger.FluidBasis basis,List<WorldTopologyLedger.Registration> registrations,WorldTopologyLedger.MaterialTotal constructed,
              WorldTopologyLedger.MaterialTotal destroyed,List<Recovery> recoveries,WorldTopologyLedger.Restated restated,List<String> removedMovers,List<ColumnUnit> columns,
              List<ShaftUnit> shafts,Map<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts> stores) {
            this(nextIdentity,basis,registrations,constructed,destroyed,recoveries,restated,removedMovers,columns,shafts,stores,Map.of());
        }
        World {health=Map.copyOf(health);stills=List.copyOf(stills);}
        /** Facts without shaft units (a world before schema 10). */
        World(long nextIdentity,WorldTopologyLedger.FluidBasis basis,List<WorldTopologyLedger.Registration> registrations,WorldTopologyLedger.MaterialTotal constructed,
              WorldTopologyLedger.MaterialTotal destroyed,List<Recovery> recoveries,WorldTopologyLedger.Restated restated,List<String> removedMovers,List<ColumnUnit> columns) {
            this(nextIdentity,basis,registrations,constructed,destroyed,recoveries,restated,removedMovers,columns,List.of(),null);
        }
        /**
         * The electric stores (schema 11, W5). A world written before them ({@code stores} null) has a store, unmetered and
         * empty, for each powered unit it holds: an air cooler, an induced-draft tower, a motor, a generator (owner decision
         * A73 option A: they ran free, and run free until their port first receives FE).
         */
        Map<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts> electricStores() {
            if(stores!=null)return stores;
            var legacy=new HashMap<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts>();
            for(var r:registrations)if(ElectricStores.powered(r))legacy.put(r.device().id(),com.wormzjl.icheme.science.machine.ElectricStore.Facts.UNMETERED);
            for(var u:shafts)if(ElectricStores.powered(u))legacy.put(u.id(),com.wormzjl.icheme.science.machine.ElectricStore.Facts.UNMETERED);
            return legacy;
        }
        /** The ledger these facts and {@code events} make at {@code onlineTick}, its recoveries' solids named by {@code record}. */
        WorldTopologyLedger.Snapshot snapshot(long onlineTick,List<WorldTopologyLedger.Event> events,Function<Solid,SolidMaterial> record) {            var active=new HashMap<Long,WorldTopologyLedger.Registration>();
            for(var r:registrations)if(active.put(r.device().id(),r)!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate device "+r.device().id());
            var pending=new HashMap<UUID,WorldTopologyLedger.RecoveredSolid>();
            for(var r:recoveries)if(pending.put(r.id,new WorldTopologyLedger.RecoveredSolid(r.position,r.player,inventory(r.solids,record),r.energy))!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate recovery "+r.id);
            var units=new HashMap<Long,ColumnUnit>();for(var u:columns)if(units.put(u.id(),u)!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate column unit "+u.id());
            var parts=new HashMap<Long,ShaftUnit>();for(var u:shafts)if(parts.put(u.id(),u)!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate shaft unit "+u.id());
            var batch=new HashMap<Long,StillUnit>();for(var u:stills)if(batch.put(u.id(),u)!=null)throw new IllegalArgumentException("Invalid saved topology: duplicate batch still "+u.id());
            var snapshot=new WorldTopologyLedger.Snapshot(onlineTick,nextIdentity,active,events,constructed,destroyed,basis,pending,restated,units,parts,electricStores(),health,builds,batch);
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
            // Schema 10: the shaft units, by identity.
            f.list(new TreeMap<>(s.shafts()).values(),FluidFacts::shaft);
            // Schema 11: the electric stores, by unit identity: content (J) and whether metered.
            f.list(new TreeMap<>(s.stores()).entrySet(),(q,e)->q.count(e.getKey()).f64(e.getValue().joules()).bool(e.getValue().metered()));
            // Schema 12: the pumps' health, by device identity, only pumps below 1 (a pump without an entry is new).
            f.list(new TreeMap<>(s.health()).entrySet(),(q,e)->q.count(e.getKey()).f64(e.getValue()));
            // Schema 15: the build facts, line paints by identity and structures by origin. A structure's shape is its
            // shell's own SNBT, made once when it was recorded.
            var builds=s.builds();
            f.list(new TreeMap<>(builds.paints()).entrySet(),(q,e)->q.count(e.getKey()).i32(e.getValue().primary()).i32(e.getValue().secondary()));
            var structures=new ArrayList<>(builds.structures().entrySet());structures.sort(Map.Entry.comparingByKey(ORIGIN_ORDER));
            f.list(structures,(q,e)->{position(q,e.getKey());var t=e.getValue();q.text(t.text()).i32(t.paint().body()).i32(t.paint().structure()).text(t.name());});
            // Schema 15: each rack lane's rack frame, by identity.
            f.list(new TreeMap<>(builds.racks()).entrySet(),(q,e)->q.count(e.getKey()).name(e.getValue().facing()).name(e.getValue().shape()));
            // Schema 15: each named one-block device's name (N1), by identity.
            f.list(new TreeMap<>(builds.names()).entrySet(),(q,e)->q.count(e.getKey()).text(e.getValue()));
            // Schema 16, last: the batch stills, by their pots' identities.
            f.list(new TreeMap<>(s.stills()).values(),FluidFacts::still);
        });
    }
    private static final Comparator<PhysicalFluidTopology.Position> ORIGIN_ORDER=Comparator.comparing(PhysicalFluidTopology.Position::dimension)
            .thenComparingInt(PhysicalFluidTopology.Position::x).thenComparingInt(PhysicalFluidTopology.Position::y).thenComparingInt(PhysicalFluidTopology.Position::z);
    /**
     * Schema 8: a structure's record. Its shape reads through the shell's own reader and repairs ({@code ShapeRepair}),
     * repairs logged as a shell logs them. A shape of a kind this build retired is dropped: the shell's load removes the
     * structure whole (backward-compat decision D6), and the registry cannot place it again. Returns null for that.
     */
    private static WorldTopologyLedger.Structure structure(Record q,PhysicalFluidTopology.Position origin,String text,int body,int structure,String name) {
        net.minecraft.nbt.CompoundTag tag;
        try{tag=net.minecraft.nbt.TagParser.parseTag(text);}
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid){throw q.invalid("structure at "+origin+": its shape does not read: "+invalid.getMessage());}
        var read=com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity.readShape(tag);
        if(read.retired()!=null){warn("structure at "+origin+" is a retired "+read.retired()+"; the registry drops it");return null;}
        if(read.shape()==null)throw q.invalid("structure at "+origin+": no shape");
        for(var repair:read.repairs())IChemE.LOGGER.warn("equipment_compat kind={} at={} repair={}",read.shape().kind().name().toLowerCase(Locale.ROOT),origin,repair);
        var paint=valid(q,"structure paint at "+origin,()->new com.wormzjl.icheme.science.equipment.Paint(body,structure));
        if(!name.isEmpty()&&!WorldTopologyLedger.named(name))throw q.invalid("structure name at "+origin);
        return WorldTopologyLedger.Structure.of(read.shape(),paint,name);
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
            // A world written before schema 10 has no shaft units: no part turned before them.
            var shafts=f.list(List.<ShaftUnit>of(),FluidFacts::shaft);
            // A world written before schema 11 has no electric stores: null, read as unmetered stores for its powered units.
            var saved=f.list(null,q->{long id=q.count(0);double joules=q.f64(0);boolean metered=q.bool(true);
                return valid(q,"electric store "+id,()->Map.entry(id,new com.wormzjl.icheme.science.machine.ElectricStore.Facts(joules,metered)));});
            Map<Long,com.wormzjl.icheme.science.machine.ElectricStore.Facts> stores=null;
            if(saved!=null){stores=new HashMap<>();for(var e:saved)if(stores.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate electric store "+e.getKey());}
            // A world written before schema 12 has no pump health: every pump is new (health 1).
            var health=new HashMap<Long,Double>();
            for(var e:f.list(List.<Map.Entry<Long,Double>>of(),q->{long id=q.count(0);double value=q.f64(1);
                if(!(value>=0&&value<=1))throw q.invalid("pump health "+id+": "+value);return Map.entry(id,value);}))
                if(health.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate pump health "+e.getKey());
            // Schema 15: the build facts. A record written before them holds none, and the blocks teach them later.
            var paints=new HashMap<Long,WorldTopologyLedger.LinePaint>();
            for(var e:f.list(List.<Map.Entry<Long,WorldTopologyLedger.LinePaint>>of(),q->{long id=q.count(0);int primary=q.i32(com.wormzjl.icheme.science.equipment.Paint.BARE),secondary=q.i32(primary);
                    var paint=valid(q,"line paint of "+id,()->new WorldTopologyLedger.LinePaint(primary,secondary));if(id<=0||paint.bare())throw q.invalid("line paint of "+id);return Map.entry(id,paint);}))
                if(paints.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate line paint "+e.getKey());
            var structures=new HashMap<PhysicalFluidTopology.Position,WorldTopologyLedger.Structure>();var origins=new HashSet<PhysicalFluidTopology.Position>();
            for(var e:f.list(List.<Map.Entry<PhysicalFluidTopology.Position,Optional<WorldTopologyLedger.Structure>>>of(),q->{var origin=required(position(q),q,"structure origin");var text=q.text("");
                    int body=q.i32(0),structure=q.i32(0);var name=q.text("");if(origin.lane()!=0||text.isEmpty())throw q.invalid("structure at "+origin);
                    return Map.entry(origin,Optional.ofNullable(structure(q,origin,text,body,structure,name)));})) {
                if(!origins.add(e.getKey()))throw f.invalid("duplicate structure at "+e.getKey());
                e.getValue().ifPresent(s->structures.put(e.getKey(),s));
            }
            // Schema 15: the rack frames. A record written before them holds none, and the racks teach them at bind.
            var racks=new HashMap<Long,WorldTopologyLedger.RackFrame>();
            for(var e:f.list(List.<Map.Entry<Long,WorldTopologyLedger.RackFrame>>of(),q->{long id=q.count(0);var facing=q.name(PhysicalFluidTopology.Direction.class,null);var shape=q.name(WorldTopologyLedger.RackShape.class,null);
                    if(id<=0||facing==null||shape==null)throw q.invalid("rack frame of "+id);return Map.entry(id,valid(q,"rack frame of "+id,()->new WorldTopologyLedger.RackFrame(facing,shape)));}))
                if(racks.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate rack frame "+e.getKey());
            // Schema 15: the device names. A record written before them holds none, and the blocks teach them at bind.
            var names=new HashMap<Long,String>();
            for(var e:f.list(List.<Map.Entry<Long,String>>of(),q->{long id=q.count(0);var name=q.text("");
                    if(id<=0||!WorldTopologyLedger.named(name))throw q.invalid("device name of "+id);return Map.entry(id,name);}))
                if(names.put(e.getKey(),e.getValue())!=null)throw f.invalid("duplicate device name "+e.getKey());
            // Schema 16: the batch stills. A world written before schema 16 has none.
            var stills=f.list(List.<StillUnit>of(),FluidFacts::still);
            required(basis,f,"basis");required(constructed,f,"constructed total");required(destroyed,f,"destroyed total");
            return new World(next,basis,registrations,constructed,destroyed,recoveries,restated,List.copyOf(removedMovers),columns,shafts,stores,health,
                    new WorldTopologyLedger.Builds(paints,structures,racks,names),stills);
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
    private static void tower(Fields f,com.wormzjl.icheme.science.fluid.network.CoolingTower t) {
        f.name(t.draft()).f64(t.width()).f64(t.fillDepth()).f64(t.height()).count(t.columns()).count(t.rows())
            .name(t.mode()).f64(t.speed()).f64(t.setPoint());
    }
    private static com.wormzjl.icheme.science.fluid.network.CoolingTower tower(Record f) {
        var draft=f.name(com.wormzjl.icheme.science.fluid.network.CoolingTower.Draft.class,null);
        double w=f.f64(0),fill=f.f64(0),height=f.f64(0);int columns=(int)f.count(0),rows=(int)f.count(0);
        var mode=f.name(com.wormzjl.icheme.science.fluid.network.CoolingTower.Mode.class,null);
        double speed=f.f64(0),target=f.f64(298.15);
        return valid(f,"cooling tower",()->new com.wormzjl.icheme.science.fluid.network.CoolingTower(draft,w,fill,height,columns,rows,mode,speed,target));
    }
    private static void registration(Fields f,WorldTopologyLedger.Registration r) {
        var d=r.device();var g=d.geometry();var s=r.spec();
        f.count(d.id());position(f,d.position());f.name(d.kind()).name(d.facing())
                .record(q->q.f64(g.length()).f64(g.diameter()).f64(g.roughness()).f64(g.minorLoss()).count(g.channels()))
                .record(q->control(q,d.control())).bool(d.generatorEnabled()).name(d.cutoff()).bool(d.inlineFilter())
                .list(d.ports(),(q,p)->{position(q,p.cell());q.name(p.face()).name(p.port()).name(p.role());})
                .record(q->q.f64(s.volume()).f64(s.temperature()).f64(s.pressure()).doubles(s.composition())
                        .record(feed->feed.f64(s.solids().volumeFraction()).list(s.solids().grades(),(e,grade)->e.text(grade.material()).text(grade.size().metres()).f64(grade.massShare())))
                        .f64(s.levelHeight()).bool(s.open()).record(shape->geometry(shape,s.geometry())).list(s.tower()==null?List.<com.wormzjl.icheme.science.fluid.network.CoolingTower>of():List.of(s.tower()),FluidFacts::tower).f64(s.blanket()).f64(s.relief()).list(s.reliefFace()==null?List.<PhysicalFluidTopology.Direction>of():List.of(s.reliefFace()),(e,face)->e.name(face)))
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
            var towers=q.list(List.<com.wormzjl.icheme.science.fluid.network.CoolingTower>of(),FluidFacts::tower);if(towers.size()>1)throw q.invalid("Multiple cooling towers");
            // Schema 22: a spec written before the gas blanketing valve existed has none.
            double blanket=q.f64(0);
            // Schema 23: a spec written before the pressure safety valve existed has none.
            double relief=q.f64(0);var faces=q.list(List.<PhysicalFluidTopology.Direction>of(),e->required(e.name(PhysicalFluidTopology.Direction.class,null),e,"relief face"));if(faces.size()>1)throw q.invalid("Multiple relief faces");
            required(composition,q,"composition");return valid(q,"device spec",()->new FluidDeviceSpec(volume,temperature,pressure,composition,feed,levelHeight,open,shape,towers.isEmpty()?null:towers.getFirst(),blanket,relief,faces.isEmpty()?null:faces.getFirst()));});
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
     * (each a port by name, which one, its port device, its valve, its account and its solids) and totals; its further
     * condenser water sides (schema 8), its air condenser surface (schema 9) and its utility sides (schema 21).
     */
    private static void column(Fields f,ColumnUnit u) {
        var s=u.setpoints();var t=u.totals();
        f.count(u.id());position(f,u.master());f.bool(u.running())
                .record(q->setpoints(q,s))
                .list(u.stations(),(q,st)->{q.name(st.port()).count(st.index()).count(st.device()).count(st.valve()).record(a->account(a,st.account()));solids(q,Solid.of(st.solids()));})
                .record(q->q.record(a->account(a,t.taken())).record(a->account(a,t.produced())).f64(t.reboiler()).f64(t.condenser()).f64(t.pumparounds()))
                .count(u.condenser()).bool(u.leaving())
                // Schema 8 (S3b): the condenser's further water sides, one a shell in parallel.
                .list(u.condensers().isEmpty()?List.<Long>of():u.condensers().subList(1,u.condensers().size()),(q,side)->q.count(side))
                // Schema 9 (U2): the air condenser's bare tube, m².
                .f64(u.airSurface())
                // Schema 21 (W4): the utility sides of the reboiler and of each pumparound's cooler.
                .record(q->q.list(u.utilities().reboiler(),(p,side)->p.count(side)).list(u.utilities().pumparounds(),(p,sides)->p.list(sides,(e,side)->e.count(side))))
                // Schema 21 (W5): the powered members, the fan sides, the reflux pump's movers and each pumparound pump's.
                .record(q->q.list(u.members().fans(),(p,m)->p.count(m)).list(u.members().reflux(),(p,m)->p.count(m)).list(u.members().pumparounds(),(p,ms)->p.list(ms,(e,m)->e.count(m))));
    }
    private static ColumnUnit column(Record f) {
        long id=f.count(0);var master=position(f);boolean running=f.bool(false);
        var setpoints=f.record(null,FluidFacts::setpoints);
        var stations=f.list(List.of(),q->{var port=q.name(com.wormzjl.icheme.science.column.operation.Port.class,null);long index=q.count(0),device=q.count(0),valve=q.count(0);
            var account=q.record(null,FluidFacts::account);required(port,q,"station port");required(account,q,"station account");var held=solids(q);
            if(index>=ColumnUnit.MAXIMUM_STATIONS)throw q.invalid("station index "+index);
            // Crystals melt where they arrive, so what an account holds is inert: its saved record is the whole material.
            return valid(q,"column station",()->new ColumnUnit.Station(port,(int)index,device,valve,account,inventory(held,s->new SolidMaterial(s.id,s.revision,s.density,s.heatCapacity,null))));});
        var totals=f.record(null,q->{var taken=q.record(null,FluidFacts::account);var produced=q.record(null,FluidFacts::account);double reboiler=q.f64(0),condenser=q.f64(0),pumparounds=q.f64(0);
            required(taken,q,"taken total");required(produced,q,"produced total");
            return valid(q,"column totals",()->new ColumnUnit.Totals(taken,produced,reboiler,condenser,pumparounds));});
        long condenser=f.count(0);boolean leaving=f.bool(false);
        // A unit written before schema 8 has one water side at most.
        var further=f.list(List.<Long>of(),q->q.count(0));
        var condensers=new ArrayList<Long>();if(condenser!=0)condensers.add(condenser);condensers.addAll(further);
        if(condenser==0&&!further.isEmpty())throw f.invalid("column condenser sides");
        // A unit written before schema 9 has no air condenser surface: its air takes any duty, as before.
        double air=f.f64(0);
        // A unit written before schema 21 has no utility sides: its reboiler and pumparound duties stay free until its next Start.
        var utilities=f.record(ColumnUnit.Utilities.NONE,q->{var reboiler=q.list(List.<Long>of(),p->p.count(0));var pumparounds=q.list(List.<List<Long>>of(),p->p.list(List.<Long>of(),s->s.count(0)));
            return valid(q,"column utilities",()->new ColumnUnit.Utilities(reboiler,pumparounds));});
        // A unit written before schema 21 has no members: none draws power until its next Start.
        var members=f.record(ColumnUnit.Members.NONE,q->{var fans=q.list(List.<Long>of(),p->p.count(0));var reflux=q.list(List.<Long>of(),p->p.count(0));var pumparounds=q.list(List.<List<Long>>of(),p->p.list(List.<Long>of(),s->s.count(0)));
            return valid(q,"column members",()->new ColumnUnit.Members(fans,reflux,pumparounds));});
        required(master,f,"column origin");required(setpoints,f,"column setpoints");required(totals,f,"column totals");
        return valid(f,"column unit "+id,()->new ColumnUnit(id,master,running,setpoints,stations,totals,condensers,null,leaving,air,utilities,members));
    }
    private static Fields setpoints(Fields q,com.wormzjl.icheme.science.column.operation.Setpoints s) {
        return q.f64(s.designFeedRate()).f64(s.feedRate()).f64(s.condenserTemperature()).f64(s.refluxRatio()).f64(s.reboilerPerFeed())
                .doubles(s.steamPerFeed()).doubles(s.drawsPerFeed()).doubles(s.pumparoundsPerFeed());
    }
    private static com.wormzjl.icheme.science.column.operation.Setpoints setpoints(Record q) {
        double design=q.f64(0),rate=q.f64(0),condenser=q.f64(0),reflux=q.f64(0),reboiler=q.f64(0);
        var steam=q.doubles(new double[0]);var draws=q.doubles(new double[0]);var pumparounds=q.doubles(new double[0]);
        return valid(q,"column setpoints",()->new com.wormzjl.icheme.science.column.operation.Setpoints(design,rate,condenser,reflux,reboiler,steam,draws,pumparounds));
    }
    /**
     * A batch still (schema 16, batch 2026-11-09-batch-distiller): its pot's identity, its water side, distillate, vent and
     * burners (0 for none), its design (pot volume, heater by name, built-in condenser, trays), its head's two accounts, the
     * heat left in its burning item (J), its firing (0 to 1), its reflux valve (the ratio, then whether it is closed: a closed
     * valve's ratio is +Infinity, which a facts number cannot hold, so it is written as the default ratio and the flag) and its
     * totals (J).
     */
    private static void still(Fields f,StillUnit u) {
        f.count(u.id()).count(u.water()).count(u.distillate()).count(u.vent()).count(u.burners())
                .f64(u.volume()).name(u.heater()).bool(u.condenser()).count(u.stages())
                .record(a->account(a,u.head())).record(a->account(a,u.venting()))
                .f64(u.burning()).f64(u.firing()).f64(u.closedValve()?StillUnit.DEFAULT_REFLUX:u.reflux()).bool(u.closedValve()).f64(u.heated()).f64(u.condensed());
    }
    private static StillUnit still(Record f) {
        long id=f.count(0),water=f.count(0),distillate=f.count(0),vent=f.count(0),burners=f.count(0);
        double volume=f.f64(0);var heater=f.name(com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.class,null);boolean condenser=f.bool(false);long stages=f.count(0);
        var head=f.record(null,FluidFacts::account);var venting=f.record(null,FluidFacts::account);
        double burning=f.f64(0),firing=f.f64(StillUnit.DEFAULT_FIRING),valve=f.f64(StillUnit.DEFAULT_REFLUX);double reflux=f.bool(false)?Double.POSITIVE_INFINITY:valve;double heated=f.f64(0),condensed=f.f64(0);
        required(heater,f,"still heater");required(head,f,"still head account");required(venting,f,"still vent account");
        if(stages>com.wormzjl.icheme.science.fluid.network.StillColumn.MAX_STAGES)throw f.invalid("still trays "+stages);
        return valid(f,"batch still "+id,()->new StillUnit(id,water,distillate,vent,burners,volume,heater,condenser,(int)stages,head,venting,burning,firing,reflux,heated,condensed));
    }
    private static void account(Fields f,com.wormzjl.icheme.science.column.operation.ColumnCycle.Account a){f.doubles(a.moles()).f64(a.energy());}
    private static com.wormzjl.icheme.science.column.operation.ColumnCycle.Account account(Record f) {
        var moles=f.doubles(null);double energy=f.f64(0);required(moles,f,"account moles");
        return valid(f,"column account",()->new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(moles,energy));
    }
    /**
     * A shaft unit (schema 10, batch 2026-11-03-rotating-machinery W3): its identity, kind by name, position, axis, ends (each a
     * cell, its step and whether power leaves), fluid device, rated power, design speed, poles, drive by name, settings
     * (running, mode by name, set point, power limit, ratio) and trip by name. Its engine state is in the topology state.
     */
    private static void shaft(Fields f,ShaftUnit u) {
        var s=u.settings();
        f.count(u.id()).name(u.kind());position(f,u.position());f.count(u.axis())
                .list(u.ends(),(q,e)->{position(q,e.cell());q.i32(e.dx()).i32(e.dy()).i32(e.dz()).bool(e.output());})
                .count(u.fluid()).f64(u.ratedPower()).f64(u.designSpeed()).count(u.poles()).name(u.drive())
                .record(q->q.bool(s.running()).name(s.mode()).f64(s.setPoint()).f64(s.powerLimit()).f64(s.ratio()))
                .name(u.trip());
    }
    private static ShaftUnit shaft(Record f) {
        long id=f.count(0);var kind=f.name(com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.class,null);var position=position(f);long axis=f.count(0);
        var ends=f.list(List.<ShaftUnit.End>of(),q->{var cell=required(position(q),q,"shaft end cell");int dx=q.i32(0),dy=q.i32(0),dz=q.i32(0);boolean output=q.bool(false);
            return valid(q,"shaft end",()->new ShaftUnit.End(cell,dx,dy,dz,output));});
        long fluid=f.count(0);double power=f.f64(0),speed=f.f64(0);long poles=f.count(0);
        var drive=f.name(com.wormzjl.icheme.science.equipment.MachineDuty.Drive.class,com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED);
        var settings=f.record(ShaftUnit.Settings.DEFAULT,q->{boolean running=q.bool(true);var mode=q.name(com.wormzjl.icheme.science.machine.ShaftTrain.Mode.class,com.wormzjl.icheme.science.machine.ShaftTrain.Mode.SPEED);
            double set=q.f64(0),limit=q.f64(0),ratio=q.f64(0);return valid(q,"shaft settings",()->new ShaftUnit.Settings(running,mode,set,limit,ratio));});
        var trip=f.name(com.wormzjl.icheme.science.machine.ShaftTrain.Trip.class,com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE);
        required(kind,f,"shaft unit kind");required(position,f,"shaft unit position");
        if(axis>2||poles>12||ends.size()>ShaftUnit.MAXIMUM_ENDS)throw f.invalid("shaft unit "+id);
        return valid(f,"shaft unit "+id,()->new ShaftUnit(id,kind,position,(int)axis,ends,fluid,power,speed,(int)poles,drive,settings,trip,null));
    }
    /** A heat side's unit (schema 5): its unit and side by name, area, partner, control and heat in transit; an air cooler's fans (schema 11). */
    private static void heatUnit(Fields f,HeatUnit h) {
        var c=h.control();
        f.name(h.unit()).name(h.side()).f64(h.area()).count(h.partner()).record(q->q.name(c.mode()).f64(c.target()).f64(c.lowLimit()).f64(c.share())).f64(h.transitJoule())
                .count(h.fanCount()).name(h.exchangerType());
    }
    private static HeatUnit heatUnit(Record f) {
        var unit=f.name(com.wormzjl.icheme.science.equipment.HeatSides.Unit.class,null);var side=f.name(com.wormzjl.icheme.science.equipment.HeatSides.Side.class,null);double area=f.f64(0);long partner=f.count(0);
        var control=f.record(HeatUnit.Control.OFF,q->{var mode=q.name(HeatUnit.Mode.class,HeatUnit.Mode.OFF);double target=q.f64(0),lowLimit=q.f64(0),share=q.f64(0);
            return valid(q,"heat unit control",()->new HeatUnit.Control(mode,target,lowLimit,share));});
        double transit=f.f64(0);
        // A side saved before schema 11 has no fan count: 0, read as one fan motor for the bank until its structure restates it.
        long fans=f.count(0);
        required(unit,f,"heat unit");required(side,f,"heat unit side");
        if(fans>HeatUnit.MAXIMUM_FANS)throw f.invalid("heat unit fans "+fans);
        var type=f.name(com.wormzjl.icheme.science.equipment.ExchangerType.class,com.wormzjl.icheme.science.equipment.ExchangerType.STANDARD);
        return valid(f,"heat unit",()->new HeatUnit(unit,side,area,partner,control,transit,(int)fans,type));
    }
    private static void geometry(Fields f,com.wormzjl.icheme.science.fluid.network.VesselGeometry g){f.name(g.form()).f64(g.capacity()).f64(g.height()).f64(g.diameter()).f64(g.length()).f64(g.bootDiameter()).f64(g.bootLength());}
    private static com.wormzjl.icheme.science.fluid.network.VesselGeometry geometry(Record f){var form=f.name(com.wormzjl.icheme.science.fluid.network.VesselGeometry.Form.class,null);double capacity=f.f64(0),height=f.f64(0),d=f.f64(0),l=f.f64(0),bd=f.f64(0),bl=f.f64(0);return valid(f,"vessel geometry",()->new com.wormzjl.icheme.science.fluid.network.VesselGeometry(form,capacity,height,d,l,bd,bl));}
    private static void control(Fields f,FlowControl control) {
        switch(control) {
            case FlowControl.Passive ignored->f.text("passive").f64(0).f64(0).bool(false).f64(0);
            case FlowControl.Pump pump->f.text("pump").f64(pump.requestedVolumeFlow()).f64(pump.maximumAddedPressure()).bool(pump.enabled()).f64(0);
            case FlowControl.Compressor compressor->f.text("compressor").f64(compressor.requestedVolumeFlow()).f64(compressor.maximumPressureRatio()).bool(compressor.enabled()).f64(0).f64(compressor.setFlow()).f64(compressor.setPressure());
            // Schema 10 (W3): a casing on its curve, its set points, switch and law; its speed is its train's, a slice input.
            // Schema 19: a vacuum pump's law, its capacity in the rated flow's place, its rated speed in the pump's, then its type.
            case FlowControl.Machine machine when machine.law() instanceof com.wormzjl.icheme.science.machine.VacuumPump law->
                f.text("machine").f64(machine.setFlow()).f64(machine.setPressure()).bool(machine.enabled()).f64(0)
                        .record(q->q.text("vacuum_pump").f64(law.capacity()).f64(0).f64(0).f64(law.ratedSpeed()).name(law.type()));
            case FlowControl.Machine machine->{var law=machine.law();
                f.text("machine").f64(machine.setFlow()).f64(machine.setPressure()).bool(machine.enabled()).f64(0)
                        .record(q->q.text(law.liquid()?"centrifugal_pump":"centrifugal_compressor").f64(law.ratedFlow()).f64(law.ratedHead()).f64(law.efficiency())
                                .f64(law instanceof com.wormzjl.icheme.science.machine.CentrifugalPump pump?pump.ratedSpeed():0));}
            // Schema 17 (batch 2026-11-10-steam-ejector-flow): a steam ejector set, its suction's bore (m); its motive is a slice input.
            case FlowControl.Ejector ejector->f.text("ejector").f64(ejector.design().suction()).f64(0).bool(true).f64(0);
            // Schema 10 (W3): a steam turbine's rated power and isentropic efficiency.
            case FlowControl.Turbine turbine->f.text("turbine").f64(0).f64(0).bool(true).f64(0).f64(turbine.design().ratedPower()).f64(turbine.design().efficiency());
            case FlowControl.PressureValve valve->f.text("pressure_valve").f64(0).f64(0).bool(false).f64(valve.targetPressure());
            case FlowControl.ControlValve valve->{f.text("control_valve").f64(0).f64(0).bool(false).f64(valve.targetPressure()).name(valve.source());f.list(valve.sensor()==null?List.<java.util.UUID>of():List.of(valve.sensor()),FluidFacts::uuid).i64(valve.revision()).f64(valve.massTarget()).f64(valve.volumeTarget());}
        }
    }
    private static FlowControl control(Record f) {
        String kind=f.text("passive");double flow=f.f64(0),limit=f.f64(0);boolean enabled=f.bool(true);double target=f.f64(0);
        return valid(f,"control",()->switch(kind) {
            case "passive"->new FlowControl.Passive();
            case "pump"->new FlowControl.Pump(flow,limit,enabled);
            case "compressor"->new FlowControl.Compressor(flow,limit,enabled,f.f64(0),f.f64(0));
            case "pressure_valve"->new FlowControl.PressureValve(target);
            case "control_valve"->{var source=f.name(FlowControl.ValveSource.class,null);var sensor=f.list(List.of(),FluidFacts::uuid);long revision=f.i64(0);if(sensor.size()>1)throw f.invalid("multiple control sources");double mass=f.f64(1),volume=f.f64(.01);yield new FlowControl.ControlValve(source,target,mass,volume,sensor.isEmpty()?null:sensor.getFirst(),revision);}
            case "machine"->{var law=f.record(null,q->{var type=q.text("");double rated=q.f64(0),head=q.f64(0),efficiency=q.f64(0),rpm=q.f64(0);
                    return switch(type){case "centrifugal_pump"->new com.wormzjl.icheme.science.machine.CentrifugalPump(rated,head,efficiency,rpm);
                        case "centrifugal_compressor"->new com.wormzjl.icheme.science.machine.CentrifugalCompressor(rated,head,efficiency);
                        case "vacuum_pump"->{var vacuum=q.name(com.wormzjl.icheme.science.machine.VacuumPump.Type.class,null);if(vacuum==null)throw q.invalid("a vacuum pump without its type");
                            yield new com.wormzjl.icheme.science.machine.VacuumPump(vacuum,rated,rpm);}
                        default->throw q.invalid("unknown machine law "+type);};});
                if(law==null)throw f.invalid("a machine without its law");
                yield new FlowControl.Machine(law,0,enabled,flow,limit);}
            case "turbine"->{double power=f.f64(0),efficiency=f.f64(0);yield new FlowControl.Turbine(new com.wormzjl.icheme.science.machine.SteamTurbine(power,efficiency));}
            case "ejector"->new FlowControl.Ejector(new com.wormzjl.icheme.science.machine.SteamEjector(flow));
            default->throw new IllegalArgumentException("unknown control "+kind);
        });
    }
    /**
     * The topology unit's state: its queued events, each with the registrations it would install; then the running column
     * units' engine state ({@link ColumnUnit.State}), by unit; then the held devices of {@code active} and the held shaft
     * parts (W5c C1), by identity. An
     * event lists, after its recovery, the edits whose replacement is held. Holding is state (decision S11, batch
     * 2026-11-03-save-corruption): a registration's facts never carry it. A checkpoint written before the column states, or
     * before holding, has none.
     */
    static byte[] topologyState(List<WorldTopologyLedger.Event> events,Map<Long,ColumnUnit> columns){return topologyState(events,columns,Map.of());}
    /** {@code shafts}: the shaft units whose engine state (a driver's speed, W3) is kept, after the column states. */
    static byte[] topologyState(List<WorldTopologyLedger.Event> events,Map<Long,ColumnUnit> columns,Map<Long,ShaftUnit> shafts){return topologyState(events,columns,shafts,Map.of());}
    /** {@code active}: the registrations whose hold (registry first, decision S11) is kept, after the shaft states. */
    static byte[] topologyState(List<WorldTopologyLedger.Event> events,Map<Long,ColumnUnit> columns,Map<Long,ShaftUnit> shafts,Map<Long,WorldTopologyLedger.Registration> active) {
        var states=new ArrayList<ColumnUnit>();for(var u:columns.values())if(u.state()!=null)states.add(u);states.sort(Comparator.comparingLong(ColumnUnit::id));
        var speeds=new ArrayList<ShaftUnit>();for(var u:shafts.values())if(u.state()!=null)speeds.add(u);speeds.sort(Comparator.comparingLong(ShaftUnit::id));
        var held=new ArrayList<Long>();for(var r:active.values())if(r.device().held())held.add(r.device().id());for(var u:shafts.values())if(u.held())held.add(u.id());Collections.sort(held);
        return section(f->f.list(events,(q,event)->{uuid(q,event.id());q.count(event.tick());
            q.list(event.edits(),(e,edit)->e.count(edit.id()).list(edit.replacement()==null?List.<WorldTopologyLedger.Registration>of():List.of(edit.replacement()),FluidFacts::registration));
            var touched=new ArrayList<>(event.touched());Collections.sort(touched);q.list(touched,(t,id)->t.count(id));
            var recovery=event.recovery();
            q.list(recovery==null?List.<WorldTopologyLedger.Recovery>of():List.of(recovery),(r,v)->r.count(v.filterId()).list(v.player()==null?List.<UUID>of():List.of(v.player()),FluidFacts::uuid));
            q.list(event.edits().stream().filter(edit->edit.replacement()!=null&&edit.replacement().device().held()).map(WorldTopologyLedger.Edit::id).toList(),(h,id)->h.count(id));
        }).list(states,(q,u)->columnState(q.count(u.id()),u.state()))
          .list(speeds,(q,u)->shaftState(q.count(u.id()),u.state())).list(held,(q,id)->q.count(id)));
    }
    static byte[] events(List<WorldTopologyLedger.Event> events){return topologyState(events,Map.of());}
    /**
     * A topology unit's state: its events, the column states that validate (one that does not is dropped: that unit solves
     * again), the shaft drivers' speeds (W3), and the identities of its held devices and shaft parts.
     */
    record TopologyState(List<WorldTopologyLedger.Event> events,Map<Long,ColumnUnit.State> columns,Map<Long,ShaftUnit.State> shafts,Set<Long> held) {}
    static TopologyState topologyState(byte[] data,int offset,int length) {
        return read(data,offset,length,"state of the topology unit",f->{
            var events=f.list(List.<WorldTopologyLedger.Event>of(),q->{
                var id=required(uuid(q),q,"event identity");long tick=q.count(0);
                var edits=q.list(List.of(),e->{long device=e.count(0);var replacement=e.list(List.of(),FluidFacts::registration);if(replacement.size()>1)throw e.invalid("two replacements");
                    return valid(e,"edit",()->new WorldTopologyLedger.Edit(device,replacement.isEmpty()?null:replacement.getFirst()));});
                var touched=new HashSet<Long>();for(long t:q.list(List.<Long>of(),t->t.count(0)))if(!touched.add(t))throw q.invalid("duplicate touched device");
                var recovery=q.list(List.of(),r->{long filter=r.count(0);var player=r.list(List.of(),FluidFacts::uuid);return valid(r,"recovery",()->new WorldTopologyLedger.Recovery(filter,player.isEmpty()?null:player.getFirst()));});
                var holding=new HashSet<Long>();for(long h:q.list(List.<Long>of(),h->h.count(0)))if(!holding.add(h))throw q.invalid("duplicate held edit");
                var held=new ArrayList<WorldTopologyLedger.Edit>(edits.size());
                for(var edit:edits) {
                    if(!holding.remove(edit.id())){held.add(edit);continue;}
                    var r=edit.replacement();if(r==null)throw q.invalid("a held removal of "+edit.id());
                    held.add(valid(q,"held edit",()->new WorldTopologyLedger.Edit(edit.id(),new WorldTopologyLedger.Registration(r.device().withHeld(true),r.spec(),r.revision()))));
                }
                if(!holding.isEmpty())throw q.invalid("held edits of no edit "+holding);
                return valid(q,"event",()->new WorldTopologyLedger.Event(id,tick,held,touched,recovery.isEmpty()?null:recovery.getFirst()));
            });
            var columns=new HashMap<Long,ColumnUnit.State>();
            for(var entry:f.list(List.<Map.Entry<Long,ColumnUnit.State>>of(),q->{long id=q.count(0);var state=columnState(q);return new AbstractMap.SimpleEntry<>(id,state);}))
                if(entry.getValue()!=null&&columns.put(entry.getKey(),entry.getValue())!=null)throw new IllegalArgumentException("Invalid saved topology state: duplicate column unit "+entry.getKey());
            // W3: a driver's speed; one that does not validate is dropped and its train starts at its set speed.
            var shafts=new HashMap<Long,ShaftUnit.State>();
            for(var entry:f.list(List.<Map.Entry<Long,ShaftUnit.State>>of(),q->{long id=q.count(0);double speed=q.f64(0),previous=q.f64(Double.NaN),measured=q.f64(Double.NaN);
                    ShaftUnit.State state;try{state=new ShaftUnit.State(speed,previous,measured);}catch(IllegalArgumentException invalid){state=null;}return new AbstractMap.SimpleEntry<>(id,state);}))
                if(entry.getValue()!=null&&shafts.put(entry.getKey(),entry.getValue())!=null)throw new IllegalArgumentException("Invalid saved topology state: duplicate shaft unit "+entry.getKey());
            var held=new HashSet<Long>();for(long id:f.list(List.<Long>of(),q->q.count(0)))if(!held.add(id))throw f.invalid("duplicate held device "+id);
            return new TopologyState(events,columns,shafts,Set.copyOf(held));
        });
    }
    static List<WorldTopologyLedger.Event> events(byte[] data,int offset,int length){return topologyState(data,offset,length).events();}
    /** A driver's state: its speed, then the slice before's speed and measure while each is known; an absent one reads as NaN (none yet). */
    private static void shaftState(Fields f,ShaftUnit.State s) {
        f.f64(s.speed());
        if(!Double.isFinite(s.previousSpeed()))return;
        f.f64(s.previousSpeed());
        if(Double.isFinite(s.previousMeasured()))f.f64(s.previousMeasured());
    }
    private static Fields columnState(Fields f,ColumnUnit.State s) {
        var t=s.table();var ref=t.reference();
        var held=new double[t.held().length];for(int i=0;i<held.length;i++)held[i]=t.held()[i]?1:0;
        return f.list(t.axis(),(q,id)->q.text(id))
                .list(t.products(),(q,p)->q.name(p.port()).count(p.index()).doubles(p.recovery()).f64(p.temperature()).f64(p.pressure()))
                .f64(t.reboilerPerFeed()).doubles(t.pumparoundPerFeed()).f64(t.condenserPerFeed())
                .record(q->q.doubles(ref.feedFractions()).f64(ref.feedEnthalpy()).f64(ref.enthalpySlope()).doubles(ref.steamRatios()).f64(ref.alpha()).record(p->setpoints(p,ref.setpoints())).f64(ref.feedTemperature()).doubles(ref.steamTemperatures()))
                .doubles(held).list(Arrays.asList(s.enthalpy()),(q,row)->q.doubles(row)).i64(s.tableTick()).i64(s.applied()).f64(s.solvedCondenser())
                .list(s.levels(),(q,l)->q.count(l.device()).i64(l.tick()).f64(l.held()).f64(l.rate()).f64(l.molarMass()))
                .list(s.credits(),(q,c)->q.count(c.device()).i64(c.tick()).record(a->account(a,c.product())))
                .f64(t.condenserWaterPerFeed()).f64(t.waterBelow()).doubles(t.airArea())
                .record(q->{var p=s.probes();if(p!=null)q.f64(p.topPressure()).f64(p.topTemperature()).f64(p.bottomPressure()).f64(p.bottomTemperature()).f64(p.refluxPressure()).f64(p.refluxTemperature()).f64(p.refluxPerFeed()).f64(p.vapourPerFeed());})
                .record(q->{var h=t.flood();q.f64(h.perFeed()).count(h.tray()).bool(h.vapourLimited()).f64(h.dryPascal()).f64(h.liquidPascal());})
                .doubles(t.pumparoundDraw());
    }
    /** A column unit's engine state as saved, or null where it does not validate. */
    private static ColumnUnit.State columnState(Record f) {
        var axis=f.list(List.<String>of(),q->q.text(""));
        var products=f.list(List.<com.wormzjl.icheme.science.column.operation.SplitTable.Product>of(),q->{
            var port=q.name(com.wormzjl.icheme.science.column.operation.Port.class,null);long index=q.count(0);var recovery=q.doubles(new double[0]);double t=q.f64(0),p=q.f64(0);
            try{return new com.wormzjl.icheme.science.column.operation.SplitTable.Product(port,Math.toIntExact(index),recovery,t,p);}catch(RuntimeException invalid){return null;}});
        double reboiler=f.f64(0);var pa=f.doubles(new double[0]);double condenser=f.f64(0);
        var reference=f.record(null,q->{var z=q.doubles(new double[0]);double h=q.f64(0),slope=q.f64(0);var steam=q.doubles(new double[0]);double alpha=q.f64(0);
            var setpoints=q.record(null,p->{try{return setpoints(p);}catch(IllegalArgumentException invalid){return null;}});double feedT=q.f64(Double.NaN);var steamT=q.doubles(new double[0]);
            try{return new com.wormzjl.icheme.science.column.operation.SplitTable.Reference(z,h,slope,steam,alpha,setpoints,feedT,steamT);}catch(RuntimeException invalid){return null;}});
        var held=f.doubles(new double[0]);var enthalpy=f.list(List.<double[]>of(),q->q.doubles(new double[0])).toArray(new double[0][]);long tableTick=f.i64(0),applied=f.i64(0);double solved=f.f64(0);
        var levels=f.list(List.<ColumnUnit.State.Level>of(),q->{long device=q.count(0),tick=q.i64(0);double amount=q.f64(0),rate=q.f64(0),molar=q.f64(0);return new ColumnUnit.State.Level(device,tick,amount,rate,molar);});
        var credits=f.list(List.<ColumnUnit.State.Credit>of(),q->{long device=q.count(0),tick=q.i64(0);
            var product=q.record(null,a->{try{return account(a);}catch(IllegalArgumentException invalid){return null;}});return product==null?null:new ColumnUnit.State.Credit(device,tick,product);});
        // The water's share of the condenser duty (COOLER_MODEL_PLAN.md section 6): a state written before it, the whole duty.
        double water=f.f64(condenser);
        // Where a booking difference goes (C1b): a state written before it follows the water's share.
        double below=f.f64(Double.NaN);
        // The air condenser's surface per feed at each fan step (U2): a state written before it has no air the world models.
        var air=f.doubles(new double[0]);
        var probes=f.record(null,q->{try{return new ColumnUnit.Probes(q.f64(0),q.f64(0),q.f64(0),q.f64(0),q.f64(0),q.f64(0),q.f64(-1),q.f64(-1));}catch(IllegalArgumentException invalid){return null;}});
        // The table's hydraulics (batch 2026-11-10-column-failure-modes, W3): a state written before them is dropped, and that unit solves again.
        var flood=f.record(null,q->{double per=q.f64(-1);long tray=q.count(-1);boolean vapour=q.bool(true);double dry=q.f64(-1),liquid=q.f64(-1);
            try{return new com.wormzjl.icheme.science.column.operation.SplitTable.Flood(per,Math.toIntExact(tray),vapour,dry,liquid);}catch(RuntimeException invalid){return null;}});
        // The pumparounds' draw temperatures (W4): a state written before them has none, and its coolers' utilities read the hottest product.
        var draws=f.doubles(new double[0]);
        if(flood==null)return null;
        try {
            var kept=new boolean[held.length];for(int i=0;i<kept.length;i++)kept[i]=held[i]!=0;
            if(products.contains(null)||credits.contains(null))return null;
            var table=Double.isNaN(below)?new com.wormzjl.icheme.science.column.operation.SplitTable(axis,products,reboiler,pa,condenser,reference,kept,water)
                    :new com.wormzjl.icheme.science.column.operation.SplitTable(axis,products,reboiler,pa,condenser,reference,kept,water,below,air);
            return new ColumnUnit.State(table.withFlood(flood).withPumparoundDraw(draws),enthalpy,tableTick,applied,solved,levels,credits,probes);
        }catch(RuntimeException invalid){return null;}
    }

    // ---------------- an island ----------------

    /** A vessel's contents as saved: its inventory and the phase split of its state, on the island's component axis. */
    record Vessel(long id,double volume,double levelHeight,boolean open,double[] moles,double energy,List<Solid> solids,
                  double temperature,double pressure,double[] liquid,double[] vapor,double waterLiquid,double waterVapor,double hydrocarbonPressure,boolean rigid) {}
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
                result.add(new Vessel(v.id,v.volume,v.levelHeight,v.open,moles,v.energy+f*drift.energy.get(v.id),v.solids,v.temperature,v.pressure,v.liquid,v.vapor,v.waterLiquid,v.waterVapor,v.hydrocarbonPressure,v.rigid));
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
                q.f64(t.temperature()).f64(t.pressure()).doubles(t.liquid()).doubles(t.vapor()).f64(t.waterLiquid()).f64(t.waterVapor()).f64(t.hydrocarbonPartialPressure()).bool(i.rigid());});
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
                    required(q.doubles(null),q,"vessel moles"),q.f64(0),solids(q),q.f64(0),q.f64(0),required(q.doubles(null),q,"vessel liquid"),required(q.doubles(null),q,"vessel vapour"),q.f64(0),q.f64(0),q.f64(0),q.bool(false)));
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
            if(v.volume!=i.volume()||v.levelHeight!=i.levelHeight()||v.open!=i.open()||v.rigid!=i.rigid()||!same(v.moles,i.moles())||v.energy!=i.internalEnergy())return "vessel "+n.id()+" inventory";
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

    /**
     * An island's facts, the committed tick its index row gives, and whether its contents restate: an island restates only
     * when the records of its own compounds moved or one of them is gone (V3); the two-argument form restates with the world.
     */
    record Placed(Island island,long committed,boolean restate) {
        Placed(Island island,long committed){this(island,committed,true);}
    }
    /** What a rebuild made: the islands, the world ledger, and what it restated. */
    record Rebuilt(FluidCheckpointCodec.Checkpoint checkpoint,WorldTopologyLedger.Snapshot world,int vessels,int restated) {}

    /**
     * Rebuilds a world from its facts alone: the registry and ledger of {@code world}, the vessels' contents and the
     * filters' cakes of {@code islands}, compiled into islands as a topology change compiles them (their junctions
     * minted, decision D37), awake at {@code epoch} with no solved interval. Generators and voids are initialised from
     * their specs, as the registry holds them.
     *
     * <p>The world rebuilds on the current basis of {@code catalog}: a saved basis names its compounds by id, and its
     * package id is a label (batch 2026-11-04-refinery-compounds, W4). With {@code restate} the property data or the
     * basis changed (AGENTS.md, "Saves and data formats"): every amount is mapped
     * onto the current axis by component id (through the package's aliases); a vessel keeps its moles, its volume, its
     * solids and its temperature, and the new data gives its energy, pressure and phase split (a TP flash at its saved
     * temperature and pressure). The energy difference and every component and solid the data no longer has are booked
     * in the ledger's {@link WorldTopologyLedger.Restated}, and the islands' status names them.</p>
     *
     * <p>Restatement is per island (V3): a vessel of an island that does not restate ({@link Placed#restate}) keeps its saved
     * contents bit for bit, read on its own compound list as the state path reads it, whatever else changed (a compound
     * added to the package restates no island). Each rebuilt island runs on its own list: nitrogen, the compounds its
     * stock holds and those its column outlets' accounts hold (V2's registry compile; a list shrinks here to what is held).</p>
     */
    static Rebuilt rebuild(World world,List<Placed> islands,long epoch,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,
                           MaterialCatalog catalog,boolean restate,String reason) {
        // The world rebuilds on the current basis. Without a restatement the saved one holds the same compounds and identity
        // (the load compares them, whatever else stops the state), and the world then names the current network package.
        var basis=WorldTopologyLedger.FluidBasis.capture(catalog);
        if(!restate&&!world.basis.sameAs(basis))throw new IllegalArgumentException("The saved fluid basis "+world.basis.components()+" is not the current "+basis.components()+"; its facts must be restated");
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
            if(restate)spec=spec.withCharge(spec.temperature(),spec.pressure(),composition);
            registrations.put(r.device().id(),new WorldTopologyLedger.Registration(r.device(),spec,r.revision()));
        }
        // The column units, their accounts on the current axis, and their queued Starts and Stops, which do not survive this
        // path, settled on what stands. A component the data no longer has leaves, booked as removed, with its molar share of
        // the account's energy: an account has no temperature to restate at (owner 2026-11-02, merge review finding 5, option A).
        double energy=0;
        var columns=new HashMap<Long,ColumnUnit>();
        for(var saved:world.columns) {
            var u=restate?saved.withStations(saved.running(),saved.stations().stream().map(s->s.withSolids(inventory(Solid.of(s.solids()),solid))).toList()):saved;
            if(!axis.identity()) {
                var stations=new ArrayList<ColumnUnit.Station>();
                for(var s:u.stations()) {
                    var held=s.account();var kept=axis.map(held.moles(),removed);
                    double left=held.total()>0?held.energy()*Arrays.stream(kept).sum()/held.total():held.energy();
                    energy+=left-held.energy();stations.add(s.withAccount(new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(kept,left)));
                }
                var totals=new ColumnUnit.Totals(new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(axis.map(u.totals().taken().moles(),null),u.totals().taken().energy()),
                        new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(axis.map(u.totals().produced().moles(),null),u.totals().produced().energy()),u.totals().reboiler(),u.totals().condenser(),u.totals().pumparounds());
                u=new ColumnUnit(u.id(),u.master(),u.running(),u.setpoints(),stations,totals,u.condensers(),null,u.leaving(),u.airSurface(),u.utilities(),u.members());
            }
            columns.put(u.id(),settled(u,registrations));
        }
        // The batch stills (schema 16), their head's accounts on the current axis as a column's are: a component the data
        // no longer has leaves, booked as removed, with its molar share of the account's energy.
        var stills=new HashMap<Long,StillUnit>();
        for(var saved:world.stills) {
            var u=saved;
            if(!axis.identity()) {
                var kept=new ArrayList<com.wormzjl.icheme.science.column.operation.ColumnCycle.Account>();
                for(var held:List.of(u.head(),u.venting())) {
                    var moles=axis.map(held.moles(),removed);
                    double left=held.total()>0?held.energy()*Arrays.stream(moles).sum()/held.total():held.energy();
                    energy+=left-held.energy();kept.add(new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(moles,left));
                }
                u=u.withAccounts(kept.get(0),kept.get(1));
            }
            stills.put(u.id(),u);
        }
        var ports=new HashSet<Long>();
        for(var u:columns.values())for(var s:u.stations())if(s.device()!=0)ports.add(s.device());
        registrations.values().removeIf(r->r.device().port()&&!ports.contains(r.device().id()));
        // Vessel contents and filter cakes, by device identity.
        var stock=new HashMap<Long,PassiveNetwork.Reservoir>();var cakes=new HashMap<Long,InlineFilter>();int restated=0,vessels=0;
        var origins=new HashMap<Long,IslandSets.Origin>();
        for(var placed:islands) {
            var island=placed.island;var from=new Axis(island.components,basis.components(),restate?catalog.requirePackage(basis.packageId()):null);
            // The model the island's facts lie on, when it keeps its contents: its own compounds, all still in the package.
            var held=restate&&placed.restate?null:own(island,basis,models);
            for(var v:island.at(placed.committed)) {
                var r=registrations.get(v.id);
                if(r==null||r.device().kind()!=TopologyCompiler.Kind.RESERVOIR){warn("vessel "+v.id+" is not a registered reservoir; its contents are dropped");removed.add(v,from);continue;}
                double y=r.device().position().y();var solids=inventory(v.solids,solid);
                PassiveNetwork.Reservoir node;
                if(held!=null) {
                    var state=Arrays.stream(v.moles).sum()==0&&!solids.empty()?held.solidState(v.temperature,v.pressure,solids):held.state(v.temperature,v.pressure,v.liquid,v.vapor,v.waterLiquid,v.waterVapor,v.hydrocarbonPressure).withSolids(solids);
                    var kept=new PassiveNetwork.Reservoir(v.id,y,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(v.volume,v.moles,v.energy,solids,v.levelHeight,v.open,r.spec().geometry(),null,r.spec().tower(),v.rigid&&!v.open).withBlanket(r.spec().blanket()));
                    origins.put(v.id,new IslandSets.Origin(kept,held));node=IslandSets.node(kept,held,model);
                } else {
                    var moles=from.map(v.moles,removed);restated++;
                    // A rigid vessel stays rigid empty (batch 2026-11-10-rigid-reservoir, R1 A).
                    if(Arrays.stream(moles).sum()==0&&solids.empty()){node=r.spec().initialize(r.device(),model,()->{});node=new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),node.inventory().withRigid(v.rigid));}
                    else {
                        var state=Arrays.stream(moles).sum()==0?model.solidState(v.temperature,v.pressure,solids):model.flashTP(v.temperature,v.pressure,moles,()->{}).withSolids(solids);
                        node=new PassiveNetwork.Reservoir(v.id,y,state,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Inventory(v.volume,moles,state.internalEnergy(),solids,v.levelHeight,v.open,r.spec().geometry(),null,r.spec().tower(),v.rigid&&!v.open).withBlanket(r.spec().blanket()));
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
        // What each column outlet may deliver, on the current axis: its account's compounds join its island's list.
        var delivers=new HashMap<Long,Set<String>>();
        for(var u:columns.values())for(var st:u.stations()) {
            if(st.device()==0||st.port().inlet())continue;var n=st.account().moles();
            for(int i=0;i<n.length;i++)if(n[i]>0&&!basis.components().get(i).equals("Water"))delivers.computeIfAbsent(st.device(),ignored->new HashSet<>()).add(basis.components().get(i));
        }
        var compiled=PhysicalFluidTopology.compile(devices,stock,cakes,NetworkReduction.ambient(model),Set.of(),PhysicalRegistry.reliefs(registrations.values()));
        long next=world.nextIdentity;var entries=new ArrayList<FluidCheckpointCodec.IslandEntry>();
        String status="WAITING: rebuilt from saved facts ("+reason+")"+(removed.empty()?"":"; removed by the property data: "+removed.summary());
        for(var island:compiled.islands()) {
            var set=IslandSets.held(island.graph(),model,origins);for(long device:island.physicalIds())set.addAll(delivers.getOrDefault(device,Set.of()));
            var own=model.on(set);
            var graph=PassiveNetwork.mintJunctions(IslandSets.onto(island.graph(),model,own,origins),own);long id=next++;
            var dimension=registrations.get(island.physicalIds().iterator().next()).device().position().dimension();
            entries.add(new FluidCheckpointCodec.IslandEntry(dimension,key.packageId(),own.compounds(),new IslandCoordinator.Snapshot(id,0,graph,new IslandClock.Snapshot(epoch,epoch,0,REBUILT_CADENCE),
                    Optional.empty(),status,Map.of(),Optional.empty(),0)));
        }
        var recoveries=new HashMap<UUID,WorldTopologyLedger.RecoveredSolid>();
        for(var r:world.recoveries) {
            var solids=inventory(r.solids,solid);
            if(solids.empty()){warn("recovery "+r.id+" holds only solids the data no longer has; it is dropped");continue;}
            recoveries.put(r.id,new WorldTopologyLedger.RecoveredSolid(r.position,r.player,solids,r.energy));
        }
        var restatedTotal=world.restated.plus(new WorldTopologyLedger.Restated(energy,removed.moles,removed.solids));
        // The build facts are kept whole: a paint of a device this rebuild left out goes with it.
        var shafts=new HashMap<Long,ShaftUnit>();for(var u:world.shafts)shafts.put(u.id(),u);
        var snapshot=new WorldTopologyLedger.Snapshot(epoch,next,registrations,List.of(),axis.total(world.constructed),axis.total(world.destroyed),basis,recoveries,restatedTotal,columns,shafts,world.electricStores(),world.health(),
                world.builds.keeping(registrations.keySet()),stills);
        var ledger=new WorldTopologyLedger(snapshot);
        // A dismantled column whose release was still queued leaves now, its accounts booked as destroyed.
        for(var u:columns.values())if(u.leaving())ledger.removeColumn(u.id());
        snapshot=ledger.snapshot();
        if(restate)IChemE.LOGGER.warn("fluid_checkpoint status=RESTATED vessels={} energy_delta_J={} removed={}",restated,energy,removed.empty()?"none":removed.summary());
        return new Rebuilt(new FluidCheckpointCodec.Checkpoint(entries),snapshot,vessels,restated);
    }
    /** The model island facts {@code island} lie on now: the network package on its own compounds (water not named), or null when one of them is gone. */
    private static FluidThermodynamics own(Island island,WorldTopologyLedger.FluidBasis basis,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        var compounds=island.components.stream().filter(c->!c.equals("Water")).toList();
        try{var held=models.apply(new FluidCheckpointCodec.PackageKey(basis.packageId(),compounds));return held!=null&&held.components().equals(island.components)?held:null;}
        catch(IllegalArgumentException gone){return null;}
    }
    /**
     * Unit {@code u} as what stands in {@code registrations} leaves it, queued events gone: a unit that is stopped, leaves or
     * misses a port device releases what it holds and its ports leave, so no device stays held by a stopped unit and no
     * running unit misses a port. A running unit whose devices all stand keeps running.
     */
    private static ColumnUnit settled(ColumnUnit u,Map<Long,WorldTopologyLedger.Registration> registrations) {
        boolean unplaced=u.running()&&u.stations().stream().anyMatch(s->s.device()!=0&&!registrations.containsKey(s.device()));
        if(u.running()&&!unplaced&&!u.leaving())return u;
        for(var r:List.copyOf(registrations.values()))if(r.device().suspendedBy()==u.id())
            registrations.put(r.device().id(),new WorldTopologyLedger.Registration(r.device().withSuspendedBy(0),r.spec(),Math.addExact(r.revision(),1)));
        for(var s:u.stations())if(s.device()!=0)registrations.remove(s.device());
        return u.withStations(false,u.stations().stream().map(s->s.withDevice(0)).toList());
    }
    /** A generator's or void's state from its spec; a spec outside the package's input box is clamped into it first (logged). */
    private static PassiveNetwork.Reservoir initialised(WorldTopologyLedger.Registration r,FluidThermodynamics model,MaterialCatalog catalog) {
        var spec=r.spec();var d=r.device();
        if(d.kind()!=TopologyCompiler.Kind.RESERVOIR) {
            var box=model.domain().envelope();
            double t=Math.min(box.maximumTemperature(),Math.max(box.minimumTemperature(),spec.temperature())),p=Math.min(box.maximumPressure(),Math.max(box.minimumPressure(),spec.pressure()));
            if(t!=spec.temperature()||p!=spec.pressure()) {
                warn("device "+d.id()+": its spec "+spec.temperature()+" K, "+spec.pressure()+" Pa lies outside the package's input box; it now reads "+t+" K, "+p+" Pa");
                spec=spec.withCharge(t,p,spec.composition());
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
