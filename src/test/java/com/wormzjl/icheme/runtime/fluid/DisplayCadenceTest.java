package com.wormzjl.icheme.runtime.fluid;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Code base review R10: a device display publishes once per presentation cadence, at its identity's slot, as buckets do. */
class DisplayCadenceTest {
    @Test void aDisplayPublishesOncePerCadenceAtItsSlot() {
        for(long id:new long[]{0,1,99,100,12_345,-7,Long.MAX_VALUE}) {
            int due=0;long at=-1;
            for(long tick=5_000;tick<5_000+FluidPresentation.BUCKET_TICKS;tick++)if(FluidWorldAuthority.displayDue(id,tick)){due++;at=tick;}
            assertEquals(1,due,"device "+id);
            assertEquals(Math.floorMod(id,(long)FluidPresentation.BUCKET_TICKS),Math.floorMod(at,(long)FluidPresentation.BUCKET_TICKS),"device "+id);
        }
        assertNotEquals(FluidWorldAuthority.displayDue(1,100),FluidWorldAuthority.displayDue(2,100),"devices spread over the cadence");
    }
}
