package com.wormzjl.icheme.client.fx.silhouette;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Which silhouettes DH draws, within {@link #BOXES} boxes for all structures together (batch 2026-11-10-custom-shader-system,
 * W10, plan section 4.7). Every structure's hull comes first, the nearest first; then the near boxes, the nearest first,
 * while they fit. A structure whose near set does not fit shows its hull at every distance.
 */
public final class SilhouetteBudget {
    /** The most boxes registered with DH at once. */
    public static final int BOXES=50_000;

    /** What a structure registers: nothing, its hull, or its hull and its near boxes. */
    public enum Tier {NONE,HULL,NEAR}

    /** One structure: its distance from the player (any monotonic measure) and its box counts. */
    public record Candidate(double distance,int near,int far) {}

    private SilhouetteBudget(){}

    /** The tier of each candidate, in their order, within {@code budget} boxes. */
    public static Tier[] plan(List<Candidate> candidates,int budget) {
        var order=new Integer[candidates.size()];
        for(int k=0;k<order.length;k++)order[k]=k;
        Arrays.sort(order,Comparator.comparingDouble(k->candidates.get(k).distance()));
        var tiers=new Tier[order.length];Arrays.fill(tiers,Tier.NONE);
        int left=budget;
        for(int k:order){var c=candidates.get(k);if(c.far()>0&&c.far()<=left){tiers[k]=Tier.HULL;left-=c.far();}}
        for(int k:order){var c=candidates.get(k);if(tiers[k]==Tier.HULL&&c.near()>0&&c.near()<=left){tiers[k]=Tier.NEAR;left-=c.near();}}
        return tiers;
    }
}
