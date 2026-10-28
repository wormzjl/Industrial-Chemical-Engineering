package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.SteamVirial;
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
        // Facts schema 5: an exchanger's tube and shell sides, partners, each a passage of many channels, one with a
        // control and heat in transit (every field away from its default).
        long tube=world.ledger.nextIdentity(),shell=tube+1;var spec=FluidDeviceSpec.nitrogen(CATALOG);
        var tubeSide=heatSide(tube,30,0,HeatSides.tubePassage(1,6),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,450,shell,
                new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE,330,280,.25),1234.5));
        var shellSide=heatSide(shell,30,4,HeatSides.shellPassage(1,6),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.SHELL,450,tube));
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(tube,new WorldTopologyLedger.Registration(tubeSide,spec,0)),new WorldTopologyLedger.Edit(shell,new WorldTopologyLedger.Registration(shellSide,spec,0))),shell+1,null);
        world.runTo(700);
        // Facts schema 6: a running column unit, a pipe it holds, and its feed port, a void inside the feed valve.
        long pipe=world.place(40,64,0,Kind.PIPE,Direction.EAST,.05);long feedValve=world.place(41,64,0,Kind.VALVE,Direction.EAST);world.runTo(720);
        long unit=world.ledger.reserve(1);long port=world.ledger.nextIdentity();int m=model.componentCount();
        var held=new double[m];held[0]=3.5;held[m-1]=.25;
        var at=new PhysicalFluidTopology.Position(EventPathWorld.DIM,40,64,0,PhysicalFluidTopology.PORT_LANE);
        var feed=new PhysicalFluidTopology.Device(port,at,Kind.VOID,Direction.EAST,world.registry.registrations().get(feedValve).device().geometry(),new FlowControl.Passive(),
                List.of(new PhysicalFluidTopology.Port(at,Direction.EAST,PassiveNetwork.PhasePort.BULK)),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null,0);
        var setpoints=new com.wormzjl.icheme.science.column.operation.Setpoints(10,8,320,2.5,1e4,new double[]{.3},new double[]{.1},new double[]{-2e4});
        var account=new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(held,-1.2e6);
        world.ledger.column(new ColumnUnit(unit,new PhysicalFluidTopology.Position(EventPathWorld.DIM,40,70,0),true,setpoints,
                List.of(new ColumnUnit.Station(com.wormzjl.icheme.science.column.operation.Port.FEED,0,port,feedValve,account)),
                new ColumnUnit.Totals(account,com.wormzjl.icheme.science.column.operation.ColumnCycle.Account.empty(m),2e5,-3e5,-1e4)));
        var held0=world.registry.registrations().get(pipe);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(pipe,new WorldTopologyLedger.Registration(held0.device().withSuspendedBy(unit),held0.spec(),held0.revision()+1)),
                new WorldTopologyLedger.Edit(port,new WorldTopologyLedger.Registration(feed,new FluidDeviceSpec(1,330,260000,FluidDeviceSpec.nitrogen(CATALOG).composition()),0))),port+1,null);
        world.runTo(800);return world;
    }
    private static PhysicalFluidTopology.Device heatSide(long id,int x,int z,PipeResistance.Geometry passage,HeatUnit heat) {
        var inlet=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,64,z);var outlet=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x+2,64,z);
        var ports=List.of(new PhysicalFluidTopology.Port(inlet,Direction.WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.INLET),
                new PhysicalFluidTopology.Port(outlet,Direction.EAST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(id,inlet,Kind.HEAT_SIDE,Direction.NORTH,passage,new FlowControl.Passive(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
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
    /**
     * W3 (plan section 5.2): the heat an exchanger holds in transit is a fact of its sides. A save writes the coordinator's
     * value into the side with the lower identity and 0 into the other; a load reads the two back as their sum; the facts
     * round-trip it. A removed unit books it as destroyed energy.
     */
    @Test void anExchangersHeatInTransitIsAFactOfItsSides() {
        var world=corpusWorld();var saved=world.ledger.snapshot();
        long tube=saved.active().values().stream().filter(r->r.device().heat()!=null&&r.device().heat().side()==HeatSides.Side.TUBE).findFirst().orElseThrow().device().id();
        long shell=saved.active().get(tube).device().heat().partner(),pair=PhysicalFluidTopology.sideIdentity(Math.min(tube,shell));
        assertEquals(Map.of(pair,1234.5),saved.heatTransits(),"a load reads the transit the facts carry");
        var written=saved.withHeatTransits(Map.of(pair,-42.25));
        assertEquals(-42.25,written.active().get(Math.min(tube,shell)).device().heat().transitJoule(),"the lower side holds it");
        assertEquals(0,written.active().get(Math.max(tube,shell)).device().heat().transitJoule(),"the other side 0");
        assertSame(written,written.withHeatTransits(written.heatTransits()),"an unchanged transit writes nothing new");
        var decoded=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(written),written.onlineTick(),key->model);
        assertEquals(Map.of(pair,-42.25),decoded.heatTransits(),"the facts round-trip it");
        double before=world.ledger.snapshot().destroyed().totalEnergy();world.ledger.removedHeat(-42.25);
        assertEquals(before-42.25,world.ledger.snapshot().destroyed().totalEnergy(),1e-9,"a removed unit books it as destroyed energy");
    }
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
                assertEquals(Long.parseLong(expected.getProperty("lanes","0")),ledger.active().values().stream().filter(r->r.device().position().lane()!=0&&r.device().position().lane()<=PhysicalFluidTopology.RACK_PLACES).count(),folder+": rack lanes");
                // Schema 3 wrote a spec's vessel geometry and the control valve; an older image reads prisms and no control valve.
                assertEquals(Long.parseLong(expected.getProperty("shaped","0")),shaped(ledger),folder+": shaped vessels");
                assertEquals(Long.parseLong(expected.getProperty("controlValves","0")),controlValves(ledger),folder+": control valves");
                // Schema 5 wrote heat sides and channel counts; an older image reads none and one channel everywhere.
                assertEquals(expected.getProperty("heatSides",""),heatSides(ledger),folder+": heat sides");
                // Schema 6 wrote the column units and the devices they hold; an older image reads none.
                assertEquals(Long.parseLong(expected.getProperty("suspended","0")),suspended(ledger),folder+": suspended devices");
                assertEquals(expected.getProperty("columns",""),columns(ledger),folder+": column units");
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
     * A vessel of superheated steam and nitrogen, charged from a generator of 80 % steam at 520 K and 10 bar, written under
     * the ideal steam of {@code separate-free-water-v2} (corpus image {@code steam-v2}, 2026-10-28). The second-virial
     * steam of v3 changes the thermodynamic revision, so the world restates on load by a TP flash at each vessel's saved
     * temperature and pressure: its moles, wall volume, temperature and pressure kept, its contents' volume and energy the
     * virial steam's ({@code p_w = n_w R T / (V - n_w B)}), and the ledger books the energy difference, the steam's
     * residual energy {@code n_w u_res = -n_w p_w T dB/dT}. When the image is missing the test writes it under
     * {@code build/compat/fluid/}.
     */
    @Test void aWorldSavedUnderTheIdealSteamRestatesOntoTheVirialSteam() throws IOException {
        var folder=CORPUS.resolve("steam-v2");
        if(!Files.isDirectory(folder)) {
            var written=Path.of("build/compat/fluid/steam-v2");write(image(steamWorld()),written);
            fail("No steam corpus image: copy "+written.toAbsolutePath()+" to "+folder);
        }
        var image=read(folder);var core=FluidCheckpointCodec.readCore(image.core());var saved=new TreeMap<Long,FluidFacts.Vessel>();
        for(var row:core.islands()){var pack=image.packs().get(row.unit().pack());
            for(var v:FluidCheckpointCodec.islandFacts(pack,FluidCheckpointCodec.islandSections(pack,row),row.id()).vessels())saved.put(v.id(),v);}
        var restated=load(image,key->model,CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,restated.route());
        assertTrue(restated.reason().contains("property data of package "+PACKAGE+" changed"),restated.reason());
        var ledger=restated.world().orElseThrow();
        assertTrue(ledger.restated().removedMoles().isEmpty()&&ledger.restated().removedSolids().isEmpty());
        double energy=0,residual=0;int vessels=0;
        for(var e:restated.checkpoint().islands())for(var n:e.snapshot().graph().reservoirs()) {
            if(n.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
            var v=saved.get(n.id());var s=n.state();vessels++;
            assertArrayEquals(v.moles(),n.inventory().moles(),"vessel "+n.id()+": its moles are kept exactly");
            assertEquals(v.volume(),n.inventory().volume());
            assertEquals(v.temperature(),s.temperature(),1e-9*v.temperature(),"vessel "+n.id()+": its temperature is kept");
            assertTrue(s.waterVapor()>0&&s.waterLiquid()==0&&s.waterVapor()==v.waterVapor(),"all of the water is steam, as saved");
            // The restatement is a TP flash at the saved temperature and pressure (FluidFacts.rebuild): the denser virial
            // steam fills less than the wall until the island's first solve brings the contents back to it.
            assertEquals(v.pressure(),s.pressure(),"vessel "+n.id()+": restated at its saved pressure");
            assertTrue(s.volume()<v.volume(),"vessel "+n.id()+": the virial steam is denser than the ideal steam it was saved as");
            double b=SteamVirial.secondVirial(s.temperature()),t=s.temperature();
            assertEquals(s.waterVapor()*FluidThermodynamics.R*t/(s.vaporVolume()-s.waterVapor()*b),s.waterPartialPressure(),1e-9*s.waterPartialPressure(),"the virial steam's partial pressure");
            energy+=n.inventory().internalEnergy()-v.energy();
            residual+=-s.waterVapor()*s.waterPartialPressure()*t*SteamVirial.temperatureDerivative(t);
            System.out.println("steam-v2 vessel "+n.id()+": T "+t+" K, steam "+s.waterVapor()+" mol at "+s.waterPartialPressure()+" Pa of "+s.pressure()
                    +" Pa, volume "+v.volume()+" -> "+s.volume()+" m3, U "+v.energy()+" -> "+n.inventory().internalEnergy()+" J");
        }
        assertEquals(1,vessels);
        // U = U_N2 + n_w (u_ig - p_w T dB/dT): the steam's residual energy, the nitrogen's own moving a few joules.
        assertEquals(residual,energy,1e-3*Math.abs(residual),"the energy moves by the steam's residual energy");
        // dB/dT > 0 below the Boyle temperature, so the steam's own energy falls: -25.1 kJ for the image's 145.5 mol at 661 K and
        // 7.98 bar, -173 J/mol (SteamVirialTest checks dB/dT against IF97 Region 2).
        assertTrue(residual<0,"the steam's residual energy: "+residual+" J");
        assertEquals(energy,ledger.restated().energy(),1e-9*Math.abs(energy),"the ledger books the energy difference");
        var reloaded=new EventPathWorld(1,ledger,restated.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList());
        assertEquals(FluidCheckpointStore.Route.EXACT,load(image(reloaded),key->model,CATALOG).route(),"restated once");
    }
    /** A 1 m3 vessel charged from a generator of 80 % steam and 20 % nitrogen at 520 K and 10 bar (the steam's dew point 444 K), the generator then removed. */
    private EventPathWorld steamWorld() {
        var world=new EventPathWorld();
        double[] nitrogen=FluidDeviceSpec.nitrogen(CATALOG).composition(),water=FluidDeviceSpec.water(CATALOG).composition(),n=new double[nitrogen.length];
        for(int c=0;c<n.length;c++)n[c]=.2*nitrogen[c]+.8*water[c];
        long generator=world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,520,1e6,n),.05);
        for(int x=1;x<=2;x++)world.place(x,64,0,Kind.PIPE,Direction.EAST,.05);
        world.place(3,64,0,Kind.RESERVOIR,Direction.EAST);
        world.runTo(400);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(generator,null)),world.ledger.nextIdentity(),null);world.runTo(600);
        world.assertKeptUp("the steam line");
        return world;
    }
    /**
     * A world saved with one-block pumps and compressors, by main {@code 96806b8a} before batch
     * 2026-10-28-old-mover-removal (image under {@code compat/removed-movers/}, writer in
     * {@code tools/old-mover-removal/image-writer/}). The owner removed them whole (decision 2026-10-28), so the world
     * opens without them: its islands rebuild from the other facts, every vessel keeps its stock (a mover holds none),
     * and the rebuilt world saves as an exact checkpoint. The image predates the virial steam of
     * {@code separate-free-water-v3}, so the load names the property data's change, its first reason; the movers go all
     * the same, logged by {@code FluidFacts}.
     */
    @Test void aWorldWithOneBlockMoversOpensWithoutThemAndKeepsEveryVessel() throws IOException {
        var folder=Path.of("src/test/resources/compat/removed-movers");var image=read(folder);
        var expected=new Properties();try(var in=Files.newBufferedReader(folder.resolve("expected.properties"))){expected.load(in);}
        var loaded=load(image,key->model,CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,loaded.route());
        assertTrue(loaded.reason().contains("property data of package "+PACKAGE+" changed"),loaded.reason());
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
        props.setProperty("lanes",Long.toString(loaded.world().orElseThrow().active().values().stream().filter(r->r.device().position().lane()!=0&&r.device().position().lane()<=PhysicalFluidTopology.RACK_PLACES).count()));
        props.setProperty("shaped",Long.toString(shaped(loaded.world().orElseThrow())));
        props.setProperty("controlValves",Long.toString(controlValves(loaded.world().orElseThrow())));
        props.setProperty("heatSides",heatSides(loaded.world().orElseThrow()));
        props.setProperty("suspended",Long.toString(suspended(loaded.world().orElseThrow())));
        props.setProperty("columns",columns(loaded.world().orElseThrow()));
        props.setProperty("vessels",Long.toString(contents(loaded.checkpoint()).values().stream().filter(v->v instanceof PassiveNetwork.Inventory).count()));
        props.setProperty("moles",Double.toString(Arrays.stream(moles(loaded.checkpoint(),model.componentCount())).sum()));
        try(var out=Files.newBufferedWriter(folder.resolve("expected.properties"))){props.store(out,"Facts schema "+FluidFacts.SCHEMA+" corpus image, state format "+FluidCheckpointCodec.VERSION);}
    }
    /** Every heat side and every passage of more than one channel, in identity order: what schema 5 carries. */
    private static String heatSides(WorldTopologyLedger.Snapshot world) {
        var text=new StringBuilder();
        for(var r:new TreeMap<>(world.active()).values()) {
            var g=r.device().geometry();var h=r.device().heat();if(h==null&&g.channels()==1)continue;
            if(!text.isEmpty())text.append(";");
            text.append(r.device().id()).append(":").append(g.channels());
            if(h!=null)text.append(":").append(h.unit()).append(":").append(h.side()).append(":").append(h.area()).append(":").append(h.partner()).append(":").append(h.control()).append(":").append(h.transitJoule());
        }
        return text.toString();
    }
    private static long suspended(WorldTopologyLedger.Snapshot world){return world.active().values().stream().filter(r->r.device().suspended()).count();}
    /** Every column unit, in identity order: what schema 6 carries. */
    private static String columns(WorldTopologyLedger.Snapshot world) {
        var text=new StringBuilder();
        for(var u:new TreeMap<>(world.columns()).values()) {
            if(!text.isEmpty())text.append(";");
            text.append(u.id()).append(":").append(u.running()).append(":").append(u.setpoints().feedRate()).append(":").append(u.totals().taken().total());
            for(var s:u.stations())text.append(":").append(s.port()).append(s.index()).append("=").append(s.device()).append("/").append(s.valve()).append("/").append(s.account().total()).append("/").append(s.account().energy());
        }
        return text.toString();
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
