package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.NetworkReduction;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * Owner decisions D42 and D43 (PHASE_PORTS_REVIEW.md "The certificate is the gauge: rest at 0.1 Pa, zero-flow loops, saved
 * crude cases (2026-10-13)"). Worlds whose sources tie exactly come to rest at a drive the solver's full precision never
 * settles: before the gauge they were solved at roundoff until the Newton failed and the island was held. With the rest
 * test reading drives and flows against 0.1 Pa they certify "STEADY: no flow" and are no longer stepped; their states are
 * kept to the last digit. Each world holds with the rest test of D19 (no band, working tree only; review, section 6).
 */
class RestResolutionWorldTest {
    private static final FluidDeviceSpec WATER=EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325);
    private static void assertAtRestAndCertified(EventPathWorld w,long device,String label) {
        w.assertKeptUp(label);
        var end=w.coordinator.snapshot(w.registry.owner(device));
        if(!end.status().startsWith("STEADY: no flow")) {
            System.out.println("REST_WORLD "+label+" status="+end.status()+" flows="+java.util.Arrays.toString(end.lastResult().map(r->r.averageMassFlows()).orElse(new double[0])));
            for(var node:end.graph().reservoirs())System.out.println("  node "+node.id()+" "+node.kind()+" z="+node.elevation()+" empty="+node.empty()+" P="+node.state().pressure()+" m="+node.state().mass());
            for(var pipe:end.graph().pipes())System.out.println("  pipe "+pipe.id()+" "+pipe.first()+"->"+pipe.second()+" ports="+pipe.firstPort()+"/"+pipe.secondPort());
        }
        assertTrue(end.status().startsWith("STEADY: no flow"),label+": "+end.status());
        for(double q:end.lastResult().orElseThrow().averageMassFlows())assertEquals(0.0,q,label+": nothing moves once certified");
        assertTrue(NetworkReduction.atRest(end.graph(),w.model),label+": the gauge reads no drive on the certified state");
    }
    /** Independent loops among the island's nodes that are not fixed boundaries. */
    private static int loops(PassiveNetwork graph) {
        int[] parent=new int[graph.reservoirs().size()];for(int i=0;i<parent.length;i++)parent[i]=i;int loops=0;
        for(var pipe:graph.pipes()) {
            if(graph.reservoirs().get(pipe.first()).fixed()||graph.reservoirs().get(pipe.second()).fixed())continue;
            int a=pipe.first(),b=pipe.second();while(parent[a]!=a)a=parent[a];while(parent[b]!=b)b=parent[b];
            if(a==b)loops++;else parent[Math.max(a,b)]=Math.min(a,b);
        }
        return loops;
    }

    /**
     * Family B, a tie (defaults seed 138 minimized, with its generator mirrored): two identical 1 atm water generators 2 m
     * up, on equal legs, feed one junction between two identical 1 atm nitrogen tanks on equal legs, fed through their
     * sides. Water falls 2 m and compresses both gases to about 120.86 kPa (1 atm plus the column to the tanks' water). The
     * drive halves every few seconds and within about a minute is below what a gauge reads: both generators then supply
     * equal halves of nothing, both tanks stand at the same pressure to the gauge. It held at tick 1363 on the
     * reconstruction's equation gate with the rest test of D19.
     */
    @Test void twoIdenticalGeneratorsOnOneJunctionComeToRestAtTheGauge() {
        var w=new EventPathWorld();w.runTo(236);
        w.place(-2,1,-1,Kind.PIPE,NORTH,.05);w.place(-1,0,-1,Kind.PIPE,NORTH,.05);w.place(0,0,-1,Kind.PIPE,NORTH,.05);
        long first=w.place(-2,2,-1,Kind.GENERATOR,NORTH,EventPathWorld.defaultControl(Kind.GENERATOR),WATER,.05);
        long south=w.place(0,0,0,Kind.RESERVOIR,NORTH,.05);w.place(0,0,-2,Kind.PIPE,NORTH,.05);
        long north=w.place(0,0,-3,Kind.RESERVOIR,NORTH,.05);w.place(-2,0,-1,Kind.PIPE,NORTH,.05);
        w.place(2,1,-1,Kind.PIPE,NORTH,.05);w.place(2,0,-1,Kind.PIPE,NORTH,.05);w.place(1,0,-1,Kind.PIPE,NORTH,.05);
        w.place(2,2,-1,Kind.GENERATOR,NORTH,EventPathWorld.defaultControl(Kind.GENERATOR),WATER,.05);
        w.runTo(4236);
        assertAtRestAndCertified(w,first,"two generators on one junction");
        var graph=w.coordinator.snapshot(w.registry.owner(first)).graph();
        var a=graph.reservoirs().stream().filter(n->n.id()==south).findFirst().orElseThrow().state();
        var b=graph.reservoirs().stream().filter(n->n.id()==north).findFirst().orElseThrow().state();
        assertTrue(TestPhases.waterVolume(a)>.1&&TestPhases.waterVolume(b)>.1,"both tanks took water before they came to rest: "+TestPhases.waterVolume(a)+", "+TestPhases.waterVolume(b)+" m3");
        // 1 atm plus a 2 m column of water at 997.05 kg/m3 (Region 1 at the state pressure; 120860 Pa at the retired 996.0).
        assertEquals(120880,a.pressure(),5,"1 atm plus the water column");
        assertEquals(a.pressure(),b.pressure(),NetworkReduction.REST_RESOLUTION,"the two tanks read the same on a gauge");
    }

    /**
     * Family C, a zero-flow loop (defaults seed 616 minimized, 11 blocks): a 1 atm water generator joins a ring of pipes that
     * hangs 2 m below it, with a 1 atm void a block above. The generator cannot lift water into the void at its own
     * pressure; around the ring the same water stands on both sides, so nothing drives a circulation. It held on its first
     * interval (Newton iteration limit) when the ring's roundoff circulation was solved.
     */
    @Test void aLoopWithNoNetDriveIsAtRest() {
        var w=new EventPathWorld();w.runTo(116);
        w.place(1,0,-2,Kind.PIPE,NORTH,.05);w.place(1,-2,-1,Kind.PIPE,NORTH,.05);w.place(1,-2,-2,Kind.PIPE,NORTH,.05);w.place(1,0,0,Kind.PIPE,NORTH,.05);
        long generator=w.place(-1,0,-1,Kind.GENERATOR,NORTH,EventPathWorld.defaultControl(Kind.GENERATOR),WATER,.05);
        w.place(1,1,0,Kind.VOID,NORTH,.05);w.place(1,-1,-1,Kind.PIPE,NORTH,.05);w.place(-1,0,0,Kind.PIPE,NORTH,.05);w.place(1,0,-1,Kind.PIPE,NORTH,.05);
        w.place(0,0,0,Kind.PIPE,NORTH,.05);w.place(1,-1,-2,Kind.PIPE,NORTH,.05);
        w.runTo(120);
        var formed=w.coordinator.observe(w.registry.owner(generator)).graph();
        assertTrue(loops(formed)>0,"the ring is a loop of the solved island");
        w.runTo(3116);
        assertAtRestAndCertified(w,generator,"a ring at rest");
    }

    /**
     * Card E9's world (defaults seed 47 minimized, 12 blocks): a 1 atm water generator and a 1 atm void at the same height,
     * joined through a small network that also rises to a nitrogen tank three blocks up. Nothing drives water from one to
     * the other, and the generator cannot lift it to the tank: rest from the start. It held when the junctions, a roundoff
     * away from balance (0.2 microPa), were solved and a roundoff flow booked more of a trace than its node held.
     */
    @Test void cardE9sWorldIsAtRest() {
        var w=new EventPathWorld();w.runTo(629);
        w.place(1,1,-1,Kind.PIPE,NORTH,.05);w.place(1,0,-2,Kind.PIPE,NORTH,.05);w.place(0,3,-1,Kind.RESERVOIR,NORTH,.05);
        long generator=w.place(0,0,-1,Kind.GENERATOR,NORTH,EventPathWorld.defaultControl(Kind.GENERATOR),WATER,.05);
        w.place(0,2,-1,Kind.PIPE,NORTH,.05);w.place(0,2,0,Kind.PIPE,NORTH,.05);w.place(0,3,0,Kind.PIPE,NORTH,.05);w.place(0,1,-1,Kind.PIPE,NORTH,.05);
        w.place(1,0,-4,Kind.VOID,NORTH,.05);w.place(1,0,-3,Kind.PIPE,NORTH,.05);w.place(0,1,0,Kind.PIPE,NORTH,.05);w.place(1,0,-1,Kind.PIPE,NORTH,.05);
        w.runTo(3629);
        assertAtRestAndCertified(w,generator,"card E9");
    }
}
