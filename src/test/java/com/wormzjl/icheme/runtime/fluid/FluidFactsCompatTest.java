package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import net.minecraft.nbt.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Backward compatibility of the fluid checkpoint (AGENTS.md, "Saves and data formats"; batch 2026-10-26-backward-compat):
 * a world whose state this build cannot use is rebuilt from its facts with every vessel's contents, every filter's cake,
 * the registry and the ledger exactly as saved; a changed property basis restates the contents and books the energy; a
 * facts section this build cannot read refuses the world; and every corpus image under {@code compat/fluid/} still
 * rebuilds to the totals it was written with.
 */
class FluidFactsCompatTest {
    private static final MaterialCatalog CATALOG=EventPathWorld.CATALOG;
    private static final String PACKAGE=FluidMaterialCatalog.networkPackage(CATALOG);
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    /**
     * Two vessels filled from a 3 bar, 340 K nitrogen generator through a filtered line, the generator then removed: a
     * closed line of two charged vessels, still exchanging, at its 600th tick.
     */
    private EventPathWorld world() {
        var world=new EventPathWorld();
        long generator=world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,340,300000,FluidDeviceSpec.nitrogen(CATALOG).composition()),.05);
        for(int x=1;x<=3;x++)world.place(x,64,0,Kind.PIPE,Direction.EAST,.05);
        long first=world.place(4,64,0,Kind.RESERVOIR,Direction.EAST);
        for(int x=5;x<=6;x++)world.place(x,64,0,Kind.PIPE,Direction.EAST,.05);
        world.place(7,64,0,Kind.RESERVOIR,Direction.EAST);
        // Facts schema 2: a pipe laid beside the line with its joints along the line, so it does not join it.
        world.placeLaid(2,64,1,Direction.EAST,.05,1<<Direction.WEST.ordinal()|1<<Direction.EAST.ordinal());
        // And a rack lane on positions 2 and 3 of a rack facing east beside the second vessel's line: the lane field of a
        // position, the width and the points it meets its neighbours at.
        int along=1<<Direction.WEST.ordinal()|1<<Direction.EAST.ordinal();
        long points=RackGeometry.points(new RackGeometry.Rack(5,RackGeometry.STRAIGHT),new RackGeometry.Lane(2,2,along,along,PipeSizes.code(PipeSizes.outside(.1))));
        world.placeLane(5,64,1,3,2,points,Direction.EAST,.1,along);
        world.runTo(20);assertTrue(world.registry.installPipeFilter(generator+2,Direction.EAST));world.runTo(400);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(generator,null)),world.ledger.nextIdentity(),null);world.runTo(600);
        world.assertKeptUp("the saved line");
        var stock=contents(new FluidCheckpointCodec.Checkpoint(world.coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry(EventPathWorld.DIM,PACKAGE,s)).toList()));
        assertTrue(Arrays.stream(((PassiveNetwork.Inventory)stock.get(first)).moles()).sum()>10,"the generator charged the vessels: "+stock.get(first));
        return world;
    }
    private EventPathWorld corpusWorld(){
        var world=world();var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        world.place(20,64,0,Kind.RESERVOIR,Direction.EAST,new FluidDeviceSpec(g.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(CATALOG).composition(),SlurryFeed.NONE,g.height(),false,g),.05);
        long valve=world.place(22,64,0,Kind.VALVE,Direction.EAST);
        var r=world.registry.registrations().get(valve);var d=r.device();
        var control=new FlowControl.ControlValve(FlowControl.ValveSource.VOLUME_FLOW,200000,1,.02,new UUID(1,2),1);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(valve,new WorldTopologyLedger.Registration(d.withControl(control),r.spec(),r.revision()+1))),world.ledger.nextIdentity(),null);
        world.runTo(700);return world;
    }
    private FluidCheckpointCodec.Image image(EventPathWorld world) {
        var checkpoint=new FluidCheckpointCodec.Checkpoint(world.coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry(EventPathWorld.DIM,PACKAGE,s)).toList());
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.of(world.ledger.snapshot()),world.ledger.onlineTick(),key->model));
        return store.image();
    }
    private static FluidCheckpointCodec.Image edited(FluidCheckpointCodec.Image image,java.util.function.Consumer<CompoundTag> edit) {
        var core=image.core().copy();edit.accept(core);CheckpointImages.reseal(core);return new FluidCheckpointCodec.Image(core,image.packs());
    }
    private static FluidCheckpointStore.Loaded load(FluidCheckpointCodec.Image image,java.util.function.Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,MaterialCatalog catalog) {
        return FluidCheckpointStore.memory(image).load(image.core(),models,catalog);
    }
    /** Every vessel's inventory and every filter's cake, by device identity. */
    private static Map<Long,Object> contents(FluidCheckpointCodec.Checkpoint checkpoint) {
        var out=new TreeMap<Long,Object>();
        for(var entry:checkpoint.islands()) {
            var graph=entry.snapshot().graph();
            for(var n:graph.reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)assertNull(out.put(n.id(),n.inventory()),"vessel "+n.id()+" in two islands");
            for(var p:graph.pipes())if(p.filter()!=null)assertNull(out.put(p.id()-Long.MIN_VALUE,p.filter()),"filter "+p.id()+" in two islands");
        }
        return out;
    }
    private static double[] moles(FluidCheckpointCodec.Checkpoint checkpoint,int components) {
        var total=new double[components];
        for(var entry:checkpoint.islands())for(var n:entry.snapshot().graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)for(int c=0;c<components;c++)total[c]+=n.inventory().moles()[c];
        return total;
    }

    @Test void anotherStateFormatRebuildsTheWorldFromItsFactsWithEveryVesselExactlyAsSaved() {
        var world=world();var image=image(world);var saved=world.ledger.snapshot();
        var exact=load(image,key->model,CATALOG);
        assertEquals(FluidCheckpointStore.Route.EXACT,exact.route(),"this build's own checkpoint loads exactly");
        var rebuilt=load(edited(image,core->core.putInt("StateFormat",FluidCheckpointCodec.VERSION+1)),key->model,CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,rebuilt.route());assertTrue(rebuilt.reason().contains("state format "+(FluidCheckpointCodec.VERSION+1)),rebuilt.reason());
        assertEquals(contents(exact.checkpoint()),contents(rebuilt.checkpoint()),"every vessel's inventory and every filter's cake, bit for bit");
        var ledger=rebuilt.world().orElseThrow();
        assertEquals(saved.active(),ledger.active(),"the registry: devices, settings, specs and revisions");
        assertTrue(ledger.events().isEmpty(),"pending events are state: dropped (owner decision D5)");
        assertArrayEquals(saved.constructed().moles(),ledger.constructed().moles());assertEquals(saved.constructed().totalEnergy(),ledger.constructed().totalEnergy());
        assertArrayEquals(saved.destroyed().moles(),ledger.destroyed().moles());assertEquals(saved.basis(),ledger.basis());
        assertEquals(WorldTopologyLedger.Restated.NONE,ledger.restated(),"nothing restated: the basis did not change");
        long epoch=FluidCheckpointCodec.epoch(image.core());assertEquals(epoch,ledger.onlineTick());
        assertEquals(saved.nextIdentity()+rebuilt.checkpoint().islands().size(),ledger.nextIdentity(),"rebuilt islands take new identities from the ledger's counter");
        for(var entry:rebuilt.checkpoint().islands()) {
            var s=entry.snapshot();
            assertTrue(s.status().startsWith("WAITING: rebuilt from saved facts (state format"),s.status());
            assertEquals(new IslandClock.Snapshot(epoch,epoch,0,FluidFacts.REBUILT_CADENCE),s.clock(),"awake at the epoch, no debt");
            assertTrue(s.certificate().isEmpty()&&s.lastResult().isEmpty()&&s.fences().isEmpty());
        }
        // The rebuilt world runs as a loaded one does, its stock conserved, and saves to an exact checkpoint again.
        var reloaded=new EventPathWorld(1,ledger,rebuilt.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList());
        reloaded.runTo(epoch+300);reloaded.assertKeptUp("the rebuilt line");
        var before=moles(rebuilt.checkpoint(),model.componentCount());var after=moles(new FluidCheckpointCodec.Checkpoint(reloaded.coordinator.snapshots().stream()
                .map(s->new FluidCheckpointCodec.IslandEntry(EventPathWorld.DIM,PACKAGE,s)).toList()),model.componentCount());
        for(int c=0;c<before.length;c++)assertEquals(before[c],after[c],1e-9*Math.max(1,Math.abs(before[c])),"component "+c+" conserved by the rebuilt line");
        var again=image(reloaded);assertEquals(FluidCheckpointStore.Route.EXACT,load(again,key->model,CATALOG).route());
    }

    /**
     * A changed property basis (here the energy reference's revision) restates every vessel: its moles, volume and
     * temperature are kept, the current data gives its energy and pressure, and the ledger books the energy difference.
     */
    @Test void aChangedBasisRestatesEveryVesselKeepingItsAmountsAndTemperatureAndBooksTheEnergy() {
        var world=world();var image=image(world);var exact=load(image,key->model,CATALOG);
        var changed=edited(image,core->{var entry=((ListTag)core.get("Packages")).getCompound(0);entry.putString("EnergyRevision",entry.getString("EnergyRevision")+"-retired");});
        var restated=load(changed,key->model,CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,restated.route());assertTrue(restated.reason().contains("property data of package "+PACKAGE+" changed"),restated.reason());
        var before=new TreeMap<Long,PassiveNetwork.Reservoir>();var after=new TreeMap<Long,PassiveNetwork.Reservoir>();
        for(var e:exact.checkpoint().islands())for(var n:e.snapshot().graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)before.put(n.id(),n);
        for(var e:restated.checkpoint().islands())for(var n:e.snapshot().graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)after.put(n.id(),n);
        assertEquals(before.keySet(),after.keySet());double energy=0;
        for(long id:before.keySet()) {
            var a=before.get(id);var b=after.get(id);
            assertArrayEquals(a.inventory().moles(),b.inventory().moles(),"vessel "+id+": its moles are kept exactly");
            assertEquals(a.inventory().volume(),b.inventory().volume());assertEquals(a.inventory().solids(),b.inventory().solids());
            assertEquals(a.state().temperature(),b.state().temperature(),1e-9*a.state().temperature(),"vessel "+id+": its temperature is kept");
            assertEquals(a.state().pressure(),b.state().pressure(),1e-6*a.state().pressure(),"vessel "+id+": the same data gives the same pressure");
            energy+=b.inventory().internalEnergy()-a.inventory().internalEnergy();
        }
        var booked=restated.world().orElseThrow().restated();
        assertEquals(energy,booked.energy(),1e-9*Math.max(1,Math.abs(energy)),"the ledger books the energy difference");
        assertTrue(booked.removedMoles().isEmpty()&&booked.removedSolids().isEmpty(),"the data still has every component");
        assertEquals(WorldTopologyLedger.FluidBasis.capture(CATALOG),restated.world().orElseThrow().basis());
    }

    /**
     * A component the property data no longer has: its amounts leave the vessels and the ledger books them by id, the
     * islands' status line names them, and every other amount is kept on the current axis.
     */
    @Test void aComponentTheDataNoLongerHasIsBookedAsRemovedAndNamedOnTheStatusLine() {
        var image=image(world());var core=FluidCheckpointCodec.readCore(image.core());
        var t=core.topology();var topologyPack=image.packs().get(t.pack());
        var world=FluidCheckpointCodec.worldFacts(topologyPack,FluidCheckpointCodec.topologySections(topologyPack,(int)t.offset(),t.length(),core.topologyGeneration()));
        // The saved island's axis has one more component, "Retired", which every vessel holds 2.5 mol of.
        var placed=new ArrayList<FluidFacts.Placed>();var kept=new TreeMap<Long,double[]>();int vessels=0;
        for(var row:core.islands()) {
            var pack=image.packs().get(row.unit().pack());var facts=FluidCheckpointCodec.islandFacts(pack,FluidCheckpointCodec.islandSections(pack,row),row.id());
            var components=new ArrayList<>(facts.components());components.add("Retired");
            var widened=facts.vessels().stream().map(v->{var moles=Arrays.copyOf(v.moles(),v.moles().length+1);moles[moles.length-1]=2.5;kept.put(v.id(),v.moles());
                return new FluidFacts.Vessel(v.id(),v.volume(),v.levelHeight(),v.open(),moles,v.energy(),v.solids(),v.temperature(),v.pressure(),v.liquid(),v.vapor(),v.waterLiquid(),v.waterVapor(),v.hydrocarbonPressure());}).toList();
            vessels+=widened.size();
            placed.add(new FluidFacts.Placed(new FluidFacts.Island(facts.dimension(),facts.packageId(),components,widened,facts.filters(),null),row.committed()));
        }
        var rebuilt=FluidFacts.rebuild(world,placed,core.epoch(),key->model,CATALOG,true,"test");
        assertEquals(Map.of("Retired",2.5*vessels),rebuilt.world().restated().removedMoles(),"the ledger books the removed component by id");
        for(var entry:rebuilt.checkpoint().islands()) {
            assertTrue(entry.snapshot().status().contains("removed by the property data: Retired "+(2.5*vessels)+" mol"),entry.snapshot().status());
            for(var n:entry.snapshot().graph().reservoirs())if(kept.containsKey(n.id()))assertArrayEquals(kept.get(n.id()),n.inventory().moles(),"vessel "+n.id()+": every other amount is kept");
        }
    }

    /** A facts section is read by every later build: one this build cannot read refuses the world, its files kept. */
    @Test void aFactsSectionThisBuildCannotReadRefusesTheWorld() {
        var world=world();var image=image(world);long island=world.coordinator.snapshots().getFirst().id();
        var unit=CheckpointImages.unitBytes(image,island);
        // The facts schema follows the 30-byte header: a later schema was written by a newer build.
        var later=unit.clone();later[FluidCheckpointCodec.UNIT_HEADER]=(byte)(FluidFacts.SCHEMA+1);
        var refusal=assertThrows(FluidCheckpointCodec.Unreadable.class,()->load(CheckpointImages.withUnit(image,island,later),key->model,CATALOG)).getMessage();
        assertTrue(refusal.contains("facts schema "+(FluidFacts.SCHEMA+1))&&refusal.contains("newer build"),refusal);
        // A device kind this build does not know (a renamed constant without its alias) refuses the world too.
        var topology=image.core().getCompound("Topology");var pack=image.packs().get(topology.getLong("Pack"));int offset=(int)topology.getLong("Offset");
        var bytes=CheckpointImages.renamed(Arrays.copyOfRange(pack,offset,offset+topology.getInt("Length")),"RESERVOIR","RESERVOIX");
        var packs=new HashMap<>(image.packs());var copy=pack.clone();System.arraycopy(bytes,0,copy,offset,bytes.length);packs.put(topology.getLong("Pack"),copy);
        var core=image.core().copy();core.getCompound("Topology").putByteArray("SHA256",FluidCheckpointCodec.sha256(bytes));CheckpointImages.reseal(core);
        refusal=assertThrows(FluidCheckpointCodec.Unreadable.class,()->load(new FluidCheckpointCodec.Image(core,packs),key->model,CATALOG)).getMessage();
        assertTrue(refusal.contains("unknown Kind RESERVOIX"),refusal);
    }
    /** Fields are append-only: a record that ends before a field reads that field's default (the behaviour before the
     * field existed), and a record with a field this build does not read was written by a newer build. */
    @Test void aMissingFieldReadsItsDefaultAndAnExtraFieldRefuses() {
        var older=FluidFacts.section(f->f.f64(1).name(Kind.PIPE));
        assertEquals(List.of(1.0,Kind.PIPE,7.0),FluidFacts.read(older,0,older.length,"an older record",r->List.of(r.f64(0),r.name(Kind.class,null),r.f64(7))));
        var newer=FluidFacts.section(f->f.f64(1).name(Kind.PIPE).text("appended"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidFacts.read(newer,0,newer.length,"a newer record",r->List.of(r.f64(0),r.name(Kind.class,null)))).getMessage().contains("newer build"));
    }

    // ---------------- the corpus: one image per facts schema ----------------

    private static final Path CORPUS=Path.of("src/test/resources/compat/fluid");

    /**
     * Every corpus image rebuilds from its facts to the registry, vessel count and stock it was written with. The image
     * of this build's schema must exist (AGENTS.md: a facts-schema change adds one); when it is missing the test writes
     * it under {@code build/compat/fluid/} to be copied into the corpus.
     */
    @Test void everyCorpusImageRebuildsFromItsFactsToItsRecordedTotals() throws IOException {
        var current=CORPUS.resolve("schema-"+FluidFacts.SCHEMA);
        if(!Files.isDirectory(current)) {
            var written=Path.of("build/compat/fluid/schema-"+FluidFacts.SCHEMA);write(image(corpusWorld()),written);
            fail("No corpus image of facts schema "+FluidFacts.SCHEMA+": copy "+written.toAbsolutePath()+" to "+current);
        }
        try(var images=Files.list(CORPUS)) {
            for(var folder:images.sorted().toList()) {
                var image=read(folder);var expected=new Properties();try(var in=Files.newBufferedReader(folder.resolve("expected.properties"))){expected.load(in);}
                var rebuilt=load(edited(image,core->core.putInt("StateFormat",Integer.MAX_VALUE)),key->model,CATALOG);
                assertEquals(FluidCheckpointStore.Route.FACTS,rebuilt.route(),folder.toString());
                var ledger=rebuilt.world().orElseThrow();
                assertEquals(Integer.parseInt(expected.getProperty("registrations")),ledger.active().size(),folder+": registrations");
                // Schema 2 wrote joints; an image of schema 1 reads every registration with all six faces, as pipes were then.
                assertEquals(Long.parseLong(expected.getProperty("laid","0")),ledger.active().values().stream().filter(r->r.device().joints()!=PhysicalFluidTopology.ALL_FACES).count(),folder+": laid pipes");
                assertEquals(Long.parseLong(expected.getProperty("lanes","0")),ledger.active().values().stream().filter(r->r.device().position().lane()!=0).count(),folder+": rack lanes");
                // Schema 3 wrote a spec's vessel geometry and the control valve; an older image reads prisms and no control valve.
                assertEquals(Long.parseLong(expected.getProperty("shaped","0")),shaped(ledger),folder+": shaped vessels");
                assertEquals(Long.parseLong(expected.getProperty("controlValves","0")),controlValves(ledger),folder+": control valves");
                long vessels=rebuilt.checkpoint().islands().stream().flatMap(e->e.snapshot().graph().reservoirs().stream()).filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).count();
                assertEquals(Long.parseLong(expected.getProperty("vessels")),vessels,folder+": vessels");
                double total=Arrays.stream(moles(rebuilt.checkpoint(),model.componentCount())).sum();
                assertEquals(Double.parseDouble(expected.getProperty("moles")),total,1e-12*total,folder+": stock");
            }
        }
    }
    /**
     * A world written before 2026-10-26 identifies its property basis by a hash of the package data (facts schema 1):
     * loaded as it is, it restates once onto the declared revisions, every amount kept, the same data giving the same
     * energy, and saves as an exact checkpoint again.
     */
    @Test void aSchemaOneWorldRestatesOnceOntoTheDeclaredRevisions() throws IOException {
        var folder=CORPUS.resolve("schema-1");var image=read(folder);
        var expected=new Properties();try(var in=Files.newBufferedReader(folder.resolve("expected.properties"))){expected.load(in);}
        var restated=load(image,key->model,CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,restated.route());
        assertTrue(restated.reason().contains("property data of package "+PACKAGE+" changed"),restated.reason());
        var ledger=restated.world().orElseThrow();
        assertEquals(WorldTopologyLedger.FluidBasis.capture(CATALOG),ledger.basis());
        double total=Arrays.stream(moles(restated.checkpoint(),model.componentCount())).sum();
        assertEquals(Double.parseDouble(expected.getProperty("moles")),total,1e-12*total,"every amount kept");
        assertTrue(ledger.restated().removedMoles().isEmpty()&&ledger.restated().removedSolids().isEmpty());
        double energy=0;for(var e:restated.checkpoint().islands())for(var n:e.snapshot().graph().reservoirs())
            if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)energy+=Math.abs(n.inventory().internalEnergy());
        assertEquals(0,ledger.restated().energy(),1e-9*energy,"the same data gives the same energy");
        var reloaded=new EventPathWorld(1,ledger,restated.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList());
        assertEquals(FluidCheckpointStore.Route.EXACT,load(image(reloaded),key->model,CATALOG).route(),"restated once");
    }
    /**
     * A world saved with one-block pumps and compressors, by main {@code 96806b8a} before batch
     * 2026-10-28-old-mover-removal (image under {@code compat/removed-movers/}, writer in
     * {@code tools/old-mover-removal/image-writer/}). The owner removed them whole (decision 2026-10-28), so the world
     * opens without them: its islands rebuild from the other facts, every vessel keeps its stock (a mover holds none),
     * and the rebuilt world saves as an exact checkpoint.
     */
    @Test void aWorldWithOneBlockMoversOpensWithoutThemAndKeepsEveryVessel() throws IOException {
        var folder=Path.of("src/test/resources/compat/removed-movers");var image=read(folder);
        var expected=new Properties();try(var in=Files.newBufferedReader(folder.resolve("expected.properties"))){expected.load(in);}
        var loaded=load(image,key->model,CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,loaded.route());
        assertTrue(loaded.reason().contains("one-block movers"),loaded.reason());
        var ledger=loaded.world().orElseThrow();
        assertEquals(Integer.parseInt(expected.getProperty("registrations"))-Integer.parseInt(expected.getProperty("movers")),ledger.active().size(),"every other registration kept");
        assertTrue(ledger.active().values().stream().noneMatch(r->r.device().kind()==Kind.PUMP||r.device().kind()==Kind.COMPRESSOR),"no mover left");
        double total=Arrays.stream(moles(loaded.checkpoint(),model.componentCount())).sum();
        assertEquals(Double.parseDouble(expected.getProperty("moles")),total,1e-12*total,"every vessel keeps its stock");
        assertEquals(Long.parseLong(expected.getProperty("vessels")),contents(loaded.checkpoint()).size());
        var reloaded=new EventPathWorld(1,ledger,loaded.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList());
        assertEquals(FluidCheckpointStore.Route.EXACT,load(image(reloaded),key->model,CATALOG).route(),"opened once");
    }
    private void write(FluidCheckpointCodec.Image image,Path folder) throws IOException {
        Files.createDirectories(folder);NbtIo.writeCompressed(image.core(),folder.resolve("core.dat"));
        for(var pack:image.packs().entrySet())Files.write(folder.resolve("pack-"+pack.getKey()+".bin"),pack.getValue());
        var loaded=load(image,key->model,CATALOG);var props=new Properties();
        props.setProperty("registrations",Integer.toString(loaded.world().orElseThrow().active().size()));
        props.setProperty("laid",Long.toString(loaded.world().orElseThrow().active().values().stream().filter(r->r.device().joints()!=PhysicalFluidTopology.ALL_FACES).count()));
        props.setProperty("lanes",Long.toString(loaded.world().orElseThrow().active().values().stream().filter(r->r.device().position().lane()!=0).count()));
        props.setProperty("shaped",Long.toString(shaped(loaded.world().orElseThrow())));
        props.setProperty("controlValves",Long.toString(controlValves(loaded.world().orElseThrow())));
        props.setProperty("vessels",Long.toString(contents(loaded.checkpoint()).values().stream().filter(v->v instanceof PassiveNetwork.Inventory).count()));
        props.setProperty("moles",Double.toString(Arrays.stream(moles(loaded.checkpoint(),model.componentCount())).sum()));
        try(var out=Files.newBufferedWriter(folder.resolve("expected.properties"))){props.store(out,"Facts schema "+FluidFacts.SCHEMA+" corpus image, state format "+FluidCheckpointCodec.VERSION);}
    }
    private static long shaped(WorldTopologyLedger.Snapshot world){return world.active().values().stream().filter(r->r.spec().geometry().form()!=VesselGeometry.Form.PRISM).count();}
    private static long controlValves(WorldTopologyLedger.Snapshot world){return world.active().values().stream().filter(r->r.device().control() instanceof FlowControl.ControlValve).count();}
    private static FluidCheckpointCodec.Image read(Path folder) throws IOException {
        var core=NbtIo.readCompressed(folder.resolve("core.dat"),NbtAccounter.unlimitedHeap());var packs=new HashMap<Long,byte[]>();
        try(var files=Files.list(folder)){for(var file:files.toList()){var name=file.getFileName().toString();
            if(name.startsWith("pack-")&&name.endsWith(".bin"))packs.put(Long.parseLong(name.substring(5,name.length()-4)),Files.readAllBytes(file));}}
        return new FluidCheckpointCodec.Image(core,packs);
    }
}
