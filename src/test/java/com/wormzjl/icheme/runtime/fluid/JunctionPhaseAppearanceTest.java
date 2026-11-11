package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * Hold class E3 of the placement-sequence fuzz (phase-ports batch, PHASE_PORTS_REVIEW.md "Hold class E3: a hydrocarbon
 * arriving at a junction with no hydrocarbon phase (2026-09-28)"): a junction minted from a water generator holds pure
 * water, so its layout omits nitrogen (no phase to hold it in); when a nitrogen generator's flow delivers nitrogen, the
 * reconstruction booked it into a vapour at the state's hydrocarbon partial pressure of zero and every step size failed
 * with "Hydrocarbon vapour partial pressure below the numerical floor". The smallest world of fuzz seed 30: a water
 * generator, a junction pipe with a void above it, a pipe to a nitrogen generator, all placed in one tick. The island must
 * keep up without a hold and carry the nitrogen through the junction.
 */
class JunctionPhaseAppearanceTest {
    @Test void nitrogenArrivingAtAWaterSeededJunctionGetsAVapourPhase() {
        var w=new EventPathWorld();w.runTo(100);var passive=new FlowControl.Passive();
        w.place(0,0,0,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NETWORK,355.6753347596607,361077.84297592135),.02);
        w.place(0,0,1,Kind.PIPE,NORTH,.02);w.place(0,1,1,Kind.VOID,NORTH,.02);w.place(0,0,2,Kind.PIPE,NORTH,.02);
        w.place(0,0,3,Kind.GENERATOR,NORTH,passive,EventPathWorld.pure(MaterialTestBasis.NITROGEN,333.2954119245145,366182.8050784859),.02);
        w.runTo(2000);w.assertKeptUp("nitrogen into a water-seeded junction");
        var island=w.onWorld(w.coordinator.snapshots().getFirst());
        var junction=island.graph().reservoirs().stream().filter(n->n.junction()).findFirst().orElseThrow().state();
        assertTrue(TestPhases.vapor(junction)[MaterialTestBasis.NITROGEN]>0,"the junction carries the nitrogen in a vapour phase: "+junction);
    }
}
