package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldTopologyLedgerTest {
    /** The models a decoded topology reads its pending recoveries with: none of these ledgers has one. */
    private static final java.util.function.Function<FluidCheckpointCodec.PackageKey,FluidThermodynamics> NO_RECOVERIES=key->{throw new AssertionError("no pending recovery to read");};
    /** The queued events a batch may take, in queue order: those that meet no earlier queued event (identities and
     * positions), as PhysicalRegistry decides from the footprints. */
    private static List<WorldTopologyLedger.Event> ready(WorldTopologyLedger ledger) {
        var earlierIds=new HashSet<Long>();var earlierPositions=new HashSet<PhysicalFluidTopology.Position>();var ready=new ArrayList<WorldTopologyLedger.Event>();
        for(var footprint:ledger.footprints()) {
            if(!footprint.meets(earlierIds,earlierPositions))ready.add(footprint.event());
            earlierIds.addAll(footprint.ids());earlierPositions.addAll(footprint.positions());
        }
        return ready;
    }
    /** Stages the first queued event alone. */
    private static WorldTopologyLedger.Prepared applyFirst(WorldTopologyLedger ledger,Collection<PassiveNetwork.Reservoir> additions,Collection<PassiveNetwork.Reservoir> removals,double[] weights,long nextIdentity) {
        return ledger.applyBatch(List.of(ledger.events().getFirst().id()),additions,removals,weights,nextIdentity);
    }
    private WorldTopologyLedger.Registration at(long id,int x) {
        var old=record(id,Kind.PIPE);var d=old.device();return new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",x,12,0),d.kind(),d.facing(),d.geometry(),d.control()),old.spec(),0);
    }
    @Test void independentLaterEventCanCommitWithoutAdvancingTheBlockedEventsTimestamp() {
        var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty());ledger.tick();
        var first=ledger.queue(List.of(new WorldTopologyLedger.Edit(1,at(1,0))),Set.of(1L),2);ledger.commit(first);ledger.tick();
        var second=ledger.queue(List.of(new WorldTopologyLedger.Edit(2,at(2,20))),Set.of(2L),3);ledger.commit(second);
        assertEquals(List.of(first.event(),second.event()),ready(ledger));
        var applied=ledger.applyBatch(List.of(second.event().id()),List.of(),List.of(),new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],3);ledger.commit(applied);
        assertEquals(Set.of(2L),ledger.active().keySet());assertEquals(List.of(first.event()),ledger.snapshot().events());assertEquals(1,ledger.snapshot().events().getFirst().tick());
        var saved=ledger.snapshot();var restored=new WorldTopologyLedger(FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(saved),saved.onlineTick(),NO_RECOVERIES));
        assertEquals(Set.of(1L,2L),restored.latest().keySet());restored.commit(applyFirst(restored,List.of(),List.of(),new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],3));assertEquals(Set.of(1L,2L),restored.active().keySet());
        assertThrows(IllegalStateException.class,()->ledger.commit(applied));
    }
    @Test void sharedIslandDependenciesAndRemovalReplacementPositionsCannotBeReordered() {
        var ledger=new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0,3,Map.of(1L,at(1,0),2L,at(2,20)),List.of(),WorldTopologyLedger.MaterialTotal.empty(),WorldTopologyLedger.MaterialTotal.empty()));
        var remove=ledger.queue(List.of(new WorldTopologyLedger.Edit(1,null)),Set.of(1L),3);ledger.commit(remove);
        var replacement=ledger.queue(List.of(new WorldTopologyLedger.Edit(3,at(3,0))),Set.of(3L),4);ledger.commit(replacement);
        assertEquals(List.of(remove.event()),ready(ledger));assertThrows(IllegalStateException.class,()->ledger.applyBatch(List.of(replacement.event().id()),List.of(),List.of(),new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],4));
        ledger.commit(applyFirst(ledger,List.of(),List.of(),new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],4));assertEquals(List.of(replacement.event()),ready(ledger));ledger.commit(applyFirst(ledger,List.of(),List.of(),new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],4));
        var a=ledger.queue(List.of(new WorldTopologyLedger.Edit(2,null)),Set.of(2L,3L),4);ledger.commit(a);
        var b=ledger.queue(List.of(new WorldTopologyLedger.Edit(3,null)),Set.of(3L),4);ledger.commit(b);
        assertEquals(List.of(a.event()),ready(ledger));assertThrows(IllegalStateException.class,()->ledger.applyBatch(List.of(b.event().id()),List.of(),List.of(),new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1],4));
    }
    private WorldTopologyLedger.Registration record(long id,Kind kind) {
        var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position("minecraft:overworld",0,12,0),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),kind==Kind.PUMP?new FlowControl.Pump(.01,500000):new FlowControl.Passive());
        return new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(),0);
    }
    @Test void pendingPhysicalEventsRoundTripWithControlSettingsAndTheirOriginalTimestamps() {
        var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty());for(int i=0;i<40;i++)ledger.tick();
        var registration=record(1,Kind.PUMP);var edit=ledger.queue(List.of(new WorldTopologyLedger.Edit(1,registration)),Set.of(1L),2);ledger.commit(edit);
        for(int i=0;i<10;i++)ledger.tick();var saved=ledger.snapshot();var restored=new WorldTopologyLedger(FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(saved),saved.onlineTick(),NO_RECOVERIES));
        assertEquals(50,restored.onlineTick());assertEquals(40,restored.snapshot().events().getFirst().tick());assertEquals(registration,restored.latest().get(1L));assertTrue(restored.snapshot().active().isEmpty());
    }
    @Test void constructionAndDestructionCountOnceAndNeverReuseTheOldIdentity() {
        var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty());var registration=record(1,Kind.RESERVOIR);
        ledger.commit(ledger.queue(List.of(new WorldTopologyLedger.Edit(1,registration)),Set.of(1L),2));
        var model=FluidTestSupport.networkModel();var charge=com.wormzjl.icheme.fluid.support.FluidTestSupport.chargedBoundary(registration.spec(),registration.device(),model);
        double[] w=new double[com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1];for(int c=0;c<com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK+1;c++)w[c]=c==com.wormzjl.icheme.science.material.MaterialTestBasis.NETWORK?model.waterMolecularWeight:model.hydrocarbon.molecularWeight(c);
        var created=applyFirst(ledger,List.of(charge),List.of(),w,3);var stale=applyFirst(ledger,List.of(charge),List.of(),w,3);ledger.commit(created);
        assertThrows(IllegalStateException.class,()->ledger.commit(stale));assertArrayEquals(charge.inventory().moles(),ledger.snapshot().constructed().moles());
        ledger.commit(ledger.queue(List.of(new WorldTopologyLedger.Edit(1,null)),Set.of(1L),3));ledger.commit(applyFirst(ledger,List.of(),List.of(charge),w,3));
        assertTrue(ledger.snapshot().active().isEmpty());assertArrayEquals(ledger.snapshot().constructed().moles(),ledger.snapshot().destroyed().moles());assertEquals(ledger.snapshot().constructed().totalEnergy(),ledger.snapshot().destroyed().totalEnergy());
        assertThrows(IllegalStateException.class,()->ledger.queue(List.of(new WorldTopologyLedger.Edit(1,registration)),Set.of(1L),3));
    }
    @Test void finiteReservoirInitializationCannotBeReappliedAsASettingsEdit() {
        var registration=record(1,Kind.RESERVOIR);var snapshot=new WorldTopologyLedger.Snapshot(0,2,Map.of(1L,registration),List.of(),WorldTopologyLedger.MaterialTotal.empty(),WorldTopologyLedger.MaterialTotal.empty());
        var ledger=new WorldTopologyLedger(snapshot);var changed=new WorldTopologyLedger.Registration(registration.device(),new FluidDeviceSpec(1,298.15,200000,registration.spec().composition()),1);
        assertThrows(IllegalStateException.class,()->ledger.queue(List.of(new WorldTopologyLedger.Edit(1,changed)),Set.of(1L),2));assertSame(snapshot,ledger.snapshot());
    }
    /**
     * A crystal is its component in another phase (owner decision D23): a pilot tank placed holding CO2 in N2 below its
     * frost point and broken after its refresh deposited CO2-I books the same component moles and total energy destroyed
     * as constructed. The dry ice enters the destroyed totals as CO2 moles with its true energy (its stored sensible part
     * plus its energy offset times its mass), never as a solid's mass.
     */
    @Test void aTankBrokenHoldingDryIceBooksItsCrystalAsItsComponent() {
        var catalog=com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.catalog();
        var pilot=FluidTestSupport.model(catalog,com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.PACKAGE_ID);
        // 10 % CO2 in N2 at 170 K and 1 MPa (frost point 192.75 K), 0.1 m3 at 12 m: placed as gas, its first refresh deposits CO2-I.
        double[] z={.9,0,0,.1,0};var unit=pilot.flashTP(170,1e6,z,()->{});double[] n=z.clone();for(int i=0;i<n.length;i++)n[i]*=.1/unit.volume();
        var placed=new PassiveNetwork.Reservoir(1,12,pilot.flashTP(170,1e6,n,()->{}));
        var broken=new PassiveIntervalSolver(pilot).solve(new PassiveNetwork(List.of(placed),List.of()),5,PassiveIntervalSolver.Settings.defaults(),()->{}).graph().reservoirs().getFirst();
        var crystals=broken.inventory().solids().crystals();assertEquals(1,crystals.size(),"the refresh deposited CO2-I: "+broken.inventory().solids());
        var ledger=new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(catalog));var weights=pilot.molecularWeights();
        var device=new PhysicalFluidTopology.Device(1,new PhysicalFluidTopology.Position("minecraft:overworld",0,12,0),Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
        ledger.commit(ledger.queue(List.of(new WorldTopologyLedger.Edit(1,new WorldTopologyLedger.Registration(device,new FluidDeviceSpec(.1,170,1e6,z),0))),Set.of(1L),2));
        ledger.commit(applyFirst(ledger,List.of(placed),List.of(),weights,2));
        ledger.commit(ledger.queue(List.of(new WorldTopologyLedger.Edit(1,null)),Set.of(1L),2));ledger.commit(applyFirst(ledger,List.of(),List.of(broken),weights,2));
        var constructed=ledger.snapshot().constructed();var destroyed=ledger.snapshot().destroyed();
        double kg=crystals.getFirst().massKg(),offset=kg*crystals.getFirst().material().crystal().energyOffset();
        System.out.println("dry ice "+kg+" kg, u0 m "+offset+" J; constructed "+Arrays.toString(constructed.moles())+" mol, "+constructed.totalEnergy()+" J; destroyed "
                +Arrays.toString(destroyed.moles())+" mol, "+destroyed.totalEnergy()+" J");
        for(int c=0;c<weights.length;c++)assertEquals(constructed.moles()[c],destroyed.moles()[c],1e-12*Math.max(1,constructed.moles()[c]),pilot.components().get(c)+": what was placed as gas leaves as gas and dry ice");
        assertEquals(constructed.totalEnergy(),destroyed.totalEnergy(),1e-9*Math.abs(offset),"the true energy, the dry ice's offset included");
        assertTrue(destroyed.solidMasses().isEmpty(),"the crystal is booked as CO2, not as a solid's mass: "+destroyed.solidMasses());
    }
    // The fluid basis (merged from FluidBasisTest).
    @org.junit.jupiter.api.AfterEach void resetMaterials(){com.wormzjl.icheme.science.material.MaterialRuntime.reset();}
    private static MaterialCatalog reordered() {
        var resources=new HashMap<>(MaterialCatalog.bundled().resources());String path="data/icheme/materials/bases/network.json";
        var basis=com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();basis.remove("extends");
        var original=MaterialCatalog.bundled().requirePackage("icheme:tjl20_methane_nitrogen");
        var ids=new ArrayList<>(original.components());var properties=new ArrayList<>(original.properties().stream().map(MaterialCatalog.Property::id).toList());
        Collections.reverse(ids);Collections.reverse(properties);basis.add("components",new com.google.gson.Gson().toJsonTree(ids));basis.add("properties",new com.google.gson.Gson().toJsonTree(properties));resources.put(path,basis.toString());
        return MaterialCatalog.parse(resources);
    }
    @Test void topologyWithoutAnyIslandStillRejectsAChangedIdentityAxis() {
        var original=MaterialCatalog.bundled();var saved=WorldTopologyLedger.Snapshot.empty(original);
        var decoded=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(saved),saved.onlineTick(),NO_RECOVERIES);assertEquals(saved.basis(),decoded.basis());
        decoded.basis().requireCurrent(original);
        assertThrows(IllegalArgumentException.class,()->decoded.basis().requireCurrent(reordered()));
        // The basis lies near the end of the topology: one cut short of it is refused, never read without it.
        var bytes=FluidCheckpointCodec.encodeTopology(saved);
        assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(Arrays.copyOf(bytes,bytes.length-2),0,NO_RECOVERIES));
    }
    @Test void capturedAccountingAndSerializationNeverReadTheReloadedGlobalAxis() {
        var basis=new WorldTopologyLedger.FluidBasis("test:nitrogen",List.of("Nitrogen","Water"),"test:nitrogen@r1");
        var ledger=new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0,1,Map.of(),List.of(),WorldTopologyLedger.MaterialTotal.empty(2),WorldTopologyLedger.MaterialTotal.empty(2),basis));
        com.wormzjl.icheme.science.material.MaterialRuntime.publish(reordered());ledger.tick();
        var saved=ledger.snapshot();assertEquals(basis,saved.basis());assertEquals(2,saved.constructed().moles().length);
        var next=saved.constructed().plus(List.of(),saved.basis().components(),new double[saved.basis().components().size()]);
        assertArrayEquals(saved.constructed().moles(),next.moles());
        var decoded=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(saved),saved.onlineTick(),NO_RECOVERIES);assertEquals(saved.basis(),decoded.basis());
    }
}
