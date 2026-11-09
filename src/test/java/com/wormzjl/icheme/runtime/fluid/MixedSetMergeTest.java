package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.NORTH;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Island compound sets (batch 2026-11-04-refinery-compounds, V2 and V3): every island runs on its own compound list, and a
 * compound at exactly zero is inert, so a merge, a split, a growth and a reload move nothing. Two rest lines on different
 * lists (nitrogen with methane, nitrogen with ethane) are joined and parted again; a flowing line grows a compound; a
 * world saved without a compound loads with it.
 */
class MixedSetMergeTest {
    private static final String METHANE="Methane",ETHANE="Ethane",N2=FluidMaterialCatalog.NITROGEN;

    /** A generator of nitrogen and {@code other} in equal parts at 300 K and 3 bar, on {@code model}'s list. */
    private static FluidDeviceSpec feed(FluidThermodynamics model,String other) {
        var n=new double[model.componentCount()];n[model.components().indexOf(N2)]=.5;n[model.components().indexOf(other)]=.5;
        return new FluidDeviceSpec(1,300,3e5,n);
    }
    /**
     * Line GPPPRPPR along x at row {@code z}: a generator of {@code other} with nitrogen charges the two tanks (a placed tank
     * holds nothing, D54) and is removed at tick 400 by {@link #rested}; returns the generator. The first tank is at x 4.
     */
    private static long line(EventPathWorld w,int z,String other) {
        long generator=w.place(0,0,z,Kind.GENERATOR,NORTH,feed(w.model,other),.05);
        for(int x=1;x<=3;x++)w.place(x,0,z,Kind.PIPE,NORTH,.05);
        w.place(4,0,z,Kind.RESERVOIR,NORTH,.05);for(int x=5;x<=6;x++)w.place(x,0,z,Kind.PIPE,NORTH,.05);w.place(7,0,z,Kind.RESERVOIR,NORTH,.05);
        return generator;
    }
    private static long tank(EventPathWorld w,int z){return w.registry.at(new PhysicalFluidTopology.Position(EventPathWorld.DIM,4,0,z)).orElseThrow().device().id();}
    /** The lines charged and their generators removed: what a later edit finds at rest. */
    private static void charged(EventPathWorld w,long... generators){w.runTo(400);for(long g:generators)remove(w,g);w.runTo(2_400);w.assertKeptUp("the charged lines");}
    /** Every vessel's inventory on the world list, by device: amounts by compound id, energy as held. */
    private static Map<Long,PassiveNetwork.Inventory> stock(EventPathWorld w) {
        var out=new TreeMap<Long,PassiveNetwork.Inventory>();
        for(var s:w.coordinator.observe())for(var n:w.onWorld(s).graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)assertNull(out.put(n.id(),n.inventory()));
        return out;
    }
    private static Set<String> list(EventPathWorld w,long device){return new HashSet<>(w.coordinator.model(w.registry.owner(device)).compounds());}
    private static void applied(EventPathWorld w){w.registry.applyPending();assertTrue(w.ledger.events().isEmpty(),"every edit applied");}
    private static void remove(EventPathWorld w,long device){w.registry.submit(List.of(new WorldTopologyLedger.Edit(device,null)),w.ledger.nextIdentity(),null);}
    /** The world after its rest lines certified: methane at z 0, ethane at z 4, a third methane line at z 8 that no edit touches. */
    private static EventPathWorld rested() {
        var w=new EventPathWorld();charged(w,line(w,0,METHANE),line(w,4,ETHANE),line(w,8,METHANE));
        for(var s:w.coordinator.observe())assertTrue(s.certificate().isPresent(),"every rest line certifies: "+s.status());
        return w;
    }

    @Test void twoIslandsOnDifferentListsMergeAndSplitMovingNoMoleAndNoJoule() {
        var w=rested();long a=tank(w,0),b=tank(w,4),c=tank(w,8);
        assertEquals(Set.of(N2,METHANE),list(w,a),"each island on its own list: nitrogen and what its stock holds");
        assertEquals(Set.of(N2,ETHANE),list(w,b));
        long untouched=w.registry.owner(c);var before=w.coordinator.observe(untouched);
        for(long island:List.of(w.registry.owner(a),w.registry.owner(b)))w.coordinator.snapshot(island);
        var held=stock(w);
        // The merge: a pipe run from the middle of the methane line to the middle of the ethane line.
        long[] joint=new long[3];for(int z=1;z<=3;z++)joint[z-1]=w.place(2,0,z,Kind.PIPE,NORTH,.05);
        applied(w);
        assertEquals(w.registry.owner(a),w.registry.owner(b),"one island");
        assertEquals(Set.of(N2,METHANE,ETHANE),list(w,a),"a merge takes the union of the lists");
        assertEquals(held,stock(w),"the merge moves no mole and no joule of any vessel");
        var now=w.coordinator.observe(untouched);
        assertEquals(before.certificate(),now.certificate(),"the untouched island keeps its certificate");
        assertEquals(before.payloadGeneration(),now.payloadGeneration());assertEquals(before.clock(),now.clock(),"and is not even read");
        // The split: the middle pipe of the run leaves; each part keeps the merged list less what it holds nowhere.
        remove(w,joint[1]);applied(w);
        assertNotEquals(w.registry.owner(a),w.registry.owner(b),"two islands again");
        assertEquals(Set.of(N2,METHANE),list(w,a),"a split part drops the compounds it holds nowhere");
        assertEquals(Set.of(N2,ETHANE),list(w,b));
        assertEquals(held,stock(w),"the split moves no mole and no joule of any vessel");
        assertEquals(before.certificate(),w.coordinator.observe(untouched).certificate(),"the untouched island still holds its certificate");
        // Both parts run on: the lists are complete for what they hold.
        w.runTo(w.ledger.onlineTick()+400);w.assertKeptUp("the parted lines");
    }

    @Test void aMergePastTheCompoundCapHoldsAsACapabilityLimitNamingItsCause() {
        var w=rested();w.registry.compoundCap(2);long a=tank(w,0);
        var held=stock(w);
        for(int z=1;z<=3;z++)w.place(2,0,z,Kind.PIPE,NORTH,.05);
        applied(w);
        long island=w.registry.owner(a);var s=w.coordinator.observe(island);
        assertTrue(s.status().startsWith("HELD: an island carries at most 2 compounds and water; joined, this one would carry 3"),s.status());
        assertEquals(held,stock(w),"the held island holds its stock as merged");
        w.runTo(w.ledger.onlineTick()+200);
        assertTrue(w.coordinator.observe(island).status().startsWith("HELD: an island carries at most 2"),"the capability hold stays: "+w.coordinator.observe(island).status());
        assertEquals(held,stock(w),"nothing runs while it holds");
    }

    /**
     * A flowing island's list grows when its generator's spec names a new compound: the replacement has the same lines, so it
     * carries its junctions' solved states onto the longer list (PassiveNetwork.withJunctionStates), padded by id.
     */
    @Test void aGrowingListCarriesTheJunctionStatesById() {
        var w=new EventPathWorld();var model=w.model;
        var feed=new double[model.componentCount()];feed[model.components().indexOf(METHANE)]=1;
        long generator=w.place(0,0,0,Kind.GENERATOR,NORTH,new FluidDeviceSpec(1,298.15,150000,feed),.05);
        for(int x=1;x<=3;x++)w.place(x,0,0,Kind.PIPE,NORTH,.05);
        w.place(2,0,1,Kind.PIPE,NORTH,.05);w.place(2,0,2,Kind.RESERVOIR,NORTH,.05);w.place(4,0,0,Kind.RESERVOIR,NORTH,.05);
        w.runTo(1_000);w.assertKeptUp("the methane line");
        long owner=w.registry.owner(generator);assertEquals(Set.of(N2,METHANE),list(w,generator));
        var old=w.onWorld(w.coordinator.snapshot(owner)).graph().reservoirs().stream().filter(PassiveNetwork.Reservoir::junction).toList();
        assertFalse(old.isEmpty(),"the branch compiles a junction");
        var richer=feed.clone();richer[model.components().indexOf(ETHANE)]=.25;var r=w.registry.registrations().get(generator);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(generator,new WorldTopologyLedger.Registration(r.device(),new FluidDeviceSpec(1,298.15,150000,richer),r.revision()+1))),w.ledger.nextIdentity(),null);
        applied(w);
        assertNotEquals(owner,w.registry.owner(generator),"the edit replaced the island");
        assertEquals(Set.of(N2,METHANE,ETHANE),list(w,generator),"the list grows by the generator's new compound");
        var carried=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(var n:w.onWorld(w.coordinator.observe(w.registry.owner(generator))).graph().reservoirs())if(n.junction())carried.put(n.id(),n);
        for(var j:old) {
            var n=Objects.requireNonNull(carried.get(j.id()),"junction "+j.id()+" carried");
            assertEquals(j.state().temperature(),n.state().temperature(),"junction "+j.id()+": its temperature");
            assertEquals(j.state().pressure(),n.state().pressure(),"junction "+j.id()+": its pressure");
            assertArrayEquals(PhaseLayout.totalAmounts(j.state()),PhaseLayout.totalAmounts(n.state()),"junction "+j.id()+": its amounts, by id");
        }
        w.runTo(w.ledger.onlineTick()+400);w.assertKeptUp("the line with ethane");
    }

    /**
     * A world saved without a compound loads with it (a compound added to the package): the world list moved, so the world
     * rebuilds from its facts, and no island restates: every vessel keeps its saved contents bit for bit, the ledger books
     * nothing, and each island keeps its own list. The model a certificate signs is the island's own list's: the added
     * compound leaves its identity alone.
     */
    @Test void aCompoundAddedToThePackageRestatesNoIsland() {
        var lacking=FluidFactsCompatTest.lacking("Isopentane");var network=FluidMaterialCatalog.networkPackage(lacking);
        var lackingModel=FluidThermodynamics.forNetwork(lacking,network);var current=new EventPathWorld().model;
        var saved=MaterialRuntime.with(lacking,network,()->{
            var w=new EventPathWorld(lacking,lackingModel);charged(w,line(w,0,METHANE),line(w,4,ETHANE));
            var store=FluidCheckpointStore.memory();
            store.commit(store.prepare(new FluidCheckpointCodec.Checkpoint(w.entries()),Optional.of(w.ledger.snapshot()),w.ledger.onlineTick(),key->key.on(lackingModel)));
            var contents=new TreeMap<Long,PassiveNetwork.Reservoir>();
            for(var s:w.coordinator.observe())for(var n:s.graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)contents.put(n.id(),IslandSets.node(n,w.coordinator.model(s.id()),w.coordinator.model(s.id()).whole()));
            return Map.entry(store.image(),contents);
        });
        var image=saved.getKey();var loaded=FluidCheckpointStore.memory(image).load(image.core(),key->key.on(current),EventPathWorld.CATALOG);
        assertEquals(FluidCheckpointStore.Route.FACTS,loaded.route(),"the world list moved: "+loaded.reason());
        assertEquals(WorldTopologyLedger.Restated.NONE,loaded.world().orElseThrow().restated(),"nothing restated, nothing removed");
        int vessels=0;
        for(var e:loaded.checkpoint().islands()) {
            var own=e.key().on(current);assertFalse(own.compounds().contains("Isopentane"),"no island carries the added compound");
            assertTrue(e.snapshot().status().startsWith("WAITING: rebuilt from saved facts"),e.snapshot().status());
            for(var n:e.snapshot().graph().reservoirs()) {
                if(n.kind()!=PassiveNetwork.NodeKind.RESERVOIR)continue;vessels++;var was=saved.getValue().get(n.id());
                var ids=lackingModel.components();var amounts=IslandSets.amounts(n.inventory().moles(),own,current);
                for(int c=0;c<ids.size();c++)assertEquals(was.inventory().moles()[c],amounts[current.components().indexOf(ids.get(c))],"vessel "+n.id()+": "+ids.get(c)+" kept");
                assertEquals(was.inventory().internalEnergy(),n.inventory().internalEnergy(),"vessel "+n.id()+": its energy, bit for bit");
                assertEquals(was.state().temperature(),n.state().temperature());assertEquals(was.state().pressure(),n.state().pressure(),"vessel "+n.id()+": its pressure, bit for bit");
            }
        }
        assertEquals(saved.getValue().size(),vessels);
        for(var compounds:List.of(List.of(N2,METHANE),List.of(N2,ETHANE)))
            assertEquals(lackingModel.on(compounds).revision(),current.on(compounds).revision(),"an island's model identity names its own compounds only: "+compounds);
    }
}
