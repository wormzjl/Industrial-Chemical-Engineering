package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import org.jetbrains.annotations.Nullable;

/**
 * One dense fluid in what the server reads off its own state (P2 of batch 2026-11-16-supercritical-followups, items 7, 8 and
 * 16). One fluid on a supercritical isotherm ({@link FluidThermodynamics.State#dense()}) has no interface: its density runs
 * from a gas's to a liquid's, and the network's slot rule moves it from the vapour slot to a liquid slot at reduced density 1,
 * where nothing physical happens. A reading that follows the slot flips there. Each reading here weighs the fluid by its
 * liquid-like weight {@code w} ({@link FluidThermodynamics#pumpLiquidLikeWeight}: 0 at or below reduced density 0.5, 1 from 1,
 * a smoothstep between), so it is the gas's reading at or below 0.5, continuous at 1, and the liquid slot's from 1.
 *
 * <p>Arithmetic on committed states the presentation already holds: no solve, no flash, no model build. A state that is not
 * one dense fluid gives NaN, and every reader keeps its old arithmetic for it, to the bit.</p>
 */
public final class DenseReadings {
    private DenseReadings() {
    }

    /** {@code w} of {@code state} where it is one dense fluid ({@link FluidThermodynamics.State#dense()}); NaN for any other state and for null. */
    public static double weight(@Nullable FluidThermodynamics.State state) {
        return state != null && state.dense() ? FluidThermodynamics.pumpLiquidLikeWeight(state) : Double.NaN;
    }

    /** {@code w} of node {@code node} of {@code graph} ({@link #weight}); NaN for an empty vessel or an index outside the graph. */
    public static double weight(PassiveNetwork graph, int node) {
        if (node < 0 || node >= graph.reservoirs().size()) return Double.NaN;
        var reservoir = graph.reservoirs().get(node);
        return reservoir.empty() ? Double.NaN : weight(reservoir.state());
    }

    /**
     * The volume a level reads, m³: both liquids and the solids, as before, plus {@code w} times the vapour row's volume where
     * {@code w} ({@link #weight}) is above 0. One dense fluid then reads 0 at or below reduced density 0.5, rises with its
     * density and reads full from 1, where the liquid slot holds it (and {@code w} is 1 on an empty vapour row). For any other
     * contents ({@code w} NaN or 0) the sum is the old one, to the bit.
     */
    public static double levelVolume(FluidView.State state, double w) {
        double[] v = state.phaseVolumes();
        double condensed = v[0] + v[1] + state.solids().volume();
        return w > 0 ? condensed + w * v[2] : condensed;
    }

    /**
     * The moved stream's {@code w} over pipe {@code pipe}'s transfer {@code moved}: its forward stream left the pipe's first
     * node and its reverse stream the second; the two weighted by their masses. NaN where a side that carried mass left a node
     * that is not one dense fluid, or nothing moved. {@code sums} gathers {@code [Σ m w, Σ m, refused]} over several pipes.
     */
    static void add(PassiveNetwork graph, PassiveNetwork.Pipe pipe, PipeTransfer moved, double[] sums) {
        side(graph, pipe.first(), moved.forward(), sums);
        side(graph, pipe.second(), moved.reverse(), sums);
    }

    private static void side(PassiveNetwork graph, int node, PipeTransfer.Stream stream, double[] sums) {
        if (!(stream.massKg() > 0)) return;
        double w = weight(graph, node);
        if (!(w >= 0)) sums[2] = 1;
        else {
            sums[0] += stream.massKg() * w;
            sums[1] += stream.massKg();
        }
    }

    /** The weight {@code sums} ({@link #add}) gathered: NaN where a side refused or nothing moved. */
    static double moved(double[] sums) {
        return sums[2] == 0 && sums[1] > 0 ? Math.clamp(sums[0] / sums[1], 0, 1) : Double.NaN;
    }

    /**
     * What a burst gives the air and the ground of {@code held} moles of one dense fluid of weight {@code w}: the gas-like share
     * {@code 1 − w} as gas and the liquid-like share {@code w} as liquid, whatever slot holds it, added to {@code gas} and
     * {@code liquid}. The liquid then goes by its volatile share as any spill's ({@code Releases.contents}).
     */
    static void split(double held, double w, int compound, double[] gas, double[] liquid) {
        double l = held * w;
        gas[compound] += held - l;
        liquid[compound] += l;
    }

    /**
     * A vessel's held moles {@code moles} (its inventory, the world axis) split for a burst into {@code gas} and {@code liquid}
     * (written, not added): by its state's phases per compound, as before; one dense fluid by its weight instead
     * ({@link #split(double, double, int, double[], double[])}), its liquid-like part also written to {@code dense}.
     * {@code state} null is an empty vessel's.
     */
    static void release(double[] moles, @Nullable FluidThermodynamics.State state, double[] gas, double[] liquid, double[] dense) {
        double w = weight(state);
        double[] v = state == null ? null : state.vaporAmountsView(), l = state == null ? null : state.liquidAmountsView(), a = state == null ? null : state.aqueousAmountsView();
        for (int c = 0; c < Math.min(gas.length, moles.length); c++) {
            double held = Math.max(0, moles[c]);
            if (w >= 0) {
                gas[c] = 0;
                liquid[c] = 0;
                split(held, w, c, gas, liquid);
                dense[c] = liquid[c];
                continue;
            }
            double all = v == null ? 0 : v[c] + l[c] + a[c], share = all > 0 ? Math.clamp(v[c] / all, 0, 1) : 1;
            gas[c] = held * share;
            liquid[c] = held - gas[c];
        }
    }

    /**
     * One side {@code phases} (a moved stream's three rows) of a line's holdup, times {@code scale}, added to a burst's
     * {@code gas} and {@code liquid}: its gas row as gas and both liquid rows as liquid, as before; where the node it left is one
     * dense fluid of weight {@code w}, the three rows pooled and split by {@code w}, the liquid-like part also added to {@code dense}.
     */
    static void moved(double[][] phases, double scale, double w, double[] gas, double[] liquid, double[] dense) {
        for (int c = 0; c < Math.min(gas.length, phases[2].length); c++) {
            if (w >= 0) {
                double held = (phases[0][c] + phases[1][c] + phases[2][c]) * scale, l = held * w;
                gas[c] += held - l;
                liquid[c] += l;
                dense[c] += l;
                continue;
            }
            gas[c] += phases[2][c] * scale;
            liquid[c] += (phases[0][c] + phases[1][c]) * scale;
        }
    }

    /**
     * The masses a burst is heard by, kg ({@code Releases.Spilled}): the gas, and the liquid, from moles {@code gas} and
     * {@code liquid} at molar masses {@code weights}; {@code flashed} (the volatile share of a dense fluid's liquid-like part,
     * {@code Releases.volatileMoles}) moves from the liquid to the gas, since it reaches the air. With no dense fluid
     * ({@code flashed} zeros) the masses are the old ones.
     */
    static Releases.Spilled heard(double[] gas, double[] liquid, double[] flashed, double[] weights) {
        double gasMass = 0, liquidMass = 0, air = 0;
        for (int c = 0; c < Math.min(gas.length, weights.length); c++) {
            gasMass += gas[c] * weights[c];
            liquidMass += liquid[c] * weights[c];
            air += flashed[c] * weights[c];
        }
        return new Releases.Spilled(gasMass + air, liquidMass - air);
    }
}
