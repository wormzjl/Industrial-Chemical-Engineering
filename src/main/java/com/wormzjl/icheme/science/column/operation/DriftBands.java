package com.wormzjl.icheme.science.column.operation;

import java.util.Optional;

/**
 * When a running column solves again (batch 2026-10-28-column-operation, plan section 6, bands set by C1): the intensive
 * state it takes has left a band round the table's reference. A throughput change alone never solves (the column's
 * equations are homogeneous in its extensive inputs) except past {@link #THROUGHPUT}, where the trays' pressure drop moves
 * the sump.
 *
 * <p>The table's error against drift is first order (C1 review, section 2): 1 K of feed enthalpy or 0.5 % of composition
 * moves a product by about 1 to 2.5 %. A triggered warm solve costs 0.05 to 0.6 s.
 */
public final class DriftBands {
    /** The feed's composition: the sum of |z - z_ref| over the axis. */
    public static final double COMPOSITION = .005;
    /** The feed's specific enthalpy, in kelvin of its own slope. */
    public static final double ENTHALPY_KELVIN = 1;
    /** A steam inlet's ratio to the feed, relative. */
    public static final double STEAM_RATIO = .02;
    /** The throughput relative to the design rate. */
    public static final double THROUGHPUT = .2;
    /** The closed condenser duty against the solved one, relative. */
    public static final double CLOSURE = .03;
    /** Trace share below which a component the table borrowed a row for does not ask for a solve. */
    public static final double NEW_COMPONENT = 1e-6;

    private DriftBands() {
    }

    /** Why a column solves: the band left, or another cause. */
    public enum Trigger {
        START, SETPOINT, COMPONENT, COMPOSITION, ENTHALPY, STEAM_RATIO, THROUGHPUT, CLOSURE
    }

    /** A trigger and the value that crossed its band. */
    public record Crossing(Trigger trigger, double value) {
    }

    /**
     * The first band {@code step} leaves round {@code table}'s reference, or empty while it stays inside.
     *
     * @param designDemand the feed's set demand at the design rate over the step, mol (for the throughput)
     */
    public static Optional<Crossing> check(SplitTable table, ColumnCycle.Step step, double designDemand) {
        var state = check(table.reference(), table.held(), step, designDemand);
        if (state.isPresent() || !(step.feedMoles() > 0)) return state;
        if (Math.abs(step.departure()) > CLOSURE) return Optional.of(new Crossing(Trigger.CLOSURE, step.departure()));
        return Optional.empty();
    }

    /**
     * The first band of the state {@code step} took (component, composition, enthalpy, steam ratio, throughput) it leaves round
     * {@code reference}, whose solve held the components {@code held}; empty while it stays inside. The closure is the table's,
     * not the state's: W2 asks this of a refused state.
     */
    public static Optional<Crossing> check(SplitTable.Reference reference, boolean[] held, ColumnCycle.Step step, double designDemand) {
        double fed = step.feedMoles();
        if (!(fed > 0)) return Optional.empty();
        double[] feed = step.feed().moles();
        double[] z = reference.feedFractions();
        double l1 = 0;
        for (int i = 0; i < feed.length; i++) {
            double share = feed[i] / fed;
            if (!held[i] && share > NEW_COMPONENT) return Optional.of(new Crossing(Trigger.COMPONENT, share));
            l1 += Math.abs(share - z[i]);
        }
        for (int j = 0; j < step.steam().size(); j++) {
            double s = step.steam().get(j).total();
            for (int i = 0; i < feed.length; i++) {
                double n = step.steam().get(j).moles()[i];
                if (!held[i] && n > NEW_COMPONENT * Math.max(s, fed)) return Optional.of(new Crossing(Trigger.COMPONENT, n / Math.max(s, fed)));
            }
        }
        if (l1 > COMPOSITION) return Optional.of(new Crossing(Trigger.COMPOSITION, l1));
        double h = step.feed().energy() / fed;
        double slope = Math.abs(reference.enthalpySlope());
        if (slope > 0 && Math.abs(h - reference.feedEnthalpy()) > ENTHALPY_KELVIN * slope)
            return Optional.of(new Crossing(Trigger.ENTHALPY, (h - reference.feedEnthalpy()) / slope));
        double[] ratios = reference.steamRatios();
        double[] set = reference.setpoints().steamPerFeed();
        for (int j = 0; j < step.steam().size() && j < ratios.length; j++) {
            double ratio = step.steam().get(j).total() / fed;
            // Against the larger of the solved and the set ratio: steam that comes where the table solved none (a start before the
            // steam line filled) or goes where it solved some is a crossing too.
            double scale = Math.max(ratios[j], j < set.length ? set[j] : 0);
            if (scale > 0 && Math.abs(ratio - ratios[j]) / scale > STEAM_RATIO) return Optional.of(new Crossing(Trigger.STEAM_RATIO, (ratio - ratios[j]) / scale));
        }
        double alpha = designDemand > 0 ? fed / designDemand : 0;
        if (Math.abs(alpha - reference.alpha()) > THROUGHPUT) return Optional.of(new Crossing(Trigger.THROUGHPUT, alpha));
        return Optional.empty();
    }

    /**
     * The state {@code step} took as a reference for {@link #check(SplitTable.Reference, boolean[], ColumnCycle.Step, double)}
     * (W2: a refused state): its feed's shares and specific enthalpy, each steam's ratio, its throughput against
     * {@code designDemand}, at set points {@code setpoints}; {@code enthalpySlope} is the feed's, J/(mol K) (0: unknown, no
     * enthalpy band). Its temperatures are unknown.
     */
    public static SplitTable.Reference reference(ColumnCycle.Step step, double designDemand, Setpoints setpoints, double enthalpySlope) {
        double fed = step.feedMoles();
        double[] z = step.feed().moles();
        for (int i = 0; i < z.length; i++) z[i] = fed > 0 ? z[i] / fed : 0;
        double[] ratios = new double[step.steam().size()];
        for (int j = 0; j < ratios.length; j++) ratios[j] = fed > 0 ? step.steam().get(j).total() / fed : 0;
        return new SplitTable.Reference(z, fed > 0 ? step.feed().energy() / fed : 0, enthalpySlope, ratios, designDemand > 0 ? fed / designDemand : 0, setpoints,
                Double.NaN, new double[ratios.length]);
    }
}
