package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import net.minecraft.nbt.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoint format 11 (format 5, F3's format 4 less its kind column, plus the pipe ports and the compressor control of
 * the phase-ports batch in 6, the end flows in 7, without the ledger of transfers and modules in 8, and without the
 * liquid compressibility in 9, the movers' efficiency in 10 and the velocity-limit modes in 11, whose solids name their records, crystals among them): a restored
 * certificate continues exactly (the full round trip is FluidCheckpointStoreTest's, through the files); every index
 * and certificate field is validated; certificates are discarded, keeping inventory, when certificates are off or their signature
 * changed; a certified island's unit is reused in place, never re-encoded, while it stays certified; a thousand
 * certified islands load without a solve burst; and a save taken during a property hold restarts awake from the held
 * state. Older formats are refused with the instruction to create a fresh world; nothing reads them.
 */
class FluidCheckpointFormatTest extends CertifyingRig.Islands {
    private static long[] column(CompoundTag core,String name){return core.getCompound("Islands").getLongArray(name);}
    private static long index(CompoundTag core,long island,String name){return column(core,name)[CheckpointImages.row(core,island)];}
    private static void set(CompoundTag core,long island,String name,long value){column(core,name)[CheckpointImages.row(core,island)]=value;}
    private static FluidCheckpointCodec.Image copy(FluidCheckpointCodec.Image image){return new FluidCheckpointCodec.Image(image.core().copy(),image.packs());}
    private static void assertInventories(PassiveNetwork expected,PassiveNetwork actual,String what) {
        assertEquals(expected.reservoirs().size(),actual.reservoirs().size(),what);
        for(int n=0;n<expected.reservoirs().size();n++) {
            var a=expected.reservoirs().get(n);var b=actual.reservoirs().get(n);
            assertEquals(a.id(),b.id(),what);assertEquals(a.kind(),b.kind(),what);
            assertEquals(a.inventory(),b.inventory(),what+": node "+a.id()+" inventory, bit for bit");
            assertEquals(a.state().temperature(),b.state().temperature(),what+": node "+a.id()+" temperature");
            assertEquals(a.state().pressure(),b.state().pressure(),what+": node "+a.id()+" pressure");
        }
    }
    /** A certified island's snapshot with its certificate base and interval start graphs replaced. */
    private static FluidCheckpointCodec.IslandEntry withCertificateGraphs(FluidCheckpointCodec.IslandEntry entry,java.util.function.UnaryOperator<PassiveNetwork> base,java.util.function.UnaryOperator<PassiveNetwork> before) {
        var s=entry.snapshot();var c=s.certificate().orElseThrow();var saved=c.saved().orElseThrow();var interval=saved.interval();var r=interval.result();
        var result=new PassiveIntervalSolver.Result(base.apply(r.graph()),r.advancedSeconds(),r.averageMassFlows(),r.acceptedSubsteps(),r.rejectedSubsteps(),r.pumpWorkJoule(),r.boundaries(),r.rejectionReasons(),r.endpointModes(),r.endpointHeads(),r.endpointFlows(),r.pipeTransfers());
        var edited=new IslandCertificate.Saved(saved.sinceTick(),saved.horizonTick(),new IslandCertificate.Interval(interval.startTick(),interval.endTick(),before.apply(interval.before()),result),saved.signature());
        var certified=new IslandCoordinator.Certified(c.drift(),c.sinceTick(),c.baseTick(),c.horizonTick(),c.largestFlow(),Optional.of(edited));
        return new FluidCheckpointCodec.IslandEntry(entry.dimension(),entry.packageId(),new IslandCoordinator.Snapshot(s.id(),s.revision(),s.graph(),s.clock(),Optional.of(result),s.status(),s.fences(),Optional.of(certified)));
    }
    private static PassiveNetwork withNodeEnergy(PassiveNetwork graph,double energy) {
        var nodes=new ArrayList<>(graph.reservoirs());
        for(int n=0;n<nodes.size();n++){var node=nodes.get(n);if(node.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;var i=node.inventory();
            nodes.set(n,new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),new PassiveNetwork.Inventory(i.volume(),i.moles(),energy,i.solids())));break;}
        return new PassiveNetwork(nodes,graph.pipes());
    }
    private static PassiveNetwork withLongerSections(PassiveNetwork graph) {
        return new PassiveNetwork(graph.reservoirs(),graph.pipes().stream().map(p->new PassiveNetwork.Pipe(p.id(),p.first(),p.second(),
                p.sections().stream().map(g->new PipeResistance.Geometry(g.length()+1,g.diameter(),g.roughness(),g.minorLoss())).toList(),p.control(),p.blockedDirections(),p.filter())).toList());
    }

    // ---------------- round trips ----------------

    /**
     * A certified island is saved at its base and loaded at its committed tick, materialised without a solve; restored,
     * it keeps its since tick and holds only its horizon, and it continues bit for bit as if it had never been saved.
     */
    @Test void aRestoredCertificateContinuesWithoutASolveExactlyAsIfNeverSaved() {
        var original=rig(CertificatePolicy.defaults());
        original.register(1,deadHeadedLine(100,0));original.register(2,closedPair(200,4));
        original.runUntilCertified(List.of(1L,2L),2_000);original.run(1_234-original.epoch[0]);
        var image=FluidCheckpointCodec.encode(original.checkpoint(),1_234,key->key.on(model));
        // The unit holds the certificate base; the load materialises it to the saved committed tick.
        var base=original.stored(2).certificate().orElseThrow().saved().orElseThrow().interval().result().graph();
        var now=original.read(2).graph();
        assertTrue(java.util.stream.IntStream.range(0,now.reservoirs().size()).allMatch(n->now.reservoirs().get(n).inventory().equals(base.reservoirs().get(n).inventory())),"the closed pair rests exactly: drift 0, the replay is the identity");
        var decoded=FluidCheckpointCodec.decode(image,key->key.on(model));
        assertInventories(base,decoded.islands().get(1).snapshot().certificate().orElseThrow().saved().orElseThrow().interval().result().graph(),"the unit records the base");
        assertInventories(original.read(2).graph(),decoded.islands().get(1).snapshot().graph(),"materialised at load");
        countFromHere();
        var restored=rig(CertificatePolicy.defaults(),model,1_234);restored.load(decoded);
        assertEquals(2,counter("certificatesRestored"));assertEquals(0,counter("certificatesDiscarded"));
        for(long id:List.of(1L,2L)) {
            assertFalse(restored.coordinator.retainsSolver(id),"a restored certified island keeps no solver caches");
            assertEquals(original.stored(id).certificate().orElseThrow().sinceTick(),restored.stored(id).certificate().orElseThrow().sinceTick());
            assertEquals(original.stored(id).status(),restored.stored(id).status(),"the status line continues from the saved since tick");
        }
        assertEquals(restored.coordinator.epochTick(2,original.stored(2).certificate().orElseThrow().horizonTick()),restored.coordinator.nextDue(),"only the STEADY horizon is scheduled");
        FluidRuntimeDiagnostics.reset();
        original.run(5_000);restored.run(5_000);
        assertEquals(0,restored.solves);assertEquals(0,counter("solvesDispatched"),"no solve after the load");
        for(long id:List.of(1L,2L)) {
            var a=original.read(id);var b=restored.read(id);
            assertEquals(a.clock(),b.clock());assertInventories(a.graph(),b.graph(),"island "+id+" 5000 ticks after the load");
            assertEquals(a.status(),b.status());
        }
    }

    // ---------------- validation ----------------

    /** Every index and certificate field is validated on load, and an invalid one refuses the load with a clear reason. */
    @Test void everyInvalidCertificateFieldIsRefused() {
        var rig=rig(CertificatePolicy.defaults());rig.register(2,closedPair(200,4));
        rig.runUntilCertified(List.of(2L),2_000);rig.run(1_234-rig.epoch[0]);
        var image=FluidCheckpointCodec.encode(rig.checkpoint(),1_234,key->key.on(model));var core=image.core();
        long base=index(core,2,"Base"),committed=index(core,2,"Committed"),horizon=index(core,2,"Horizon"),start=index(core,2,"Start");
        assertTrue(base<committed&&committed<horizon);
        var strings=(ListTag)core.get("Strings");int policy=-1;for(int i=0;i<strings.size();i++)if(strings.getString(i).startsWith("restDetection="))policy=i;
        assertTrue(policy>=0,"the certificate policy is in the string table");int policyIndex=policy;
        record Case(String expected,Consumer<CompoundTag> edit) {}
        var cases=List.<Case>of(
                new Case("later than its committed tick",t->set(t,2,"Committed",base-1)),
                new Case("earlier than its committed tick",t->set(t,2,"Horizon",committed-1)),
                new Case("invalid certificate base",t->set(t,2,"Base",-2)),
                new Case("carries a certificate",t->set(t,2,"Base",FluidCheckpointCodec.AWAKE)),
                new Case("is empty or negative",t->set(t,2,"Start",base)),
                new Case("disagrees with its result",t->set(t,2,"Start",start-20)),
                new Case("since <= base",t->set(t,2,"Since",base+1)),
                new Case("since <= base",t->set(t,2,"Since",-1)),
                new Case("field Horizon",t->t.getCompound("Islands").remove("Horizon")),
                new Case("Invalid certificate signature",t->((ListTag)t.get("Strings")).set(policyIndex,StringTag.valueOf(""))),
                new Case("ahead of the checkpoint's world epoch",t->t.putLong("Epoch",1_233)),
                new Case("lies in pack 7",t->set(t,2,"Pack",7)),
                new Case("does not lie inside pack",t->set(t,2,"Offset",1L<<40)),
                new Case("the index expects island 2 revision 5",t->set(t,2,"Revision",5)),
                new Case("Invalid or duplicate unit generation",t->set(t,2,"Generation",0)),
                new Case("is not after its last pack",t->t.putLong("NextPack",1)));
        for(var c:cases) {
            var edited=copy(image);c.edit().accept(edited.core());
            assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(edited,key->key.on(model)),c.expected()+": an unsealed edit is refused as corrupt");
            // A removed field is refused before any checksum can be computed; everything else is resealed to reach its own check.
            try{CheckpointImages.reseal(edited.core());}catch(IllegalArgumentException missing){assertTrue(missing.getMessage().contains(c.expected()),missing.getMessage());}
            var refusal=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(edited,key->key.on(model)));
            assertTrue(refusal.getMessage().contains(c.expected()),c.expected()+" / got: "+refusal.getMessage());
        }
        // Nonfinite deltas: the energy between the interval's start and its base overflows, so the island's facts (its
        // certificate's drift) cannot even be written.
        assertTrue(assertThrows(IllegalArgumentException.class,()->CheckpointImages.withIsland(image,2,key->key.on(model),entry->withCertificateGraphs(entry,g->withNodeEnergy(g,1.7e308),g->withNodeEnergy(g,-1.7e308))))
                .getMessage().contains("nonfinite energy delta"));
        // A fence before the committed tick: a fence at the committed tick, then the committed tick moved past it.
        var event=UUID.randomUUID();
        var fenced=CheckpointImages.withIsland(image,2,key->key.on(model),entry->{var s=entry.snapshot();return new FluidCheckpointCodec.IslandEntry(entry.dimension(),entry.packageId(),
                new IslandCoordinator.Snapshot(s.id(),s.revision(),s.graph(),s.clock(),s.lastResult(),s.status(),Map.of(event,committed),s.certificate()));});
        assertEquals(1,FluidCheckpointCodec.decode(fenced,key->key.on(model)).islands().getFirst().snapshot().fences().size(),"the fence itself reads back");
        set(fenced.core(),2,"Committed",committed+1);CheckpointImages.reseal(fenced.core());
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(fenced,key->key.on(model))).getMessage().contains("precedes the committed tick"));
        // A unit whose own bytes are malformed: truncated, or with trailing bytes, each resealed so only the unit is wrong.
        var bytes=CheckpointImages.unitBytes(image,2);
        var truncated=CheckpointImages.withUnit(image,2,Arrays.copyOf(bytes,bytes.length-1));
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(truncated,key->key.on(model))).getMessage().contains("Invalid saved unit of island 2"));
        var trailing=CheckpointImages.withUnit(image,2,Arrays.copyOf(bytes,bytes.length+3));
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(trailing,key->key.on(model))).getMessage().contains("trailing bytes"));
        // The header's envelope byte is frozen: a unit of another envelope is not this checkpoint's, so the world is refused.
        var otherFormat=bytes.clone();otherFormat[4]=1;
        var refused=assertThrows(FluidCheckpointCodec.Unreadable.class,()->FluidCheckpointCodec.decode(CheckpointImages.withUnit(image,2,otherFormat),key->key.on(model))).getMessage();
        assertTrue(refused.contains("has envelope 1")&&refused.contains("reads envelope "+FluidCheckpointCodec.UNIT_ENVELOPE),refused);
    }

    /** restDetection=false discards every saved certificate; the island keeps its materialised inventory and solves again. */
    @Test void restDetectionOffDiscardsEverySavedCertificateAndKeepsTheInventory() {
        var rig=rig(CertificatePolicy.defaults());rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));
        rig.runUntilCertified(List.of(1L,2L),2_000);rig.run(1_234-rig.epoch[0]);
        var decoded=FluidCheckpointCodec.decode(FluidCheckpointCodec.encode(rig.checkpoint(),1_234,key->key.on(model)),key->key.on(model));
        countFromHere();
        var off=rig(CertificatePolicy.disabled(),model,1_234);off.load(decoded);
        assertEquals(0,counter("certificatesRestored"));assertEquals(2,counter("certificatesDiscarded"));
        for(int i=0;i<2;i++) {
            var saved=decoded.islands().get(i).snapshot();var now=off.stored(saved.id());
            assertTrue(now.certificate().isEmpty());assertTrue(off.coordinator.retainsSolver(saved.id()));
            assertTrue(now.status().startsWith("WAITING: saved certificate discarded: certificates are off"),now.status());
            assertInventories(rig.read(saved.id()).graph(),now.graph(),"island "+saved.id()+" keeps the inventory it was saved with");
            assertEquals(saved.clock(),now.clock());
            assertNotEquals(saved.payloadGeneration(),now.payloadGeneration(),"a discarded certificate changes what the unit records");
        }
        off.run(200);assertEquals(4,off.solves,"both islands solve again, one interval each per cadence");
    }

    /** A saved certificate whose signature is not its island's current one is discarded, and the inventory kept. */
    @Test void aSignatureMismatchDiscardsTheCertificateAndKeepsTheInventory() {
        var rig=rig(CertificatePolicy.defaults());rig.register(2,closedPair(200,4));
        rig.runUntilCertified(List.of(2L),2_000);rig.run(1_234-rig.epoch[0]);
        var expected=rig.read(2).graph();
        var image=FluidCheckpointCodec.encode(rig.checkpoint(),1_234,key->key.on(model));
        var defaults=CertificatePolicy.defaults();
        record Case(String reason,FluidThermodynamics model,CertificatePolicy policy,FluidCheckpointCodec.Image image) {}
        // A graph-identity change: every run of pipe one metre longer, in the base and in the interval's start alike.
        var longer=CheckpointImages.withIsland(image,2,key->key.on(model),entry->withCertificateGraphs(entry,FluidCheckpointFormatTest::withLongerSections,FluidCheckpointFormatTest::withLongerSections));
        var cases=List.of(
                new Case("property revision",FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.NETWORK_PACKAGE,10, FluidThermodynamics.DEFAULT_TRACE_CUTOFF_MOLE_FRACTION),defaults,image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-8,1e-6,17_280,2,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-5,17_280,2,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-6,17_279,2,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-6,17_280,3,0),image),
                new Case("certificate policy",model,new CertificatePolicy(true,1e-7,1e-6,17_280,2,60),image),
                new Case("graph identity",model,defaults,longer));
        assertNotEquals(model.revision(),cases.getFirst().model().revision());
        assertEquals(model.thermodynamicRevision(),cases.getFirst().model().thermodynamicRevision(),"a velocity limit leaves the saved inventory readable");
        for(var c:cases) {
            var decoded=FluidCheckpointCodec.decode(c.image(),key->c.model());
            countFromHere();
            var loaded=rig(c.policy(),c.model(),1_234);loaded.load(decoded);
            assertEquals(1,counter("certificatesDiscarded"),c.reason());assertEquals(0,counter("certificatesRestored"),c.reason());
            var now=loaded.stored(2);
            assertTrue(now.certificate().isEmpty(),c.reason());assertTrue(loaded.coordinator.retainsSolver(2));
            assertTrue(now.status().contains("its "+c.reason()),c.reason()+": "+now.status());
            assertTrue(loaded.coordinator.certificationRefusal(2).startsWith("saved certificate discarded"));
            for(int n=0;n<expected.reservoirs().size();n++)assertEquals(expected.reservoirs().get(n).inventory(),now.graph().reservoirs().get(n).inventory(),c.reason()+": inventory kept");
            loaded.run(100);assertEquals(1,loaded.solves,c.reason()+": the island solves its next interval");
        }
    }

    // ---------------- the dirty rule ----------------

    /**
     * A certified island's unit stays where it is, never re-encoded, on every save while it stays certified; a solve, a
     * wake at the horizon and a new certificate make the next save write it. A save after no change writes no unit and
     * no pack; after one island's solve it writes exactly that island's unit. (Tests run with the scheduler's
     * self-verification on, so every reused unit is also compared with a fresh encoding of its island.)
     */
    @Test void aCertifiedPayloadIsCopiedNotReencodedUntilASolveOrACertificate() {
        assertTrue(Boolean.getBoolean("icheme.fluid.scheduler.verify"),"the reused units are verified against a fresh encoding");
        // A 25 s recheck gives the resting islands a finite horizon (the closed pair rests exactly since every job starts
        // from its committed interval, review 8.9 (d)), so the test can run to it.
        var rig=rig(new CertificatePolicy(true,1e-9,1e-6,5,2,25));
        rig.register(1,deadHeadedLine(100,0));rig.register(2,closedPair(200,4));rig.register(3,slowFill(300,8));
        rig.runUntilCertified(List.of(1L,2L),2_000);
        // The world ledger, advanced with the islands' epoch as the world advances it; captured as the world captures it.
        var ledger=new WorldTopologyLedger(world(0));
        java.util.function.Supplier<WorldTopologyLedger.Snapshot> topology=()->{while(ledger.onlineTick()<rig.epoch[0])ledger.tick();return ledger.snapshot();};
        var data=new FluidSavedData(rig.checkpoint(),topology.get(),key->key.on(model));
        data.bindCapture(()->new FluidSavedData.Capture(rig.checkpoint(),topology.get()));
        countFromHere();
        data.save(new CompoundTag(),null);var first=data.lastSave();
        assertEquals(3,first.payloadsEncoded());assertEquals(0,first.payloadsReused(),"the first save encodes every island");assertTrue(first.topologyEncoded());assertTrue(first.stats().full());
        var immediate=data.save(new CompoundTag(),null);var second=data.lastSave();
        assertEquals(0,second.payloadsEncoded());assertEquals(3,second.payloadsReused(),"nothing changed: every unit stays");
        assertFalse(second.topologyEncoded(),"an unchanged world ledger keeps its unit");assertEquals(0,second.stats().pack(),"and no pack is written");
        long horizon=rig.stored(2).certificate().orElseThrow().horizonTick();
        rig.run(100);
        data.save(new CompoundTag(),null);var third=data.lastSave();
        assertFalse(third.topologyEncoded(),"the ledger is unchanged at another online tick");
        assertEquals(1,third.payloadsEncoded(),"only the awake island solved");assertEquals(2,third.payloadsReused(),"the certified islands were only materialised");
        assertEquals(third.stats().encodedBytes()+third.stats().copiedBytes()+FluidCheckpointStore.PACK_HEADER,third.stats().packBytes(),"the new pack holds that one unit");
        assertTrue(horizon>rig.epoch[0],"the horizon lies ahead");
        rig.run(horizon-rig.epoch[0]+100);
        assertTrue(rig.stored(2).clock().committedTick()>horizon,"the closed pair woke at its horizon and solved its revalidating interval");
        data.save(new CompoundTag(),null);var fourth=data.lastSave();
        // Under the 25 s recheck the dead-headed line reaches its horizon too and revalidates with the pair.
        assertEquals(3,fourth.payloadsEncoded(),"the two revalidated islands and the awake island are encoded");assertEquals(0,fourth.payloadsReused(),"no certified island stayed");
        assertEquals(7,counter("payloadsEncoded"));assertEquals(5,counter("payloadsReused"));
        assertTrue(first.payloadBytes()>0&&first.payloadBytes()==second.payloadBytes());
        assertNotNull(immediate.get("Islands"));
    }

    // ---------------- load without a solve burst ----------------

    /** Plan 5 item 7: a thousand certified islands load and dispatch no solve until their horizon, then revalidate once. */
    @Test void aThousandCertifiedIslandsLoadWithoutASolveBurst() {
        // A 60 s recheck gives each resting tank a horizon, so the test can see the first solve come exactly there.
        var policy=new CertificatePolicy(true,1e-9,1e-6,17_280,2,60);
        var original=rig(policy);for(long id=1;id<=1_000;id++)original.register(id,loneTank(100_000+id));
        original.run(300);
        var checkpoint=original.checkpoint();
        assertTrue(checkpoint.islands().stream().allMatch(i->i.snapshot().certificate().map(c->c.drift()==0&&c.horizonTick()==1_400).orElse(false)),"every tank rests with its horizon at 1400");
        long started=System.nanoTime();var image=FluidCheckpointCodec.encode(checkpoint,300,key->key.on(model));long encoded=System.nanoTime();
        var decoded=FluidCheckpointCodec.decode(image,key->key.on(model));long decodedAt=System.nanoTime();
        countFromHere();
        var loaded=rig(policy,model,300);loaded.load(decoded);long registered=System.nanoTime();
        assertEquals(1_000,counter("certificatesRestored"));assertEquals(0,counter("certificatesDiscarded"));
        assertEquals(0,loaded.coordinator.readyCount(),"no island is ready at the load");
        System.out.printf(Locale.ROOT,"1000 certified islands: encode %.1f ms, decode %.1f ms, register %.1f ms, %d unit bytes, core record %d bytes%n",(encoded-started)/1e6,(decodedAt-encoded)/1e6,(registered-decodedAt)/1e6,
                Arrays.stream(image.core().getCompound("Islands").getIntArray("Length")).asLongStream().sum(),image.core().sizeInBytes());
        FluidRuntimeDiagnostics.reset();
        loaded.run(1_099);
        assertEquals(1_399,loaded.epoch[0]);assertEquals(0,loaded.solves);
        for(var name:List.of("solvesDispatched","readinessPumps","deadlinesFired","materialisations","islandVisits"))assertEquals(0,counter(name),name+" before the horizon");
        loaded.tick();
        assertEquals(1_000,counter("deadlinesFired"),"every horizon falls at 1400");assertEquals(0,loaded.solves);
        // Each woken tank solves one revalidating interval, [1400, 1500], at most 64 dispatches a tick.
        loaded.run(120);
        assertEquals(1_000,loaded.solves,"one revalidating solve per island after its horizon, none before");
        for(long id=1;id<=1_000;id++)assertTrue(loaded.stored(id).certificate().isPresent(),"island "+id+" renewed");
    }

    // ---------------- property hold ----------------

    /**
     * Plan 3.6 with a restart: a save taken during a hold keeps committed time at the hold tick and the online time
     * accrued since, and no certificate; the restarted island solves its debt from the held state and certifies again
     * only after restConfirmIntervals fresh intervals.
     */
    @Test void holdSaveAndRestartKeepsCommittedFrozenAndDebtAndRequalifiesOverTheConfirmCount() {
        for(int confirm:new int[]{2,3}) {
            var policy=new CertificatePolicy(true,1e-9,1e-6,17_280,confirm,0);
            var rig=rig(policy);rig.register(1,deadHeadedLine(100,0));
            rig.runUntilCertified(List.of(1L),2_000);rig.run(537);
            rig.coordinator.suspendForPropertyChange("HELD: test property change");
            long hold=rig.epoch[0];rig.run(1_000);
            var during=rig.read(1);
            assertTrue(during.certificate().isPresent()&&during.certificate().orElseThrow().saved().isEmpty(),"a held certificate is not saved");
            var image=FluidCheckpointCodec.encode(rig.checkpoint(),rig.epoch[0],key->key.on(model));
            assertEquals(FluidCheckpointCodec.AWAKE,index(image.core(),1,"Base"));
            var decoded=FluidCheckpointCodec.decode(image,key->key.on(model)).islands().getFirst().snapshot();
            assertEquals(hold,decoded.clock().committedTick(),"committed time stays frozen at the hold");
            assertEquals(hold+1_000,decoded.clock().onlineTick(),"the online time accrued during the hold is kept as debt");
            assertEquals(during.status(),decoded.status(),"the hold's status line is kept");
            assertTrue(decoded.certificate().isEmpty());assertInventories(during.graph(),decoded.graph(),"held inventory");
            var restarted=rig(policy,model,rig.epoch[0]);restarted.load(new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,decoded))));
            assertTrue(restarted.coordinator.retainsSolver(1));assertEquals(1,restarted.coordinator.readyCount(),"awake with debt, ready at once");
            restarted.coordinator.pump();restarted.drain();
            var again=restarted.stored(1).certificate().orElseThrow();
            assertEquals(hold+100L*confirm,again.baseTick(),"requalified over "+confirm+" intervals after the restart");
            assertEquals(hold+100L*confirm,again.sinceTick());assertEquals(confirm,restarted.solves);
            var now=restarted.read(1).graph();
            for(int n=0;n<now.reservoirs().size();n++)assertEquals(during.graph().reservoirs().get(n).inventory(),now.reservoirs().get(n).inventory(),"rest is the identity");
        }
    }

    // ---------------- other formats ----------------

    /** Formats saved before the envelope (state format 19 and older: 3 and before, 7 with its ledger, ... 19) are a
     * format mismatch, which a world open answers with its one reset (owner decision D4), and nothing in the tag is
     * changed; a newer build's pack format is unreadable, so the world is refused. */
    @Test void preEnvelopeFormatsAreAMismatchAndANewerPackIsUnreadable() {
        for(int format:new int[]{1,2,3,7,8,9,10,11,19}) {
            var old=new CompoundTag();old.putInt("FluidFormat",format);old.putLong("Epoch",40);
            byte[] json=("{\"version\":"+format+",\"islands\":[],\"transfers\":{\"revision\":0,\"buffers\":[],\"pending\":[]}}").getBytes(StandardCharsets.UTF_8);
            old.putByteArray(format==3?"Ledger":"Checkpoint",json);old.put("Islands",new ListTag());old.putByteArray("SHA256",new byte[32]);var before=old.copy();
            var refusal=assertThrows(FluidCheckpointCodec.FormatMismatchException.class,()->FluidSavedData.load(old,key->key.on(model),FluidCheckpointStore.memory()));
            assertTrue(refusal.getMessage().contains("format "+format)&&refusal.getMessage().contains("has no envelope"),refusal.getMessage());
            assertEquals(before,old,"a refused save is left exactly as it was");
        }
        var data=new FluidSavedData(new FluidCheckpointCodec.Checkpoint(List.of()),world(40),key->{throw new AssertionError("no model needed");});
        var current=data.save(new CompoundTag(),null);var packs=Map.copyOf(((FluidCheckpointStore.Memory)data.store().backend()).packs);
        assertTrue(FluidSavedData.load(current,key->{throw new AssertionError();},data.store().reopen()).world().isPresent());
        // The topology is saved without its online tick: the checkpoint's epoch is the one clock of the world.
        var moved=current.copy();moved.putLong("Epoch",41);CheckpointImages.reseal(moved);
        assertEquals(41,FluidSavedData.load(moved,key->{throw new AssertionError();},FluidCheckpointStore.memory(new FluidCheckpointCodec.Image(moved,packs))).world().orElseThrow().onlineTick());
        var unsigned=current.copy();unsigned.remove("SHA256");
        assertThrows(IllegalArgumentException.class,()->FluidSavedData.load(unsigned,key->{throw new AssertionError();},FluidCheckpointStore.memory(new FluidCheckpointCodec.Image(unsigned,packs))));
        // A pack of a later pack format: a newer build wrote it.
        var otherPacks=new HashMap<>(packs);var pack=otherPacks.get(1L).clone();pack[7]=2;otherPacks.put(1L,pack);
        var refusal=assertThrows(FluidCheckpointCodec.Unreadable.class,()->FluidSavedData.load(current,key->{throw new AssertionError();},FluidCheckpointStore.memory(new FluidCheckpointCodec.Image(current,otherPacks)))).getMessage();
        assertTrue(refusal.contains("pack format 2")&&refusal.contains("newer build"),refusal);
    }
}
