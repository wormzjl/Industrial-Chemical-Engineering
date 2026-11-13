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
        var shown=IslandSets.presented(stored,from,w.model);
        for(int i=0;i<stored.graph().reservoirs().size();i++)
            assertEquals(bits(IslandSets.paddedNode(stored.graph().reservoirs().get(i),from,w.model)),bits(shown.graph().reservoirs().get(i)),"node "+i);
        var result=shown.lastResult().orElseThrow();
        // A generator to a void books no boundary transfer (its ends are reservoirs); any there is checked.
        for(int i=0;i<last.boundaries().size();i++)
            assertArrayEquals(IslandSets.boundary(last.boundaries().get(i),from,w.model).moles(),result.boundaries().get(i).moles(),"boundary "+i);
        assertFalse(last.pipeTransfers().isEmpty());
        for(int i=0;i<last.pipeTransfers().size();i++) {
            var t=last.pipeTransfers().get(i);var u=result.pipeTransfers().get(i);
            assertTrue(Arrays.deepEquals(IslandSets.stream(t.forward(),from,w.model).phaseMoles(),u.forward().phaseMoles()),"forward "+i);
            assertTrue(Arrays.deepEquals(IslandSets.stream(t.reverse(),from,w.model).phaseMoles(),u.reverse().phaseMoles()),"reverse "+i);
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
