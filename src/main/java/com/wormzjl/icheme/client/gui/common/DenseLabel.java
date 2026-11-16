package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * What a player reads on one dense fluid (S5 of batch 2026-11-15-supercritical-fluids, owner 2026-11-16, decision 1): one
 * fluid on a supercritical isotherm of its own composition ({@link FluidThermodynamics.State#dense()}) above reduced density
 * {@link FluidThermodynamics#GAS_LIKE_UNTIL} reads "Dense fluid", in whichever slot it sits; at or below it, it reads as
 * the gas it is (nitrogen at 300 K and 1 bar, reduced density 0.004). The network's slot rule moves such a fluid between the
 * vapour and the liquid slot at reduced density 1, where nothing physical happens; no word follows that slot.
 *
 * <p>Presentation only. On a state the model evaluated (the simulator's streams) the rule reads its traits. On the device
 * screen, whose view carries amounts and no traits (no wire field: one protocol number, {@code icheme-1}), the client
 * evaluates the one fluid's root once per published snapshot on its own catalog ({@link #row(FluidThermodynamics, List,
 * double, double, double[], double[][])}): one cubic root, no flash, no solver, no process state. A dedicated server whose
 * datapack overrides a compound can differ from the client's data; where the client lacks a compound or the root is
 * refused, the label is today's. No Minecraft class.</p>
 */
public final class DenseLabel {
    private DenseLabel() {
    }

    /** Whether {@code state} reads "Dense fluid": one dense fluid whose liquid-like weight is above 0 (exactly above reduced density 0.5). */
    public static boolean dense(@Nullable FluidThermodynamics.State state) {
        return state != null && state.dense() && FluidThermodynamics.liquidLikeWeight(state) > 0;
    }

    /**
     * The view's phase row that holds the dense fluid, in the order of the device screen's and the simulator's shares (0 the
     * oil, 1 the aqueous liquid, 2 the gas, by label: {@code FluidView.State.from}); -1 where {@code state} does not read
     * "Dense fluid".
     */
    public static int row(@Nullable FluidThermodynamics.State state) {
        if (!dense(state)) return -1;
        int slot = state.oneFluidSlot();
        if (slot == FluidThermodynamics.State.VAPOR) return 2;
        if (slot == state.slot(FluidThermodynamics.OIL)) return 0;
        return slot == state.slot(FluidThermodynamics.AQUEOUS) ? 1 : -1;
    }

    /**
     * The row (0, 1, 2) of a view's phases that holds one dense fluid above reduced density 0.5, evaluated on {@code model}:
     * exactly one row holds fluid ({@code volumes}), and the untranslated cubic's one root for its amounts at {@code t} and
     * {@code p} lies on a supercritical isotherm above that density. -1 for any other view, and where the model cannot say
     * (no model, a compound of the view's amounts that {@code model} does not have, a refused root): today's label.
     *
     * @param axis    the view's component ids, in the order of {@code moles}' rows
     * @param volumes the view's phase volumes, 3 rows
     * @param moles   the view's phase amounts, 3 rows on {@code axis}
     */
    public static int row(@Nullable FluidThermodynamics model, List<String> axis, double t, double p, double[] volumes, double[][] moles) {
        return read(model, axis, t, p, volumes, moles).row();
    }

    /**
     * What the client's evaluation reads of a view's contents: the row that holds one dense fluid ({@link #row(FluidThermodynamics,
     * List, double, double, double[], double[][])}) and its liquid-like weight {@code w} (the server's
     * {@link FluidThermodynamics#pumpLiquidLikeWeight} of the same root's reduced density, {@link #weight(double)}); {@link #NONE}
     * for contents that do not read "Dense fluid". P2 of batch 2026-11-16-supercritical-followups: the "Dense" share's colour.
     */
    public record Reading(int row, double weight) {
        public static final Reading NONE = new Reading(-1, Double.NaN);
    }

    /** The row and weight of a view's contents on {@code model} ({@link Reading}); the same one cubic root as {@link #row}. */
    public static Reading read(@Nullable FluidThermodynamics model, List<String> axis, double t, double p, double[] volumes, double[][] moles) {
        if (model == null || volumes == null || volumes.length != 3 || moles == null || moles.length != 3 || !(t > 0) || !(p > 0)) return Reading.NONE;
        int row = -1;
        for (int i = 0; i < 3; i++) {
            if (!(volumes[i] > 0)) continue;
            if (row >= 0) return Reading.NONE;
            row = i;
        }
        if (row < 0 || moles[row].length != axis.size()) return Reading.NONE;
        var components = model.components();
        double[] amounts = new double[components.size()];
        double total = 0;
        for (int c = 0; c < axis.size(); c++) {
            double n = moles[row][c];
            if (!(n > 0)) continue;
            int at = components.indexOf(axis.get(c));
            if (at < 0) return Reading.NONE;
            amounts[at] = n;
            total += n;
        }
        if (!(total > 0)) return Reading.NONE;
        try {
            var traits = model.hydrocarbon.phase(t, p, amounts, PengRobinsonKernel.Root.VAPOR).traits();
            return traits != null && traits.supercriticalIsotherm() && traits.reducedDensity() > FluidThermodynamics.GAS_LIKE_UNTIL
                    ? new Reading(row, weight(traits.reducedDensity())) : Reading.NONE;
        } catch (IllegalArgumentException | ArithmeticException refused) {
            return Reading.NONE;
        }
    }

    /**
     * The liquid-like weight of one fluid at reduced density {@code reducedDensity}: the server's
     * {@link FluidThermodynamics#pumpLiquidLikeWeight} law (0 at or below {@link FluidThermodynamics#GAS_LIKE_UNTIL}, 1 from
     * {@link FluidThermodynamics#PUMP_LIQUID_LIKE_FROM}, a smoothstep between) on a density the client evaluated.
     */
    public static double weight(double reducedDensity) {
        return com.wormzjl.icheme.science.fluid.transport.MixtureViscosity.smoothstep((reducedDensity - FluidThermodynamics.GAS_LIKE_UNTIL)
                / (FluidThermodynamics.PUMP_LIQUID_LIKE_FROM - FluidThermodynamics.GAS_LIKE_UNTIL));
    }

    /** The liquid-like weight of a model state that reads "Dense fluid" ({@link #dense}); NaN for any other. */
    public static double weight(@Nullable FluidThermodynamics.State state) {
        return dense(state) ? FluidThermodynamics.pumpLiquidLikeWeight(state) : Double.NaN;
    }

    /** The phases' colours where a "Dense" share is drawn: the vapour's {@code vapour} blended to the liquid's {@code liquid} by {@code w} (ARGB, each channel). */
    public static int blend(int vapour, int liquid, double w) {
        double s = w > 0 ? Math.min(w, 1) : 0;
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = vapour >>> shift & 255, b = liquid >>> shift & 255;
            out |= (int) Math.round(a + (b - a) * s) << shift;
        }
        return out;
    }

    /** The client's network model, built once per catalog and kept: {@link #models()} counts the builds. */
    private static MaterialCatalog modelCatalog;
    private static FluidThermodynamics model;
    private static int built;

    /**
     * The network model of {@code catalog} ({@link MaterialRuntime#active()} on the client), built on the first call for that
     * catalog and kept; null where it does not build. A catalog published again (a reload in single player) builds it again.
     */
    public static synchronized @Nullable FluidThermodynamics model(MaterialCatalog catalog) {
        if (catalog != modelCatalog) {
            modelCatalog = catalog;
            built++;
            String network = FluidMaterialCatalog.networkPackage(catalog);
            try {
                model = MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
            } catch (RuntimeException unavailable) {
                model = null;
            }
        }
        return model;
    }

    /** How many network models this client has built for the label (a counter for the review; one per catalog). */
    public static synchronized int models() {
        return built;
    }
}
