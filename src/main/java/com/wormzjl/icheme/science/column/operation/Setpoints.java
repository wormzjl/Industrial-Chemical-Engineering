package com.wormzjl.icheme.science.column.operation;

import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import java.util.Arrays;

/**
 * A running column's setpoints (plan sections 7 and 13; owner Q2: per unit feed, ratio control). The feed rate is set
 * in mol/s and capped at the design rate (owner Q8, no turndown floor); every other extensive value is held per mole of
 * feed taken, so a feed shortage scales them all. A fact of the column unit.
 *
 * @param designFeedRate  the operating point's feed rate, mol/s: the cap
 * @param feedRate        the set feed rate, mol/s, 0 to the design rate
 * @param steamPerFeed    mol of steam per mol of feed, per steam inlet
 * @param drawsPerFeed    mol drawn per mol of feed, per side draw
 * @param reboilerPerFeed J per mol of feed
 * @param pumparoundsPerFeed J per mol of feed, per pumparound (negative: cooling)
 */
public record Setpoints(double designFeedRate, double feedRate, double condenserTemperature, double refluxRatio, double reboilerPerFeed,
                        double[] steamPerFeed, double[] drawsPerFeed, double[] pumparoundsPerFeed) {
    public Setpoints {
        steamPerFeed = steamPerFeed.clone();
        drawsPerFeed = drawsPerFeed.clone();
        pumparoundsPerFeed = pumparoundsPerFeed.clone();
        if (!(designFeedRate > 0) || !Double.isFinite(designFeedRate)) throw new IllegalArgumentException("A design feed rate above 0");
        if (!(feedRate >= 0) || feedRate > designFeedRate * (1 + 1e-12)) throw new IllegalArgumentException("A feed rate of 0 to the design rate");
        if (!(condenserTemperature > 0) || !(refluxRatio >= 0) || !Double.isFinite(refluxRatio) || !Double.isFinite(reboilerPerFeed))
            throw new IllegalArgumentException("A condenser temperature above 0, a reflux of 0 or more, a finite reboiler duty");
        for (double s : steamPerFeed) if (!(s >= 0) || !Double.isFinite(s)) throw new IllegalArgumentException("A steam ratio of 0 or more");
        for (double d : drawsPerFeed) if (!(d > 0) || !Double.isFinite(d)) throw new IllegalArgumentException("A draw ratio above 0");
        for (double q : pumparoundsPerFeed) if (!Double.isFinite(q) || q == 0) throw new IllegalArgumentException("A pumparound duty other than 0");
    }

    /** The setpoints of a design's operating point: its rate, and its values per mole of its feed. */
    public static Setpoints of(ColumnOperatingPoint point) {
        double f = point.feedRate();
        return new Setpoints(f, f, point.condenserTemperature(), point.refluxRatio(), point.reboilerDuty() / f,
                point.steam().stream().mapToDouble(s -> s.flow() / f).toArray(), point.draws().stream().mapToDouble(d -> d / f).toArray(),
                point.pumparounds().stream().mapToDouble(p -> p.duty() / f).toArray());
    }

    /** These setpoints with the feed rate {@code rate}, mol/s, clamped to 0 and the design rate. */
    public Setpoints withFeedRate(double rate) {
        return new Setpoints(designFeedRate, Math.max(0, Math.min(designFeedRate, rate)), condenserTemperature, refluxRatio, reboilerPerFeed,
                steamPerFeed, drawsPerFeed, pumparoundsPerFeed);
    }

    @Override
    public double[] steamPerFeed() {
        return steamPerFeed.clone();
    }

    @Override
    public double[] drawsPerFeed() {
        return drawsPerFeed.clone();
    }

    @Override
    public double[] pumparoundsPerFeed() {
        return pumparoundsPerFeed.clone();
    }

    /** Whether another solve is needed for {@code other}: any value but the feed rate differs (a throughput alone never solves). */
    public boolean intensivelyDiffers(Setpoints other) {
        return condenserTemperature != other.condenserTemperature || refluxRatio != other.refluxRatio || reboilerPerFeed != other.reboilerPerFeed
                || !Arrays.equals(steamPerFeed, other.steamPerFeed) || !Arrays.equals(drawsPerFeed, other.drawsPerFeed)
                || !Arrays.equals(pumparoundsPerFeed, other.pumparoundsPerFeed);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Setpoints s && designFeedRate == s.designFeedRate && feedRate == s.feedRate && !intensivelyDiffers(s);
    }

    @Override
    public int hashCode() {
        return Double.hashCode(feedRate) * 31 + Double.hashCode(condenserTemperature);
    }
}
