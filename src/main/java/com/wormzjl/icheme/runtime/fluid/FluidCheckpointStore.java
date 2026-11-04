package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.science.fluid.state.EnergyReference;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import net.minecraft.nbt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Where checkpoint format 11 lives, and what a save writes (the owner's decision F3): per-island storage with the mod's
 * own dirty tracking, no world-wide size bound, a small core record in the world's saved data.
 *
 * <p>Layout: the core record ({@link FluidCheckpointCodec#coreTag}) is the world's saved data file
 * {@code data/icheme_fluid_core.dat}. Every island (and the world topology) is one storage unit; units live in
 * pack files {@code data/icheme_fluid/fluid-<n>.pack}, and the core record's index says, per island, which pack,
 * offset, length and SHA-256 hold its unit. A save writes one new pack (more only past 256 MiB): the units whose island
 * changed since they were written (revision and payload generation), plus any live units it moves out of a pack that
 * has become mostly dead; unchanged units keep their place in their pack. Packs rather than one file per island
 * because a durable atomic file write costs about 2.5 ms whatever its size (2.4 s of the IO thread for 1,000 changed
 * islands at every autosave, 10,000 file opens at every load), while a pack costs one flushed write per save (6 ms for
 * 5 MB) and a bounded number of files.</p>
 *
 * <p>Commit protocol: the new pack is written first (temporary file, flushed, atomically renamed), then the core record
 * the same way; a crash before the core's rename leaves the previous core and every pack it references intact. Packs
 * are never modified after they are written; a pack is deleted only when neither the new core record nor the one
 * before it references it, and a load deletes every pack its core does not reference. Commits run on the IO thread in
 * submission order; a commit whose predecessor failed is not written either, and the next save writes every unit
 * afresh. Load: every pack the core lists must be present with exactly the listed size and header, every unit's
 * SHA-256 must match the index and its header the island, revision and generation the index gives; anything else
 * refuses the world with the fresh-world instruction, its files left as they are. The loaded index seeds the dirty
 * tracking.</p>
 */
public final class FluidCheckpointStore {
    static final int PACK_MAGIC=0x43434650,PACK_FORMAT=1;
    /** Magic, format, pack number. */
    static final int PACK_HEADER=4+4+8;
    /** At most this many packs are kept referenced; beyond it the smallest are compacted into the next save's pack. */
    static final int MAXIMUM_PACKS=16;
    /** A save starts a new pack when the next unit would take the current one past this size, so no pack nears the
     * 2 GiB of an array and no save, however large, is bounded by one. */
    static final long PACK_LIMIT=256L<<20;
    public static final String CORE_NAME=FluidSavedData.DATA_NAME+".dat";
    public static final String UNIT_DIRECTORY="icheme_fluid";
    private static final Pattern PACK_NAME=Pattern.compile("fluid-([0-9]{1,18})\\.pack");
    private static final String TEMPORARY_SUFFIX=".tmp";
    /** The {@code basedOn} of a plan that writes every unit afresh and depends on nothing written before. */
    private static final long SELF_CONTAINED=-1;

    /** Where packs and the core record are kept. */
    interface Backend {
        byte[] readPack(long pack) throws IOException;
        /** {@code length} bytes of a pack from {@code offset}: a unit a compaction moves. */
        byte[] readUnit(long pack,long offset,int length) throws IOException;
        /** Writes a pack atomically and durably: a reader sees either no file or the whole pack. */
        void writePack(long pack,byte[] bytes) throws IOException;
        /** Replaces the core record atomically and durably. */
        void writeCore(CompoundTag core) throws IOException;
        /** The committed core record, or null when there is none. */
        CompoundTag readCore() throws IOException;
        Set<Long> packs() throws IOException;
        void deletePack(long pack) throws IOException;
        /** Deletes temporary files an interrupted write left behind. */
        int cleanTemporary() throws IOException;
    }

    /** Where a live unit lies, and which island state it records (revision, in-memory payload generation and the package it
     * names; a topology unit names none). */
    private record Ref(long revision,long payloadGeneration,String packageId,long generation,FluidCheckpointCodec.UnitRef unit) {}
    private sealed interface Segment permits Bytes,Copy {}
    private record Bytes(byte[] data) implements Segment {}
    private record Copy(FluidCheckpointCodec.UnitRef from) implements Segment {}
    /** One pack a save writes: its number, its size and its units in order. */
    private record NewPack(long number,long size,List<Segment> segments) {}
    /**
     * What one save costs and writes: islands, units encoded afresh, reused in place and moved by compaction, their
     * bytes, the island units' and the topology's bytes, whether every unit was written afresh, the first
     * new pack (0 when nothing but the core record was written), how many new packs and their bytes, the packs the core
     * record references, and its size.
     */
    public record Stats(int islands,int encoded,int reused,int copied,long encodedBytes,long copiedBytes,long islandBytes,
                        long topologyBytes,boolean topologyEncoded,boolean full,long pack,int newPacks,long packBytes,int packs,long coreBytes) {}
    /** One prepared save: its new packs and the sealed core record, in commit order. */
    public static final class Plan {
        private final long sequence,basedOn;private final List<NewPack> packs;private final CompoundTag core;private final long[] referenced;private final Stats stats;
        private Plan(long sequence,long basedOn,List<NewPack> packs,CompoundTag core,long[] referenced,Stats stats) {
            this.sequence=sequence;this.basedOn=basedOn;this.packs=packs;this.core=core;this.referenced=referenced;this.stats=stats;
        }
        public CompoundTag core(){return core;}
        public Stats stats(){return stats;}
    }
    /** How a load read its checkpoint: exactly as saved, or rebuilt from its facts. */
    public enum Route {EXACT,FACTS}
    /** What a load read: the islands and the world ledger, how, and (rebuilt from facts) why. */
    public record Loaded(FluidCheckpointCodec.Checkpoint checkpoint,Optional<WorldTopologyLedger.Snapshot> world,Route route,String reason) {}

    private final Backend backend;private final Executor io;
    // Save state, on the saving (server) thread.
    private final Map<Long,Ref> islands=new HashMap<>();
    private Ref topology;private WorldTopologyLedger.Snapshot topologySource;
    private final Map<Long,Long> packSizes=new TreeMap<>();
    private FluidCheckpointCodec.Strings strings=new FluidCheckpointCodec.Strings();
    private long nextPack=1,nextGeneration=1,sequence,knownFailure;
    private boolean selfContained=true,loaded;
    private long[] loadedPacks=new long[0];
    private long packLimit=PACK_LIMIT;
    private CompletableFuture<Void> pending=CompletableFuture.completedFuture(null);
    // Commit state, on the IO thread (guarded by the lock; the two sequences are read by the saving thread).
    private final Object lock=new Object();
    private volatile long lastCommitted,lastFailed;
    private Set<Long> previousReferenced=Set.of();

    private FluidCheckpointStore(Backend backend,Executor io){this.backend=Objects.requireNonNull(backend);this.io=Objects.requireNonNull(io);}
    /** A store in memory, committing on the calling thread: tests and tools. */
    public static FluidCheckpointStore memory(){return new FluidCheckpointStore(new Memory(),Runnable::run);}
    /** A store in memory holding {@code image}, ready to be loaded. */
    static FluidCheckpointStore memory(FluidCheckpointCodec.Image image){var memory=new Memory();memory.packs.putAll(image.packs());memory.core=image.core();return new FluidCheckpointStore(memory,Runnable::run);}
    /**
     * The store of a world whose saved data lives in {@code dataFolder} ({@code <world>/data}): the core record is
     * {@code icheme_fluid_core.dat} there, packs are in its {@code icheme_fluid} folder. Commits submitted
     * with {@link #submit} run on {@code io}; {@code stampDataVersion} adds Minecraft's data version to the core record
     * as a saved data file carries it.
     */
    public static FluidCheckpointStore directory(Path dataFolder,Executor io,boolean stampDataVersion){return new FluidCheckpointStore(new Directory(dataFolder,stampDataVersion),io);}
    /** Tests: a smaller pack size, so a save of a few units spans several packs. */
    void packLimit(long bytes){if(bytes<=PACK_HEADER)throw new IllegalArgumentException();packLimit=bytes;}
    /** A store over any backend: tests that inject write failures. */
    static FluidCheckpointStore of(Backend backend,Executor io){return new FluidCheckpointStore(backend,io);}

    // ---------------- save ----------------

    /** Lays out the units a save writes: a new pack is started when the next unit would take the current one past
     * {@link #PACK_LIMIT} (a unit larger than that gets a pack of its own). */
    private final class Layout {
        final List<NewPack> packs=new ArrayList<>();private List<Segment> segments;private long number,offset;
        FluidCheckpointCodec.UnitRef place(Segment segment,int length,byte[] sha256) {
            if(segments==null||offset>PACK_HEADER&&offset+length>packLimit)open();
            var unit=new FluidCheckpointCodec.UnitRef(number,offset,length,sha256);segments.add(segment);offset+=length;return unit;
        }
        private void open(){close();number=nextPack++;offset=PACK_HEADER;segments=new ArrayList<>();}
        List<NewPack> close(){if(segments!=null){packs.add(new NewPack(number,offset,List.copyOf(segments)));packSizes.put(number,offset);segments=null;}return packs;}
        int count(){return packs.size()+(segments==null?0:1);}
        boolean contains(long pack){return segments!=null&&pack==number||packs.stream().anyMatch(p->p.number==pack);}
    }
    /**
     * Prepares a save on the saving thread: every island whose revision and payload generation match its stored unit
     * keeps that unit where it is; every other one is encoded into the new pack, with the topology when its ledger
     * changed; live units of mostly dead packs are moved into it; the core record indexes the result. Nothing is
     * written here. After a failed commit, or after {@link #forget}, every unit is written afresh.
     */
    public Plan prepare(FluidCheckpointCodec.Checkpoint checkpoint,Optional<WorldTopologyLedger.Snapshot> world,long epoch,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) {
        Objects.requireNonNull(checkpoint);Objects.requireNonNull(world);Objects.requireNonNull(models);
        if(epoch<0)throw new IllegalArgumentException("Negative world epoch");
        long failed=lastFailed;if(failed>knownFailure){knownFailure=failed;selfContained=true;}
        long seq=++sequence;boolean full=selfContained;long basedOn=full?SELF_CONTAINED:seq-1;
        if(full){islands.clear();topology=null;topologySource=null;packSizes.clear();strings=new FluidCheckpointCodec.Strings();}
        var layout=new Layout();
        var refs=new LinkedHashMap<Long,Ref>();var snapshots=new ArrayList<IslandCoordinator.Snapshot>();
        var packages=new LinkedHashMap<FluidCheckpointCodec.PackageKey,FluidCheckpointCodec.PackageRecord>();
        int encoded=0,reused=0,copied=0;long encodedBytes=0,copiedBytes=0,topologyBytes=0;boolean topologyEncoded=false;
        for(var entry:checkpoint.islands()) {
            var s=entry.snapshot();
            if(s.clock().onlineTick()>epoch)throw new IllegalStateException("Island "+s.id()+" at online tick "+s.clock().onlineTick()+" is ahead of the world epoch "+epoch);
            var key=entry.key();
            var packageModel=Objects.requireNonNull(models.apply(key));
            if(!packages.containsKey(key))packages.put(key,FluidCheckpointCodec.PackageRecord.of(key,packageModel));
            var old=islands.get(s.id());Ref ref;
            if(old!=null&&s.payloadGeneration()!=0&&old.revision==s.revision()&&old.payloadGeneration==s.payloadGeneration()&&old.packageId.equals(entry.packageId())) {
                ref=old;reused++;
                if(FluidCheckpointCodec.verifying()&&!MessageDigest.isEqual(FluidCheckpointCodec.sha256(FluidCheckpointCodec.islandUnit(entry,old.generation,strings,packageModel.components())),old.unit.sha256()))
                    throw new IllegalStateException("Stored unit of island "+s.id()+" at payload generation "+s.payloadGeneration()+" no longer matches the island");
            } else {
                long generation=nextGeneration++;byte[] unit=FluidCheckpointCodec.islandUnit(entry,generation,strings,packageModel.components());
                ref=new Ref(s.revision(),s.payloadGeneration(),entry.packageId(),generation,layout.place(new Bytes(unit),unit.length,FluidCheckpointCodec.sha256(unit)));
                encoded++;encodedBytes+=unit.length;
            }
            if(refs.put(s.id(),ref)!=null)throw new IllegalArgumentException("Duplicate saved island identity");
            snapshots.add(s);
        }
        Ref topologyRef=null;
        if(world.isPresent()) {
            var current=world.orElseThrow();
            if(topology!=null&&topologySource!=null&&FluidCheckpointCodec.sameWorldBody(topologySource,current)) {
                topologyRef=topology;
                if(FluidCheckpointCodec.verifying()&&!MessageDigest.isEqual(FluidCheckpointCodec.sha256(FluidCheckpointCodec.topologyUnit(current,topology.generation)),topology.unit.sha256()))
                    throw new IllegalStateException("Stored topology unit no longer matches the world ledger");
            } else {
                long generation=nextGeneration++;byte[] unit=FluidCheckpointCodec.topologyUnit(current,generation);
                topologyRef=new Ref(0,0,"",generation,layout.place(new Bytes(unit),unit.length,FluidCheckpointCodec.sha256(unit)));topologyEncoded=true;
            }
        }
        // Compaction: a pack less than half live, or the smallest packs beyond the bound, move their live units here.
        var live=new TreeMap<Long,Long>();
        for(var ref:refs.values())if(!layout.contains(ref.unit.pack()))live.merge(ref.unit.pack(),(long)ref.unit.length(),Long::sum);
        if(topologyRef!=null&&!layout.contains(topologyRef.unit.pack()))live.merge(topologyRef.unit.pack(),(long)topologyRef.unit.length(),Long::sum);
        var moved=new HashSet<Long>();
        for(var e:live.entrySet())if(2*e.getValue()<packSizes.get(e.getKey())-PACK_HEADER)moved.add(e.getKey());
        var byLive=live.entrySet().stream().filter(e->!moved.contains(e.getKey())).sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
        for(int i=0;live.size()-moved.size()+Math.max(1,layout.count())>MAXIMUM_PACKS&&i<byLive.size();i++)moved.add(byLive.get(i));
        if(!moved.isEmpty()) {
            for(var e:refs.entrySet()) {
                var ref=e.getValue();if(!moved.contains(ref.unit.pack()))continue;
                var unit=layout.place(new Copy(ref.unit),ref.unit.length(),ref.unit.sha256());
                e.setValue(new Ref(ref.revision,ref.payloadGeneration,ref.packageId,ref.generation,unit));copied++;copiedBytes+=ref.unit.length();
            }
            if(topologyRef!=null&&moved.contains(topologyRef.unit.pack())) {
                var from=topologyRef.unit;topologyRef=new Ref(0,0,"",topologyRef.generation,layout.place(new Copy(from),from.length(),from.sha256()));copied++;copiedBytes+=from.length();
            }
        }
        var newPacks=layout.close();
        var referenced=new TreeSet<Long>();for(var ref:refs.values())referenced.add(ref.unit.pack());if(topologyRef!=null)referenced.add(topologyRef.unit.pack());
        packSizes.keySet().retainAll(referenced);
        islands.clear();islands.putAll(refs);topology=topologyRef;topologySource=world.orElse(null);selfContained=false;
        var rows=new ArrayList<FluidCheckpointCodec.IndexRow>(snapshots.size());long islandBytes=0;
        for(var s:snapshots) {
            var ref=refs.get(s.id());islandBytes+=ref.unit.length();var persisted=s.certificate().flatMap(IslandCoordinator.Certified::saved);var clock=s.clock();
            rows.add(persisted.map(saved->new FluidCheckpointCodec.IndexRow(s.id(),s.revision(),ref.generation,clock.onlineTick(),clock.committedTick(),clock.retryAtTick(),clock.cadenceTicks(),
                    saved.baseTick(),saved.sinceTick(),saved.interval().startTick(),saved.horizonTick(),ref.unit))
                    .orElseGet(()->new FluidCheckpointCodec.IndexRow(s.id(),s.revision(),ref.generation,clock.onlineTick(),clock.committedTick(),clock.retryAtTick(),clock.cadenceTicks(),
                    FluidCheckpointCodec.AWAKE,0,0,0,ref.unit)));
        }
        if(topologyRef!=null)topologyBytes=topologyRef.unit.length();
        long[] packs=referenced.stream().mapToLong(Long::longValue).toArray();long[] sizes=Arrays.stream(packs).map(packSizes::get).toArray();
        var core=FluidCheckpointCodec.coreTag(new FluidCheckpointCodec.CoreRecord(FluidCheckpointCodec.VERSION,FluidFacts.SCHEMA,epoch,strings,List.copyOf(packages.values()),packs,sizes,nextPack,nextGeneration,
                topologyRef==null?0:topologyRef.generation,topologyRef==null?null:topologyRef.unit,rows));
        long packBytes=newPacks.stream().mapToLong(NewPack::size).sum();
        var stats=new Stats(rows.size(),encoded,reused,copied,encodedBytes,copiedBytes,islandBytes,topologyBytes,topologyEncoded,full,
                newPacks.isEmpty()?0:newPacks.getFirst().number,newPacks.size(),packBytes,packs.length,core.sizeInBytes());
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.payloadsEncoded,encoded);FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.payloadsReused,reused);
        return new Plan(seq,basedOn,List.copyOf(newPacks),core,packs,stats);
    }
    /** The next save writes every unit afresh (a cold save). */
    public void forget(){selfContained=true;}

    /** Commits a plan on the calling thread, after every commit submitted before it. */
    public void commit(Plan plan){pending.join();write(plan);}
    /** Commits a plan on the IO executor, after every commit submitted before it; never throws there. */
    public void submit(Plan plan) {
        var done=new CompletableFuture<Void>();pending=done;
        io.execute(()->{try{write(plan);}catch(Throwable unexpected){IChemE.LOGGER.error("fluid_checkpoint status=COMMIT_FAILED sequence={}",plan.sequence,unexpected);}finally{done.complete(null);}});
    }
    /** Waits for every submitted commit. */
    public void flush(){pending.join();}
    /** Whether the last prepared save was committed. */
    public boolean committed(){pending.join();return lastCommitted==sequence;}

    private void write(Plan plan) {
        synchronized(lock) {
            if(plan.basedOn!=SELF_CONTAINED&&lastCommitted!=plan.basedOn) {
                lastFailed=plan.sequence;
                IChemE.LOGGER.error("fluid_checkpoint status=NOT_WRITTEN sequence={} detail=the save it builds on ({}) was not written; the next save writes every unit afresh",plan.sequence,plan.basedOn);
                return;
            }
            try {
                for(var pack:plan.packs){byte[] data=assemble(pack);backend.writePack(pack.number,data);}
                backend.writeCore(plan.core);
                lastCommitted=plan.sequence;
            } catch(IOException|RuntimeException failure) {
                lastFailed=plan.sequence;
                IChemE.LOGGER.error("fluid_checkpoint status=WRITE_FAILED sequence={} detail=the previous save stays in force; the next save writes every unit afresh",plan.sequence,failure);
                return;
            }
            var keep=new HashSet<Long>();for(long p:plan.referenced)keep.add(p);var referenced=Set.copyOf(keep);keep.addAll(previousReferenced);
            try{for(long p:backend.packs())if(!keep.contains(p))backend.deletePack(p);}
            catch(IOException failure){IChemE.LOGGER.warn("fluid_checkpoint status=CLEANUP_FAILED detail=unreferenced packs stay until the next save or load",failure);}
            previousReferenced=referenced;
        }
    }
    /** A new pack's bytes: its header, then each segment; a moved unit is read from its pack and checked first. */
    private byte[] assemble(NewPack pack) throws IOException {
        var data=new byte[Math.toIntExact(pack.size)];var header=ByteBuffer.wrap(data);header.putInt(PACK_MAGIC);header.putInt(PACK_FORMAT);header.putLong(pack.number);
        int offset=PACK_HEADER;
        for(var segment:pack.segments) {
            switch(segment) {
                case Bytes b->{System.arraycopy(b.data,0,data,offset,b.data.length);offset+=b.data.length;}
                case Copy c->{
                    var from=c.from;byte[] unit=backend.readUnit(from.pack(),from.offset(),from.length());
                    if(!MessageDigest.isEqual(FluidCheckpointCodec.sha256(unit),from.sha256()))throw new IOException("Unit to move out of pack "+from.pack()+" at "+from.offset()+" no longer matches its digest");
                    System.arraycopy(unit,0,data,offset,unit.length);offset+=unit.length;
                }
            }
        }
        if(offset!=data.length)throw new IllegalStateException("Pack layout mismatch");
        return data;
    }
    /** A pack holding {@code units} after its header, for tests that build one by hand. */
    static byte[] pack(long number,List<byte[]> units) {
        int size=PACK_HEADER;for(var unit:units)size+=unit.length;var data=ByteBuffer.allocate(size);data.putInt(PACK_MAGIC);data.putInt(PACK_FORMAT);data.putLong(number);
        for(var unit:units)data.put(unit);return data.array();
    }

    // ---------------- load ----------------

    /**
     * Reads a world's checkpoint from its core record and this store's packs (AGENTS.md, "Saves and data formats"),
     * validating everything (see the class comment). The load is exact when the checkpoint's state is this build's
     * format, its property basis is the current one and every state section decodes: the islands resume as saved, and
     * the loaded index seeds the dirty tracking. Otherwise the world is rebuilt from its facts alone
     * ({@link FluidFacts#rebuild}, restating its contents when the property data changed); nothing is reused, so the
     * next save writes every unit afresh, and the packs read stay until its core record replaces this one. An envelope
     * or facts section this build cannot read, a missing or damaged pack and a digest mismatch refuse the world.
     * Packs are read one at a time, and each is released once its units are read. Only a store that has neither loaded
     * nor saved yet can load. Nothing is written or deleted; {@link #cleanOrphans} does that once the load is accepted.
     */
    public Loaded load(CompoundTag coreTag,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models){return load(coreTag,models,MaterialRuntime.current());}
    /** {@link #load} with the material data {@code catalog} the models were built from, which a rebuild from facts reads. */
    public Loaded load(CompoundTag coreTag,Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,MaterialCatalog catalog) {
        if(loaded||sequence!=0)throw new IllegalStateException("A fluid checkpoint store loads once, before any save");
        Objects.requireNonNull(catalog);
        var core=FluidCheckpointCodec.readCore(coreTag);
        // Why the state cannot be used, once known: the load then reads facts only.
        String why=core.stateFormat()!=FluidCheckpointCodec.VERSION?"state format "+core.stateFormat()+", this build's is "+FluidCheckpointCodec.VERSION:null;boolean restate=false;
        var byKey=new HashMap<FluidCheckpointCodec.PackageKey,FluidThermodynamics>();
        for(var p:core.packages()) {
            // The model the saved package's key names now (a world's islands run on its network package, whatever id the save
            // names): units written under other data restate.
            var model=Objects.requireNonNull(models.apply(p.key()));
            byKey.put(p.key(),model);
            var reference=EnergyReference.sensible(model.components());
            if(reference.formationDataQualified())throw new IllegalArgumentException("This build's energy reference of package "+p.packageId()+" is formation-qualified, which a checkpoint cannot carry");
            if(!p.propertyRevision().equals(model.thermodynamicRevision())||!p.energyRevision().equals(reference.revision())||!p.energyComponents().equals(reference.components())) {
                restate=true;why="the property data of package "+p.packageId()+" changed";
            }
        }
        var rowsByPack=new HashMap<Long,List<Integer>>();
        for(int i=0;i<core.islands().size();i++)rowsByPack.computeIfAbsent(core.islands().get(i).unit().pack(),ignored->new ArrayList<>()).add(i);
        var entries=new FluidCheckpointCodec.IslandEntry[core.islands().size()];var facts=new FluidFacts.Island[entries.length];
        Optional<WorldTopologyLedger.Snapshot> world=Optional.empty();FluidFacts.World worldFacts=null;
        for(int i=0;i<core.packs().length;i++) {
            long p=core.packs()[i];byte[] bytes=pack(p,core.packBytes()[i]);
            for(int index:rowsByPack.getOrDefault(p,List.of())) {
                var row=core.islands().get(index);var unit=row.unit();
                if(!MessageDigest.isEqual(FluidCheckpointCodec.sha256(bytes,(int)unit.offset(),unit.length()),unit.sha256()))throw new IllegalArgumentException("Fluid checkpoint checksum mismatch in the unit of island "+row.id());
                var sections=FluidCheckpointCodec.islandSections(bytes,row);facts[index]=FluidCheckpointCodec.islandFacts(bytes,sections,row.id());
                if(why==null)try{entries[index]=FluidCheckpointCodec.island(bytes,row,sections,facts[index],core.epoch(),core.strings(),byKey);}
                catch(FluidCheckpointCodec.Unreadable unreadable){throw unreadable;}
                catch(IllegalArgumentException state){why="the state of island "+row.id()+" cannot be used: "+state.getMessage();}
            }
            var unit=core.topology();
            if(unit!=null&&unit.pack()==p) {
                if(!MessageDigest.isEqual(FluidCheckpointCodec.sha256(bytes,(int)unit.offset(),unit.length()),unit.sha256()))throw new IllegalArgumentException("Topology checkpoint checksum mismatch");
                var sections=FluidCheckpointCodec.topologySections(bytes,(int)unit.offset(),unit.length(),core.topologyGeneration());worldFacts=FluidCheckpointCodec.worldFacts(bytes,sections);
                // Pending recoveries read their solids as records of the topology's basis package: the model checked
                // against the package table when an island uses it, else the world's own.
                if(why==null)try{world=Optional.of(FluidCheckpointCodec.topology(bytes,sections,core.epoch(),key->{var known=byKey.get(key);return known!=null?known:models.apply(key);}));}
                catch(FluidCheckpointCodec.Unreadable unreadable){throw unreadable;}
                catch(IllegalArgumentException state){why="the topology's state cannot be used: "+state.getMessage();}
            }
        }
        // The world's basis is a fact, read at every load: compound ids in order and the fluid identity, never the package id.
        // Another basis restates the facts whatever else stops the state (a world with no islands has no package to flag it).
        var basis=WorldTopologyLedger.FluidBasis.capture(catalog);
        if(worldFacts!=null&&!worldFacts.basis().sameAs(basis)){restate=true;if(why==null)why="the fluid property data changed";}
        nextGeneration=core.nextGeneration();nextPack=core.nextPack();
        try{for(long p:backend.packs())nextPack=Math.max(nextPack,p+1);}catch(IOException ignored){}
        loaded=true;loadedPacks=core.packs().clone();
        synchronized(lock){var previous=new HashSet<Long>();for(long p:loadedPacks)previous.add(p);previousReferenced=Set.copyOf(previous);}
        if(why!=null) {
            if(worldFacts==null)throw new IllegalArgumentException("The fluid checkpoint's state cannot be used ("+why+") and it holds no topology unit to rebuild its islands from");
            var placed=new ArrayList<FluidFacts.Placed>(facts.length);for(int i=0;i<facts.length;i++)placed.add(new FluidFacts.Placed(facts[i],core.islands().get(i).committed()));
            var rebuilt=FluidFacts.rebuild(worldFacts,placed,core.epoch(),models,catalog,restate,why);
            // Nothing stored is reused: the next save writes every unit afresh.
            islands.clear();topology=null;topologySource=null;packSizes.clear();strings=new FluidCheckpointCodec.Strings();selfContained=true;
            IChemE.LOGGER.warn("fluid_checkpoint status=FACTS reason={} saved_islands={} islands={} vessels={} restated={}",why,facts.length,rebuilt.checkpoint().islands().size(),rebuilt.vessels(),rebuilt.restated());
            return new Loaded(rebuilt.checkpoint(),Optional.of(rebuilt.world()),Route.FACTS,why);
        }
        var refs=new LinkedHashMap<Long,Ref>();
        for(int i=0;i<entries.length;i++){var row=core.islands().get(i);refs.put(row.id(),new Ref(row.revision(),entries[i].snapshot().payloadGeneration(),entries[i].packageId(),row.generation(),row.unit()));}
        var checkpoint=new FluidCheckpointCodec.Checkpoint(Arrays.asList(entries));
        // Seed the dirty tracking: an island registered with the payload generation its decoded snapshot carries keeps
        // its unit where it is until something it records changes.
        islands.clear();islands.putAll(refs);topology=core.topology()==null?null:new Ref(0,0,"",core.topologyGeneration(),core.topology());topologySource=world.orElse(null);
        packSizes.clear();for(int i=0;i<core.packs().length;i++)packSizes.put(core.packs()[i],core.packBytes()[i]);
        strings=core.strings();selfContained=false;
        // The same basis under another package id: the world names the current package, and its topology unit is written
        // again at the next save (its source is the saved ledger).
        return new Loaded(checkpoint,world.map(w->w.basis().equals(basis)?w:w.withBasis(basis)),Route.EXACT,"");
    }
    /** A listed pack's bytes, its size and header checked: a missing, short or foreign pack refuses the world. */
    private byte[] pack(long p,long size) {
        byte[] bytes;
        try{bytes=backend.readPack(p);}
        catch(NoSuchFileException missing){throw new IllegalArgumentException("Fluid pack "+p+" is missing: the world's fluid data is incomplete.");}
        catch(IOException failure){throw new IllegalArgumentException("Fluid pack "+p+" cannot be read: "+failure.getMessage());}
        if(bytes.length!=size)throw new IllegalArgumentException("Fluid pack "+p+" holds "+bytes.length+" bytes but the checkpoint expects "+size+": a half-written or damaged pack.");
        var header=ByteBuffer.wrap(bytes);
        if(header.getInt()!=PACK_MAGIC)throw new IllegalArgumentException("Fluid pack "+p+" is not a fluid pack");
        int format=header.getInt();if(format!=PACK_FORMAT)throw new FluidCheckpointCodec.Unreadable("Fluid pack format "+format+" cannot be read: this build reads pack format "+PACK_FORMAT+", so a newer build wrote it");
        if(header.getLong()!=p)throw new IllegalArgumentException("Fluid pack "+p+" names another pack");
        return bytes;
    }
    /** Reads the committed core record from this store's backend and loads it. */
    public Optional<Loaded> read(Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models) throws IOException {
        var core=backend.readCore();return core==null?Optional.empty():Optional.of(load(core,models));
    }
    /**
     * An empty store after a rejected format, before any save. Retired packs survive until the replacement core is
     * committed; new pack numbers skip them, so a failed replacement cannot overwrite the previous checkpoint.
     */
    FluidCheckpointStore resetForFormatChange() {
        if(loaded||sequence!=0)throw new IllegalStateException("Only an unopened checkpoint can reset its format");
        var fresh=new FluidCheckpointStore(backend,io);
        try{for(long pack:backend.packs())fresh.nextPack=Math.max(fresh.nextPack,Math.addExact(pack,1));}
        catch(IOException failure){throw new IllegalStateException("Cannot list retired fluid packs",failure);}
        return fresh;
    }
    /** Deletes, after an accepted load, every pack the loaded core record does not reference and every temporary file
     * an interrupted write left: orphans of a crash between a pack and its core record. Returns how many. */
    public int cleanOrphans() {
        if(!loaded)return 0;
        var keep=new HashSet<Long>();for(long p:loadedPacks)keep.add(p);int deleted=0;
        try{for(long p:backend.packs())if(!keep.contains(p)){backend.deletePack(p);deleted++;}deleted+=backend.cleanTemporary();}
        catch(IOException failure){IChemE.LOGGER.warn("fluid_checkpoint status=CLEANUP_FAILED detail=orphans stay until the next save",failure);}
        return deleted;
    }
    /** A new store over the same packs and core record, as a restarted server opens them: it can load what this one
     * committed. Waits for this store's submitted commits first. */
    public FluidCheckpointStore reopen(){flush();return new FluidCheckpointStore(backend,io);}
    /** The committed core record and packs of an in-memory store. */
    FluidCheckpointCodec.Image image() {
        if(!(backend instanceof Memory memory))throw new IllegalStateException("Not an in-memory store");
        return new FluidCheckpointCodec.Image(memory.core,memory.packs);
    }
    Backend backend(){return backend;}

    // ---------------- backends ----------------

    static final class Memory implements Backend {
        final Map<Long,byte[]> packs=new TreeMap<>();CompoundTag core;
        public byte[] readPack(long pack) throws IOException{var bytes=packs.get(pack);if(bytes==null)throw new NoSuchFileException("fluid-"+pack+".pack");return bytes;}
        public byte[] readUnit(long pack,long offset,int length) throws IOException{var bytes=readPack(pack);if(offset<0||offset+length>bytes.length)throw new IOException("Unit beyond the end of pack "+pack);return Arrays.copyOfRange(bytes,(int)offset,(int)offset+length);}
        public void writePack(long pack,byte[] bytes){packs.put(pack,bytes);}
        public void writeCore(CompoundTag core){this.core=core;}
        public CompoundTag readCore(){return core;}
        public Set<Long> packs(){return Set.copyOf(packs.keySet());}
        public void deletePack(long pack){packs.remove(pack);}
        public int cleanTemporary(){return 0;}
    }
    /** Packs in {@code <data>/icheme_fluid}, the core record as the world's saved data file beside it. */
    static final class Directory implements Backend {
        final Path core,units;private final boolean stampDataVersion;
        Directory(Path dataFolder,boolean stampDataVersion){core=dataFolder.resolve(CORE_NAME);units=dataFolder.resolve(UNIT_DIRECTORY);this.stampDataVersion=stampDataVersion;}
        Path pack(long number){return units.resolve("fluid-"+number+".pack");}
        public byte[] readPack(long pack) throws IOException{return Files.readAllBytes(pack(pack));}
        public byte[] readUnit(long pack,long offset,int length) throws IOException {
            var unit=ByteBuffer.allocate(length);
            try(var channel=FileChannel.open(pack(pack),StandardOpenOption.READ)){while(unit.hasRemaining()){int read=channel.read(unit,offset+unit.position());if(read<0)throw new IOException("Unit beyond the end of pack "+pack);}}
            return unit.array();
        }
        public void writePack(long pack,byte[] bytes) throws IOException{Files.createDirectories(units);atomicWrite(pack(pack),bytes);}
        public void writeCore(CompoundTag data) throws IOException {
            // As SavedData.save(File) writes it: {data: ..., DataVersion}, gzip-compressed NBT.
            var root=new CompoundTag();root.put("data",data);if(stampDataVersion)NbtUtils.addCurrentDataVersion(root);
            var bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(root,bytes);Files.createDirectories(core.getParent());atomicWrite(core,bytes.toByteArray());
        }
        public CompoundTag readCore() throws IOException{return Files.exists(core)?NbtIo.readCompressed(core,NbtAccounter.unlimitedHeap()).getCompound("data"):null;}
        public Set<Long> packs() throws IOException {
            if(!Files.isDirectory(units))return Set.of();var found=new HashSet<Long>();
            try(var files=Files.list(units)){for(var file:(Iterable<Path>)files::iterator){var m=PACK_NAME.matcher(file.getFileName().toString());if(m.matches())found.add(Long.parseLong(m.group(1)));}}
            return found;
        }
        public void deletePack(long pack) throws IOException{Files.deleteIfExists(pack(pack));}
        public int cleanTemporary() throws IOException {
            int deleted=0;
            if(Files.isDirectory(units))try(var files=Files.list(units)){for(var file:(Iterable<Path>)files::iterator)if(file.getFileName().toString().endsWith(TEMPORARY_SUFFIX)&&Files.deleteIfExists(file))deleted++;}
            // An interrupted core record write leaves its temporary beside the saved data file.
            if(Files.isDirectory(core.getParent()))try(var files=Files.list(core.getParent())) {
                for(var file:(Iterable<Path>)files::iterator){var name=file.getFileName().toString();if(name.startsWith(CORE_NAME+".")&&name.endsWith(TEMPORARY_SUFFIX)&&Files.deleteIfExists(file))deleted++;}
            }
            return deleted;
        }
        static void atomicWrite(Path target,byte[] bytes) throws IOException {
            FluidCheckpointStore.atomicWrite(target,channel->{var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);});
        }
    }
    /** What an atomic write puts into its temporary file. */
    @FunctionalInterface public interface Content {void write(FileChannel channel) throws IOException;}
    /**
     * Writes a temporary file beside {@code target}, flushes it to disk, then renames it over the target (atomically
     * where the file system can): a reader sees the old file or the whole new one, never a part. A failure deletes the
     * temporary file and leaves the target as it was; a crash leaves at most a temporary file, which a load deletes.
     */
    public static void atomicWrite(Path target,Content content) throws IOException {
        var temporary=Files.createTempFile(target.getParent(),target.getFileName().toString()+".",TEMPORARY_SUFFIX);
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){content.write(channel);channel.force(true);}
            try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        } catch(IOException|RuntimeException failure) {
            try{Files.deleteIfExists(temporary);}catch(IOException second){failure.addSuppressed(second);}
            throw failure;
        }
    }
}
