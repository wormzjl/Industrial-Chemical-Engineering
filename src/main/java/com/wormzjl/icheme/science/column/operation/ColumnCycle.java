package com.wormzjl.icheme.science.column.operation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One cycle of a running column (batch 2026-10-28-column-operation, plan sections 5 and 7): take from the inlet accounts at
 * most the set rates, split what was taken by the table, and close the energy on the condenser. Minecraft-free and pure:
 * the runtime gives it the accounts and books what it returns.
 *
 * <p>Conservation holds by construction: every component taken goes, split by rows that sum to one, into the products;
 * the energy taken plus the reboiler and pumparound duties plus the condenser duty equals the products' energy, because
 * the condenser duty is that difference. An account is never drawn below empty.
 *
 * <p>Short inputs (owner Q1, decided after C1): rule B takes the feed and each steam as available up to its rate (a short
 * steam is a new intensive state, which the drift bands send to a solve); rule A, where a solve on the new ratios failed,
 * takes every input at one throughput {@code alpha}. A full product buffer cuts the throughput under both rules.
 */
public final class ColumnCycle {
    /** Trip when the closed condenser duty departs from the solved one by more than this share (plan section 6). */
    public static final double TRIP_DEPARTURE = .5;

    private ColumnCycle() {
    }

    /** The energy of a product's moles at its solved temperature and port pressure, J, in the network's own model. */
    @FunctionalInterface
    public interface ProductEnergy {
        double energy(int product, double[] moles);
    }

    /** An account's contents: moles on the axis and their energy, J. */
    public record Account(double[] moles, double energy) {
        public Account {
            moles = moles.clone();
            for (double n : moles) if (!(n >= 0) || !Double.isFinite(n)) throw new IllegalArgumentException("An account's moles are finite and not negative");
            if (!Double.isFinite(energy)) throw new IllegalArgumentException("An account's energy is finite");
        }

        public static Account empty(int components) {
            return new Account(new double[components], 0);
        }

        @Override
        public double[] moles() {
            return moles.clone();
        }

        public double total() {
            double t = 0;
            for (double n : moles) t += n;
            return t;
        }

        /** The share {@code fraction} (0 to 1) of this well-mixed account: its moles and its energy alike. */
        public Account part(double fraction) {
            if (fraction >= 1) return this;
            if (!(fraction > 0)) return empty(moles.length);
            double[] out = new double[moles.length];
            for (int i = 0; i < out.length; i++) out[i] = moles[i] * fraction;
            return new Account(out, energy * fraction);
        }

        /** This account less {@code taken} (which came from it), never below empty. */
        public Account less(Account taken) {
            if (taken == this) return empty(moles.length);
            double[] out = new double[moles.length];
            for (int i = 0; i < out.length; i++) out[i] = Math.max(0, moles[i] - taken.moles[i]);
            return new Account(out, energy - taken.energy);
        }

        /** This account with {@code added}. */
        public Account plus(Account added) {
            double[] out = moles.clone();
            for (int i = 0; i < out.length; i++) out[i] += added.moles[i];
            return new Account(out, energy + added.energy);
        }
    }

    /**
     * A cycle's outcome.
     *
     * @param alpha            the feed taken over its set demand, 0 to 1
     * @param feed             taken from the feed account
     * @param steam            taken from each steam account
     * @param products         to each product's account, in the table's order
     * @param reboiler         J into the column
     * @param pumparounds      J into the column per pumparound (negative: removed)
     * @param condenser        J into the column (negative: removed), closing the balance
     * @param solvedCondenser  the table's condenser duty at the feed taken, J
     * @param conditions       what held (W1): each LIMIT with the share of the set feed it allows, in the order the cycle
     *                         weighs them (feed, outlets, condenser water, air face, reboiler, pumparounds, flooding, steam), or the TRIP (the closure would
     *                         heat, or departs too far) with its departure
     */
    public record Step(double alpha, Account feed, List<Account> steam, List<Account> products, double reboiler, double[] pumparounds,
                       double condenser, double solvedCondenser, List<Condition.Active> conditions) {
        public Step {
            steam = List.copyOf(steam);
            products = List.copyOf(products);
            pumparounds = pumparounds.clone();
            conditions = List.copyOf(conditions);
        }

        /** The condition that binds: the TRIP, else the LIMIT with the lowest share; null where the cycle took its set feed. */
        public Condition.Active binding() {
            return Condition.binding(conditions);
        }

        /** Whether the cycle tripped and took nothing. */
        public boolean tripped() {
            var b = binding();
            return b != null && b.condition().rank == Condition.Rank.TRIP;
        }

        /** The closed condenser duty's departure from the solved one, a share of the solved; 0 when nothing ran. */
        public double departure() {
            return solvedCondenser == 0 ? 0 : (condenser - solvedCondenser) / Math.abs(solvedCondenser);
        }

        public double feedMoles() {
            return feed.total();
        }
    }

    /**
     * One cycle.
     *
     * @param seconds the cycle's online time, s
     * @param room    each product account's free room, mol, in the table's order (a full buffer has none)
     * @param ruleA   take every input at one throughput (rule A); otherwise as available (rule B)
     */
    public static Step step(SplitTable table, Setpoints setpoints, double seconds, Account feed, List<Account> steam, double[] room,
                            ProductEnergy energy, boolean ruleA) {
        return step(table, setpoints, seconds, feed, steam, room, Double.POSITIVE_INFINITY, energy, ruleA);
    }

    /**
     * One cycle whose condenser can reject at most {@code condenserRoom} J into its cooling water (C6b: what its water has not
     * yet taken leaves less): a short condenser cuts the throughput as a full outlet does, by the table's solved water duty
     * per feed (the whole duty of a water-cooled condenser, an air-then-water condenser's trim share).
     */
    public static Step step(SplitTable table, Setpoints setpoints, double seconds, Account feed, List<Account> steam, double[] room,
                            double condenserRoom, ProductEnergy energy, boolean ruleA) {
        return step(table, setpoints, seconds, feed, steam, room, condenserRoom, Double.POSITIVE_INFINITY, energy, ruleA);
    }

    /**
     * One cycle whose air condenser holds at most {@code airFeed} mol/s of feed with its fans at full speed (U2,
     * {@code COOLER_MODEL_PLAN.md} section 8; {@link SplitTable#airFeed}): a duty past full fans cuts the throughput as a
     * cooling water shortage does.
     */
    public static Step step(SplitTable table, Setpoints setpoints, double seconds, Account feed, List<Account> steam, double[] room,
                            double condenserRoom, double airFeed, ProductEnergy energy, boolean ruleA) {
        return step(table, setpoints, seconds, feed, steam, room, Rooms.free(airFeed).withCondenser(condenserRoom), energy, ruleA);
    }

    /**
     * What each of a cycle's duties can still pass to its utility, J, and the air's most feed, mol/s (W4): the condenser's
     * cooling water, the reboiler's heating, each pumparound cooler's cooling (in the pumparounds' order; one past the array
     * has none). {@link Double#POSITIVE_INFINITY} where a duty has no utility side: it is free, as every duty but the
     * condenser's was before W4.
     */
    public record Rooms(double condenser, double reboiler, double[] pumparounds, double airFeed) {
        /** Every duty free. */
        public static final Rooms NONE = free(Double.POSITIVE_INFINITY);

        public Rooms {
            pumparounds = pumparounds.clone();
            if (Double.isNaN(condenser) || Double.isNaN(reboiler) || Double.isNaN(airFeed)) throw new IllegalArgumentException("Invalid duty room");
            for (double r : pumparounds) if (Double.isNaN(r)) throw new IllegalArgumentException("Invalid duty room");
        }

        /** Every utility free, the air holding at most {@code airFeed} mol/s of feed. */
        public static Rooms free(double airFeed) {
            return new Rooms(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, new double[0], airFeed);
        }

        public Rooms withCondenser(double value) {
            return new Rooms(value, reboiler, pumparounds, airFeed);
        }

        public Rooms withReboiler(double value) {
            return new Rooms(condenser, value, pumparounds, airFeed);
        }

        public Rooms withPumparounds(double[] value) {
            return new Rooms(condenser, reboiler, value, airFeed);
        }

        @Override
        public double[] pumparounds() {
            return pumparounds.clone();
        }

        /** Pumparound {@code j}'s room, J: unbounded where it has none. */
        public double pumparound(int j) {
            return j >= 0 && j < pumparounds.length ? pumparounds[j] : Double.POSITIVE_INFINITY;
        }
    }

    /**
     * One cycle whose duties pass at most {@code rooms} to their utilities (W4): a duty whose utility is short cuts the
     * throughput as a full outlet does, each share the room over that duty of a full take at the set feed (decision D1: a
     * short reboiler or pumparound utility cuts the feed, no solve).
     */
    public static Step step(SplitTable table, Setpoints setpoints, double seconds, Account feed, List<Account> steam, double[] room,
                            Rooms rooms, ProductEnergy energy, boolean ruleA) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(rooms, "rooms");
        double condenserRoom = rooms.condenser(), airFeed = rooms.airFeed();
        int m = table.axis().size();
        double[] steamPerFeed = setpoints.steamPerFeed();
        if (steam.size() != steamPerFeed.length) throw new IllegalArgumentException("One steam account per steam setpoint");
        if (room.length != table.products().size()) throw new IllegalArgumentException("One room per product");
        double demand = setpoints.feedRate() * seconds;
        if (!(demand > 0)) return idle(table, steam.size(), List.of());
        // The throughput the outlets allow, from the products of a full take at the set demand: the feed at its composition and
        // each steam at its ratio, whatever the accounts hold, so every share is a share of the set feed (a short feed with a
        // tight outlet weighs the outlet against the whole demand, and the cut never overfills its buffer).
        double feedTotal = feed.total();
        double[] full = new double[m];
        if (feedTotal > 0) {
            double[] n = feed.moles();
            for (int i = 0; i < m; i++) full[i] = n[i] * demand / feedTotal;
        }
        for (int j = 0; j < steam.size(); j++) {
            double s = steam.get(j).total();
            if (s > 0) {
                double[] n = steam.get(j).moles();
                for (int i = 0; i < m; i++) full[i] += n[i] * steamPerFeed[j] * demand / s;
            }
        }
        double[][] fullProducts = table.split(full);
        // Every LIMIT that holds, with the share of the set feed it allows, in the order they are weighed: the binding one is
        // the lowest, the first of equal ones (Condition.binding).
        var conditions = new java.util.ArrayList<Condition.Active>();
        if (feedTotal < demand) conditions.add(new Condition.Active(Condition.FEED_SHORT, Math.max(0, feedTotal / demand)));
        double alphaRoom = 1;
        int fullest = -1;
        for (int k = 0; k < room.length; k++) {
            double made = Arrays.stream(fullProducts[k]).sum();
            if (made > 0 && Math.max(0, room[k]) / made < alphaRoom) {
                alphaRoom = Math.max(0, room[k]) / made;
                fullest = k;
            }
        }
        if (alphaRoom < 1) conditions.add(new Condition.Active(Condition.PRODUCT_FULL, alphaRoom, fullest, ""));
        double duty = -table.condenserWaterPerFeed() * demand;
        if (duty > 0 && condenserRoom < Double.POSITIVE_INFINITY) {
            double alphaCondenser = Math.max(0, condenserRoom) / duty;
            if (alphaCondenser < 1) conditions.add(new Condition.Active(Condition.COOLING_SHORT, alphaCondenser));
        }
        double alphaAir = Math.max(0, airFeed) * seconds / demand;
        if (alphaAir < 1) conditions.add(new Condition.Active(Condition.COOLING_SHORT, alphaAir));
        // The reboiler's heating and each pumparound cooler's cooling (W4): each share is its room over its duty of a full take.
        double heating = table.reboilerPerFeed() * demand;
        if (heating > 0 && rooms.reboiler() < Double.POSITIVE_INFINITY) {
            double alphaHeating = Math.max(0, rooms.reboiler()) / heating;
            if (alphaHeating < 1) conditions.add(new Condition.Active(Condition.HEATING_SHORT, alphaHeating));
        }
        double[] perFeed = table.pumparoundPerFeed();
        for (int j = 0; j < perFeed.length; j++) {
            double cooling = -perFeed[j] * demand, r = rooms.pumparound(j);
            if (!(cooling > 0) || r == Double.POSITIVE_INFINITY) continue;
            double alphaCooling = Math.max(0, r) / cooling;
            if (alphaCooling < 1) conditions.add(new Condition.Active(Condition.PUMPAROUND_SHORT, alphaCooling, j, ""));
        }
        // Flooding (W3.1, decision D2): at one table the worst tray's flood fraction is proportional to the feed taken, so the
        // share that keeps it at its limit is the inverse of its fraction at the set feed.
        var flood = table.flood();
        double atSet = flood.fraction(setpoints.feedRate());
        if (atSet > 1) conditions.add(new Condition.Active(Condition.FLOODING, 1 / atSet, flood.tray(), ""));
        if (ruleA)
            for (int j = 0; j < steam.size(); j++) {
                double want = steamPerFeed[j] * demand;
                if (want > 0 && steam.get(j).total() / want < 1)
                    conditions.add(new Condition.Active(Condition.STEAM_SHORT, steam.get(j).total() / want, j, Condition.RULE_A));
            }
        var binding = Condition.binding(conditions);
        double alpha = Math.max(0, binding == null ? 1 : binding.value());
        double feedTaken = alpha * demand;
        Account takenFeed = feedTotal > 0 ? feed.part(feedTaken / feedTotal) : Account.empty(m);
        var takenSteam = new java.util.ArrayList<Account>();
        double[] pooled = takenFeed.moles();
        double energyIn = takenFeed.energy();
        for (int j = 0; j < steam.size(); j++) {
            double have = steam.get(j).total();
            double want = steamPerFeed[j] * feedTaken;
            // Rule B: a short steam is taken as it is and cuts nothing, so it binds only where nothing else does.
            if (!ruleA && want > have) conditions.add(new Condition.Active(Condition.STEAM_SHORT, 1, j, ""));
            var t = have > 0 ? steam.get(j).part(Math.min(want, have) / have) : Account.empty(m);
            takenSteam.add(t);
            double[] n = t.moles();
            for (int i = 0; i < m; i++) pooled[i] += n[i];
            energyIn += t.energy();
        }
        double[][] split = table.split(pooled);
        var products = new java.util.ArrayList<Account>();
        double out = 0;
        for (int k = 0; k < split.length; k++) {
            double e = Arrays.stream(split[k]).sum() > 0 ? energy.energy(k, split[k]) : 0;
            products.add(new Account(split[k], e));
            out += e;
        }
        double fed = takenFeed.total();
        double reboiler = table.reboilerPerFeed() * fed;
        double[] pa = table.pumparoundPerFeed();
        double paTotal = 0;
        for (int j = 0; j < pa.length; j++) {
            pa[j] *= fed;
            paTotal += pa[j];
        }
        double condenser = out - energyIn - reboiler - paTotal;
        double solved = table.condenserPerFeed() * fed;
        double departure = solved == 0 ? 0 : (condenser - solved) / Math.abs(solved);
        Condition.Active trip = null;
        if (fed > 0 && condenser > 0) trip = new Condition.Active(Condition.CLOSURE, departure, -1, "the condenser would heat the overhead");
        else if (fed > 0 && solved != 0 && Math.abs(condenser - solved) > TRIP_DEPARTURE * Math.abs(solved))
            trip = new Condition.Active(Condition.CLOSURE, departure, -1,
                    String.format(java.util.Locale.ROOT, "the condenser duty departs %.0f %% from the solved one", 100 * Math.abs(condenser - solved) / Math.abs(solved)));
        if (trip != null) return idle(table, steam.size(), List.of(trip));
        // A sign before the limit binds (W3.2): the worst tray near its flood at the feed taken.
        double loaded = flood.fraction(fed / seconds);
        if (loaded >= Condition.HIGH_LOAD_FRACTION)
            conditions.add(new Condition.Active(Condition.HIGH_LOAD, loaded, flood.tray(),
                    String.format(java.util.Locale.ROOT, "tray %d at %.0f %% of flood", flood.tray() + 1, 100 * Math.min(loaded, 1))));
        return new Step(alpha, takenFeed, takenSteam, products, reboiler, pa, condenser, solved, conditions);
    }

    private static Step idle(SplitTable table, int steams, List<Condition.Active> conditions) {
        int m = table.axis().size();
        var empty = Account.empty(m);
        return new Step(0, empty, java.util.Collections.nCopies(steams, empty), java.util.Collections.nCopies(table.products().size(), empty), 0,
                new double[table.pumparoundPerFeed().length], 0, 0, conditions);
    }
}
