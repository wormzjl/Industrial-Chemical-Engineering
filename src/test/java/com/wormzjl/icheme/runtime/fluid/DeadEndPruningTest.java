package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner decision D18 (phase-ports batch, PHASE_PORTS_REVIEW.md "Boundary split, dead ends and rest (2026-09-28)"): a branch
 * that leads to no destination - no tank, no generator, no void: an open pipe end, or a run ending in a filter, pump,
 * compressor or valve with nothing behind it - carries no flow, so it is left out of the solve
 * ({@code NetworkReduction.pruneDeadEnds}). A filter on such a branch keeps its cake in a dormant island of its own; every
 * other pruned block belongs to no island. The branch rejoins the solve when an event gives it a destination.
 *
 * <p>The worlds are the smallest ones of hold class E1 (the interval-start rate pass an island with an inline filter runs):
 * fuzz seed 6 (random), a 281 kPa two-phase crude generator three blocks below a junction pipe joining two nitrogen voids
 * and an inline filter facing down with nothing past it; and fuzz seed 12 (world defaults), a 1 atm water generator two
 * blocks above a junction on a nitrogen tank's top face whose third branch runs to a filter with nothing past it. Both held
 * that rate pass on their first slice; without the filter in the solve they keep up.
 */
class DeadEndPruningTest {
    private static final FlowControl PASSIVE=new FlowControl.Passive();
    private static long at(EventPathWorld w,int x,int y,int z){return w.registry.at(new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,y,z)).orElseThrow().device().id();}
    private static void assertDormant(EventPathWorld w,long filter,String label) {
        Long island=w.registry.owner(filter);assertNotNull(island,label+": a pruned filter keeps its cake in an island");
        assertEquals(List.of(filter),w.registry.members(island),label+": in a dormant island of its own");
        assertEquals("NO FLOW: dead-end filter",w.registry.diagnostic(filter),label);
    }

    @Test void aFilterWithNothingPastItLeavesTheCrudeGeneratorsIslandAlone() {
        double[] crude=Arrays.copyOf(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane_nitrogen").crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions(),
                FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition().length);
        var w=new EventPathWorld();w.runTo(662);var nitrogen=EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325);
        w.place(0,0,-2,Kind.VOID,NORTH,PASSIVE,nitrogen,.1);w.place(0,0,-1,Kind.PIPE,NORTH,.1);w.place(0,1,1,Kind.PIPE,NORTH,.1);
        long generator=w.place(-1,-3,1,Kind.GENERATOR,NORTH,PASSIVE,new FluidDeviceSpec(1,388.1713415246347,281494.7083162402,crude),.1);
        w.place(-1,0,1,Kind.PIPE,NORTH,.05);w.place(-1,-1,1,Kind.PIPE,NORTH,.1);long filter=w.place(0,2,1,Kind.FILTER,DOWN,.1);
        w.place(0,0,0,Kind.VOID,NORTH,PASSIVE,nitrogen,.02);w.place(-1,-2,1,Kind.PIPE,NORTH,.1);w.place(0,0,1,Kind.PIPE,NORTH,.05);
        w.runTo(664);
        assertDormant(w,filter,"seed 6");
        assertNull(w.registry.owner(at(w,0,1,1)),"the pipe between the junction and the filter is pruned with it");
        for(var pipe:w.coordinator.observe(w.registry.owner(generator)).graph().pipes())assertNull(pipe.filter(),"the generator's island solves no filter");
        w.runTo(4700);w.assertKeptUp("seed 6 with its dead-end filter pruned");
    }

    @Test void aFilterBranchOnATanksJunctionIsPrunedKeepsItsCakeAndRejoinsWithADestination() {
        var w=new EventPathWorld();w.runTo(617);
        long filter=w.place(0,1,-3,Kind.FILTER,NORTH,.05);long tank=w.place(0,0,0,Kind.RESERVOIR,NORTH,.05);w.place(0,1,1,Kind.PIPE,NORTH,.05);
        w.place(0,3,1,Kind.GENERATOR,NORTH,PASSIVE,EventPathWorld.pure(MaterialTestBasis.NETWORK,298.15,101325),.05);
        w.place(0,1,-2,Kind.PIPE,NORTH,.05);w.place(0,1,0,Kind.PIPE,NORTH,.05);w.place(1,0,0,Kind.PIPE,NORTH,.05);w.place(0,2,1,Kind.PIPE,NORTH,.05);
        w.place(0,1,-1,Kind.PIPE,NORTH,.05);w.place(2,0,0,Kind.PIPE,NORTH,.05);
        w.runTo(619);
        assertDormant(w,filter,"seed 12");long line=w.registry.owner(tank);
        assertEquals(5,w.registry.members(line).size(),"the tank's island is the generator, three pipes and the tank: "+w.registry.members(line));
        assertNull(w.registry.owner(at(w,0,1,-1)));assertNull(w.registry.owner(at(w,0,1,-2)));
        var cake=w.registry.cake(filter);
        // A dead-end pipe on the tank's line recompiles it; the walk passes the pruned branch to the dormant filter,
        // which stays as it is.
        w.runTo(4700);w.assertKeptUp("seed 12 with its filter branch pruned");
        long dormant=w.registry.owner(filter);w.place(-1,1,1,Kind.PIPE,NORTH,.05);w.runTo(4900);
        assertNotEquals(line,w.registry.owner(tank),"the edit replaced the tank's island");
        assertEquals(dormant,w.registry.owner(filter),"the dormant filter's island is not replaced");assertEquals(cake,w.registry.cake(filter));
        // A pipe and a void past the filter give its branch a destination: it rejoins the tank's island with its cake. That island is
        // then seed 12's held world with a void added (hold class E1c, which this batch does not change): no keep-up here.
        w.place(0,1,-4,Kind.PIPE,NORTH,.05);w.place(0,1,-5,Kind.VOID,NORTH,PASSIVE,EventPathWorld.pure(MaterialTestBasis.NITROGEN,298.15,101325),.05);w.runTo(5100);
        assertTrue(w.ledger.events().isEmpty(),"the void is applied: "+w.ledger.events());
        assertEquals(w.registry.owner(tank),w.registry.owner(filter),"the filter's branch reaches a void: it is solved with the tank's line");
        assertEquals(cake,w.registry.cake(filter),"with the cake it kept");
    }
}
