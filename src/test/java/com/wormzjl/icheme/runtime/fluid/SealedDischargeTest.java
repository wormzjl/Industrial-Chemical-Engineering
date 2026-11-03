package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner decision D58, case A4 of the phase-ports batch (REMAINING_HOLDS.md, "Remaining issues sorted for the owner"): a
 * pocket behind devices that let nothing through. In real life a pump whose discharge has no way out dead-heads: it stands
 * at its shutoff and moves nothing. A way out that the one-way rules forbid (into a generator, which only supplies) is no
 * way out, so the start-point relief of hold F8 closes the pump at its shutoff before anything is solved.
 */
class SealedDischargeTest {
    /**
     * The smallest world of world-defaults fuzz seed 358 (8 blocks, all placed at tick 158; minimized in
     * tools/phase-ports-probes/hold-classes logs rc18). A 1 atm water generator and a pump side by side in a ring of pipes:
     * the pump draws from the generator on one side and its discharge returns to the same generator on the other. A
     * generator only supplies, so the discharge has no way out and the pump moves nothing. The pump started at its target,
     * the first pass pushed its flow into the generator, the pass loop closed that direction, and the discharge's junctions
     * were left a sealed pocket with the pump still at its target: singular at every step size (held at tick 159).
     */
    @Test void aPumpDischargingIntoItsGeneratorDeadHeads() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(158);
        w.place(-2,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-2,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,0,-4,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-2,0,-4,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-1,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(-2,0,-3,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325),.05);
        w.place(-1,0,-3,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.05);
        w.runTo(158+120*100);w.assertKeptUp("a pump discharging into its generator");
        for(var s:w.coordinator.snapshots()) {
            var result=s.lastResult().orElseThrow();
            System.out.println("SEALED_DISCHARGE island "+s.id()+" modes="+result.endpointModes()+" flows="+java.util.Arrays.toString(result.averageMassFlows()));
            // The dead-head is decided per solve, after the island-level rest test, so the island is still solved: its
            // junctions' relaxation leaves roundoff flows (one unit in the last place of a 1 kg buffer).
            for(double q:result.averageMassFlows())assertEquals(0,q,1e-12,"nothing moves");
            for(int e=0;e<s.graph().pipes().size();e++)if(s.graph().pipes().get(e).control() instanceof FlowControl.Pump)
                assertEquals(FlowControl.Mode.CLOSED,result.endpointModes().get(e),"the pump stands at its shutoff");
        }
    }

    /**
     * The mover's own end (owner decision D59 (4), t28): the smallest world of world-defaults fuzz seed 826 (13 blocks, all
     * placed at tick 152; minimized in tools/phase-ports-probes/hold-classes logs cc17). The pump draws from a junction fed
     * through pipes by a placed tank and discharges straight into a 1 atm water generator, which only supplies: its own
     * forward flow is forbidden, a pocket of one node. Without the rule it started on its head limit pushing into the
     * generator and the step stalled at pass 0 at residual 9.96 at every step size (held at tick 153); with it the pump
     * starts at its shutoff and the island keeps up for 120 slices.
     */
    @Test void aPumpDischargingStraightIntoAGeneratorDeadHeads() {
        var w=new EventPathWorld();var passive=new FlowControl.Passive();
        w.runTo(152);
        w.place(1,0,1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,3,-1,Kind.VOID,NORTH,.05);
        w.place(1,0,0,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325),.05);
        w.place(0,0,0,Kind.PUMP,SOUTH,new FlowControl.Pump(.01,500000),null,.05);
        w.place(0,0,-4,Kind.RESERVOIR,NORTH,.05);
        w.place(0,0,-3,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,0,-3,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,2,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,0,-2,Kind.PIPE,NORTH,passive,null,.05);
        w.place(0,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,1,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.place(1,0,-1,Kind.PIPE,NORTH,passive,null,.05);
        w.runTo(152+120*100);w.assertKeptUp("a pump discharging straight into a generator");
        for(var s:w.coordinator.snapshots()) {
            var result=s.lastResult().orElseThrow();
            System.out.println("OWN_END_DISCHARGE island "+s.id()+" modes="+result.endpointModes()+" flows="+java.util.Arrays.toString(result.averageMassFlows()));
            for(int e=0;e<s.graph().pipes().size();e++)if(s.graph().pipes().get(e).control() instanceof FlowControl.Pump) {
                assertNotEquals(FlowControl.Mode.MOVER_LAW,result.endpointModes().get(e),"the pump moves nothing");
                assertEquals(0,result.averageMassFlows()[e],1e-12,"the pump moves nothing");
            }
        }
    }
}
