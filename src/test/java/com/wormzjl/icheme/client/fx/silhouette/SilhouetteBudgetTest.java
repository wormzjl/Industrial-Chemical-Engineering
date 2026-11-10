package com.wormzjl.icheme.client.fx.silhouette;

import com.wormzjl.icheme.runtime.fluid.Silhouettes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Plan section 4.7 (batch 2026-11-10-custom-shader-system, W10): at most 50,000 boxes, hulls first, near sets to the nearest. */
class SilhouetteBudgetTest {
    @Test void everyHullFitsAndNearSetsGoToTheNearestWithinTheBudget() {
        // The most structures a client holds, each with a large near set: 256 x (400 + 16) > 50,000.
        var candidates=new ArrayList<SilhouetteBudget.Candidate>();
        for(int k=0;k<Silhouettes.MAXIMUM_STRUCTURES;k++)candidates.add(new SilhouetteBudget.Candidate((k*37)%Silhouettes.MAXIMUM_STRUCTURES,400,SilhouetteShapes.HULL_BOXES));
        var tiers=SilhouetteBudget.plan(candidates,SilhouetteBudget.BOXES);
        int total=0;double farthestNear=-1,nearestHullOnly=Double.MAX_VALUE;
        for(int k=0;k<tiers.length;k++){
            var c=candidates.get(k);
            assertTrue(tiers[k]!=SilhouetteBudget.Tier.NONE,"every hull fits");
            total+=c.far()+(tiers[k]==SilhouetteBudget.Tier.NEAR?c.near():0);
            if(tiers[k]==SilhouetteBudget.Tier.NEAR)farthestNear=Math.max(farthestNear,c.distance());
            else nearestHullOnly=Math.min(nearestHullOnly,c.distance());
        }
        assertTrue(total<=SilhouetteBudget.BOXES,"boxes: "+total);
        assertTrue(farthestNear<nearestHullOnly,"near sets go to the nearest structures");
        assertTrue(total>SilhouetteBudget.BOXES-400,"the budget is used");
    }

    @Test void hullsComeBeforeNearSetsAndNothingOverflows() {
        var candidates=List.of(new SilhouetteBudget.Candidate(1,30,10),new SilhouetteBudget.Candidate(2,30,10),new SilhouetteBudget.Candidate(3,30,10));
        var tiers=SilhouetteBudget.plan(candidates,65);
        assertEquals(List.of(SilhouetteBudget.Tier.NEAR,SilhouetteBudget.Tier.HULL,SilhouetteBudget.Tier.HULL),List.of(tiers));
        assertEquals(List.of(SilhouetteBudget.Tier.HULL,SilhouetteBudget.Tier.NONE),List.of(SilhouetteBudget.plan(List.of(new SilhouetteBudget.Candidate(1,30,10),new SilhouetteBudget.Candidate(2,30,10)),15)));
        assertEquals(List.of(SilhouetteBudget.Tier.HULL),List.of(SilhouetteBudget.plan(List.of(new SilhouetteBudget.Candidate(1,0,10)),100)),"no near set: the hull alone");
    }
}
