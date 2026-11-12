package com.wormzjl.icheme.science.fluid.thermo;

import java.util.Arrays;

/**
 * Test reads of a {@link FluidThermodynamics.State} on its phase accessors (W3b of batch 2026-11-11-aqueous-solvents):
 * water is the last component of every phase, and the liquids sit in two slots labelled by their water share
 * ({@link FluidThermodynamics.State#slot}). The tests written against the hydrocarbon liquid and the free water read
 * the same quantities here: the compounds (every component but water) over the liquids or in the vapour, water per
 * phase, the volume of the OIL and AQUEOUS phases.
 */
public final class TestPhases {
    private TestPhases() {}

    /**
     * The state of the compounds' liquid {@code liquid} and vapour {@code vapor} (the conserved basis less water) with
     * {@code waterLiquid} and {@code waterVapor} mol of water: the compounds' liquid in the liquid slot, the liquid water
     * in the aqueous slot (alone, it is the aqueous phase), the water vapour in the vapour.
     */
    public static FluidThermodynamics.State state(FluidThermodynamics model, double t, double p, double[] liquid, double[] vapor,
                                                  double waterLiquid, double waterVapor) {
        int n = model.componentCount();
        if (liquid.length != n - 1 || vapor.length != n - 1) throw new IllegalArgumentException("Fluid state basis mismatch");
        double[] v = Arrays.copyOf(vapor, n), l = Arrays.copyOf(liquid, n), a = new double[n];
        v[n - 1] = waterVapor;
        a[n - 1] = waterLiquid;
        return model.state(t, p, v, l, a);
    }

    /** {@link #state(FluidThermodynamics, double, double, double[], double[], double, double)} on a prepared temperature. */
    public static FluidThermodynamics.State state(FluidThermodynamics model, double t, double p, double[] liquid, double[] vapor,
                                                  double waterLiquid, double waterVapor, FluidThermodynamics.Prepared prepared) {
        int n = model.componentCount();
        if (liquid.length != n - 1 || vapor.length != n - 1) throw new IllegalArgumentException("Fluid state basis mismatch");
        double[] v = Arrays.copyOf(vapor, n), l = Arrays.copyOf(liquid, n), a = new double[n];
        v[n - 1] = waterVapor;
        a[n - 1] = waterLiquid;
        return model.state(t, p, v, l, a, prepared);
    }

    /**
     * {@code amounts} on {@code model}'s conserved basis (its compounds, then water): itself where it has that length,
     * otherwise the compounds' amounts with no water.
     */
    public static double[] conserved(HydrocarbonModel model, double[] amounts) {
        int n = model.componentCount() + 1;
        if (amounts.length == n) return amounts;
        if (amounts.length != n - 1) throw new IllegalArgumentException("Fluid basis mismatch");
        return Arrays.copyOf(amounts, n);
    }

    /** The compounds (water excluded) over both liquids, mol. */
    public static double[] liquid(FluidThermodynamics.State s) {
        int n = s.componentCount() - 1;
        double[] out = new double[n], l = s.liquidAmountsView(), a = s.aqueousAmountsView();
        for (int i = 0; i < n; i++) out[i] = l[i] + a[i];
        return out;
    }

    /** The OIL phase's amounts on the conserved basis (water last), mol; zeros where there is none. */
    public static double[] oil(FluidThermodynamics.State s) { return s.labelAmountsView(FluidThermodynamics.OIL).clone(); }

    /** The AQUEOUS phase's isothermal compressibility, 1/Pa (0 where there is none). */
    public static double waterCompressibility(FluidThermodynamics.State s) {
        var phase = s.labelPhase(FluidThermodynamics.AQUEOUS);
        return phase == null ? 0 : phase.isothermalCompressibility();
    }

    /** The compounds (water excluded) in the vapour, mol. */
    public static double[] vapor(FluidThermodynamics.State s) {
        return Arrays.copyOf(s.vaporAmountsView(), s.componentCount() - 1);
    }

    /** Water in the vapour, mol. */
    public static double waterVapor(FluidThermodynamics.State s) {
        return s.vaporAmountsView()[s.componentCount() - 1];
    }

    /** Water over both liquids, mol. */
    public static double waterLiquid(FluidThermodynamics.State s) { return s.liquidWater(); }

    /** The volume of the OIL phase, m3. */
    public static double liquidVolume(FluidThermodynamics.State s) { return s.labelVolume(FluidThermodynamics.OIL); }

    /** The volume of the AQUEOUS phase, m3. */
    public static double waterVolume(FluidThermodynamics.State s) { return s.labelVolume(FluidThermodynamics.AQUEOUS); }

    /** The OIL phase's molar properties, null where there is none. */
    public static HydrocarbonModel.Phase liquidProperties(FluidThermodynamics.State s) { return s.labelPhase(FluidThermodynamics.OIL); }

    /** The vapour's molar properties, null where there is none. */
    public static HydrocarbonModel.Phase vaporProperties(FluidThermodynamics.State s) { return s.vaporPhase(); }

    /** The compounds' partial pressure in the vapour, Pa (0 without a vapour). */
    public static double hydrocarbonPartialPressure(FluidThermodynamics.State s) {
        double total = 0;
        for (double v : s.vaporAmountsView()) total += v;
        return total > 0 ? s.pressure() * (total - waterVapor(s)) / total : 0;
    }

    /** Water's partial pressure in the vapour, Pa (0 without a vapour). */
    public static double waterPartialPressure(FluidThermodynamics.State s) {
        double total = 0;
        for (double v : s.vaporAmountsView()) total += v;
        return total > 0 ? s.pressure() * waterVapor(s) / total : 0;
    }

    /** The package's ice crystal (the crystal of water), or null. */
    public static NetworkCrystals.Crystal ice(FluidThermodynamics model) {
        for (var crystal : model.crystalSolids().all()) if (crystal.water()) return crystal;
        return null;
    }
}
