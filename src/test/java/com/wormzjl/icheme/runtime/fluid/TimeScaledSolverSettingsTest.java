package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialTestBasis;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The solver's time constants under the time factor (batch 2026-11-11-minecraft-time-delta, W2; owner "Holdup only"): the
 * interval's first step and step cap and a junction's buffer scale with the factor, nothing else does, and factor 1 is the
 * engine's default exactly. The world is {@link EmptyTankFillsTest}'s: a junction on a tank's top face below a water generator.
 */
class TimeScaledSolverSettingsTest {
    private final FluidThermodynamics model=FluidTestSupport.networkModel();

    @Test void theStepSettingsScaleWithTheFactorAndFactorOneIsTheDefault() {
        assertEquals(PassiveIntervalSolver.Settings.defaults(),PassiveIntervalSolver.Settings.defaults(SimulationTime.ONLINE));
        assertEquals(new PassiveIntervalSolver.Settings(4,80,1024),PassiveIntervalSolver.Settings.defaults(SimulationTime.of(4)));
        assertEquals(new PassiveIntervalSolver.Settings(72,1440,1024),PassiveIntervalSolver.Settings.defaults(SimulationTime.of(72)));
    }

    @Test void aJunctionsBufferScalesWithTheFactorAndNothingElseOfTheGraphDoes() {
        assertSame(model,model.withTime(SimulationTime.ONLINE));
        var one=world(model);
        for(int factor:new int[]{4,72}) {
            var timed=model.withTime(SimulationTime.of(factor));
            assertEquals(factor,timed.time().factor());assertEquals(model.thermodynamicRevision(),timed.thermodynamicRevision());
            var graph=world(timed);int junctions=0;
            for(int i=0;i<graph.reservoirs().size();i++) {
                var a=one.reservoirs().get(i).inventory();var b=graph.reservoirs().get(i).inventory();
                if(one.reservoirs().get(i).junction()) {
                    junctions++;
                    assertEquals(factor,Arrays.stream(b.moles()).sum()/Arrays.stream(a.moles()).sum(),1e-12*factor,"the buffer holds "+factor+" times the holdup time");
                    assertEquals(factor,b.internalEnergy()/a.internalEnergy(),1e-12*factor);
                } else {
                    assertArrayEquals(a.moles(),b.moles(),0,"a vessel's contents do not depend on the factor");assertEquals(a.internalEnergy(),b.internalEnergy(),0);
                }
            }
            assertEquals(1,junctions,"the world's one junction");
        }
    }

    /** {@link EmptyTankFillsTest#world()}'s seven blocks on {@code model}, compiled and minted. */
    private static PassiveNetwork world(FluidThermodynamics model) {
        var devices=List.of(EmptyTankFillsTest.device(1,0,3,1,Kind.GENERATOR),EmptyTankFillsTest.device(2,0,2,1,Kind.PIPE),EmptyTankFillsTest.device(3,0,1,1,Kind.PIPE),
                EmptyTankFillsTest.device(4,0,1,0,Kind.PIPE),EmptyTankFillsTest.device(5,0,1,-1,Kind.PIPE),EmptyTankFillsTest.device(6,0,0,-1,Kind.PIPE),EmptyTankFillsTest.device(7,0,0,0,Kind.RESERVOIR));
        var boundaries=new LinkedHashMap<Long,PassiveNetwork.Reservoir>();
        boundaries.put(1L,new FluidDeviceSpec(1,298.15,101325,EmptyTankFillsTest.only(MaterialTestBasis.NETWORK)).initialize(devices.getFirst(),model,()->{}));
        boundaries.put(7L,new FluidDeviceSpec(1,298.15,101325,EmptyTankFillsTest.only(MaterialTestBasis.NITROGEN)).initialize(devices.getLast(),model,()->{}));
        var compiled=PhysicalFluidTopology.compile(devices,boundaries,Map.of(),NetworkReduction.ambient(model));
        return PassiveNetwork.mintJunctions(compiled.islands().stream().filter(i->i.physicalIds().contains(7L)).findFirst().orElseThrow().graph(),model);
    }
}
