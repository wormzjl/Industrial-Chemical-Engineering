package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.EAST;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Code base review R1: an island presented on the world list with one axis map for the whole island is, part by part, what
 * each part padded alone gives, bit for bit; and an instrument reads the observations of the stored last result, which the
 * presentation carries unchanged, so its read builds no snapshot.
 */
class IslandSetsPresentedTest {
    @AfterEach void counters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}

    @Test void aPresentedIslandIsEachOfItsPartsPaddedAloneAndAnInstrumentReadsTheStoredResult() {
        var w=new EventPathWorld();
        w.place(0,64,0,Kind.GENERATOR,EAST,FluidDeviceSpec.nitrogen(w.catalog),.05);
        w.place(1,64,0,Kind.PIPE,EAST,.05);long sink=w.place(2,64,0,Kind.VOID,EAST,FluidDeviceSpec.nitrogen(w.catalog),.05);
        w.runTo(400);
        long island=w.registry.owner(sink);var from=w.coordinator.model(island);
        assertNotEquals(w.model.compounds(),from.compounds(),"the island carries its own compound list");
        var stored=w.coordinator.observe(island);var last=stored.lastResult().orElseThrow();
        var presentation=new IslandSets.Presentation(w.model);
        var shown=presentation.snapshot(stored,from);
        var statusChange=new IslandCoordinator.Snapshot(island,stored.revision(),stored.graph(),stored.clock(),stored.lastResult(),"WAITING");
        var updated=presentation.snapshot(statusChange,from);
        assertEquals("WAITING",updated.status());
        assertSame(shown.graph(),updated.graph());
        assertSame(shown.lastResult().orElseThrow(),updated.lastResult().orElseThrow());
        presentation.clear();
        assertNotSame(shown.graph(),presentation.snapshot(stored,from).graph());
        for(int i=0;i<stored.graph().reservoirs().size();i++)
            assertEquals(bits(IslandSets.paddedNode(stored.graph().reservoirs().get(i),from,w.model)),bits(shown.graph().reservoirs().get(i)),"node "+i);
        var result=shown.lastResult().orElseThrow();
        assertEquals(stored.graph()==last.graph(),shown.graph()==result.graph(),"a preview can have a different graph from its last solve");
        if(stored.graph()!=last.graph()) {
            var atLastSolve=new IslandCoordinator.Snapshot(island,stored.revision(),last.graph(),stored.clock(),stored.lastResult(),stored.status());
            var projected=IslandSets.presented(atLastSolve,from,w.model);
            assertSame(projected.graph(),projected.lastResult().orElseThrow().graph(),"one projection for a shared source graph");
        }
        // A generator to a void books no boundary transfer (its ends are reservoirs); any there is checked.
        for(int i=0;i<last.boundaries().size();i++)
            assertArrayEquals(IslandSets.boundary(last.boundaries().get(i),from,w.model).moles(),result.boundaries().get(i).moles(),"boundary "+i);
        assertFalse(last.pipeTransfers().isEmpty());
        for(int i=0;i<last.pipeTransfers().size();i++) {
            var t=last.pipeTransfers().get(i);var u=result.pipeTransfers().get(i);
            var forward=t.forward().phaseMoles();var shownForward=u.forward().phaseMoles();
            var reverse=t.reverse().phaseMoles();var shownReverse=u.reverse().phaseMoles();
            for(int phase=0;phase<forward.length;phase++) {
                assertArrayEquals(IslandSets.amounts(forward[phase],from,w.model),shownForward[phase],"forward "+i+" phase "+phase);
                assertArrayEquals(IslandSets.amounts(reverse[phase],from,w.model),shownReverse[phase],"reverse "+i+" phase "+phase);
            }
        }
        assertSame(last.observations(),result.observations(),"the presentation carries the observations as stored");
        assertSame(last.observations(),w.coordinator.lastResult(island).orElseThrow().observations());
        FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;
        w.coordinator.lastResult(island);
        assertEquals(0L,(long)FluidRuntimeDiagnostics.sample().get("islandSnapshots"),"an instrument's read builds no snapshot");
    }
    /** A node's state and inventory, bit for bit. */
    private static String bits(PassiveNetwork.Reservoir n) {
        var s=n.state();
        return n.id()+":"+Double.doubleToLongBits(s.temperature())+","+Double.doubleToLongBits(s.pressure())+","+Arrays.toString(s.vaporAmountsView())+Arrays.toString(s.liquidAmountsView())+Arrays.toString(s.aqueousAmountsView())
                +(s.vaporPhase()==null?"-":Arrays.toString(s.vaporPhase().logFugacityView()))+(s.liquidPhase()==null?"-":Arrays.toString(s.liquidPhase().logFugacityView()))
                +"|"+Arrays.toString(n.inventory().moles())+","+Double.doubleToLongBits(n.inventory().internalEnergy());
    }
}
