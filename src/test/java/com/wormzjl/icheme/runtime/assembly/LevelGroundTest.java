package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.assembly.PortGraph.Fitting;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the walker reads a registered pipe (batch 2026-10-26-multi-machine-framework): by the law the pipe compiles
 * through, so every valve type the fluid network has (a pressure valve, a control valve with or without its module) is
 * the assembly's control valve.
 */
class LevelGroundTest {
    private static final PhysicalFluidTopology.Position AT = new PhysicalFluidTopology.Position("minecraft:overworld", 0, 0, 0);
    private static final PipeResistance.Geometry BORE = new PipeResistance.Geometry(1, .05, .000045, 0);

    private static PhysicalFluidTopology.Device pipe(FlowControl control, PhysicalFluidTopology.Cutoff cutoff, boolean filter) {
        return PhysicalFluidTopology.Device.builder(1, AT, Kind.PIPE, PhysicalFluidTopology.Direction.NORTH, BORE, control).cutoff(cutoff).inlineFilter(filter).build();
    }

    private static Fitting fitting(FlowControl control) {
        var piece = LevelGround.piece(pipe(control, PhysicalFluidTopology.Cutoff.NONE, false));
        assertNotNull(piece);
        assertTrue(piece.pipe());
        return piece.fitting();
    }

    @Test
    void aPipeReadsAsTheFittingItsLawNames() {
        assertEquals(Fitting.CONTROL_VALVE, fitting(new FlowControl.PressureValve(200000)));
        assertEquals(Fitting.INLINE_PUMP, fitting(PhysicalFluidTopology.defaultMover(Kind.PUMP)));
        assertEquals(Fitting.INLINE_PUMP, fitting(PhysicalFluidTopology.defaultMover(Kind.COMPRESSOR)));
        assertNull(fitting(new FlowControl.Passive()), "a bare pipe passes the line on");
        assertEquals(Fitting.OTHER, LevelGround.piece(pipe(new FlowControl.Passive(), PhysicalFluidTopology.Cutoff.CHECK, false)).fitting());
        assertEquals(Fitting.OTHER, LevelGround.piece(pipe(new FlowControl.Passive(), PhysicalFluidTopology.Cutoff.NONE, true)).fitting());
    }
}
