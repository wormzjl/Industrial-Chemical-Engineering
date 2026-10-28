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

    /** What bounded the throughput. */
    public enum Limit {
        NONE, FEED, STEAM, OUTLET
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
     * @param trip             why the column trips (the closure would heat, or departs too far), or null
     */
    public record Step(double alpha, Limit limit, Account feed, List<Account> steam, List<Account> products, double reboiler, double[] pumparounds,
                       double condenser, double solvedCondenser, String trip) {
        public Step {
            steam = List.copyOf(steam);
            products = List.copyOf(products);
            pumparounds = pumparounds.clone();
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
        Objects.requireNonNull(table, "table");
        int m = table.axis().size();
        double[] steamPerFeed = setpoints.steamPerFeed();
        if (steam.size() != steamPerFeed.length) throw new IllegalArgumentException("One steam account per steam setpoint");
        if (room.length != table.products().size()) throw new IllegalArgumentException("One room per product");
        double demand = setpoints.feedRate() * seconds;
        if (!(demand > 0)) return idle(table, steam.size(), Limit.NONE, null);
        // The throughput the outlets allow, from the products of a full take.
        double feedTotal = feed.total();
        double[] full = feedTotal > 0 ? feed.part(demand / feedTotal).moles() : new double[m];
        for (int j = 0; j < steam.size(); j++) {
            double s = steam.get(j).total();
            if (s > 0) {
                double[] add = steam.get(j).part(steamPerFeed[j] * demand / s).moles();
                for (int i = 0; i < m; i++) full[i] += add[i];
            }
        }
        double[][] fullProducts = table.split(full);
        double alphaRoom = 1;
        for (int k = 0; k < room.length; k++) {
            double made = Arrays.stream(fullProducts[k]).sum();
            if (made > 0) alphaRoom = Math.min(alphaRoom, Math.max(0, room[k]) / made);
        }
        double alpha = Math.min(1, feedTotal / demand);
        Limit limit = feedTotal < demand ? Limit.FEED : Limit.NONE;
        if (alphaRoom < alpha) {
            alpha = alphaRoom;
            limit = Limit.OUTLET;
        }
        if (ruleA)
            for (int j = 0; j < steam.size(); j++) {
                double want = steamPerFeed[j] * demand;
                if (want > 0 && steam.get(j).total() / want < alpha) {
                    alpha = steam.get(j).total() / want;
                    limit = Limit.STEAM;
                }
            }
        alpha = Math.max(0, alpha);
        double feedTaken = alpha * demand;
        Account takenFeed = feedTotal > 0 ? feed.part(feedTaken / feedTotal) : Account.empty(m);
        var takenSteam = new java.util.ArrayList<Account>();
        double[] pooled = takenFeed.moles();
        double energyIn = takenFeed.energy();
        for (int j = 0; j < steam.size(); j++) {
            double have = steam.get(j).total();
            double want = steamPerFeed[j] * feedTaken;
            if (!ruleA && want > have && limit == Limit.NONE) limit = Limit.STEAM;
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
        String trip = null;
        if (fed > 0 && condenser > 0) trip = "the condenser would heat the overhead";
        else if (fed > 0 && solved != 0 && Math.abs(condenser - solved) > TRIP_DEPARTURE * Math.abs(solved))
            trip = String.format(java.util.Locale.ROOT, "the condenser duty departs %.0f %% from the solved one", 100 * Math.abs(condenser - solved) / Math.abs(solved));
        if (trip != null) return idle(table, steam.size(), limit, trip);
        return new Step(alpha, limit, takenFeed, takenSteam, products, reboiler, pa, condenser, solved, null);
    }

    private static Step idle(SplitTable table, int steams, Limit limit, String trip) {
        int m = table.axis().size();
        var empty = Account.empty(m);
        return new Step(0, limit, empty, java.util.Collections.nCopies(steams, empty), java.util.Collections.nCopies(table.products().size(), empty), 0,
                new double[table.pumparoundPerFeed().length], 0, 0, trip);
    }
}
