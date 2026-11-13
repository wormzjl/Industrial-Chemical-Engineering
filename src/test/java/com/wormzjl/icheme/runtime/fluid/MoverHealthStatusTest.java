package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.machine.VacuumPump;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A device page's status line carries the health word of every mover that wears (code base review M-02): a liquid mover
 * and a dry screw vacuum pump (owner V6 B), never one that does not wear.
 */
class MoverHealthStatusTest {
    private static final FlowControl.Mover DRY_SCREW=new FlowControl.Machine(new VacuumPump(VacuumPump.Type.DRY_SCREW,300/3600.,2950),1,true);
    private static final FlowControl.Mover LIQUID_RING=new FlowControl.Machine(new VacuumPump(VacuumPump.Type.LIQUID_RING_ONE_STAGE,500/3600.,1450),1,true);
    private static final FlowControl.Mover PUMP=new FlowControl.Pump(.01,500000);

    @Test void aFailedDryScrewReadsFailed() {
        assertTrue(MachineHealth.worn(DRY_SCREW));
        assertEquals("FAILED: use a repair kit / RUNNING / NORMAL",FluidWorldAuthority.withHealth("RUNNING / NORMAL",DRY_SCREW,"FAILED"));
        assertEquals("RUNNING / NORMAL / LIQUID",FluidWorldAuthority.withHealth("RUNNING / NORMAL",DRY_SCREW,"LIQUID"));
        assertEquals("RUNNING / NORMAL",FluidWorldAuthority.withHealth("RUNNING / NORMAL",DRY_SCREW,""));
    }
    @Test void aLiquidPumpReadsItsWordAsBefore() {
        assertEquals("FAILED: use a repair kit / RUNNING",FluidWorldAuthority.withHealth("RUNNING",PUMP,"FAILED"));
        assertEquals("RUNNING / CAVITATION",FluidWorldAuthority.withHealth("RUNNING",PUMP,"CAVITATION"));
    }
    @Test void aMoverThatDoesNotWearKeepsItsLine() {
        assertFalse(MachineHealth.worn(LIQUID_RING));
        assertEquals("RUNNING / NORMAL",FluidWorldAuthority.withHealth("RUNNING / NORMAL",LIQUID_RING,"FAILED"));
        assertFalse(MachineHealth.worn(FRAME),"a reciprocating frame has no wear mode");
        assertEquals("RUNNING / NORMAL",FluidWorldAuthority.withHealth("RUNNING / NORMAL",FRAME,"FAILED"));
    }
    private static final FlowControl.Mover CENTRIFUGAL_COMPRESSOR=new FlowControl.Machine(new com.wormzjl.icheme.science.machine.CentrifugalCompressor(.02,24400,.78),1,true);
    private static final FlowControl.Mover FRAME=new FlowControl.Compressor(.1,3);
    /** N2: a centrifugal compressor wears on wet gas and says so on its line. */
    @Test void aCentrifugalCompressorReadsWetGas() {
        assertTrue(MachineHealth.worn(CENTRIFUGAL_COMPRESSOR));
        assertEquals(java.util.List.of(com.wormzjl.icheme.science.machine.Wear.Fault.WET_GAS),MachineHealth.faults(CENTRIFUGAL_COMPRESSOR));
        assertEquals("RUNNING / MOVER_LAW / WET GAS",FluidWorldAuthority.withHealth("RUNNING / MOVER_LAW",CENTRIFUGAL_COMPRESSOR,"WET GAS"));
        assertEquals("FAILED: use a repair kit / RUNNING / MOVER_LAW",FluidWorldAuthority.withHealth("RUNNING / MOVER_LAW",CENTRIFUGAL_COMPRESSOR,"FAILED"));
    }
}
