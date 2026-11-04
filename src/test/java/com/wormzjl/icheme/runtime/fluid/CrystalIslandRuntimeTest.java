package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Crystals as solids through the real coordinator (milestone M3 of the unified thermo port, re-homed on main's
 * {@link CertifyingRig} from the branch's P4 stage 2b test), on the pilot package {@code icheme:pilot_cryogenic}:
 *
 * <ul>
 * <li>a vessel's crystal, a population of its solids, reaches its block entity and an open menu on the engine's own
 * schedule: at the island's presentation bucket after the interval whose refresh deposited it commits, never on a
 * subscription, a load or a tick of its own; nothing is shown before the commit;</li>
 * <li>a crystal beside two liquids (N2 + C2H6 of type III with CO2 at 95 K) is a thermo hold like a domain one: retried
 * once, then parked until the island's inputs change, naming the vessel; a fenced event is accepted at the island's
 * committed tick with its inventory unchanged (main's rule for a held owner, which replaces the branch's pending
 * fence);</li>
 * <li>a vessel whose crystal has formed rests and certifies with it; replay and a checkpoint carry the crystal stock bit
 * for bit, and an interval that changed the crystal does not qualify (milestone M4).</li>
 * </ul>
 */
class CrystalIslandRuntimeTest {
    private static final String PILOT="icheme:pilot_cryogenic";
    private final FluidThermodynamics model=FluidTestSupport.model(MaterialCatalog.bundled(),PILOT);

    /** A closed 0.1 m3 vessel, device 101 of island 1, holding {@code state}. */
    private static PassiveNetwork vessel(FluidThermodynamics.State state){return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(101,0,state)),List.of());}
    /** A delivery to a menu or a block entity: the tick, the view's committed tick and the crystal it shows (kg). */
    private record Seen(long tick,long committed,double crystal) {}
    private static double crystal(FluidView view) {
        if(view.state()==null)return Double.NaN;
        double kg=0;for(var p:view.state().solids().crystals())kg+=p.massKg();return kg;
    }
    private static double crystal(PassiveNetwork.Inventory inventory){double kg=0;for(var p:inventory.solids().crystals())kg+=p.massKg();return kg;}

    @Test void theCrystalReachesTheViewsAndMenusOnTheEnginesBucketOnly() {
        // 10 % CO2 in N2 at 170 K and 1 MPa, below its 192.75 K frost point, charged supersaturated.
        double[] z={.9,0,0,.1,0};var unit=model.flashTP(170,1e6,z,()->{});double[] n=z.clone();for(int i=0;i<n.length;i++)n[i]*=.1/unit.volume();
        var rig=new CertifyingRig(CertificatePolicy.defaults(),model,0);rig.register(1,vessel(model.flashTP(170,1e6,n,()->{})));
        var menu=new ArrayList<Seen>();var block=new ArrayList<Seen>();
        var host=new FluidPresentation.Host<List<Seen>>() {
            public long key(long device){return 1;}
            public long revision(long device){return 0;}
            public boolean eventPending(UUID event){return false;}
            public boolean open(List<Seen> consumer){return true;}
            public FluidView view(long device,Map<Long,IslandCoordinator.Snapshot> islands) {
                var island=islands.computeIfAbsent(1L,rig.coordinator::presentation);var node=island.graph().reservoirs().getFirst();
                return new FluidView(device,0,island.clock().committedTick(),island.clock().onlineTick(),island.status(),FluidView.State.from(node.state(),node.inventory().volume()),
                        0,List.of(),null,5,List.of(),null);
            }
            public boolean present(long device,Supplier<FluidView> view){var v=view.get();block.add(new Seen(rig.epoch[0],v.committedTick(),crystal(v)));return true;}
            public void deliver(List<Seen> consumer,long device,boolean withStatic,FluidView view,String reply){consumer.add(new Seen(rig.epoch[0],view.committedTick(),crystal(view)));}
        };
        var presentation=new FluidPresentation<>(host,0);
        rig.onPublished=changed->presentation.markIfLoaded(101);
        // Subscribing and loading deliver nothing by themselves: the bucket does.
        presentation.subscribe(menu,101);presentation.deviceLoaded(101);
        assertTrue(menu.isEmpty()&&block.isEmpty(),"nothing pushed on subscription or load");
        for(int t=0;t<450;t++){rig.tick();presentation.tick(rig.epoch[0]);}
        System.out.println("menu deliveries "+menu+"\nblock-entity presentations "+block);
        assertFalse(menu.isEmpty());
        for(var seen:menu)assertEquals(1,seen.tick()%FluidPresentation.BUCKET_TICKS,"a menu is delivered at its island's bucket only: "+seen);
        for(var seen:block)assertEquals(1,seen.tick()%FluidPresentation.BUCKET_TICKS,"a block entity is presented at the bucket only: "+seen);
        // Before the first interval commits the view shows the charged gas, no crystal; after it, the deposited crystal.
        var first=menu.getFirst();assertEquals(0,first.committed());assertEquals(0,first.crystal(),0,"no crystal before the engine's commit");
        var committed=menu.stream().filter(s->s.committed()>0).findFirst().orElseThrow();
        assertEquals(100,committed.committed(),"the first interval commits at online tick 100");
        assertEquals(101,committed.tick(),"shown at the next bucket, not when it committed");
        var island=rig.coordinator.presentation(1);double deposited=crystal(island.graph().reservoirs().getFirst().inventory());
        assertTrue(deposited>0);assertEquals(deposited,committed.crystal(),0,"the view carries the committed crystal");
        assertTrue(block.stream().anyMatch(s->s.committed()==100&&s.crystal()==deposited),"the block entity shows it at the same bucket: "+block);
    }

    @Test void aCrystalBesideTwoLiquidsParksTheIslandTypedAndAFencedEventIsAcceptedWhileHeld() {
        // 40 % N2, 55 % C2H6 and 5 % CO2 at 95 K and 2 MPa: the fluid the crystal would leave is two liquids (type III).
        double[] liquid={40,0,55,5};var state=model.state(95,2e6,liquid,new double[4],0,0,2e6);
        var rig=new CertifyingRig(CertificatePolicy.defaults(),model,0);rig.register(1,vessel(state));
        rig.run(300);
        var held=rig.coordinator.snapshot(1);
        System.out.println("held island: "+held.status()+" | solves "+rig.solves);
        assertTrue(held.status().startsWith(ProcessSolveServices.PHASE_HOLD),held.status());
        assertTrue(held.status().contains("a crystal beside a phase set the equilibrium engine does not implement")&&held.status().contains("in node 101"),
                "the typed reason and the node: "+held.status());
        assertTrue(rig.coordinator.waitsForInputs(1),"retried once, the same hold: the island waits for its inputs to change");
        assertEquals(0,held.clock().committedTick(),"nothing commits while held");
        int solves=rig.solves;rig.run(300);assertEquals(solves,rig.solves,"a parked island dispatches nothing");
        // A held owner accepts a fenced event at its committed tick without a solve, its inventory (crystals included) kept.
        var before=rig.coordinator.snapshot(1).graph();
        var event=UUID.randomUUID();rig.coordinator.fence(event,150,List.of(1L));
        assertTrue(rig.coordinator.aligned(event,List.of(1L)),"the held owner accepts the event without a solve");
        assertEquals(150,rig.coordinator.snapshot(1).clock().committedTick());
        assertEquals(before,rig.coordinator.snapshot(1).graph(),"the held inventory is kept");
    }

    /**
     * A closed tank of 10 % CO2 in N2 at 170 K and 1 MPa deposits CO2-I at its first refresh and then sits at equilibrium
     * with its dry ice: nothing moves, so it rests (D23 (6), the refresh before the rest test) and certifies holding the
     * crystal. Replay carries the crystal stock bit for bit, and a checkpoint taken while certified restores the
     * certificate and the crystal record. Node solids are evidence: the certified interval with 1 % less dry ice before
     * it, all else equal, does not qualify.
     */
    @Test void aDepositedVesselCertifiesAndReplaysItsCrystalsExactly() {
        double[] z={.9,0,0,.1,0};var unit=model.flashTP(170,1e6,z,()->{});double[] n=z.clone();for(int i=0;i<n.length;i++)n[i]*=.1/unit.volume();
        var rig=new CertifyingRig(CertificatePolicy.defaults(),model,0);rig.register(1,vessel(model.flashTP(170,1e6,n,()->{})));
        rig.runUntilCertified(List.of(1L),2_000);
        var saved=rig.stored(1).certificate().orElseThrow().saved().orElseThrow();var base=saved.interval().result().graph().reservoirs().getFirst().inventory();
        System.out.println("certified at tick "+rig.epoch[0]+" after "+rig.solves+" solves: "+saved+" | "+base.solids());
        assertTrue(crystal(base)>0,"the vessel certified holding its CO2-I: "+base.solids());
        int solves=rig.solves;rig.run(1_000);
        assertEquals(solves,rig.solves,"a resting vessel solves nothing");
        assertEquals(base.solids(),rig.read(1).graph().reservoirs().getFirst().inventory().solids(),"the crystal stock 1000 ticks into the replay, bit for bit");
        var image=FluidCheckpointCodec.encode(rig.checkpoint(),key->key.on(model));
        var restored=new CertifyingRig(CertificatePolicy.defaults(),model,rig.epoch[0]);restored.load(FluidCheckpointCodec.decode(image,key->key.on(model)));
        assertTrue(restored.stored(1).certificate().isPresent(),"the certificate is restored: "+restored.stored(1).status());
        assertEquals(rig.read(1).graph().reservoirs().getFirst().inventory(),restored.read(1).graph().reservoirs().getFirst().inventory(),"the restored vessel, its crystal included");
        var interval=saved.interval();var node=interval.before().reservoirs().getFirst();var inventory=node.inventory();
        var less=new com.wormzjl.icheme.science.fluid.state.SolidInventory(inventory.solids().populations().stream().map(p->new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(
                p.material(),p.size(),p.material().crystal()!=null?.99*p.massKg():p.massKg())).toList());
        var changed=new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(node.id(),node.elevation(),node.state(),node.kind(),
                new PassiveNetwork.Inventory(inventory.volume(),inventory.moles(),inventory.internalEnergy(),less))),interval.before().pipes());
        var refused=IslandCertificate.issue(new IslandCertificate.Summary(new IslandCertificate.Interval(interval.startTick(),interval.endTick(),changed,interval.result()),
                model.molecularWeights()),CertificatePolicy.defaults());
        assertNull(refused.certificate(),"an interval that changed the crystal does not qualify");
        assertTrue(refused.refusal().contains("node solids"),refused.refusal());
    }
}
