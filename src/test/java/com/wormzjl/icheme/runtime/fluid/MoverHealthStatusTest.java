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
        assertTrue(PumpHealth.worn(DRY_SCREW));
        assertEquals("FAILED: use a repair kit / RUNNING / NORMAL",FluidWorldAuthority.withHealth("RUNNING / NORMAL",DRY_SCREW,"FAILED"));
        assertEquals("RUNNING / NORMAL / LIQUID",FluidWorldAuthority.withHealth("RUNNING / NORMAL",DRY_SCREW,"LIQUID"));
        assertEquals("RUNNING / NORMAL",FluidWorldAuthority.withHealth("RUNNING / NORMAL",DRY_SCREW,""));
    }
    @Test void aLiquidPumpReadsItsWordAsBefore() {
        assertEquals("FAILED: use a repair kit / RUNNING",FluidWorldAuthority.withHealth("RUNNING",PUMP,"FAILED"));
        assertEquals("RUNNING / CAVITATION",FluidWorldAuthority.withHealth("RUNNING",PUMP,"CAVITATION"));
    }
    @Test void aMoverThatDoesNotWearKeepsItsLine() {
        assertFalse(PumpHealth.worn(LIQUID_RING));
        assertEquals("RUNNING / NORMAL",FluidWorldAuthority.withHealth("RUNNING / NORMAL",LIQUID_RING,"FAILED"));
    }
}
