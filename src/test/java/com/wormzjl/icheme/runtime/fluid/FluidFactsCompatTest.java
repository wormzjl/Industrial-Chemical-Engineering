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
        var stock=contents(new FluidCheckpointCodec.Checkpoint(world.entries()));
        assertTrue(Arrays.stream(((PassiveNetwork.Inventory)stock.get(first)).moles()).sum()>10,"the generator charged the vessels: "+stock.get(first));
        return world;
    }
    private EventPathWorld corpusWorld(){
        var world=world();var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        world.place(20,64,0,Kind.RESERVOIR,Direction.EAST,new FluidDeviceSpec(g.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(CATALOG).composition(),SlurryFeed.NONE,g.height(),false,g,null),.05);
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
        // Facts schema 8 (S3b): a second condenser shell in parallel, its tube the column's second water side. Schema 9 (U2):
        // its water flow is the column's (the COLUMN mode).
        long tube2=world.ledger.nextIdentity(),shell2=tube2+1;
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(tube2,new WorldTopologyLedger.Registration(heatSide(tube2,34,0,HeatSides.tubePassage(1,6),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.TUBE,450,shell2)
                        .withControl(new HeatUnit.Control(HeatUnit.Mode.COLUMN,0,0,0))),spec,0)),
                new WorldTopologyLedger.Edit(shell2,new WorldTopologyLedger.Registration(heatSide(shell2,34,4,HeatSides.shellPassage(1,6),new HeatUnit(HeatSides.Unit.EXCHANGER,HeatSides.Side.SHELL,450,tube2)),spec,0))),shell2+1,null);
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
                List.of(new ColumnUnit.Station(com.wormzjl.icheme.science.column.operation.Port.FEED,0,port,feedValve,account,new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(model.solid("icheme:demo_particle"),com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("100"),1.5))))),
                new ColumnUnit.Totals(account,com.wormzjl.icheme.science.column.operation.ColumnCycle.Account.empty(m),2e5,-3e5,-1e4),List.of(tube,tube2),null,false,
                // Facts schema 9 (U2): the air condenser's bare tube.
                1234.5));
        var held0=world.registry.registrations().get(pipe);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(pipe,new WorldTopologyLedger.Registration(held0.device().withSuspendedBy(unit),held0.spec(),held0.revision()+1)),
                new WorldTopologyLedger.Edit(port,new WorldTopologyLedger.Registration(feed,new FluidDeviceSpec(1,330,260000,FluidDeviceSpec.nitrogen(CATALOG).composition()),0))),port+1,null);
        // Facts schema 7: a tower's bank and control on a basin.
        var tower=CoolingTower.induced(8,2,3,2).withControl(CoolingTower.Mode.TARGET_TEMPERATURE,.4,299);
        world.place(50,64,0,Kind.RESERVOIR,Direction.EAST,new FluidDeviceSpec(tower.basinVolume(),298.15,101325,
            FluidDeviceSpec.water(CATALOG).composition(),SlurryFeed.NONE,CoolingTower.BASIN_DEPTH,true).withTower(tower),.05);
        world.runTo(800);return world;
    }
    private static PhysicalFluidTopology.Device heatSide(long id,int x,int z,PipeResistance.Geometry passage,HeatUnit heat) {
        var inlet=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,64,z);var outlet=new PhysicalFluidTopology.Position(EventPathWorld.DIM,x+2,64,z);
        var ports=List.of(new PhysicalFluidTopology.Port(inlet,Direction.WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.INLET),
                new PhysicalFluidTopology.Port(outlet,Direction.EAST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.OUTLET));
        return new PhysicalFluidTopology.Device(id,inlet,Kind.HEAT_SIDE,Direction.NORTH,passage,new FlowControl.Passive(),ports,true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,heat);
    }
    private FluidCheckpointCodec.Image image(EventPathWorld world) {
        var checkpoint=new FluidCheckpointCodec.Checkpoint(world.entries());
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.of(world.ledger.snapshot()),world.ledger.onlineTick(),key->key.on(model)));
        return store.image();
    }
    private static FluidCheckpointCodec.Image edited(FluidCheckpointCodec.Image image,java.util.function.Consumer<CompoundTag> edit) {
        var core=image.core().copy();edit.accept(core);CheckpointImages.reseal(core);return new FluidCheckpointCodec.Image(core,image.packs());
    }
    private static FluidCheckpointStore.Loaded load(FluidCheckpointCodec.Image image,java.util.function.Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> models,MaterialCatalog catalog) {
        return FluidCheckpointStore.memory(image).load(image.core(),models,catalog);
    }
    /** Every vessel's inventory on the world list (each island's by compound id, V3) and every filter's cake, by device identity. */
    private Map<Long,Object> contents(FluidCheckpointCodec.Checkpoint checkpoint) {
        var out=new TreeMap<Long,Object>();
        for(var entry:checkpoint.islands()) {
            var graph=entry.snapshot().graph();var own=entry.key().on(model);
            for(var n:graph.reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)assertNull(out.put(n.id(),IslandSets.paddedNode(n,own,model).inventory()),"vessel "+n.id()+" in two islands");
            for(var p:graph.pipes())if(p.filter()!=null)assertNull(out.put(p.id()-Long.MIN_VALUE,p.filter()),"filter "+p.id()+" in two islands");
        }
        return out;
    }
    /** Amounts on the axis {@code components} on the world list, by compound id. */
    private double[] onWorld(double[] amounts,List<String> components) {
        var world=model.components();var out=new double[world.size()];
        for(int c=0;c<amounts.length;c++){int to=world.indexOf(components.get(c));if(to>=0)out[to]=amounts[c];else assertEquals(0,amounts[c],components.get(c)+" is off the world list");}
        return out;
    }
    /** Every vessel's moles summed on the world list: each island's amounts mapped by compound id (V3). */
    private double[] moles(FluidCheckpointCodec.Checkpoint checkpoint,int components) {
        var total=new double[components];
        for(var entry:checkpoint.islands())for(var n:entry.snapshot().graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR){var m=IslandSets.amounts(n.inventory().moles(),entry.key().on(model),model);for(int c=0;c<components;c++)total[c]+=m[c];}
        return total;
    }

    @Test void anotherStateFormatRebuildsTheWorldFromItsFactsWithEveryVesselExactlyAsSaved() {
        var world=world();var image=image(world);var saved=world.ledger.snapshot();
        var exact=load(image,key->key.on(model),CATALOG);
        assertEquals(FluidCheckpointStore.Route.EXACT,exact.route(),"this build's own checkpoint loads exactly");
        var rebuilt=load(edited(image,core->core.putInt("StateFormat",FluidCheckpointCodec.VERSION+1)),key->key.on(model),CATALOG);
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
        var reloaded=new EventPathWorld(1,ledger,rebuilt.checkpoint().islands());
        reloaded.runTo(epoch+300);reloaded.assertKeptUp("the rebuilt line");
        var before=moles(rebuilt.checkpoint(),model.componentCount());var after=moles(new FluidCheckpointCodec.Checkpoint(reloaded.entries()),model.componentCount());
        for(int c=0;c<before.length;c++)assertEquals(before[c],after[c],1e-9*Math.max(1,Math.abs(before[c])),"component "+c+" conserved by the rebuilt line");
        var again=image(reloaded);assertEquals(FluidCheckpointStore.Route.EXACT,load(again,key->key.on(model),CATALOG).route());
    }

    /**
     * A changed property basis (here the energy reference's revision) restates every vessel: its moles, volume and
     * temperature are kept, the current data gives its energy and pressure, and the ledger books the energy difference.
     */
    @Test void aChangedBasisRestatesEveryVesselKeepingItsAmountsAndTemperatureAndBooksTheEnergy() {
        var world=world();var image=image(world);var exact=load(image,key->key.on(model),CATALOG);
        var changed=edited(image,core->{var entry=((ListTag)core.get("Packages")).getCompound(0);entry.putString("EnergyRevision",entry.getString("EnergyRevision")+"-retired");});
        var restated=load(changed,key->key.on(model),CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,restated.route());assertTrue(restated.reason().contains("property data of package "+PACKAGE+" changed"),restated.reason());
        var before=new TreeMap<Long,PassiveNetwork.Reservoir>();var after=new TreeMap<Long,PassiveNetwork.Reservoir>();
        var exactOwn=new HashMap<Long,List<String>>();var restatedOwn=new HashMap<Long,List<String>>();
        for(var e:exact.checkpoint().islands())for(var n:e.snapshot().graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR){before.put(n.id(),n);exactOwn.put(n.id(),e.key().on(model).components());}
        for(var e:restated.checkpoint().islands())for(var n:e.snapshot().graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR){after.put(n.id(),n);restatedOwn.put(n.id(),e.key().on(model).components());}
        assertEquals(before.keySet(),after.keySet());double energy=0;
        for(long id:before.keySet()) {
            var a=before.get(id);var b=after.get(id);
            assertArrayEquals(onWorld(a.inventory().moles(),exactOwn.get(id)),onWorld(b.inventory().moles(),restatedOwn.get(id)),"vessel "+id+": its moles are kept exactly");
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
            var widened=facts.vessels().stream().map(v->{var moles=Arrays.copyOf(v.moles(),v.moles().length+1);moles[moles.length-1]=2.5;kept.put(v.id(),onWorld(v.moles(),facts.components()));
                return new FluidFacts.Vessel(v.id(),v.volume(),v.levelHeight(),v.open(),moles,v.energy(),v.solids(),v.temperature(),v.pressure(),v.liquid(),v.vapor(),v.waterLiquid(),v.waterVapor(),v.hydrocarbonPressure());}).toList();
            vessels+=widened.size();
            placed.add(new FluidFacts.Placed(new FluidFacts.Island(facts.dimension(),facts.packageId(),components,widened,facts.filters(),null),row.committed()));
        }
        var rebuilt=FluidFacts.rebuild(world,placed,core.epoch(),key->key.on(model),CATALOG,true,"test");
        assertEquals(Map.of("Retired",2.5*vessels),rebuilt.world().restated().removedMoles(),"the ledger books the removed component by id");
        for(var entry:rebuilt.checkpoint().islands()) {
            assertTrue(entry.snapshot().status().contains("removed by the property data: Retired "+(2.5*vessels)+" mol"),entry.snapshot().status());
            var own=entry.key().on(model).components();
            for(var n:entry.snapshot().graph().reservoirs())if(kept.containsKey(n.id()))assertArrayEquals(kept.get(n.id()),onWorld(n.inventory().moles(),own),"vessel "+n.id()+": every other amount is kept");
        }
    }

    /** A component the data no longer has leaves a column's account too: the ledger and the status line book it (merge review, finding 5). */
    @Test void aComponentRemovedFromAColumnAccountIsBooked() {
        var image=image(corpusWorld());var core=FluidCheckpointCodec.readCore(image.core());
        var t=core.topology();var topologyPack=image.packs().get(t.pack());
        var world=FluidCheckpointCodec.worldFacts(topologyPack,FluidCheckpointCodec.topologySections(topologyPack,(int)t.offset(),t.length(),core.topologyGeneration()));
        var ids=new ArrayList<>(world.basis().components());int retired=ids.size()-1;ids.add(retired,"Retired");
        java.util.function.BiFunction<double[],Double,double[]> widen=(n,held)->{var w=new double[n.length+1];System.arraycopy(n,0,w,0,retired);w[retired]=held;w[n.length]=n[retired];return w;};
        var registrations=new ArrayList<WorldTopologyLedger.Registration>();
        for(var r:world.registrations()){var s=r.spec();registrations.add(new WorldTopologyLedger.Registration(r.device(),new FluidDeviceSpec(s.volume(),s.temperature(),s.pressure(),widen.apply(s.composition(),0.),s.solids(),s.levelHeight(),s.open(),s.geometry(),s.tower()),r.revision()));}
        java.util.function.Function<WorldTopologyLedger.MaterialTotal,WorldTopologyLedger.MaterialTotal> total=m->new WorldTopologyLedger.MaterialTotal(widen.apply(m.moles(),0.),m.totalEnergy(),m.solidMasses());
        var placed=new ArrayList<FluidFacts.Placed>();
        for(var row:core.islands()){var pack=image.packs().get(row.unit().pack());placed.add(new FluidFacts.Placed(FluidCheckpointCodec.islandFacts(pack,FluidCheckpointCodec.islandSections(pack,row),row.id()),row.committed()));}
        // The same world with and without the retired component in its account: what the ledger books for it is the difference.
        java.util.function.DoubleFunction<FluidFacts.Rebuilt> restated=retiring->{
        var columns=new ArrayList<ColumnUnit>();
        for(var u:world.columns()) {
            columns.add(new ColumnUnit(u.id(),u.master(),u.running(),u.setpoints(),u.stations().stream().map(s->s.withAccount(new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(widen.apply(s.account().moles(),retiring),s.account().energy()))).toList(),
                    new ColumnUnit.Totals(new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(widen.apply(u.totals().taken().moles(),0.),u.totals().taken().energy()),
                            new com.wormzjl.icheme.science.column.operation.ColumnCycle.Account(widen.apply(u.totals().produced().moles(),0.),u.totals().produced().energy()),u.totals().reboiler(),u.totals().condenser(),u.totals().pumparounds()),u.condensers()));
        }
        var wide=new FluidFacts.World(world.nextIdentity(),new WorldTopologyLedger.FluidBasis(world.basis().packageId(),ids,world.basis().revision()),registrations,total.apply(world.constructed()),total.apply(world.destroyed()),world.recoveries(),world.restated(),world.removedMovers(),columns);
        return FluidFacts.rebuild(wide,placed,core.epoch(),key->key.on(model),CATALOG,true,"test");
        };
        var rebuilt=restated.apply(1.5);
        assertEquals(Map.of("Retired",1.5),rebuilt.world().restated().removedMoles(),"the ledger books the removed component by id");
        for(var entry:rebuilt.checkpoint().islands())assertTrue(entry.snapshot().status().contains("Retired 1.5 mol"),entry.snapshot().status());
        // An account has no temperature, so the component leaves with its molar share of the energy (owner 2026-11-02, finding 5, option A).
        var before=world.columns().iterator().next().stations().getFirst().account();double share=1.5/(before.total()+1.5),leaves=share*before.energy();
        for(var u:rebuilt.world().columns().values())assertEquals(before.energy()-leaves,u.stations().getFirst().account().energy(),1e-9*Math.abs(before.energy()),"the rest keeps its specific energy");
        assertEquals(-leaves,rebuilt.world().restated().energy()-restated.apply(0).world().restated().energy(),1e-6,"the ledger books what left");
    }
    /** Queued events do not survive the facts path: a unit's queued Start, Stop or removal settles on what stands (merge review, finding 15). */
    @Test void aColumnsQueuedEventsSettleOnWhatStands() {
        var stoppedHolding=corpusWorld();var unit=stoppedHolding.ledger.columns().values().iterator().next();
        stoppedHolding.ledger.column(unit.withStations(false,unit.stations().stream().map(s->s.withDevice(0)).toList()));
        var settled=load(edited(image(stoppedHolding),core->core.putInt("StateFormat",FluidCheckpointCodec.VERSION+1)),key->key.on(model),CATALOG).world().orElseThrow();
        assertEquals(0,suspended(settled),"a stopped unit holds nothing");
        assertTrue(unit.stations().stream().noneMatch(s->settled.active().containsKey(s.device())),"its ports left");
        assertFalse(settled.columns().get(unit.id()).running());

        var unplaced=corpusWorld();var u=unplaced.ledger.columns().values().iterator().next();
        unplaced.ledger.column(u.withStations(true,u.stations().stream().map(s->s.withDevice(s.device()+1000)).toList()));
        var started=load(edited(image(unplaced),core->core.putInt("StateFormat",FluidCheckpointCodec.VERSION+1)),key->key.on(model),CATALOG).world().orElseThrow();
        assertEquals(0,suspended(started),"a Start whose ports never stood holds nothing");
        assertFalse(started.columns().get(u.id()).running());

        var leaving=corpusWorld();var v=leaving.ledger.columns().values().iterator().next();double[] destroyed=leaving.ledger.snapshot().destroyed().moles();
        leaving.ledger.column(v.leave());
        var left=load(edited(image(leaving),core->core.putInt("StateFormat",FluidCheckpointCodec.VERSION+1)),key->key.on(model),CATALOG).world().orElseThrow();
        assertFalse(left.columns().containsKey(v.id()),"a dismantled column leaves");
        assertEquals(0,suspended(left));
        assertEquals(Arrays.stream(destroyed).sum()+v.stations().getFirst().account().total(),Arrays.stream(left.destroyed().moles()).sum(),1e-9,"its accounts are destroyed");
        assertEquals(1.5,left.destroyed().solidMasses().values().stream().mapToDouble(Double::doubleValue).sum(),0,"and its solids (merge review, finding 10)");

        var running=corpusWorld();var w=running.ledger.columns().values().iterator().next();
        assertTrue(load(edited(image(running),core->core.putInt("StateFormat",FluidCheckpointCodec.VERSION+1)),key->key.on(model),CATALOG).world().orElseThrow().columns().get(w.id()).running(),"a unit whose devices stand keeps running");
    }
    // ---------------- the world basis by compound ids (batch 2026-11-04-refinery-compounds, W4) ----------------

    private static final String NETWORK_RECORD="data/icheme/materials/packages/tjl20_nitrogen.json";
    /**
     * The bundled catalog with its network package's record written out (no {@code extends}) as {@code compounds} of its
     * own list, under id {@code id}; the network configuration and the column preset that name the package follow it. A
     * shorter list holds no crude assay, so the network then offers only its pure presets and the column preset leaves.
     */
    private static MaterialCatalog network(String id,java.util.function.UnaryOperator<List<String>> compounds) {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());var whole=CATALOG.requirePackage(PACKAGE).components();
        var record=com.google.gson.JsonParser.parseString(resources.get(NETWORK_RECORD)).getAsJsonObject();record.remove("extends");
        var list=compounds.apply(new ArrayList<>(whole));
        record.add("components",new com.google.gson.Gson().toJsonTree(list));
        record.addProperty("id",id);resources.put(NETWORK_RECORD,record.toString());
        String network="data/icheme/materials/networks/default.json",column="data/icheme/materials/presets/column_tia_juana_nitrogen.json";
        if(!list.containsAll(whole)) {
            resources.remove(column);
            var config=com.google.gson.JsonParser.parseString(resources.get(network)).getAsJsonObject();
            config.add("fluid_presets",new com.google.gson.Gson().toJsonTree(List.of("water","nitrogen")));resources.put(network,config.toString());
        }
        for(var path:List.of(network,column)) {
            if(!resources.containsKey(path))continue;
            var named=com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();assertEquals(PACKAGE,named.get("package").getAsString(),path);
            named.addProperty("package",id);resources.put(path,named.toString());
        }
        var catalog=MaterialCatalog.parse(resources);assertEquals(id,FluidMaterialCatalog.networkPackage(catalog));return catalog;
    }
    /** The network package under another id, its compounds and records unchanged; the old id names no package. */
    static MaterialCatalog renamed() {
        var catalog=network("icheme:network_renamed",ids->ids);
        assertThrows(IllegalArgumentException.class,()->catalog.requirePackage(PACKAGE));
        assertEquals(CATALOG.fluidRevision(PACKAGE),catalog.fluidRevision("icheme:network_renamed"),"the same physics under another id");
        return catalog;
    }
    /** The network package without {@code compound}. */
    static MaterialCatalog lacking(String compound){return network(PACKAGE,ids->{assertTrue(ids.remove(compound),compound);return ids;});}
    private static FluidThermodynamics networkModel(MaterialCatalog catalog){return FluidThermodynamics.forNetwork(catalog,FluidMaterialCatalog.networkPackage(catalog));}

    /**
     * A world with no islands whose state format and basis change together: the load names the state format, and its facts
     * restate onto the current basis all the same, the ledger's totals mapped by compound id onto the current network
     * package, whether that package was renamed or lost a compound (VARYING_COMPONENTS_REVIEW, W4; the inferred failure at
     * {@code FluidFacts.rebuild}, which took the saved basis whenever the package table had no island to flag a change).
     */
    @Test void aWorldWithNoIslandsRestatesOntoTheCurrentBasisWhenItsStateFormatChangesToo() {
        var basis=WorldTopologyLedger.FluidBasis.capture(CATALOG);var ids=basis.components();
        double[] built=new double[ids.size()],gone=new double[ids.size()];
        built[ids.indexOf("Nitrogen")]=5;built[ids.indexOf("Propane")]=2;built[ids.indexOf("Water")]=1;gone[ids.indexOf("Propane")]=.5;
        var saved=new WorldTopologyLedger.Snapshot(1200,7,Map.of(),List.of(),new WorldTopologyLedger.MaterialTotal(built,-4e5),new WorldTopologyLedger.MaterialTotal(gone,-1e5),basis);
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(new FluidCheckpointCodec.Checkpoint(List.of()),Optional.of(saved),saved.onlineTick(),key->key.on(model)));
        var image=edited(store.image(),core->core.putInt("StateFormat",FluidCheckpointCodec.VERSION+1));
        for(var catalog:List.of(lacking("Propane"),renamed())) {
            var current=networkModel(catalog);
            var loaded=load(image,key->key.on(current),catalog);
            assertEquals(FluidCheckpointStore.Route.FACTS,loaded.route());assertTrue(loaded.reason().contains("state format "+(FluidCheckpointCodec.VERSION+1)),loaded.reason());
            assertTrue(loaded.checkpoint().islands().isEmpty());
            var ledger=loaded.world().orElseThrow();ledger.basis().requireCurrent(catalog);
            assertEquals(WorldTopologyLedger.FluidBasis.capture(catalog),ledger.basis(),"the world names the current network package");
            var now=ledger.basis().components();
            for(int c=0;c<ids.size();c++) {
                int to=now.indexOf(ids.get(c));
                if(to>=0){assertEquals(built[c],ledger.constructed().moles()[to],ids.get(c)+" constructed");assertEquals(gone[c],ledger.destroyed().moles()[to],ids.get(c)+" destroyed");}
            }
            assertEquals(saved.constructed().totalEnergy(),ledger.constructed().totalEnergy());assertEquals(saved.nextIdentity(),ledger.nextIdentity());
        }
    }

    /**
     * The world basis is checked by compound ids and fluid identity, never by package id (corpus image
     * {@code compat/fluid/compound-basis/}: a vessel of nitrogen, methane and propane written at state format 30 on
     * {@code icheme:tjl20_methane_nitrogen}). Under a catalog whose network package has another id, its compounds and
     * records unchanged, the image loads as it loads under the bundled one, by the same route to the same contents, and
     * names the current package; a save of this build's data keeps its state there. Under a catalog whose package lacks
     * propane, the world restates: the propane leaves, booked by id and named on the island's status line, and every
     * other amount is kept. When the image is missing the test writes it under {@code build/compat/fluid/}.
     */
    @Test void theWorldBasisIsCheckedByCompoundIdsNotByPackageId() throws IOException {
        var folder=CORPUS.resolve("compound-basis");
        if(!Files.isDirectory(folder)) {
            var written=Path.of("build/compat/fluid/compound-basis");write(image(propaneWorld()),written);
            fail("No compound-basis corpus image: copy "+written.toAbsolutePath()+" to "+folder);
        }
        var image=read(folder);var renamedCatalog=renamed();var renamedModel=networkModel(renamedCatalog);
        var bundled=load(image,key->key.on(model),CATALOG);var renamed=load(image,key->key.on(renamedModel),renamedCatalog);
        assertEquals(bundled.route(),renamed.route(),renamed.reason());
        assertEquals(contents(bundled.checkpoint()),contents(renamed.checkpoint()),"every vessel, bit for bit");
        assertEquals(WorldTopologyLedger.FluidBasis.capture(renamedCatalog),renamed.world().orElseThrow().basis(),"the world names the current network package");
        assertEquals(bundled.world().orElseThrow().restated(),renamed.world().orElseThrow().restated());
        // A save of this build's data keeps its state under the renamed package, and saves again under the new id.
        var fresh=image(propaneWorld());var store=FluidCheckpointStore.memory(fresh);var kept=store.load(fresh.core(),key->key.on(renamedModel),renamedCatalog);
        assertEquals(FluidCheckpointStore.Route.EXACT,kept.route(),kept.reason());
        assertEquals(contents(load(fresh,key->key.on(model),CATALOG).checkpoint()),contents(kept.checkpoint()));
        assertEquals("icheme:network_renamed",kept.world().orElseThrow().basis().packageId());
        // The world saves its islands as the authority does, on the current package: every unit is written again under the
        // new id (a unit is reused only for the package it names), and the save loads exactly.
        var islands=new FluidCheckpointCodec.Checkpoint(kept.checkpoint().islands().stream().map(e->new FluidCheckpointCodec.IslandEntry(e.dimension(),"icheme:network_renamed",e.compounds(),e.snapshot())).toList());
        store.commit(store.prepare(islands,kept.world(),FluidCheckpointCodec.epoch(fresh.core()),key->key.on(renamedModel)));var again=store.image();
        var resaved=FluidCheckpointCodec.readCore(again.core());
        assertEquals(List.of("icheme:network_renamed"),resaved.packages().stream().map(FluidCheckpointCodec.PackageRecord::packageId).toList());
        var t=resaved.topology();var topologyPack=again.packs().get(t.pack());
        assertEquals("icheme:network_renamed",FluidCheckpointCodec.worldFacts(topologyPack,FluidCheckpointCodec.topologySections(topologyPack,(int)t.offset(),t.length(),resaved.topologyGeneration())).basis().packageId());
        var reloaded=load(again,key->key.on(renamedModel),renamedCatalog);
        assertEquals(FluidCheckpointStore.Route.EXACT,reloaded.route(),reloaded.reason());assertEquals(contents(kept.checkpoint()),contents(reloaded.checkpoint()));

        // Without propane: it leaves the world, booked by id; every other amount is kept.
        var lackingCatalog=lacking("Propane");var lackingModel=networkModel(lackingCatalog);var core=FluidCheckpointCodec.readCore(image.core());
        var saved=new TreeMap<Long,FluidFacts.Vessel>();List<String> axis=null;double propane=0;
        for(var row:core.islands()){var pack=image.packs().get(row.unit().pack());var facts=FluidCheckpointCodec.islandFacts(pack,FluidCheckpointCodec.islandSections(pack,row),row.id());
            axis=facts.components();for(var v:facts.vessels()){saved.put(v.id(),v);propane+=v.moles()[axis.indexOf("Propane")];}}
        assertTrue(propane>1,"the image's vessels hold propane: "+propane);
        var restated=load(image,key->key.on(lackingModel),lackingCatalog);
        assertEquals(FluidCheckpointStore.Route.FACTS,restated.route());
        var ledger=restated.world().orElseThrow();assertEquals(WorldTopologyLedger.FluidBasis.capture(lackingCatalog),ledger.basis());
        assertEquals(Map.of("Propane",propane),ledger.restated().removedMoles(),"the ledger books the removed propane by id");
        var now=lackingModel.components();int vessels=0;
        for(var entry:restated.checkpoint().islands()) {
            assertTrue(entry.snapshot().status().contains("removed by the property data: Propane "+propane+" mol"),entry.snapshot().status());
            for(var n:entry.snapshot().graph().reservoirs()) {
                var v=saved.get(n.id());if(v==null)continue;vessels++;var held=IslandSets.amounts(n.inventory().moles(),entry.key().on(lackingModel),lackingModel);
                for(int c=0;c<axis.size();c++){int to=now.indexOf(axis.get(c));if(to>=0)assertEquals(v.moles()[c],held[to],"vessel "+n.id()+": "+axis.get(c)+" kept");}
            }
        }
        assertEquals(saved.size(),vessels);
    }
    /** A 2 m3 vessel charged from a generator of 70 % nitrogen, 20 % methane and 10 % propane at 300 K and 5 bar, the generator then removed. */
    private EventPathWorld propaneWorld() {
        var world=new EventPathWorld();var ids=WorldTopologyLedger.FluidBasis.capture(CATALOG).components();var n=new double[ids.size()];
        n[ids.indexOf("Nitrogen")]=.7;n[ids.indexOf("Methane")]=.2;n[ids.indexOf("Propane")]=.1;
        long generator=world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,300,5e5,n),.05);
        for(int x=1;x<=2;x++)world.place(x,64,0,Kind.PIPE,Direction.EAST,.05);
        world.place(3,64,0,Kind.RESERVOIR,Direction.EAST,new FluidDeviceSpec(2,298.15,101325,FluidDeviceSpec.nitrogen(CATALOG).composition()),.05);
        world.runTo(400);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(generator,null)),world.ledger.nextIdentity(),null);world.runTo(600);
        world.assertKeptUp("the propane line");
        return world;
    }

    /** A facts section is read by every later build: one this build cannot read refuses the world, its files kept. */
    /**
     * W3 (plan section 5.2): the heat an exchanger holds in transit is a fact of its sides. A save writes the coordinator's
     * value into the side with the lower identity and 0 into the other; a load reads the two back as their sum; the facts
     * round-trip it. A removed unit books it as destroyed energy.
     */
    @Test void anExchangersHeatInTransitIsAFactOfItsSides() {
        var world=corpusWorld();var saved=world.ledger.snapshot();
        // The tube that holds the transit: the corpus's second condenser shell (schema 8) holds none.
        long tube=saved.active().values().stream().filter(r->r.device().heat()!=null&&r.device().heat().side()==HeatSides.Side.TUBE&&r.device().heat().transitJoule()!=0).findFirst().orElseThrow().device().id();
        long shell=saved.active().get(tube).device().heat().partner(),pair=PhysicalFluidTopology.sideIdentity(Math.min(tube,shell));
        assertEquals(Map.of(pair,1234.5),saved.heatTransits(),"a load reads the transit the facts carry");
        var written=saved.withHeatTransits(Map.of(pair,-42.25));
        assertEquals(-42.25,written.active().get(Math.min(tube,shell)).device().heat().transitJoule(),"the lower side holds it");
        assertEquals(0,written.active().get(Math.max(tube,shell)).device().heat().transitJoule(),"the other side 0");
        assertSame(written,written.withHeatTransits(written.heatTransits()),"an unchanged transit writes nothing new");
        var decoded=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(written),written.onlineTick(),key->key.on(model));
        assertEquals(Map.of(pair,-42.25),decoded.heatTransits(),"the facts round-trip it");
        double before=world.ledger.snapshot().destroyed().totalEnergy();world.ledger.removedHeat(-42.25);
        assertEquals(before-42.25,world.ledger.snapshot().destroyed().totalEnergy(),1e-9,"a removed unit books it as destroyed energy");
    }
    @Test void aFactsSectionThisBuildCannotReadRefusesTheWorld() {
        var world=world();var image=image(world);long island=world.coordinator.snapshots().getFirst().id();
        var unit=CheckpointImages.unitBytes(image,island);
        // The facts schema follows the 30-byte header: a later schema was written by a newer build.
        var later=unit.clone();later[FluidCheckpointCodec.UNIT_HEADER]=(byte)(FluidFacts.SCHEMA+1);
        var refusal=assertThrows(FluidCheckpointCodec.Unreadable.class,()->load(CheckpointImages.withUnit(image,island,later),key->key.on(model),CATALOG)).getMessage();
        assertTrue(refusal.contains("facts schema "+(FluidFacts.SCHEMA+1))&&refusal.contains("newer build"),refusal);
        // A device kind this build does not know (a renamed constant without its alias) refuses the world too.
        var topology=image.core().getCompound("Topology");var pack=image.packs().get(topology.getLong("Pack"));int offset=(int)topology.getLong("Offset");
        var bytes=CheckpointImages.renamed(Arrays.copyOfRange(pack,offset,offset+topology.getInt("Length")),"RESERVOIR","RESERVOIX");
        var packs=new HashMap<>(image.packs());var copy=pack.clone();System.arraycopy(bytes,0,copy,offset,bytes.length);packs.put(topology.getLong("Pack"),copy);
        var core=image.core().copy();core.getCompound("Topology").putByteArray("SHA256",FluidCheckpointCodec.sha256(bytes));CheckpointImages.reseal(core);
        refusal=assertThrows(FluidCheckpointCodec.Unreadable.class,()->load(new FluidCheckpointCodec.Image(core,packs),key->key.on(model),CATALOG)).getMessage();
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
    @Test void towerSettingsSurviveBothExactAndFactsOnlyLoads(){
        var world=corpusWorld();var saved=image(world);
        for(var restored:List.of(load(saved,key->key.on(model),CATALOG),load(edited(saved,tag->tag.putInt("StateFormat",Integer.MAX_VALUE)),key->key.on(model),CATALOG))){
            var towers=restored.world().orElseThrow().active().values().stream().map(r->r.spec().tower()).filter(java.util.Objects::nonNull).toList();
            assertEquals(List.of(CoolingTower.induced(8,2,3,2).withControl(CoolingTower.Mode.TARGET_TEMPERATURE,.4,299)),towers);
        }
    }

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
                var rebuilt=load(edited(image,core->core.putInt("StateFormat",Integer.MAX_VALUE)),key->key.on(model),CATALOG);
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
        var restated=load(image,key->key.on(model),CATALOG);
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
        var reloaded=new EventPathWorld(1,ledger,restated.checkpoint().islands());
        assertEquals(FluidCheckpointStore.Route.EXACT,load(image(reloaded),key->key.on(model),CATALOG).route(),"restated once");
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
        var restated=load(image,key->key.on(model),CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,restated.route());
        assertTrue(restated.reason().contains("property data of package "+PACKAGE+" changed"),restated.reason());
        var ledger=restated.world().orElseThrow();
        assertTrue(ledger.restated().removedMoles().isEmpty()&&ledger.restated().removedSolids().isEmpty());
        double energy=0,residual=0;int vessels=0;
        for(var e:restated.checkpoint().islands())for(var n:e.snapshot().graph().reservoirs()) {
            if(n.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;
            var v=saved.get(n.id());var s=n.state();vessels++;
            assertArrayEquals(v.moles(),IslandSets.amounts(n.inventory().moles(),e.key().on(model),model),"vessel "+n.id()+": its moles are kept exactly");
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
        var reloaded=new EventPathWorld(1,ledger,restated.checkpoint().islands());
        assertEquals(FluidCheckpointStore.Route.EXACT,load(image(reloaded),key->key.on(model),CATALOG).route(),"restated once");
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
        var loaded=load(image,key->key.on(model),CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,loaded.route());
        assertTrue(loaded.reason().contains("property data of package "+PACKAGE+" changed"),loaded.reason());
        var ledger=loaded.world().orElseThrow();
        assertEquals(Integer.parseInt(expected.getProperty("registrations"))-Integer.parseInt(expected.getProperty("movers")),ledger.active().size(),"every other registration kept");
        assertTrue(ledger.active().values().stream().noneMatch(r->r.device().kind()==Kind.PUMP||r.device().kind()==Kind.COMPRESSOR),"no mover left");
        double total=Arrays.stream(moles(loaded.checkpoint(),model.componentCount())).sum();
        assertEquals(Double.parseDouble(expected.getProperty("moles")),total,1e-12*total,"every vessel keeps its stock");
        assertEquals(Long.parseLong(expected.getProperty("vessels")),contents(loaded.checkpoint()).size());
        var reloaded=new EventPathWorld(1,ledger,loaded.checkpoint().islands());
        assertEquals(FluidCheckpointStore.Route.EXACT,load(image(reloaded),key->key.on(model),CATALOG).route(),"opened once");
    }
    private void write(FluidCheckpointCodec.Image image,Path folder) throws IOException {
        Files.createDirectories(folder);NbtIo.writeCompressed(image.core(),folder.resolve("core.dat"));
        for(var pack:image.packs().entrySet())Files.write(folder.resolve("pack-"+pack.getKey()+".bin"),pack.getValue());
        var loaded=load(image,key->key.on(model),CATALOG);var props=new Properties();
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
            text.append(u.id()).append(":").append(u.running()).append(":").append(u.setpoints().feedRate()).append(":").append(u.totals().taken().total()).append(":condenser=").append(u.condenser());
            // Schema 8: the further water sides, written only where there are some, as an older image has none.
            if(u.condensers().size()>1)text.append(":condensers=").append(u.condensers());
            // Schema 9: the air condenser's surface, written only where there is one, as an older image has none.
            if(u.airSurface()>0)text.append(":air=").append(u.airSurface());
            for(var s:u.stations())text.append(":").append(s.port()).append(s.index()).append("=").append(s.device()).append("/").append(s.valve()).append("/").append(s.account().total()).append("/").append(s.account().energy()).append("/").append(s.solids().massKg());
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
