package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.SolidMaterialCatalog.SolidMaterial;
import net.minecraft.nbt.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The checkpoint of one island: round trip, field refusals, a changed basis, energy datum or velocity limit, and
 * solids read back as their records (crystals beside custom solids). */
class FluidCheckpointCodecTest {
    private static final String PACKAGE="icheme:tjl20_methane_nitrogen";
    private final FluidThermodynamics model=FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE);
    private FluidCheckpointCodec.Checkpoint fixture() {
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(1,298.15,101325,()->{}))),List.of());
        var full=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var state=new IslandCoordinator.Snapshot(1,4,full.graph(),new IslandClock.Snapshot(350,200,400,100),Optional.of(full),"FULL",Map.of(UUID.randomUUID(),300L));
        return new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,state)));
    }
    private static CompoundTag islands(CompoundTag core){return core.getCompound("Islands");}
    static void assertSameImage(FluidCheckpointCodec.Image a,FluidCheckpointCodec.Image b,String what) {
        assertEquals(a.core(),b.core(),what+": core record");assertEquals(a.packs().keySet(),b.packs().keySet(),what+": packs");
        for(var pack:a.packs().keySet())assertArrayEquals(a.packs().get(pack),b.packs().get(pack),what+": pack "+pack);
    }
    private static FluidCheckpointCodec.Image copy(FluidCheckpointCodec.Image image){return new FluidCheckpointCodec.Image(image.core().copy(),image.packs());}

    @Test void roundTripRetainsStockDebtAndFences() {
        var before=fixture();var image=FluidCheckpointCodec.encode(before,key->model);
        assertEquals(FluidCheckpointCodec.ENVELOPE,image.core().getInt("Envelope"));assertEquals(FluidCheckpointCodec.VERSION,image.core().getInt("StateFormat"));assertEquals(350,FluidCheckpointCodec.epoch(image.core()),"epoch of a core-only checkpoint: its most advanced island");
        var loaded=FluidCheckpointCodec.decode(image,key->model);var a=before.islands().getFirst().snapshot();var b=loaded.islands().getFirst().snapshot();
        assertEquals(a.graph().reservoirs().getFirst().inventory(),b.graph().reservoirs().getFirst().inventory());assertEquals(a.clock(),b.clock());assertEquals(a.fences(),b.fences());
        assertEquals(a.revision(),b.revision());assertEquals(a.status(),b.status());
        assertSameImage(image,FluidCheckpointCodec.encode(loaded,key->model),"a reloaded checkpoint re-encodes to the same bytes");
    }
    /** Format 12: a drum's level height (batch 2026-10-15-custom-equipment-rendering) is the vessel's identity and comes back. */
    @Test void aVesselsLevelHeightRoundTrips() {
        var charge=new PassiveNetwork.Reservoir(1,0,model.initialNitrogenCharge(2,298.15,101325,()->{}));var i=charge.inventory();
        var tall=new PassiveNetwork.Reservoir(1,0,charge.state(),charge.kind(),new PassiveNetwork.Inventory(i.volume(),i.moles(),i.internalEnergy(),i.solids(),4));
        var full=new PassiveIntervalSolver(model).solve(new PassiveNetwork(List.of(tall),List.of()),5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var state=new IslandCoordinator.Snapshot(1,4,full.graph(),new IslandClock.Snapshot(350,200,400,100),Optional.of(full),"FULL");
        var before=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,state)));
        var image=FluidCheckpointCodec.encode(before,key->model);var loaded=FluidCheckpointCodec.decode(image,key->model);
        var inventory=loaded.islands().getFirst().snapshot().graph().reservoirs().getFirst().inventory();
        assertEquals(4,inventory.levelHeight());assertEquals(full.graph().reservoirs().getFirst().inventory(),inventory);
        assertSameImage(image,FluidCheckpointCodec.encode(loaded,key->model),"a reloaded checkpoint re-encodes to the same bytes");
    }
    /** Format 24: a connection's heat side (batch 2026-10-27-heat-exchanger-design, W5) comes back with its facts and its
     * slice inputs, so a restored island moves the heat its graph did; a plain connection comes back without one. Format 27
     * (W4): with its controller and planned limit, and an air cooler's side with its air, fans and controller. Format 28
     * (W7): a vessel's kettle pool with its facts and slice inputs (here as the slice planned it without a read of its tubes:
     * idle at the vessel's temperature). */
    @Test void aHeatSideRoundTrips() {
        var charge=model.initialNitrogenCharge(1,298.15,101325,()->{});
        var side=new HeatSide(57,new HeatCurve.Films(430,487,85),new double[]{298.15,300.5},.25,-12.5,0,1,
                new HeatControl(HeatControl.Target.OUTLET_TEMPERATURE,320,280,.75),HeatControl.Limit.HIGHEST);
        var cooled=new AirCooled(90,com.wormzjl.icheme.science.equipment.HeatSides.AIR,.4,new HeatControl(HeatControl.Target.DUTY,5e5,0,1),HeatControl.Limit.LOWEST);
        var pool=new Pool(PhysicalFluidTopology.sideIdentity(21),PhysicalFluidTopology.sideIdentity(20),56,.15,.5,new double[]{340,338,336,334,332,330,328,326},12.5,.5);
        var heated=new PassiveNetwork.Reservoir(4,0,charge,PassiveNetwork.NodeKind.RESERVOIR,new PassiveNetwork.Reservoir(4,0,charge).inventory().withPool(pool));
        var graph=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1,0,charge),new PassiveNetwork.Reservoir(2,0,charge),new PassiveNetwork.Reservoir(3,0,charge),heated),
                List.of(new PassiveNetwork.Pipe(9,0,1,new PipeResistance.Geometry(12,.0212,1.5e-6,1.5,174)).withHeat(side),new PassiveNetwork.Pipe(10,1,2,new PipeResistance.Geometry(1,.05,4.5e-5,0)),
                        new PassiveNetwork.Pipe(11,2,3,new PipeResistance.Geometry(6,.0212,1.5e-6,1.5,174)).withHeat(cooled)));
        var full=new PassiveIntervalSolver(model).solve(graph,5,PassiveIntervalSolver.Settings.defaults(),()->{});
        var state=new IslandCoordinator.Snapshot(1,4,full.graph(),new IslandClock.Snapshot(350,200,400,100),Optional.of(full),"FULL");
        var image=FluidCheckpointCodec.encode(new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,state))),key->model);
        var loaded=FluidCheckpointCodec.decode(image,key->model);var pipes=loaded.islands().getFirst().snapshot().graph().pipes();
        assertEquals(side,pipes.getFirst().heat());assertNull(pipes.get(1).heat());
        assertEquals(full.graph().pipes().getLast().heat(),pipes.getLast().heat());assertEquals(.4,((AirCooled)pipes.getLast().heat()).draught(),"no flow, the louvers and fans stand");
        assertEquals(174,pipes.getFirst().sections().getFirst().channels());
        var planned=full.graph().reservoirs().getLast().inventory().pool();
        assertEquals(pool.shape(),planned.shape());assertEquals(0,planned.active(),"no read of its tubes: the pool moves no heat");
        assertEquals(planned,loaded.islands().getFirst().snapshot().graph().reservoirs().getLast().inventory().pool(),"the pool comes back");
        assertNull(loaded.islands().getFirst().snapshot().graph().reservoirs().getFirst().inventory().pool());
        assertSameImage(image,FluidCheckpointCodec.encode(loaded,key->model),"a reloaded checkpoint re-encodes to the same bytes");
    }
    @Test void missingClocksMistypedStampsCorruptionUnknownVersionsAndChangedPropertiesAreRejected() {
        var image=FluidCheckpointCodec.encode(fixture(),key->model);
        var missing=copy(image);islands(missing.core()).remove("Committed");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(missing,key->model)).getMessage().contains("field Committed"));
        var mistyped=copy(image);islands(mistyped.core()).putDouble("Revision",1.5);
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(mistyped,key->model)).getMessage().contains("field Revision"));
        var corrupt=copy(image);islands(corrupt.core()).getLongArray("Committed")[0]=250;
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(corrupt,key->model)).getMessage().contains("checksum"),"an unsealed change is caught by the envelope digest");
        var packs=new HashMap<>(image.packs());var bytes=packs.get(1L).clone();bytes[bytes.length/2]^=1;packs.put(1L,bytes);
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(new FluidCheckpointCodec.Image(image.core(),packs),key->model)).getMessage().contains("checksum"),"a flipped unit byte");
        // A newer build's envelope or facts schema: unreadable, so the world is refused with its files kept.
        var envelope=copy(image);envelope.core().putInt("Envelope",2);
        assertTrue(assertThrows(FluidCheckpointCodec.Unreadable.class,()->FluidCheckpointCodec.decode(envelope,key->model)).getMessage().contains("envelope 2"));
        var schema=copy(image);schema.core().putInt("FactsSchema",999);CheckpointImages.reseal(schema.core());
        assertTrue(assertThrows(FluidCheckpointCodec.Unreadable.class,()->FluidCheckpointCodec.decode(schema,key->model)).getMessage().contains("facts schema 999"));
        // Another state format rebuilds from facts, which an image of islands alone does not carry (FluidFactsCompatTest
        // rebuilds a world image).
        var state=copy(image);state.core().putInt("StateFormat",FluidCheckpointCodec.VERSION+1);CheckpointImages.reseal(state.core());
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(state,key->model)).getMessage().contains("no topology unit"));
    }
    /** A changed thermodynamic basis restates the world from its facts (FluidFactsCompatTest); an image of islands alone
     * has no registry to rebuild them from, so it is refused, and its stock is never read on the new basis. */
    @Test void aChangedThermodynamicBasisRebuildsFromFactsAndNeverReinterpretsStock() {
        var image=FluidCheckpointCodec.encode(fixture(),key->model);
        var retired=copy(image);
        ((ListTag)retired.core().get("Packages")).getCompound(0).putString("PropertyRevision","fluid-trbdf2-r1:fluid-nitrogen-r1:7781afaaad17de4926015ebf321c0b106766dd5c131668f54962a8825ed25bd5:fluid-shared-k-v1:nist-liquid-calibration-20260915:k=0x1.12e0be826d695p-30:cb6773ed32b52aefb268f9c5610e288c8cecdbc6c682522b54d0be703c92b533:log-liquid-wilke-v1:dwsim-conditional-solute-v1");
        CheckpointImages.reseal(retired.core());
        var refusal=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(retired,key->model));
        assertTrue(refusal.getMessage().contains("property data of package "+PACKAGE+" changed")&&refusal.getMessage().contains("no topology unit"),refusal.getMessage());
        for(String field:List.of("ideal_gas_cp","viscosity")) {
            var resources=new HashMap<>(MaterialCatalog.bundled().resources());
            String path="data/icheme/materials/properties/crude_pc04.json";
            var property=com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();
            var correlation=property.getAsJsonObject(field);
            if(field.equals("viscosity"))correlation=correlation.getAsJsonObject("liquid");
            var coefficients=correlation.getAsJsonArray("coefficients");
            coefficients.set(0,new com.google.gson.JsonPrimitive(coefficients.get(0).getAsDouble()*1.001));
            // Nothing is hashed (owner decisions 2026-10-26): the edit declares itself with the revision of what it changes,
            // the viscosity's own or the property record's.
            var declared=field.equals("viscosity")?correlation:property;
            declared.addProperty("revision",declared.get("revision").getAsString()+"-edited");
            resources.put(path,property.toString());
            var overridden=FluidThermodynamics.forNetwork(MaterialCatalog.parse(resources),PACKAGE);
            assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(image,key->overridden),field).getMessage().contains("property data of package"),field);
        }
    }
    /** An energy datum change is a basis change: the saved energies are on another reference, so they are never read. */
    @Test void anEnergyDatumChangeRestatesAndNeverReadsTheSavedEnergy() {
        var image=FluidCheckpointCodec.encode(fixture(),key->model);
        var revision=copy(image);var entry=((ListTag)revision.core().get("Packages")).getCompound(0);entry.putString("EnergyRevision",entry.getString("EnergyRevision")+"-offset");CheckpointImages.reseal(revision.core());
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(revision,key->model)).getMessage().contains("property data of package"));
        var components=copy(image);((ListTag)((ListTag)components.core().get("Packages")).getCompound(0).get("EnergyComponents")).remove(0);CheckpointImages.reseal(components.core());
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(components,key->model)).getMessage().contains("property data of package"));
    }
    /** The topology unit: every field of the world ledger comes back exactly and re-encodes to the same bytes. */
    @Test void aTopologyRoundTripsEveryFieldExactlyAndRefusesAMalformedUnit() {
        var empty=com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger.Snapshot.empty(MaterialCatalog.bundled());int count=empty.basis().components().size();
        var nitrogen=new double[count];nitrogen[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var mixed=new double[count];for(int c=0;c<count;c++)mixed[c]=c+1;
        var slurry=new SlurryFeed(.1,List.of(new SlurryFeed.Grade("icheme:demo_particle",com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("100"),1)));
        var block=new PipeResistance.Geometry(1,.05,.000045,0);var wide=new PipeResistance.Geometry(2,.1,.0001,.5);
        java.util.function.Function<Object[],WorldTopologyLedger.Registration> device=a->new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device((long)a[0],
                new PhysicalFluidTopology.Position((String)a[1],(int)a[2],64,-3),(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind)a[3],PhysicalFluidTopology.Direction.EAST,(PipeResistance.Geometry)a[4],(FlowControl)a[5]),
                (FluidDeviceSpec)a[6],(long)a[7]);
        var kinds=com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.class.getEnumConstants();
        var reservoir=device.apply(new Object[]{1L,"minecraft:overworld",0,kinds[0],block,new FlowControl.Passive(),new FluidDeviceSpec(2,300,150000,nitrogen),0L});
        var pump=device.apply(new Object[]{2L,"minecraft:overworld",1,com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,block,new FlowControl.Pump(.01,500000),new FluidDeviceSpec(1,298.15,101325,nitrogen),0L});
        var valve=device.apply(new Object[]{3L,"minecraft:overworld",2,com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.VALVE,wide,new FlowControl.PressureValve(120000),new FluidDeviceSpec(1,298.15,101325,nitrogen),0L});
        var pipe=device.apply(new Object[]{4L,"minecraft:the_nether",5,com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,wide,new FlowControl.Passive(),new FluidDeviceSpec(1,298.15,101325,mixed),0L});
        var generator=device.apply(new Object[]{5L,"minecraft:overworld",7,com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.GENERATOR,block,new FlowControl.Passive(),new FluidDeviceSpec(1,320,180000,mixed,slurry),0L});
        var retuned=device.apply(new Object[]{3L,"minecraft:overworld",2,com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.VALVE,wide,new FlowControl.PressureValve(130000),new FluidDeviceSpec(1,298.15,101325,nitrogen),1L});
        var player=UUID.randomUUID();
        var events=List.of(new WorldTopologyLedger.Event(UUID.randomUUID(),10,List.of(new WorldTopologyLedger.Edit(3,retuned),new WorldTopologyLedger.Edit(4,null)),Set.of(2L,3L,4L)),
                new WorldTopologyLedger.Event(UUID.randomUUID(),12,List.of(new WorldTopologyLedger.Edit(2,null)),Set.of(2L),new WorldTopologyLedger.Recovery(2,player)));
        // A recovery's solids are read back as records of the basis package, so its particles are the catalog's.
        var particle=MaterialCatalog.bundled().solids().require("icheme:demo_particle");
        var solids=new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(particle,com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("100"),.25)));
        var recoveries=Map.of(UUID.randomUUID(),new WorldTopologyLedger.RecoveredSolid(new PhysicalFluidTopology.Position("minecraft:overworld",3,65,-3),player,solids,12.5),
                UUID.randomUUID(),new WorldTopologyLedger.RecoveredSolid(new PhysicalFluidTopology.Position("minecraft:the_nether",9,70,1),null,solids,-4));
        var moles=new double[count];moles[0]=3.5;moles[count-1]=-0.0+1e-9;
        var constructed=new WorldTopologyLedger.MaterialTotal(moles,4.25e6,Map.of("icheme:demo_particle",.75));
        var world=new WorldTopologyLedger.Snapshot(12,9,Map.of(1L,reservoir,2L,pump,3L,valve,4L,pipe,5L,generator),events,constructed,WorldTopologyLedger.MaterialTotal.empty(count),empty.basis(),recoveries);
        var bytes=FluidCheckpointCodec.encodeTopology(world);var decoded=FluidCheckpointCodec.decodeTopology(bytes,77,key->model);
        assertEquals(77,decoded.onlineTick());assertEquals(9,decoded.nextIdentity());
        assertEquals(world.active(),decoded.active(),"devices, positions, kinds, geometries, controls, specs, slurries, revisions");
        assertEquals(world.events(),decoded.events(),"events: edits, removals, touched sets, recovery");
        assertArrayEquals(world.constructed().moles(),decoded.constructed().moles());assertEquals(world.constructed().totalEnergy(),decoded.constructed().totalEnergy());
        assertEquals(world.constructed().solidMasses(),decoded.constructed().solidMasses());assertArrayEquals(world.destroyed().moles(),decoded.destroyed().moles());
        assertEquals(world.basis(),decoded.basis());assertEquals(world.recoveries(),decoded.recoveries());
        assertArrayEquals(bytes,FluidCheckpointCodec.encodeTopology(decoded),"a decoded topology re-encodes to the same bytes");
        // The basis identity (a package's composed revisions, about 3.3 KB of ASCII) is stored once per world.
        assertTrue(bytes.length-world.basis().revision().length()<2_500,"the facts and events of five devices: "+bytes.length+" bytes with the basis");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(Arrays.copyOf(bytes,bytes.length+1),0,key->model)).getMessage().contains("trailing bytes"));
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(Arrays.copyOf(bytes,bytes.length/2),0,key->model));
    }
    /** A saved island stays readable under another velocity limit: the limit is in the model's revision (a certificate
     * issued under the old one is not kept, FluidCheckpointFormatTest) but not in its thermodynamic one, which the
     * package table holds. Restated without the approximation anchor (owner decision D37, S2). */
    @Test void changingVelocityLimitPreservesSavedStockAndClocks() {
        var before=fixture();var image=FluidCheckpointCodec.encode(before,key->model);
        var loaded=FluidCheckpointCodec.decode(image,key->FluidThermodynamics.forNetwork(MaterialCatalog.bundled(),PACKAGE,10, FluidThermodynamics.DEFAULT_TRACE_CUTOFF_MOLE_FRACTION));
        var a=before.islands().getFirst().snapshot();var b=loaded.islands().getFirst().snapshot();
        assertEquals(a.graph().reservoirs().getFirst().inventory(),b.graph().reservoirs().getFirst().inventory());assertEquals(a.clock(),b.clock());assertEquals(a.fences(),b.fences());
    }

    // ---------------- format 6: pipe ports and the compressor (phase-ports WP5, plan 4.4) ----------------

    private int component(String name){int i=model.components().indexOf(name);assertTrue(i>=0,name);return i;}
    private FluidThermodynamics.State waterUnderNitrogen(double waterVolume,double pressure) {
        double t=298.15;double[] mw=model.molecularWeights();double[] n=new double[mw.length];int water=component("Water");
        n[water]=waterVolume*997/mw[water];n[component("Nitrogen")]=pressure*(1-waterVolume)/(FluidThermodynamics.R*t);
        var unit=model.flashTP(t,pressure,n,()->{});for(int c=0;c<n.length;c++)n[c]/=unit.volume();
        return model.flashTP(t,pressure,n,()->{});
    }
    /**
     * An island whose pipes have every port kind and a compressor, solved for one interval in which the compressor, fed
     * the bulk of a half-water tank, is refused ({@code INLET_WRONG_PHASE}): graph, ports, controls, the last interval's
     * endpoint modes come back exactly, and the reloaded checkpoint re-encodes to the same bytes.
     */
    @Test void portsACompressorAndARefusedMoverRoundTripExactly() {
        var block=new PipeResistance.Geometry(1,.05,.000045,0);
        var nodes=List.of(new PassiveNetwork.Reservoir(1,0,waterUnderNitrogen(.5,150000)),new PassiveNetwork.Reservoir(2,0,model.initialNitrogenCharge(1,298.15,150000,()->{}),PassiveNetwork.NodeKind.JUNCTION),
                new PassiveNetwork.Reservoir(3,0,model.initialNitrogenCharge(1,298.15,101325,()->{}),PassiveNetwork.NodeKind.VOID));
        var pipes=List.of(new PassiveNetwork.Pipe(10,0,2,List.of(block),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.LIQUID,PassiveNetwork.PhasePort.BULK),
                new PassiveNetwork.Pipe(11,0,1,List.of(block),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK),
                new PassiveNetwork.Pipe(12,1,2,List.of(block),new FlowControl.Compressor(.01,2.5),0,null,PassiveNetwork.PhasePort.BULK,PassiveNetwork.PhasePort.BULK),
                new PassiveNetwork.Pipe(13,0,2,List.of(block),new FlowControl.Passive(),0,null,PassiveNetwork.PhasePort.VAPOR,PassiveNetwork.PhasePort.BULK));
        var full=new PassiveIntervalSolver(model).solve(new PassiveNetwork(nodes,pipes),5,PassiveIntervalSolver.Settings.defaults(),()->{});
        assertEquals(FlowControl.Mode.INLET_WRONG_PHASE,full.endpointModes().get(2),"the compressor on the tank's bulk is refused");
        var state=new IslandCoordinator.Snapshot(1,4,full.graph(),new IslandClock.Snapshot(350,200,400,100),Optional.of(full),"FULL");
        var before=new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PACKAGE,state)));
        var image=FluidCheckpointCodec.encode(before,key->model);assertEquals(FluidCheckpointCodec.VERSION,image.core().getInt("StateFormat"));
        var after=FluidCheckpointCodec.decode(image,key->model).islands().getFirst().snapshot();
        assertEquals(full.graph().pipes(),after.graph().pipes(),"ends, sections, controls, blocked masks and the ports of every pipe");
        assertEquals(PassiveNetwork.PhasePort.LIQUID,after.graph().pipes().get(0).firstPort());assertEquals(PassiveNetwork.PhasePort.VAPOR,after.graph().pipes().get(3).firstPort());
        assertEquals(new FlowControl.Compressor(.01,2.5),after.graph().pipes().get(2).control());
        assertEquals(full.endpointModes(),after.lastResult().orElseThrow().endpointModes(),"the refused mover's committed mode, the hysteresis input of the next slice (A38)");
        assertArrayEquals(full.averageMassFlows(),after.lastResult().orElseThrow().averageMassFlows());
        // Since format 7: the end flows the island's next job starts from round-trip bit for bit.
        assertArrayEquals(full.endpointFlows(),after.lastResult().orElseThrow().endpointFlows(),"the committed end flows (format 7)");
        assertSameImage(image,FluidCheckpointCodec.encode(FluidCheckpointCodec.decode(image,key->model),key->model),"a reloaded checkpoint re-encodes to the same bytes");
    }
    /** The world ledger's compressor registration comes back exactly; an unknown control kind is refused. */
    @Test void aCompressorRegistrationRoundTripsInTheTopologyUnit() {
        var empty=WorldTopologyLedger.Snapshot.empty(MaterialCatalog.bundled());int count=empty.basis().components().size();
        var nitrogen=new double[count];nitrogen[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN]=1;
        var device=new PhysicalFluidTopology.Device(4,new PhysicalFluidTopology.Position("minecraft:overworld",3,64,-3),com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,
                PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Compressor(.05,3));
        var world=new WorldTopologyLedger.Snapshot(12,9,Map.of(4L,new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(1,298.15,101325,nitrogen),2)),List.of(),
                WorldTopologyLedger.MaterialTotal.empty(count),WorldTopologyLedger.MaterialTotal.empty(count),empty.basis(),Map.of());
        var bytes=FluidCheckpointCodec.encodeTopology(world);var decoded=FluidCheckpointCodec.decodeTopology(bytes,12,key->model);
        assertEquals(world.active(),decoded.active());assertArrayEquals(bytes,FluidCheckpointCodec.encodeTopology(decoded));
        // The control is saved by its kind's name: a name this build does not know is refused.
        var corrupt=CheckpointImages.renamed(bytes,"compressor","compressar");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(corrupt,12,key->model)).getMessage().contains("unknown control compressar"),"an unknown control kind is refused");
    }

    // ---------------- solids name their records: crystals beside custom solids (D23, milestone M4) ----------------

    private static final String PILOT="icheme:pilot_cryogenic";
    /** One pilot vessel, 0.1 m3 of 10 % CO2 in N2 at 170 K and 1 MPa (below its 192.75 K frost point) with 1 kg of the
     * demo particle, after one 5 s interval: its refresh has deposited CO2-I beside the particles. */
    private static PassiveNetwork depositedVessel(FluidThermodynamics pilot) {
        double[] z={.9,0,0,.1,0};var unit=pilot.flashTP(170,1e6,z,()->{});double[] n=z.clone();for(int i=0;i<n.length;i++)n[i]*=.1/unit.volume();
        var particles=new SolidInventory(List.of(new SolidInventory.Population(pilot.solids.require("icheme:demo_particle"),ParticleSize.micrometres("100"),1)));
        var vessel=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(101,0,pilot.flashTP(170,1e6,n,()->{}).withSolids(particles))),List.of());
        return new PassiveIntervalSolver(pilot).solve(vessel,5,PassiveIntervalSolver.Settings.defaults(),()->{}).graph();
    }
    /** {@code graph} with its vessel holding {@code solids}: a stock the codec writes as it is given. */
    private static PassiveNetwork holding(PassiveNetwork graph,SolidInventory solids) {
        var node=graph.reservoirs().getFirst();var inventory=node.inventory();
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state().withSolids(solids),node.kind(),
                new PassiveNetwork.Inventory(inventory.volume(),inventory.moles(),inventory.internalEnergy(),solids))),graph.pipes());
    }
    private static SolidInventory replaced(SolidInventory solids,SolidMaterial from,SolidMaterial to) {
        return new SolidInventory(solids.populations().stream().map(p->p.material().equals(from)?new SolidInventory.Population(to,p.size(),p.massKg()):p).toList());
    }
    private static FluidCheckpointCodec.Checkpoint island(PassiveNetwork graph) {
        var state=new IslandCoordinator.Snapshot(1,4,graph,new IslandClock.Snapshot(350,200,400,100),Optional.empty(),"READY");
        return new FluidCheckpointCodec.Checkpoint(List.of(new FluidCheckpointCodec.IslandEntry("minecraft:overworld",PILOT,state)));
    }
    /**
     * Crystals ride the solids encoding (owner decision D23): a population's id names its record in the island's package,
     * a crystal it admits or a custom solid. An id naming no record of the package, or saved constants that are not the
     * record's, are refused with the fresh-world instruction; a deposited CO2-I comes back as the package's crystal
     * record (component and energy offset included) beside the custom particles, bit for bit, and re-encodes to the same
     * bytes. A crystal record and a custom solid never share an id, so an id names one record.
     */
    @Test void aCrystalIsReadBackAsItsRecordBesideACustomSolidAndAnIdNamingNoRecordIsRefused() {
        var pilot=FluidTestSupport.model(MaterialCatalog.bundled(),PILOT);
        var graph=depositedVessel(pilot);var solids=graph.reservoirs().getFirst().inventory().solids();
        assertEquals(1,solids.crystals().size(),"the refresh deposited CO2-I: "+solids);assertEquals(2,solids.populations().size(),"beside the particles: "+solids);
        var crystal=solids.crystals().getFirst().material();assertEquals("icheme:crystal_carbon_dioxide_i",crystal.id());
        var foreign=new SolidMaterial("icheme:crystal_methane_i",crystal.revision(),crystal.density(),crystal.heatCapacity(),new SolidMaterial.Crystal("Methane",crystal.crystal().energyOffset()));
        var unknown=FluidCheckpointCodec.encode(island(holding(graph,replaced(solids,crystal,foreign))),key->pilot);
        var refusal=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(unknown,key->pilot)).getMessage();
        assertTrue(refusal.contains("icheme:crystal_methane_i")&&refusal.contains(PILOT)&&refusal.contains("this data does not have"),refusal);
        var denser=new SolidMaterial(crystal.id(),crystal.revision(),crystal.density()*1.001,crystal.heatCapacity(),crystal.crystal());
        var changed=FluidCheckpointCodec.encode(island(holding(graph,replaced(solids,crystal,denser))),key->pilot);
        refusal=assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decode(changed,key->pilot)).getMessage();
        assertTrue(refusal.contains("Saved solid properties differ")&&refusal.contains(crystal.id()),refusal);
        var image=FluidCheckpointCodec.encode(island(graph),key->pilot);var loaded=FluidCheckpointCodec.decode(image,key->pilot);
        var back=loaded.islands().getFirst().snapshot().graph().reservoirs().getFirst().inventory();
        assertEquals(graph.reservoirs().getFirst().inventory(),back,"the vessel's stock, crystal and particles, bit for bit");
        assertEquals(crystal.crystal(),back.solids().crystals().getFirst().material().crystal(),"the crystal's component and energy offset");
        assertSameImage(image,FluidCheckpointCodec.encode(loaded,key->pilot),"a reloaded crystal checkpoint re-encodes to the same bytes");
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());
        resources.put("data/icheme/materials/solids/dry_ice.json","{\"schema_version\":1,\"id\":\""+crystal.id()+"\",\"revision\":\"r1\",\"density_kg_per_m3\":1562,\"heat_capacity_j_per_kg_kelvin\":1200}");
        assertTrue(assertThrows(IllegalArgumentException.class,()->MaterialCatalog.parse(resources)).getMessage().contains("a solid id names one record"));
    }
}
